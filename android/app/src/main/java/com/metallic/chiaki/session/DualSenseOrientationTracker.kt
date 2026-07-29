// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import com.metallic.chiaki.lib.ControllerState
import kotlin.math.sqrt

/**
 * Fuses a DualSense's gyro and accelerometer into the orientation quaternion the
 * console expects, and maps both into the console's axis convention.
 *
 * This exists because feeding raw gyro/accel alone is not enough: the feedback
 * packet also carries orientX/Y/Z/W, and if those stay at a constant identity
 * the host sees a controller that never rotates -- motion appears dead even
 * though gyro values are arriving correctly. CloudPad's own phone-sensor path
 * gets its quaternion from TYPE_ROTATION_VECTOR; a controller IMU has no
 * equivalent, so the quaternion has to be integrated here.
 *
 * Madgwick filter, ported from Senshi's RawDualSenseOrientationTracker.
 * [BETA_WARMUP] is deliberately huge for the first [WARMUP_SAMPLES_COUNT]
 * samples so the estimate snaps to gravity immediately instead of drifting in
 * from identity over several seconds.
 */
class DualSenseOrientationTracker
{
	companion object
	{
		private const val WARMUP_SAMPLES_COUNT = 30
		private const val BETA_WARMUP = 20.0f
		private const val BETA_DEFAULT = 0.05f
		/** Deadband to stop micro-jitter being streamed as constant tiny rotations. */
		private const val ORIENT_FUZZ = 0.0007f
		private const val ACCEL_ZERO_MIN_NORM_SQ = 0.72f
		private const val ACCEL_ZERO_MAX_NORM_SQ = 1.32f
		private const val ACCEL_ZERO_MAX_GYRO_SQ = 0.03f

		// The resting quaternion is a 45 deg rotation about X, undone in applyTo.
		private const val SIN_1_4_PI = 0.70710677f
		private const val SIN_NEG_1_4_PI = -0.70710677f
		private const val COS_1_4_PI = 0.70710677f
		private const val COS_NEG_1_4_PI = 0.70710677f
	}

	private var gyroX = 0.0f
	private var gyroY = 0.0f
	private var gyroZ = 0.0f
	private var accelX = 0.0f
	private var accelY = 1.0f
	private var accelZ = 0.0f
	// Madgwick is seeded with a 90 deg rotation about X and applyTo rotates back,
	// matching chiaki_orientation_init in lib/src/orientation.c.
	private var orientX = SIN_1_4_PI
	private var orientY = 0.0f
	private var orientZ = 0.0f
	private var orientW = COS_1_4_PI
	private var lastTimestampNs = 0L
	private var sampleIndex = 0L

	// Accelerometer zero-offset, captured once while the pad is demonstrably at
	// rest. This is the per-unit bias correction that a fixed nominal scale
	// factor cannot provide, and without it the fused orientation slowly leans.
	private var accelZeroCaptured = false
	private var accelZeroX = 0.0f
	private var accelZeroY = 0.0f
	private var accelZeroZ = 0.0f

	fun reset()
	{
		gyroX = 0.0f; gyroY = 0.0f; gyroZ = 0.0f
		accelX = 0.0f; accelY = 1.0f; accelZ = 0.0f
		orientX = SIN_1_4_PI; orientY = 0.0f; orientZ = 0.0f; orientW = COS_1_4_PI
		lastTimestampNs = 0L
		sampleIndex = 0L
		accelZeroCaptured = false
		accelZeroX = 0.0f; accelZeroY = 0.0f; accelZeroZ = 0.0f
	}

	/**
	 * @param gx,gy,gz rad/s in the controller's own frame
	 * @param ax,ay,az g in the controller's own frame
	 */
	fun update(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float, timestampNs: Long)
	{
		maybeCaptureAccelZero(gx, gy, gz, ax, ay, az)
		val correctedAx = if(accelZeroCaptured) ax - accelZeroX else ax
		val correctedAy = if(accelZeroCaptured) ay - accelZeroY else ay
		val correctedAz = if(accelZeroCaptured) az - accelZeroZ else az

		gyroX = gx; gyroY = gy; gyroZ = gz
		accelX = correctedAx; accelY = correctedAy; accelZ = correctedAz
		sampleIndex += 1L
		if(sampleIndex <= 1L || lastTimestampNs == 0L)
		{
			lastTimestampNs = timestampNs
			return
		}

		// Clamped so a stall can't integrate one huge step.
		val deltaSec = (timestampNs - lastTimestampNs).coerceIn(1L, 50_000_000L).toFloat() / 1_000_000_000.0f
		lastTimestampNs = timestampNs

		updateOrientation(
			gx, gy, gz, correctedAx, correctedAy, correctedAz,
			beta = if(sampleIndex < WARMUP_SAMPLES_COUNT) BETA_WARMUP else BETA_DEFAULT,
			timeStepSec = deltaSec
		)
	}

	/** Captures the accel zero once the pad is near 1g and barely rotating. */
	private fun maybeCaptureAccelZero(gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float)
	{
		if(accelZeroCaptured || sampleIndex < 3L)
			return
		val accelNormSq = ax * ax + ay * ay + az * az
		val gyroNormSq = gx * gx + gy * gy + gz * gz
		if(accelNormSq < ACCEL_ZERO_MIN_NORM_SQ || accelNormSq > ACCEL_ZERO_MAX_NORM_SQ || gyroNormSq > ACCEL_ZERO_MAX_GYRO_SQ)
			return
		accelZeroX = ax
		accelZeroY = ay - 1.0f
		accelZeroZ = az
		accelZeroCaptured = true
	}

	/**
	 * Writes fused motion into [target], mirroring
	 * chiaki_orientation_tracker_apply_to_controller_state in
	 * lib/src/orientation.c so Android behaves identically to the desktop
	 * frontend.
	 *
	 * Gyro and accel pass through unpermuted. Senshi applies a permutation here
	 * (gyroX = -gyroZ, gyroY = gyroX, gyroZ = -gyroY) because it parses values
	 * straight out of the raw HID report; we take them from the platform's
	 * controller sensor API, which -- like SDL on desktop -- has already mapped
	 * them into the standard frame. Reapplying that permutation is a double
	 * transform, observed on-device as pitching the pad producing yaw.
	 *
	 * The quaternion still needs the rotation back out of Madgwick's seeded
	 * frame, but without Senshi's additional axis swap.
	 */
	fun applyTo(target: ControllerState)
	{
		target.gyroX = gyroX
		target.gyroY = gyroY
		target.gyroZ = gyroZ
		target.accelX = accelX
		target.accelY = accelY
		target.accelZ = accelZ

		target.orientW = COS_NEG_1_4_PI * orientW - SIN_NEG_1_4_PI * orientX
		target.orientX = COS_NEG_1_4_PI * orientX + SIN_NEG_1_4_PI * orientW
		target.orientY = COS_NEG_1_4_PI * orientY - SIN_NEG_1_4_PI * orientZ
		target.orientZ = COS_NEG_1_4_PI * orientZ + SIN_NEG_1_4_PI * orientY
	}

	private fun updateOrientation(
		gx: Float, gy: Float, gz: Float,
		ax: Float, ay: Float, az: Float,
		beta: Float, timeStepSec: Float
	)
	{
		var q0 = orientW
		var q1 = orientX
		var q2 = orientY
		var q3 = orientZ

		var qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz)
		var qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy)
		var qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx)
		var qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx)

		// Gradient-descent correction toward measured gravity.
		if(!(ax == 0.0f && ay == 0.0f && az == 0.0f))
		{
			val accelNorm = invSqrt(ax * ax + ay * ay + az * az)
			val normAx = ax * accelNorm
			val normAy = ay * accelNorm
			val normAz = az * accelNorm

			val twoQ0 = 2.0f * q0
			val twoQ1 = 2.0f * q1
			val twoQ2 = 2.0f * q2
			val twoQ3 = 2.0f * q3
			val fourQ0 = 4.0f * q0
			val fourQ1 = 4.0f * q1
			val fourQ2 = 4.0f * q2
			val eightQ1 = 8.0f * q1
			val eightQ2 = 8.0f * q2
			val q0q0 = q0 * q0
			val q1q1 = q1 * q1
			val q2q2 = q2 * q2
			val q3q3 = q3 * q3

			var s0 = fourQ0 * q2q2 + twoQ2 * normAx + fourQ0 * q1q1 - twoQ1 * normAy
			var s1 = fourQ1 * q3q3 - twoQ3 * normAx + 4.0f * q0q0 * q1 - twoQ0 * normAy - fourQ1 + eightQ1 * q1q1 + eightQ1 * q2q2 + fourQ1 * normAz
			var s2 = 4.0f * q0q0 * q2 + twoQ0 * normAx + fourQ2 * q3q3 - twoQ3 * normAy - fourQ2 + eightQ2 * q1q1 + eightQ2 * q2q2 + fourQ2 * normAz
			var s3 = 4.0f * q1q1 * q3 - twoQ1 * normAx + 4.0f * q2q2 * q3 - twoQ2 * normAy
			val stepNormSq = s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3
			if(stepNormSq > 0.000001f)
			{
				val stepNorm = invSqrt(stepNormSq)
				s0 *= stepNorm; s1 *= stepNorm; s2 *= stepNorm; s3 *= stepNorm
				qDot1 -= beta * s0
				qDot2 -= beta * s1
				qDot3 -= beta * s2
				qDot4 -= beta * s3
			}
		}

		q0 += qDot1 * timeStepSec
		q1 += qDot2 * timeStepSec
		q2 += qDot3 * timeStepSec
		q3 += qDot4 * timeStepSec

		val quatNorm = invSqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
		orientW = fuzz(q0 * quatNorm, orientW)
		orientX = fuzz(q1 * quatNorm, orientX)
		orientY = fuzz(q2 * quatNorm, orientY)
		orientZ = fuzz(q3 * quatNorm, orientZ)
	}

	/** Progressive deadband: small changes are damped, large ones pass through. */
	private fun fuzz(cur: Float, prev: Float): Float
	{
		val f = ORIENT_FUZZ
		val d = cur - prev
		return when
		{
			d > -f / 2f && d < f / 2f -> prev
			d > -f && d < f -> 0.75f * prev + 0.25f * cur
			d > -f * 2f && d < f * 2f -> 0.6f * prev + 0.4f * cur
			else -> cur
		}
	}

	private fun invSqrt(value: Float): Float = if(value <= 0.0f) 0.0f else 1.0f / sqrt(value)
}
