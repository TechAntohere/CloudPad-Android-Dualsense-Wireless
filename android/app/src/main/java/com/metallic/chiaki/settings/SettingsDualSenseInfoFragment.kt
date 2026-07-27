// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.Manifest
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.pylux.stream.R

class SettingsDualSenseInfoFragment: PreferenceFragmentCompat(), TitleFragment, ControllerInputContainmentOwner
{
    private companion object
    {
        private const val REQUEST_BLUETOOTH_CONNECT = 0xD51
        private const val KEY_STATUS = "dualsense_info_status"
        private const val KEY_REFRESH = "dualsense_info_refresh"
        private const val KEY_LIVE_INPUT = "dualsense_info_live_input"
        private const val KEY_INPUT_FILTER_PROBE = "dualsense_info_input_filter_probe"
        private const val KEY_MIC_CONTAINMENT_TEST = "dualsense_info_bt_mic_containment_test"
        private const val KEY_MIC_RESET = "dualsense_info_bt_mic_reset"
        private const val KEY_FIRMWARE = "dualsense_info_firmware"
        private const val KEY_FACTORY = "dualsense_info_factory"
        private const val KEY_LOG = "dualsense_info_log"
    }

    private val workerThread = HandlerThread("DualSenseInfoReader").apply { start() }
    private val workerHandler = Handler(workerThread.looper)
    private var bridge: DualSenseBtHidBridge? = null
    private var micTestSession: DualSenseBtMicTestSession? = null

    override fun getTitle(resources: Resources): String = resources.getString(R.string.preferences_dualsense_info_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
    {
        setPreferencesFromResource(R.xml.preferences_dualsense_info, rootKey)
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        {
            bridge = DualSenseBtHidBridge(requireContext()).also { hidBridge ->
                hidBridge.setStatusListener {
                    updateBridgeStatus()
                }
                hidBridge.start()
            }
        }
        else
        {
            findPreference<Preference>(KEY_STATUS)?.summary = getString(R.string.preferences_dualsense_info_android_version_missing)
        }

        findPreference<Preference>(KEY_REFRESH)?.setOnPreferenceClickListener {
            refreshInfo()
            true
        }
        findPreference<Preference>(KEY_MIC_RESET)?.setOnPreferenceClickListener {
            sendMicReset()
            true
        }
        findPreference<Preference>(KEY_INPUT_FILTER_PROBE)?.setOnPreferenceClickListener {
            runInputFilterProbe()
            true
        }
        findPreference<Preference>(KEY_MIC_CONTAINMENT_TEST)?.setOnPreferenceClickListener {
            findPreference<Preference>(KEY_MIC_CONTAINMENT_TEST)?.summary =
                getString(R.string.preferences_dualsense_info_mic_containment_summary)
            sendMicReset()
            true
        }
        updateBridgeStatus()
        refreshInfo()
    }

    override fun onPause()
    {
        stopControllerInputContainment("fragment paused")
        super.onPause()
    }

    override fun onDestroy()
    {
        stopControllerInputContainment("fragment destroyed")
        bridge?.close()
        bridge = null
        workerThread.quitSafely()
        super.onDestroy()
    }

    override val controllerInputContainmentActive: Boolean
        get() = micTestSession?.isRunning == true

    override fun stopControllerInputContainment(reason: String)
    {
        val session = micTestSession ?: return
        micTestSession = null
        session.stop(reason)
        (activity as? SettingsActivity)?.releaseControllerInputContainment()
        findPreference<Preference>(KEY_MIC_CONTAINMENT_TEST)?.summary =
            getString(R.string.preferences_dualsense_info_mic_containment_stopping, reason)
    }

    override fun noteContainedControllerInput(kind: String)
    {
        micTestSession?.noteContainedInput(kind)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(requestCode == REQUEST_BLUETOOTH_CONNECT)
            refreshInfo()
    }

    private fun refreshInfo()
    {
        val hidBridge = bridge ?: return
        if(!hasBluetoothPermission())
        {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH_CONNECT)
            findPreference<Preference>(KEY_STATUS)?.summary = getString(R.string.preferences_controller_tester_bt_permission_denied)
            return
        }

        findPreference<Preference>(KEY_LIVE_INPUT)?.summary = getString(R.string.preferences_dualsense_info_loading)
        findPreference<Preference>(KEY_FIRMWARE)?.summary = getString(R.string.preferences_dualsense_info_loading)
        findPreference<Preference>(KEY_FACTORY)?.summary = getString(R.string.preferences_dualsense_info_loading)

        workerHandler.removeCallbacksAndMessages(null)
        workerHandler.post {
            hidBridge.refresh()
            waitForBridgeReady(hidBridge)

            val inputResult = hidBridge.requestInputReport()
            val inputStatus = DualSenseInfoParser.parseInputStatus(inputResult.report)
            val inputSummary = inputStatus?.let { DualSenseInfoParser.inputRows(it).formatRows() }
                ?: "Unavailable: ${inputResult.message}"

            val firmwareResult = hidBridge.requestFeatureReport(0x20, 63, 1000L)
            val firmwareInfo = DualSenseInfoParser.parseFirmware(firmwareResult.report)
            val firmwareSummary = firmwareInfo?.let { DualSenseInfoParser.firmwareRows(it).formatRows() }
                ?: "Unavailable: ${firmwareResult.message}"

            activity?.runOnUiThread {
                findPreference<Preference>(KEY_LIVE_INPUT)?.summary = inputSummary
                findPreference<Preference>(KEY_FIRMWARE)?.summary = firmwareSummary
                findPreference<Preference>(KEY_FACTORY)?.summary = getString(R.string.preferences_dualsense_info_factory_loading)
                updateBridgeStatus()
            }

            val selectedDeviceName = hidBridge.getStatus(true).connectedDeviceName.orEmpty()
            val includeEdgeReads = selectedDeviceName.contains("edge", ignoreCase = true)
            val factoryRows = if(firmwareInfo != null)
                DualSenseInfoReader(hidBridge).readFactoryRows(firmwareInfo, includeEdgeReads)
            else
                listOf("Factory reads" to "unavailable until firmware report 0x20 is readable")
            val factorySummary = factoryRows.formatRows()
            val logSummary = hidBridge.getFullLogText().ifBlank { getString(R.string.preferences_controller_tester_bt_log_empty) }

            activity?.runOnUiThread {
                findPreference<Preference>(KEY_FACTORY)?.summary = factorySummary
                findPreference<Preference>(KEY_LOG)?.summary = logSummary
                updateBridgeStatus()
            }
        }
    }

    private fun sendMicReset()
    {
        val hidBridge = bridge ?: return
        if(!hasBluetoothPermission())
        {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH_CONNECT)
            findPreference<Preference>(KEY_MIC_RESET)?.summary = getString(R.string.preferences_controller_tester_bt_permission_denied)
            return
        }

        findPreference<Preference>(KEY_MIC_RESET)?.summary = getString(R.string.preferences_dualsense_info_mic_reset_sending)
        workerHandler.post {
            hidBridge.refresh()
            waitForBridgeReady(hidBridge)
            val transport = hidBridge.resolveBestAvailableTransport(
                DualSenseBtHidBridge.OutputTransportMode.SEND_DATA_RAW,
                DualSenseBtHidBridge.OutputTransportMode.SEND_DATA_HEX,
                DualSenseBtHidBridge.OutputTransportMode.SET_REPORT_RAW,
                DualSenseBtHidBridge.OutputTransportMode.SET_REPORT_HEX,
            )
            var sequence = (System.currentTimeMillis() and 0xFF).toInt()
            val result = DualSenseBtMicControl.forceClose(
                bridge = hidBridge,
                transportMode = transport,
                nextSequence = {
                    val current = sequence
                    sequence = (sequence + 1) and 0xFF
                    current
                },
                reason = "manual reset",
                closeRepeats = 4,
                ledRepeats = 4,
                settleDelaysMs = longArrayOf(250L, 750L, 1250L),
            )
            val closeOk = result.micOk
            val ledOk = result.ledOk
            val summary = getString(
                R.string.preferences_dualsense_info_mic_reset_result,
                if(closeOk) "ok" else "failed",
                if(ledOk) "ok" else "failed",
                transport.transportName,
            ) + "\n" + result.message
            activity?.runOnUiThread {
                findPreference<Preference>(KEY_MIC_RESET)?.summary = summary
                findPreference<Preference>(KEY_LOG)?.summary = hidBridge.getFullLogText()
                    .ifBlank { getString(R.string.preferences_controller_tester_bt_log_empty) }
            }
        }
    }

    private fun runInputFilterProbe()
    {
        val context = requireContext().applicationContext
        findPreference<Preference>(KEY_INPUT_FILTER_PROBE)?.summary = getString(R.string.preferences_dualsense_info_input_filter_probe_running)
        workerHandler.post {
            val summary = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                DualSenseInputFilterProbe.run(context)
            else
                getString(R.string.preferences_dualsense_info_android_version_missing)
            activity?.runOnUiThread {
                findPreference<Preference>(KEY_INPUT_FILTER_PROBE)?.summary = summary
            }
        }
    }

    private fun toggleContainedMicTest()
    {
        findPreference<Preference>(KEY_MIC_CONTAINMENT_TEST)?.summary =
            getString(R.string.preferences_dualsense_info_mic_containment_summary)
        sendMicReset()
    }

    private fun updateBridgeStatus()
    {
        val hidBridge = bridge
        val summary = if(hidBridge == null)
        {
            getString(R.string.preferences_dualsense_info_android_version_missing)
        }
        else
        {
            val status = hidBridge.getStatus(hasBluetoothPermission())
            getString(
                R.string.preferences_dualsense_info_status_format,
                if(status.hiddenApiReady) "ready" else "not ready",
                if(status.permissionGranted) "granted" else "missing",
                if(status.proxyReady) "ready" else "not ready",
                status.connectedDeviceName ?: getString(R.string.preferences_controller_tester_bt_device_none),
                status.connectedDeviceCount,
                "setReport=${yesNo(status.setReportReady)}, sendData=${yesNo(status.sendDataReady)}, getReport=${yesNo(status.getReportReady)}, feature=${yesNo(status.featureReportReady)}",
                status.lastError ?: "none",
            )
        }
        findPreference<Preference>(KEY_STATUS)?.summary = summary
    }

    private fun hasBluetoothPermission(): Boolean
    {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun waitForBridgeReady(hidBridge: DualSenseBtHidBridge)
    {
        repeat(12) {
            val status = hidBridge.getStatus(true)
            if(status.proxyReady && status.getReportReady)
                return
            Thread.sleep(50L)
        }
    }

    private fun List<Pair<String, String>>.formatRows(): String = joinToString("\n") { (label, value) ->
        "$label: ${value.ifBlank { "n/a" }}"
    }

    private fun yesNo(value: Boolean): String = if(value) "yes" else "no"
}
