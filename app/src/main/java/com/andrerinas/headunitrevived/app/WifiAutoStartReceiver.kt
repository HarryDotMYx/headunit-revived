package com.andrerinas.headunitrevived.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.main.MainActivity
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import android.os.Build

class WifiAutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return

        val networkInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)
        }

        if (networkInfo != null && networkInfo.isConnected) {
            // [FIX] WifiManager.getConnectionInfo().getSSID() is redacted to "<unknown ssid>" on
            // Android 10+ unless the app currently holds (foreground/background) location access
            // — which this app doesn't request for background use, so the fallback query below
            // was returning the redacted placeholder on every background WiFi-connect broadcast,
            // i.e. exactly the scenario WiFi auto-start exists for. NetworkInfo.getExtraInfo()
            // carries the SSID too and is delivered as part of this broadcast's own extras
            // regardless of the receiver's permission state — pass it through as a hint.
            @Suppress("DEPRECATION")
            val ssidHint = networkInfo.extraInfo?.removeSurrounding("\"")
            checkAndStart(context, ssidHint)
        }
    }

    companion object {
        fun checkAndStart(context: Context, ssidHint: String? = null) {
            // Read settings from device-protected storage for maximum reliability
            if (!Settings.isAutoStartOnWifiEnabled(context)) return
            val targetSsid = Settings.getAutoStartWifiSsid(context).removeSurrounding("\"")
            if (targetSsid.isEmpty()) return

            var currentSsid = ssidHint?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

            if (currentSsid == null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo: WifiInfo? = wifiManager.connectionInfo
                currentSsid = wifiInfo?.ssid?.removeSurrounding("\"")
            }

            if (currentSsid == null || currentSsid == "<unknown ssid>") {
                AppLog.w("WifiAutoStartReceiver: No valid SSID connected yet (likely redacted — location permission not available in background).")
                return
            }

            AppLog.d("WifiAutoStartReceiver: Checking WiFi: $currentSsid (Target: $targetSsid)")

            if (currentSsid.equals(targetSsid, ignoreCase = true)) {
                AppLog.i("WifiAutoStartReceiver: MATCH! Starting AapService via WiFi Auto-start...")

                // Don't trigger if already connected
                if (App.provide(context).commManager.isConnected) {
                    AppLog.d("WifiAutoStartReceiver: Already connected to Android Auto. Ignoring event.")
                    return
                }

                // Start the service
                val serviceIntent = Intent(context, AapService::class.java)
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    AppLog.e("Failed to start AapService from background: ${e.message}")
                }

                // Attempt to start the UI via FullScreenIntent (Required for Android 10+ background restrictions)
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, "WiFi auto-start")
                }

                val pendingIntent = PendingIntent.getActivity(
                    context, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                )

                val notification = NotificationCompat.Builder(context, App.bootStartChannel)
                    .setSmallIcon(R.drawable.ic_stat_aa)
                    .setContentTitle(context.getString(R.string.wifi_autostart_title))
                    .setContentText(context.getString(R.string.wifi_autostart_content, currentSsid))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_EVENT)
                    .setAutoCancel(true)
                    .setFullScreenIntent(pendingIntent, true)
                    .setContentIntent(pendingIntent)
                    .build()

                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(99, notification)
                AppLog.i("WifiAutoStartReceiver: Triggered FullScreenIntent notification.")
            }
        }
    }
}
