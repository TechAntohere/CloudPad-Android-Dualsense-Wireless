// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.metallic.chiaki.common.Preferences
import com.pylux.stream.R
import com.pylux.stream.databinding.ActivitySettingsBinding

interface TitleFragment
{
	fun getTitle(resources: Resources): String
}

/**
 * Implemented by a settings fragment that needs to swallow DualSense input
 * while it is on screen.
 *
 * Android's input stack claims DualSense HID reports before the app sees them,
 * so a screen that talks to the controller directly has to capture the pointer
 * and drop the events Android synthesises, or they leak through as button
 * presses in the settings UI.
 */
interface ControllerInputContainmentOwner
{
	val controllerInputContainmentActive: Boolean
	fun stopControllerInputContainment(reason: String)
	fun noteContainedControllerInput(kind: String)
}

class SettingsActivity: AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback
{
	companion object
	{
		private const val SONY_VENDOR_ID = 0x054c
		private val DUALSENSE_PRODUCT_IDS = setOf(0x0ce6, 0x0df2)
	}

	private lateinit var binding: ActivitySettingsBinding

	override fun onCreate(savedInstanceState: Bundle?)
	{
		val prefs = Preferences(this)
		if (prefs.getThemeColour() != "pink") setTheme(prefs.getThemeStyleRes())
		super.onCreate(savedInstanceState)
		binding = ActivitySettingsBinding.inflate(layoutInflater)
		setContentView(binding.root)
		title = ""
		setSupportActionBar(binding.toolbar)
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		{
			binding.root.setOnCapturedPointerListener { _, event ->
				consumeContainedControllerInput(event, "captured")
			}
		}

		val rootFragment = SettingsFragment()
		replaceFragment(rootFragment, false)
		supportFragmentManager.addOnBackStackChangedListener {
			val titleFragment = supportFragmentManager.findFragmentById(R.id.settingsFragment) as? TitleFragment ?: return@addOnBackStackChangedListener
			binding.titleTextView.text = titleFragment.getTitle(resources)
		}
		binding.titleTextView.text = rootFragment.getTitle(resources)
	}

	override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference) = when(pref.fragment)
	{
		SettingsRegisteredHostsFragment::class.java.canonicalName -> {
			replaceFragment(SettingsRegisteredHostsFragment(), true)
			true
		}
		SettingsDualSenseInfoFragment::class.java.canonicalName -> {
			replaceFragment(SettingsDualSenseInfoFragment(), true)
			true
		}
		else -> false
	}

	// --- DualSense input containment ---------------------------------------

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			syncControllerInputContainment()
		else
		{
			containmentOwner()?.stopControllerInputContainment("window focus lost")
			releaseControllerInputContainment()
		}
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(consumeContainedControllerInput(event, "key"))
			return true
		return super.dispatchKeyEvent(event)
	}

	override fun dispatchTouchEvent(event: MotionEvent): Boolean
	{
		if(consumeContainedControllerInput(event, "touch"))
			return true
		return super.dispatchTouchEvent(event)
	}

	override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(consumeContainedControllerInput(event, "motion"))
			return true
		return super.dispatchGenericMotionEvent(event)
	}

	fun requestControllerInputContainment()
	{
		syncControllerInputContainment()
	}

	fun releaseControllerInputContainment()
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
			return
		if(binding.root.hasPointerCapture())
			binding.root.releasePointerCapture()
	}

	private fun syncControllerInputContainment()
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
			return
		if(containmentOwner()?.controllerInputContainmentActive != true)
		{
			releaseControllerInputContainment()
			return
		}
		binding.root.isFocusable = true
		binding.root.isFocusableInTouchMode = true
		binding.root.post {
			if(containmentOwner()?.controllerInputContainmentActive == true && hasWindowFocus())
			{
				binding.root.requestFocus()
				if(!binding.root.hasPointerCapture())
					binding.root.requestPointerCapture()
			}
		}
	}

	private fun consumeContainedControllerInput(event: InputEvent, kind: String): Boolean
	{
		val owner = containmentOwner() ?: return false
		if(!owner.controllerInputContainmentActive)
			return false
		if(!isDualSenseLike(event.device))
			return false
		owner.noteContainedControllerInput(kind)
		return true
	}

	private fun containmentOwner(): ControllerInputContainmentOwner? =
		supportFragmentManager.findFragmentById(R.id.settingsFragment) as? ControllerInputContainmentOwner

	private fun isDualSenseLike(device: InputDevice?): Boolean
	{
		if(device == null)
			return false
		if(device.vendorId == SONY_VENDOR_ID && (device.productId in DUALSENSE_PRODUCT_IDS || device.name.contains("DualSense", ignoreCase = true)))
			return true
		return device.name.contains("DualSense", ignoreCase = true) ||
			device.name.contains("Wireless Controller", ignoreCase = true)
	}

	private fun replaceFragment(fragment: Fragment, addToBackStack: Boolean)
	{
		supportFragmentManager.beginTransaction()
			.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
			.replace(R.id.settingsFragment, fragment)
			.also {
				if(addToBackStack)
					it.addToBackStack(null)
			}
			.commit()
	}
}