// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.os.Handler
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

class DualSenseBtMicTestSession(
    private val bridge: DualSenseBtHidBridge,
    private val workerHandler: Handler,
    private val onUpdate: (String) -> Unit,
)
{
    @Volatile private var running = false
    @Volatile private var micOpen = false
    private var listenerCloseable: Closeable? = null
    private var outputSequence = 0
    private var transportMode = DualSenseBtHidBridge.OutputTransportMode.AUTO
    private var startedAtMs = 0L
    private var lastSendStatus = "not started"
    private var lastButtonDown = false
    private var lastMicPresent: Boolean? = null
    private var lastMicMuted: Boolean? = null
    private var lastPolledInputReport = "not polled"

    private val hidReports = AtomicLong()
    private val micReports = AtomicLong()
    private val acceptedMicReports = AtomicLong()
    private val droppedMicReports = AtomicLong()
    private val micPayloadBytes = AtomicLong()
    private val buttonToggles = AtomicLong()
    private val polledInputReports = AtomicLong()
    private val polledMicReports = AtomicLong()
    private val pollFailures = AtomicLong()
    private val containedKeyEvents = AtomicLong()
    private val containedTouchEvents = AtomicLong()
    private val containedMotionEvents = AtomicLong()
    private val containedCapturedPointerEvents = AtomicLong()

    val isRunning: Boolean
        get() = running

    private val tickRunnable = object : Runnable
    {
        override fun run()
        {
            if(!running)
                return
            onUpdate(summary())
            workerHandler.postDelayed(this, 1000L)
        }
    }

    private val pollRunnable = object : Runnable
    {
        override fun run()
        {
            if(!running)
                return
            pollInputReportOnce()
            workerHandler.postDelayed(this, 250L)
        }
    }

    fun start()
    {
        if(running)
            return
        onUpdate("DualSense BT mic receive test is disabled. Sending mic-close only.")
        workerHandler.post {
            bridge.refresh()
            transportMode = bridge.resolveBestAvailableTransport(
                DualSenseBtHidBridge.OutputTransportMode.SEND_DATA_RAW,
                DualSenseBtHidBridge.OutputTransportMode.SEND_DATA_HEX,
                DualSenseBtHidBridge.OutputTransportMode.SET_REPORT_RAW,
                DualSenseBtHidBridge.OutputTransportMode.SET_REPORT_HEX,
            )
            sendMicState(open = false, reason = "disabled", showMutedLed = false, repeatsOverride = 4, ledRepeatsOverride = 4)
            onUpdate("DualSense BT mic disabled.\n${summaryBody()}")
        }
    }

    fun stop(reason: String = "stop")
    {
        if(!running)
            return
        running = false
        workerHandler.removeCallbacks(tickRunnable)
        workerHandler.removeCallbacks(pollRunnable)
        workerHandler.post {
            sendMicState(open = false, reason = reason, showMutedLed = false, repeatsOverride = 4, ledRepeatsOverride = 4)
            listenerCloseable?.close()
            listenerCloseable = null
            onUpdate("BT mic receive test stopped ($reason).\n${summaryBody()}")
        }
    }

    fun noteContainedInput(kind: String)
    {
        when(kind)
        {
            "key" -> containedKeyEvents.incrementAndGet()
            "touch" -> containedTouchEvents.incrementAndGet()
            "motion" -> containedMotionEvents.incrementAndGet()
            "captured" -> containedCapturedPointerEvents.incrementAndGet()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun sendMicState(
        open: Boolean,
        reason: String,
        showMutedLed: Boolean,
        repeatsOverride: Int? = null,
        ledRepeatsOverride: Int? = null,
    )
    {
        if(!open)
        {
            val closeResult = DualSenseBtMicControl.forceClose(
                bridge = bridge,
                transportMode = transportMode,
                nextSequence = ::nextMicSequence,
                reason = reason,
                closeRepeats = repeatsOverride ?: 3,
                ledRepeats = ledRepeatsOverride ?: 3,
                settleDelaysMs = if(reason == "preclose") longArrayOf() else longArrayOf(250L, 750L, 1250L),
            )
            micOpen = false
            lastSendStatus = closeResult.message
            return
        }

        val repeats = repeatsOverride ?: if(open) 3 else 3
        var micSent = false
        var micMessage = "not sent"
        repeat(repeats) { index ->
            val report = DualSenseBtAudioHapticsBuilder.buildMicControlReport(nextMicSequence(), open)
            val result = bridge.sendOutputReport(
                report,
                streaming = true,
                transportMode = transportMode,
            )
            micSent = micSent || result.success
            micMessage = result.message
            if(index != repeats - 1)
                Thread.sleep(40L)
        }

        val ledResult = DualSenseBtMicControl.sendMuteLedOffBurst(
            bridge = bridge,
            transportMode = transportMode,
            repeats = ledRepeatsOverride ?: 1,
        )
        micOpen = open
        lastSendStatus =
            "open($reason): mic=${if(micSent) "ok" else "fail"} $micMessage; led=${if(ledResult.ledOk) "ok" else "fail"} ${ledResult.message}"
    }

    private fun nextMicSequence(): Int = outputSequence++ and 0xFF

    private fun pollInputReportOnce()
    {
        val result = bridge.requestInputReport(reportId = 0x31, bufferSize = 77, timeoutMs = 120L)
        val report = result.report
        if(result.success && report != null)
        {
            polledInputReports.incrementAndGet()
            val header = if(report.size > 1) report[1].toInt() and 0xFF else 0
            val hasHid = (header and 0x01) != 0
            val hasMic = (header and 0x02) != 0
            if(hasMic)
                polledMicReports.incrementAndGet()
            if(hasHid && report.size >= 56)
            {
                lastMicPresent = (report[55].toInt() and 0x02) != 0
                lastMicMuted = (report[55].toInt() and 0x04) != 0
            }
            lastPolledInputReport =
                "ok len=${report.size} header=0x${header.hex2()} hid=${yesNo(hasHid)} mic=${yesNo(hasMic)} ${report.previewHex(16)}"
        }
        else
        {
            pollFailures.incrementAndGet()
            lastPolledInputReport = result.message
        }
    }

    private fun handleReport(reportType: Int, reportId: Int, report: ByteArray)
    {
        if(!running || report.size < 2 || (report[0].toInt() and 0xFF) != 0x31)
            return

        val header = report[1].toInt() and 0xFF
        val hasHid = (header and 0x01) != 0
        val hasMic = (header and 0x02) != 0

        if(hasHid)
        {
            hidReports.incrementAndGet()
            if(report.size >= 56)
            {
                val muteButtonDown = (report[11].toInt() and 0x04) != 0
                if(muteButtonDown && !lastButtonDown)
                {
                    buttonToggles.incrementAndGet()
                    workerHandler.post {
                        if(running)
                            sendMicState(open = !micOpen, reason = "mute button", showMutedLed = true)
                        if(running)
                            onUpdate(summary())
                    }
                }
                lastButtonDown = muteButtonDown
                lastMicPresent = (report[55].toInt() and 0x02) != 0
                lastMicMuted = (report[55].toInt() and 0x04) != 0
            }
        }

        if(hasMic)
        {
            micReports.incrementAndGet()
            if(micOpen)
            {
                acceptedMicReports.incrementAndGet()
                if(report.size >= 74)
                    micPayloadBytes.addAndGet(71L)
            }
            else
            {
                droppedMicReports.incrementAndGet()
            }
        }
    }

    private fun summary(): String =
        "BT mic receive test running.\n${summaryBody()}"

    private fun summaryBody(): String
    {
        val elapsedSeconds = ((SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L) / 1000L)
        val statusMic = when
        {
            lastMicPresent == null && lastMicMuted == null -> "unknown"
            else -> "present=${yesNo(lastMicPresent == true)}, muted=${yesNo(lastMicMuted == true)}"
        }
        return "Elapsed: ${elapsedSeconds}s\n" +
            "Mic stream: ${if(micOpen) "open" else "closed"}\n" +
            "Input reports: hid=${hidReports.get()}, mic=${micReports.get()}, accepted=${acceptedMicReports.get()}, dropped=${droppedMicReports.get()}\n" +
            "Polled 0x31: reports=${polledInputReports.get()}, mic=${polledMicReports.get()}, failures=${pollFailures.get()}\n" +
            "Last polled 0x31: $lastPolledInputReport\n" +
            "Mic payload: ${micPayloadBytes.get()} bytes\n" +
            "Mute button toggles: ${buttonToggles.get()}\n" +
            "Contained input: key=${containedKeyEvents.get()}, touch=${containedTouchEvents.get()}, motion=${containedMotionEvents.get()}, captured=${containedCapturedPointerEvents.get()}\n" +
            "Controller status: $statusMic\n" +
            "Transport: ${transportMode.transportName}\n" +
            "Last send: $lastSendStatus"
    }

    private fun yesNo(value: Boolean): String = if(value) "yes" else "no"

    private fun Int.hex2(): String = "%02X".format(this and 0xFF)

    private fun ByteArray.previewHex(maxBytes: Int): String
    {
        if(isEmpty())
            return ""
        return take(maxBytes).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } +
            if(size > maxBytes) " ..." else ""
    }
}
