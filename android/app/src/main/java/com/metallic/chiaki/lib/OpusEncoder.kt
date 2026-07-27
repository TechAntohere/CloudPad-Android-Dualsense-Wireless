// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.lib

/**
 * Thin Kotlin wrapper around the native libopus encoder.
 * Used by [com.metallic.chiaki.stream.ControllerSystemAudioService] to encode
 * 48 kHz stereo PCM into Opus frames for the DualSense BT speaker path.
 */
object OpusEncoderNative {
    init {
        System.loadLibrary("chiaki-jni")
    }

    @JvmStatic external fun create(sampleRate: Int, channels: Int): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun encode(
        handle: Long,
        pcmBuf: ShortArray, pcmOffset: Int, frameSize: Int,
        outBuf: ByteArray,  outOffset: Int,  maxBytes: Int
    ): Int
    @JvmStatic external fun reset(handle: Long)
}

/**
 * RAII wrapper — allocates the encoder on construction, closes it with [close].
 *
 * @param sampleRate  Must be 48000 for DualSense speaker path.
 * @param channels    Must be 2 (stereo).
 */
class OpusEncoder(sampleRate: Int = 48_000, channels: Int = 2) : AutoCloseable {

    private var handle: Long = OpusEncoderNative.create(sampleRate, channels)
    val isValid get() = handle != 0L

    /**
     * Encode one frame of interleaved PCM16 samples.
     *
     * @param pcm       Interleaved short samples (L R L R …).
     * @param frameSize Samples per channel (e.g. 480 for a 10 ms frame at 48 kHz).
     * @param out       Output byte array; must be at least [maxBytes] long.
     * @param maxBytes  Maximum output size — 200 for the DualSense speaker path.
     * @return Number of bytes written, or ≤ 0 on error.
     */
    fun encode(
        pcm: ShortArray,
        frameSize: Int = 480,
        out: ByteArray = ByteArray(200),
        maxBytes: Int = out.size
    ): Int {
        if (!isValid) return -1
        return OpusEncoderNative.encode(handle, pcm, 0, frameSize, out, 0, maxBytes)
    }

    fun reset() { if (isValid) OpusEncoderNative.reset(handle) }

    override fun close() {
        if (handle != 0L) {
            OpusEncoderNative.destroy(handle)
            handle = 0L
        }
    }
}
