// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import kotlin.math.ceil
import kotlin.math.floor

object DualSenseBtAudioWaveClip
{
    const val DEFAULT_ASSET_PATH = "haptics/savannah.wav"
    const val FULL_SONG_ASSET_PATH = DEFAULT_ASSET_PATH
    private const val TARGET_SAMPLE_RATE = 3000.0
    private const val TARGET_SAMPLE_BYTES = 64
    // Remote Play delivers native haptics to Android in 90-frame / ~30 ms callbacks.
    // Keep the tester shaped like the real JNI path instead of over-driving it with
    // synthetic 30-frame / 10 ms callbacks.
    private const val STREAM_FRAME_FRAMES = 90
    private const val STREAM_FRAME_BYTES = STREAM_FRAME_FRAMES * 2 * 2
    private const val OUTPUT_GAIN = 1.45

    data class LoadedClip(
        val reports: List<ByteArray>,
        val durationMs: Long,
        val streamFrames: List<ByteArray> = emptyList(),
    )

    private data class WavData(
        val bytes: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val dataSize: Int,
    )

    fun loadFromAssets(context: Context, assetPath: String = DEFAULT_ASSET_PATH): LoadedClip
    {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        return load(bytes)
    }

    fun load(bytes: ByteArray): LoadedClip
    {
        val wav = parseWav(bytes)
        val interleaved = resampleToBtHaptics(wav)
        val streamPcm = resampleToStreamPcm16(wav)
        val reports = buildReports(interleaved)
        val durationMs = ((streamPcm.size / 4L) * 1000L) / TARGET_SAMPLE_RATE.toLong()
        return LoadedClip(
            reports = reports,
            durationMs = durationMs,
            streamFrames = buildStreamFrames(streamPcm),
        )
    }

    private fun parseWav(bytes: ByteArray): WavData
    {
        require(bytes.size >= 44) { "WAV file is too small" }
        require(readAscii(bytes, 0, 4) == "RIFF") { "Missing RIFF header" }
        require(readAscii(bytes, 8, 4) == "WAVE") { "Missing WAVE header" }

        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var dataOffset = -1
        var dataSize = 0

        while(offset + 8 <= bytes.size)
        {
            val chunkId = readAscii(bytes, offset, 4)
            val chunkSize = readInt32LE(bytes, offset + 4)
            val chunkDataOffset = offset + 8
            if(chunkDataOffset + chunkSize > bytes.size)
                break

            when(chunkId)
            {
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

    private fun resampleToBtHaptics(wav: WavData): ByteArray
    {
        val frameSize = wav.channels * (wav.bitsPerSample / 8)
        val sourceFrames = wav.dataSize / frameSize
        val targetFrames = ceil(sourceFrames * (TARGET_SAMPLE_RATE / wav.sampleRate)).toInt().coerceAtLeast(1)
        val output = ByteArray(targetFrames * 2)

        for(frameIndex in 0 until targetFrames)
        {
            val sourcePosition = frameIndex * wav.sampleRate / TARGET_SAMPLE_RATE
            val baseIndex = floor(sourcePosition).toInt().coerceIn(0, sourceFrames - 1)
            val nextIndex = (baseIndex + 1).coerceAtMost(sourceFrames - 1)
            val fraction = sourcePosition - floor(sourcePosition)

            val left = interpolateSample(wav, baseIndex, nextIndex, fraction, 0)
            val right = if(wav.channels == 1) left else interpolateSample(wav, baseIndex, nextIndex, fraction, 1)

            output[frameIndex * 2] = normalizeToSigned8(left)
            output[frameIndex * 2 + 1] = normalizeToSigned8(right)
        }

        return output
    }

    private fun resampleToStreamPcm16(wav: WavData): ByteArray
    {
        val frameSize = wav.channels * (wav.bitsPerSample / 8)
        val sourceFrames = wav.dataSize / frameSize
        val targetFrames = ceil(sourceFrames * (TARGET_SAMPLE_RATE / wav.sampleRate)).toInt().coerceAtLeast(1)
        val output = ByteArray(targetFrames * 4)

        for(frameIndex in 0 until targetFrames)
        {
            val sourcePosition = frameIndex * wav.sampleRate / TARGET_SAMPLE_RATE
            val baseIndex = floor(sourcePosition).toInt().coerceIn(0, sourceFrames - 1)
            val nextIndex = (baseIndex + 1).coerceAtMost(sourceFrames - 1)
            val fraction = sourcePosition - floor(sourcePosition)

            val left = interpolateSample(wav, baseIndex, nextIndex, fraction, 0)
            val right = if(wav.channels == 1) left else interpolateSample(wav, baseIndex, nextIndex, fraction, 1)

            writeSignedInt16LE(output, frameIndex * 4, normalizeToSigned16(left))
            writeSignedInt16LE(output, frameIndex * 4 + 2, normalizeToSigned16(right))
        }

        return output
    }

    private fun interpolateSample(wav: WavData, baseIndex: Int, nextIndex: Int, fraction: Double, channel: Int): Double
    {
        val a = readNormalizedSample(wav, baseIndex, channel)
        val b = readNormalizedSample(wav, nextIndex, channel)
        return a + (b - a) * fraction
    }

    private fun readNormalizedSample(wav: WavData, frameIndex: Int, channel: Int): Double
    {
        val bytesPerSample = wav.bitsPerSample / 8
        val sampleOffset = wav.dataOffset + (frameIndex * wav.channels + channel) * bytesPerSample
        return when(wav.bitsPerSample)
        {
            16 -> readSignedInt16LE(wav.bytes, sampleOffset).toDouble() / 32768.0
            8 -> ((wav.bytes[sampleOffset].toInt() and 0xFF) - 128) / 128.0
            else -> 0.0
        }
    }

    private fun normalizeToSigned8(sample: Double): Byte
    {
        val shaped = (sample * OUTPUT_GAIN).coerceIn(-1.0, 1.0)
        return (shaped * 127.0).toInt().coerceIn(-127, 127).toByte()
    }

    private fun normalizeToSigned16(sample: Double): Int
    {
        val shaped = (sample * OUTPUT_GAIN).coerceIn(-1.0, 1.0)
        return (shaped * 32767.0).toInt().coerceIn(-32767, 32767)
    }

    private fun buildReports(interleavedSamples: ByteArray): List<ByteArray>
    {
        val reports = ArrayList<ByteArray>()
        var offset = 0
        var sequence = 0
        while(offset < interleavedSamples.size)
        {
            val chunkSize = minOf(TARGET_SAMPLE_BYTES, interleavedSamples.size - offset)
            val chunk = ByteArray(chunkSize)
            System.arraycopy(interleavedSamples, offset, chunk, 0, chunkSize)
            reports += DualSenseBtAudioHapticsBuilder.buildSampleReport(sequence, chunk)
            sequence = (sequence + 1) and 0xFF
            offset += TARGET_SAMPLE_BYTES
        }
        if(reports.isEmpty())
            reports += DualSenseBtAudioHapticsBuilder.buildSilenceReport(0)
        return reports
    }

    private fun buildStreamFrames(interleavedPcm16: ByteArray): List<ByteArray>
    {
        val frames = ArrayList<ByteArray>((interleavedPcm16.size + STREAM_FRAME_BYTES - 1) / STREAM_FRAME_BYTES)
        var offset = 0
        while(offset < interleavedPcm16.size)
        {
            val frame = ByteArray(STREAM_FRAME_BYTES)
            val copySize = minOf(STREAM_FRAME_BYTES, interleavedPcm16.size - offset)
            System.arraycopy(interleavedPcm16, offset, frame, 0, copySize)
            frames += frame
            offset += STREAM_FRAME_BYTES
        }
        if(frames.isEmpty())
            frames += ByteArray(STREAM_FRAME_BYTES)
        return frames
    }

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

    private fun readInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readSignedInt16LE(bytes: ByteArray, offset: Int): Int
    {
        val value = readInt16LE(bytes, offset)
        return if((value and 0x8000) != 0) value - 0x10000 else value
    }

    private fun writeSignedInt16LE(bytes: ByteArray, offset: Int, value: Int)
    {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
