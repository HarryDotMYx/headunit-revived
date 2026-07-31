package com.andrerinas.headunitrevived.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.dx.DexMaker
import com.android.dx.TypeId
import java.lang.reflect.Method
import com.andrerinas.headunitrevived.utils.SoftApConfigCompat

/**
 * Manages WiFi Hotspot (tethering) using reflection + dexmaker.
 */
object HotspotManager {
    private const val TAG = "HUREV_WIFI"
    private const val CALLBACK_CLASS = "android.net.ConnectivityManager\$OnStartTetheringCallback"
    private const val START_CALLBACK_CLASS = "android.net.TetheringManager\$StartTetheringCallback"

    private var cachedCallbackClass: Class<*>? = null

    // [FIX] Callers (AapService) used to spawn a raw Thread{} per enable/disable call. Two calls
    // issued close together — e.g. rapid Strategy switching between WiFi Direct and Headunit
    // Hotspot, or initWifiMode() re-entering while a previous call is still mid-flight (each call
    // can take 500ms+ for the WiFi radio to settle) — could then run concurrently and finish out
    // of order, leaving SoftAP running at the same time as WiFi Direct: exactly the conflict this
    // subsystem exists to prevent. A single-thread executor makes every call strictly serialized
    // in issue order, and lets callers stay fire-and-forget without blocking their own thread.
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun setHotspotEnabledAsync(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        executor.execute {
            setHotspotEnabled(appContext, enabled)
        }
    }

    fun setHotspotEnabled(context: Context, enabled: Boolean): Boolean {
        AppLog.i("HotspotManager: Setting hotspot enabled=$enabled (API ${Build.VERSION.SDK_INT})")

        // On Android 8+, WiFi must be disabled before tethering can start
        var disabledWifi = false
        if (enabled) {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (wm.isWifiEnabled) {
                    AppLog.i("HotspotManager: Disabling WiFi before enabling hotspot...")
                    wm.isWifiEnabled = false
                    disabledWifi = true
                    Thread.sleep(500) // Let the radio settle
                }
            } catch (e: Exception) {
                AppLog.w("HotspotManager: Failed to disable WiFi: ${e.message}")
            }
        }

        // [FIX] SoftApConfigCompat.enableHotspot only *configures* the SSID/password via
        // WifiManager#setSoftApConfiguration — it never actually starts the AP. Its result used
        // to short-circuit this function with `return true`, so on the privileged/system-signed
        // builds this app targets, the hotspot got configured but tryTetheringManager() (the
        // call that actually starts it) was skipped entirely: "Headunit Hotspot" strategy silently
        // never brought up a hotspot. Apply the config best-effort, then always continue on to
        // actually starting it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SoftApConfigCompat.enableHotspot(context, enabled)
        }
        // Newer API: TetheringManager (official) before ConnectivityManager fallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (tryTetheringManager(context, enabled)) return true
        }
        if (tryConnectivityManager(context, enabled)) return true
        if (tryLegacyWifiManager(context, enabled)) return true

        AppLog.w("HotspotManager: All hotspot attempts failed.")
        if (disabledWifi) {
            // [FIX] WiFi was switched off above to make room for the hotspot; if every attempt
            // to actually start the hotspot then failed, the device was left with neither WiFi
            // nor a hotspot — total connectivity loss until the user noticed and manually
            // re-enabled WiFi. Restore it since we're the ones who turned it off.
            AppLog.i("HotspotManager: Restoring WiFi since the hotspot could not be started...")
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wm.isWifiEnabled = true
            } catch (e: Exception) {
                AppLog.w("HotspotManager: Failed to restore WiFi: ${e.message}")
            }
        }
        return false
    }

    private fun tryConnectivityManager(context: Context, enabled: Boolean): Boolean {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (!enabled) {
                val stopMethod = cm.javaClass.methods.find { it.name == "stopTethering" }
                if (stopMethod != null) {
                    stopMethod.isAccessible = true
                    stopMethod.invoke(cm, 0)
                    return true
                }
                return false
            }

            val startMethod = cm.javaClass.methods.find {
                it.name == "startTethering" && it.parameterTypes.size >= 4
            } ?: return false

            startMethod.isAccessible = true
            val callbackInst = createTetheringCallback(context)
            val handler = Handler(Looper.getMainLooper())

            return when (startMethod.parameterTypes.size) {
                4 -> {
                    startMethod.invoke(cm, 0, false, callbackInst, handler)
                    true
                }
                5 -> {
                    startMethod.invoke(cm, 0, false, callbackInst, handler, context.packageName)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            AppLog.e("HotspotManager: CM path failed", e)
            return false
        }
    }

    // [FIX] createTetheringCallback() lazily generates and caches a dexmaker subclass the first
    // time it's called. setHotspotEnabled() can now run concurrently with itself only via the
    // single-thread executor above, but this method is also reachable directly in tests/future
    // callers — synchronized so two concurrent callers can't both miss the cache and run the
    // (non-trivial, not obviously thread-safe) DexMaker codegen path at the same time.
    @Synchronized
    @Suppress("UNCHECKED_CAST")
    private fun createTetheringCallback(context: Context): Any? {
        try {
            cachedCallbackClass?.let { cls ->
                return cls.getDeclaredConstructor().newInstance()
            }

            val parentClass = Class.forName(CALLBACK_CLASS) ?: return null
            val dexMaker = DexMaker()
            val getByName: Method = TypeId::class.java.getDeclaredMethod("get", String::class.java)
            val getByClass: Method = TypeId::class.java.getDeclaredMethod("get", Class::class.java)

            val generatedType = getByName.invoke(null, "LTetheringCallback;") as TypeId<Any>
            val parentType = getByClass.invoke(null, parentClass) as TypeId<Any>

            dexMaker.declare(generatedType, "TetheringCallback.generated", java.lang.reflect.Modifier.PUBLIC, parentType)

            val constructor = generatedType.getConstructor() as com.android.dx.MethodId<Any, Void>
            val parentConstructor = parentType.getConstructor() as com.android.dx.MethodId<Any, Void>
            val code = dexMaker.declare(constructor, java.lang.reflect.Modifier.PUBLIC)
            val thisRef = code.getThis(generatedType)
            code.invokeDirect(parentConstructor, null, thisRef)
            code.returnVoid()

            val dexCache = context.codeCacheDir
            val classLoader = dexMaker.generateAndLoad(this.javaClass.classLoader, dexCache)
            val generatedClass = classLoader.loadClass("TetheringCallback")
            cachedCallbackClass = generatedClass

            return generatedClass.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            AppLog.e("HotspotManager: Dexmaker failed", e)
            return null
        }
    }

    private fun tryTetheringManager(context: Context, enabled: Boolean): Boolean {
        try {
            val tm = context.getSystemService("tethering") ?: return false
            if (enabled) {
                // [FIX] TetheringManager has *two* 3-parameter startTethering overloads:
                // (int, Executor, StartTetheringCallback) and, on newer API levels,
                // (TetheringRequest, Executor, StartTetheringCallback). Matching on name+arity
                // alone via Class#getMethods() (unordered) could resolve to either one — if it
                // picked the TetheringRequest overload, invoking it with `0` as the first arg
                // threw IllegalArgumentException and silently fell through to the next fallback
                // path, non-deterministically depending on ART's method ordering. Match the
                // first parameter type too so this always resolves to the (int, ...) overload.
                val startMethod = tm.javaClass.methods.find {
                    it.name == "startTethering" &&
                        it.parameterTypes.size == 3 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType
                } ?: return false
                // [FIX] A null StartTetheringCallback was passed here; some AOSP-fork
                // implementations invoke callback methods without a null check. Provide a real
                // (if minimal) callback via a dynamic proxy — StartTetheringCallback is a plain
                // interface, unlike ConnectivityManager's older abstract-class callback, so no
                // dexmaker codegen is needed for it.
                startMethod.invoke(tm, 0, context.mainExecutor, createNoOpStartTetheringCallback())
                return true
            } else {
                // [FIX] Used to `stopMethod?.invoke(tm, 0); return true` — reporting success even
                // when reflection failed to find stopTethering and nothing was actually called.
                val stopMethod = tm.javaClass.methods.find { it.name == "stopTethering" }
                    ?: return false
                stopMethod.invoke(tm, 0)
                return true
            }
        } catch (e: Exception) {
            AppLog.e("HotspotManager: TetheringManager path failed", e)
            return false
        }
    }

    private fun createNoOpStartTetheringCallback(): Any? {
        return try {
            val callbackClass = Class.forName(START_CALLBACK_CLASS)
            java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                if (method.name == "onTetheringFailed") {
                    val error = (args?.getOrNull(0) as? Int) ?: -1
                    AppLog.w("HotspotManager: TetheringManager reported startTethering failure, error=$error")
                }
                null
            }
        } catch (e: Exception) {
            AppLog.w("HotspotManager: Failed to create StartTetheringCallback proxy: ${e.message}")
            null
        }
    }

    private fun tryLegacyWifiManager(context: Context, enabled: Boolean): Boolean {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            return method.invoke(wm, null, enabled) as Boolean
        } catch (_: Exception) { return false }
    }
}
