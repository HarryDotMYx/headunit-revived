package com.andrerinas.headunitrevived.connection

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class LibusbAccessoryConnection(private val usbMgr: UsbManager, private val device: UsbDevice) : AccessoryConnection {
    @Volatile private var isConnectedVal = false
    @Volatile private var isConnecting = false
    private var usbDeviceConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var usbNative: UsbNative? = null
    private val stateLock = Any()

    // Direct ByteBuffer for JNI and tracking leftover state
    private val readBuffer = ByteBuffer.allocateDirect(16384)
    private var leftoverSize = 0
    private var leftoverPos = 0

    private val activeTransfers = AtomicInteger(0)

    override val isSingleMessage: Boolean
        get() = false

    override val isConnected: Boolean
        get() = isConnectedVal

    fun isDeviceRunning(device: UsbDevice): Boolean {
        return isConnectedVal && UsbDeviceCompat.getUniqueName(device) == UsbDeviceCompat.getUniqueName(this.device)
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            if (isConnectedVal || isConnecting) {
                return@withContext false
            }
            isConnecting = true
        }

        try {
            if (!usbMgr.hasPermission(device)) {
                AppLog.e("LibusbAccessoryConnection: No permission for USB device")
                synchronized(stateLock) { isConnecting = false }
                return@withContext false
            }
            
            // Open device
            var conn: UsbDeviceConnection? = null
            for (i in 0 until 3) {
                if (!isConnecting) {
                    conn?.close()
                    return@withContext false
                }
                try {
                    conn = usbMgr.openDevice(device)
                    if (conn != null) break
                } catch (t: Throwable) {
                    AppLog.w("LibusbAccessoryConnection: Attempt ${i + 1} to openDevice failed: ${t.message}")
                }
                if (i < 2) {
                    try {
                        for (k in 0 until 10) {
                            if (!isConnecting) {
                                conn?.close()
                                return@withContext false
                            }
                            Thread.sleep(100)
                        }
                    } catch (_: Exception) {}
                }
            }
            
            if (conn == null) {
                AppLog.e("LibusbAccessoryConnection: connection is null")
                synchronized(stateLock) { isConnecting = false }
                return@withContext false
            }

            if (!isConnecting) {
                conn.close()
                return@withContext false
            }

            synchronized(stateLock) {
                usbDeviceConnection = conn
            }

            if (device.interfaceCount <= 0) {
                AppLog.e("LibusbAccessoryConnection: No interface found on device")
                synchronized(stateLock) {
                    conn.close()
                    usbDeviceConnection = null
                    isConnecting = false
                }
                return@withContext false
            }
            val iface = device.getInterface(0)
            synchronized(stateLock) {
                usbInterface = iface
            }
            
            if (!conn.claimInterface(iface, true)) {
                AppLog.e("LibusbAccessoryConnection: Failed to claim interface")
                synchronized(stateLock) {
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    isConnecting = false
                }
                return@withContext false
            }
            
            // Find endpoints
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    if (epIn == null) epIn = ep
                } else {
                    if (epOut == null) epOut = ep
                }
            }
            if (epIn == null || epOut == null) {
                AppLog.e("LibusbAccessoryConnection: Unable to find endpoints")
                synchronized(stateLock) {
                    conn.releaseInterface(iface)
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    isConnecting = false
                }
                return@withContext false
            }

            synchronized(stateLock) {
                endpointIn = epIn
                endpointOut = epOut
            }

            if (!isConnecting) {
                synchronized(stateLock) {
                    conn.releaseInterface(iface)
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    endpointIn = null
                    endpointOut = null
                    isConnecting = false
                }
                return@withContext false
            }

            val native = UsbNative()
            if (!native.wrap(conn, epIn.address, epOut.address)) {
                AppLog.e("LibusbAccessoryConnection: Failed to wrap USB device via JNI")
                synchronized(stateLock) {
                    native.close()
                    conn.releaseInterface(iface)
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    endpointIn = null
                    endpointOut = null
                    isConnecting = false
                }
                return@withContext false
            }

            synchronized(stateLock) {
                if (!isConnecting) {
                    native.close()
                    conn.releaseInterface(iface)
                    conn.close()
                    usbDeviceConnection = null
                    usbInterface = null
                    endpointIn = null
                    endpointOut = null
                    isConnecting = false
                    return@withContext false
                }
                usbNative = native
                isConnectedVal = true
                isConnecting = false
            }
            AppLog.i("LibusbAccessoryConnection: Successfully connected via JNI Libusb")
            return@withContext true
        } catch (e: Exception) {
            AppLog.e("LibusbAccessoryConnection: Error during connect: ${e.message}")
            synchronized(stateLock) {
                isConnecting = false
            }
            disconnect()
            return@withContext false
        }
    }

    override fun disconnect() {
        synchronized(stateLock) {
            isConnecting = false
            isConnectedVal = false
        }

        // Wait for active JNI transfers (readers/writers) to finish before freeing context.
        // [FIX] This was capped at 1500ms with a comment claiming "the JNI read timeout chunk is
        // capped at 1000ms" — true for recvBlocking (which chunks native reads to <=1000ms and
        // rechecks isConnectedVal between chunks), but sendBlocking passes the caller's full
        // timeout straight to a single native.write() call with no chunking. AapSslContext and
        // AapTransport call sendBlocking with a 2000ms timeout — that write can legitimately
        // still be inside libusb_bulk_transfer when this wait gives up at 1500ms, and closing
        // the native context (usbNative.close() below) while nativeWrite is still using the
        // same handle is a use-after-free in the native layer (SIGSEGV, uncatchable from here).
        // A proper fix would chunk sendBlocking the way recvBlocking already does, but
        // nativeWrite's C implementation returns a raw negative libusb error for a timeout
        // (unlike nativeRead, which specifically maps LIBUSB_ERROR_TIMEOUT to 0) — chunking it
        // the same way would misreport an in-budget timeout as a fatal send error, a real
        // regression I can't verify without physical USB hardware to test against. Widening the
        // wait past the known 2000ms maximum is the safe, native-code-free fix: if any future
        // caller passes a longer sendBlocking timeout, this constant must grow to match.
        val start = android.os.SystemClock.elapsedRealtime()
        while (activeTransfers.get() > 0 && android.os.SystemClock.elapsedRealtime() - start < 2500) {
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                break
            }
        }

        synchronized(stateLock) {
            try {
                usbNative?.close()
            } catch (e: Exception) {
                AppLog.e("LibusbAccessoryConnection: Error closing native: ${e.message}")
            }
            usbNative = null
            
            try {
                if (usbDeviceConnection != null && usbInterface != null) {
                    usbDeviceConnection!!.releaseInterface(usbInterface)
                }
            } catch (e: Exception) {}
            
            try {
                usbDeviceConnection?.close()
            } catch (e: Exception) {}
            
            usbDeviceConnection = null
            usbInterface = null
            endpointIn = null
            endpointOut = null
            leftoverSize = 0
            leftoverPos = 0
        }
    }

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        if (!isConnectedVal) return -1
        val native = usbNative ?: return -1
        activeTransfers.incrementAndGet()
        try {
            if (!isConnectedVal) return -1
            return native.write(buf, length, timeout)
        } finally {
            activeTransfers.decrementAndGet()
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        if (!isConnectedVal) return -1
        val native = usbNative ?: return -1
        activeTransfers.incrementAndGet()
        try {
            if (!isConnectedVal) return -1
            var totalReturned = 0
            val overallStart = android.os.SystemClock.elapsedRealtime()

            while (totalReturned < length && isConnectedVal) {
                if (leftoverSize > 0) {
                    val available = leftoverSize - leftoverPos
                    val toCopy = minOf(length - totalReturned, available)
                    
                    readBuffer.position(leftoverPos)
                    readBuffer.get(buf, totalReturned, toCopy)
                    
                    leftoverPos += toCopy
                    totalReturned += toCopy

                    if (leftoverPos >= leftoverSize) {
                        leftoverSize = 0
                        leftoverPos = 0
                    }

                    if (totalReturned >= length || !readFully) break
                    continue
                }

                if (!isConnectedVal) break

                val jniTimeout = if (timeout <= 0) {
                    1000
                } else {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - overallStart
                    val remaining = timeout - elapsed
                    if (remaining <= 0) break
                    minOf(remaining.toInt(), 1000)
                }

                readBuffer.clear()
                val transferred = native.read(readBuffer, jniTimeout)
                if (transferred < 0) {
                    isConnectedVal = false
                    return if (totalReturned > 0) totalReturned else -1
                }
                if (transferred == 0) {
                    if (timeout > 0) {
                        val elapsed = android.os.SystemClock.elapsedRealtime() - overallStart
                        if (elapsed >= timeout) {
                            break
                        }
                    }
                    continue
                }

                leftoverSize = transferred
                leftoverPos = 0
            }

            return totalReturned
        } finally {
            activeTransfers.decrementAndGet()
        }
    }

    companion object {
    }
}
