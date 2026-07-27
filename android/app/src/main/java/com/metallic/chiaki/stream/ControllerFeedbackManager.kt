// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.graphics.Color
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.metallic.chiaki.lib.RumbleEvent
import com.metallic.chiaki.lib.TriggerEffectsEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ControllerFeedbackManager(private val context: Context)
{
	companion object
	{
		private const val TAG = "ControllerFeedback"
		private const val PLAYSTATION_VENDOR_ID = 0x054c
		private const val RUMBLE_DURATION_MS = 220L
		private const val TEST_RUMBLE_DURATION_MS = 400L
		private const val STREAM_RUMBLE_SCALE = 255
		private const val STREAM_RUMBLE_MIN_AMPLITUDE = 255
		private const val PWM_PERIOD_MS = 20L
		private const val MIN_NONZERO_AMPLITUDE = 32
		private const val LIGHTBAR_CLEAR_DELAY_MS = 300L
		private const val LIGHTBAR_UPDATE_INTERVAL_MS = 50L
	}

	data class FeedbackDeviceInfo(
		val id: Int,
		val name: String,
		val vendorId: Int,
		val productId: Int,
		val rumblePathCount: Int,
		val totalLightCount: Int,
		val rgbLightCount: Int,
		val active: Boolean,
	)
	{
		val supportsRumble: Boolean get() = rumblePathCount > 0
		val supportsLights: Boolean get() = rgbLightCount > 0
	}

	private val deviceVibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
	private val btStreamFeedback = DualSenseBtStreamFeedback(context)
	private val feedbackHandler = Handler(Looper.getMainLooper())
	private val lightSessions = mutableMapOf<Int, LightsManager.LightsSession>()
	var allowFallbackRumble: Boolean = false
	private var activeControllerDeviceId: Int? = null
	private var pendingStopRunnable: Runnable? = null
	private var pendingLightClearRunnable: Runnable? = null
	private var lastGameplayLightColor: Int = Color.BLACK
	private var lastGameplayLightUpdateMs: Long = 0L
	private var lastPlayerIndex: Int? = null
	private var nativeHapticsOwnsControllerFeedback = false
	var controllerJackStateCallback: ((Boolean) -> Unit)?
		get() = btStreamFeedback.controllerJackStateCallback
		set(value) { btStreamFeedback.controllerJackStateCallback = value }

	fun onResume()
	{
		btStreamFeedback.onResume()
	}

	fun onPause()
	{
		cancelPendingStop()
		btStreamFeedback.handleClassicRumble(0, 0)
		btStreamFeedback.onPause()
		cancelPendingLightClear()
		releaseNativeHapticsFeedbackOwnership()
		cancelControllerFeedback()
		clearControllerLights()
		lastGameplayLightColor = Color.BLACK
		lastGameplayLightUpdateMs = 0L
		// Keep lastPlayerIndex — nulling it here would cause the next PS5 playerIndex
		// event to trigger an unnecessary restartBt on resume (even with isNativeHapticsActive guard).
	}

	fun release()
	{
		controllerJackStateCallback = null
		onPause()
	}

	fun updateHeadphoneVolume(volumePercent: Int)
	{
		btStreamFeedback.updateHeadphoneVolume(volumePercent)
	}

	fun noteInputDevice(device: InputDevice?)
	{
		if(device != null && (isGameControllerDevice(device) || isSonyFeedbackDevice(device) || hasControllerLights(device)))
			activeControllerDeviceId = device.id
	}

	fun handleRumble(event: RumbleEvent)
    {
        if(btStreamFeedback.isNativeHapticsActive())
        {
            takeNativeHapticsFeedbackOwnership()
            return
        }
        releaseNativeHapticsFeedbackOwnership()
        val leftMotor = normalizeClassicMotor(event.left.toInt())
        val rightMotor = normalizeClassicMotor(event.right.toInt())
        if(leftMotor == 0 && rightMotor == 0)
        {
            cancelPendingStop()
            btStreamFeedback.handleClassicRumble(0, 0)
            return
        }
        if(btStreamFeedback.handleClassicRumble(leftMotor, rightMotor))
        {
            scheduleClassicRumbleStop(RUMBLE_DURATION_MS)
            return
        }
        if(!allowFallbackRumble)
            return
        emitRumble(
			normalizeStreamMotor(event.left.toInt()),
			normalizeStreamMotor(event.right.toInt()),
			RUMBLE_DURATION_MS,
		)
    }

	fun handleTriggerEffects(_event: TriggerEffectsEvent)
	{
		btStreamFeedback.handleTriggerEffects(_event)
	}

	fun handlePlayerIndex(playerIndex: Int)
	{
		if(lastPlayerIndex == playerIndex)
		{
			btStreamFeedback.updatePlayerIndex(playerIndex)
			return
		}
		lastPlayerIndex = playerIndex
		// If haptics are actively streaming, do NOT restart BT — that tears down the
		// writer/packetizer and creates an audible/tactile ~60-100ms gap. The player
		// LED can be updated via updatePlayerIndex without a full restart.
		if(btStreamFeedback.isNativeHapticsActive())
		{
			btStreamFeedback.updatePlayerIndex(playerIndex)
			return
		}
		restartBt(clearBufferedHaptics = false)
		btStreamFeedback.updatePlayerIndex(playerIndex)
	}

	fun handleSessionConnected(isPS5: Boolean)
	{
		if(!btStreamFeedback.isNativeHapticsActive())
		{
		    restartBt(clearBufferedHaptics = true)
			if(isPS5)
				btStreamFeedback.prearmCombinedHapticsRoute()
		}
	}

	fun restartBt(clearBufferedHaptics: Boolean)
	{
		btStreamFeedback.requestStreamRestart(clearBufferedHaptics = clearBufferedHaptics)
		btStreamFeedback.expireStaleTriggerEffectsOnHostStatusChange()
	}

	fun handleHapticIntensity(intensity: Int)
	{
		btStreamFeedback.updateHapticIntensity(intensity)
	}

	fun handleTriggerIntensity(intensity: Int)
	{
		btStreamFeedback.updateTriggerIntensity(intensity)
	}

	fun handleHapticsFrame(data: ByteArray)
	{
		handleHapticsFrame(data, 0L)
	}

	fun handleHapticsFrame(data: ByteArray, nativeElapsedRealtimeNs: Long)
    {
        if(btStreamFeedback.handleHapticsFrame(data, nativeElapsedRealtimeNs))
        {
            takeNativeHapticsFeedbackOwnership()
            return
        }
        releaseNativeHapticsFeedbackOwnership()
        if(!allowFallbackRumble)
            return
        emitRumble(fallbackHapticsMotor(data, 0), fallbackHapticsMotor(data, 1), RUMBLE_DURATION_MS)
    }

	fun handleLightbarColor(red: Int, green: Int, blue: Int)
	{
		val color = Color.rgb(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))
		val now = SystemClock.uptimeMillis()
		val nativeColorDelta = abs(Color.red(color) - Color.red(lastGameplayLightColor)) +
			abs(Color.green(color) - Color.green(lastGameplayLightColor)) +
			abs(Color.blue(color) - Color.blue(lastGameplayLightColor))
		val shouldUpdateNative = color != lastGameplayLightColor &&
			(now - lastGameplayLightUpdateMs >= LIGHTBAR_UPDATE_INTERVAL_MS || nativeColorDelta >= 24)

		btStreamFeedback.updatePersistentLightbar(
			Color.red(color),
			Color.green(color),
			Color.blue(color),
			sendReport = true,
		)

		if(shouldUpdateNative)
		{
			cancelPendingLightClear()
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
				setControllerLightColor(color)
			lastGameplayLightColor = color
			lastGameplayLightUpdateMs = now
		}
	}

	fun updateTesterVisualState(playerLedMask: Int, playerLedBrightness: Int, red: Int, green: Int, blue: Int)
	{
		btStreamFeedback.syncTesterVisualState(
			playerLedMask = playerLedMask,
			playerLedBrightness = playerLedBrightness,
			red = red,
			green = green,
			blue = blue,
		)
	}

	fun isBluetoothControllerOutputAvailable(): Boolean = btStreamFeedback.isControllerOutputAvailable()

	fun getBluetoothStreamDebugSnapshot(): DualSenseBtStreamFeedback.BtHapticsDebugSnapshot =
		btStreamFeedback.getBtHapticsDebugSnapshot()

	fun clearAdaptiveTriggers(sendReport: Boolean = true): Boolean =
		btStreamFeedback.clearTriggerEffects(sendReport)

	fun getFeedbackDeviceInfo(): List<FeedbackDeviceInfo>
		= orderedFeedbackDevices().map { device ->
			FeedbackDeviceInfo(
				id = device.id,
				name = device.name.toString(),
				vendorId = device.vendorId,
				productId = device.productId,
				rumblePathCount = controllerRumblePathCount(device),
				totalLightCount = availableLights(device).size,
				rgbLightCount = controllableLights(device).size,
				active = device.id == activeControllerDeviceId,
			)
		}

	fun testControllerRumble(leftMotor: Int, rightMotor: Int, durationMs: Long = TEST_RUMBLE_DURATION_MS): Boolean
	{
		cancelPendingStop()
		val used = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
			vibrateApi31Controller(leftMotor, rightMotor, durationMs)
		else
			vibrateLegacyController(leftMotor, rightMotor, durationMs)
		if(used)
			scheduleStop(durationMs)
		return used
	}

	fun testDeviceFallbackRumble(amplitude: Int = 255, durationMs: Long = TEST_RUMBLE_DURATION_MS): Boolean
	{
		cancelPendingStop()
		if(!deviceVibrator.hasVibrator())
			return false
		vibrateEffect(deviceVibrator, amplitude, durationMs)
		scheduleStop(durationMs)
		return true
	}

	fun setControllerLightColor(color: Int): Boolean
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			return false

		var used = false
		for(device in orderedFeedbackDevices())
		{
			val lights = controllableLights(device)
			if(lights.isEmpty())
				continue

			val session = lightSessions[device.id] ?: runCatching {
				device.lightsManager.openSession()
			}.getOrNull()?.also {
				lightSessions[device.id] = it
			} ?: continue

			val lightState = LightState.Builder()
				.setColor(color)
				.build()
			val requestBuilder = LightsRequest.Builder()
			lights.forEach { light -> requestBuilder.addLight(light, lightState) }
			runCatching {
				session.requestLights(requestBuilder.build())
			}.onSuccess {
				used = true
			}
		}
		return used
	}

	fun clearControllerLights()
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			return

		val offState = LightState.Builder()
			.setColor(Color.BLACK)
			.build()
		for(device in orderedFeedbackDevices())
		{
			val session = lightSessions[device.id] ?: continue
			val lights = controllableLights(device)
			if(lights.isNotEmpty())
			{
				val requestBuilder = LightsRequest.Builder()
				lights.forEach { light -> requestBuilder.addLight(light, offState) }
				runCatching {
					session.requestLights(requestBuilder.build())
				}
			}
		}
		closeLightSessions()
		lastGameplayLightColor = Color.BLACK
		lastGameplayLightUpdateMs = 0L
	}

	fun stopAllFeedback()
	{
		cancelPendingStop()
		btStreamFeedback.handleClassicRumble(0, 0)
		cancelPendingLightClear()
		releaseNativeHapticsFeedbackOwnership()
		cancelControllerFeedback()
		clearControllerLights()
	}

	private fun normalizeMotor(value: Int): Int
	{
		if(value <= 0)
			return 0
		return min(255, max(MIN_NONZERO_AMPLITUDE, value * 3))
	}

	private fun fallbackHapticsMotor(data: ByteArray, channelOffset: Int): Int
	{
		var peak = 0
		var sum = 0
		var count = 0
		var index = channelOffset
		while(index < data.size)
		{
			val magnitude = abs(data[index].toInt())
			if(magnitude > peak)
				peak = magnitude
			sum += magnitude
			count += 1
			index += 2
		}
		if(count == 0)
			return 0
		val average = sum / count
		return min(255, max(peak * 2, average * 4))
	}

	private fun emitRumble(lowFreqMotor: Int, highFreqMotor: Int, durationMs: Long)
	{
		if(lowFreqMotor == 0 && highFreqMotor == 0)
		{
			scheduleStop(durationMs)
			return
		}
		cancelPendingStop()

		val usedControllerFeedback = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
			vibrateApi31Controller(lowFreqMotor, highFreqMotor, durationMs)
		else
			vibrateLegacyController(lowFreqMotor, highFreqMotor, durationMs)

		if(!usedControllerFeedback && deviceVibrator.hasVibrator())
		{
			Log.d(TAG, "Falling back to device vibrator")
			rumbleSingleVibrator(deviceVibrator, lowFreqMotor, highFreqMotor, durationMs)
		}
	}

	private fun updateGameplayLightbar(lowFreqMotor: Int, highFreqMotor: Int)
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			return
		cancelPendingLightClear()
		val color = gameplayLightColor(lowFreqMotor, highFreqMotor)
		val now = SystemClock.uptimeMillis()
		val colorDelta = kotlin.math.abs(Color.red(color) - Color.red(lastGameplayLightColor)) +
			kotlin.math.abs(Color.green(color) - Color.green(lastGameplayLightColor)) +
			kotlin.math.abs(Color.blue(color) - Color.blue(lastGameplayLightColor))
		if(color == lastGameplayLightColor && now - lastGameplayLightUpdateMs < LIGHTBAR_UPDATE_INTERVAL_MS)
			return
		if(now - lastGameplayLightUpdateMs < LIGHTBAR_UPDATE_INTERVAL_MS && colorDelta < 24)
			return
		if(setControllerLightColor(color))
		{
			lastGameplayLightColor = color
			lastGameplayLightUpdateMs = now
		}
	}

	private fun gameplayLightColor(lowFreqMotor: Int, highFreqMotor: Int): Int
	{
		val low = lowFreqMotor.coerceIn(0, 255)
		val high = highFreqMotor.coerceIn(0, 255)
		val intensity = max(low, high)
		if(intensity <= 0)
			return Color.BLACK
		val red = max(low, intensity / 5)
		val green = min(255, (low + high) / 2)
		val blue = max(high, intensity / 6)
		return Color.rgb(red, green, blue)
	}

	private fun normalizeStreamMotor(value: Int): Int
	{
		if(value <= 0)
			return 0
		return 255
	}

	private fun normalizeClassicMotor(value: Int): Int =
		value.coerceIn(0, 255)

	private fun feedbackDevices(): List<InputDevice>
		= InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull().filter {
			isGameControllerDevice(it) || isSonyFeedbackDevice(it) || hasControllerLights(it)
		}

	private fun controllerScore(device: InputDevice): Int
	{
		var score = 0
		if(device.id == activeControllerDeviceId)
			score += 1000
		if(device.vendorId == PLAYSTATION_VENDOR_ID)
			score += 100
		if(device.name.contains("dualsense", ignoreCase = true) || device.name.contains("wireless controller", ignoreCase = true))
			score += 50
		if(hasJoystickAxes(device))
			score += 20
		if(hasGamepadButtons(device))
			score += 20
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && inputDeviceVibratorIds(device).isNotEmpty())
			score += 10
		else if(hasUsableVibrator(inputDeviceVibrator(device)))
			score += 10
		if(hasControllerLights(device))
			score += 5
		if(device.name.contains("touchpad", ignoreCase = true))
			score -= 200
		return score
	}

	private fun orderedFeedbackDevices(): List<InputDevice>
		= feedbackDevices().sortedByDescending { controllerScore(it) }

	private fun exposesControllerVibration(device: InputDevice): Boolean
	{
		return if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
			inputDeviceVibratorIds(device).isNotEmpty() || hasUsableVibrator(inputDeviceVibrator(device))
		else
			hasUsableVibrator(inputDeviceVibrator(device))
	}

	private fun controllerRumblePathCount(device: InputDevice): Int
	{
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
		{
			val vibratorIds = inputDeviceVibratorIds(device)
			if(vibratorIds.isNotEmpty())
				return vibratorIds.count { vibratorId -> hasUsableVibrator(inputDeviceVibratorById(device, vibratorId)) }
		}
		return if(hasUsableVibrator(inputDeviceVibrator(device))) 1 else 0
	}

	private fun availableLights(device: InputDevice): List<Light>
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			return emptyList()
		return runCatching { device.lightsManager.lights.toList() }
			.getOrDefault(emptyList())
	}

	private fun canAssumeSonyRgbLight(device: InputDevice, light: Light): Boolean
	{
		if(device.vendorId != PLAYSTATION_VENDOR_ID)
			return false
		if(Build.VERSION.SDK_INT >= 34)
			return false
		return light.type == Light.LIGHT_TYPE_INPUT
	}

	private fun controllableLights(device: InputDevice): List<Light>
		= availableLights(device).filter { light ->
			light.hasRgbControl() || canAssumeSonyRgbLight(device, light)
		}

	private fun hasControllerLights(device: InputDevice): Boolean
		= controllableLights(device).isNotEmpty()

	private fun isSonyFeedbackDevice(device: InputDevice?): Boolean
	{
		if(device == null)
			return false
		if(device.name.endsWith(" Touchpad"))
			return false
		return device.vendorId == PLAYSTATION_VENDOR_ID && (exposesControllerVibration(device) || hasControllerLights(device))
	}

	private fun getMotionRangeForJoystickAxis(device: InputDevice, axis: Int): InputDevice.MotionRange?
	{
		return device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
			?: device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
	}

	private fun hasJoystickAxes(device: InputDevice): Boolean
	{
		return (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
			&& getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_X) != null
			&& getMotionRangeForJoystickAxis(device, MotionEvent.AXIS_Y) != null
	}

	private fun hasGamepadButtons(device: InputDevice): Boolean
	{
		return (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
	}

	private fun isGameControllerDevice(device: InputDevice?): Boolean
	{
		if(device == null)
			return false
		if(device.name.endsWith(" Touchpad"))
			return false
		if(hasJoystickAxes(device) || hasGamepadButtons(device))
			return true
		if(Build.VERSION.SDK_INT == Build.VERSION_CODES.R && device.id == -1)
		{
			return InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull()
				.any { candidate -> candidate.id != -1 && (hasJoystickAxes(candidate) || hasGamepadButtons(candidate)) }
		}
		return false
	}

	private fun createEffect(vibrator: Vibrator, amplitude: Int, durationMs: Long): VibrationEffect
	{
		return if(vibrator.hasAmplitudeControl())
			VibrationEffect.createOneShot(durationMs, amplitude)
		else
		{
			val onTime = max(1L, ((amplitude / 255.0) * PWM_PERIOD_MS).toLong())
			val offTime = max(1L, PWM_PERIOD_MS - onTime)
			VibrationEffect.createWaveform(longArrayOf(0L, onTime, offTime), 0)
		}
	}

	private fun vibrateEffect(vibrator: Vibrator, amplitude: Int, durationMs: Long)
	{
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		{
			val effect = createEffect(vibrator, amplitude, durationMs)
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			{
				val attrs = VibrationAttributes.Builder()
					.setUsage(VibrationAttributes.USAGE_MEDIA)
					.build()
				vibrator.vibrate(effect, attrs)
			}
			else
			{
				val attrs = AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_GAME)
					.build()
				vibrator.vibrate(effect, attrs)
			}
		}
		else
		{
			vibrator.vibrate(durationMs)
		}
	}

	private fun rumbleSingleVibrator(vibrator: Vibrator, lowFreqMotor: Int, highFreqMotor: Int, durationMs: Long)
	{
		val amplitude = min(255, lowFreqMotor + highFreqMotor)
		if(amplitude <= 0)
		{
			vibrator.cancel()
			return
		}
		vibrateEffect(vibrator, amplitude, durationMs)
	}

	private fun vibrateLegacyController(lowFreqMotor: Int, highFreqMotor: Int, durationMs: Long): Boolean
	{
		var used = false
		for(device in orderedFeedbackDevices())
		{
			val vibrator = inputDeviceVibrator(device) ?: continue
			if(!hasUsableVibrator(vibrator))
				continue
			runCatching {
				Log.d(TAG, "Rumble via legacy vibrator on ${device.name}")
				rumbleSingleVibrator(vibrator, lowFreqMotor, highFreqMotor, durationMs)
				used = true
			}.onFailure { throwable ->
				Log.w(TAG, "Legacy controller vibration failed on ${safeInputDeviceName(device)}", throwable)
			}
		}
		if(!used)
			Log.d(TAG, "No legacy controller vibrator path found")
		return used
	}

	private fun vibrateViaDefaultVibrator(vibratorManager: VibratorManager?, deviceName: String, lowFreqMotor: Int, highFreqMotor: Int, durationMs: Long): Boolean
	{
		val defaultVibrator = vibratorManager?.let { manager ->
			runCatching { manager.defaultVibrator }.getOrNull()
		} ?: return false
		if(!hasUsableVibrator(defaultVibrator))
			return false
		Log.d(TAG, "Rumble via default vibrator on $deviceName")
		rumbleSingleVibrator(defaultVibrator, lowFreqMotor, highFreqMotor, durationMs)
		return true
	}

	private fun vibrateApi31Controller(lowFreqMotor: Int, highFreqMotor: Int, durationMs: Long): Boolean
	{
		var used = false
		for(device in orderedFeedbackDevices())
		{
			val vibratorManager = inputDeviceVibratorManager(device)
			val vibratorIds = inputDeviceVibratorIds(device)
			if(vibratorIds.isNotEmpty())
			{
				if(vibratorIds.size == 1)
				{
					val vibrator = inputDeviceVibratorById(device, vibratorIds[0])
					if(hasUsableVibrator(vibrator))
					{
						runCatching {
							Log.d(TAG, "Rumble via first controller vibrator on ${device.name}")
							rumbleSingleVibrator(vibrator!!, lowFreqMotor, highFreqMotor, durationMs)
							used = true
						}.onFailure { throwable ->
							Log.w(TAG, "Single controller vibrator failed on ${safeInputDeviceName(device)}", throwable)
						}
						if(used)
							continue
					}
				}

				val allAmplitudeControlled = vibratorIds.all { vibratorId ->
					hasAmplitudeControl(inputDeviceVibratorById(device, vibratorId))
				}
				if((vibratorIds.size == 2 || vibratorIds.size == 4) && allAmplitudeControlled)
				{
					val amplitudes = if(device.vendorId == PLAYSTATION_VENDOR_ID)
					{
						if(vibratorIds.size == 4)
							intArrayOf(lowFreqMotor, highFreqMotor, 0, 0)
						else
							intArrayOf(lowFreqMotor, highFreqMotor)
					}
					else if(vibratorIds.size == 4)
						intArrayOf(highFreqMotor, lowFreqMotor, 0, 0)
					else
						intArrayOf(highFreqMotor, lowFreqMotor)
					val combo = CombinedVibration.startParallel()
					var added = false
					vibratorIds.forEachIndexed { index, vibratorId ->
						val amplitude = amplitudes.getOrElse(index) { 0 }
						if(amplitude <= 0)
							return@forEachIndexed
						combo.addVibrator(vibratorId, VibrationEffect.createOneShot(durationMs, amplitude))
						added = true
					}
					if(added && vibratorManager != null)
					{
						runCatching {
							Log.d(TAG, "Rumble via vibratorManager on ${device.name} (${vibratorIds.size} vibrators)")
							if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
							{
								val attrs = VibrationAttributes.Builder()
									.setUsage(VibrationAttributes.USAGE_MEDIA)
									.build()
								vibratorManager.vibrate(combo.combine(), attrs)
							}
							else
							{
								vibratorManager.vibrate(combo.combine())
							}
							used = true
						}.onFailure { throwable ->
							Log.w(TAG, "Parallel vibratorManager rumble failed on ${safeInputDeviceName(device)}", throwable)
						}
						if(used)
							continue
					}
				}

				if(vibrateViaDefaultVibrator(vibratorManager, device.name, lowFreqMotor, highFreqMotor, durationMs))
				{
					used = true
					continue
				}

				val firstVibrator = inputDeviceVibratorById(device, vibratorIds[0])
				if(hasUsableVibrator(firstVibrator))
				{
					runCatching {
						Log.d(TAG, "Rumble via fallback first vibrator on ${device.name}")
						rumbleSingleVibrator(firstVibrator!!, lowFreqMotor, highFreqMotor, durationMs)
						used = true
					}.onFailure { throwable ->
						Log.w(TAG, "Fallback first vibrator failed on ${safeInputDeviceName(device)}", throwable)
					}
					if(used)
						continue
				}
			}

			val vibrator = inputDeviceVibrator(device)
			if(hasUsableVibrator(vibrator))
			{
				runCatching {
					Log.d(TAG, "Rumble via single vibrator on ${device.name}")
					rumbleSingleVibrator(vibrator!!, lowFreqMotor, highFreqMotor, durationMs)
					used = true
				}.onFailure { throwable ->
					Log.w(TAG, "Single vibrator rumble failed on ${safeInputDeviceName(device)}", throwable)
				}
			}
		}
		if(!used)
			Log.d(TAG, "No API31 controller rumble path found")
		return used
	}

	private fun cancelControllerFeedback()
	{
		for(device in orderedFeedbackDevices())
		{
			runCatching {
				val vibratorManager = inputDeviceVibratorManager(device)
				val vibrator = inputDeviceVibrator(device)
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibratorManager != null)
					vibratorManager.cancel()
				else if(hasUsableVibrator(vibrator))
					vibrator?.cancel()
			}.onFailure { throwable ->
				Log.w(TAG, "Cancel controller feedback failed on ${safeInputDeviceName(device)}", throwable)
			}
		}
		deviceVibrator.cancel()
	}

	private fun takeNativeHapticsFeedbackOwnership()
	{
		cancelPendingStop()
		if(nativeHapticsOwnsControllerFeedback)
			return
		cancelControllerFeedback()
		nativeHapticsOwnsControllerFeedback = true
	}

	private fun releaseNativeHapticsFeedbackOwnership()
	{
		nativeHapticsOwnsControllerFeedback = false
	}

	private fun inputDeviceVibratorManager(device: InputDevice): VibratorManager? =
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
			runCatching { device.vibratorManager }.getOrNull()
		else
			null

	private fun inputDeviceVibratorIds(device: InputDevice): IntArray =
		inputDeviceVibratorManager(device)?.let { manager ->
			runCatching { manager.vibratorIds }.getOrDefault(intArrayOf())
		} ?: intArrayOf()

	private fun inputDeviceVibrator(device: InputDevice): Vibrator? =
		runCatching { device.vibrator }.getOrNull()

	private fun inputDeviceVibratorById(device: InputDevice, vibratorId: Int): Vibrator? =
		inputDeviceVibratorManager(device)?.let { manager ->
			runCatching { manager.getVibrator(vibratorId) }.getOrNull()
		}

	private fun hasUsableVibrator(vibrator: Vibrator?): Boolean =
		vibrator?.let { runCatching { it.hasVibrator() }.getOrDefault(false) } == true

	private fun hasAmplitudeControl(vibrator: Vibrator?): Boolean =
		vibrator?.let { runCatching { it.hasAmplitudeControl() }.getOrDefault(false) } == true

	private fun safeInputDeviceName(device: InputDevice): String =
		runCatching { device.name.toString() }.getOrDefault("InputDevice(${device.id})")

	private fun closeLightSessions()
	{
		lightSessions.values.forEach { session ->
			runCatching { session.close() }
		}
		lightSessions.clear()
	}

	private fun releaseControllerLightSessions()
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			return
		cancelPendingLightClear()
		closeLightSessions()
		lastGameplayLightColor = Color.BLACK
		lastGameplayLightUpdateMs = 0L
	}

	private fun cancelPendingLightClear()
	{
		pendingLightClearRunnable?.let { feedbackHandler.removeCallbacks(it) }
		pendingLightClearRunnable = null
	}

	private fun scheduleGameplayLightClear(delayMs: Long = LIGHTBAR_CLEAR_DELAY_MS)
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			return
		cancelPendingLightClear()
		pendingLightClearRunnable = Runnable {
			setControllerLightColor(Color.BLACK)
			lastGameplayLightColor = Color.BLACK
			lastGameplayLightUpdateMs = SystemClock.uptimeMillis()
			pendingLightClearRunnable = null
		}
		feedbackHandler.postDelayed(pendingLightClearRunnable!!, delayMs)
	}

	private fun cancelPendingStop()
	{
		pendingStopRunnable?.let { feedbackHandler.removeCallbacks(it) }
		pendingStopRunnable = null
	}

	private fun scheduleStop(delayMs: Long = RUMBLE_DURATION_MS)
	{
		cancelPendingStop()
		pendingStopRunnable = Runnable {
			cancelControllerFeedback()
			pendingStopRunnable = null
		}
		feedbackHandler.postDelayed(pendingStopRunnable!!, delayMs)
	}

	private fun scheduleClassicRumbleStop(delayMs: Long = RUMBLE_DURATION_MS)
	{
		cancelPendingStop()
		pendingStopRunnable = Runnable {
			btStreamFeedback.handleClassicRumble(0, 0)
			pendingStopRunnable = null
		}
		feedbackHandler.postDelayed(pendingStopRunnable!!, delayMs)
	}
}



















