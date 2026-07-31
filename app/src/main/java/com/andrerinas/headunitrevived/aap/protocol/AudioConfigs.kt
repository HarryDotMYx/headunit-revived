package com.andrerinas.headunitrevived.aap.protocol

import android.media.AudioManager
import android.util.SparseArray
import com.andrerinas.headunitrevived.aap.protocol.proto.Media

import com.andrerinas.headunitrevived.decoder.AudioDecoder

object AudioConfigs {
    private val audioTracks = SparseArray<Media.AudioConfiguration>(3)

    fun stream(channel: Int, separateAudioStreams: Boolean = true) : Int
    {
        if (separateAudioStreams) {
            return when(channel) {
                Channel.ID_AU1 -> AudioManager.STREAM_VOICE_CALL
                Channel.ID_AU2 -> AudioManager.STREAM_NOTIFICATION
                else -> AudioManager.STREAM_MUSIC
            }
        }
        return AudioManager.STREAM_MUSIC
    }

    // [FIX] Was declared as a non-null return type over SparseArray.get(), which returns null
    // for a key that was never put() — only channels ID_AUD/ID_AU1/ID_AU2 are populated below,
    // so any other channel produced an NPE at the Kotlin non-null intrinsic check instead of a
    // handleable null. Currently unreachable in practice (Channel.isAudio already restricts
    // callers to exactly those three channels — see AapMessageHandlerType.kt) but that's an
    // upstream gate this function shouldn't rely on to avoid crashing.
    fun get(channel: Int): Media.AudioConfiguration? {
        return audioTracks.get(channel)
    }

    init {
        val audioConfig0 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_48
            numberOfBits = 16
            numberOfChannels = 2
        }.build()
        audioTracks.put(Channel.ID_AUD, audioConfig0)

        val audioConfig1 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_16
            numberOfBits = 16
            numberOfChannels = 1
        }.build()
        audioTracks.put(Channel.ID_AU1, audioConfig1)

        val audioConfig2 = Media.AudioConfiguration.newBuilder().apply {
            sampleRate = AudioDecoder.SAMPLE_RATE_HZ_16
            numberOfBits = 16
            numberOfChannels = 1
        }.build()
        audioTracks.put(Channel.ID_AU2, audioConfig2)
    }
}
