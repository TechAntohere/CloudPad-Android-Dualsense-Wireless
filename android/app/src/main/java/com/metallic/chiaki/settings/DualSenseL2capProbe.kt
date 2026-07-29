// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Constructor

/**
 * Feasibility probe: can this app become the DualSense's HID host itself?
 *
 * ## Why
 *
 * The controller's microphone is streamed inside ordinary input reports: report
 * id 0x31, 78 bytes, with byte[2] & 0x0F == 0x02 marking an audio packet
 * carrying a 71-byte mono Opus frame at offset 4 (control packets use 0x01).
 * The kernel's hid-playstation driver validates only id, size and CRC32 -- it
 * never inspects that nibble -- so audio packets are parsed as controller state
 * and the Opus bytes are discarded inside the kernel, before any userspace API
 * can see them. Input-event containment cannot recover them: by the time an app
 * sees a KeyEvent the payload is gone, and /dev/hidraw0 is system:system under
 * SELinux enforcing.
 *
 * The remaining route is to stop the kernel owning the connection: drop the HID
 * profile, then open the HID L2CAP channels ourselves (PSM 0x11 control, 0x13
 * interrupt). This is what l2cap_proxy does on Linux for PS3/PS4 pads.
 *
 * ## The single question
 *
 * Will Android's native stack let an app uid open a **classic BR/EDR** L2CAP
 * channel on a **reserved** PSM? HiddenApiBypass reaches the hidden
 * [BluetoothSocket] constructor, but cannot influence Fluoride's own validation,
 * which is native. No root is involved either way.
 *
 * ## Safety
 *
 * Running this disconnects the pad from Android's HID host, so it stops being a
 * system controller until [stop]. [stop] always closes sockets, restores the
 * HID connection policy to ALLOWED and asks the profile to reconnect, so a
 * failed probe cannot leave the controller stranded. If the pad still misbehaves
 * afterwards, toggling Bluetooth clears the stack's session state.
 */
class DualSenseL2capProbe(
	private val appContext: Context,
	private val workerHandler: Handler,
	private val onUpdate: (String) -> Unit
)
{
	companion object
	{
		private const val TAG = "DSL2capProbe"

		/** Bluetooth-assigned HID channels. */
		private const val PSM_HID_CONTROL = 0x11
		private const val PSM_HID_INTERRUPT = 0x13

		/** BluetoothSocket.TYPE_L2CAP (classic). TYPE_L2CAP_LE = 4 is what the
		 *  public createInsecureL2capChannel() uses, and is LE-only. */
		private const val SOCKET_TYPE_L2CAP = 3

		private const val HID_HOST_PROFILE = 4
		private const val CONNECTION_POLICY_ALLOWED = 100

		private const val DS_INPUT_REPORT_BT = 0x31
		private const val DS_INPUT_REPORT_BT_SIZE = 78
		private const val PAYLOAD_TYPE_MASK = 0x0F
		private const val PAYLOAD_TYPE_CONTROL = 0x01
		private const val PAYLOAD_TYPE_AUDIO = 0x02
		private const val MIC_OPUS_OFFSET = 4
		private const val MIC_OPUS_SIZE = 71
	}

	@Volatile private var running = false
	private var controlSocket: BluetoothSocket? = null
	private var interruptSocket: BluetoothSocket? = null
	private var readThread: Thread? = null

	/** Remembered so [stop] can hand the pad back to Android. */
	private var hidProxy: BluetoothProfile? = null
	private var targetDevice: BluetoothDevice? = null

	private val log = StringBuilder()
	private var totalReports = 0L
	private var controlReports = 0L
	private var audioReports = 0L
	private var otherReports = 0L

	val isRunning: Boolean get() = running

	fun start()
	{
		if(running)
			return
		running = true
		log.setLength(0)
		totalReports = 0; controlReports = 0; audioReports = 0; otherReports = 0
		workerHandler.post { runProbe() }
	}

	fun stop()
	{
		running = false
		workerHandler.post {
			closeSockets()
			restoreController()
			publish()
		}
	}

	private fun runProbe()
	{
		append("=== DualSense L2CAP host probe ===")

		if(!initHiddenApi())
			return

		val adapter = BluetoothAdapter.getDefaultAdapter()
		if(adapter == null || !adapter.isEnabled)
		{
			append("FAIL: Bluetooth adapter unavailable or disabled")
			publish()
			return
		}

		val proxy = acquireHidHostProxy(adapter).also { hidProxy = it }

		val device = findDualSense(adapter, proxy)
		if(device == null)
		{
			append("FAIL: no connected DualSense found. Press the PS button first.")
			publish()
			return
		}
		targetDevice = device
		append("Target: ${safeName(device)} [${device.address}]")

		if(proxy == null)
		{
			append("FAIL: no HID host proxy; cannot release the channels")
			publish()
			return
		}

		// Step 1 -- make the kernel let go, else it still owns both PSMs.
		if(!disconnectHidHost(proxy, device))
		{
			append("")
			append("ABORT: could not release the HID host, so an L2CAP failure here")
			append("would be meaningless. Not attempting.")
			restoreController()
			publish()
			return
		}

		// Step 2 -- the actual question.
		val interrupt = openL2cap(device, PSM_HID_INTERRUPT, "interrupt")
		if(interrupt == null)
		{
			append("")
			append("VERDICT: classic L2CAP on a reserved PSM is NOT available to an app.")
			append("BT mic is not reachable this way. Restoring controller...")
			restoreController()
			publish()
			return
		}
		interruptSocket = interrupt

		controlSocket = openL2cap(device, PSM_HID_CONTROL, "control")

		append("")
		append("VERDICT: interrupt channel OPEN -- reading raw reports...")
		publish()
		startReading(interrupt)
	}

	private fun initHiddenApi(): Boolean
	{
		val ok = runCatching {
			HiddenApiBypass.addHiddenApiExemptions(
				"Landroid/bluetooth/BluetoothSocket;",
				"Landroid/bluetooth/BluetoothDevice;",
				"Landroid/bluetooth/BluetoothAdapter;",
				"Landroid/bluetooth/BluetoothHidHost;",
			)
		}.getOrElse {
			append("FAIL: HiddenApiBypass init: ${it.message}")
			publish()
			false
		}
		if(ok)
			append("HiddenApiBypass ready")
		return ok
	}

	/**
	 * Picks the pad that is actually connected. Several may be bonded; choosing
	 * a bonded-but-absent one yields "ACL connection failed", which looks exactly
	 * like the stack refusing the PSM but means nothing.
	 */
	@SuppressLint("MissingPermission")
	private fun findDualSense(adapter: BluetoothAdapter, proxy: BluetoothProfile?): BluetoothDevice?
	{
		val connected = runCatching { proxy?.connectedDevices }.getOrNull().orEmpty()
		connected.forEach { append("  HID-connected: ${safeName(it)} [${it.address}]") }

		connected.firstOrNull { safeName(it).contains("DualSense", ignoreCase = true) }
			?.let { return it }

		append("  (no DualSense reported connected by the HID profile)")
		return null
	}

	@SuppressLint("MissingPermission")
	private fun acquireHidHostProxy(adapter: BluetoothAdapter): BluetoothProfile?
	{
		val latch = Object()
		var result: BluetoothProfile? = null
		val listener = object: BluetoothProfile.ServiceListener
		{
			override fun onServiceConnected(profile: Int, p: BluetoothProfile)
			{
				synchronized(latch) { result = p; latch.notifyAll() }
			}
			override fun onServiceDisconnected(profile: Int) {}
		}

		val requested = runCatching {
			adapter.getProfileProxy(appContext, listener, HID_HOST_PROFILE)
		}.getOrDefault(false)
		if(!requested)
		{
			append("WARN: getProfileProxy(HID_HOST) refused")
			return null
		}
		synchronized(latch) {
			if(result == null)
				runCatching { latch.wait(2500) }
		}
		if(result == null)
			append("WARN: HID host proxy not ready in 2.5s")
		return result
	}

	/** @return true only if the pad genuinely left the HID host's connected list. */
	@SuppressLint("MissingPermission")
	private fun disconnectHidHost(proxy: BluetoothProfile, device: BluetoothDevice): Boolean
	{
		val classes = linkedSetOf<Class<*>>(proxy.javaClass)
		runCatching { Class.forName("android.bluetooth.BluetoothHidHost") }.getOrNull()?.let(classes::add)

		for(clazz in classes)
		{
			val attempt = runCatching {
				val m = clazz.getMethod("disconnect", BluetoothDevice::class.java)
				m.isAccessible = true
				m.invoke(proxy, device)
			}
			if(attempt.isSuccess)
			{
				append("HID host disconnect via ${clazz.simpleName}: ${attempt.getOrNull()}")
				runCatching { Thread.sleep(1500) }
				val still = runCatching { proxy.connectedDevices }.getOrNull().orEmpty()
					.any { it.address == device.address }
				if(still)
				{
					append("WARN: still listed as HID-connected after disconnect")
					return false
				}
				append("Confirmed: no longer HID-connected")
				return true
			}
			val t = attempt.exceptionOrNull()
			val cause = t?.cause
			append("  disconnect via ${clazz.simpleName} failed: " +
				"${cause?.javaClass?.simpleName ?: t?.javaClass?.simpleName}: ${cause?.message ?: t?.message ?: "no detail"}")
		}
		return false
	}

	/**
	 * Hands the pad back: policy to ALLOWED, then ask the profile to reconnect.
	 * Always safe to call, and called on every exit path.
	 */
	@SuppressLint("MissingPermission")
	private fun restoreController()
	{
		val proxy = hidProxy
		val device = targetDevice
		if(proxy == null || device == null)
		{
			append("Nothing to restore.")
			return
		}

		// disconnect() sets connectionPolicy=FORBIDDEN on some builds; undo that
		// first or the reconnect below is refused.
		val policy = runCatching {
			val m = proxy.javaClass.getMethod(
				"setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType
			)
			m.isAccessible = true
			m.invoke(proxy, device, CONNECTION_POLICY_ALLOWED)
		}
		append(if(policy.isSuccess) "Connection policy restored to ALLOWED"
		       else "  (setConnectionPolicy unavailable: ${policy.exceptionOrNull()?.cause?.message ?: "n/a"})")

		val reconnect = runCatching {
			val m = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
			m.isAccessible = true
			m.invoke(proxy, device)
		}
		append(if(reconnect.isSuccess) "Reconnect requested: ${reconnect.getOrNull()}"
		       else "  (connect unavailable: ${reconnect.exceptionOrNull()?.cause?.message ?: "n/a"})")
		append("If the pad stays down, press PS, or toggle Bluetooth off/on.")
	}

	@SuppressLint("MissingPermission")
	private fun openL2cap(device: BluetoothDevice, psm: Int, label: String): BluetoothSocket?
	{
		append("")
		append("Opening classic L2CAP $label channel, PSM 0x${psm.toString(16)}...")

		val ctors: Array<Constructor<*>> = runCatching {
			BluetoothSocket::class.java.declaredConstructors
		}.getOrElse {
			append("  FAIL: cannot enumerate BluetoothSocket constructors: ${it.message}")
			return null
		}
		append("  ${ctors.size} constructor(s) visible")

		for(ctor in ctors)
		{
			val types = ctor.parameterTypes
			if(types.size < 6) continue
			if(types[0] != Int::class.javaPrimitiveType) continue
			if(!types.any { it == BluetoothDevice::class.java }) continue

			val args = arrayOfNulls<Any?>(types.size)
			var portAssigned = false
			for(i in types.indices)
			{
				args[i] = when
				{
					types[i] == BluetoothDevice::class.java -> device
					types[i] == Boolean::class.javaPrimitiveType -> false
					types[i] == Int::class.javaPrimitiveType -> when
					{
						i == 0 -> SOCKET_TYPE_L2CAP
						i == 1 -> -1               // fd: none pre-made
						!portAssigned -> { portAssigned = true; psm }
						else -> 0
					}
					else -> null
				}
			}

			val built = runCatching {
				ctor.isAccessible = true
				ctor.newInstance(*args) as BluetoothSocket
			}
			if(built.isFailure)
			{
				val t = built.exceptionOrNull()
				append("  ctor(${types.joinToString(",") { p -> p.simpleName }}) -> ${t?.cause?.message ?: t?.message}")
				continue
			}
			val socket = built.getOrThrow()

			val connected = runCatching { socket.connect() }
			if(connected.isSuccess)
			{
				append("  SUCCESS: $label channel connected")
				return socket
			}
			append("  connect() failed: ${connected.exceptionOrNull()?.message}")
			runCatching { socket.close() }
		}

		append("  FAIL: no constructor produced a connected $label channel")
		return null
	}

	private fun startReading(socket: BluetoothSocket)
	{
		readThread = Thread {
			val buf = ByteArray(512)
			val input = runCatching { socket.inputStream }.getOrNull()
			if(input == null)
			{
				append("FAIL: no input stream")
				publish()
				return@Thread
			}
			while(running)
			{
				val read = runCatching { input.read(buf) }
				if(read.isFailure)
				{
					if(running) append("read error: ${read.exceptionOrNull()?.message}")
					break
				}
				val n = read.getOrDefault(-1)
				if(n <= 0)
					break
				totalReports++

				val reportId = buf[0].toInt() and 0xFF
				if(reportId == DS_INPUT_REPORT_BT && n >= 3)
				{
					when(buf[2].toInt() and PAYLOAD_TYPE_MASK)
					{
						PAYLOAD_TYPE_CONTROL -> controlReports++
						PAYLOAD_TYPE_AUDIO -> {
							audioReports++
							if(audioReports <= 3L)
							{
								val end = minOf(MIC_OPUS_OFFSET + MIC_OPUS_SIZE, n)
								val hex = buf.copyOfRange(MIC_OPUS_OFFSET, end)
									.take(16).joinToString(" ") { b -> "%02x".format(b) }
								append("MIC PACKET #$audioReports size=$n opus[0..15]=$hex")
							}
						}
						else -> otherReports++
					}
				}
				else otherReports++

				if(totalReports <= 5L || totalReports % 100L == 0L)
				{
					append("reports=$totalReports control=$controlReports audio=$audioReports other=$otherReports (size=$n, expect $DS_INPUT_REPORT_BT_SIZE)")
					publish()
				}
			}
			append("Reader stopped. total=$totalReports control=$controlReports audio=$audioReports other=$otherReports")
			publish()
		}.apply { isDaemon = true; start() }
	}

	private fun closeSockets()
	{
		runCatching { interruptSocket?.close() }
		runCatching { controlSocket?.close() }
		interruptSocket = null
		controlSocket = null
		readThread = null
	}

	@SuppressLint("MissingPermission")
	private fun safeName(device: BluetoothDevice): String =
		runCatching { device.name }.getOrNull() ?: "(unnamed)"

	private fun append(line: String)
	{
		Log.i(TAG, line)
		synchronized(log) {
			log.append(line).append('\n')
			if(log.length > 8000)
				log.delete(0, log.length - 8000)
		}
	}

	private fun publish() = onUpdate(synchronized(log) { log.toString() })
}
