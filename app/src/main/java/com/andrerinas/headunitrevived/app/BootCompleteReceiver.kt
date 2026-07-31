package com.andrerinas.headunitrevived.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import android.os.UserManager
import android.os.Build

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in BOOT_ACTIONS) return

        // [FIX] Only the three standard AOSP actions (BOOT_COMPLETED/LOCKED_BOOT_COMPLETED/
        // USER_UNLOCKED) are protected broadcasts the platform guarantees only the system can
        // send. The OEM "ACC ON"/quickboot actions below them are unofficial, undocumented
        // strings this receiver has no choice but to also listen for to support that hardware —
        // there's no permission or sender-identity check Android exposes here that would
        // distinguish the real OEM firmware from any other zero-permission app sending the same
        // action, without also breaking the OEM firmware's ability to trigger this (it doesn't
        // hold any special permission either). Debouncing at least bounds how often a malicious
        // sender can force-start AapService (each start is a foreground service with a visible
        // notification, so it isn't silent — but it's still an avoidable battery/DoS surface).
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTriggerElapsedMs < TRIGGER_DEBOUNCE_MS) {
            AppLog.w("Boot auto-start: ignoring $action, retriggered within ${TRIGGER_DEBOUNCE_MS}ms of the last one")
            return
        }
        lastTriggerElapsedMs = now

        AppLog.i("Boot auto-start: received action=$action")

        val isLocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && 
                      !(context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked

        if (isLocked) {
            AppLog.w("BootCompleteReceiver: Device is locked. Cannot start AapService yet. Waiting for user unlock.")
            return
        }

        val bootEnabled = Settings.isAutoStartOnBootEnabled(context)
        val screenOnEnabled = Settings.isAutoStartOnScreenOnEnabled(context)
        val usbEnabled = Settings.isAutoStartOnUsbEnabled(context)
        val wifiEnabled = Settings.isAutoStartOnWifiEnabled(context)

        if (bootEnabled) {
            AppLog.i("Boot auto-start: starting AapService with BOOT_START (trigger=$action)")
            val serviceIntent = Intent(context, AapService::class.java).apply {
                putExtra(EXTRA_BOOT_START, true)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } else if (screenOnEnabled) {
            // "Start on screen on" needs the service alive to register its dynamic
            // SCREEN_ON receiver. On Quick Boot devices this is a real reboot, so
            // the service must be started after boot to listen for future SCREEN_ON.
            AppLog.i("Boot auto-start: screen-on auto-start enabled, starting AapService to register SCREEN_ON receiver (trigger=$action)")
            val serviceIntent = Intent(context, AapService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        } else if (usbEnabled) {
            // On hibernating head units, USB_DEVICE_ATTACHED may not fire after wake.
            // Start the service in the background so it can register its UsbReceiver
            // and check for already-connected USB devices.
            AppLog.i("Boot auto-start: USB auto-start enabled, starting AapService to check USB (trigger=$action)")
            val serviceIntent = Intent(context, AapService::class.java).apply {
                this.action = AapService.ACTION_CHECK_USB
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } else if (wifiEnabled) {
            // Start the service to listen for WiFi connectivity changes dynamically.
            AppLog.i("Boot auto-start: WiFi auto-start enabled, starting AapService to listen for WiFi (trigger=$action)")
            val serviceIntent = Intent(context, AapService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            AppLog.i("Boot auto-start: disabled, skipping")
        }
    }

    companion object {
        const val EXTRA_BOOT_START = "com.andrerinas.headunitrevived.EXTRA_BOOT_START"

        @Volatile private var lastTriggerElapsedMs: Long = 0L
        private const val TRIGGER_DEBOUNCE_MS = 10_000L

        private val BOOT_ACTIONS = setOf(
            // Standard Android boot
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            // Generic / OEM quick boot
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            // MediaTek IPO (Instant Power On)
            "com.mediatek.intent.action.QUICKBOOT_POWERON",
            "com.mediatek.intent.action.BOOT_IPO",
            // FYT / GLSX head units (ACC ignition wake)
            "com.fyt.boot.ACCON",
            "com.glsx.boot.ACCON",
            "android.intent.action.ACTION_MT_COMMAND_SLEEP_OUT",
            // Microntek / MTCD / PX3 head units (ACC wake)
            "com.cayboy.action.ACC_ON",
            "com.carboy.action.ACC_ON"
        )
    }
}
