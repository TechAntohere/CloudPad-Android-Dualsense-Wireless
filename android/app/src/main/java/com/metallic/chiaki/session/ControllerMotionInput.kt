// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import android.view.InputDevice

/**
 * Motion from the controller's own IMU, rather than the phone/tablet's.
 *
 * Android 12 (API 31) exposes a game controller's sensors through
 * [InputDevice.getSensorManager]. For a DualSense these come from the kernel's
 * hid-playstation driver, which reads the controller's factory calibration
 * (feature report 0x05: per-axis gyro bias, gyro speed calibration and accel
 * min/max) and publishes properly scaled SI values.
 *
 * That matters: on this hardware the driver reports gyro resolution 1024 units
 * per rad/s, derived from that controller's own calibration data. Implementations
 * that instead parse the raw HID report with a nominal hardcoded constant get
 * both the scale and the per-unit bias wrong, which shows up as drift and as
 * axes that feel cross-coupled.
 *
 * Below API 31 there is no per-device sensor API, so this stays inert and the
 * caller keeps using the device's own sensors.
 */
class ControllerMotionInput(
	private val onMotion: (gyroX: Float, gyroY: Float, gyroZ: Float,
	                       accelX: Float, accelY: Float, accelZ: Float,
	                       timestampNs: Long) -> Unit
)
{
	companion object
	{
		private const val TAG = "ControllerMotion"
		private const val PLAYSTATION_VENDOR_ID = 0x054c
		/** ~4 ms, matching the DualSense's 250 Hz BT report rate. */
		private const val SAMPLING_PERIOD_US = 4000
	}

	private var listener: SensorEventListener? = null
	private var attachedDeviceId: Int? = null

	// Diagnostics: confirms whether the platform actually delivers controller
	// sensor events, and at what rate. The DualSense reports at ~250 Hz over BT,
	// but Android may sample far slower or not deliver at all.
	private var gyroEventCount = 0L
	private var accelEventCount = 0L
	private var firstEventNs = 0L

	private var gyroX = 0.0f
	private var gyroY = 0.0f
	private var gyroZ = 0.0f
	private var accelX = 0.0f
	private var accelY = 0.0f
	private var accelZ = 0.0f

	/** True while controller-sourced motion is being delivered. */
	val isActive: Boolean get() = listener != null

	/**
	 * Finds a DualSense-class device exposing SOURCE_SENSOR and starts listening.
	 * Safe to call repeatedly (e.g. on device add/remove); a no-op if already
	 * attached to a still-present device.
	 */
	fun refresh()
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			return

		val current = attachedDeviceId
		if(current != null && InputDevice.getDevice(current) != null)
			return
		if(current != null)
			detach()

		val device = InputDevice.getDeviceIds()
			.map { InputDevice.getDevice(it) }
			.filterNotNull()
			.firstOrNull {
				(it.sources and InputDevice.SOURCE_SENSOR) == InputDevice.SOURCE_SENSOR &&
					(it.vendorId == PLAYSTATION_VENDOR_ID || it.name.contains("DualSense", ignoreCase = true))
			} ?: return

		val sensorManager = device.sensorManager
		val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
		val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
		if(gyro == null && accel == null)
			return

		val l = object: SensorEventListener
		{
			override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
			override fun onSensorChanged(event: SensorEvent)
			{
				if(firstEventNs == 0L)
					firstEventNs = System.nanoTime()
				when(event.sensor.type)
				{
					Sensor.TYPE_GYROSCOPE -> {
						// Already rad/s and already calibrated by the driver.
						gyroX = event.values[0]
						gyroY = event.values[1]
						gyroZ = event.values[2]
						gyroEventCount++
						if(gyroEventCount <= 3L || gyroEventCount % 250L == 0L)
						{
							val elapsedS = (System.nanoTime() - firstEventNs) / 1_000_000_000.0
							val hz = if(elapsedS > 0) gyroEventCount / elapsedS else 0.0
							Log.i(TAG, "gyro #$gyroEventCount %.1f Hz  x=%.3f y=%.3f z=%.3f"
								.format(hz, gyroX, gyroY, gyroZ))
						}
					}
					Sensor.TYPE_ACCELEROMETER -> {
						// m/s^2 -> g, which is what ControllerState carries.
						accelX = event.values[0] / SensorManager.GRAVITY_EARTH
						accelY = event.values[1] / SensorManager.GRAVITY_EARTH
						accelZ = event.values[2] / SensorManager.GRAVITY_EARTH
						accelEventCount++
						if(accelEventCount <= 3L || accelEventCount % 250L == 0L)
							Log.i(TAG, "accel #$accelEventCount x=%.3f y=%.3f z=%.3f".format(accelX, accelY, accelZ))
					}
					else -> return
				}
				// event.timestamp is the hardware timestamp; the driver reports
				// mHasHardwareTimestamp=1 for this device, so it's the right
				// clock for integrating the orientation step.
				onMotion(gyroX, gyroY, gyroZ, accelX, accelY, accelZ, event.timestamp)
			}
		}

		var registered = false
		gyro?.let { registered = sensorManager.registerListener(l, it, SAMPLING_PERIOD_US) || registered }
		accel?.let { registered = sensorManager.registerListener(l, it, SAMPLING_PERIOD_US) || registered }
		if(!registered)
		{
			Log.w(TAG, "Controller exposes sensors but registration failed; falling back to device sensors")
			return
		}

		listener = l
		attachedDeviceId = device.id
		Log.i(TAG, "Using controller IMU from '${device.name}' (id=${device.id})" +
			" gyro=${gyro != null} accel=${accel != null}")
	}

	fun detach()
	{
		val id = attachedDeviceId ?: return
		val l = listener
		if(l != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
			InputDevice.getDevice(id)?.sensorManager?.unregisterListener(l)
		listener = null
		attachedDeviceId = null
		gyroX = 0.0f; gyroY = 0.0f; gyroZ = 0.0f
		accelX = 0.0f; accelY = 0.0f; accelZ = 0.0f
	}
}
