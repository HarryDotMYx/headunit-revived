package com.andrerinas.headunitrevived.connection

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.andrerinas.headunitrevived.aap.Utils
import com.andrerinas.headunitrevived.utils.AppLog

class UsbAccessoryMode(private val usbMgr: UsbManager) {

    fun connectAndSwitch(device: UsbDevice, useLibusb: Boolean = false): Boolean {
        if (switchInProgress) {
            AppLog.w("UsbAccessoryMode: A switch is already in progress on another caller; skipping duplicate connectAndSwitch.")
            return false
        }
        switchInProgress = true
        try {
            return connectAndSwitchLocked(device, useLibusb)
        } finally {
            switchInProgress = false
        }
    }

    private fun connectAndSwitchLocked(device: UsbDevice, useLibusb: Boolean): Boolean {
        val connection: UsbDeviceConnection?
        try {
            connection = usbMgr.openDevice(device)                 // Open device for connection
        } catch (e: Throwable) {
            AppLog.e(e)
            return false
        }

        if (connection == null) {
            AppLog.e("Cannot open device")
            return false
        }

        val result = if (useLibusb) {
            AppLog.i("UsbAccessoryMode: Performing AOA switch via native libusb...")
            val native = UsbNative()
            if (native.wrap(connection, 0, 0)) {
                val switchResult = native.accModeSwitch() == 0
                native.close()
                switchResult
            } else {
                AppLog.e("UsbAccessoryMode: Failed to wrap device for native AOA switch")
                native.close()
                false
            }
        } else {
            // [FIX] A phone plugged in moments ago may still be negotiating its own USB gadget
            // configuration when the very first GET_PROTOCOL control transfer arrives. That used
            // to get one attempt at a 100ms timeout with no retry — too tight for a "cold"
            // phone, fine for a "warm" one already used for AA before. That's why this "worked
            // sometimes, not others": whether the phone's gadget stack was ready in time was a
            // coin flip, not something wrong with the accessory itself. Retry the whole switch a
            // few times with a short backoff before giving up.
            var switched = false
            var attempt = 0
            while (!switched && attempt < MAX_SWITCH_ATTEMPTS) {
                switched = switch(connection)
                if (!switched) {
                    attempt++
                    if (attempt < MAX_SWITCH_ATTEMPTS) {
                        AppLog.w("UsbAccessoryMode: switch attempt $attempt failed, retrying in ${SWITCH_RETRY_DELAY_MS}ms...")
                        try { Thread.sleep(SWITCH_RETRY_DELAY_MS) } catch (e: Exception) {}
                    }
                }
            }
            switched
        }
        connection.close()

        AppLog.i("Result: $result")
        return result
    }

    private fun switch(connection: UsbDeviceConnection): Boolean {
        // Do accessory negotiation and attempt to switch to accessory mode. Called only by usb_connect()
        val buffer = ByteArray(2)
        var len = connection.controlTransfer(UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR, ACC_REQ_GET_PROTOCOL, 0, 0, buffer, 2, USB_TIMEOUT_IN_MS)
        if (len != 2) {
            AppLog.e("Error controlTransfer len: $len")
            return false
        }
        val acc_ver = Utils.getAccVersion(buffer)
        // Get OAP / ACC protocol version
        AppLog.i("Success controlTransfer len: $len  acc_ver: $acc_ver")
        if (acc_ver < 1) {
            // If error or version too low...
            AppLog.e("No support acc")
            return false
        }
        AppLog.i("acc_ver: $acc_ver")

        // Send all accessory identification strings. Abort if any transfer fails — a partial
        // identification (e.g. manufacturer sent but model missing) can cause the phone to
        // ignore the ACC_REQ_START or fail to switch into accessory mode.
        if (!initStringControlTransfer(connection, ACC_IDX_MAN, MANUFACTURER) ||
            !initStringControlTransfer(connection, ACC_IDX_MOD, MODEL) ||
            !initStringControlTransfer(connection, ACC_IDX_DES, DESCRIPTION) ||
            !initStringControlTransfer(connection, ACC_IDX_VER, VERSION) ||
            !initStringControlTransfer(connection, ACC_IDX_URI, URI) ||
            !initStringControlTransfer(connection, ACC_IDX_SER, SERIAL)) {
            return false
        }

        AppLog.i("Sending acc start")
        // Send accessory start request. Device should re-enumerate as an accessory.
        len = connection.controlTransfer(UsbConstants.USB_TYPE_VENDOR, ACC_REQ_START, 0, 0, byteArrayOf(), 0, USB_TIMEOUT_IN_MS)

        // len == 0: clean ACK before re-enumeration (expected path).
        // len < 0: phone disconnected before the ACK because it started re-enumerating
        //          immediately upon receipt — the command was still received. Treat as success.
        AppLog.i("Acc start sent (len=$len). Waiting for re-enumeration...")
        try { Thread.sleep(500) } catch (e: Exception) {}
        return true
    }

    private fun initStringControlTransfer(conn: UsbDeviceConnection, index: Int, string: String): Boolean {
        val len = conn.controlTransfer(UsbConstants.USB_TYPE_VENDOR, ACC_REQ_SEND_STRING, 0, index, string.toByteArray(), string.length, USB_TIMEOUT_IN_MS)
        return if (len < 0) {
            // Negative means the USB transfer itself failed (e.g. device disconnected or
            // timed out). Abort the switch — ACC_REQ_START would be pointless.
            AppLog.e("Error controlTransfer len: $len  index: $index  string: \"$string\"")
            false
        } else {
            // len == string.length is the ideal ACK. Some phones return 0 for a successful
            // OUT control transfer (they accept the data but report 0 bytes in the data
            // stage). Treat any non-negative return as success; log a warning if unexpected.
            if (len != string.length) {
                AppLog.w("Unexpected controlTransfer len: $len (expected ${string.length})  index: $index  string: \"$string\"")
            } else {
                AppLog.i("Success controlTransfer len: $len  index: $index  string: \"$string\"")
            }
            true
        }
    }

    companion object {
        // [FIX] AapService has its own isSwitchingToAccessory guard, but three other places
        // (UsbAttachedActivity, HomeFragment, UsbListFragment) call connectAndSwitch() directly
        // without ever setting or checking it — so it was never a real mutex, just one that
        // AapService's own call sites happened to honor. Two callers could run connectAndSwitch
        // at the same time, each opening their own UsbDeviceConnection and sending overlapping
        // GET_PROTOCOL/SEND_STRING/ACC_REQ_START control transfers, bouncing the phone through
        // re-enumeration twice and leaving the accessory node the app later opens in a stale
        // state. Moving the guard into connectAndSwitch itself means every caller, in every
        // component, participates automatically — no shared state needs to be threaded through.
        @Volatile private var switchInProgress = false

        // [FIX] Was 100ms — too tight for a control transfer the phone's own gadget driver
        // (not this app) has to service, especially milliseconds after a cold plug-in. These are
        // one-off vendor requests during setup, not a hot path, so a longer timeout costs
        // nothing on the happy path.
        private const val USB_TIMEOUT_IN_MS = 1000
        private const val MAX_SWITCH_ATTEMPTS = 3
        private const val SWITCH_RETRY_DELAY_MS = 300L
        private const val MANUFACTURER = "Android"
        private const val MODEL = "Android Auto"
        private const val DESCRIPTION = "Android Auto"//"Android Open Automotive Protocol"
        private const val VERSION = "2.0.1"
        private const val URI = "https://developer.android.com/auto/index.html"
        private const val SERIAL = "HU-AAAAAA001"

        // Indexes for strings sent by the host via ACC_REQ_SEND_STRING:
        private const val ACC_IDX_MAN = 0
        private const val ACC_IDX_MOD = 1
        private const val ACC_IDX_DES = 2
        private const val ACC_IDX_VER = 3
        private const val ACC_IDX_URI = 4
        private const val ACC_IDX_SER = 5

        // OAP Control requests:
        private const val ACC_REQ_GET_PROTOCOL = 51
        private const val ACC_REQ_SEND_STRING = 52
        private const val ACC_REQ_START = 53
    }
}
