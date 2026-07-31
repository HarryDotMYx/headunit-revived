package com.andrerinas.headunitrevived.decoder

import android.util.SparseArray
import com.andrerinas.headunitrevived.utils.AppLog

class AudioDecoder {

    private val audioTracks = SparseArray<AudioTrackWrapper>(3)
    private var mixer: AudioMixer? = null

    fun getTrack(channel: Int): AudioTrackWrapper? {
        return audioTracks.get(channel)
    }

    fun getMixer(): AudioMixer? {
        return mixer
    }

    fun hasMixer(): Boolean {
        return mixer != null && mixer!!.isRunning()
    }

    fun decode(channel: Int, buffer: ByteArray, offset: Int, size: Int) {
        val audioTrack = audioTracks.get(channel)
        audioTrack?.write(buffer, offset, size)
    }

    fun stop() {
        for (i in 0 until audioTracks.size()) {
            stop(audioTracks.keyAt(i))
        }
        releaseMixer()
    }

    fun stop(chan: Int) {
        val audioTrack = audioTracks.get(chan)
        audioTrack?.stopPlayback()
        // [FIX] Was put(chan, null), which SparseArray treats as "store a null value at this
        // key" — the key itself stays present, so every channel ever stopped accumulated as a
        // dead (null-valued) entry instead of actually being removed.
        audioTracks.remove(chan)
    }

    fun releaseMixer() {
        synchronized(this) {
            mixer?.stop()
            mixer = null
        }
    }

    fun start(channel: Int, stream: Int, sampleRate: Int, numberOfBits: Int, numberOfChannels: Int, isAac: Boolean = false, gain: Float = 1.0f, audioLatencyMultiplier: Int = 8, audioQueueCapacity: Int = 0, staticAudioFocus: Boolean = false, attachHwDspEqualizer: Boolean = false) {
        if (staticAudioFocus) {
            synchronized(this) {
                // [FIX] Was `if (mixer == null)` — if AudioMixer.start() ever failed internally
                // (e.g. AudioTrack.play() throwing on a picky head unit HAL), `mixer` was still
                // a non-null instance with running=false forever: this guard would never fire
                // again for the rest of the session, so every later channel silently fed a mixer
                // nothing was draining. Check isRunning() so a failed instance gets replaced.
                if (mixer?.isRunning() != true) {
                    mixer = AudioMixer(stream, attachHwDspEqualizer)
                    mixer!!.start()
                    AppLog.i("AudioDecoder: Created and started shared AudioMixer")
                }
            }
        }
        val thread = AudioTrackWrapper(
            stream = stream,
            sampleRateInHz = sampleRate,
            bitDepth = numberOfBits,
            channelCount = numberOfChannels,
            isAac = isAac,
            gain = gain,
            audioLatencyMultiplier = audioLatencyMultiplier,
            audioQueueCapacity = audioQueueCapacity,
            mixer = if (staticAudioFocus) mixer else null,
            channelId = channel,
            attachHwDspEqualizer = attachHwDspEqualizer
        )
        audioTracks.put(channel, thread)
    }

    fun setGain(channel: Int, gain: Float) {
        audioTracks.get(channel)?.setGain(gain)
    }

    companion object {
        const val SAMPLE_RATE_HZ_48 = 48000
        const val SAMPLE_RATE_HZ_16 = 16000
    }
}
