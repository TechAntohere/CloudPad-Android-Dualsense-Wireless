// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

object DualSenseBtMicControl
{
    data class Result(
        val micOk: Boolean,
        val ledOk: Boolean,
        val message: String,
    )

    fun forceClose(
        bridge: DualSenseBtHidBridge,
        transportMode: DualSenseBtHidBridge.OutputTransportMode,
        nextSequence: () -> Int,
        reason: String,
        closeRepeats: Int,
        ledRepeats: Int,
        settleDelaysMs: LongArray,
    ): Result
    {
        var closeOk = false
        var closeMessage = "not sent"
        repeat(closeRepeats.coerceAtLeast(0)) { index ->
            val result = bridge.sendOutputReport(
                DualSenseBtAudioHapticsBuilder.buildMicControlReport(nextSequence() and 0xFF, open = false),
                streaming = true,
                transportMode = transportMode,
            )
            closeOk = closeOk || result.success
            closeMessage = result.message
            if(index != closeRepeats - 1)
                Thread.sleep(40L)
        }

        var ledResult = sendMuteLedOffBurst(bridge, transportMode, ledRepeats)
        for(delayMs in settleDelaysMs)
        {
            Thread.sleep(delayMs)
            val closeResult = bridge.sendOutputReport(
                DualSenseBtAudioHapticsBuilder.buildMicControlReport(nextSequence() and 0xFF, open = false),
                streaming = true,
                transportMode = transportMode,
            )
            closeOk = closeOk || closeResult.success
            closeMessage = closeResult.message
            ledResult = ledResult.merge(sendMuteLedOffBurst(bridge, transportMode, repeats = 2))
        }

        return Result(
            micOk = closeOk,
            ledOk = ledResult.ledOk,
            message = "close($reason): mic=${if(closeOk) "ok" else "fail"} $closeMessage; led=${if(ledResult.ledOk) "ok" else "fail"} ${ledResult.message}",
        )
    }

    fun sendMuteLedOffBurst(
        bridge: DualSenseBtHidBridge,
        transportMode: DualSenseBtHidBridge.OutputTransportMode,
        repeats: Int,
    ): Result
    {
        var ledOk = false
        var message = "not sent"
        repeat(repeats.coerceAtLeast(0)) { index ->
            val controllerData = bridge.sendOutputReport(
                DualSenseBtReportBuilder.buildControllerDataMuteLedReport(0),
                streaming = true,
                transportMode = transportMode,
            )
            val regularOutput = bridge.sendOutputReport(
                DualSenseBtReportBuilder.buildRegularVisualReport(),
                streaming = true,
                transportMode = transportMode,
            )
            ledOk = ledOk || controllerData.success || regularOutput.success
            message = "0x32=${if(controllerData.success) "ok" else "fail"} ${controllerData.message}; 0x31=${if(regularOutput.success) "ok" else "fail"} ${regularOutput.message}"
            if(index != repeats - 1)
                Thread.sleep(40L)
        }
        return Result(micOk = false, ledOk = ledOk, message = message)
    }

    private fun Result.merge(other: Result): Result =
        Result(
            micOk = micOk || other.micOk,
            ledOk = ledOk || other.ledOk,
            message = other.message,
        )
}
