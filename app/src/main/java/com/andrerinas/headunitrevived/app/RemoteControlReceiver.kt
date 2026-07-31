package com.andrerinas.headunitrevived.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.utils.AppLog

class RemoteControlReceiver : BroadcastReceiver() {

    companion object {
        // Whitelist of media-transport keycodes safe to accept from the raw
        // "keycode"/"key_code" int-extra escape hatch. Deliberately excludes
        // anything that could affect navigation, calls, power, or system UI.
        private val ALLOWED_RAW_KEYCODES = setOf(
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_SEARCH
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLog.i("RemoteControlReceiver received: $action")

        // Broadcast for UI debugging (KeymapFragment)
        // [FIX] This receiver is exported with unprotected actions, so intent.extras can carry
        // a Parcelable extra of a class that doesn't exist in our classloader — putExtras()
        // forces an eager unparcel and throws BadParcelableException on API <= 33, uncaught,
        // crashing this process (and the live AA session with it) from a zero-permission app.
        // This is a best-effort debug broadcast; skip it on failure rather than let it take
        // down onReceive.
        try {
            val debugIntent = Intent("com.andrerinas.headunitrevived.DEBUG_KEY").apply {
                putExtra("action", action)
                intent.extras?.let { putExtras(it) }
                setPackage(context.packageName)
            }
            context.sendBroadcast(debugIntent)
        } catch (e: Exception) {
            AppLog.w("RemoteControlReceiver: Failed to relay debug broadcast: ${e.message}")
        }

        if (Intent.ACTION_MEDIA_BUTTON == action) {
            val event = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            
            event?.let {
                AppLog.i("ACTION_MEDIA_BUTTON: " + it.keyCode + " (handled via MediaSession)")
                // We no longer send to commManager here to prevent double-skips.
                // The active MediaSession in AapService handles ACTION_MEDIA_BUTTON.

                // Also broadcast for the UI
                val keyIntent = Intent(com.andrerinas.headunitrevived.contract.KeyIntent.action).apply {
                    putExtra(com.andrerinas.headunitrevived.contract.KeyIntent.extraEvent, it)
                    setPackage(context.packageName)
                }
                context.sendBroadcast(keyIntent)
            }
        } else {
            // Handle command-based intents (common on many Android Headunits)
            val command = intent.getStringExtra("command") ?: intent.getStringExtra("action") ?: intent.getStringExtra("action_command")

            // Broadcast command for UI debug (if not already handled by ACTION_MEDIA_BUTTON block)
            if (action != Intent.ACTION_MEDIA_BUTTON) {
                try {
                    val debugIntent = Intent("com.andrerinas.headunitrevived.DEBUG_KEY").apply {
                        putExtra("action", action)
                        putExtra("command", command)
                        intent.extras?.let { putExtras(it) }
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(debugIntent)
                } catch (e: Exception) {
                    AppLog.w("RemoteControlReceiver: Failed to relay debug broadcast: ${e.message}")
                }
            }

            val commManager = App.provide(context).commManager
            if (commManager.connectionState.value !is CommManager.ConnectionState.TransportStarted) {
                AppLog.i("RemoteControlReceiver: Transport not started, skipping command execution")
                return
            }

            when (command) {
                "next", "skip_next", "skip", "forward", "skip_forward" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_NEXT, true)
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_NEXT, false)
                }
                "previous", "skip_previous", "prev", "rewind", "back", "skip_back", "skip_backward" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true)
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, false)
                }
                "play", "start", "resume" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, true)
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY, false)
                }
                "pause", "stop" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE, true)
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE, false)
                }
                "togglepause", "playpause", "play_pause", "media_play_pause" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true)
                    commManager.sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, false)
                }
                "mute", "volume_mute" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_VOLUME_MUTE, true)
                    commManager.sendKey(KeyEvent.KEYCODE_VOLUME_MUTE, false)
                }
                "voice", "mic", "microphone", "search" -> {
                    commManager.sendKey(KeyEvent.KEYCODE_SEARCH, true)
                    commManager.sendKey(KeyEvent.KEYCODE_SEARCH, false)
                }
                else -> {
                    // Some headunits send a raw keycode as an int extra. This receiver is
                    // exported with unprotected broadcast actions, so any app on the device
                    // can reach this path — only forward keycodes that are safe media
                    // transport controls, never an arbitrary attacker-chosen KeyEvent code.
                    val extraKeyCode = intent.getIntExtra("keycode", -1)
                        .takeIf { it > 0 }
                        ?: intent.getIntExtra("key_code", -1).takeIf { it > 0 }
                    if (extraKeyCode != null && extraKeyCode in ALLOWED_RAW_KEYCODES) {
                        AppLog.i("RemoteControlReceiver: raw keycode=$extraKeyCode from command=$command")
                        commManager.sendKey(extraKeyCode, true)
                        commManager.sendKey(extraKeyCode, false)
                    } else {
                        AppLog.i("RemoteControlReceiver: Unknown command='$command' from action='$action'")
                    }
                }
            }
        }
    }
}
