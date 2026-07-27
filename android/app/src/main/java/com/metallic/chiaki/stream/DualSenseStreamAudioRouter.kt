// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import com.metallic.chiaki.lib.OpusEncoder
import com.metallic.chiaki.settings.DualSenseBtReportBuilder
import com.metallic.chiaki.settings.DualSenseBtSpeakerAudio

/**
 * Routes decoded Remote Play main audio directly into the DualSense BT audio path.
 *
 * This is intentionally separate from [ControllerSystemAudioService]: MediaProjection mirrors
 * Android system audio, while this router is a true stream-audio switch. When enabled, native
 * audio output is muted and decoded PCM is encoded here for the controller speaker/jack FIFO.
 */
object DualSenseStreamAudioRouter {
    private const val SAMPLE_RATE = DualSenseBtSpeakerAudio.SAMPLE_RATE
    private const val SOURCE_SAMPLES_PER_CHANNEL = 512
    private const val SOURCE_INTERLEAVED = SOURCE_SAMPLES_PER_CHANNEL * 2
    private const val OUTPUT_SAMPLES_PER_CHANNEL = DualSenseBtSpeakerAudio.SAMPLES_PER_CHANNEL_PER_FRAME
    private const val OUTPUT_INTERLEAVED = DualSenseBtSpeakerAudio.SAMPLES_PER_FRAME_INTERLEAVED

    @Volatile
    private var enabled = false

    private val lock = Any()
    private var encoder: OpusEncoder? = null
    private val accumulator = ShortArray(SOURCE_INTERLEAVED)
    private var accumulatedFrames = 0
    private val pcmFrame = ShortArray(OUTPUT_INTERLEAVED)
    private val opusOut = ByteArray(DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if(enabled == value)
                return
            enabled = value
            accumulatedFrames = 0
            ControllerSpeakerBus.flush()
            if(value) {
                ControllerSpeakerBus.enabled = true
                ensureEncoderLocked()
                writeSilenceFrameLocked()
            } else {
                encoder?.reset()
                if(!ControllerSystemAudioService.isRunning)
                    ControllerSpeakerBus.enabled = false
            }
        }
    }

    fun isEnabled(): Boolean = enabled

    fun onPcmFrame(data: ByteArray, channels: Int, sampleRate: Int) {
        if(!enabled || data.isEmpty() || sampleRate != SAMPLE_RATE || channels <= 0)
            return

        synchronized(lock) {
            if(!enabled)
                return
            ensureEncoderLocked()

            val bytesPerSourceFrame = channels * 2
            var offset = 0
            while(offset + bytesPerSourceFrame <= data.size) {
                val left = readS16Le(data, offset)
                val right = if(channels > 1) readS16Le(data, offset + 2) else left
                val dst = accumulatedFrames * 2
                accumulator[dst] = left
                accumulator[dst + 1] = right
                accumulatedFrames++
                offset += bytesPerSourceFrame

                if(accumulatedFrames >= SOURCE_SAMPLES_PER_CHANNEL) {
                    encodeAccumulatedFrameLocked()
                    accumulatedFrames = 0
                }
            }
        }
    }

    private fun ensureEncoderLocked(): OpusEncoder {
        val existing = encoder
        if(existing != null && existing.isValid)
            return existing
        return OpusEncoder(SAMPLE_RATE, 2).also { encoder = it }
    }

    private fun writeSilenceFrameLocked() {
        val enc = ensureEncoderLocked()
        val silence = ShortArray(OUTPUT_INTERLEAVED)
        val bytes = enc.encode(silence, OUTPUT_SAMPLES_PER_CHANNEL, opusOut, opusOut.size)
        // Must be a full hard-CBR packet; zero-padding a short one back up to
        // 200 bytes yields a frame the firmware decoder mishandles, defeating
        // the point of having a real silence frame. See TechAntohere/Senshi#1.
        if(bytes == DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)
            ControllerSpeakerBus.setSilenceFrame(opusOut.copyOf(bytes))
    }

    private fun encodeAccumulatedFrameLocked() {
        downsample512to480Stereo(accumulator, pcmFrame)

        val volume = DualSenseBtReportBuilder.headphoneVolumePercent.coerceIn(0, 100)
        if(volume < 100) {
            val gain = volume / 100f
            for(i in pcmFrame.indices)
                pcmFrame[i] = (pcmFrame[i] * gain).toInt().toShort()
        }

        // Controller jack mode wants true stereo: Opus Ch0 -> L, Ch1 -> R.
        val enc = ensureEncoderLocked()
        val bytes = enc.encode(pcmFrame, OUTPUT_SAMPLES_PER_CHANNEL, opusOut, opusOut.size)
        val opus = if(bytes > 0)
            opusOut.copyOf(bytes).copyOf(DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)
        else
            ByteArray(DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)
        ControllerSpeakerBus.onSpeakerFrame(opus)
    }

    private fun downsample512to480Stereo(input: ShortArray, output: ShortArray) {
        val outFrames = output.size / 2
        val inFrames = input.size / 2
        for(outIdx in 0 until outFrames) {
            val srcPos = outIdx.toDouble() * inFrames / outFrames
            val lo = srcPos.toInt().coerceAtMost(inFrames - 1)
            val hi = (lo + 1).coerceAtMost(inFrames - 1)
            val frac = (srcPos - lo).toFloat()
            output[outIdx * 2] =
                (input[lo * 2] + frac * (input[hi * 2] - input[lo * 2])).toInt().toShort()
            output[outIdx * 2 + 1] =
                (input[lo * 2 + 1] + frac * (input[hi * 2 + 1] - input[lo * 2 + 1])).toInt().toShort()
        }
    }

    private fun readS16Le(data: ByteArray, offset: Int): Short {
        val lo = data[offset].toInt() and 0xFF
        val hi = data[offset + 1].toInt()
        return ((hi shl 8) or lo).toShort()
    }
}
