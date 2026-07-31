package com.andrerinas.headunitrevived.connection.carkey.fyt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.connection.carkey.CarKeyReceiver
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.SUExecutor
import com.andrerinas.headunitrevived.utils.SystemProperties
import com.andrerinas.headunitrevived.utils.SystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// based of decompilation of "com.syu.steer_HD.apk"
// this mainly adds support for uis7870 (dudu 7, tested)
// although this should also affect all other uis models (uis7862 ...)
class CarFYTReceiver : CarKeyReceiver {

    private var connection: IPCConnection? = null

    override val isSupported: Boolean get() {
        return SystemProperties.exists("ro.fyt.platform") ||
            SystemProperties.exists("syu.fyt.platform")
    }

    override val isSUNeeded = true

    override fun register(context: Context) {
        AppLog.i("CarKeyReceiver: Detected FYT device!")

        val suExecutor = App.provide(context).suExecutor
        this.connection = IPCConnection(context, suExecutor, this)
        connection!!.connect()

        // ask for root early
        Handler(Looper.getMainLooper()).post {
            suExecutor.checkPermissionOnBoot()
        }
    }

    override fun unregister() {
        connection?.disconnect()
        connection = null
    }


    // reference: com.syu.ipcself.Conn, com.syu.steer.ipc.Ipc_NewNotifyPage
    private class IPCConnection(
        val context: Context,
        val suExecutor: SUExecutor,
        val receiver: CarFYTReceiver) : ServiceConnection {

        private val PACKAGE_NAME = "com.syu.ms"
        private val CLASS_NAME = "app.ToolkitService"

        // [FIX] connect()/attemptConnect() run on the "ConnectionThread" HandlerThread while
        // disconnect() can be called from another thread (e.g. main) and nulls handler/toolkit
        // directly. Without @Volatile, attemptConnect() running concurrently on the other thread
        // isn't guaranteed to observe that write promptly, and worse, disconnect() nulling
        // `handler` in the gap between attemptConnect()'s null-check and its `handler!!.postDelayed`
        // use below could NPE-crash the process. Made volatile and attemptConnect() now snapshots
        // handler into a local val instead of re-reading the field with `!!`.
        @Volatile private var handler: Handler? = null
        @Volatile private var toolkit: RemoteToolkit? = null
        private val modules = HashMap<Int, RemoteModule>()
        @Volatile private var isBinding = false

        // [FIX] observe() registers an AAPCallback with the remote FYT ToolkitService via
        // module.register(callback, code, 1) but nothing ever called the matching unregister
        // (module.register(callback, code, 0)). Every onServiceConnected (including automatic
        // rebinds after the FYT service restarts mid-drive) called observe() again and registered
        // a brand new callback on top of the old one, so a single steering-wheel press could fire
        // multiple stale callbacks at once — key events duplicating/multiplying across
        // connect/disconnect cycles. Track what's been registered so disconnect() can undo it.
        private val registeredObservations = mutableListOf<Triple<RemoteModule, ModuleCallback.Stub, Int>>()

        fun connect() {
            if (this.handler != null)
                return

            // create separate thread
            val thread = HandlerThread("ConnectionThread")
            thread.start()

            handler = Handler(thread.looper)

            // initiate on new thread
            handler!!.post(this::attemptConnect)
        }

        private fun attemptConnect() {
            val currentHandler = this.handler ?: return
            if (this.toolkit != null || isBinding)
                return;

            val intent = Intent()
            intent.setClassName(PACKAGE_NAME, CLASS_NAME)

            if (context.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
                isBinding = true
            } else {
                currentHandler.postDelayed(this::attemptConnect, 2000)
            }
        }

        fun disconnect() {
            if (this.handler == null)
                return

            // [FIX] see registeredObservations declaration — tell the remote service to stop
            // calling back into every callback we registered before tearing down the connection.
            for ((module, callback, code) in registeredObservations) {
                try {
                    module.register(callback, code, 0)
                } catch (e: Exception) {
                    // Remote service may already be dead (DeadObjectException) — nothing to
                    // clean up on its side in that case, and there's no local state to leak.
                }
            }
            registeredObservations.clear()

            this.handler!!.removeCallbacksAndMessages(null)
            this.handler!!.looper.quit()
            try {
                context.unbindService(this)
            } catch (e: IllegalArgumentException) {
                // Ignore if not registered/bound
            }
            this.isBinding = false
            this.handler = null
            this.toolkit = null
            this.modules.clear()
            suExecutor.setProp("sys.carlink.type", "0") // removes key focus from app
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            AppLog.i("CarKeyReceiver: Connected with FYT Service")
            isBinding = false

            try {
                this.toolkit = RemoteToolkit.Stub.asInterface(binder)

                if (!observe(0, intArrayOf(133))) {
                    AppLog.w("CarKeyReceiver: Failed to bind into module 0. Not observing keys")
                    return
                }

                // obtain key focus
                suExecutor.setProp("sys.carlink.type", "2")
            } catch (e: Exception) {
                // [FIX] observe() -> getRemoteModule()/register() are binder IPC calls that can
                // throw RemoteException/DeadObjectException if the FYT ToolkitService dies right
                // after binding. This was previously unguarded — an uncaught exception here
                // propagates out of onServiceConnected (a framework binder-thread callback,
                // never itself wrapped in a try/catch), crashing the whole process on FYT head
                // units. Recover the same way the bind-failure path already does: retry.
                AppLog.e("CarKeyReceiver: FYT service died during observe() setup, retrying connection", e)
                this.toolkit = null
                handler?.postDelayed(this::attemptConnect, 2000)
            }
        }

        fun observe(moduleCode: Int, codes: IntArray): Boolean {
            val module = this.toolkit!!.getRemoteModule(moduleCode) ?: return false
            modules[moduleCode] = module
            val callback = AAPCallback(moduleCode)

            for (code in codes) {
                module.register(
                    callback,
                    code,
                    1, /* 1 = register, 0 = unregister */
                )
                registeredObservations.add(Triple(module, callback, code))
            }

            return true
        }

        override fun onServiceDisconnected(p0: ComponentName) {
            AppLog.i("CarKeyReceiver: Disconnected from FYT Service")
            isBinding = false
            this.toolkit = null
            // [FIX] The binder is already dead, so these registrations are moot on the remote
            // side too — drop them so a later disconnect() doesn't attempt no-op IPC calls
            // against a dead RemoteModule, and so this list can't grow unbounded across repeated
            // onServiceDisconnected -> automatic onServiceConnected rebind cycles.
            this.modules.clear()
            registeredObservations.clear()
        }

        private inner class AAPCallback(val moduleCode: Int) :
            ModuleCallback.Stub() {

            override fun update(
                updateCode: Int,
                ints: IntArray?,
                floats: FloatArray?,
                strings: Array<String>?,
            ) {

                if (moduleCode == 0) {
                    when (updateCode) {
                        133 -> {
                            if (ints == null || ints.isEmpty())
                                return

                            val key = ints[0]
                            AppLog.i("CarKeyReceiver: Clicked key $key")

                            // assistant
                            if (key == 576) {
                                receiver.toggleVoiceAssistant(context)
                                return
                            }

                            receiver.handleClick(context, key)
                        }
                    }
                }
            }
        }
    }
}
