package com.metallic.chiaki.session

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.view.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState
import kotlin.math.pow

/** Sony. Used to recognise a DualSense's separate touchpad input device. */
private const val PLAYSTATION_VENDOR_ID = 0x054c

class StreamInput(
	val context: Context,
	val preferences: Preferences,
	val isRemotePlay: Boolean = false
) {
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	val controllerState: ControllerState get()
	{
		// A connected controller's own IMU wins over the tablet's: the player is
		// holding the pad, not the tablet, so the tablet's motion is noise.
		val usingControllerImu = controllerMotionInput.isActive
		val motionSource = if(usingControllerImu) controllerImuState else sensorControllerState
		val controllerState = motionSource or keyControllerState or motionControllerState or physicalTouchpadControllerState

		// This flip compensates for the *tablet* being held in landscape, so it
		// must not be applied to controller-sourced motion -- the pad's
		// orientation is independent of how the screen is rotated. Applying it
		// anyway is what makes controller gyro feel like the axes are swapped.
		if(!usingControllerImu)
		{
			val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
			@Suppress("DEPRECATION")
			when(windowManager.defaultDisplay.rotation)
			{
				Surface.ROTATION_90 -> {
					controllerState.accelX *= -1.0f
					controllerState.accelZ *= -1.0f
					controllerState.gyroX *= -1.0f
					controllerState.gyroZ *= -1.0f
					controllerState.orientX *= -1.0f
					controllerState.orientZ *= -1.0f
				}
				else -> {}
			}
		}

		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

		return controllerState or touchControllerState
	}

	private val sensorControllerState = ControllerState()
	private val keyControllerState = ControllerState()
	private val motionControllerState = ControllerState()

	/**
	 * Touches read from the DualSense's own touchpad.
	 *
	 * Android exposes the physical touchpad as a *separate* input device from the
	 * gamepad (its own /dev/input node with SOURCE_TOUCHPAD), which is why it
	 * never reached the stream: nothing routed those MotionEvents. Kept apart
	 * from [touchControllerState] so the on-screen touchpad overlay and the
	 * hardware one can be active at the same time without fighting over touch ids.
	 */
	private var physicalTouchpadControllerState = ControllerState()

	/** Android pointerId -> chiaki touch id, for touches currently down on the pad. */
	private val touchpadPointers = mutableMapOf<Int, UByte>()

	/** Diagnostic counter for non-touchscreen motion events, see onTouchpadMotionEvent. */
	private var touchpadDiagCount = 0L
	/** Diagnostic counter for captured-pointer events. */
	private var capturedDiagCount = 0L

	/**
	 * Motion from the controller's own IMU. When a DualSense is connected this
	 * supersedes the tablet's sensors entirely -- streaming the tablet's motion
	 * while holding a controller is meaningless, and the controller's values are
	 * calibrated per-unit by the kernel driver.
	 */
	private val controllerImuState = ControllerState()

	/**
	 * Fuses controller gyro+accel into the orientation quaternion.
	 *
	 * Writing gyro/accel alone is not sufficient: the feedback packet also
	 * carries orientX/Y/Z/W, and leaving those at a constant identity makes the
	 * host treat the pad as never rotating -- motion looks dead despite correct
	 * gyro values arriving. The tracker also remaps both vectors into the
	 * console's axis convention, which is not the identity mapping.
	 */
	private val controllerOrientationTracker = DualSenseOrientationTracker()
	private val controllerMotionInput = ControllerMotionInput { gx, gy, gz, ax, ay, az, timestampNs ->
		controllerOrientationTracker.update(gx, gy, gz, ax, ay, az, timestampNs)
		controllerOrientationTracker.applyTo(controllerImuState)
		controllerStateUpdated()
	}

	/** True when controller-sourced motion is live. */
	val isControllerMotionActive: Boolean get() = controllerMotionInput.isActive

	/**
	 * Re-scan for a controller IMU, e.g. after a controller connects mid-session.
	 * If one is found, the tablet's sensors are released so only a single motion
	 * source is ever feeding the stream.
	 */
	fun refreshControllerMotion()
	{
		val wasActive = controllerMotionInput.isActive
		controllerMotionInput.refresh()
		if(!wasActive && controllerMotionInput.isActive)
		{
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManager.unregisterListener(sensorEventListener)
			controllerOrientationTracker.reset()
		}
	}

	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	private val swapCrossMoon = preferences.swapCrossMoon
	private val handler = Handler(Looper.getMainLooper())

	// ---- Mapping lookup structures ----

	private var activeMapping: Map<ControllerAction, PhysicalInput> = emptyMap()
	private var singleKeyToActions: Map<Int, List<ControllerAction>> = emptyMap()
	private var singleAxisMappings: List<Triple<ControllerAction, Int, Boolean>> = emptyList()

	data class ComboEntry(val modifierKeyCode: Int, val trigger: PhysicalInput, val action: ControllerAction)

	private var comboEntries: List<ComboEntry> = emptyList()
	private var comboModifierKeyCodes: Set<Int> = emptySet()

	// ---- Combo runtime state ----

	private val heldModifiers = mutableMapOf<Int, Boolean>()
	private val activeComboActions = mutableMapOf<ControllerAction, Int>()
	private val triggeredComboAxes = mutableSetOf<Pair<Int, Boolean>>()
	// Last-known values for axes used as combo triggers — used to ignore axes that were
	// already above threshold when the modifier key was pressed (e.g. L2 drift at rest).
	private val lastAxisValues = mutableMapOf<Int, Float>()

	init { reloadMapping() }

	/**
	 * Rebuilds the mapping lookup tables from the currently-saved controller mapping.
	 * Called once at construction, and again from the in-stream Quick Settings panel's
	 * Save button after a remap edit, so a live session picks up the new mapping without
	 * needing to reconnect.
	 */
	fun reloadMapping()
	{
		activeMapping = PhysicalInput.resolveMapping(preferences.loadControllerMapping())

		singleKeyToActions = activeMapping.entries
			.filter { it.value is PhysicalInput.Button }
			.groupBy(
				keySelector = { (it.value as PhysicalInput.Button).keyCode },
				valueTransform = { it.key }
			)

		singleAxisMappings = activeMapping.entries
			.filter { it.value is PhysicalInput.AxisDirection }
			.map { val ax = it.value as PhysicalInput.AxisDirection; Triple(it.key, ax.axis, ax.positive) }

		comboEntries = activeMapping.entries
			.filter { it.value is PhysicalInput.Combo }
			.map { (action, input) ->
				val combo = input as PhysicalInput.Combo
				ComboEntry(combo.modifierKeyCode, combo.trigger, action)
			}

		comboModifierKeyCodes = comboEntries.map { it.modifierKeyCode }.toSet()

		// Defensive: drop any in-flight held-key/combo runtime state referencing the old
		// mapping, to avoid a "stuck button" if a physical key held during remapping no
		// longer maps to anything.
		stopTouchpadHold()
		heldModifiers.clear()
		activeComboActions.clear()
		triggeredComboAxes.clear()
		lastAxisValues.clear()
		keyControllerState.buttons = 0U
		keyControllerState.l2State = 0U
		keyControllerState.r2State = 0U
		controllerStateUpdated()
	}

	// ---- Sensor / lifecycle ----

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			// The controller's own IMU supersedes the tablet's. Bail out before
			// doing any work: the values would be discarded downstream anyway,
			// but each call rebuilds the entire ControllerState through four
			// chained `or`s (allocating a state and zipping the touch array each
			// time) and pushes a packet. Left running, the tablet's three
			// sensors and the controller's two interleave at ~300 rebuilds/sec
			// from two unsynchronised clocks, which shows up as jittery motion.
			if(controllerMotionInput.isActive)
				return
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					sensorControllerState.gyroX = event.values[1]
					sensorControllerState.gyroY = event.values[2]
					sensorControllerState.gyroZ = event.values[0]
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					sensorControllerState.orientX = q[2]
					sensorControllerState.orientY = q[3]
					sensorControllerState.orientZ = q[1]
					sensorControllerState.orientW = q[0]
				}
				else -> return
			}
			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume()
		{
			// Try the controller's own IMU first; only fall back to the tablet's
			// sensors if there isn't one, so the two never run concurrently.
			controllerMotionInput.refresh()
			if(controllerMotionInput.isActive)
				return

			val samplingPeriodUs = 4000
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			listOfNotNull(
				sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
			).forEach {
				sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
			}
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManager.unregisterListener(sensorEventListener)
			controllerMotionInput.detach()
		}
	}

	private var lifecycleOwnerRef: LifecycleOwner? = null
	private var motionObserverAdded = false

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		lifecycleOwnerRef = lifecycleOwner
		if(preferences.motionEnabled)
			enableMotion()
	}

	/** Live-toggles motion sensor input for an already-running stream, e.g. from the
	 *  in-stream Quick Settings panel, without needing to reconnect. */
	fun setMotionEnabled(enabled: Boolean)
	{
		if(enabled) enableMotion() else disableMotion()
	}

	private fun enableMotion()
	{
		val owner = lifecycleOwnerRef ?: return
		if(motionObserverAdded) return
		owner.lifecycle.addObserver(motionLifecycleObserver)
		motionObserverAdded = true
	}

	private fun disableMotion()
	{
		val owner = lifecycleOwnerRef ?: return
		if(!motionObserverAdded) return
		owner.lifecycle.removeObserver(motionLifecycleObserver)
		motionObserverAdded = false

		// Lifecycle.removeObserver() doesn't synthesize an ON_PAUSE call, so the sensor
		// listener must be unregistered explicitly here, otherwise motion data keeps
		// streaming (or sticks at its last reading) after being "disabled".
		val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
		sensorManager.unregisterListener(sensorEventListener)
		motionControllerState.accelX = 0f; motionControllerState.accelY = 0f; motionControllerState.accelZ = 0f
		motionControllerState.gyroX = 0f; motionControllerState.gyroY = 0f; motionControllerState.gyroZ = 0f
		motionControllerState.orientX = 0f; motionControllerState.orientY = 0f; motionControllerState.orientZ = 0f; motionControllerState.orientW = 0f
		controllerStateUpdated()
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	// ---- Touchpad gestures ----

	/** Touch id for the sustained touch registered by [startTouchpadHold], if currently held. */
	private var touchpadHoldTouchId: UByte? = null

	/** Simulates pressing and holding the touchpad button (as opposed to [quickTouchpadTap]'s
	 *  momentary click) — the touch position and BUTTON_TOUCHPAD stay set until [stopTouchpadHold]
	 *  is called, mirroring how [PhysicalInput.Combo]-driven actions are held for as long as the
	 *  mapped combo/button stays pressed. */
	private fun startTouchpadHold()
	{
		touchControllerState = ControllerState()
		val touchId = touchControllerState.startTouch(960U.toUShort(), 471U.toUShort()) ?: return
		touchpadHoldTouchId = touchId
		keyControllerState.buttons = keyControllerState.buttons or ControllerState.BUTTON_TOUCHPAD
		controllerStateUpdated()
	}

	private fun stopTouchpadHold()
	{
		touchpadHoldTouchId?.let { touchControllerState.stopTouch(it) }
		touchpadHoldTouchId = null
		touchControllerState = ControllerState()
		keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
		controllerStateUpdated()
	}

	private fun quickTouchpadTap(x: UShort, y: UShort)
	{
		touchControllerState = ControllerState()
		val touchId = touchControllerState.startTouch(x, y) ?: return
		// Set BUTTON_TOUCHPAD alongside the touch position to simulate a physical click
		keyControllerState.buttons = keyControllerState.buttons or ControllerState.BUTTON_TOUCHPAD
		controllerStateUpdated()

		handler.postDelayed({
			touchControllerState.stopTouch(touchId)
			touchControllerState = ControllerState()
			keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
			controllerStateUpdated()
		}, 80)
	}

	private fun quickTouchpadSwipe(direction: Int)
	{
		keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
		controllerStateUpdated()

		val startX = 960U.toUShort()
		val startY = 471U.toUShort()

		val endX: UShort
		val endY: UShort

		when(direction)
		{
			KeyEvent.KEYCODE_DPAD_UP -> { endX = startX; endY = 120U.toUShort() }
			KeyEvent.KEYCODE_DPAD_DOWN -> { endX = startX; endY = 820U.toUShort() }
			KeyEvent.KEYCODE_DPAD_LEFT -> { endX = 250U.toUShort(); endY = startY }
			KeyEvent.KEYCODE_DPAD_RIGHT -> { endX = 1670U.toUShort(); endY = startY }
			else -> return
		}

		touchControllerState = ControllerState()
		val touchId = touchControllerState.startTouch(startX, startY) ?: return
		controllerStateUpdated()

		handler.postDelayed({
			touchControllerState.setTouchPos(touchId, endX, endY)
			controllerStateUpdated()
		}, 60)

		handler.postDelayed({
			touchControllerState.stopTouch(touchId)
			touchControllerState = ControllerState()
			controllerStateUpdated()
		}, 140)
	}

	// ---- Action → button mask ----

	private fun actionToButtonMask(action: ControllerAction): UInt? = when(action)
	{
		ControllerAction.CROSS -> if(swapCrossMoon) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS
		ControllerAction.CIRCLE -> if(swapCrossMoon) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON
		ControllerAction.SQUARE -> if(swapCrossMoon) ControllerState.BUTTON_PYRAMID else ControllerState.BUTTON_BOX
		ControllerAction.TRIANGLE -> if(swapCrossMoon) ControllerState.BUTTON_BOX else ControllerState.BUTTON_PYRAMID
		ControllerAction.L1 -> ControllerState.BUTTON_L1
		ControllerAction.R1 -> ControllerState.BUTTON_R1
		ControllerAction.L3 -> ControllerState.BUTTON_L3
		ControllerAction.R3 -> ControllerState.BUTTON_R3
		ControllerAction.START -> ControllerState.BUTTON_OPTIONS
		ControllerAction.SELECT -> if(!isRemotePlay) ControllerState.BUTTON_SHARE else null
		ControllerAction.HOME -> if(isRemotePlay) ControllerState.BUTTON_PS else null
		ControllerAction.DPAD_UP -> ControllerState.BUTTON_DPAD_UP
		ControllerAction.DPAD_DOWN -> ControllerState.BUTTON_DPAD_DOWN
		ControllerAction.DPAD_LEFT -> ControllerState.BUTTON_DPAD_LEFT
		ControllerAction.DPAD_RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
		ControllerAction.TOUCHPAD_CLICK -> ControllerState.BUTTON_TOUCHPAD
		else -> null
	}

	// ---- Action press / release ----

	private fun pressAction(action: ControllerAction)
	{
		when(action)
		{
			ControllerAction.L2 -> { keyControllerState.l2State = UByte.MAX_VALUE; controllerStateUpdated() }
			ControllerAction.R2 -> { keyControllerState.r2State = UByte.MAX_VALUE; controllerStateUpdated() }
			ControllerAction.TOUCHPAD_CLICK -> quickTouchpadTap(960U.toUShort(), 471U.toUShort())
			ControllerAction.TOUCHPAD_HOLD -> startTouchpadHold()
			ControllerAction.TOUCHPAD_LEFT_CLICK -> quickTouchpadTap(480U.toUShort(), 471U.toUShort())
			ControllerAction.TOUCHPAD_RIGHT_CLICK -> quickTouchpadTap(1440U.toUShort(), 471U.toUShort())
			ControllerAction.TOUCHPAD_SWIPE_UP -> quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_UP)
			ControllerAction.TOUCHPAD_SWIPE_DOWN -> quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_DOWN)
			ControllerAction.TOUCHPAD_SWIPE_LEFT -> quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_LEFT)
			ControllerAction.TOUCHPAD_SWIPE_RIGHT -> quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_RIGHT)
			else -> {
				val mask = actionToButtonMask(action) ?: return
				keyControllerState.buttons = keyControllerState.buttons or mask
				controllerStateUpdated()
			}
		}
	}

	private fun releaseAction(action: ControllerAction)
	{
		when(action)
		{
			ControllerAction.L2 -> { keyControllerState.l2State = 0U; controllerStateUpdated() }
			ControllerAction.R2 -> { keyControllerState.r2State = 0U; controllerStateUpdated() }
			ControllerAction.TOUCHPAD_HOLD -> stopTouchpadHold()
			// Tap/swipe actions are fire-and-forget; quickTouchpadTap/Swipe handle their own cleanup
			ControllerAction.TOUCHPAD_CLICK, ControllerAction.TOUCHPAD_LEFT_CLICK,
			ControllerAction.TOUCHPAD_RIGHT_CLICK, ControllerAction.TOUCHPAD_SWIPE_UP,
			ControllerAction.TOUCHPAD_SWIPE_DOWN, ControllerAction.TOUCHPAD_SWIPE_LEFT,
			ControllerAction.TOUCHPAD_SWIPE_RIGHT -> {}
			else -> {
				val mask = actionToButtonMask(action) ?: return
				keyControllerState.buttons = keyControllerState.buttons and mask.inv()
				controllerStateUpdated()
			}
		}
	}

	private fun fireQuickPress(action: ControllerAction)
	{
		pressAction(action)
		when(action)
		{
			ControllerAction.TOUCHPAD_CLICK, ControllerAction.TOUCHPAD_LEFT_CLICK,
			ControllerAction.TOUCHPAD_RIGHT_CLICK, ControllerAction.TOUCHPAD_SWIPE_UP,
			ControllerAction.TOUCHPAD_SWIPE_DOWN, ControllerAction.TOUCHPAD_SWIPE_LEFT,
			ControllerAction.TOUCHPAD_SWIPE_RIGHT -> {}
			else -> handler.postDelayed({ releaseAction(action) }, 80)
		}
	}

	// ---- Combo modifier lifecycle ----

	// Actions that fire as a momentary pulse rather than being held for the key duration.
	// TOUCHPAD_CLICK is included so it never overlaps with BUTTON_SHARE when both are on
	// the same physical key — the brief BUTTON_TOUCHPAD pulse fires then clears independently.
	private fun isQuickPressAction(action: ControllerAction) =
		action == ControllerAction.TOUCHPAD_CLICK
		|| action == ControllerAction.TOUCHPAD_LEFT_CLICK
		|| action == ControllerAction.TOUCHPAD_RIGHT_CLICK
		|| action == ControllerAction.TOUCHPAD_SWIPE_UP
		|| action == ControllerAction.TOUCHPAD_SWIPE_DOWN
		|| action == ControllerAction.TOUCHPAD_SWIPE_LEFT
		|| action == ControllerAction.TOUCHPAD_SWIPE_RIGHT

	private fun onComboModifierDown(keyCode: Int)
	{
		if(keyCode !in heldModifiers)
		{
			heldModifiers[keyCode] = false
			triggeredComboAxes.clear()
			// Pre-mark any combo-trigger axes that are already above threshold (e.g. L2 drift)
			// so they don't fire a combo on the very first motion event after the modifier press.
			for(combo in comboEntries)
			{
				if(combo.modifierKeyCode != keyCode) continue
				if(combo.trigger !is PhysicalInput.AxisDirection) continue
				val current = lastAxisValues[combo.trigger.axis] ?: 0f
				val dir = if(combo.trigger.positive) maxOf(0f, current) else maxOf(0f, -current)
				if(dir > 0.5f) triggeredComboAxes.add(combo.trigger.axis to combo.trigger.positive)
			}

			val actions = singleKeyToActions[keyCode]
			val hasHeldActions = actions?.any { !isQuickPressAction(it) } == true

			actions?.forEach { action ->
				if(!isQuickPressAction(action))
				{
					// Held actions (e.g. SELECT→BUTTON_SHARE) always press immediately on key-down
					pressAction(action)
				}
				else if(!hasHeldActions)
				{
					// No held actions present: fire quick-press actions immediately (same
					// behaviour as the non-modifier single-action path, e.g. TOUCHPAD_CLICK)
					fireQuickPress(action)
				}
				// If there ARE held actions, quick-press actions are deferred to key-up
				// to avoid BUTTON_TOUCHPAD overlapping BUTTON_SHARE in the same state frame
			}
		}
	}

	private fun onComboModifierUp(keyCode: Int)
	{
		val comboTriggered = heldModifiers.remove(keyCode) ?: false
		triggeredComboAxes.clear()

		val toRelease = activeComboActions.entries.filter { it.value == keyCode }.map { it.key }.toList()
		for(action in toRelease)
		{
			activeComboActions.remove(action)
			releaseAction(action)
		}

		val actions = singleKeyToActions[keyCode]
		val hasHeldActions = actions?.any { !isQuickPressAction(it) } == true

		// Two passes: release held actions first, then fire quick presses.
		// This guarantees BUTTON_SHARE (SELECT) is cleared before BUTTON_TOUCHPAD
		// is set, so they never appear together in a controller state frame.
		// Quick-press actions are only deferred here when held actions are also present;
		// if there are no held actions they already fired on key-down.
		actions?.forEach { action ->
			if(!isQuickPressAction(action)) releaseAction(action)
		}
		if(!comboTriggered && hasHeldActions)
		{
			// Defer quick-press actions one event-loop tick (matching the single-action path's
			// handler.post deferral) so the cleared BUTTON_SHARE state is fully processed
			// by the server before BUTTON_TOUCHPAD appears.
			val quickPressActions = singleKeyToActions[keyCode]?.filter { isQuickPressAction(it) } ?: emptyList()
			if(quickPressActions.isNotEmpty())
			{
				handler.post {
					quickPressActions.forEach { fireQuickPress(it) }
				}
			}
		}
	}

	// ---- dispatchKeyEvent ----

	// ---- Physical DualSense touchpad ----

	/**
	 * Whether a MotionEvent came from a controller's own touchpad rather than the
	 * screen. Some vendors surface it as SOURCE_TOUCHPAD, others fold it into
	 * SOURCE_MOUSE, so the mouse case is additionally checked against the device.
	 */
	private fun isControllerTouchpadMotionEvent(event: MotionEvent): Boolean
	{
		val source = event.source
		if((source and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD)
			return true
		if((source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)
			return isLikelyControllerTouchpadDevice(event.device)
		return false
	}

	private fun isLikelyControllerTouchpadDevice(device: InputDevice?): Boolean
	{
		if(device == null)
			return false
		val sources = device.sources
		if((sources and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD)
			return true
		if(device.vendorId == PLAYSTATION_VENDOR_ID && device.name.contains("touchpad", ignoreCase = true))
			return true
		if(device.vendorId == PLAYSTATION_VENDOR_ID &&
			(sources and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)) != 0)
			return true
		return false
	}

	/** Maps a raw axis value onto the PS5 touchpad grid, using the device's own
	 *  reported range where available. */
	private fun touchpadCoordinate(value: Float, range: InputDevice.MotionRange?, maxExclusive: UShort): UShort
	{
		val maxIndex = maxExclusive.toInt() - 1
		val normalized = when
		{
			range != null && range.range > 0.0f -> ((value - range.min) / range.range).coerceIn(0.0f, 1.0f)
			value.isFinite() && value in 0.0f..1.0f -> value
			else -> 0.0f
		}
		return (normalized * maxIndex.toFloat()).toInt().coerceIn(0, maxIndex).toUShort()
	}

	private fun touchpadAxis(event: MotionEvent, pointerIndex: Int, axis: Int, maxExclusive: UShort): UShort
	{
		val source = if((event.source and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD)
			InputDevice.SOURCE_TOUCHPAD
		else
			event.source
		val range = event.device?.getMotionRange(axis, source) ?: event.device?.getMotionRange(axis)
		val value = if(axis == MotionEvent.AXIS_X) event.getX(pointerIndex) else event.getY(pointerIndex)
		return touchpadCoordinate(value, range, maxExclusive)
	}

	private fun clearTouchpadTouches()
	{
		val activeTouchIds = touchpadPointers.values.toList()
		touchpadPointers.clear()
		activeTouchIds.forEach { physicalTouchpadControllerState.stopTouch(it) }
	}

	private fun syncTouchpadPointers(event: MotionEvent, ignorePointerId: Int? = null)
	{
		val activePointerIds = LinkedHashSet<Int>(event.pointerCount)
		for(pointerIndex in 0 until event.pointerCount)
		{
			val pointerId = event.getPointerId(pointerIndex)
			if(pointerId == ignorePointerId)
				continue
			activePointerIds += pointerId
			val x = touchpadAxis(event, pointerIndex, MotionEvent.AXIS_X, ControllerState.TOUCHPAD_WIDTH)
			val y = touchpadAxis(event, pointerIndex, MotionEvent.AXIS_Y, ControllerState.TOUCHPAD_HEIGHT)
			val touchId = touchpadPointers[pointerId]
				?: physicalTouchpadControllerState.startTouch(x, y)?.also { touchpadPointers[pointerId] = it }
				?: continue
			physicalTouchpadControllerState.setTouchPos(touchId, x, y)
		}

		// Drop touches whose pointer vanished without an explicit UP.
		val stalePointerIds = touchpadPointers.keys.filter { it !in activePointerIds }
		stalePointerIds.forEach { pointerId ->
			touchpadPointers.remove(pointerId)?.let { physicalTouchpadControllerState.stopTouch(it) }
		}
	}

	/**
	 * Feeds the controller's physical touchpad into the stream. Returns true when
	 * the event was consumed, so the caller doesn't also treat it as a screen touch.
	 */
	/** Virtual cursor position for relative (mouse-style) captured touchpad input. */
	private var capturedX = ControllerState.TOUCHPAD_WIDTH.toInt() / 2
	private var capturedY = ControllerState.TOUCHPAD_HEIGHT.toInt() / 2
	private var capturedTouchId: UByte? = null

	/**
	 * Touchpad input arriving via pointer capture.
	 *
	 * Under capture Android may report either absolute touchpad coordinates
	 * (SOURCE_TOUCHPAD) or relative deltas (SOURCE_MOUSE_RELATIVE), depending on
	 * device and vendor. Absolute is preferred when available; otherwise deltas
	 * are integrated into a virtual position so the PS5 still sees a coherent
	 * touch path.
	 */
	fun onCapturedTouchpadEvent(event: MotionEvent): Boolean
	{
		capturedDiagCount++
		if(capturedDiagCount <= 20L || capturedDiagCount % 200L == 0L)
			Log.i("TouchpadDiag", "CAPTURED #$capturedDiagCount src=0x${Integer.toHexString(event.source)}" +
				" action=${event.actionMasked} btn=${event.buttonState} dev='${event.device?.name}'" +
				" x=${event.x} y=${event.y} rel=(${event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)}," +
				"${event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)})")

		// Absolute path: treat exactly like an uncaptured touchpad event.
		if((event.source and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD)
			return onTouchpadMotionEvent(event)

		// Relative path: integrate deltas into a virtual position.
		val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
		val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
		val maxX = ControllerState.TOUCHPAD_WIDTH.toInt() - 1
		val maxY = ControllerState.TOUCHPAD_HEIGHT.toInt() - 1
		capturedX = (capturedX + dx.toInt()).coerceIn(0, maxX)
		capturedY = (capturedY + dy.toInt()).coerceIn(0, maxY)

		val pressed = (event.buttonState and MotionEvent.BUTTON_PRIMARY) != 0
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
				if(pressed)
					keyControllerState.buttons = keyControllerState.buttons or ControllerState.BUTTON_TOUCHPAD
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
				keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
				capturedTouchId?.let { physicalTouchpadControllerState.stopTouch(it) }
				capturedTouchId = null
			}
		}

		// A relative device gives no notion of "finger down", so a touch is held
		// for as long as movement continues; it is released on ACTION_UP above.
		if(event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE)
		{
			val id = capturedTouchId
				?: physicalTouchpadControllerState.startTouch(capturedX.toUShort(), capturedY.toUShort())
					?.also { capturedTouchId = it }
			if(id != null)
				physicalTouchpadControllerState.setTouchPos(id, capturedX.toUShort(), capturedY.toUShort())
		}

		controllerStateUpdated()
		return true
	}

	fun onTouchpadMotionEvent(event: MotionEvent): Boolean
	{
		// Diagnostic: log any event from a non-touchscreen source so we can see
		// what the controller touchpad actually arrives as -- or whether the
		// system consumes it as a cursor before we ever see it.
		if((event.source and InputDevice.SOURCE_TOUCHSCREEN) != InputDevice.SOURCE_TOUCHSCREEN)
		{
			touchpadDiagCount++
			if(touchpadDiagCount <= 20L || touchpadDiagCount % 100L == 0L)
				Log.i("TouchpadDiag", "#$touchpadDiagCount src=0x${Integer.toHexString(event.source)}" +
					" action=${event.actionMasked} dev='${event.device?.name}' vid=0x${Integer.toHexString(event.device?.vendorId ?: 0)}" +
					" accepted=${isControllerTouchpadMotionEvent(event)}")
		}
		if(!isControllerTouchpadMotionEvent(event))
			return false

		val isMouseSource = (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
				// A touchpad reported as a mouse presses its button on DOWN.
				if(isMouseSource && event.actionMasked == MotionEvent.ACTION_DOWN)
					keyControllerState.buttons = keyControllerState.buttons or ControllerState.BUTTON_TOUCHPAD
				syncTouchpadPointers(event)
			}
			MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> syncTouchpadPointers(event)
			MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
				val pointerId = event.getPointerId(event.actionIndex)
				if(isMouseSource && event.actionMasked == MotionEvent.ACTION_UP)
					keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
				// The lifted pointer is still in the event, so exclude it explicitly.
				syncTouchpadPointers(event, ignorePointerId = pointerId)
				touchpadPointers.remove(pointerId)?.let { physicalTouchpadControllerState.stopTouch(it) }
			}
			MotionEvent.ACTION_CANCEL -> {
				clearTouchpadTouches()
				keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
			}
			MotionEvent.ACTION_BUTTON_PRESS -> {
				if(event.actionButton == MotionEvent.BUTTON_PRIMARY)
					keyControllerState.buttons = keyControllerState.buttons or ControllerState.BUTTON_TOUCHPAD
				syncTouchpadPointers(event)
			}
			MotionEvent.ACTION_BUTTON_RELEASE -> {
				if(event.actionButton == MotionEvent.BUTTON_PRIMARY)
					keyControllerState.buttons = keyControllerState.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
				syncTouchpadPointers(event)
			}
			else -> return false
		}

		controllerStateUpdated()
		return true
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
		if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0)
			return event.keyCode in comboModifierKeyCodes || event.keyCode in singleKeyToActions
		val isDown = event.action == KeyEvent.ACTION_DOWN

		// --- PHYSICAL PS BUTTON ---
		// A real DualSense reports its PS button as BUTTON_MODE (scancode 0x13c
		// in Vendor_054c_Product_0ce6.kl); some pads report BUTTON_C instead.
		// DEFAULT_MAPPING only binds HOME to a SELECT+START combo, so on a pad
		// that actually has the button, pressing it did nothing at all. Handled
		// ahead of the combo layer because it is never a modifier, and left
		// un-remappable so it always works even with a custom mapping.
		if(event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE || event.keyCode == KeyEvent.KEYCODE_BUTTON_C)
		{
			keyControllerState.buttons =
				if(isDown) keyControllerState.buttons or ControllerState.BUTTON_PS
				else keyControllerState.buttons and ControllerState.BUTTON_PS.inv()
			controllerStateUpdated()
			return true
		}

		// --- COMBO MODIFIER ---
		if(event.keyCode in comboModifierKeyCodes)
		{
			if(isDown) onComboModifierDown(event.keyCode) else onComboModifierUp(event.keyCode)
			return true
		}

		// --- COMBO TRIGGER (button) ---
		if(isDown && heldModifiers.isNotEmpty())
		{
			for(combo in comboEntries)
			{
				if(combo.trigger !is PhysicalInput.Button) continue
				if(combo.trigger.keyCode != event.keyCode) continue
				if(combo.modifierKeyCode !in heldModifiers) continue

				heldModifiers[combo.modifierKeyCode] = true
				pressAction(combo.action)
				when(combo.action)
				{
					ControllerAction.TOUCHPAD_SWIPE_UP, ControllerAction.TOUCHPAD_SWIPE_DOWN,
					ControllerAction.TOUCHPAD_SWIPE_LEFT, ControllerAction.TOUCHPAD_SWIPE_RIGHT -> {}
					else -> activeComboActions[combo.action] = combo.modifierKeyCode
				}
				return true
			}
		}

		if(!isDown)
		{
			val activeCombo = activeComboActions.entries.firstOrNull { (action, _) ->
				comboEntries.any {
					it.action == action &&
					it.trigger is PhysicalInput.Button &&
					it.trigger.keyCode == event.keyCode
				}
			}
			if(activeCombo != null)
			{
				activeComboActions.remove(activeCombo.key)
				releaseAction(activeCombo.key)
				return true
			}
		}

		// --- SINGLE-INPUT ACTION(S) — one physical button may fire multiple actions ---
		val actions = singleKeyToActions[event.keyCode] ?: return false

		// If any held action (e.g. SELECT→BUTTON_SHARE) shares this key with TOUCHPAD_CLICK,
		// defer the touchpad quick press until after the held action releases so the two
		// button bits never appear in the same state update sent to the console.
		val hasHeldAction = actions.any { !isQuickPressAction(it) }

		for(action in actions)
		{
			when(action)
			{
				ControllerAction.L2 -> { keyControllerState.l2State = if(isDown) UByte.MAX_VALUE else 0U }
				ControllerAction.R2 -> { keyControllerState.r2State = if(isDown) UByte.MAX_VALUE else 0U }
				ControllerAction.TOUCHPAD_CLICK -> when {
					// Standalone: fire on key-down as normal
					!hasHeldAction && isDown -> fireQuickPress(action)
					// Paired with held action: defer to key-up so BUTTON_TOUCHPAD never
					// overlaps BUTTON_SHARE (or similar) in the same controller state frame
					hasHeldAction && !isDown -> handler.post { fireQuickPress(action) }
				}
				ControllerAction.TOUCHPAD_HOLD -> if(isDown) startTouchpadHold() else stopTouchpadHold()
				ControllerAction.TOUCHPAD_LEFT_CLICK -> { if(isDown) quickTouchpadTap(480U.toUShort(), 471U.toUShort()) }
				ControllerAction.TOUCHPAD_RIGHT_CLICK -> { if(isDown) quickTouchpadTap(1440U.toUShort(), 471U.toUShort()) }
				ControllerAction.TOUCHPAD_SWIPE_UP -> { if(isDown) quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_UP) }
				ControllerAction.TOUCHPAD_SWIPE_DOWN -> { if(isDown) quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_DOWN) }
				ControllerAction.TOUCHPAD_SWIPE_LEFT -> { if(isDown) quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_LEFT) }
				ControllerAction.TOUCHPAD_SWIPE_RIGHT -> { if(isDown) quickTouchpadSwipe(KeyEvent.KEYCODE_DPAD_RIGHT) }
				else -> {
					val buttonMask = actionToButtonMask(action) ?: continue
					keyControllerState.buttons = if(isDown) keyControllerState.buttons or buttonMask
					                              else keyControllerState.buttons and buttonMask.inv()
				}
			}
		}
		controllerStateUpdated()
		return true
	}

	// ---- onGenericMotionEvent ----

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false

		fun Float.signedAxis() = (this * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (this * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()
		fun Float.coerceSigned() = coerceIn(-1f, 1f)
		// Front-loads L2/R2 response so a partial squeeze reaches a meaningful analog value
		// sooner instead of tracking raw travel linearly (which felt like it needed a near-full
		// press before anything registered), while still reaching maximum at a full press.
		fun Float.triggerResponseCurve() = if(this <= 0f) 0f else pow(0.4f)

		// L2/R2 travel is reported on different axis codes depending on the controller's
		// driver: Xbox-style pads use AXIS_LTRIGGER/AXIS_RTRIGGER, while DualShock/DualSense
		// pads commonly report the same physical trigger via AXIS_BRAKE/AXIS_GAS instead.
		// Checking both and taking whichever is populated means the default mapping gets
		// a genuine analog reading regardless of which axis the connected pad actually uses.
		fun MotionEvent.resolvedAxisValue(axis: Int): Float = when(axis)
		{
			MotionEvent.AXIS_LTRIGGER -> maxOf(getAxisValue(axis), getAxisValue(MotionEvent.AXIS_BRAKE))
			MotionEvent.AXIS_RTRIGGER -> maxOf(getAxisValue(axis), getAxisValue(MotionEvent.AXIS_GAS))
			else -> getAxisValue(axis)
		}

		// Update last-known axis values for combo edge detection
		for(combo in comboEntries)
		{
			if(combo.trigger is PhysicalInput.AxisDirection)
				lastAxisValues[combo.trigger.axis] = event.resolvedAxisValue(combo.trigger.axis)
		}

		// Combo axis triggers (modifier held + axis movement)
		if(heldModifiers.isNotEmpty())
		{
			for(combo in comboEntries)
			{
				if(combo.trigger !is PhysicalInput.AxisDirection) continue
				if(combo.modifierKeyCode !in heldModifiers) continue
				val rawValue = event.resolvedAxisValue(combo.trigger.axis)
				val dirValue = if(combo.trigger.positive) maxOf(0f, rawValue) else maxOf(0f, -rawValue)
				val triggerKey = combo.trigger.axis to combo.trigger.positive
				if(dirValue > 0.5f)
				{
					if(triggerKey !in triggeredComboAxes)
					{
						// First time this axis crosses the threshold — fire the combo once
						heldModifiers[combo.modifierKeyCode] = true
						triggeredComboAxes.add(triggerKey)
						// Quick-press actions (swipes) clean themselves up via their own delayed
						// handler and must never be tracked here, matching the button-trigger path.
						if(!isQuickPressAction(combo.action)) activeComboActions[combo.action] = combo.modifierKeyCode
						pressAction(combo.action)
						return true
					}
					// Already triggered — let normal axis processing continue (axis is
					// excluded from it via triggeredComboAxes, so no double-processing)
				}
				else if(triggerKey in triggeredComboAxes)
				{
					// Axis has returned to neutral while the modifier is still held — release any
					// held (non-quick-press) combo action bound to it, e.g. TOUCHPAD_HOLD, so it
					// doesn't stay stuck on until the modifier itself is released.
					triggeredComboAxes.remove(triggerKey)
					if(activeComboActions.remove(combo.action) != null) releaseAction(combo.action)
				}
			}
		}

		// Normal axis processing (skip axes claimed by an active combo)
		var leftX = 0f; var leftY = 0f; var rightX = 0f; var rightY = 0f
		var l2 = 0f; var r2 = 0f; var dpadX = 0f; var dpadY = 0f

		for((action, axis, positive) in singleAxisMappings)
		{
			if((axis to positive) in triggeredComboAxes) continue
			val rawValue = event.resolvedAxisValue(axis)
			val dirValue = if(positive) maxOf(0f, rawValue) else maxOf(0f, -rawValue)
			when(action)
			{
				ControllerAction.LEFT_STICK_RIGHT -> leftX += dirValue
				ControllerAction.LEFT_STICK_LEFT -> leftX -= dirValue
				ControllerAction.LEFT_STICK_DOWN -> leftY += dirValue
				ControllerAction.LEFT_STICK_UP -> leftY -= dirValue
				ControllerAction.RIGHT_STICK_RIGHT -> rightX += dirValue
				ControllerAction.RIGHT_STICK_LEFT -> rightX -= dirValue
				ControllerAction.RIGHT_STICK_DOWN -> rightY += dirValue
				ControllerAction.RIGHT_STICK_UP -> rightY -= dirValue
				ControllerAction.L2 -> l2 += dirValue
				ControllerAction.R2 -> r2 += dirValue
				ControllerAction.DPAD_RIGHT -> dpadX += dirValue
				ControllerAction.DPAD_LEFT -> dpadX -= dirValue
				ControllerAction.DPAD_DOWN -> dpadY += dirValue
				ControllerAction.DPAD_UP -> dpadY -= dirValue
				else -> {}
			}
		}

		var dpadButtons = 0U
		if(dpadX > 0.5f) dpadButtons = dpadButtons or ControllerState.BUTTON_DPAD_RIGHT
		if(dpadX < -0.5f) dpadButtons = dpadButtons or ControllerState.BUTTON_DPAD_LEFT
		if(dpadY > 0.5f) dpadButtons = dpadButtons or ControllerState.BUTTON_DPAD_DOWN
		if(dpadY < -0.5f) dpadButtons = dpadButtons or ControllerState.BUTTON_DPAD_UP

		val dpadMask = ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_LEFT or
				ControllerState.BUTTON_DPAD_DOWN or ControllerState.BUTTON_DPAD_UP
		motionControllerState.buttons = (motionControllerState.buttons and dpadMask.inv()) or dpadButtons
		motionControllerState.leftX = leftX.coerceSigned().signedAxis()
		motionControllerState.leftY = leftY.coerceSigned().signedAxis()
		motionControllerState.rightX = rightX.coerceSigned().signedAxis()
		motionControllerState.rightY = rightY.coerceSigned().signedAxis()
		motionControllerState.l2State = l2.coerceIn(0f, 1f).triggerResponseCurve().unsignedAxis()
		motionControllerState.r2State = r2.coerceIn(0f, 1f).triggerResponseCurve().unsignedAxis()

		controllerStateUpdated()
		return true
	}
}
