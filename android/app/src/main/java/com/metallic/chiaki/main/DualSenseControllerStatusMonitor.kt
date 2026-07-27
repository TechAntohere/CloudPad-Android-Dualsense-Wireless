// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.ContextCompat
import com.metallic.chiaki.settings.DualSenseBtHidBridge
import com.metallic.chiaki.settings.DualSenseInfoParser
import com.metallic.chiaki.settings.DualSenseInfoReader
import java.io.Closeable
import java.util.Locale

class DualSenseControllerStatusMonitor(
	private val context: Context,
	private val listener: (State) -> Unit,
): Closeable
{
	data class State(
		val connected: Boolean,
		val statusText: String,
		val batteryText: String,
		val batteryPercent: Int?,
		val headphonesPlugged: Boolean,
		val shellColorName: String?,
		val shellColor: Int,
	)
	{
		companion object
		{
			fun disconnected(message: String = "Not connected") = State(
				connected = false,
				statusText = message,
				batteryText = "--",
				batteryPercent = null,
				headphonesPlugged = false,
				shellColorName = null,
				shellColor = Color.WHITE,
			)
		}
	}

	private val appContext = context.applicationContext
	private val mainHandler = Handler(Looper.getMainLooper())
	private var workerThread: HandlerThread? = null
	private var workerHandler: Handler? = null
	private var bridge: DualSenseBtHidBridge? = null
	@Volatile private var running = false
	private var lastShellColorName: String? = null
	private var lastShellColor = Color.WHITE
	private var factoryReadAttempted = false
	private var lastFactoryReadAttemptMs = 0L
	private var lastSelectedDeviceAddress: String? = null
	private var lastSelectedDeviceName: String? = null

	private val pollRunnable = object : Runnable
	{
		override fun run()
		{
			if(!running)
				return
			pollOnce()
			workerHandler?.postDelayed(this, 1500L)
		}
	}

	fun start()
	{
		if(running)
			return
		running = true
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
		{
			publish(State.disconnected("Android 9+ required"))
			return
		}
		if(!hasBluetoothPermission())
		{
			publish(State.disconnected("Bluetooth permission missing"))
			return
		}
		workerThread = HandlerThread("DualSenseStatusMonitor").also { it.start() }
		workerHandler = Handler(workerThread!!.looper)
		bridge = DualSenseBtHidBridge(appContext).also { it.start() }
		workerHandler?.post(pollRunnable)
	}

	fun stop()
	{
		running = false
		workerHandler?.removeCallbacksAndMessages(null)
		bridge?.close()
		bridge = null
		workerThread?.quitSafely()
		workerThread = null
		workerHandler = null
	}

	override fun close() = stop()

	private fun pollOnce()
	{
		val hidBridge = bridge ?: return
		hidBridge.refresh()
		waitForBridgeReady(hidBridge)
		refreshSelectedControllerIdentity(hidBridge)
		val inputResult = hidBridge.requestInputReport(timeoutMs = 350L)
		val inputStatus = DualSenseInfoParser.parseInputStatus(inputResult.report)
		if(inputStatus == null)
		{
			publish(State.disconnected())
			return
		}

		val now = System.currentTimeMillis()
		if(!factoryReadAttempted || (lastShellColorName == null && now - lastFactoryReadAttemptMs >= 8_000L))
		{
			lastFactoryReadAttemptMs = now
			factoryReadAttempted = readShellColor(hidBridge)
		}

		val batteryPercent = parseBatteryPercent(inputStatus.batteryPercent)
		val colorName = lastShellColorName
		val jackText = if(inputStatus.headphonesPlugged) "Jack plugged" else "Jack unplugged"
		val colorText = colorName?.replaceFirstChar {
			if(it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
		}
		publish(
			State(
				connected = true,
				statusText = listOfNotNull(colorText, jackText).joinToString(" · "),
				batteryText = batteryPercent?.toString() ?: "--",
				batteryPercent = batteryPercent,
				headphonesPlugged = inputStatus.headphonesPlugged,
				shellColorName = colorName,
				shellColor = lastShellColor,
			)
		)
	}

	private fun readShellColor(hidBridge: DualSenseBtHidBridge): Boolean
	{
		val colorName = try {
			val firmware = DualSenseInfoParser.parseFirmware(
				hidBridge.requestFeatureReport(0x20, 63, 1000L).report
			)
			val selectedDeviceName = hidBridge.getStatus(true).connectedDeviceName.orEmpty()
			val includeEdgeReads = selectedDeviceName.contains("edge", ignoreCase = true)
			val rows = DualSenseInfoReader(hidBridge).readFactoryRows(firmware, includeEdgeReads)
			rows.firstOrNull { it.first == "Controller shell color" }?.second
				?.substringBefore(" (")
				?.takeUnless { it.startsWith("unavailable", ignoreCase = true) || it.startsWith("unknown", ignoreCase = true) }
		} catch(_: Exception) {
			null
		} ?: return false
		lastShellColorName = colorName
		lastShellColor = shellColorInt(colorName)
		return true
	}

	private fun refreshSelectedControllerIdentity(hidBridge: DualSenseBtHidBridge)
	{
		val diagnostics = hidBridge.getTransportDiagnostics()
		val address = diagnostics.selectedDeviceAddress
		val name = diagnostics.selectedDeviceName
		if(address == lastSelectedDeviceAddress && name == lastSelectedDeviceName)
			return

		lastSelectedDeviceAddress = address
		lastSelectedDeviceName = name
		factoryReadAttempted = false
		lastFactoryReadAttemptMs = 0L
		lastShellColorName = null
		lastShellColor = Color.WHITE
	}

	private fun waitForBridgeReady(hidBridge: DualSenseBtHidBridge)
	{
		val deadline = System.currentTimeMillis() + 1500L
		while(running && System.currentTimeMillis() < deadline)
		{
			val status = hidBridge.getStatus(hasBluetoothPermission())
			if(status.proxyReady && status.getReportReady && status.connectedDeviceName != null)
				return
			Thread.sleep(50L)
		}
	}

	private fun hasBluetoothPermission(): Boolean =
		Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
			ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

	private fun publish(state: State)
	{
		mainHandler.post { listener(state) }
	}

	private fun parseBatteryPercent(text: String): Int? =
		text.removeSuffix("%").toIntOrNull()?.coerceIn(0, 100)

	private fun shellColorInt(name: String): Int
	{
		val normalized = name.lowercase(Locale.US)
		return when
		{
			"midnight black" in normalized -> Color.rgb(28, 28, 32)
			"cosmic red" in normalized -> Color.rgb(187, 40, 64)
			"nova pink" in normalized -> Color.rgb(239, 117, 173)
			"galactic purple" in normalized -> Color.rgb(89, 65, 151)
			"starlight blue" in normalized -> Color.rgb(71, 143, 205)
			"grey camouflage" in normalized -> Color.rgb(108, 111, 111)
			"volcanic red" in normalized -> Color.rgb(226, 34, 39)
			"sterling silver" in normalized -> Color.rgb(190, 193, 197)
			"cobalt blue" in normalized -> Color.rgb(33, 78, 170)
			"chroma teal" in normalized -> Color.rgb(0, 170, 164)
			"chroma indigo" in normalized -> Color.rgb(73, 70, 173)
			"chroma pearl" in normalized -> Color.rgb(222, 222, 218)
			"30th" in normalized -> Color.rgb(174, 174, 170)
			"spider" in normalized -> Color.rgb(195, 26, 36)
			"astro" in normalized -> Color.rgb(83, 158, 221)
			"fortnite" in normalized -> Color.rgb(58, 88, 183)
			"last of us" in normalized -> Color.rgb(79, 81, 70)
			"icon blue" in normalized -> Color.rgb(25, 71, 149)
			"genshin" in normalized -> Color.rgb(236, 223, 189)
			else -> Color.WHITE
		}
	}
}
