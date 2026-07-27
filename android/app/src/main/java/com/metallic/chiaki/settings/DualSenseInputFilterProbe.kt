// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import androidx.annotation.RequiresApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.P)
object DualSenseInputFilterProbe
{
    private const val SONY_VENDOR_ID = 0x054C
    private val DUALSENSE_PRODUCT_IDS = setOf(0x0CE6, 0x0DF2)

    fun run(context: Context): String
    {
        val lines = ArrayList<String>()
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/hardware/input/InputManager;",
                "Landroid/hardware/input/IInputManager;",
                "Landroid/view/InputDevice;",
            )
            lines += "HiddenApiBypass: ready"
        }.onFailure { throwable ->
            lines += "HiddenApiBypass: failed: ${throwable.rootMessage()}"
        }

        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        if(inputManager == null)
        {
            lines += "InputManager: unavailable"
            return lines.joinToString("\n")
        }

        val allDevices = InputDevice.getDeviceIds()
            .asIterable()
            .mapNotNull { id: Int -> InputDevice.getDevice(id) }
        val candidates = allDevices.filter(::isDualSenseLike)

        lines += "Input devices: ${allDevices.size}"
        if(candidates.isEmpty())
        {
            lines += "DualSense-like devices: none"
            lines += "No disable attempt was made."
            return lines.joinToString("\n")
        }

        lines += "DualSense-like devices:"
        candidates.forEach { device ->
            lines += "  id=${device.id} vid=${hex16(device.vendorId)} pid=${hex16(device.productId)} enabled=${readEnabled(device)} name=${device.name}"
        }

        val disableMethod = findInputManagerMethod(inputManager, "disableInputDevice")
        val enableMethod = findInputManagerMethod(inputManager, "enableInputDevice")
        val isEnabledMethod = findInputDeviceMethod("isEnabled")
        lines += "Methods: disable=${methodStatus(disableMethod)}, enable=${methodStatus(enableMethod)}, isEnabled=${methodStatus(isEnabledMethod)}"

        val selected = candidates.first()
        lines += "Selected: id=${selected.id} ${selected.name}"

        if(disableMethod == null || enableMethod == null)
        {
            lines += "Result: cannot test. Hidden InputManager enable/disable method not found."
            return lines.joinToString("\n")
        }

        var disableSucceeded = false
        try
        {
            disableMethod.invoke(inputManager, selected.id)
            disableSucceeded = true
            Thread.sleep(150L)
            val afterDisable = InputDevice.getDevice(selected.id)
            lines += "After disable: enabled=${readEnabled(afterDisable)}"
        }
        catch(throwable: Throwable)
        {
            lines += "Disable failed: ${throwable.rootMessage()}"
        }
        finally
        {
            if(disableSucceeded)
            {
                try
                {
                    enableMethod.invoke(inputManager, selected.id)
                    Thread.sleep(150L)
                    val afterEnable = InputDevice.getDevice(selected.id)
                    lines += "After restore: enabled=${readEnabled(afterEnable)}"
                }
                catch(throwable: Throwable)
                {
                    lines += "Restore failed: ${throwable.rootMessage()}"
                }
            }
        }

        lines += if(disableSucceeded)
            "Result: app can toggle the Android input device. Next test would be whether HID report broadcasts still arrive while disabled."
        else
            "Result: app cannot toggle the Android input device. HiddenApiBypass alone is not enough for filtering."
        return lines.joinToString("\n")
    }

    private fun isDualSenseLike(device: InputDevice): Boolean
    {
        if(device.vendorId == SONY_VENDOR_ID && (device.productId in DUALSENSE_PRODUCT_IDS || device.name.contains("DualSense", ignoreCase = true)))
            return true
        return device.name.contains("DualSense", ignoreCase = true) ||
            device.name.contains("Wireless Controller", ignoreCase = true)
    }

    private fun findInputManagerMethod(inputManager: InputManager, name: String): Method?
    {
        val classes = LinkedHashSet<Class<*>>()
        classes += inputManager.javaClass
        classes += InputManager::class.java
        return findOneIntArgMethod(classes, name)
    }

    private fun findInputDeviceMethod(name: String): Method?
    {
        return findNoArgMethod(InputDevice::class.java, name)
    }

    private fun findOneIntArgMethod(classes: Iterable<Class<*>>, name: String): Method?
    {
        for(clazz in classes)
        {
            var current: Class<*>? = clazz
            while(current != null)
            {
                val match = declaredMethods(current).firstOrNull { method ->
                    method.name == name &&
                        method.parameterTypes.size == 1 &&
                        (method.parameterTypes[0] == Int::class.javaPrimitiveType || method.parameterTypes[0] == Int::class.javaObjectType)
                }
                if(match != null)
                    return match.apply { isAccessible = true }
                current = current.superclass
            }
        }
        return null
    }

    private fun findNoArgMethod(clazz: Class<*>, name: String): Method?
    {
        var current: Class<*>? = clazz
        while(current != null)
        {
            val match = declaredMethods(current).firstOrNull { method ->
                method.name == name && method.parameterTypes.isEmpty()
            }
            if(match != null)
                return match.apply { isAccessible = true }
            current = current.superclass
        }
        return null
    }

    private fun declaredMethods(clazz: Class<*>): List<Method>
    {
        return runCatching {
            HiddenApiBypass.getDeclaredMethods(clazz).filterIsInstance<Method>()
        }.getOrElse {
            runCatching { clazz.declaredMethods.toList() }.getOrDefault(emptyList())
        }
    }

    private fun readEnabled(device: InputDevice?): String
    {
        if(device == null)
            return "missing"
        val method = findInputDeviceMethod("isEnabled") ?: return "unknown"
        return runCatching {
            (method.invoke(device) as? Boolean)?.let { if(it) "yes" else "no" } ?: "unknown"
        }.getOrElse { "error:${it.rootMessage()}" }
    }

    private fun methodStatus(method: Method?): String =
        method?.let { "${it.declaringClass.simpleName}.${it.name}" } ?: "no"

    private fun hex16(value: Int): String =
        String.format(Locale.US, "0x%04X", value and 0xFFFF)

    private fun Throwable.rootMessage(): String
    {
        val root = if(this is InvocationTargetException && targetException != null) targetException else this
        return "${root.javaClass.simpleName}: ${root.message ?: "no message"}"
    }
}
