// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * DualSense BT speaker audio.
 *
 * Ground truth here is the working Windows bridge:
 *   1. 0x31 prep
 *   2. wrapped USB-style 0x31 route
 *   3. 0x32 controller-data (ledReady=false)
 *   4. 0x32 controller-data (ledReady=true)
 *   5. wrapped USB-style 0x31 route again
 *   6. stream 0x39 dual-frame reports on the controller clock
 *
 * The important part is that the working 0x39 path is not "Opus only". It also
 * carries the 130-byte PCM-derived haptics tail at offset 413.
 */
object DualSenseBtSpeakerAudio {

    data class DualFramePacket(
        val opus1: ByteArray,
        val opus2: ByteArray,
        val pcm1: ByteArray,
        val pcm2: ByteArray,
    )

    private data class WavData(
        val bytes: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val dataSize: Int,
    )

    const val OPUS_BYTES_PER_FRAME = 200
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val SAMPLES_PER_CHANNEL_PER_FRAME = 480
    const val SAMPLES_PER_FRAME_INTERLEAVED = SAMPLES_PER_CHANNEL_PER_FRAME * CHANNELS
    const val PCM_BYTES_PER_FRAME = SAMPLES_PER_FRAME_INTERLEAVED * 2
    const val PREPARATION_SAMPLE_RATE = (SAMPLE_RATE * SAMPLES_PER_CHANNEL_PER_FRAME) / 512
    const val DEFAULT_LATENCY_SCALE: Byte = 0x30
    const val REPORT_SIZE_TWO = 547
    const val REPORT_PERIOD_SINGLE_NS = 10_666_667L
    const val REPORT_PERIOD_TWO_NS = REPORT_PERIOD_SINGLE_NS * 2L

    // Audio section type bytes, matching DS5Dongle single-frame routing values (|0x80):
    // 0x53|0x80 = 0xD3: two frames, both channels to internal speaker
    // 0x55|0x80 = 0xD5: two frames, Ch0->headset-L, Ch1->speaker (WRONG old default)
    // 0x56|0x80 = 0xD6: two frames, full headset stereo, speaker muted by hardware
    private const val MODE_TWO_SPEAKER = 0xD3.toByte()
    private const val MODE_TWO_HEADSET = 0xD6.toByte()
    private const val HAPTICS_TAIL_OFFSET = 413
    private const val HAPTICS_TAIL_LENGTH = 130
    private const val OUTPUT_HAPTICS_SAMPLES = 32
    private const val INPUT_SAMPLES_PER_HAPTICS_SAMPLE = SAMPLES_PER_CHANNEL_PER_FRAME / OUTPUT_HAPTICS_SAMPLES
    private const val CONTROLLER_SPEAKER_HIGH_PASS_HZ = 180.0
    private const val CONTROLLER_SPEAKER_SIGNAL_GAIN = 0.82
    private const val CONTROLLER_SPEAKER_SOFT_CLIP_DRIVE = 1.15
    private const val DEFAULT_HAPTICS_GAIN = 1.0

    const val OPUS_ASSET_PATH = "haptics/savannah_opus.bin"
    const val WAV_ASSET_PATH = "haptics/savannah.wav"

    private val ctrLock = Any()
    private var frameCtr = 0
    private var lastCount = 0

    fun resetCounters() = synchronized(ctrLock) {
        frameCtr = 0
        lastCount = 0
    }

    fun loadDualFramePackets(context: Context): List<DualFramePacket> {
        val opusFrames = loadOpusFrames(context)
        val pcmFrames = loadPreparedPcmFrames(context)
        if (opusFrames.isEmpty()) {
            return emptyList()
        }

        val totalFrames = maxOf(opusFrames.size, pcmFrames.size)
        val totalPairs = (totalFrames + 1) / 2
        val zeroOpus = ByteArray(OPUS_BYTES_PER_FRAME)
        val zeroPcm = ByteArray(PCM_BYTES_PER_FRAME)
        val packets = ArrayList<DualFramePacket>(totalPairs)

        for (pairIndex in 0 until totalPairs) {
            val frameIndex = pairIndex * 2
            packets += DualFramePacket(
                opus1 = opusFrames.getOrElse(frameIndex) { zeroOpus },
                opus2 = opusFrames.getOrElse(frameIndex + 1) { zeroOpus },
                pcm1 = pcmFrames.getOrElse(frameIndex) { zeroPcm },
                pcm2 = pcmFrames.getOrElse(frameIndex + 1) { zeroPcm },
            )
        }

        return packets
    }

    fun buildTwoFrameReport(
        packet: DualFramePacket,
        routeSpeaker: Boolean = true,
        @Suppress("UNUSED_PARAMETER") speakerVolume: Int = DualSenseBtAudioHapticsBuilder.DEFAULT_SPEAKER_VOLUME,
        latency: Byte = DEFAULT_LATENCY_SCALE,
        hapticsGain: Double = DEFAULT_HAPTICS_GAIN,
        includeHapticsTail: Boolean = true,
    ): ByteArray {
        require(packet.opus1.size == OPUS_BYTES_PER_FRAME && packet.opus2.size == OPUS_BYTES_PER_FRAME)
        require(packet.pcm1.size == PCM_BYTES_PER_FRAME && packet.pcm2.size == PCM_BYTES_PER_FRAME)

        val report = ByteArray(REPORT_SIZE_TWO)
        report[0] = 0x39
        synchronized(ctrLock) {
            report[1] = DualSenseBtReportBuilder.nextOutputSeqTag()
            writeAudioConfig(report, 2, latency, nextFrameCtr(2), if (routeSpeaker) MODE_TWO_SPEAKER else MODE_TWO_HEADSET)
        }
        System.arraycopy(packet.opus1, 0, report, 13, OPUS_BYTES_PER_FRAME)
        System.arraycopy(packet.opus2, 0, report, 213, OPUS_BYTES_PER_FRAME)
        if (includeHapticsTail) {
            writeBluetoothHapticsTail(report, HAPTICS_TAIL_OFFSET, packet.pcm1, packet.pcm2, hapticsGain)
        } else {
            report.fill(0, HAPTICS_TAIL_OFFSET, HAPTICS_TAIL_OFFSET + HAPTICS_TAIL_LENGTH)
        }
        writeCrc(report, 543)
        return report
    }

    fun buildSilenceTwoFrameReport(
        routeSpeaker: Boolean = false,
        speakerVolume: Int = DualSenseBtAudioHapticsBuilder.DEFAULT_SPEAKER_VOLUME,
        latency: Byte = DEFAULT_LATENCY_SCALE,
        includeHapticsTail: Boolean = true,
    ): ByteArray =
        buildTwoFrameReport(
            DualFramePacket(
                opus1 = ByteArray(OPUS_BYTES_PER_FRAME),
                opus2 = ByteArray(OPUS_BYTES_PER_FRAME),
                pcm1 = ByteArray(PCM_BYTES_PER_FRAME),
                pcm2 = ByteArray(PCM_BYTES_PER_FRAME),
            ),
            routeSpeaker = routeSpeaker,
            speakerVolume = speakerVolume,
            latency = latency,
            includeHapticsTail = includeHapticsTail,
        )

    fun buildTwoFramePackedHapticsReport(
        packedFrame1: ByteArray,
        packedFrame2: ByteArray,
        latency: Byte = DEFAULT_LATENCY_SCALE,
    ): ByteArray {
        require(packedFrame1.size == DualSenseBtAudioHapticsBuilder.SAMPLE_BYTES) {
            "Packed haptics frame 1 must be exactly ${DualSenseBtAudioHapticsBuilder.SAMPLE_BYTES} bytes"
        }
        require(packedFrame2.size == DualSenseBtAudioHapticsBuilder.SAMPLE_BYTES) {
            "Packed haptics frame 2 must be exactly ${DualSenseBtAudioHapticsBuilder.SAMPLE_BYTES} bytes"
        }

        val report = ByteArray(REPORT_SIZE_TWO)
        report[0] = 0x39
        synchronized(ctrLock) {
            report[1] = DualSenseBtReportBuilder.nextOutputSeqTag()
            writeAudioConfig(report, 2, latency, nextFrameCtr(2), MODE_TWO_SPEAKER)
        }
        writePackedBluetoothHapticsTail(report, HAPTICS_TAIL_OFFSET, packedFrame1, packedFrame2)
        writeCrc(report, 543)
        return report
    }

    fun buildSilenceTwoFramePackedHapticsReport(
        latency: Byte = DEFAULT_LATENCY_SCALE,
    ): ByteArray =
        buildTwoFramePackedHapticsReport(
            packedFrame1 = ByteArray(DualSenseBtAudioHapticsBuilder.SAMPLE_BYTES),
            packedFrame2 = ByteArray(DualSenseBtAudioHapticsBuilder.SAMPLE_BYTES),
            latency = latency,
        )

    fun getPrefillDualPacketCount(latency: Byte = DEFAULT_LATENCY_SCALE): Int {
        val basePrefillFrames = ceil((latency.toInt() and 0xFF) / 12.0).toInt().coerceAtLeast(2)
        val evenPrefillFrames = basePrefillFrames.coerceAtLeast(4).let { if ((it and 1) != 0) it + 1 else it }
        return (evenPrefillFrames / 2).coerceAtLeast(1)
    }

    private fun loadOpusFrames(context: Context): List<ByteArray> {
        val raw = context.assets.open(OPUS_ASSET_PATH).use { it.readBytes() }
        val frames = ArrayList<ByteArray>(raw.size / OPUS_BYTES_PER_FRAME)
        var offset = 0
        while (offset + OPUS_BYTES_PER_FRAME <= raw.size) {
            frames += raw.copyOfRange(offset, offset + OPUS_BYTES_PER_FRAME)
            offset += OPUS_BYTES_PER_FRAME
        }
        return frames
    }

    private fun loadPreparedPcmFrames(context: Context): List<ByteArray> {
        val wavBytes = context.assets.open(WAV_ASSET_PATH).use { it.readBytes() }
        val wav = parseWav(wavBytes)
        val resampled = resampleStereo16Interleaved(wav, PREPARATION_SAMPLE_RATE)
        val prepared = prepareForControllerSpeaker(resampled)
        return packPcmFrames(prepared)
    }

    private fun parseWav(bytes: ByteArray): WavData {
        require(bytes.size >= 44) { "WAV file is too small" }
        require(readAscii(bytes, 0, 4) == "RIFF") { "Missing RIFF header" }
        require(readAscii(bytes, 8, 4) == "WAVE") { "Missing WAVE header" }

        var offset = 12
        var audioFormat = 0
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val chunkId = readAscii(bytes, offset, 4)
            val chunkSize = readInt32LE(bytes, offset + 4)
            val chunkDataOffset = offset + 8
            if (chunkDataOffset + chunkSize > bytes.size) {
                break
            }

            when (chunkId) {
                "fmt " -> {
                    require(chunkSize >= 16) { "Invalid fmt chunk" }
                    audioFormat = readInt16LE(bytes, chunkDataOffset)
                    channels = readInt16LE(bytes, chunkDataOffset + 2)
                    sampleRate = readInt32LE(bytes, chunkDataOffset + 4)
                    bitsPerSample = readInt16LE(bytes, chunkDataOffset + 14)
                }
                "data" -> {
                    dataOffset = chunkDataOffset
                    dataSize = chunkSize
                }
            }

            offset = chunkDataOffset + chunkSize + (chunkSize and 1)
        }

        require(audioFormat == 1) { "Only PCM WAV files are supported" }
        require(channels == 1 || channels == 2) { "Only mono or stereo WAV files are supported" }
        require(bitsPerSample == 8 || bitsPerSample == 16) { "Only 8-bit or 16-bit PCM WAV files are supported" }
        require(sampleRate > 0) { "Invalid WAV sample rate" }
        require(dataOffset >= 0 && dataSize > 0) { "Missing WAV data chunk" }

        return WavData(
            bytes = bytes,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            dataOffset = dataOffset,
            dataSize = dataSize,
        )
    }

    private fun resampleStereo16Interleaved(wav: WavData, targetSampleRate: Int): ShortArray {
        val bytesPerSample = wav.bitsPerSample / 8
        val frameSize = wav.channels * bytesPerSample
        val sourceFrames = wav.dataSize / frameSize
        val targetFrames = ceil(sourceFrames * (targetSampleRate.toDouble() / wav.sampleRate.toDouble()))
            .toInt()
            .coerceAtLeast(1)
        val output = ShortArray(targetFrames * 2)

        for (frameIndex in 0 until targetFrames) {
            val sourcePosition = frameIndex * wav.sampleRate / targetSampleRate.toDouble()
            val baseIndex = floor(sourcePosition).toInt().coerceIn(0, sourceFrames - 1)
            val nextIndex = (baseIndex + 1).coerceAtMost(sourceFrames - 1)
            val fraction = sourcePosition - floor(sourcePosition)

            val left = interpolateSample(wav, baseIndex, nextIndex, fraction, 0)
            val right = if (wav.channels == 1) {
                left
            } else {
                interpolateSample(wav, baseIndex, nextIndex, fraction, 1)
            }

            output[(frameIndex * 2) + 0] = normalizedToSigned16(left)
            output[(frameIndex * 2) + 1] = normalizedToSigned16(right)
        }

        return output
    }

    private fun interpolateSample(
        wav: WavData,
        baseIndex: Int,
        nextIndex: Int,
        fraction: Double,
        channel: Int,
    ): Double {
        val a = readNormalizedSample(wav, baseIndex, channel)
        val b = readNormalizedSample(wav, nextIndex, channel)
        return a + (b - a) * fraction
    }

    private fun readNormalizedSample(wav: WavData, frameIndex: Int, channel: Int): Double {
        val bytesPerSample = wav.bitsPerSample / 8
        val sampleOffset = wav.dataOffset + (frameIndex * wav.channels + channel) * bytesPerSample
        return when (wav.bitsPerSample) {
            16 -> readSignedInt16LE(wav.bytes, sampleOffset) / 32768.0
            8 -> (((wav.bytes[sampleOffset].toInt() and 0xFF) - 128) / 128.0)
            else -> 0.0
        }
    }

    private fun normalizedToSigned16(sample: Double): Short {
        val clamped = sample.coerceIn(-1.0, 1.0)
        return (clamped * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun prepareForControllerSpeaker(stereoInterleavedPcm16: ShortArray): ShortArray {
        if (stereoInterleavedPcm16.isEmpty()) {
            return ShortArray(0)
        }

        val output = ShortArray(stereoInterleavedPcm16.size)
        val rc = 1.0 / (2.0 * PI * CONTROLLER_SPEAKER_HIGH_PASS_HZ)
        val dt = 1.0 / PREPARATION_SAMPLE_RATE.toDouble()
        val alpha = rc / (rc + dt)
        val normalization = tanh(CONTROLLER_SPEAKER_SOFT_CLIP_DRIVE)

        var previousInputLeft = 0.0
        var previousOutputLeft = 0.0
        var previousInputRight = 0.0
        var previousOutputRight = 0.0

        var sampleIndex = 0
        while (sampleIndex + 1 < stereoInterleavedPcm16.size) {
            val left = stereoInterleavedPcm16[sampleIndex] / Short.MAX_VALUE.toDouble()
            val right = stereoInterleavedPcm16[sampleIndex + 1] / Short.MAX_VALUE.toDouble()

            val highPassedLeft = alpha * (previousOutputLeft + left - previousInputLeft)
            val highPassedRight = alpha * (previousOutputRight + right - previousInputRight)

            previousInputLeft = left
            previousOutputLeft = highPassedLeft
            previousInputRight = right
            previousOutputRight = highPassedRight

            val shapedLeft = tanh(highPassedLeft * CONTROLLER_SPEAKER_SOFT_CLIP_DRIVE) / normalization
            val shapedRight = tanh(highPassedRight * CONTROLLER_SPEAKER_SOFT_CLIP_DRIVE) / normalization
            val downmixed = ((shapedLeft + shapedRight) * 0.5) * CONTROLLER_SPEAKER_SIGNAL_GAIN
            val monoSample = normalizedToSigned16(downmixed)

            output[sampleIndex] = monoSample
            output[sampleIndex + 1] = monoSample
            sampleIndex += 2
        }

        return output
    }

    private fun packPcmFrames(preparedSamples: ShortArray): List<ByteArray> {
        if (preparedSamples.isEmpty()) {
            return listOf(ByteArray(PCM_BYTES_PER_FRAME))
        }

        val paddedSamples = alignUp(preparedSamples.size, SAMPLES_PER_FRAME_INTERLEAVED)
        val padded = ShortArray(paddedSamples)
        System.arraycopy(preparedSamples, 0, padded, 0, preparedSamples.size)

        val frameCount = padded.size / SAMPLES_PER_FRAME_INTERLEAVED
        val frames = ArrayList<ByteArray>(frameCount)

        for (frameIndex in 0 until frameCount) {
            val frame = ByteArray(PCM_BYTES_PER_FRAME)
            val baseSampleIndex = frameIndex * SAMPLES_PER_FRAME_INTERLEAVED
            for (sampleIndex in 0 until SAMPLES_PER_FRAME_INTERLEAVED) {
                val value = padded[baseSampleIndex + sampleIndex].toInt()
                val outputOffset = sampleIndex * 2
                frame[outputOffset] = (value and 0xFF).toByte()
                frame[outputOffset + 1] = ((value ushr 8) and 0xFF).toByte()
            }
            frames += frame
        }

        return frames
    }

    private fun alignUp(value: Int, multiple: Int): Int {
        require(multiple > 0) { "multiple must be positive" }
        if (value <= 0) {
            return multiple
        }
        val remainder = value % multiple
        return if (remainder == 0) value else value + (multiple - remainder)
    }

    private fun writeAudioConfig(
        report: ByteArray,
        off: Int,
        latency: Byte,
        counter: Byte,
        mode: Byte,
    ) {
        report[off + 0] = 0x91.toByte()
        report[off + 1] = 0x07
        report[off + 2] = 0xFE.toByte()
        report[off + 3] = latency
        report[off + 4] = latency
        report[off + 5] = latency
        report[off + 6] = latency
        report[off + 7] = latency
        report[off + 8] = counter
        report[off + 9] = mode
        report[off + 10] = 0xC8.toByte()
    }

    private fun writeBluetoothHapticsTail(
        report: ByteArray,
        offset: Int,
        pcmFrame1: ByteArray,
        pcmFrame2: ByteArray,
        hapticsGain: Double,
    ) {
        report.fill(0, offset, offset + HAPTICS_TAIL_LENGTH)
        report[offset + 0] = 0xD2.toByte()
        report[offset + 1] = 0x40
        writeBluetoothHapticsBlock(pcmFrame1, report, offset + 2, hapticsGain)
        writeBluetoothHapticsBlock(pcmFrame2, report, offset + 66, hapticsGain)
    }

    private fun writePackedBluetoothHapticsTail(
        report: ByteArray,
        offset: Int,
        packedFrame1: ByteArray,
        packedFrame2: ByteArray,
    ) {
        require(packedFrame1.size == DualSenseBtAudioHapticsBuilder.SAMPLE_BYTES)
        require(packedFrame2.size == DualSenseBtAudioHapticsBuilder.SAMPLE_BYTES)
        report.fill(0, offset, offset + HAPTICS_TAIL_LENGTH)
        report[offset + 0] = 0xD2.toByte()
        report[offset + 1] = 0x40
        System.arraycopy(packedFrame1, 0, report, offset + 2, packedFrame1.size)
        System.arraycopy(packedFrame2, 0, report, offset + 66, packedFrame2.size)
    }

    private fun writeBluetoothHapticsBlock(
        stereoPcmFrame: ByteArray,
        report: ByteArray,
        offset: Int,
        hapticsGain: Double,
    ) {
        require(stereoPcmFrame.size == PCM_BYTES_PER_FRAME) {
            "Bluetooth haptics PCM frames must be exactly $PCM_BYTES_PER_FRAME bytes"
        }

        for (outputIndex in 0 until OUTPUT_HAPTICS_SAMPLES) {
            var sumLeft = 0
            var sumRight = 0
            val baseInputOffset = outputIndex * INPUT_SAMPLES_PER_HAPTICS_SAMPLE * 4

            for (inputIndex in 0 until INPUT_SAMPLES_PER_HAPTICS_SAMPLE) {
                val sampleOffset = baseInputOffset + (inputIndex * 4)
                val left = readSignedInt16LE(stereoPcmFrame, sampleOffset)
                val right = readSignedInt16LE(stereoPcmFrame, sampleOffset + 2)
                sumLeft += left
                sumRight += right
            }

            val scaledLeft = ((sumLeft / INPUT_SAMPLES_PER_HAPTICS_SAMPLE.toDouble()) / 256.0 * hapticsGain)
                .roundToInt()
                .coerceIn(-128, 127)
            val scaledRight = ((sumRight / INPUT_SAMPLES_PER_HAPTICS_SAMPLE.toDouble()) / 256.0 * hapticsGain)
                .roundToInt()
                .coerceIn(-128, 127)

            val outputOffset = offset + (outputIndex * 2)
            report[outputOffset + 0] = scaledLeft.toByte()
            report[outputOffset + 1] = scaledRight.toByte()
        }
    }

    private fun writeCrc(report: ByteArray, crcOffset: Int) {
        val crc = DualSenseBtCrc.compute(report, crcOffset)
        report[crcOffset] = (crc and 0xFF).toByte()
        report[crcOffset + 1] = ((crc ushr 8) and 0xFF).toByte()
        report[crcOffset + 2] = ((crc ushr 16) and 0xFF).toByte()
        report[crcOffset + 3] = ((crc ushr 24) and 0xFF).toByte()
    }

    private fun nextFrameCtr(count: Int): Byte {
        val previous = lastCount
        lastCount = count
        frameCtr = when {
            previous == 0 -> 0
            previous == 1 -> (frameCtr + 1) and 0xFF
            else -> (frameCtr + 2) and 0xFF
        }
        return frameCtr.toByte()
    }

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun readInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readSignedInt16LE(bytes: ByteArray, offset: Int): Int {
        val value = readInt16LE(bytes, offset)
        return if ((value and 0x8000) != 0) value - 0x10000 else value
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
