package com.andrerinas.headunitrevived.connection

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NearbySocket : Socket() {
    private var internalInputStream: InputStream? = null
    private var internalOutputStream: OutputStream? = null

    private val inputLatch = CountDownLatch(1)
    private val outputLatch = CountDownLatch(1)

    // [FIX] recvBlocking (SocketAccessoryConnection) sets transport.soTimeout before every call,
    // but the latch waits below previously ignored it and called the no-arg await() — blocking
    // forever if the phone never sends the stream payload (helper app killed, GMS drops it).
    // Store the caller's timeout and use it to bound the wait instead.
    @Volatile private var soTimeoutMs: Int = DEFAULT_STREAM_WAIT_TIMEOUT_MS

    override fun setSoTimeout(timeout: Int) {
        // A real Socket treats 0 as "block forever", but that's exactly the hang this fix
        // exists to prevent, so fall back to the default rather than honoring 0 literally.
        soTimeoutMs = if (timeout > 0) timeout else DEFAULT_STREAM_WAIT_TIMEOUT_MS
    }

    override fun getSoTimeout(): Int = soTimeoutMs

    override fun close() {
        // [FIX] Release any thread currently blocked in waitForStream() below. close() on the
        // base Socket only touched its own internal state — it never reached these latches, so
        // a thread stuck waiting for a payload that was never going to arrive stayed stuck even
        // after NearbyManager explicitly closed this socket (stop() / onDisconnected()).
        inputLatch.countDown()
        outputLatch.countDown()
        super.close()
    }

    var inputStreamWrapper: InputStream?
        get() = internalInputStream
        set(value) {
            internalInputStream = value
            if (value != null) {
                com.andrerinas.headunitrevived.utils.AppLog.i("NearbySocket: InputStream is now AVAILABLE. Releasing latch.")
                inputLatch.countDown()
            }
        }

    var outputStreamWrapper: OutputStream?
        get() = internalOutputStream
        set(value) {
            internalOutputStream = value
            if (value != null) outputLatch.countDown()
        }

    override fun isConnected() = true
    
    override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()

    override fun getInputStream(): InputStream {
        com.andrerinas.headunitrevived.utils.AppLog.d("NearbySocket: getInputStream() called")
        return object : InputStream() {
            private fun waitForStream(): InputStream {
                if (inputLatch.count > 0L) {
                    com.andrerinas.headunitrevived.utils.AppLog.i("NearbySocket: Blocking read until InputStream is AVAILABLE via Nearby Payload...")
                }
                if (!inputLatch.await(soTimeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                    throw SocketTimeoutException("NearbySocket: timed out waiting for the incoming stream payload")
                }
                // The latch can also be released by close() without a stream ever arriving.
                return internalInputStream ?: throw IOException("NearbySocket: closed while waiting for stream")
            }

            override fun read(): Int {
                val b = waitForStream().read()
                return b
            }

            override fun read(b: ByteArray): Int = read(b, 0, b.size)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val readValue = waitForStream().read(b, off, len)
                return readValue
            }
            override fun available(): Int = if (inputLatch.count == 0L) (internalInputStream?.available() ?: 0) else 0
            override fun close() = if (inputLatch.count == 0L) (internalInputStream?.close() ?: Unit) else Unit
        }
    }

    override fun getOutputStream(): OutputStream {
        com.andrerinas.headunitrevived.utils.AppLog.d("NearbySocket: getOutputStream() called")
        return object : OutputStream() {
            private fun waitForStream(): OutputStream {
                if (outputLatch.count > 0L) {
                    com.andrerinas.headunitrevived.utils.AppLog.d("NearbySocket: Waiting for outputLatch...")
                }
                if (!outputLatch.await(soTimeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                    throw SocketTimeoutException("NearbySocket: timed out waiting for the outgoing stream payload")
                }
                return internalOutputStream ?: throw IOException("NearbySocket: closed while waiting for stream")
            }

            override fun write(b: Int) {
                com.andrerinas.headunitrevived.utils.AppLog.v("NearbySocket: writing 1 byte to pipe")
                waitForStream().write(b)
            }
            
            override fun write(b: ByteArray) = write(b, 0, b.size)
            override fun write(b: ByteArray, off: Int, len: Int) {
                com.andrerinas.headunitrevived.utils.AppLog.v("NearbySocket: writing $len bytes to pipe")
                waitForStream().write(b, off, len)
                // Force flush since GMS Nearby Stream payloads might buffer a lot
                waitForStream().flush()
            }
            override fun flush() {
                com.andrerinas.headunitrevived.utils.AppLog.v("NearbySocket: flush() called")
                if (outputLatch.count == 0L) internalOutputStream?.flush()
            }
            override fun close() = if (outputLatch.count == 0L) (internalOutputStream?.close() ?: Unit) else Unit
        }
    }

    companion object {
        private const val DEFAULT_STREAM_WAIT_TIMEOUT_MS = 15_000
    }
}
