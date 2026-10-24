// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import com.metallic.chiaki.lib.OpusEncoder
import com.metallic.chiaki.settings.DualSenseBtReportBuilder
import com.metallic.chiaki.settings.DualSenseBtSpeakerAudio

/**
 * Routes the host's pad speaker lane (padspk) into the DualSense BT audio path.
 *
 * This is the real feature the other two speaker sources only approximate:
 * [ControllerSystemAudioService] mirrors Android system audio and
 * [DualSenseStreamAudioRouter] re-routes the stream's main mix, whereas this carries
 * exactly the audio the game meant for the controller speaker, on its own channel.
 *
 * The lane arrives as raw mono signed 16 bit PCM at 48 kHz, one channel per local
 * controller, so no resampling is needed -- only mono-to-stereo and the Opus encode
 * the 0x39 report format requires. Only the lane belonging to [primaryControllerIndex]
 * is played, since a phone drives a single controller's speaker.
 */
object PadSpeakerAudioRouter {
    private const val SAMPLE_RATE = DualSenseBtSpeakerAudio.SAMPLE_RATE
    private const val OUTPUT_SAMPLES_PER_CHANNEL = DualSenseBtSpeakerAudio.SAMPLES_PER_CHANNEL_PER_FRAME
    private const val OUTPUT_INTERLEAVED = DualSenseBtSpeakerAudio.SAMPLES_PER_FRAME_INTERLEAVED

    @Volatile
    private var enabled = false

    /** Which local pad's lane to play. The host sends one lane per controller (0-3). */
    @Volatile
    var primaryControllerIndex: Int = 0

    private val lock = Any()
    private var encoder: OpusEncoder? = null

    /** Mono accumulator: padspk frames need not line up with the 480-sample output frame. */
    private val monoAccumulator = ShortArray(OUTPUT_SAMPLES_PER_CHANNEL)
    private var accumulatedSamples = 0
    private val pcmFrame = ShortArray(OUTPUT_INTERLEAVED)
    private val opusOut = ByteArray(DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)

    private var framesIn = 0L
    private var framesEncoded = 0L
    private var framesDroppedOtherPad = 0L

    data class DebugSnapshot(
        val enabled: Boolean,
        val primaryControllerIndex: Int,
        val framesIn: Long,
        val framesEncoded: Long,
        val framesDroppedOtherPad: Long,
        val pendingSamples: Int,
    )

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if(enabled == value)
                return
            enabled = value
            accumulatedSamples = 0
            ControllerSpeakerBus.flush()
            if(value) {
                ControllerSpeakerBus.enabled = true
                ensureEncoderLocked()
                writeSilenceFrameLocked()
            } else {
                encoder?.reset()
                // The bus is shared with the system-audio and stream-audio routers; only
                // shut it down when nothing else is feeding it.
                if(!ControllerSystemAudioService.isRunning && !DualSenseStreamAudioRouter.isEnabled())
                    ControllerSpeakerBus.enabled = false
            }
        }
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Feed one padspk frame.
     *
     * @param controllerIndex local pad the lane belongs to
     * @param data raw mono signed 16 bit little-endian PCM at 48 kHz
     */
    fun onPadSpeakerFrame(controllerIndex: Int, data: ByteArray) {
        if(!enabled || data.size < 2)
            return
        if(controllerIndex != primaryControllerIndex) {
            synchronized(lock) { framesDroppedOtherPad++ }
            return
        }

        synchronized(lock) {
            if(!enabled)
                return
            ensureEncoderLocked()
            framesIn++

            var offset = 0
            while(offset + 2 <= data.size) {
                monoAccumulator[accumulatedSamples] = readS16Le(data, offset)
                accumulatedSamples++
                offset += 2

                if(accumulatedSamples >= OUTPUT_SAMPLES_PER_CHANNEL) {
                    encodeAccumulatedFrameLocked()
                    accumulatedSamples = 0
                }
            }
        }
    }

    fun debugSnapshot(): DebugSnapshot = synchronized(lock) {
        DebugSnapshot(
            enabled = enabled,
            primaryControllerIndex = primaryControllerIndex,
            framesIn = framesIn,
            framesEncoded = framesEncoded,
            framesDroppedOtherPad = framesDroppedOtherPad,
            pendingSamples = accumulatedSamples,
        )
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
        // Must be a full hard-CBR packet; a short one padded back up to 200 bytes is a
        // frame the firmware decoder mishandles. Same constraint as the stream router.
        if(bytes == DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)
            ControllerSpeakerBus.setSilenceFrame(opusOut.copyOf(bytes))
    }

    private fun encodeAccumulatedFrameLocked() {
        val volume = DualSenseBtReportBuilder.headphoneVolumePercent.coerceIn(0, 100)
        val gain = if(volume < 100) volume / 100f else 1f

        // The lane is mono; the report format is stereo, so the same sample drives both.
        for(i in 0 until OUTPUT_SAMPLES_PER_CHANNEL) {
            val sample = if(gain < 1f)
                (monoAccumulator[i] * gain).toInt().toShort()
            else
                monoAccumulator[i]
            pcmFrame[i * 2] = sample
            pcmFrame[i * 2 + 1] = sample
        }

        val enc = ensureEncoderLocked()
        val bytes = enc.encode(pcmFrame, OUTPUT_SAMPLES_PER_CHANNEL, opusOut, opusOut.size)
        val opus = if(bytes > 0)
            opusOut.copyOf(bytes).copyOf(DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)
        else
            ByteArray(DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)
        ControllerSpeakerBus.onSpeakerFrame(opus)
        framesEncoded++
    }

    private fun readS16Le(data: ByteArray, offset: Int): Short {
        val lo = data[offset].toInt() and 0xFF
        val hi = data[offset + 1].toInt()
        return ((hi shl 8) or lo).toShort()
    }
}
