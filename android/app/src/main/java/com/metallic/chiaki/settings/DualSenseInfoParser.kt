// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import java.nio.charset.Charset
import kotlin.math.min

object DualSenseInfoParser
{
    private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
    private val UTF8: Charset = Charsets.UTF_8
    private val COLOR_MAP = mapOf(
        "00" to "white",
        "01" to "midnight black",
        "02" to "cosmic red",
        "03" to "nova pink",
        "04" to "galactic purple",
        "05" to "starlight blue",
        "06" to "grey camouflage",
        "07" to "volcanic red",
        "08" to "sterling silver",
        "09" to "cobalt blue",
        "10" to "chroma teal",
        "11" to "chroma indigo",
        "12" to "chroma pearl",
        "30" to "30th anniversary",
        "Z1" to "god of war ragnarok",
        "Z2" to "spider-man 2",
        "Z3" to "astro bot",
        "Z4" to "fortnite",
        "Z6" to "the last of us",
        "ZB" to "icon blue limited edition",
        "ZE" to "genshin impact",
    )

    data class FirmwareInfo(
        val buildDate: String,
        val buildTime: String,
        val fwType: Int,
        val swSeries: Int,
        val hwInfo: Long,
        val mainFwVersion: Long,
        val deviceInfo: ByteArray,
        val updateVersion: Int,
        val updateImageInfo: Int,
        val sblFwVersion: Long,
        val dspFwVersion: Long,
        val spiderDspFwVersion: Long,
    )

    data class InputStatus(
        val reportId: Int,
        val sequenceTag: Int,
        val batteryPercent: String,
        val powerState: String,
        val headphonesPlugged: Boolean,
        val micPlugged: Boolean,
        val micMuted: Boolean,
        val usbData: Boolean,
        val usbPower: Boolean,
        val hapticLowPassFilter: Boolean,
    )

    data class TestTraceabilityInfo(
        val serialNo: String,
        val motorInfo: String,
    )

    fun buildFeatureReport(reportId: Int, payload: ByteArray, reportSize: Int = 64): ByteArray
    {
        val report = ByteArray(reportSize)
        report[0] = reportId.toByte()
        System.arraycopy(payload, 0, report, 1, min(payload.size, reportSize - 5))
        writeBtFeatureCrc(report)
        return report
    }

    fun parseFirmware(report: ByteArray?): FirmwareInfo?
    {
        if(report == null || report.size < 60 || (report[0].toInt() and 0xFF) != 0x20)
            return null
        return FirmwareInfo(
            buildDate = cleanString(report, 1, 11, UTF8),
            buildTime = cleanString(report, 12, 8, UTF8),
            fwType = le16(report, 20),
            swSeries = le16(report, 22),
            hwInfo = le32(report, 24),
            mainFwVersion = le32(report, 28),
            deviceInfo = report.copyOfRange(32, 44),
            updateVersion = le16(report, 44),
            updateImageInfo = report[46].toInt() and 0xFF,
            sblFwVersion = le32(report, 48),
            dspFwVersion = le32(report, 52),
            spiderDspFwVersion = le32(report, 56),
        )
    }

    fun parseInputStatus(report: ByteArray?): InputStatus?
    {
        if(report == null || report.isEmpty())
            return null
        val reportId = report[0].toInt() and 0xFF
        val status0Index: Int
        val status1Index: Int
        val status2Index: Int
        val sequenceTag: Int
        when(reportId)
        {
            0x31 -> {
                if(report.size < 57)
                    return null
                sequenceTag = report[1].toInt() and 0xFF
                status0Index = 54
                status1Index = 55
                status2Index = 56
            }
            0x01 -> return null
            else -> return null
        }

        val status0 = report[status0Index].toInt() and 0xFF
        val status1 = report[status1Index].toInt() and 0xFF
        val status2 = report[status2Index].toInt() and 0xFF
        val battery = status0 and 0x0F
        val power = (status0 ushr 4) and 0x0F
        val displayedBattery = if(power == 0x2) 10 else battery
        return InputStatus(
            reportId = reportId,
            sequenceTag = sequenceTag,
            batteryPercent = if(displayedBattery <= 10) "${displayedBattery * 10}%" else "unknown ($battery)",
            powerState = powerStateName(power),
            headphonesPlugged = (status1 and 0x01) != 0,
            micPlugged = (status1 and 0x02) != 0,
            micMuted = (status1 and 0x04) != 0,
            usbData = (status1 and 0x08) != 0,
            usbPower = (status1 and 0x10) != 0,
            hapticLowPassFilter = (status2 and 0x02) != 0,
        )
    }

    fun parseBtPatchInfo(report: ByteArray?): Long?
    {
        if(report == null || report.size < 35 || (report[0].toInt() and 0xFF) != 0x22)
            return null
        return le32(report, 31)
    }

    fun firmwareRows(info: FirmwareInfo): List<Pair<String, String>>
    {
        return listOf(
            "Build time" to "${info.buildDate} ${info.buildTime}".trim(),
            "Hardware info" to "${hex(info.hwInfo, 8)} (${info.hwInfo})",
            "Device info" to info.deviceInfo.toHex(),
            "FW type" to "${hex(info.fwType.toLong(), 4)} (${info.fwType})",
            "SW series" to "${hex(info.swSeries.toLong(), 4)} (${info.swSeries})",
            "Update version" to formatUpdateVersion(info.updateVersion),
            "SBL FW" to formatThreePartVersion(info.sblFwVersion),
            "Main FW" to formatThreePartVersion(info.mainFwVersion),
            "DSP FW" to formatDspVersion(info.dspFwVersion),
            "MCU DSP FW" to formatThreePartVersion(info.spiderDspFwVersion),
        )
    }

    fun inputRows(status: InputStatus): List<Pair<String, String>>
    {
        return listOf(
            "Input report" to "0x${status.reportId.toString(16).uppercase().padStart(2, '0')} seq=0x${status.sequenceTag.toString(16).uppercase().padStart(2, '0')}",
            "Battery" to "${status.batteryPercent} / ${status.powerState}",
            "Headphones" to yesNo(status.headphonesPlugged),
            "Mic" to "${yesNo(status.micPlugged)} / muted=${yesNo(status.micMuted)}",
            "USB data/power" to "${yesNo(status.usbData)} / ${yesNo(status.usbPower)}",
            "Haptic LPF" to yesNo(status.hapticLowPassFilter),
        )
    }

    fun serialDerivedRows(serial: String): List<Pair<String, String>>
    {
        val clean = serial.trim()
        val rows = ArrayList<Pair<String, String>>()
        if(clean.length >= 6)
        {
            val colorCode = clean.substring(4, 6)
            rows += "Controller shell color" to (COLOR_MAP[colorCode] ?: "unknown ($colorCode)")
        }
        else
        {
            rows += "Controller shell color" to "unavailable (serial too short)"
        }
        if(clean.length >= 2)
        {
            val board = clean.substring(1, 2)
            if(board in setOf("1", "2", "3", "4", "5"))
                rows += "Board version" to "BDM-0${board}0"
            else
                rows += "Board version" to "unknown ($board)"
        }
        return rows
    }

    fun traceabilityInfo(report: ByteArray?): TestTraceabilityInfo?
    {
        if(report == null || report.size < 27 || (report[0].toInt() and 0xFF) != 0)
            return null
        val serial = buildString {
            append('\u202D')
            append('\u202C')
            for(i in 12 until 19)
                append((report[i].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
        }
        return TestTraceabilityInfo(
            serialNo = serial,
            motorInfo = cleanString(report, 19, 8, SHIFT_JIS),
        )
    }

    fun bytesToShiftJis(bytes: ByteArray?): String?
    {
        if(bytes == null)
            return null
        return cleanString(bytes, 0, bytes.size, SHIFT_JIS)
    }

    fun pcbaId(bytes: ByteArray?): String?
    {
        if(bytes == null || bytes.size < 6)
            return null
        return hex(le48(bytes, 0), 12)
    }

    fun pcbaIdFull(bytes: ByteArray?): String?
    {
        if(bytes == null)
            return null
        return cleanString(bytes, 0, bytes.size, SHIFT_JIS).reversed()
    }

    fun uniqueId(bytes: ByteArray?): String?
    {
        if(bytes == null || bytes.size < 9 || bytes[0].toInt() != 0)
            return null
        return hex(le64(bytes, 1), 16)
    }

    fun macAddress(bytes: ByteArray?): String?
    {
        if(bytes == null || bytes.size < 6)
            return null
        return (5 downTo 0).joinToString(":") { i ->
            (bytes[i].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }
    }

    fun moduleBarcode(bytes: ByteArray?): String?
    {
        if(bytes == null || bytes.size < 0x22)
            return null
        val left = cleanString(bytes, 0x11, 0x11, SHIFT_JIS)
        val right = cleanString(bytes, 0x22, min(0x11, bytes.size - 0x22), SHIFT_JIS)
        return listOf(left, right).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { null }
    }

    fun hex(value: Long, digits: Int): String = "0x" + value.toString(16).uppercase().padStart(digits, '0')

    private fun writeBtFeatureCrc(report: ByteArray)
    {
        if(report.size < 5)
            return
        var crc = -1
        crc = crc32Byte(crc, 0x53)
        crc = crc32Byte(crc, report[0].toInt() and 0xFF)
        for(i in 1 until report.size - 4)
            crc = crc32Byte(crc, report[i].toInt() and 0xFF)
        crc = crc.inv()
        val offset = report.size - 4
        report[offset] = crc.toByte()
        report[offset + 1] = (crc ushr 8).toByte()
        report[offset + 2] = (crc ushr 16).toByte()
        report[offset + 3] = (crc ushr 24).toByte()
    }

    private fun crc32Byte(initialCrc: Int, value: Int): Int
    {
        var crc = initialCrc xor value
        repeat(8) {
            crc = if((crc and 1) != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
        }
        return crc
    }

    private fun cleanString(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String
    {
        if(offset >= bytes.size || length <= 0)
            return ""
        val safeLength = min(length, bytes.size - offset)
        return String(bytes, offset, safeLength, charset)
            .replace("\u0000", "")
            .trim()
    }

    private fun le16(bytes: ByteArray, offset: Int): Int
    {
        if(offset + 1 >= bytes.size)
            return 0
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun le32(bytes: ByteArray, offset: Int): Long
    {
        if(offset + 3 >= bytes.size)
            return 0
        return ((bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24))
    }

    private fun le48(bytes: ByteArray, offset: Int): Long
    {
        var value = 0L
        for(i in 0 until min(6, bytes.size - offset))
            value = value or ((bytes[offset + i].toLong() and 0xFFL) shl (8 * i))
        return value
    }

    private fun le64(bytes: ByteArray, offset: Int): Long
    {
        var value = 0L
        for(i in 0 until min(8, bytes.size - offset))
            value = value or ((bytes[offset + i].toLong() and 0xFFL) shl (8 * i))
        return value
    }

    private fun powerStateName(value: Int): String = when(value)
    {
        0x0 -> "discharging"
        0x1 -> "charging"
        0x2 -> "charging complete"
        0xA -> "abnormal voltage"
        0xB -> "abnormal temperature"
        0xF -> "charging error"
        else -> "unknown ($value)"
    }

    private fun formatUpdateVersion(value: Int): String = "${hex(((value ushr 8) and 0xFF).toLong(), 2)}.${(value and 0xFF).toString(16).uppercase().padStart(2, '0')}"

    private fun formatThreePartVersion(value: Long): String = "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${value and 0xFFFF}"

    private fun formatDspVersion(value: Long): String = "${((value ushr 16) and 0xFFFF).toString(16).uppercase().padStart(4, '0')}_${(value and 0xFFFF).toString(16).uppercase().padStart(4, '0')}"

    private fun yesNo(value: Boolean): String = if(value) "yes" else "no"

    private fun ByteArray.toHex(): String = joinToString(" ") { byte ->
        (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
    }
}

class DualSenseInfoReader(private val bridge: DualSenseBtHidBridge)
{
    private companion object
    {
        private const val REPORT_FEATURE_TEST_COMMAND = 0x80
        private const val REPORT_FEATURE_TEST_RESULT = 0x81
        private const val TEST_STATUS_COMPLETE = 2
        private const val TEST_STATUS_COMPLETE_2 = 3
        private const val TEST_DEVICE_SYSTEM = 1
        private const val TEST_DEVICE_BLUETOOTH = 9
        private const val TEST_DEVICE_ADAPTIVE_TRIGGER = 7
    }

    fun readFactoryRows(firmwareInfo: DualSenseInfoParser.FirmwareInfo?, includeEdgeOnlyReads: Boolean = false): List<Pair<String, String>>
    {
        val rows = ArrayList<Pair<String, String>>()

        DualSenseInfoParser.parseBtPatchInfo(bridge.requestFeatureReport(0x22, 63, 900L).report)?.let {
            rows += "BT patch version" to DualSenseInfoParser.hex(it, 8)
        }

        val useNewTraceability = firmwareInfo != null &&
            (firmwareInfo.hwInfo and 0xFFFF) >= 777 &&
            firmwareInfo.mainFwVersion >= 65655
        val hasAdditionalAtTraceability = firmwareInfo != null && (firmwareInfo.hwInfo and 0xFFFF) >= 777
        var serial: String? = null
        var serialAttempted = false

        if(useNewTraceability)
        {
            DualSenseInfoParser.pcbaIdFull(testCommand(TEST_DEVICE_SYSTEM, 17, 24))?.let { rows += "PCBA ID" to it }
            serial = readSerialNumber()
            serialAttempted = true
            if(!serial.isNullOrBlank())
            {
                rows += "Serial number" to serial
                rows += DualSenseInfoParser.serialDerivedRows(serial)
            }
            testCommand(TEST_DEVICE_SYSTEM, 21, 32)?.let { rows += "Assemble parts info" to it.toHex() }
            DualSenseInfoParser.bytesToShiftJis(testCommand(TEST_DEVICE_SYSTEM, 24, 32))?.let { rows += "Battery barcode" to it }
            val vcmLeft = DualSenseInfoParser.bytesToShiftJis(testCommand(TEST_DEVICE_SYSTEM, 26, 32))
            val vcmRight = DualSenseInfoParser.bytesToShiftJis(testCommand(TEST_DEVICE_SYSTEM, 28, 32))
            if(!vcmLeft.isNullOrBlank() || !vcmRight.isNullOrBlank())
                rows += "VCM barcode" to listOfNotNull(vcmLeft?.ifBlank { null }, vcmRight?.ifBlank { null }).joinToString(" / ")
            if(includeEdgeOnlyReads)
                DualSenseInfoParser.moduleBarcode(testCommand(21, 34, 56))?.let { rows += "Edge module barcode" to it }
        }
        else
        {
            DualSenseInfoParser.pcbaId(testCommand(TEST_DEVICE_SYSTEM, 4, 6))?.let { rows += "PCBA ID" to it }
        }

        if(serial.isNullOrBlank() && !serialAttempted)
        {
            serial = readSerialNumber()
            if(!serial.isNullOrBlank())
            {
                rows += "Serial number" to serial
                rows += DualSenseInfoParser.serialDerivedRows(serial)
            }
            else
            {
                rows += "Serial number" to "unavailable"
                rows += "Controller shell color" to "unavailable (serial number read failed)"
            }
        }
        else if(serial.isNullOrBlank())
        {
            rows += "Serial number" to "unavailable"
            rows += "Controller shell color" to "unavailable (serial number read failed)"
        }

        DualSenseInfoParser.uniqueId(testCommand(TEST_DEVICE_SYSTEM, 9, 9))?.let { rows += "Unique ID" to it }
        DualSenseInfoParser.macAddress(testCommand(TEST_DEVICE_BLUETOOTH, 2, 6))?.let { rows += "BD MAC" to it }

        if(hasAdditionalAtTraceability)
        {
            val left = traceability(1)
            val right = traceability(2)
            if(left != null || right != null)
            {
                rows += "AT serial number" to listOfNotNull(left?.serialNo, right?.serialNo).joinToString(" / ")
                rows += "AT motor info" to listOfNotNull(left?.motorInfo, right?.motorInfo).joinToString(" / ")
            }
        }

        if(rows.isEmpty())
            rows += "Factory reads" to "unavailable"
        return rows
    }

    private fun traceability(type: Int): DualSenseInfoParser.TestTraceabilityInfo?
    {
        val data = testCommand(TEST_DEVICE_ADAPTIVE_TRIGGER, 37, 43, byteArrayOf(type.toByte()), maxPolls = 20)
        return DualSenseInfoParser.traceabilityInfo(data)
    }

    private fun readSerialNumber(): String?
    {
        return DualSenseInfoParser.bytesToShiftJis(
            testCommand(
                deviceId = TEST_DEVICE_SYSTEM,
                actionId = 19,
                resultLength = 32,
                maxPolls = 40,
                pollTimeoutMs = 120L,
                pollDelayMs = 10L,
            )
        )?.ifBlank { null }
    }

    private fun testCommand(
        deviceId: Int,
        actionId: Int,
        resultLength: Int,
        params: ByteArray = ByteArray(0),
        maxPolls: Int = 14,
        pollTimeoutMs: Long = 70L,
        pollDelayMs: Long = 5L,
    ): ByteArray?
    {
        val payload = ByteArray(2 + params.size)
        payload[0] = deviceId.toByte()
        payload[1] = actionId.toByte()
        System.arraycopy(params, 0, payload, 2, params.size)
        val send = bridge.sendFeatureReport(DualSenseInfoParser.buildFeatureReport(REPORT_FEATURE_TEST_COMMAND, payload))
        if(!send.success)
            return null

        val output = ByteArray(resultLength)
        var offset = 0
        repeat(maxPolls) {
            val report = bridge.requestFeatureReport(REPORT_FEATURE_TEST_RESULT, 63, pollTimeoutMs).report
            if(report != null &&
                report.size >= 4 &&
                (report[0].toInt() and 0xFF) == REPORT_FEATURE_TEST_RESULT &&
                (report[1].toInt() and 0xFF) == deviceId &&
                (report[2].toInt() and 0xFF) == actionId)
            {
                val status = report[3].toInt() and 0xFF
                if(status == TEST_STATUS_COMPLETE || status == TEST_STATUS_COMPLETE_2)
                {
                    val available = min(56, report.size - 4)
                    val toCopy = min(available, resultLength - offset)
                    if(toCopy > 0)
                    {
                        System.arraycopy(report, 4, output, offset, toCopy)
                        offset += toCopy
                    }
                    if(status == TEST_STATUS_COMPLETE || offset >= resultLength)
                        return output
                }
            }
            Thread.sleep(pollDelayMs)
        }
        return null
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { byte ->
        (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
    }
}
