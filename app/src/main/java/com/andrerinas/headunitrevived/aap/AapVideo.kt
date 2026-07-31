package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import java.nio.ByteBuffer

internal class AapVideo(private val videoDecoder: VideoDecoder, private val settings: Settings, private val onFrameCorrupted: () -> Unit) {

    private val messageBuffer = ByteBuffer.allocate(
        if (settings.videoCodec == VideoDecoder.CodecType.H265.mimeType) {
            Messages.DEF_BUFFER_LENGTH * 64 // ~8MB for H.265 support
        } else {
            Messages.DEF_BUFFER_LENGTH * 16 // ~2MB for H.264 legacy support
        }
    )
    private var legacyAssembledBuffer: ByteArray? = null
    private var isFrameCorrupt = false
    private var lastKeyframeRequestMs = 0L

    private fun markCorruptAndRequestRecovery() {
        if (!isFrameCorrupt) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastKeyframeRequestMs > 1000) {
                lastKeyframeRequestMs = now
                AppLog.w("AapVideo: Frame corrupted, requesting keyframe to recover stream")
                onFrameCorrupted()
            }
        }
        isFrameCorrupt = true
    }

    private fun findStartCode(buf: ByteArray, offset: Int): Int {
        if (offset + 3 > buf.size) return -1
        if (buf[offset].toInt() == 0 && buf[offset + 1].toInt() == 0) {
            if (buf[offset + 2].toInt() == 1) return 3 // 3-byte start code
            if (offset + 4 <= buf.size && buf[offset + 2].toInt() == 0 && buf[offset + 3].toInt() == 1) return 4 // 4-byte start code
        }
        return -1
    }

    fun process(message: AapMessage): Boolean {

        val flags = message.flags.toInt()
        val buf = message.data
        val len = message.size

        when (flags) {
            11 -> {
                // [FIX] flags=11 ("single fragment / standalone message") is also what
                // non-frame media messages get on this channel when their type isn't in
                // MsgType.isControl's 1..26 range (MEDIA_SETUP/START/STOP/VIDEO_FOCUS_REQUEST/
                // UPDATE_UI_CONFIG_REPLY etc. — all >=32768, same as the control-fallback range
                // AapMessageHandlerType routes on). Only genuine frame data (type 0) or codec
                // config (type 1) should touch messageBuffer/isFrameCorrupt here; anything else
                // must fall through unhandled so AapMessageHandlerType's fallback can route it
                // to AapControl instead. This used to unconditionally clear() the buffer,
                // wiping out whatever a multi-fragment frame in progress (flags 9/8/8/.../10)
                // had already assembled if one of these messages interleaved with it —
                // corrupting the next frame handed to the decoder — and silently swallowing
                // messages AapControl was supposed to handle.
                if (message.type != 0 && message.type != 1) {
                    return false
                }

                // Single fragment frame - corruption only affects this frame
                isFrameCorrupt = false
                messageBuffer.clear()

                // Timestamp Indication (Offset 10)
                val sc10 = findStartCode(buf, 10)
                if (len > 10 + sc10 && sc10 > 0) {
                    videoDecoder.decode(buf, 10, len - 10, settings.forceSoftwareDecoding, settings.videoCodec)
                    return true
                }

                // Media Indication or Config (Offset 2)
                val sc2 = findStartCode(buf, 2)
                if (len > 2 + sc2 && sc2 > 0) {
                    videoDecoder.decode(buf, 2, len - 2, settings.forceSoftwareDecoding, settings.videoCodec)
                    return true
                }
                AppLog.w("AapVideo: Dropped Flag 11 packet. len=$len")
            }
            9 -> {
                // [FIX] Same reasoning as flags=11 above — a non-frame media message can't
                // legitimately start a new video frame either.
                if (message.type != 0 && message.type != 1) {
                    return false
                }

                // First fragment - reset corruption state for the new frame
                isFrameCorrupt = false
                messageBuffer.clear()

                // Timestamp Indication (Offset 10)
                val sc10 = findStartCode(buf, 10)
                if (len > 10 + sc10 && sc10 > 0) {
                    // [FIX] Unlike flags 8/10 below, this had no bounds check before put() —
                    // an oversized first fragment would throw BufferOverflowException instead of
                    // being handled the same way the other fragment types already are.
                    val needed = message.size - 10
                    if (messageBuffer.remaining() >= needed) {
                        messageBuffer.put(message.data, 10, needed)
                    } else {
                        AppLog.e("AapVideo: First-fragment overflow (Flag 9, offset 10)! Size $needed exceeds buffer capacity ${messageBuffer.capacity()}. Invalidating frame.")
                        markCorruptAndRequestRecovery()
                        messageBuffer.clear()
                    }
                    return true
                }
                // Media Indication (Offset 2)
                val sc2 = findStartCode(buf, 2)
                if (len > 2 + sc2 && sc2 > 0) {
                    val needed = message.size - 2
                    if (messageBuffer.remaining() >= needed) {
                        messageBuffer.put(message.data, 2, needed)
                    } else {
                        AppLog.e("AapVideo: First-fragment overflow (Flag 9, offset 2)! Size $needed exceeds buffer capacity ${messageBuffer.capacity()}. Invalidating frame.")
                        markCorruptAndRequestRecovery()
                        messageBuffer.clear()
                    }
                    return true
                }
            }
            8 -> {
                if (isFrameCorrupt) return true // Skip fragments of an already corrupt frame

                // Middle fragment - append to buffer with overflow detection
                if (messageBuffer.remaining() >= message.size) {
                    messageBuffer.put(message.data, 0, message.size)
                } else {
                    AppLog.e("AapVideo: Fragment overflow (Flag 8)! Size ${message.size} exceeds remaining ${messageBuffer.remaining()}. Invalidating frame.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                }
                return true
            }
            10 -> {
                if (isFrameCorrupt) return true // Skip fragments of an already corrupt frame

                // Last fragment - append, assemble, and decode
                if (messageBuffer.remaining() >= message.size) {
                    messageBuffer.put(message.data, 0, message.size)
                } else {
                    AppLog.e("AapVideo: Final fragment overflow (Flag 10)! Invalidating frame.")
                    markCorruptAndRequestRecovery()
                    messageBuffer.clear()
                    return true
                }

                messageBuffer.flip()
                val assembledSize = messageBuffer.limit()

                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.LOLLIPOP) {
                    if (legacyAssembledBuffer == null || legacyAssembledBuffer!!.size < assembledSize) {
                        legacyAssembledBuffer = ByteArray(assembledSize + 1024)
                    }
                    messageBuffer.get(legacyAssembledBuffer!!, 0, assembledSize)
                    videoDecoder.decode(legacyAssembledBuffer!!, 0, assembledSize, settings.forceSoftwareDecoding, settings.videoCodec)
                } else {
                    videoDecoder.decode(messageBuffer.array(), 0, assembledSize, settings.forceSoftwareDecoding, settings.videoCodec)
                }

                messageBuffer.clear()
                return true
            }
        }

        return false
    }

    fun release() {
        // Kept for AapTransport lifecycle compatibility. Decoding is synchronous here.
    }
}
