package com.andrerinas.headunitrevived.connection.carkey

import android.content.Context
import com.andrerinas.headunitrevived.utils.AppLog

class CarKeysManager {

    private val all: Array<CarKeyReceiver> = CarKeyReceiver.newDefaultReceivers()
    private val registered: MutableCollection<CarKeyReceiver> = ArrayList()

    fun registerReceivers(context: Context) {
        if (registered.isNotEmpty())
            return

        try {
            for (r in all) {
                if (!r.isSupported)
                    continue

                r.register(context)
                registered.add(r)
            }

            AppLog.d("AapService: ${registered.size} CarKeyReceivers registered")
        } catch (e: Exception) {
            AppLog.e("AapService: Failed to register CarKeyReceivers", e)
        }
    }

    fun unregisterReceivers() {
        if (registered.isEmpty())
            return

        // [FIX] One receiver's unregister() throwing (e.g. IllegalArgumentException: "Receiver
        // not registered") used to abort the forEach entirely, skipping every receiver after it
        // in iteration order — e.g. CarFYTReceiver.unregister() never running, leaving the FYT
        // ToolkitService binding alive. The old `finally { registered.clear() }` still ran
        // regardless, so registerReceivers()'s `if (registered.isNotEmpty()) return` guard
        // passed on the next connect and bound a *second* FYT connection, doubling every
        // steering-wheel event. Give each receiver its own try/catch so one failure can't
        // prevent the rest from being unregistered.
        for (r in registered) {
            try {
                r.unregister()
            } catch (e: Exception) {
                AppLog.e("AapService: Failed to unregister CarKeyReceiver", e)
            }
        }
        registered.clear()
    }

    fun isSUNeeded(): Boolean {
        return registered.any { it.isSUNeeded }
    }
}
