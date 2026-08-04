package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.ssl.ConscryptInitializer
import com.andrerinas.headunitrevived.ssl.NoCheckTrustManager
import com.andrerinas.headunitrevived.ssl.SingleKeyKeyManager
import com.andrerinas.headunitrevived.utils.AppLog
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

class AapSslContext(keyManager: SingleKeyKeyManager): AapSsl {
    private val sslContext: SSLContext = createSslContext(keyManager)
    private lateinit var sslEngine: SSLEngine
    private lateinit var txBuffer: ByteBuffer
    private lateinit var rxBuffer: ByteBuffer
    
    @Volatile var isUserDisconnect = false

    override fun performHandshake(connection: AccessoryConnection): Boolean {
        // [FIX] isUserDisconnect is a sticky flag on this reused, process-lifetime instance
        // (see externalSsl in AapTransport's constructor doc — this AapSslContext survives
        // across reconnects) and was never reset. After the first clean user disconnect ever
        // set it true, every subsequent connection's genuine decrypt failures were silently
        // logged as expected and misreported as a clean user exit instead of a real error.
        isUserDisconnect = false
        if (prepare() < 0) return false

        // Buffer for unencrypted TLS records extracted from AAP messages.
        // We use a local queue or buffer to keep track of bytes ready for the SSLEngine.
        var pendingTlsData = ByteArray(0)
        
        // Hard cap on the entire SSL phase.
        val deadline = android.os.SystemClock.elapsedRealtime() + SSL_HANDSHAKE_TIMEOUT_MS

        while (getHandshakeStatus() != SSLEngineResult.HandshakeStatus.FINISHED &&
                getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {

            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                AppLog.e("SSL Handshake: Timed out after ${SSL_HANDSHAKE_TIMEOUT_MS}ms")
                return false
            }

            when (getHandshakeStatus()) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    // If we don't have enough data for a meaningful unwrap, read a full AAP message
                    if (pendingTlsData.isEmpty()) {
                        val messageData = readAapMessage(connection, deadline) ?: return false
                        pendingTlsData = messageData
                    }

                    rxBuffer.clear()
                    val data = ByteBuffer.wrap(pendingTlsData)
                    val result = sslEngine.unwrap(data, rxBuffer)
                    runDelegatedTasks(result, sslEngine)

                    when (result.status) {
                        SSLEngineResult.Status.OK -> {
                            // Keep any unconsumed bytes (e.g. next TLS record already in the buffer).
                            pendingTlsData = if (data.hasRemaining())
                                ByteArray(data.remaining()).also { data.get(it) }
                            else ByteArray(0)
                        }
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            // The current pendingTlsData doesn't contain a full TLS record.
                            // Read another AAP message and append it.
                            val nextMessage = readAapMessage(connection, deadline) ?: return false
                            pendingTlsData += nextMessage
                            AppLog.d("SSL Handshake: buffered ${pendingTlsData.size} B after underflow")
                        }
                        else -> {
                            AppLog.e("SSL Handshake: unwrap failed with status ${result.status}")
                            return false
                        }
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val handshakeData = handshakeRead()
                    val bio = Messages.createRawMessage(0, 3, 3, handshakeData)
                    if (connection.sendBlocking(bio, bio.size, 2000) < 0) {
                        AppLog.e("SSL Handshake: Send failed")
                        return false
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks()
                }

                else -> {
                    AppLog.e("SSL Handshake: Unexpected status ${getHandshakeStatus()}")
                    return false
                }
            }
        }
        
        val session = sslEngine.session
        // [FIX] Nothing ever checked what actually got negotiated. In particular
        // SSL_NULL_WITH_NULL_NULL (the JSSE placeholder cipher meaning "no cipher negotiated
        // yet") would previously have been accepted as a "successful" handshake if it were ever
        // returned here, silently providing no encryption at all.
        if (session.cipherSuite == "SSL_NULL_WITH_NULL_NULL") {
            AppLog.e("SSL Handshake: negotiated NULL cipher suite — treating as a failed handshake")
            return false
        }
        val sessionId = session.id
        if (sessionId != null && sessionId.isNotEmpty()) {
            AppLog.i("SSL handshake complete. protocol=${session.protocol} cipher=${session.cipherSuite} Session id: ${android.util.Base64.encodeToString(sessionId, android.util.Base64.NO_WRAP)}")
        } else {
            AppLog.i("SSL handshake complete. protocol=${session.protocol} cipher=${session.cipherSuite}. No session id (full handshake).")
        }
        return true
    }

    /**
     * Reads a single complete AAP message from the connection, discarding anything that
     * isn't on the SSL handshake channel/type until [deadline] is reached.
     * This ensures that we always respect AAP framing boundaries.
     */
    private fun readAapMessage(connection: AccessoryConnection, deadline: Long): ByteArray? {
        // [FIX] This used to accept ANY message's payload as TLS bytes without checking the
        // channel/type header fields at all — unlike AapTransport's version-exchange loop
        // (see its comment: "Accepting any non-empty read as the response would hand a random
        // payload to the SSL layer"), which explicitly filters for exactly this reason. A phone
        // that sends one proactive control message while the SSL phase is in progress had its
        // payload fed straight into sslEngine.unwrap(), which fails the handshake for no real
        // reason. Loop, discarding non-SSL-channel messages, until a match arrives or the
        // handshake's overall deadline (shared with the caller) is reached.
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val header = ByteArray(6)
            // Read exactly 6 bytes for the AAP header
            if (connection.recvBlocking(header, 6, 2000, true) != 6) {
                AppLog.e("SSL Handshake: Failed to read AAP header")
                return null
            }

            // AAP Header: [0]=Channel, [1]=Flags, [2..3]=Length (Big Endian), [4..5]=Type
            // The length in the header includes the 4 bytes of channel/flags/length itself?
            // No, in Messages.kt: size + 2 is stored in bytes 2..3.
            // So payload length = (header[2]*256 + header[3]) - 2.
            val totalLength = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            val payloadLength = totalLength - 2 // Minus the 2 bytes for the type field (bytes 4-5)

            if (payloadLength < 0 || payloadLength > Messages.DEF_BUFFER_LENGTH) {
                AppLog.e("SSL Handshake: Invalid AAP payload length: $payloadLength")
                return null
            }

            val payload = ByteArray(payloadLength)
            if (connection.recvBlocking(payload, payloadLength, 2000, true) != payloadLength) {
                AppLog.e("SSL Handshake: Failed to read AAP payload ($payloadLength bytes)")
                return null
            }

            val channel = header[0].toInt() and 0xFF
            val type = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
            // Handshake bytes are always sent as channel 0 / type 3 (see NEED_WRAP below:
            // Messages.createRawMessage(0, 3, 3, handshakeData)).
            if (channel != 0 || type != 3) {
                AppLog.w("SSL Handshake: Ignoring unexpected message (ch=$channel, type=$type, len=$payloadLength) while waiting for SSL data")
                continue
            }

            return payload
        }
        AppLog.e("SSL Handshake: Timed out waiting for an SSL-channel message")
        return null
    }

    private fun prepare(): Int {
        // Use a consistent (host, port) key so JSSE's ClientSessionContext can find and reuse
        // the session from the previous connection.  The values are arbitrary — they are never
        // used for DNS resolution; they just serve as the cache lookup key.
        sslEngine = sslContext.createSSLEngine("android-auto", 5277).apply {
            useClientMode = true
            // [FIX] No protocol floor was ever set, so whatever the provider/peer negotiated
            // (down to and including legacy/deprecated TLS versions on providers that still
            // offer them) was silently accepted. Pin to the strongest protocols this engine
            // actually supports rather than leaving it fully peer-controlled.
            try {
                val preferred = arrayOf("TLSv1.3", "TLSv1.2")
                val floor = preferred.filter { it in supportedProtocols }.toTypedArray()
                if (floor.isNotEmpty()) enabledProtocols = floor
            } catch (e: Exception) {
                AppLog.w("SSL: Failed to set TLS protocol floor, continuing with provider defaults: ${e.message}")
            }
            session.also {
                val appBufferMax = it.applicationBufferSize
                val netBufferMax = it.packetBufferSize

                txBuffer = ByteBuffer.allocateDirect(netBufferMax)
                rxBuffer = ByteBuffer.allocateDirect(Messages.DEF_BUFFER_LENGTH.coerceAtLeast(appBufferMax + 50))
            }
        }
        sslEngine.beginHandshake()
        return 0
    }

    override fun postHandshakeReset() {
        // Clear buffers. In this implementation, the buffers are re-created for each wrap/unwrap
        // operation (implicitly by ByteBuffer.wrap), but clearing them ensures no stale data.
        txBuffer.clear()
        rxBuffer.clear()
    }

    override fun release() {
        // No-op for SSLEngine (garbage collection handles it)
    }

    private fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus {
        return sslEngine.handshakeStatus
    }

    private fun runDelegatedTasks() {
        if (sslEngine.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var runnable: Runnable? = sslEngine.delegatedTask
            while (runnable != null) {
                runnable.run()
                runnable = sslEngine.delegatedTask
            }
            val hsStatus = sslEngine.handshakeStatus
            if (hsStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
                throw Exception("handshake shouldn't need additional tasks")
            }
        }
    }

    private fun handshakeRead(): ByteArray {
        // [FIX] encrypt()/decrypt() both hold synchronized(this) around every access to
        // sslEngine/txBuffer/rxBuffer, but this — the handshake's own NEED_WRAP path — held no
        // lock, despite sharing the same engine and txBuffer. CommManager.disconnect() is
        // callable while a handshake is still in flight (e.g. user cancels a connect attempt),
        // and that path can reach encrypt() on a different thread (the transport's Send
        // HandlerThread) concurrently with this method running on the handshake thread —
        // interleaved txBuffer.clear()/wrap()/get() calls between the two could corrupt or
        // truncate whatever TLS record either one was mid-way through writing.
        synchronized(this) {
            txBuffer.clear()
            val result = sslEngine.wrap(emptyArray(), txBuffer)
            runDelegatedTasks(result, sslEngine)
            val resultBuffer = ByteArray(result.bytesProduced())
            txBuffer.flip()
            txBuffer.get(resultBuffer)
            return resultBuffer
        }
    }

    override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        synchronized(this) {
            if (!::sslEngine.isInitialized || !::rxBuffer.isInitialized) {
                AppLog.w("SSL Decrypt: Not initialized yet")
                return null
            }
            try {
                rxBuffer.clear()
                val encrypted = ByteBuffer.wrap(buffer, start, length)
                val result = sslEngine.unwrap(encrypted, rxBuffer)
                runDelegatedTasks(result, sslEngine)

                if (AppLog.LOG_VERBOSE || result.bytesProduced() == 0) {
                    AppLog.d("SSL Decrypt Status: ${result.status}, Produced: ${result.bytesProduced()}, Consumed: ${result.bytesConsumed()}")
                }

                // [FIX] Status was never checked — a BUFFER_UNDERFLOW (this AAP message's
                // ciphertext didn't contain a full TLS record) or BUFFER_OVERFLOW/CLOSED result
                // was indistinguishable from a genuine "this record legitimately produced 0
                // bytes" OK result: both returned an empty-but-non-null ByteArrayWithLimit, which
                // AapMessageIncoming only happened to catch via an incidental size check. Treat
                // any non-OK status as a real decrypt failure instead of a silent empty success.
                if (result.status != SSLEngineResult.Status.OK) {
                    AppLog.w("SSL Decrypt: non-OK status ${result.status}, treating as failure")
                    return null
                }

                val resultBuffer = ByteArray(result.bytesProduced())
                rxBuffer.flip()
                rxBuffer.get(resultBuffer)
                return ByteArrayWithLimit(resultBuffer, resultBuffer.size)
            } catch (e: Exception) {
                // Check for Magic Garbage disconnect signal from Wireless Helper
                if (length >= 16) {
                    var allFF = true
                    for (i in 0 until 16) {
                        if (buffer[start + i] != 0xFF.toByte()) {
                            allFF = false
                            break
                        }
                    }
                    if (allFF) {
                        AppLog.i("SSL Decrypt: Magic Garbage detected. Marking as clean user disconnect.")
                        isUserDisconnect = true
                    }
                }
                
                if (!isUserDisconnect) {
                    AppLog.e("SSL Decrypt failed", e)
                }
                return null
            }
        }
    }

    override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        synchronized(this) {
            if (!::sslEngine.isInitialized || !::txBuffer.isInitialized) {
                AppLog.w("SSL Encrypt: Not initialized yet")
                return null
            }
            try {
                txBuffer.clear()
                val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
                val result = sslEngine.wrap(byteBuffer, txBuffer)
                runDelegatedTasks(result, sslEngine)

                // [FIX] see the identical fix in decrypt() — a non-OK status (e.g.
                // BUFFER_OVERFLOW) was silently treated as a successful encrypt of however many
                // bytes happened to be produced, which could be zero.
                if (result.status != SSLEngineResult.Status.OK) {
                    AppLog.e("SSL Encrypt: non-OK status ${result.status}")
                    return null
                }

                val resultBuffer = ByteArray(result.bytesProduced() + offset)
                txBuffer.flip()
                txBuffer.get(resultBuffer, offset, result.bytesProduced())
                return ByteArrayWithLimit(resultBuffer, resultBuffer.size)
            } catch (e: Exception) {
                AppLog.e("SSL Encrypt failed", e)
                return null
            }
        }
    }

    private fun runDelegatedTasks(result: SSLEngineResult, engine: SSLEngine) {
        if (result.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var runnable: Runnable? = engine.delegatedTask
            while (runnable != null) {
                runnable.run()
                runnable = engine.delegatedTask
            }
            val hsStatus = engine.handshakeStatus
            if (hsStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
                throw Exception("handshake shouldn't need additional tasks")
            }
        }
    }

    companion object {
        // Maximum wall-clock time for the entire SSL handshake loop. Caps worst-case stall at
        // 15 s regardless of how many round-trips remain when the phone stops responding.
        private const val SSL_HANDSHAKE_TIMEOUT_MS = 15_000L

        private fun createSslContext(keyManager: SingleKeyKeyManager): SSLContext {
            val providerName = ConscryptInitializer.getProviderName()

            val sslContext = if (providerName != null) {
                try {
                    AppLog.d("Creating SSLContext with Conscrypt provider")
                    SSLContext.getInstance("TLS", providerName)
                } catch (e: Exception) {
                    AppLog.w("Failed to create SSLContext with Conscrypt, using default", e)
                    SSLContext.getInstance("TLS")
                }
            } else {
                AppLog.d("Creating SSLContext with default provider")
                SSLContext.getInstance("TLS")
            }

            return sslContext.apply {
                init(arrayOf(keyManager), arrayOf(NoCheckTrustManager()), null)
                // Keep the default session cache (size 10, timeout 86400 s) so that a
                // reconnect within the same app session can use an abbreviated handshake.
            }
        }
    }
}
