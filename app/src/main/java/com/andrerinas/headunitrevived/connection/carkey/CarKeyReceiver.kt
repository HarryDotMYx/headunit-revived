package com.andrerinas.headunitrevived.connection.carkey

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.connection.carkey.fyt.CarFYTReceiver
import com.andrerinas.headunitrevived.contract.KeyIntent
import com.andrerinas.headunitrevived.utils.AppLog

interface CarKeyReceiver {

    companion object {
        @JvmStatic
        fun newDefaultReceivers(): Array<CarKeyReceiver> {
            return arrayOf(
                CarKeyBroadcastReceiver(),
                CarFYTReceiver(),
            )
        }

        // [FIX] CarKeyBroadcastReceiver is registered RECEIVER_EXPORTED with no permission for
        // 17 proprietary OEM actions, and every extraction site only checked the extra was
        // present (!= -1) before it reached here — a zero-permission app could broadcast any of
        // those actions with an arbitrary keycode and get it forwarded straight to the live AA
        // session (KEYCODE_DPAD_* to navigate/activate menu items, KEYCODE_CALL/ENDCALL to
        // hang up or place calls, etc.), same vulnerability class already fixed for
        // RemoteControlReceiver's raw-keycode escape hatch. Fixed at this single shared sink
        // (not each of the 5+ call sites in CarKeyBroadcastReceiver/CarFYTReceiver) so it can't
        // be missed by a future extraction path.
        //
        // A physical steering-wheel button the user has actually learned via KeymapFragment
        // (i.e. its raw keycode is a value in settings.keyCodes) is trusted — that requires the
        // user to have pressed the real button once. Anything not yet learned only passes for
        // the same small set of safe media-transport keycodes RemoteControlReceiver allows.
        private val SAFE_UNLEARNED_KEYCODES = setOf(
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_SEARCH
        )

        // Never forwarded, even if somehow present in a learned mapping — no legitimate car-key
        // remap needs to inject these, and misfiring one has outsized real-world consequences.
        private val DENYLISTED_KEYCODES = setOf(
            KeyEvent.KEYCODE_CALL,
            KeyEvent.KEYCODE_ENDCALL,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_SLEEP,
            KeyEvent.KEYCODE_WAKEUP
        )

        // internal (not private): AapProjectionActivity.onKeyEvent(keyCode, isPress) is the
        // *other* place a CarKeyReceiver-originated broadcast reaches commManager.sendKey() —
        // via the same KeyIntent.action broadcast this class sends, received independently by
        // that activity whenever it's alive. Both call sites must apply this same check.
        internal fun isKeyCodeAllowed(context: Context, keyCode: Int): Boolean {
            if (keyCode in DENYLISTED_KEYCODES) return false
            val settings = App.provide(context).settings
            return keyCode in settings.keyCodes.values || keyCode in SAFE_UNLEARNED_KEYCODES
        }
    }

    val isSupported: Boolean

    val isSUNeeded: Boolean

    @Throws(Exception::class)
    fun register(context: Context)

    @Throws(Exception::class)
    fun unregister()


    /** Single key press or release — broadcasts for learning and projection handling. */

    private fun handleKey(context: Context, commManager: CommManager, keyCode: Int, isDown: Boolean) {
        AppLog.d("CarKeyReceiver: Broadcasting key event: code=$keyCode, isDown=$isDown")
        // This broadcast is what KeymapFragment listens to in order to *learn* a brand new,
        // not-yet-mapped physical keycode (see its keyCodeReceiver -> onKeyEvent ->
        // codesMap[assignTargetCode] = keyCode) — it must stay unconditional or users could
        // never map a new steering-wheel button again. It's safe to leave unconditional: the
        // broadcast is package-scoped (setPackage below) and every internal receiver of it is
        // RECEIVER_NOT_EXPORTED, so no other app can ever observe it regardless of validation
        // here. What actually needs gating is the line below that controls the live session.
        context.sendBroadcast(
            Intent(KeyIntent.action).apply {
                setPackage(context.packageName)
                putExtra(
                    KeyIntent.extraEvent,
                    KeyEvent(
                        if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode,
                    ),
                )
            },
        )
        if (!isKeyCodeAllowed(context, keyCode)) {
            AppLog.w("CarKeyReceiver: Not forwarding unrecognized/unsafe raw keycode=$keyCode to the active session (not learned via Keymap and not in the safe set)")
            return
        }
        commManager.sendKey(keyCode, isDown)
    }

    fun handleKey(context: Context, keyCode: Int, isDown: Boolean) {
        handleKey(context, App.provide(context).commManager, keyCode, isDown)
    }

    /** Full click (DOWN + UP) — broadcasts both events for learning AND sends to AA. */
    private fun handleClick(context: Context, commManager: CommManager, keyCode: Int) {
        handleKey(context, commManager, keyCode, true)
        handleKey(context, commManager, keyCode, false)
    }

    fun handleClick(context: Context, keyCode: Int) {
        handleClick(context, App.provide(context).commManager, keyCode)
    }

    fun toggleVoiceAssistant(context: Context) {
        App.provide(context).commManager.sendToggleVoiceAssistant()
    }
}
