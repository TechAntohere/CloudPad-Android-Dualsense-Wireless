// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.os.SystemClock
import com.metallic.chiaki.settings.DualSenseBtSpeakerAudio

/**
 * Thread-safe singleton bus for live Android system-audio Opus frames.
 *
 * The MediaProjection capture service pushes locally encoded 10 ms Opus frames
 * here. The BT speaker thread pops one stereo pair per 0x39 report so live audio
 * stays real-time instead of draining stale backlog in bursts.
 */
object ControllerSpeakerBus {

    data class DebugSnapshot(
        val enabled: Boolean,
        val depth: Int,
        val framesIn: Long,
        val framesOut: Long,
        val overflowDrops: Long,
        val emptyPops: Long,
        val concealedPops: Long,
        val flushes: Long,
        val lastPushAgeMs: Long,
        val lastPopAgeMs: Long,
    )

    /** Set true via the Settings toggle to enable live audio streaming. */
    @Volatile var enabled: Boolean = false

    /** Small real-time FIFO. Overflow drops oldest audio to avoid stale speaker output. */
    private const val MAX_QUEUE = 4
    private const val MAX_CONCEALED_POPS = 2
    private const val CONCEALMENT_WINDOW_NS = 55_000_000L

    /** Expected Opus frame size — DualSense spec: 200 bytes per frame. */
    private val EXPECTED_OPUS_BYTES = DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME

    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()
    private var silenceFrame: ByteArray? = null
    private var lastRealFrame: ByteArray? = null
    private var consecutiveConcealedPops = 0
    private var framesIn = 0L
    private var framesOut = 0L
    private var overflowDrops = 0L
    private var emptyPops = 0L
    private var concealedPops = 0L
    private var flushes = 0L
    private var lastPushNs = 0L
    private var lastPopNs = 0L

    fun onSpeakerFrame(data: ByteArray) {
        if (!enabled) return
        if (data.size != EXPECTED_OPUS_BYTES) return
        synchronized(lock) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            while (queue.size >= MAX_QUEUE) {
                queue.removeFirst()
                overflowDrops++
            }
            queue.addLast(data.copyOf())
            framesIn++
            lastPushNs = nowNs
        }
    }

    fun popFrame(): ByteArray? {
        if (!enabled) {
            synchronized(lock) { emptyPops++ }
            return null
        }
        return synchronized(lock) {
            if (queue.isEmpty()) {
                val nowNs = SystemClock.elapsedRealtimeNanos()
                val recentRealFrame = lastPopNs > 0L && nowNs - lastPopNs <= CONCEALMENT_WINDOW_NS
                val concealed = lastRealFrame
                if (concealed != null && recentRealFrame && consecutiveConcealedPops < MAX_CONCEALED_POPS) {
                    consecutiveConcealedPops++
                    concealedPops++
                    framesOut++
                    lastPopNs = nowNs
                    concealed.copyOf()
                } else {
                    emptyPops++
                    null
                }
            } else {
                val frame = queue.removeFirst()
                lastRealFrame = frame.copyOf()
                consecutiveConcealedPops = 0
                framesOut++
                lastPopNs = SystemClock.elapsedRealtimeNanos()
                frame
            }
        }
    }

    fun setSilenceFrame(data: ByteArray) {
        if (data.size != EXPECTED_OPUS_BYTES) return
        synchronized(lock) {
            silenceFrame = data.copyOf()
        }
    }

    fun getSilenceFrame(): ByteArray? = synchronized(lock) { silenceFrame }

    fun depth(): Int = synchronized(lock) { queue.size }

    /** Remove any buffered frames without processing them. */
    fun flush() = synchronized(lock) {
        queue.clear()
        consecutiveConcealedPops = 0
        flushes++
    }

    fun debugSnapshot(): DebugSnapshot = synchronized(lock) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        DebugSnapshot(
            enabled = enabled,
            depth = queue.size,
            framesIn = framesIn,
            framesOut = framesOut,
            overflowDrops = overflowDrops,
            emptyPops = emptyPops,
            concealedPops = concealedPops,
            flushes = flushes,
            lastPushAgeMs = ageMs(nowNs, lastPushNs),
            lastPopAgeMs = ageMs(nowNs, lastPopNs),
        )
    }

    private fun ageMs(nowNs: Long, eventNs: Long): Long =
        if(eventNs <= 0L || nowNs < eventNs) -1L else (nowNs - eventNs) / 1_000_000L
}
