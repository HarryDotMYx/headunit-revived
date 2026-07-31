package com.andrerinas.headunitrevived.connection

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class SocketAccessoryConnection(private val ip: String, private val port: Int, private val context: Context) : AccessoryConnection {
    private var output: OutputStream? = null
    private var input: DataInputStream? = null
    private var transport: Socket
    // Only true for genuine Nearby tunnels, where the handshake's AA-16.4+ settle delay
    // and stale-byte drain must be skipped because every byte from the start matters.
    // Regular WirelessServer-accepted sockets (Hotspot/WiFi Direct/Headunit Server) and
    // manual-IP connections are NOT single-message and need those handshake safeguards.
    private var singleMessage: Boolean = false

    init {
        transport = Socket()
    }

    constructor(socket: Socket, context: Context) : this(socket.inetAddress.hostAddress ?: "", socket.port, context) {
        this.transport = socket
        this.singleMessage = socket is NearbySocket
        // Pre-connected sockets (like NearbySocket) need their streams initialized immediately
        // because connect() might not be called or might be bypassed.
        if (socket.isConnected) {
            try {
                this.input = DataInputStream(socket.getInputStream())
                this.output = socket.getOutputStream()
            } catch (e: IOException) {
                AppLog.e("Failed to get streams from pre-connected socket", e)
            }
        }
    }


    override val isSingleMessage: Boolean
        get() = singleMessage

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        val out = output ?: return -1
        return try {
            out.write(buf, 0, length)
            out.flush()
            length
        } catch (e: IOException) {
            AppLog.e(e)
            -1
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        val inp = input ?: return -1
        // Dynamically apply the caller's timeout so handshake (short timeouts)
        // and streaming (long timeouts) both work correctly on the same socket.
        try { transport.soTimeout = timeout } catch (_: Exception) {}
        return if (readFully) {
            // [FIX] Previously used DataInputStream.readFully(), which throws away how many
            // bytes it had already consumed when a SocketTimeoutException hits mid-read. A
            // >timeout Wi-Fi stall during a body read (e.g. AapReadSingleMessage's 10s body
            // read) would then report 0 ("nothing happened, try again") while the stream was
            // actually already offset by the bytes it silently swallowed — every subsequent
            // header/body read decrypts garbage forever, with a socket that still looks alive.
            // Track the partial count ourselves so a mid-read timeout can be reported as fatal.
            var totalRead = 0
            try {
                while (totalRead < length) {
                    val n = inp.read(buf, totalRead, length - totalRead)
                    if (n < 0) return -1 // EOF: peer closed the connection
                    totalRead += n
                }
                length
            } catch (e: SocketTimeoutException) {
                if (totalRead == 0) {
                    // Nothing consumed yet — safe for the caller to retry.
                    0
                } else {
                    AppLog.w("SocketAccessoryConnection: readFully timed out after consuming $totalRead/$length bytes — stream desynchronized, treating as fatal")
                    -1
                }
            } catch (e: IOException) {
                -1
            }
        } else {
            try {
                inp.read(buf, 0, length)
            } catch (e: SocketTimeoutException) {
                0
            } catch (e: IOException) {
                -1
            }
        }
    }

    override val isConnected: Boolean
        // [FIX] java.net.Socket.isConnected() means "connect() has ever succeeded" — it stays
        // true forever after close(), it does not mean "currently open". Without the isClosed
        // check, callers using this to detect a lost connection (e.g. the disconnect guard
        // below, and AapReadMultipleMessages' connection-lost check) could never observe a
        // WiFi/Nearby disconnect.
        get() = transport.isConnected && !transport.isClosed

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!transport.isConnected) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    var netToBind: android.net.Network? = null
                    try {
                        var wifiNetwork: android.net.Network? = null

                        // 1. Try synchronous scan of existing networks first (instant and reliable if connected)
                        val networks = cm.allNetworks
                        for (net in networks) {
                            val caps = cm.getNetworkCapabilities(net)
                            if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                                wifiNetwork = net
                                AppLog.i("Found active WiFi/P2P network via synchronous scan: $net")
                                break
                            }
                        }

                        // 2. Fallback to callback if not found synchronously (with increased 1500ms timeout)
                        if (wifiNetwork == null) {
                            val request = android.net.NetworkRequest.Builder()
                                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                                .build()
                            val latch = java.util.concurrent.CountDownLatch(1)
                            val callback = object : ConnectivityManager.NetworkCallback() {
                                override fun onAvailable(network: android.net.Network) {
                                    wifiNetwork = network
                                    latch.countDown()
                                }
                            }
                            try {
                                cm.registerNetworkCallback(request, callback)
                                latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                            } finally {
                                try {
                                    cm.unregisterNetworkCallback(callback)
                                } catch (_: Exception) {}
                            }
                        }

                        if (wifiNetwork != null) {
                            netToBind = wifiNetwork
                            AppLog.i("Found active WiFi/P2P network for binding: $wifiNetwork")
                        } else {
                            val activeNet = cm.activeNetwork
                            if (activeNet != null) {
                                val caps = cm.getNetworkCapabilities(activeNet)
                                if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                    AppLog.i("Active network is cellular. Skipping binding to prevent EHOSTUNREACH.")
                                } else {
                                    netToBind = activeNet
                                    AppLog.i("Active network is not cellular: $activeNet. Using for binding.")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        AppLog.w("Error scanning networks for binding", e)
                        // Fallback to active network on failure
                        netToBind = cm.activeNetwork
                    }

                    if (netToBind != null) {
                        try {
                            netToBind.bindSocket(transport)
                            AppLog.i("Bound socket to network: $netToBind")
                        } catch (e: Exception) {
                            AppLog.w("Failed to bind socket to network $netToBind", e)
                        }
                    }
                } else {
                    // Legacy API < 23 (Lollipop & KitKat & JB)
                    @Suppress("DEPRECATION")
                    if (cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true) {
                        try {
                            val addr = InetAddress.getByName(ip)
                            val b = addr.address
                            val ipInt = ((b[3].toInt() and 0xFF) shl 24) or
                                        ((b[2].toInt() and 0xFF) shl 16) or
                                        ((b[1].toInt() and 0xFF) shl 8) or
                                        (b[0].toInt() and 0xFF)
                            // cm.requestRouteToHost(ConnectivityManager.TYPE_WIFI, ipInt)
                            // Use reflection because requestRouteToHost is removed in newer SDKs
                            val m = cm.javaClass.getMethod("requestRouteToHost", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                            m.invoke(cm, ConnectivityManager.TYPE_WIFI, ipInt)
                            AppLog.i("Legacy: Requested route to host $ip")
                        } catch (e: Exception) {
                            AppLog.w("Legacy: Failed requestRouteToHost", e)
                        }
                    }
                }

                applyLowLatencySocketOptions(beforeConnect = true)
                // Chinese Headunit Mediatek Correction
                try {
                    transport.connect(InetSocketAddress(ip, port), 5000)
                } catch (e: Throwable) {
                    val errorMessage = e.message ?: e.toString()
                    if (errorMessage.contains("com.mediatek.cta.CtaHttp") || errorMessage.contains("CtaHttp")) {
                        AppLog.e("HUR_DEBUG: MediaTek crash intercepted.")
                    } else {
                        throw IOException(e)
                    }
                }
                // Chinese Headunit Mediatek Correction
            }
            // WiFi needs tolerance for retransmissions,
            // power-save wakes, and bufferbloat. 1s was causing readFully to timeout
            // mid-header, desynchronizing the stream ("Failed to read full header").
            transport.soTimeout = 10000
            applyLowLatencySocketOptions(beforeConnect = false)
            // Raw DataInputStream — no BufferedInputStream wrapper.
            // BufferedInputStream + readFully + timeout = internal buffer state corruption.
            input = DataInputStream(transport.getInputStream())
            output = transport.getOutputStream()
            return@withContext true
        } catch (e: IOException) {
            AppLog.e(e)
            return@withContext false
        }
    }

    override fun disconnect() {
        // [FIX] Previously gated on transport.isConnected, which is false whenever connect()
        // itself threw (e.g. the 5s connect timeout, or the MediaTek CtaHttp intercept above)
        // — so a socket that failed to connect never had its fd released here. Socket.close()
        // is safe to call unconditionally: closing an unconnected or already-closed socket is
        // a no-op per the Socket contract.
        try {
            transport.close()
        } catch (e: IOException) {
            AppLog.e(e)
        }
        input = null
        output = null
    }

    private fun applyLowLatencySocketOptions(beforeConnect: Boolean) {
        try { transport.tcpNoDelay = true } catch (_: Exception) {}
        try { transport.keepAlive = true } catch (_: Exception) {}
        try { transport.reuseAddress = true } catch (_: Exception) {}
        try { transport.trafficClass = 0x10 } catch (_: Exception) {} // IPTOS_LOWDELAY
        try { transport.setPerformancePreferences(0, 2, 1) } catch (_: Exception) {}

        // Keep TCP queues bounded for projection. Large default buffers can hide Wi-Fi
        // jitter but also allow old video data to accumulate before the decoder.
        try { transport.receiveBufferSize = LOW_LATENCY_SOCKET_BUFFER_BYTES } catch (_: Exception) {}
        try { transport.sendBufferSize = LOW_LATENCY_SOCKET_BUFFER_BYTES } catch (_: Exception) {}

        if (!beforeConnect) {
            AppLog.i(
                "Socket low-latency options: tcpNoDelay=%s, recvBuf=%d, sendBuf=%d",
                transport.tcpNoDelay,
                transport.receiveBufferSize,
                transport.sendBufferSize
            )
        }
    }

    companion object {
        private const val DEF_BUFFER_LENGTH = 131080
        private const val LOW_LATENCY_SOCKET_BUFFER_BYTES = 256 * 1024
    }
}
