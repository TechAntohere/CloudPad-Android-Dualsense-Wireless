// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

/**
 * Runs [DualSenseL2capProbe] and shows its output.
 *
 * A diagnostic, not a feature: it answers whether an app can open a classic
 * L2CAP channel on a reserved PSM, which decides whether the DualSense mic is
 * reachable over Bluetooth at all. Delete once that's settled either way.
 *
 * Running it disconnects the pad from Android's HID host, so the controller
 * stops working as a system controller until stopped. The probe restores the
 * connection policy and asks the profile to reconnect on every exit path, and
 * leaving this screen stops it.
 */
class SettingsDualSenseMicProbeFragment: PreferenceFragmentCompat(), TitleFragment
{
	private lateinit var workerThread: HandlerThread
	private lateinit var workerHandler: Handler
	private var probe: DualSenseL2capProbe? = null

	private lateinit var runPreference: Preference
	private lateinit var restorePreference: Preference
	private lateinit var logPreference: Preference

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		workerThread = HandlerThread("DualSenseL2capProbe").apply { start() }
		workerHandler = Handler(workerThread.looper)

		val screen = preferenceManager.createPreferenceScreen(requireContext())

		runPreference = Preference(requireContext()).apply {
			key = "dualsense_l2cap_probe_run"
			title = "Run L2CAP host probe"
			summary = "Releases the controller from Android, then tries to open its HID " +
				"channels directly. Press the PS button first so the pad is connected."
			isPersistent = false
			setOnPreferenceClickListener { toggleProbe(); true }
		}
		screen.addPreference(runPreference)

		restorePreference = Preference(requireContext()).apply {
			key = "dualsense_l2cap_probe_restore"
			title = "Restore controller"
			summary = "Stops the probe, re-allows the HID profile and asks it to reconnect. " +
				"If the pad still will not connect, toggle Bluetooth off and on."
			isPersistent = false
			setOnPreferenceClickListener {
				probe?.stop()
				runPreference.title = "Run L2CAP host probe"
				true
			}
		}
		screen.addPreference(restorePreference)

		logPreference = Preference(requireContext()).apply {
			key = "dualsense_l2cap_probe_log"
			title = "Probe output"
			summary = "Not started."
			isPersistent = false
			isSelectable = false
		}
		screen.addPreference(logPreference)

		preferenceScreen = screen
	}

	private fun toggleProbe()
	{
		val current = probe
		if(current != null && current.isRunning)
		{
			current.stop()
			runPreference.title = "Run L2CAP host probe"
			return
		}

		val created = current ?: DualSenseL2capProbe(
			appContext = requireContext().applicationContext,
			workerHandler = workerHandler,
			onUpdate = { text -> postLog(text) }
		).also { probe = it }

		logPreference.summary = "Starting..."
		runPreference.title = "Stop probe"
		created.start()
	}

	private fun postLog(text: String)
	{
		val activity = activity ?: return
		activity.runOnUiThread { if(isAdded) logPreference.summary = text }
	}

	override fun onDestroy()
	{
		super.onDestroy()
		// Never leave the pad detached because the screen was closed.
		probe?.stop()
		workerHandler.postDelayed({
			workerHandler.removeCallbacksAndMessages(null)
			workerThread.quitSafely()
		}, 2500)
	}

	override fun getTitle(resources: Resources): String = "DualSense mic probe"
}
