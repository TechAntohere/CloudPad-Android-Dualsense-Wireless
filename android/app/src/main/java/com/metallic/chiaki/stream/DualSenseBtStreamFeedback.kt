// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.metallic.chiaki.lib.TriggerEffectsEvent
import com.metallic.chiaki.settings.DualSenseBtAudioHapticsBuilder
import com.metallic.chiaki.settings.DualSenseBtHidBridge
import com.metallic.chiaki.settings.DualSenseBtReportBuilder
import com.metallic.chiaki.settings.DualSenseBtSpeakerAudio
import com.metallic.chiaki.settings.DualSenseInfoParser
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.stream.AudioOutputRoute
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.PI

class DualSenseBtStreamFeedback(context: Context)
{
    companion object
    {
        /** Singleton owned by ControllerBtForegroundService — keeps BT alive when app is backgrounded. */
        @Volatile private var _shared: DualSenseBtStreamFeedback? = null
        val shared: DualSenseBtStreamFeedback? get() = _shared
        fun acquireShared(context: android.content.Context): DualSenseBtStreamFeedback =
            _shared ?: DualSenseBtStreamFeedback(context.applicationContext).also { _shared = it }
        fun releaseShared() { _shared = null }

        private const val PCM16_FRAME_BYTES = 4
        private const val BT_PACKET_SAMPLE_BYTES = 64
        private const val BT_HAPTICS_OUTPUT_FRAMES = BT_PACKET_SAMPLE_BYTES / 2
        // Chiaki's live haptics sink delivers 3 kHz stereo PCM16 blocks. A Bluetooth
        // haptics report carries 32 stereo actuator samples and is sent every 10.667 ms:
        // 32 / 0.010667s = 3000 Hz. Consume 32 source frames per report so the source
        // ring and controller output ring run at the same rate. The old 30->32 per
        // packet stretch consumed only 2812.5 source frames/sec and slowly built stale
        // backlog before dropping packets.
        private const val NATIVE_HAPTICS_BLOCK_FRAMES_STREAM = BT_HAPTICS_OUTPUT_FRAMES
        private const val NATIVE_HAPTICS_BLOCK_FRAMES_TESTER = 32
        private const val NATIVE_HAPTICS_BLOCK_BYTES_STREAM = NATIVE_HAPTICS_BLOCK_FRAMES_STREAM * PCM16_FRAME_BYTES
        private const val REMOTE_PLAY_HAPTICS_FRAME_BYTES = 30 * PCM16_FRAME_BYTES
        private const val NATIVE_HAPTICS_CALLBACK_FRAMES_STREAM = 90
        private const val NATIVE_HAPTICS_CALLBACK_BYTES_STREAM = NATIVE_HAPTICS_CALLBACK_FRAMES_STREAM * PCM16_FRAME_BYTES
        // Larger source staging reduces underrun risk when callbacks arrive in bursts.
        // This keeps a few callback blocks buffered before the packetizer drains them
        // into the output ring.
        private const val NATIVE_HAPTICS_STAGING_CAPACITY_BYTES = 7680
        // keep controller data inside live packets so triggers and lightbar stay in sync.
        private const val USE_COMBINED_BT_HAPTICS = true
        private const val USE_DIRECT_BT_HAPTICS_REPORT = false
        private const val SEND_DIRECT_36_WAKE_PACKET_ON_ARM = true
        private const val COMBINED_ROUTE_SETTLE_MS = 80L
        private const val COMBINED_ROUTE_LED_SETTLE_MS = 100L
        private const val VISUAL_WARMUP_MS = 0L
        private const val HAPTICS_PRIME_SETTLE_MS = 60L
        private const val HAPTICS_REPRIME_COOLDOWN_MS = 80L
        private const val WRITER_JOIN_TIMEOUT_MS = 300L
        private const val FAST_STATE_REPORT_MIN_INTERVAL_MS = 16L
        private const val FAST_STATE_REPORT_MIN_INTERVAL_DURING_HAPTICS_MS = 32L
        private const val SLOW_STATE_REPORT_MIN_INTERVAL_DURING_HAPTICS_MS = 96L
        private const val HAPTICS_KEEPALIVE_GRACE_PERIODS = 10L
        private const val HAPTICS_DRAIN_RESET_GRACE_PERIODS = 24L
        private const val HAPTICS_QUEUE_PACKET_LIMIT = 32
        // App-side lead only hides source/packetizer jitter; it cannot fix Bluetooth HID
        // delivery jitter after sendData accepts the report. Six packets is ~64 ms: enough
        // to avoid a thin app queue without adding the 12-packet test's obvious delay.
        private const val HAPTICS_OUTPUT_LEAD_TARGET_PACKETS = 6
        private const val HAPTICS_PACKETIZER_MAX_PACKETS_PER_TICK = 6
        private const val HAPTICS_PARTIAL_FLUSH_IDLE_MS = 40L
        private const val HAPTICS_STATE_GUARD_NS = 1_500_000L
        private const val HAPTICS_SEND_FAILURE_BASE_BACKOFF_NS = 500_000L
        private const val HAPTICS_SEND_FAILURE_MAX_BACKOFF_NS = 3_000_000L
        private const val HAPTICS_CONCEALMENT_MAX_PERIODS = 2
        private const val HAPTICS_CONCEALMENT_MAX_SOURCE_AGE_NS = 40_000_000L
        private const val SUSPEND_JACK_POLLER_DURING_HAPTICS = false
        // keep the 0x39 catch-up carrier off until we have a capture.
        private const val ENABLE_TWO_FRAME_HAPTICS_CATCHUP = false
        private const val HAPTICS_TWO_FRAME_CATCHUP_LATE_NS = 1_500_000L
        private const val NATIVE_STREAM_HAPTICS_GAIN = 1.0
        private const val SINC_RESAMPLER_RADIUS = 8
        // fixed cadence: advance by one 10 ms period and never reset to now.
        private const val HAPTICS_PRODUCER_INTERVAL_NS = 10_000_000L  // 10 ms keeps producer and consumer from phase-locking.
        private const val LIVE_STREAM_HAPTICS_PERIOD_NS = DualSenseBtAudioHapticsBuilder.REPORT_PERIOD_NANOS
        private const val LIGHTBAR_ROUTE_MIN_INTERVAL_MS = 80L
        // clear triggers when output ownership changes and the last state is stale.
        private const val STALE_TRIGGER_CLEAR_ON_STATUS_CHANGE_MS = 1_250L
        private const val PLAYER_LED_BRIGHTNESS = 0x01
        private const val DEFAULT_PLAYER_LED_MASK = 0x04
        private const val DUALSENSE_EFFECT_OFF = 0
        private const val DUALSENSE_EFFECT_STRONG = 1
        private const val DUALSENSE_EFFECT_MEDIUM = 2
        private const val DUALSENSE_EFFECT_WEAK = 3
        private const val STATE_REPORT_TOGGLE_INDEX = 40
        private const val STATE_REPORT_CRC_OFFSET = 74
        private const val STATE_REPORT_CRC_LENGTH = 4
        private const val STATE_REPORT_CRC_OFFSET_32 = 138
    }

    private data class PendingStateSnapshot(
        val snapshot: ControllerOutputSnapshot,
        val semantic: ByteArray,
        val kind: Kind,
    ) {
        enum class Kind {
            FAST,
            SLOW,
        }
    }

    private data class MutableControllerOutputState(
        var playerLedBrightness: Int = PLAYER_LED_BRIGHTNESS,
        var playerLedMask: Int = DEFAULT_PLAYER_LED_MASK,
        var triggerSoftnessLevel: Int = 0x00,
        var softRumbleReduce: Int = 0x00,
        var lightbarRed: Int = 0x00,
        var lightbarGreen: Int = 0x00,
        var lightbarBlue: Int = 0x00,
        var triggerStateReport: ByteArray? = null,
        var leftTriggerControllerData: ByteArray? = null,
        var rightTriggerControllerData: ByteArray? = null,
    ) {
        fun persistentStateSnapshot(): DualSenseBtReportBuilder.PersistentOutputState =
            DualSenseBtReportBuilder.PersistentOutputState(
                playerLedBrightness = playerLedBrightness,
                playerLedMask = playerLedMask,
                triggerSoftnessLevel = triggerSoftnessLevel,
                softRumbleReduce = softRumbleReduce,
                lightbarRed = lightbarRed,
                lightbarGreen = lightbarGreen,
                lightbarBlue = lightbarBlue,
            )

        fun snapshot(speakerRegularBtOutput: Boolean): ControllerOutputSnapshot =
            ControllerOutputSnapshot(
                persistentState = persistentStateSnapshot(),
                triggerStateReport = triggerStateReport?.copyOf(),
                leftTriggerControllerData = leftTriggerControllerData?.copyOf(),
                rightTriggerControllerData = rightTriggerControllerData?.copyOf(),
                speakerRegularBtOutput = speakerRegularBtOutput,
            )
    }

    private data class ControllerOutputSnapshot(
        val persistentState: DualSenseBtReportBuilder.PersistentOutputState,
        val triggerStateReport: ByteArray?,
        val leftTriggerControllerData: ByteArray?,
        val rightTriggerControllerData: ByteArray?,
        val speakerRegularBtOutput: Boolean,
    )

    private data class HapticsCadenceState(
        val anchorNs: Long,
        val packetsSent: Long,
        val nextDeadlineNs: Long,
    )

    data class BtHapticsDebugSnapshot(
        val queuePackets: Int,
        val stagingBytes: Int,
        val framesAvailableGeneration: Long,
        val cadenceResetCount: Long,
        val keepaliveSendCount: Long,
        val deadlineCatchupCount: Long,
        val stagingOverwriteBytes: Long,
        val packetOverwriteCount: Long,
        val deferredStateTrafficCount: Long,
        val combinedRouteArmed: Boolean,
        val pendingCombinedStateFlush: Boolean,
        // Packetizer thread telemetry (Patch 2)
        val packetizerSkippedCycles: Long,
        val packetizerProducedPackets: Long,
        val sourcePacketizerProducedPackets: Long = 0L,
        val sourcePacketizerLastProducedPackets: Long = 0L,
        val sourcePacketizerQueueFullCount: Long = 0L,
        val hapticsSendAttempts: Long = 0L,
        val hapticsSendSuccesses: Long = 0L,
        val hapticsSendFailures: Long = 0L,
        val hapticsSendBackoffRemainingUs: Long = 0L,
        val hapticsLastSendDurationUs: Long = 0L,
        val hapticsMaxSendDurationUs: Long = 0L,
        val hapticsSlowSendOver8MsCount: Long = 0L,
        val hapticsSlowSendOver12MsCount: Long = 0L,
        val hapticsSlowSendOver20MsCount: Long = 0L,
        val hapticsSlowSendOver50MsCount: Long = 0L,
        val hapticsLastSuccessAgeMs: Long = 0L,
        val hapticsLastFailureAgeMs: Long = 0L,
        val hapticsFastPathFallbackCount: Long = 0L,
        val hapticsFastPathFallbackSuccessCount: Long = 0L,
        val hapticsLastSendIntervalUs: Long = 0L,
        val hapticsMaxSendIntervalUs: Long = 0L,
        val hapticsSendIntervalOver15MsCount: Long = 0L,
        val hapticsSendIntervalOver20MsCount: Long = 0L,
        val hapticsSendIntervalOver30MsCount: Long = 0L,
        val hapticsSendIntervalOver50MsCount: Long = 0L,
        val hapticsLastLargeSendIntervalUs: Long = 0L,
        val hapticsLastLargeSendIntervalAgeMs: Long = 0L,
        val hapticsLastDeadlineMissUs: Long = 0L,
        val hapticsMaxDeadlineMissUs: Long = 0L,
        val hapticsDeadlineMissCount: Long = 0L,
        val hapticsImmediateCatchupCount: Long = 0L,
        val speakerRouteArms: Long = 0L,
        val speakerRouteArmFailures: Long = 0L,
        val speakerUnderrunReports: Long = 0L,
        val speakerSendAttempts: Long = 0L,
        val speakerSendFailures: Long = 0L,
        val speakerLastSendDurationUs: Long = 0L,
        val speakerMaxSendDurationUs: Long = 0L,
        val nowMs: Long = 0L,
        val hapticsFramesReceived: Long = 0L,
        val lastHapticsFrameBytes: Int = 0,
        val lastHapticsFrameAgeMs: Long = 0L,
        val lastHapticsFrameDeltaUs: Long = 0L,
        val maxHapticsFrameDeltaUs: Long = 0L,
        val hapticsSourceGapOver20MsCount: Long = 0L,
        val hapticsSourceGapOver30MsCount: Long = 0L,
        val hapticsSourceGapOver50MsCount: Long = 0L,
        val hapticsLastLargeSourceGapUs: Long = 0L,
        val hapticsLastLargeSourceGapAgeMs: Long = 0L,
        val hapticsNativeTimestampFrames: Long = 0L,
        val hapticsNativeToKotlinLastDelayUs: Long = 0L,
        val hapticsNativeToKotlinMaxDelayUs: Long = 0L,
        val hapticsNativeToKotlinDelayOver1MsCount: Long = 0L,
        val hapticsNativeToKotlinDelayOver2MsCount: Long = 0L,
        val hapticsNativeToKotlinDelayOver5MsCount: Long = 0L,
        val hapticsNativeToKotlinDelayOver10MsCount: Long = 0L,
        val hapticsNativeToKotlinDelayOver20MsCount: Long = 0L,
        val hapticsNativeToKotlinLastLargeDelayUs: Long = 0L,
        val hapticsNativeToKotlinLastLargeDelayAgeMs: Long = 0L,
        val unexpectedHapticsFrameBytesCount: Long = 0L,
        val lastStagingOverwriteBytes: Long = 0L,
        val lastStagingOverwriteAgeMs: Long = 0L,
        val partialFlushCount: Long = 0L,
        val packetizerLastTickAgeMs: Long = 0L,
        val packetizerLastTickLateUs: Long = 0L,
        val packetizerMaxTickLateUs: Long = 0L,
        val packetizerLateTickCount: Long = 0L,
        val packetizerLastProducedPackets: Long = 0L,
        val hapticsTransportMode: String = "none",
        val hidSelectedDeviceName: String? = null,
        val hidSelectedDeviceAddress: String? = null,
        val hidConnectedDeviceCount: Int = 0,
        val hidCachedTransportMode: String? = null,
        val hidSendDataRawReady: Boolean = false,
        val hidSendDataHexReady: Boolean = false,
        val hidSetReportRawReady: Boolean = false,
        val hidSetReportHexReady: Boolean = false,
        val hidGetReportReady: Boolean = false,
        val hidSendDataRawSignature: String? = null,
        val hidSendDataHexSignature: String? = null,
        val hidSetReportRawSignature: String? = null,
        val hidSetReportHexSignature: String? = null,
        val hidGetReportSignature: String? = null,
    )

    private val appContext = context.applicationContext
    private val writerLock = Object()
    private val sourcePcmLock = Object()
    private val outputState = MutableControllerOutputState()
    private val hapticsOutputFrameRing = ByteArray(HAPTICS_QUEUE_PACKET_LIMIT * BT_PACKET_SAMPLE_BYTES)
    private val hapticsPreBuffer = ByteArray(NATIVE_HAPTICS_BLOCK_BYTES_STREAM)
    // Dedicated scratch for inline packetization in handleHapticsFrame (separate from
    // packetizerLoop's pre-buffer to avoid cross-thread data races).
    private val inlinePacketizePreBuffer = ByteArray(NATIVE_HAPTICS_BLOCK_BYTES_STREAM)
    private val inlinePacketizeScratch = ByteArray(BT_PACKET_SAMPLE_BYTES)
    private val lastRealHapticsPacket = ByteArray(BT_PACKET_SAMPLE_BYTES)
    private var nativeHapticsStaging = ByteArray(NATIVE_HAPTICS_STAGING_CAPACITY_BYTES)
    // nativeHapticsPacketScratch is a local in packetizerLoop — NOT a shared field.
    private var hapticsOutputReadFrame = 0
    private var hapticsOutputFrameCount = 0
    private var nativeHapticsStagingHead = 0
    private var nativeHapticsStagingCount = 0
    @Volatile private var nativeHapticsStagingCountVolatile = 0
    @Volatile private var lastHapticsSourceNs = 0L
    private var hapticsFramesAvailableGeneration = 0L
    private var bridge: DualSenseBtHidBridge? = null
    private var writerThread: Thread? = null
    @Volatile private var writerStopRequested = false
    // dedicated packetizer thread, decoupled from callback timing
    @Volatile private var packetizerRunning = false
    private var packetizerThread: Thread? = null
    private var packetizerSkippedCycles = 0L
    private var packetizerProducedPackets = 0L
    private var hapticsSequence = 0
    private var lastPrimeTriggerStateReport: ByteArray? = null
    private var lastSentStateSemantic: ByteArray? = null
    private var lastStateTransportReport: ByteArray? = null
    private var pendingFastState: PendingStateSnapshot? = null
    private var pendingSlowState: PendingStateSnapshot? = null
    private var lastFastStateReportMs = 0L
    private var lastSlowStateReportMs = 0L
    @Volatile private var lastTriggerEffectsUpdateMs = 0L
    private var primeBeforeNextHaptics = false
    private var lastHapticsPrimeNs = 0L
    private var lastSuccessfulHapticsSendNs = 0L
    private var lastRealHapticsPacketNs = 0L
    private var hasLastRealHapticsPacket = false
    private var hapticsConcealmentPeriodsSent = 0
    private var combinedHapticsRouteArmed = false
    @Volatile private var currentHapticIntensity = DUALSENSE_EFFECT_STRONG
    @Volatile private var currentTriggerIntensity = DUALSENSE_EFFECT_STRONG
    private var pendingCombinedStateFlush = false
    private var pendingTriggerOffReport = false
    private var pendingRouteUpdate = false
    private var lastRouteUpdateMs = 0L
    private var lastAudioStateCarrierMs = 0L
    private var restartCombinedStreamPending = false
    @Volatile private var visualActivationStartedMs = 0L
    private var hapticsCadenceResetCount = 0L
    private var hapticsKeepaliveSendCount = 0L
    private var hapticsDeadlineCatchupCount = 0L
    private var hapticsSendBackoffUntilNs = 0L
    private var hapticsSendFailureCount = 0
    private var hapticsStagingOverwriteBytes = 0L
    private var lastHapticsStagingOverwriteBytes = 0L
    private var lastHapticsStagingOverwriteNs = 0L
    private var hapticsPacketOverwriteCount = 0L
    private var deferredStateTrafficCount = 0L
    private var hapticsSendAttempts = 0L
    private var hapticsSendSuccesses = 0L
    private var hapticsSendFailures = 0L
    private var hapticsLastSendDurationNs = 0L
    private var hapticsMaxSendDurationNs = 0L
    private var hapticsSlowSendOver8MsCount = 0L
    private var hapticsSlowSendOver12MsCount = 0L
    private var hapticsSlowSendOver20MsCount = 0L
    private var hapticsSlowSendOver50MsCount = 0L
    private var hapticsFastPathFallbackCount = 0L
    private var hapticsFastPathFallbackSuccessCount = 0L
    private var hapticsLastSendIntervalNs = 0L
    private var hapticsMaxSendIntervalNs = 0L
    private var hapticsSendIntervalOver15MsCount = 0L
    private var hapticsSendIntervalOver20MsCount = 0L
    private var hapticsSendIntervalOver30MsCount = 0L
    private var hapticsSendIntervalOver50MsCount = 0L
    private var hapticsLastLargeSendIntervalNs = 0L
    private var hapticsLastLargeSendIntervalTimestampNs = 0L
    private var lastFailedHapticsSendNs = 0L
    private var hapticsLastDeadlineMissNs = 0L
    private var hapticsMaxDeadlineMissNs = 0L
    private var hapticsDeadlineMissCount = 0L
    private var hapticsImmediateCatchupCount = 0L
    @Volatile private var hapticsFramesReceived = 0L
    @Volatile private var lastHapticsFrameBytes = 0
    @Volatile private var lastHapticsFrameNs = 0L
    @Volatile private var lastHapticsFrameDeltaNs = 0L
    @Volatile private var maxHapticsFrameDeltaNs = 0L
    @Volatile private var hapticsSourceGapOver20MsCount = 0L
    @Volatile private var hapticsSourceGapOver30MsCount = 0L
    @Volatile private var hapticsSourceGapOver50MsCount = 0L
    @Volatile private var hapticsLastLargeSourceGapNs = 0L
    @Volatile private var hapticsLastLargeSourceGapTimestampNs = 0L
    @Volatile private var hapticsNativeTimestampFrames = 0L
    @Volatile private var hapticsNativeToKotlinLastDelayNs = 0L
    @Volatile private var hapticsNativeToKotlinMaxDelayNs = 0L
    @Volatile private var hapticsNativeToKotlinDelayOver1MsCount = 0L
    @Volatile private var hapticsNativeToKotlinDelayOver2MsCount = 0L
    @Volatile private var hapticsNativeToKotlinDelayOver5MsCount = 0L
    @Volatile private var hapticsNativeToKotlinDelayOver10MsCount = 0L
    @Volatile private var hapticsNativeToKotlinDelayOver20MsCount = 0L
    @Volatile private var hapticsNativeToKotlinLastLargeDelayNs = 0L
    @Volatile private var hapticsNativeToKotlinLastLargeDelayTimestampNs = 0L
    @Volatile private var unexpectedHapticsFrameBytesCount = 0L
    private var partialFlushCount = 0L
    private var sourcePacketizerProducedPackets = 0L
    private var sourcePacketizerLastProducedPackets = 0L
    private var sourcePacketizerQueueFullCount = 0L
    private var packetizerLastTickNs = 0L
    private var packetizerLastTickLateNs = 0L
    private var packetizerMaxTickLateNs = 0L
    private var packetizerLateTickCount = 0L
    private var packetizerLastProducedPackets = 0L
    private var hapticsWakeLock: PowerManager.WakeLock? = null
    // Speaker preview (real 0x39 path) + live audio streaming
    private var pendingSpeakerPreview = false
    @Volatile private var speakerPreviewPackets: List<DualSenseBtSpeakerAudio.DualFramePacket>? = null
    private var speakerThread: Thread? = null
    @Volatile private var speakerThreadRunning = false

    /**
     * Count of isochronous slots skipped because a BT stall pushed the send
     * deadline into the past. Pair this against ControllerSpeakerBus's
     * overflowDrops / emptyPops when chasing audible stutters: whichever
     * counter tracks the dropouts identifies the cause (stall vs producer
     * clock drift). See TechAntohere/Senshi#1.
     */
    @Volatile var speakerDeadlineSkips = 0L
        private set
    private var jackPollerThread: Thread? = null
    @Volatile private var jackPollerRunning = false
    @Volatile var controllerJackStateCallback: ((Boolean) -> Unit)? = null

    /**
     * Last battery level read from the controller, 0..100, or null if unknown.
     * Populated by the jack poller from the same input report it already
     * requests -- reading this costs no additional Bluetooth traffic, but it is
     * only as fresh as the poll interval.
     */
    @Volatile var controllerBatteryPercent: Int? = null
        private set

    /** Raw power state string from the same report, e.g. "discharging". */
    @Volatile var controllerPowerState: String? = null
        private set

    fun onResume()
    {
        if(!isSupported() || !hasBluetoothPermission())
            return
        // Sync volume prefs and audio routing into the report builder before the first report goes out.
        val _prefs = Preferences(appContext)
        DualSenseBtReportBuilder.speakerVolumePercent = _prefs.controllerSpeakerVolumePercent
        DualSenseBtReportBuilder.headphoneVolumePercent = _prefs.controllerHeadphoneVolumePercent
        DualSenseBtReportBuilder.headphoneMode = _prefs.controllerHeadphoneOutput
        ControllerSystemAudioService.audioOutputRoute =
            if (_prefs.controllerHeadphoneOutput) AudioOutputRoute.HEADPHONE else AudioOutputRoute.SPEAKER
        // If already fully active, just re-sync volume prefs and return — don't reset state or restart threads
        if (speakerThreadRunning && bridge != null) {
            acquireHapticsWakeLock()
            getBridge().start()
            return
        }
        DualSenseBtReportBuilder.resetStreamingState()
        if(USE_COMBINED_BT_HAPTICS)
            DualSenseBtSpeakerAudio.resetCounters()
        acquireHapticsWakeLock()
        getBridge().start()
        synchronized(writerLock) {
            visualActivationStartedMs = SystemClock.uptimeMillis()
            outputState.triggerStateReport = null
            outputState.leftTriggerControllerData = null
            outputState.rightTriggerControllerData = null
            syncPersistentOutputStateLocked()
            lastPrimeTriggerStateReport = null
            lastTriggerEffectsUpdateMs = 0L
            // keep the regular output path until the bt audio route is armed.
            primeBeforeNextHaptics = false
            combinedHapticsRouteArmed = false
            pendingCombinedStateFlush = false
            pendingTriggerOffReport = false
            pendingRouteUpdate = false
            lastRouteUpdateMs = 0L
            lastAudioStateCarrierMs = 0L
            restartCombinedStreamPending = USE_COMBINED_BT_HAPTICS
            hapticsSendBackoffUntilNs = 0L
            hapticsSendFailureCount = 0
            lastStateTransportReport = null
            queueSlowStateLocked(force = true)
            ensureWriterStartedLocked()
            writerLock.notifyAll()
        }
        // Preview packets are now loaded lazily on the first feedPreviewTone()
        // call. This used to spawn a bare Thread on every single stream start to
        // decode a bundled demo clip that only the volume preview ever reads --
        // and with no exception handler on that thread, a missing asset took the
        // whole process down mid-stream.
        // Start live speaker streaming thread
        startSpeakerThread()
        startJackPoller()
    }

    fun onPause()
    {
        if(!ControllerSystemAudioService.isRunning) stopJackPoller()
        stopWriterThread()
        if(!isSupported() || !hasBluetoothPermission())
        {
            stopBtForegroundWork()
            return
        }

        synchronized(this) {
            bridge?.let { liveBridge ->
                val hapticsTransport = resolveHapticsTransport(liveBridge)
                val stateTransport = resolveStateTransport(liveBridge)
                liveBridge.sendOutputReport(
                    DualSenseBtAudioHapticsBuilder.buildSilenceReport(hapticsSequence and 0xFF),
                    streaming = true,
                    transportMode = hapticsTransport,
                )
                hapticsSequence = (hapticsSequence + 1) and 0xFF
                val clearState = synchronized(writerLock) { outputState.persistentStateSnapshot() }
                liveBridge.sendOutputReport(
                    DualSenseBtReportBuilder.buildClearTriggerReport(state = clearState),
                    streaming = true,
                    transportMode = stateTransport,
                )
            }
        }

        if (!ControllerSystemAudioService.isRunning) {
            stopSpeakerThread()
            ControllerSpeakerBus.flush()
            bridge?.close()
            bridge = null
        }
        if(!ControllerSystemAudioService.isRunning)
        {
        outputState.triggerStateReport = null
        outputState.leftTriggerControllerData = null
        outputState.rightTriggerControllerData = null
        lastPrimeTriggerStateReport = null
        lastTriggerEffectsUpdateMs = 0L
        lastSentStateSemantic = null
        pendingFastState = null
        pendingSlowState = null
        lastFastStateReportMs = 0L
        lastSlowStateReportMs = 0L
        primeBeforeNextHaptics = false
        lastHapticsPrimeNs = 0L
        lastSuccessfulHapticsSendNs = 0L
        resetHapticsConcealmentLocked()
        combinedHapticsRouteArmed = false
        pendingCombinedStateFlush = false
        pendingTriggerOffReport = false
        hapticsSequence = 0
        outputState.lightbarRed = 0
        outputState.lightbarGreen = 0
        outputState.lightbarBlue = 0
        pendingRouteUpdate = false
        lastRouteUpdateMs = 0L
        lastAudioStateCarrierMs = 0L
        restartCombinedStreamPending = false
        hapticsSendBackoffUntilNs = 0L
        hapticsSendFailureCount = 0
        lastStateTransportReport = null
        visualActivationStartedMs = 0L
        hapticsCadenceResetCount = 0L
        hapticsKeepaliveSendCount = 0L
        hapticsDeadlineCatchupCount = 0L
        hapticsStagingOverwriteBytes = 0L
        hapticsPacketOverwriteCount = 0L
        deferredStateTrafficCount = 0L
        packetizerSkippedCycles = 0L
        packetizerProducedPackets = 0L
        DualSenseBtReportBuilder.resetStreamingState()
        resetHapticsPackingState()
        stopBtForegroundWork()
        }
    }
    fun requestStreamRestart(clearBufferedHaptics: Boolean = true)
    {
        if(clearBufferedHaptics)
            resetHapticsPackingState()
        synchronized(writerLock) {
            if(hasTriggerStateLocked())
            {
                clearTriggerStateLocked()
                pendingTriggerOffReport = true
            }
            if(clearBufferedHaptics)
                clearQueuedHapticsQueueLocked()
            resetHapticsBufferLocked()
            combinedHapticsRouteArmed = false
            pendingCombinedStateFlush = false
            pendingRouteUpdate = false
            lastRouteUpdateMs = 0L
            lastAudioStateCarrierMs = 0L
            restartCombinedStreamPending = USE_COMBINED_BT_HAPTICS
            hapticsSendBackoffUntilNs = 0L
            hapticsSendFailureCount = 0
            lastStateTransportReport = null
            queueSlowStateLocked(force = true)
            ensureWriterStartedLocked()
            writerLock.notifyAll()
        }
    }

    fun prearmCombinedHapticsRoute(): Boolean
    {
        if(!isSupported() || !hasBluetoothPermission())
            return false
        if(!USE_COMBINED_BT_HAPTICS)
            return true

        acquireHapticsWakeLock()
        getBridge().start()
        synchronized(writerLock) {
            if(combinedHapticsRouteArmed)
                return true
            restartCombinedStreamPending = true
            if(pendingFastState == null && pendingSlowState == null)
                queueSlowStateLocked(force = true)
            ensureWriterStartedLocked()
            writerLock.notifyAll()
        }
        return true
    }

    fun updatePersistentLightbar(red: Int, green: Int, blue: Int, sendReport: Boolean = true)
    {
        synchronized(writerLock) {
            val changed = red != outputState.lightbarRed || green != outputState.lightbarGreen || blue != outputState.lightbarBlue
            if(!changed)
                return
            outputState.lightbarRed = red.coerceIn(0, 255)
            outputState.lightbarGreen = green.coerceIn(0, 255)
            outputState.lightbarBlue = blue.coerceIn(0, 255)
            if(sendReport)
            {
                queueLightbarFastStateLocked()
                ensureWriterStartedLocked()
                writerLock.notifyAll()
            }
        }
    }

    fun syncTesterVisualState(playerLedMask: Int, playerLedBrightness: Int, red: Int, green: Int, blue: Int)
    {
        synchronized(writerLock) {
            val clampedMask = playerLedMask.coerceIn(0, 0x3F)
            val clampedBrightness = playerLedBrightness.coerceIn(0, 0xFF)
            val clampedRed = red.coerceIn(0, 255)
            val clampedGreen = green.coerceIn(0, 255)
            val clampedBlue = blue.coerceIn(0, 255)
            val changed =
                outputState.playerLedMask != clampedMask ||
                outputState.playerLedBrightness != clampedBrightness ||
                outputState.lightbarRed != clampedRed ||
                outputState.lightbarGreen != clampedGreen ||
                outputState.lightbarBlue != clampedBlue
            if(!changed)
                return

            outputState.playerLedMask = clampedMask
            outputState.playerLedBrightness = clampedBrightness
            outputState.lightbarRed = clampedRed
            outputState.lightbarGreen = clampedGreen
            outputState.lightbarBlue = clampedBlue

            queueLightbarFastStateLocked()
            ensureWriterStartedLocked()
            writerLock.notifyAll()
        }
    }

    fun updatePlayerIndex(playerIndex: Int)
    {
        val mask = playerLedMaskForIndex(playerIndex)
        synchronized(writerLock) {
            if(mask == outputState.playerLedMask)
                return
            outputState.playerLedMask = mask
            outputState.playerLedBrightness = PLAYER_LED_BRIGHTNESS
            queueSlowStateLocked(force = true)
            ensureWriterStartedLocked()
            writerLock.notifyAll()
        }
    }

    fun updateHapticIntensity(intensity: Int)
    {
        val normalizedIntensity = normalizeDualSenseEffectIntensity(intensity)
        if(currentHapticIntensity == normalizedIntensity)
            return
        currentHapticIntensity = normalizedIntensity
        applyPersistentState(sendReport = true)
    }

    fun updateTriggerIntensity(intensity: Int)
    {
        val normalizedIntensity = normalizeDualSenseEffectIntensity(intensity)
        if(currentTriggerIntensity == normalizedIntensity)
            return
        currentTriggerIntensity = normalizedIntensity
        applyPersistentState(sendReport = true)
    }

    private fun applyPersistentState(sendReport: Boolean = true)
    {
        synchronized(writerLock) {
            syncPersistentOutputStateLocked()
            if(sendReport)
            {
                queueSlowStateLocked(force = false)
                ensureWriterStartedLocked()
                writerLock.notifyAll()
            }
        }
    }

    private fun normalizeDualSenseEffectIntensity(intensity: Int): Int = when(intensity)
    {
        DUALSENSE_EFFECT_OFF, DUALSENSE_EFFECT_WEAK, DUALSENSE_EFFECT_MEDIUM, DUALSENSE_EFFECT_STRONG -> intensity
        else -> DUALSENSE_EFFECT_STRONG
    }

    private fun triggerIntensityNibble(intensity: Int): Int = when(normalizeDualSenseEffectIntensity(intensity))
    {
        DUALSENSE_EFFECT_OFF -> 0x0F
        DUALSENSE_EFFECT_WEAK -> 0x09
        DUALSENSE_EFFECT_MEDIUM -> 0x06
        else -> 0x00
    }

    private fun hapticIntensityNibble(intensity: Int): Int = when(normalizeDualSenseEffectIntensity(intensity))
    {
        DUALSENSE_EFFECT_OFF -> 0x0F
        DUALSENSE_EFFECT_WEAK -> 0x03
        DUALSENSE_EFFECT_MEDIUM -> 0x02
        else -> 0x00
    }

    private fun playerLedMaskForIndex(playerIndex: Int): Int = when(playerIndex)
    {
        2 -> 0x0A
        3 -> 0x15
        4 -> 0x1B
        else -> DEFAULT_PLAYER_LED_MASK
    }

    fun handleTriggerEffects(event: TriggerEffectsEvent): Boolean
    {
        if(readyBridge() == null)
            return false

        synchronized(writerLock) {
            lastTriggerEffectsUpdateMs = SystemClock.uptimeMillis()
            val persistentState = outputState.persistentStateSnapshot()
            val stateReport = DualSenseBtReportBuilder.buildTriggerEffectsReport(
                state = persistentState,
                leftType = event.leftType,
                leftData = event.leftData,
                rightType = event.rightType,
                rightData = event.rightData,
            )
            val rightControllerData = packControllerDataTrigger(event.rightType, event.rightData)
            val leftControllerData = packControllerDataTrigger(event.leftType, event.leftData)
            if(triggerStateEquals(outputState.triggerStateReport, stateReport) &&
                triggerDataEquals(outputState.rightTriggerControllerData, rightControllerData) &&
                triggerDataEquals(outputState.leftTriggerControllerData, leftControllerData))
            {
                return true
            }

            outputState.triggerStateReport = stateReport
            outputState.rightTriggerControllerData = rightControllerData
            outputState.leftTriggerControllerData = leftControllerData
            if(combinedStreamOwnsStateLocked())
            {
                pendingCombinedStateFlush = true
                lastAudioStateCarrierMs = 0L  // force immediate carrier so triggers apply without waiting for next haptics packet
            }
            else
            {
                val snapshot = snapshotOutputStateLocked()
                pendingFastState = PendingStateSnapshot(
                    snapshot = snapshot,
                    semantic = semanticStateSnapshot(snapshot, PendingStateSnapshot.Kind.FAST),
                    kind = PendingStateSnapshot.Kind.FAST,
                )
            }
            ensureWriterStartedLocked()
            writerLock.notifyAll()
            return true
        }
    }

    fun handleClassicRumble(leftMotor: Int, rightMotor: Int): Boolean
    {
        val left = leftMotor.coerceIn(0, 255)
        val right = rightMotor.coerceIn(0, 255)
        if((left != 0 || right != 0) && isNativeHapticsActive())
            return false

        val liveBridge = readyBridge() ?: return false
        val transport = resolveStateTransport(liveBridge)
        val report = synchronized(writerLock) {
            DualSenseBtReportBuilder.buildClassicRumbleReport(
                state = outputState.persistentStateSnapshot(),
                leftMotor = left,
                rightMotor = right,
                triggerStateReport = outputState.triggerStateReport,
                ledReady = isVisualSteadyStateLocked(),
            )
        }

        val sent = liveBridge.sendOutputReportFast(report, transport) || liveBridge.sendOutputReport(
            report,
            streaming = true,
            transportMode = transport,
        ).success
        if(sent)
        {
            synchronized(writerLock) {
                markStateTransportReportSentLocked(report)
            }
        }
        return sent
    }

    fun expireStaleTriggerEffectsOnHostStatusChange(nowMs: Long = SystemClock.uptimeMillis()): Boolean
    {
        synchronized(writerLock) {
            if(!hasTriggerStateLocked())
                return false
            val lastUpdateMs = lastTriggerEffectsUpdateMs
            if(lastUpdateMs == 0L || nowMs - lastUpdateMs < STALE_TRIGGER_CLEAR_ON_STATUS_CHANGE_MS)
                return false
            if(isNativeHapticsActive())
                return false

            clearTriggerStateLocked()
            pendingTriggerOffReport = true
            ensureWriterStartedLocked()
            writerLock.notifyAll()
            return true
        }
    }

    fun clearTriggerEffects(sendReport: Boolean = true): Boolean
    {
        synchronized(writerLock) {
            if(!hasTriggerStateLocked())
                return false

            clearTriggerStateLocked()
            if(sendReport)
            {
                pendingTriggerOffReport = true
                ensureWriterStartedLocked()
                writerLock.notifyAll()
            }
            return true
        }
    }

    fun handleHapticsFrame(data: ByteArray): Boolean = handleHapticsFrame(data, 0L)

    fun handleHapticsFrame(data: ByteArray, nativeElapsedRealtimeNs: Long): Boolean
    {
        if(data.size < 4)
            return false
        if(!isSupported() || !hasBluetoothPermission())
            return false

        noteHapticsSourceFrame(data.size, SystemClock.elapsedRealtimeNanos(), nativeElapsedRealtimeNs)
        appendNativeHapticsDataLocked(data)

        // Inline packetization: immediately drain any newly-complete 32-frame blocks from
        // staging into the output ring. Without this, the first packet of each effect waits
        // up to HAPTICS_PRODUCER_INTERVAL_NS (10 ms) before the packetizer thread fires.
        var inlineProduced = 0
        while(inlineProduced < HAPTICS_PACKETIZER_MAX_PACKETS_PER_TICK)
        {
            if(!tryPopNativeHapticsBlock(inlinePacketizePreBuffer, NATIVE_HAPTICS_BLOCK_BYTES_STREAM)) break
            convertNativeBlockToPacket(
                source = inlinePacketizePreBuffer,
                sourceOffset = 0,
                sourceLength = NATIVE_HAPTICS_BLOCK_BYTES_STREAM,
                target = inlinePacketizeScratch,
            )
            synchronized(writerLock) {
                copyPacketIntoOutputRingLocked(inlinePacketizeScratch)
                inlineProduced += 1
            }
        }

        synchronized(writerLock) {
            sourcePacketizerLastProducedPackets = inlineProduced.toLong()
            if(inlineProduced > 0)
            {
                sourcePacketizerProducedPackets += inlineProduced.toLong()
                packetizerProducedPackets += inlineProduced.toLong()
                signalHapticsFramesAvailableLocked(inlineProduced)
            }
            ensureWriterStartedLocked()
            writerLock.notifyAll()
        }
        return true
    }

    fun isNativeHapticsActive(): Boolean
    {
        synchronized(writerLock) {
            if(hapticsOutputFrameCount > 0)
                return true
            if(nativeHapticsStagingCountVolatile >= NATIVE_HAPTICS_BLOCK_BYTES_STREAM)
                return true
            return isHapticsCadenceLiveLocked(SystemClock.elapsedRealtimeNanos())
        }
    }

    fun getBtHapticsDebugSnapshot(): BtHapticsDebugSnapshot
    {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val nowMs = SystemClock.uptimeMillis()
        val sourceLastNs = lastHapticsFrameNs
        val sourceLastAgeMs = if(sourceLastNs > 0L) (nowNs - sourceLastNs).coerceAtLeast(0L) / 1_000_000L else 0L
        val liveBridge = bridge
        val transportDiagnostics = liveBridge?.getTransportDiagnostics()
        val hapticsTransportMode = liveBridge?.let { resolveHapticsTransport(it).transportName } ?: "none"
        synchronized(writerLock) {
            return BtHapticsDebugSnapshot(
                queuePackets = hapticsOutputFrameCount,
                stagingBytes = nativeHapticsStagingCountVolatile,
                framesAvailableGeneration = hapticsFramesAvailableGeneration,
                cadenceResetCount = hapticsCadenceResetCount,
                keepaliveSendCount = hapticsKeepaliveSendCount,
                deadlineCatchupCount = hapticsDeadlineCatchupCount,
                stagingOverwriteBytes = hapticsStagingOverwriteBytes,
                packetOverwriteCount = hapticsPacketOverwriteCount,
                deferredStateTrafficCount = deferredStateTrafficCount,
                combinedRouteArmed = combinedHapticsRouteArmed,
                pendingCombinedStateFlush = pendingCombinedStateFlush,
                packetizerSkippedCycles = packetizerSkippedCycles,
                packetizerProducedPackets = packetizerProducedPackets,
                sourcePacketizerProducedPackets = sourcePacketizerProducedPackets,
                sourcePacketizerLastProducedPackets = sourcePacketizerLastProducedPackets,
                sourcePacketizerQueueFullCount = sourcePacketizerQueueFullCount,
                hapticsSendAttempts = hapticsSendAttempts,
                hapticsSendSuccesses = hapticsSendSuccesses,
                hapticsSendFailures = hapticsSendFailures,
                hapticsSendBackoffRemainingUs = (hapticsSendBackoffUntilNs - nowNs)
                    .coerceAtLeast(0L) / 1_000L,
                hapticsLastSendDurationUs = hapticsLastSendDurationNs / 1_000L,
                hapticsMaxSendDurationUs = hapticsMaxSendDurationNs / 1_000L,
                hapticsSlowSendOver8MsCount = hapticsSlowSendOver8MsCount,
                hapticsSlowSendOver12MsCount = hapticsSlowSendOver12MsCount,
                hapticsSlowSendOver20MsCount = hapticsSlowSendOver20MsCount,
                hapticsSlowSendOver50MsCount = hapticsSlowSendOver50MsCount,
                hapticsLastSuccessAgeMs = if(lastSuccessfulHapticsSendNs > 0L)
                    (nowNs - lastSuccessfulHapticsSendNs).coerceAtLeast(0L) / 1_000_000L else 0L,
                hapticsLastFailureAgeMs = if(lastFailedHapticsSendNs > 0L)
                    (nowNs - lastFailedHapticsSendNs).coerceAtLeast(0L) / 1_000_000L else 0L,
                hapticsFastPathFallbackCount = hapticsFastPathFallbackCount,
                hapticsFastPathFallbackSuccessCount = hapticsFastPathFallbackSuccessCount,
                hapticsLastSendIntervalUs = hapticsLastSendIntervalNs / 1_000L,
                hapticsMaxSendIntervalUs = hapticsMaxSendIntervalNs / 1_000L,
                hapticsSendIntervalOver15MsCount = hapticsSendIntervalOver15MsCount,
                hapticsSendIntervalOver20MsCount = hapticsSendIntervalOver20MsCount,
                hapticsSendIntervalOver30MsCount = hapticsSendIntervalOver30MsCount,
                hapticsSendIntervalOver50MsCount = hapticsSendIntervalOver50MsCount,
                hapticsLastLargeSendIntervalUs = hapticsLastLargeSendIntervalNs / 1_000L,
                hapticsLastLargeSendIntervalAgeMs = if(hapticsLastLargeSendIntervalTimestampNs > 0L)
                    (nowNs - hapticsLastLargeSendIntervalTimestampNs).coerceAtLeast(0L) / 1_000_000L else 0L,
                hapticsLastDeadlineMissUs = hapticsLastDeadlineMissNs / 1_000L,
                hapticsMaxDeadlineMissUs = hapticsMaxDeadlineMissNs / 1_000L,
                hapticsDeadlineMissCount = hapticsDeadlineMissCount,
                hapticsImmediateCatchupCount = hapticsImmediateCatchupCount,
                nowMs = nowMs,
                hapticsFramesReceived = hapticsFramesReceived,
                lastHapticsFrameBytes = lastHapticsFrameBytes,
                lastHapticsFrameAgeMs = sourceLastAgeMs,
                lastHapticsFrameDeltaUs = lastHapticsFrameDeltaNs / 1_000L,
                maxHapticsFrameDeltaUs = maxHapticsFrameDeltaNs / 1_000L,
                hapticsSourceGapOver20MsCount = hapticsSourceGapOver20MsCount,
                hapticsSourceGapOver30MsCount = hapticsSourceGapOver30MsCount,
                hapticsSourceGapOver50MsCount = hapticsSourceGapOver50MsCount,
                hapticsLastLargeSourceGapUs = hapticsLastLargeSourceGapNs / 1_000L,
                hapticsLastLargeSourceGapAgeMs = if(hapticsLastLargeSourceGapTimestampNs > 0L)
                    (nowNs - hapticsLastLargeSourceGapTimestampNs).coerceAtLeast(0L) / 1_000_000L else 0L,
                hapticsNativeTimestampFrames = hapticsNativeTimestampFrames,
                hapticsNativeToKotlinLastDelayUs = hapticsNativeToKotlinLastDelayNs / 1_000L,
                hapticsNativeToKotlinMaxDelayUs = hapticsNativeToKotlinMaxDelayNs / 1_000L,
                hapticsNativeToKotlinDelayOver1MsCount = hapticsNativeToKotlinDelayOver1MsCount,
                hapticsNativeToKotlinDelayOver2MsCount = hapticsNativeToKotlinDelayOver2MsCount,
                hapticsNativeToKotlinDelayOver5MsCount = hapticsNativeToKotlinDelayOver5MsCount,
                hapticsNativeToKotlinDelayOver10MsCount = hapticsNativeToKotlinDelayOver10MsCount,
                hapticsNativeToKotlinDelayOver20MsCount = hapticsNativeToKotlinDelayOver20MsCount,
                hapticsNativeToKotlinLastLargeDelayUs = hapticsNativeToKotlinLastLargeDelayNs / 1_000L,
                hapticsNativeToKotlinLastLargeDelayAgeMs = if(hapticsNativeToKotlinLastLargeDelayTimestampNs > 0L)
                    (nowNs - hapticsNativeToKotlinLastLargeDelayTimestampNs).coerceAtLeast(0L) / 1_000_000L else 0L,
                unexpectedHapticsFrameBytesCount = unexpectedHapticsFrameBytesCount,
                lastStagingOverwriteBytes = lastHapticsStagingOverwriteBytes,
                lastStagingOverwriteAgeMs = if(lastHapticsStagingOverwriteNs > 0L)
                    (nowNs - lastHapticsStagingOverwriteNs).coerceAtLeast(0L) / 1_000_000L else 0L,
                partialFlushCount = partialFlushCount,
                packetizerLastTickAgeMs = if(packetizerLastTickNs > 0L)
                    (nowNs - packetizerLastTickNs).coerceAtLeast(0L) / 1_000_000L else 0L,
                packetizerLastTickLateUs = packetizerLastTickLateNs / 1_000L,
                packetizerMaxTickLateUs = packetizerMaxTickLateNs / 1_000L,
                packetizerLateTickCount = packetizerLateTickCount,
                packetizerLastProducedPackets = packetizerLastProducedPackets,
                hapticsTransportMode = hapticsTransportMode,
                hidSelectedDeviceName = transportDiagnostics?.selectedDeviceName,
                hidSelectedDeviceAddress = transportDiagnostics?.selectedDeviceAddress,
                hidConnectedDeviceCount = transportDiagnostics?.connectedDeviceCount ?: 0,
                hidCachedTransportMode = transportDiagnostics?.cachedTransportMode,
                hidSendDataRawReady = transportDiagnostics?.sendDataRawReady ?: false,
                hidSendDataHexReady = transportDiagnostics?.sendDataHexReady ?: false,
                hidSetReportRawReady = transportDiagnostics?.setReportRawReady ?: false,
                hidSetReportHexReady = transportDiagnostics?.setReportHexReady ?: false,
                hidGetReportReady = transportDiagnostics?.getReportReady ?: false,
                hidSendDataRawSignature = transportDiagnostics?.sendDataRawSignature,
                hidSendDataHexSignature = transportDiagnostics?.sendDataHexSignature,
                hidSetReportRawSignature = transportDiagnostics?.setReportRawSignature,
                hidSetReportHexSignature = transportDiagnostics?.setReportHexSignature,
                hidGetReportSignature = transportDiagnostics?.getReportSignature,
            )
        }
    }

    fun isControllerOutputAvailable(): Boolean = isSupported() && hasBluetoothPermission()

    private fun noteHapticsSourceFrame(size: Int, nowNs: Long, nativeElapsedRealtimeNs: Long)
    {
        val previousNs = lastHapticsFrameNs
        hapticsFramesReceived += 1L
        lastHapticsFrameBytes = size
        if(nativeElapsedRealtimeNs > 0L && nowNs >= nativeElapsedRealtimeNs)
        {
            val delayNs = nowNs - nativeElapsedRealtimeNs
            hapticsNativeTimestampFrames += 1L
            hapticsNativeToKotlinLastDelayNs = delayNs
            if(delayNs > hapticsNativeToKotlinMaxDelayNs)
                hapticsNativeToKotlinMaxDelayNs = delayNs
            if(delayNs >= 1_000_000L)
                hapticsNativeToKotlinDelayOver1MsCount += 1L
            if(delayNs >= 2_000_000L)
                hapticsNativeToKotlinDelayOver2MsCount += 1L
            if(delayNs >= 5_000_000L)
            {
                hapticsNativeToKotlinDelayOver5MsCount += 1L
                hapticsNativeToKotlinLastLargeDelayNs = delayNs
                hapticsNativeToKotlinLastLargeDelayTimestampNs = nowNs
            }
            if(delayNs >= 10_000_000L)
                hapticsNativeToKotlinDelayOver10MsCount += 1L
            if(delayNs >= 20_000_000L)
                hapticsNativeToKotlinDelayOver20MsCount += 1L
        }
        if(previousNs > 0L)
        {
            val deltaNs = (nowNs - previousNs).coerceAtLeast(0L)
            lastHapticsFrameDeltaNs = deltaNs
            if(deltaNs > maxHapticsFrameDeltaNs)
                maxHapticsFrameDeltaNs = deltaNs
            if(deltaNs >= 20_000_000L)
            {
                hapticsSourceGapOver20MsCount += 1L
                hapticsLastLargeSourceGapNs = deltaNs
                hapticsLastLargeSourceGapTimestampNs = nowNs
            }
            if(deltaNs >= 30_000_000L)
                hapticsSourceGapOver30MsCount += 1L
            if(deltaNs >= 50_000_000L)
                hapticsSourceGapOver50MsCount += 1L
        }
        if(size != REMOTE_PLAY_HAPTICS_FRAME_BYTES)
            unexpectedHapticsFrameBytesCount += 1L
        lastHapticsFrameNs = nowNs
    }

    /**
     * Request a short preview tone from the real DualSense BT speaker (0x39 Opus path) so the
     * user can hear the current volume level.  The writer thread picks up the flag and plays a
     * handful of savannah Opus packets.  Safe to call from any thread.
     */
    fun feedPreviewTone(volumePercent: Int)
    {
        if(!isSupported() || !hasBluetoothPermission()) return
        DualSenseBtReportBuilder.speakerVolumePercent = volumePercent.coerceIn(0, 100)
        // Decode the demo clip on first use rather than at every stream start.
        // Failure here must never be fatal: the preview is a nicety, and this
        // runs on a caller thread that may not have a handler.
        if(speakerPreviewPackets == null)
        {
            Thread {
                try
                {
                    speakerPreviewPackets = DualSenseBtSpeakerAudio.loadDualFramePackets(appContext)
                    synchronized(writerLock) { pendingSpeakerPreview = true }
                    LockSupport.unpark(writerThread)
                }
                catch(e: Throwable)
                {
                    Log.w("DualSenseBtStream", "Speaker preview clip unavailable; skipping preview", e)
                }
            }.start()
            return
        }
        synchronized(writerLock) { pendingSpeakerPreview = true }
        LockSupport.unpark(writerThread)
    }

    fun updateHeadphoneVolume(volumePercent: Int)
    {
        DualSenseBtReportBuilder.headphoneVolumePercent = volumePercent.coerceIn(0, 100)
        synchronized(writerLock)
        {
            queueSlowStateLocked(force = true)
            lastAudioStateCarrierMs = 0L
            writerLock.notifyAll()
        }
    }

    // -------------------------------------------------------------------------
    // Speaker live audio streaming thread + preview tone via real 0x39 path
    // -------------------------------------------------------------------------

    private fun startSpeakerThread()
    {
        if(speakerThreadRunning) return
        speakerThreadRunning = true
        speakerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            speakerLoop()
            speakerThreadRunning = false
        }, "DualSenseBtSpeaker").also { it.isDaemon = true; it.start() }
    }

    private fun stopSpeakerThread()
    {
        speakerThreadRunning = false
        speakerThread?.interrupt()
        runCatching { speakerThread?.join(500) }
        speakerThread = null
    }

    private fun startJackPoller()
    {
        jackPollerRunning = true
        jackPollerThread = Thread(
        {
            var lastPlugged: Boolean? = null
            var lastRouteActive = false
            while(jackPollerRunning)
            {
                try
                {
                    val liveBridge = bridge
                    if(liveBridge != null)
                    {
                        val prefs = Preferences(appContext)
                        val autoSwitchEnabled = prefs.controllerAutoSwitchOutput
                        if(!autoSwitchEnabled)
                        {
                            if(lastRouteActive)
                            {
                                lastRouteActive = false
                                controllerJackStateCallback?.invoke(false)
                            }
                            Thread.sleep(1000)
                            continue
                        }
                        if(SUSPEND_JACK_POLLER_DURING_HAPTICS && isNativeHapticsActive())
                        {
                            Thread.sleep(1000)
                            continue
                        }
                        val result = liveBridge.requestInputReport()
                        if(result.success && result.report != null)
                        {
                            val status = DualSenseInfoParser.parseInputStatus(result.report)
                            if(status != null)
                            {
                                // Battery rides along on the report the jack poller
                                // already requests, so surfacing it costs no extra
                                // BT traffic. Null while unparseable/unknown.
                                controllerBatteryPercent =
                                    status.batteryPercent.removeSuffix("%").toIntOrNull()?.coerceIn(0, 100)
                                controllerPowerState = status.powerState
                                val plugged = status.headphonesPlugged
                                if(plugged != lastPlugged)
                                {
                                    lastPlugged = plugged
                                    lastRouteActive = plugged
                                    DualSenseBtReportBuilder.headphoneMode = plugged
                                    ControllerSystemAudioService.audioOutputRoute =
                                        if(plugged) AudioOutputRoute.HEADPHONE else AudioOutputRoute.SPEAKER
                                    controllerJackStateCallback?.invoke(plugged)
                                    synchronized(writerLock)
                                    {
                                        queueSlowStateLocked(force = true)
                                        lastAudioStateCarrierMs = 0L
                                        writerLock.notifyAll()
                                    }
                                }
                            }
                        }
                    }
                    Thread.sleep(1000)
                }
                catch(e: InterruptedException) { break }
                catch(e: Exception)
                {
                    try { Thread.sleep(1000) } catch(_: InterruptedException) { break }
                }
            }
        }, "DualSenseBtJackPoller").also { it.isDaemon = true; it.start() }
    }

    private fun stopJackPoller()
    {
        jackPollerRunning = false
        jackPollerThread?.interrupt()
        jackPollerThread = null
    }

    /**
     * Speaker streaming loop — runs while the BT feedback is active.
     *
     * Two modes:
     *  1. PREVIEW: when [pendingSpeakerPreview] is set, play a few savannah packets
     *     through the real 0x39 path so the user hears the new volume level.
     *  2. LIVE: when [ControllerSpeakerBus.enabled], pop two 10.667 ms Opus
     *     frames and send them through the known-audible 0x39 two-frame report.
     *
     * Deadline model (Patch 2 speaker fix):
     *  nextDeadlineNs is the UPCOMING send deadline, set to now+period at arm time
     *  and advanced by period AFTER each send. Pop-wait windows are always anchored
     *  to nextDeadlineNs, so they have ~19 ms to wait for frames regardless of how
     *  long the previous BT send took. The drift guard after each send clamps
     *  nextDeadlineNs forward if a stall pushed us more than one period behind,
     *  preventing back-to-back sends that drain the thin frame buffer.
     */
    private fun speakerLoop()
    {
        val periodNs = DualSenseBtSpeakerAudio.REPORT_PERIOD_TWO_NS
        var nextDeadlineNs = 0L
        var speakerRouteArmed = false
        var armedBridge: DualSenseBtHidBridge? = null
        val zeroOpus = ByteArray(DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)
        val zeroPcm = ByteArray(DualSenseBtSpeakerAudio.PCM_BYTES_PER_FRAME)
        var lastHeadphoneMode = DualSenseBtReportBuilder.headphoneMode

        while(speakerThreadRunning && !Thread.currentThread().isInterrupted)
        {
            val liveBridge = bridge
            if(armedBridge !== liveBridge)
            {
                speakerRouteArmed = false
                armedBridge = liveBridge
                nextDeadlineNs = 0L
            }

            // Re-arm when headphone/speaker mode changes mid-stream
            val currentHeadphoneMode = DualSenseBtReportBuilder.headphoneMode
            if (currentHeadphoneMode != lastHeadphoneMode) {
                lastHeadphoneMode = currentHeadphoneMode
                speakerRouteArmed = false
                nextDeadlineNs = 0L
            }

            // --- Preview ---
            val doPreview = synchronized(writerLock) {
                if(pendingSpeakerPreview) { pendingSpeakerPreview = false; true } else false
            }
            if(doPreview)
            {
                val packets = speakerPreviewPackets
                if(packets != null && liveBridge != null)
                {
                    if(!armSpeakerRouteForPreview(liveBridge))
                    {
                        nextDeadlineNs = 0L
                        continue
                    }
                    DualSenseBtSpeakerAudio.resetCounters()
                    speakerRouteArmed = true
                    val count = packets.size.coerceAtMost(20)
                    var pDeadline = SystemClock.elapsedRealtimeNanos()
                    val transport = resolveSpeakerTransport(liveBridge)
                    for(i in 0 until count)
                    {
                        if(!speakerThreadRunning) break
                        val report = DualSenseBtSpeakerAudio.buildTwoFrameReport(
                            packets[i],
                            includeHapticsTail = false,
                        )
                        sendSpeakerReport(liveBridge, report, transport)
                        pDeadline += DualSenseBtSpeakerAudio.REPORT_PERIOD_TWO_NS
                        parkSpeakerUntil(pDeadline)
                    }
                }
                nextDeadlineNs = 0L
                continue
            }

            // --- Live streaming ---
            if(ControllerSpeakerBus.enabled && liveBridge != null)
            {
                // Double-drain guard: when the combined haptics route is armed, the writerLoop
                // already carries one Opus frame per 10.666ms inside every 0x36 report.
                // Running the 0x39 speakerLoop simultaneously drains ControllerSpeakerBus at
                // ~187.5 fps combined vs 100 fps production — starvation in ~180ms → silence.
                // Yield here; when haptics stop and combinedHapticsRouteArmed resets to false,
                // this loop re-arms the 0x39 path automatically on the next iteration.
                val combinedOwnsAudio = synchronized(writerLock) { combinedHapticsRouteArmed }
                if(combinedOwnsAudio) {
                    speakerRouteArmed = false
                    nextDeadlineNs = 0L
                    LockSupport.parkNanos(8_000_000L)
                    continue
                }
                if(!speakerRouteArmed)
                {
                    if(ControllerSpeakerBus.depth() < 4)  // wait for 4-frame buffer (~42ms) before arming
                    {
                        LockSupport.parkNanos(4_000_000L)
                        continue
                    }
                    if(!armSpeakerRouteForPreview(liveBridge))
                    {
                        speakerRouteArmed = false
                        nextDeadlineNs = 0L
                        LockSupport.parkNanos(40_000_000L)
                        continue
                    }
                    DualSenseBtSpeakerAudio.resetCounters()
                    speakerRouteArmed = true
                    // Set deadline one period ahead so the very first pop-wait window
                    // has the full ~19 ms to wait for frames.
                    nextDeadlineNs = SystemClock.elapsedRealtimeNanos() + periodNs
                }
                else if(nextDeadlineNs == 0L)
                {
                    nextDeadlineNs = SystemClock.elapsedRealtimeNanos() + periodNs
                }

                // Pop-wait windows are anchored to nextDeadlineNs (the upcoming send
                // deadline), guaranteeing ~19 ms of wait time even right after a park.
                // Previously nextDeadlineNs was set to 'now' at arm time, making these
                // windows immediately expire on every second-and-later iteration.
                val popDeadline = nextDeadlineNs - 2_000_000L
                while(ControllerSpeakerBus.depth() < 1 && SystemClock.elapsedRealtimeNanos() < popDeadline && speakerThreadRunning)
                    LockSupport.parkNanos(300_000L)
                // Underrun must emit a REAL encoded silence packet, not 200 zero
                // bytes: 0x00 is a SILK-mode mono TOC, so feeding it mid
                // CELT-stereo stream makes the firmware decoder swallow a
                // codec/channel switch and renders as an artifact rather than
                // silence. zeroOpus stays only as a last resort if the service
                // never published a silence frame. See TechAntohere/Senshi#1.
                val underrunFrame = ControllerSpeakerBus.getSilenceFrame() ?: zeroOpus
                val opus1 = ControllerSpeakerBus.popFrame() ?: underrunFrame
                val pop2Deadline = nextDeadlineNs - 1_000_000L
                while(ControllerSpeakerBus.depth() < 1 && SystemClock.elapsedRealtimeNanos() < pop2Deadline && speakerThreadRunning)
                    LockSupport.parkNanos(200_000L)
                val opus2 = ControllerSpeakerBus.popFrame() ?: underrunFrame
                val report = DualSenseBtSpeakerAudio.buildTwoFrameReport(
                    DualSenseBtSpeakerAudio.DualFramePacket(
                        opus1 = opus1,
                        opus2 = opus2,
                        pcm1 = zeroPcm,
                        pcm2 = zeroPcm,
                    ),
                    routeSpeaker = !DualSenseBtReportBuilder.headphoneMode,
                    includeHapticsTail = false,
                )
                // Park until the deadline, then send. This keeps send timing at the
                // deadline boundary rather than send-to-send relative, so pop-wait
                // windows in the next iteration are always relative to a future point.
                parkSpeakerUntil(nextDeadlineNs)
                sendSpeakerReport(liveBridge, report, resolveSpeakerTransport(liveBridge))
                nextDeadlineNs += periodNs
                // Drift guard. The pad consumes audio on a strict isochronous
                // grid: exactly one report per period. Lateness must NOT be
                // repaid -- arriving early, even once, overflows a shallow
                // firmware-side intake buffer and audibly cuts out.
                //
                // The previous rule (nowAfterSend + periodNs/4) did exactly
                // that: it scheduled the next report ~5 ms after a late one,
                // off-grid, producing the late-then-early pattern that triggers
                // the dropout. Instead advance by WHOLE periods until at least
                // one full period ahead, staying on the original grid, and drop
                // the frames belonging to the slots we skipped rather than
                // playing them back late. The haptics writer already does this.
                // See hifihedgehog, TechAntohere/Senshi#1.
                val nowAfterSendNs = SystemClock.elapsedRealtimeNanos()
                if(nextDeadlineNs < nowAfterSendNs)
                {
                    var skippedSlots = 0
                    while(nextDeadlineNs < nowAfterSendNs + periodNs)
                    {
                        nextDeadlineNs += periodNs
                        skippedSlots++
                    }
                    // Two Opus frames per report.
                    repeat(skippedSlots * 2) { ControllerSpeakerBus.popFrame() }
                    speakerDeadlineSkips += skippedSlots
                }
            }
            else
            {
                speakerRouteArmed = false
                nextDeadlineNs = 0L
                LockSupport.parkNanos(8_000_000L)
            }
        }
    }

    private fun resolveSpeakerTransport(
        liveBridge: DualSenseBtHidBridge,
    ): DualSenseBtHidBridge.OutputTransportMode =
        liveBridge.resolveBestAvailableTransport(
            DualSenseBtHidBridge.OutputTransportMode.SEND_DATA_RAW,
            DualSenseBtHidBridge.OutputTransportMode.SET_REPORT_RAW,
            DualSenseBtHidBridge.OutputTransportMode.SEND_DATA_HEX,
            DualSenseBtHidBridge.OutputTransportMode.SET_REPORT_HEX,
        )

    private fun sendSpeakerReport(
        liveBridge: DualSenseBtHidBridge,
        report: ByteArray,
        transport: DualSenseBtHidBridge.OutputTransportMode,
    )
    {
        if(!liveBridge.sendOutputReportFast(report, transport))
        {
            liveBridge.sendOutputReport(report, streaming = true, transportMode = transport)
        }
    }

    private fun parkSpeakerUntil(deadlineNs: Long)
    {
        while(speakerThreadRunning && !Thread.currentThread().isInterrupted)
        {
            val remainingNs = deadlineNs - SystemClock.elapsedRealtimeNanos()
            if(remainingNs <= 0L) return
            if(remainingNs > 1_500_000L)
            {
                LockSupport.parkNanos(remainingNs - 500_000L)
            }
            else
            {
                Thread.onSpinWait()
            }
        }
    }

    /** Send the speaker activation sequence needed before any 0x39 audio reports. */
    private fun armSpeakerRouteForPreview(liveBridge: DualSenseBtHidBridge): Boolean
    {
        val state = synchronized(writerLock) { outputState.persistentStateSnapshot() }
        val bt = DualSenseBtHidBridge.OutputTransportMode.AUTO
        fun sleepMs(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() } }
        fun sendRoute(report: ByteArray): Boolean =
            liveBridge.sendOutputReport(report, streaming = true, transportMode = bt).success

        if(!sendRoute(DualSenseBtReportBuilder.buildPrimeReport(state)))
            return false
        sleepMs(8)
        if(!sendRoute(DualSenseBtReportBuilder.buildSpeakerActivationReport(state)))
            return false
        sleepMs(15)
        if(!sendRoute(DualSenseBtReportBuilder.buildControllerDataReport(state = state, ledReady = false, speakerRegularBtOutput = true)))
            return false
        sleepMs(8)
        if(!sendRoute(DualSenseBtReportBuilder.buildControllerDataReport(state = state, ledReady = true, speakerRegularBtOutput = true)))
            return false
        sleepMs(8)
        if(!sendRoute(DualSenseBtReportBuilder.buildSpeakerActivationReport(state)))
            return false
        sleepMs(15)
        return true
    }

    // -------------------------------------------------------------------------
    // fixed-rate packetizer thread.
    //
    // runs independently from the native audio callback. every controller period
    // drains full pcm blocks from the source staging ring into the packet queue.
    // advance nextFireNs by a fixed interval so jitter corrects on the next cycle.
    // -------------------------------------------------------------------------

    private fun packetizerLoop()
    {
        // use audio priority here; urgent audio was too aggressive.
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        // advance the target by a fixed interval.
        var nextFireNs = SystemClock.elapsedRealtimeNanos() + HAPTICS_PRODUCER_INTERVAL_NS
        val packetScratch = ByteArray(BT_PACKET_SAMPLE_BYTES)

        while(packetizerRunning)
        {
            // Coarse sleep when more than 2 ms remains
            val remainingNs = nextFireNs - SystemClock.elapsedRealtimeNanos()
            if(remainingNs > 2_000_000L)
            {
                try
                {
                    Thread.sleep((remainingNs / 1_000_000L) - 1L)
                }
                catch(_: InterruptedException)
                {
                    if(!packetizerRunning)
                        return
                }
            }
            else if(remainingNs > 250_000L)
            {
                LockSupport.parkNanos(remainingNs - 200_000L)
                if(Thread.interrupted())
                    return
            }
            // Fine spin - sub-0.2ms precision
            while(packetizerRunning && SystemClock.elapsedRealtimeNanos() < nextFireNs)
            {
                Thread.onSpinWait()
            }
            if(!packetizerRunning) return

            val tickNs = SystemClock.elapsedRealtimeNanos()
            val tickLateNs = (tickNs - nextFireNs).coerceAtLeast(0L)
            synchronized(writerLock) {
                packetizerLastTickNs = tickNs
                packetizerLastTickLateNs = tickLateNs
                if(tickLateNs > 250_000L)
                {
                    packetizerLateTickCount += 1L
                }
                if(tickLateNs > packetizerMaxTickLateNs)
                    packetizerMaxTickLateNs = tickLateNs
            }

            // advance by one fixed interval.
            nextFireNs += HAPTICS_PRODUCER_INTERVAL_NS

            var produced = 0
            while(packetizerRunning && produced < HAPTICS_PACKETIZER_MAX_PACKETS_PER_TICK)
            {
                if(!tryPopNativeHapticsBlock(hapticsPreBuffer, NATIVE_HAPTICS_BLOCK_BYTES_STREAM))
                    break

                convertNativeBlockToPacket(
                    source = hapticsPreBuffer,
                    sourceOffset = 0,
                    sourceLength = NATIVE_HAPTICS_BLOCK_BYTES_STREAM,
                    target = packetScratch,
                )
                synchronized(writerLock) {
                    if(!packetizerRunning)
                        return
                    copyPacketIntoOutputRingLocked(packetScratch)
                    produced += 1
                }
            }

            // flush partial blocks after they sit idle long enough.
            if(produced == 0 && packetizerRunning)
            {
                val stagingCount: Int
                synchronized(sourcePcmLock) { stagingCount = nativeHapticsStagingCount }
                if(stagingCount in 1 until NATIVE_HAPTICS_BLOCK_BYTES_STREAM)
                {
                    val sourceIdleNs = SystemClock.elapsedRealtimeNanos() - lastHapticsSourceNs
                    if(sourceIdleNs >= HAPTICS_PARTIAL_FLUSH_IDLE_MS * 1_000_000L)
                    {
                        hapticsPreBuffer.fill(0)
                        val flushed = synchronized(sourcePcmLock) {
                            val count = nativeHapticsStagingCount.coerceAtMost(NATIVE_HAPTICS_BLOCK_BYTES_STREAM)
                            if(count > 0)
                            {
                                val chunk1 = minOf(count, nativeHapticsStaging.size - nativeHapticsStagingHead)
                                System.arraycopy(nativeHapticsStaging, nativeHapticsStagingHead, hapticsPreBuffer, 0, chunk1)
                                if(chunk1 < count)
                                    System.arraycopy(nativeHapticsStaging, 0, hapticsPreBuffer, chunk1, count - chunk1)
                                nativeHapticsStagingHead = (nativeHapticsStagingHead + count) % nativeHapticsStaging.size
                                nativeHapticsStagingCount -= count
                                nativeHapticsStagingCountVolatile = nativeHapticsStagingCount
                                true
                            }
                            else false
                        }
                        if(flushed)
                        {
                            convertNativeBlockToPacket(
                                source = hapticsPreBuffer,
                                sourceOffset = 0,
                                sourceLength = NATIVE_HAPTICS_BLOCK_BYTES_STREAM,
                                target = packetScratch,
                            )
                            synchronized(writerLock) {
                                if(packetizerRunning)
                                {
                                    copyPacketIntoOutputRingLocked(packetScratch)
                                    produced += 1
                                    partialFlushCount += 1L
                                }
                            }
                        }
                    }
                }
            }

            synchronized(writerLock) {
                packetizerLastProducedPackets = produced.toLong()
                if(produced > 0)
                {
                    packetizerProducedPackets += produced.toLong()
                    signalHapticsFramesAvailableLocked(produced)
                }
                else
                {
                    packetizerSkippedCycles += 1L
                }
            }

        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireHapticsWakeLock()
    {
        val existingWakeLock = hapticsWakeLock
        if(existingWakeLock?.isHeld == true)
            return

        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        hapticsWakeLock = (existingWakeLock ?: powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Chiaki:DualSenseBtHaptics",
        ).apply {
            setReferenceCounted(false)
        }).also { wakeLock ->
            runCatching {
                if(!wakeLock.isHeld)
                    wakeLock.acquire()
            }
        }
    }

    private fun releaseHapticsWakeLock()
    {
        hapticsWakeLock?.let { wakeLock ->
            runCatching {
                if(wakeLock.isHeld)
                    wakeLock.release()
            }
        }
    }

    private fun stopBtForegroundWork()
    {
        releaseHapticsWakeLock()
        ControllerBtForegroundService.stop(appContext)
    }

    private fun ensurePacketizerStartedLocked()
    {
        val existing = packetizerThread
        if(existing != null && existing.isAlive)
            return

        packetizerRunning = true
        val worker = Thread {
            try { packetizerLoop() }
            finally {
                synchronized(writerLock) {
                    if(Thread.currentThread() === packetizerThread)
                        packetizerThread = null
                }
            }
        }.apply {
            name = "DualSenseBtPacketizer"
            isDaemon = true
        }
        packetizerThread = worker
        worker.start()
    }

    private fun writerLoop()
    {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val packetBuffer = ByteArray(BT_PACKET_SAMPLE_BYTES)
        val secondPacketBuffer = ByteArray(BT_PACKET_SAMPLE_BYTES)
        val reportBuffer = ByteArray(DualSenseBtAudioHapticsBuilder.REPORT_SIZE)
        val combinedReportBuffer = ByteArray(DualSenseBtReportBuilder.COMBINED_CONTROLLER_HAPTICS_REPORT_SIZE)
        var nextHapticsDeadlineNs = 0L
        var hapticsCadenceAnchorNs = 0L
        var hapticsCadencePacketsSent = 0L

        while(true)
        {
            var stateToSend: PendingStateSnapshot? = null
            var stateSendPreferInterrupt = false
            var sendHapticsPacket = false
            var sendHapticsFrameCount = 1
            var sendKeepalive = false
            var sendRouteUpdateNow = false
            var sendCombinedStateCarrier = false
            var sendTriggerOffReport = false
            var prearmCombinedRoute = false
            var preciseWaitUntilNs = 0L
            var restartHapticsStreamNow = false

            synchronized(writerLock) {
                while(!writerStopRequested)
                {
                    val nowMs = SystemClock.uptimeMillis()
                    val nowNs = SystemClock.elapsedRealtimeNanos()
                    if(maybeResetHapticsCadenceLocked(nowNs))
                    {
                        nextHapticsDeadlineNs = 0L
                        hapticsCadenceAnchorNs = 0L
                        hapticsCadencePacketsSent = 0L
                    }
                    val fastDue = pendingFastState != null && nowMs - lastFastStateReportMs >= fastStateMinIntervalLocked()
                    val slowDue = pendingSlowState != null && nowMs - lastSlowStateReportMs >= slowStateMinIntervalLocked()
                    val hasQueuedHaptics = hapticsOutputFrameCount > 0
                    val cadenceLive = isHapticsCadenceLiveLocked(nowNs)
                    val wantsKeepalive = shouldSendKeepaliveLocked(nowNs, nextHapticsDeadlineNs)
                    val hapticsSendBlocked = nowNs < hapticsSendBackoffUntilNs
                    val routeDue = pendingRouteUpdate && nowMs - lastRouteUpdateMs >= LIGHTBAR_ROUTE_MIN_INTERVAL_MS
                    val triggerOffDue = pendingTriggerOffReport
                    val combinedStateCarrierDue = combinedHapticsRouteArmed &&
                        pendingCombinedStateFlush &&
                        nowMs - lastAudioStateCarrierMs >= fastStateMinIntervalLocked()
                    val shouldPrioritizeCombinedHaptics = shouldPrioritizeCombinedHapticsLocked(
                        nowNs = nowNs,
                        nextHapticsDeadlineNs = nextHapticsDeadlineNs,
                    )

                    val timeUntilNextHapticsNs = when
                    {
                        nextHapticsDeadlineNs == 0L || (!wantsHapticsLocked() && !cadenceLive) -> Long.MAX_VALUE
                        else -> nextHapticsDeadlineNs - nowNs
                    }

                    val canInterleaveState = canInterleaveStateLocked(
                        timeUntilNextHapticsNs = timeUntilNextHapticsNs,
                        cadenceLive = cadenceLive,
                    )

                    if(shouldPrioritizeCombinedHaptics && (fastDue || slowDue || routeDue || combinedStateCarrierDue || triggerOffDue))
                        deferredStateTrafficCount += 1L

                    if(hasQueuedHaptics && !hapticsSendBlocked && canSendQueuedHapticsLocked() &&
                        (nextHapticsDeadlineNs == 0L || nowNs >= nextHapticsDeadlineNs || !canInterleaveState))
                    {
                        restartHapticsStreamNow = restartCombinedStreamPending
                        peekHapticsPacketLocked(packetBuffer)
                        val lateByNs = if(nextHapticsDeadlineNs > 0L) nowNs - nextHapticsDeadlineNs else 0L
                        if(lateByNs > 0L)
                            noteHapticsDeadlineMissLocked(lateByNs)
                        val canCatchUpTwoFrames = USE_COMBINED_BT_HAPTICS &&
                            ENABLE_TWO_FRAME_HAPTICS_CATCHUP &&
                            hapticsOutputFrameCount >= 2 &&
                            lateByNs >= HAPTICS_TWO_FRAME_CATCHUP_LATE_NS &&
                            pendingFastState == null &&
                            pendingSlowState == null &&
                            !pendingRouteUpdate &&
                            !pendingCombinedStateFlush &&
                            !pendingTriggerOffReport
                        sendHapticsFrameCount = if(canCatchUpTwoFrames && peekHapticsPacketLocked(secondPacketBuffer, 1)) 2 else 1
                        sendHapticsPacket = true
                        break
                    }

                    if(triggerOffDue && canInterleaveState)
                    {
                        sendTriggerOffReport = true
                        break
                    }

                    if(fastDue && canInterleaveState && (!USE_COMBINED_BT_HAPTICS || !shouldPrioritizeCombinedHaptics || !combinedHapticsRouteArmed))
                    {
                        stateToSend = pendingFastState
                        stateSendPreferInterrupt = true
                        break
                    }

                    if(slowDue && canInterleaveState && (!USE_COMBINED_BT_HAPTICS || !shouldPrioritizeCombinedHaptics || !combinedHapticsRouteArmed))
                    {
                        stateToSend = pendingSlowState
                        stateSendPreferInterrupt = false
                        break
                    }

                    if(routeDue && !fastDue && canInterleaveState && !shouldPrioritizeCombinedHaptics)
                    {
                        sendRouteUpdateNow = true
                        break
                    }

                    if(combinedStateCarrierDue && canInterleaveState)
                    {
                        sendCombinedStateCarrier = true
                        break
                    }

                    if(shouldPrearmCombinedRouteLocked())
                    {
                        prearmCombinedRoute = true
                        break
                    }

                    if(wantsKeepalive && !hapticsSendBlocked)
                    {
                        restartHapticsStreamNow = restartCombinedStreamPending
                        sendKeepalive = true
                        break
                    }

                    var waitNs = computeWriterWaitNsLocked(
                        nowMs = nowMs,
                        nowNs = nowNs,
                        nextHapticsDeadlineNs = nextHapticsDeadlineNs,
                    )
                    if(hapticsSendBlocked)
                    {
                        val blockedWaitNs = (hapticsSendBackoffUntilNs - nowNs).coerceAtLeast(1L)
                        waitNs = minOf(waitNs, blockedWaitNs)
                    }
                    if(waitNs == Long.MAX_VALUE)
                        waitForHapticsFramesAvailableLocked()
                    else if(
                        nextHapticsDeadlineNs > nowNs &&
                        waitNs == nextHapticsDeadlineNs - nowNs &&
                        (hasQueuedHaptics || wantsKeepalive) &&
                        !hapticsSendBlocked &&
                        waitNs <= 8_000_000L  // 8 ms: packetizer (10 ms) wakes writer at T+10ms, leaving 0.667ms to precise-spin
                    )
                    {
                        preciseWaitUntilNs = nextHapticsDeadlineNs
                        break
                    }
                    else
                        waitOnWriterLockLocked(waitNs)
                }

                if(writerStopRequested)
                    return
            }

            if(preciseWaitUntilNs != 0L)
            {
                parkWriterUntil(preciseWaitUntilNs)
                continue
            }

            val bridge = readyBridge()
            if(bridge == null)
            {
                try
                {
                    Thread.sleep(6L)
                }
                catch(_: InterruptedException)
                {
                    Thread.currentThread().interrupt()
                    return
                }
                continue
            }

            if(stateToSend != null)
            {
                val state = stateToSend!!
                val stateTransport = resolveStateTransport(bridge)
                val report = buildReportForPendingState(state)
                val skipDueToDedupe = synchronized(writerLock) { shouldSkipStateTransportReportLocked(report) }
                if(skipDueToDedupe)
                {
                    val nowMs = SystemClock.uptimeMillis()
                    synchronized(writerLock) {
                        clearMatchingPendingStatesLocked(state.semantic)
                        lastPrimeTriggerStateReport = state.snapshot.triggerStateReport?.copyOf()
                        lastSentStateSemantic = state.semantic
                        when(state.kind)
                        {
                            PendingStateSnapshot.Kind.FAST -> lastFastStateReportMs = nowMs
                            PendingStateSnapshot.Kind.SLOW -> lastSlowStateReportMs = nowMs
                        }
                    }
                    continue
                }
                val result = bridge.sendOutputReport(
                    report,
                    streaming = true,
                    preferInterrupt = stateSendPreferInterrupt,
                    transportMode = stateTransport,
                )
                if(result.success)
                {
                    synchronized(writerLock) {
                        clearMatchingPendingStatesLocked(state.semantic)
                        lastPrimeTriggerStateReport = state.snapshot.triggerStateReport?.copyOf()
                        lastSentStateSemantic = state.semantic
                        markStateTransportReportSentLocked(report)
                        when(state.kind)
                        {
                            PendingStateSnapshot.Kind.FAST -> lastFastStateReportMs = SystemClock.uptimeMillis()
                            PendingStateSnapshot.Kind.SLOW -> lastSlowStateReportMs = SystemClock.uptimeMillis()
                        }
                    }
                }
                continue
            }

            if(sendTriggerOffReport)
            {
                val stateTransport = resolveStateTransport(bridge)
                val clearState = synchronized(writerLock) { outputState.persistentStateSnapshot() }
                val report = DualSenseBtReportBuilder.buildClearTriggerReport(state = clearState)
                val sent = bridge.sendOutputReportFast(report, stateTransport) || bridge.sendOutputReport(
                    report,
                    streaming = true,
                    transportMode = stateTransport,
                ).success
                if(sent)
                {
                    synchronized(writerLock) {
                        pendingTriggerOffReport = false
                        lastSentStateSemantic = null
                        markStateTransportReportSentLocked(report)
                        lastFastStateReportMs = SystemClock.uptimeMillis()
                    }
                }
                continue
            }

            if(sendCombinedStateCarrier)
            {
                val stateTransport = resolveStateTransport(bridge)
                if(!armCombinedHapticsRouteIfNeeded(bridge, stateTransport))
                    continue
                val snapshot = synchronized(writerLock) { snapshotOutputStateLocked() }
                val report = DualSenseBtReportBuilder.buildAudioStateCarrierReport(
                    state = snapshot.persistentState,
                    ledReady = synchronized(writerLock) { isVisualSteadyStateLocked() },
                    leftTrigger = snapshot.leftTriggerControllerData,
                    rightTrigger = snapshot.rightTriggerControllerData,
                )
                val skipDueToDedupe = synchronized(writerLock) { shouldSkipStateTransportReportLocked(report) }
                if(skipDueToDedupe)
                {
                    synchronized(writerLock) {
                        markCombinedStateFlushedLocked(snapshot, SystemClock.uptimeMillis())
                    }
                    continue
                }
                val sent = bridge.sendOutputReportFast(report, stateTransport) || bridge.sendOutputReport(
                    report,
                    streaming = true,
                    transportMode = stateTransport,
                ).success
                if(sent)
                {
                    synchronized(writerLock) {
                        markStateTransportReportSentLocked(report)
                        markCombinedStateFlushedLocked(snapshot, SystemClock.uptimeMillis())
                    }
                }
                continue
            }

            if(prearmCombinedRoute)
            {
                armCombinedHapticsRouteIfNeeded(
                    bridge = bridge,
                    transportMode = resolveHapticsTransport(bridge),
                )
                continue
            }

            if(sendRouteUpdateNow)
            {
                if(sendRouteUpdate(bridge, resolveStateTransport(bridge)))
                    continue
            }

            if(sendHapticsPacket)
            {
                val completionNs = sendHapticsReport(
                    bridge = bridge,
                    packetBuffer = packetBuffer,
                    secondPacketBuffer = if(sendHapticsFrameCount == 2) secondPacketBuffer else null,
                    reportBuffer = reportBuffer,
                    combinedReportBuffer = combinedReportBuffer,
                    transportMode = resolveHapticsTransport(bridge),
                    sendSamples = true,
                    sendFrameCount = sendHapticsFrameCount,
                    restartStream = restartHapticsStreamNow,
                    keepalive = false,
                )
                if(completionNs == null)
                {
                    synchronized(writerLock) {
                        noteHapticsSendFailureLocked(SystemClock.elapsedRealtimeNanos())
                    }
                    continue
                }
                if(restartHapticsStreamNow)
                {
                    hapticsCadenceAnchorNs = 0L
                    hapticsCadencePacketsSent = 0L
                    nextHapticsDeadlineNs = 0L
                }
                val cadenceState = advanceHapticsCadenceState(
                    previousAnchorNs = hapticsCadenceAnchorNs,
                    previousPacketsSent = hapticsCadencePacketsSent,
                    completionNs = completionNs,
                    framesSent = sendHapticsFrameCount,
                    skipMissedDeadlines = false,
                )
                hapticsCadenceAnchorNs = cadenceState.anchorNs
                hapticsCadencePacketsSent = cadenceState.packetsSent
                nextHapticsDeadlineNs = cadenceState.nextDeadlineNs
                continue
            }

            if(sendKeepalive)
            {
                val completionNs = sendHapticsReport(
                    bridge = bridge,
                    packetBuffer = packetBuffer,
                    secondPacketBuffer = null,
                    reportBuffer = reportBuffer,
                    combinedReportBuffer = combinedReportBuffer,
                    transportMode = resolveHapticsTransport(bridge),
                    sendSamples = false,
                    sendFrameCount = 1,
                    restartStream = restartHapticsStreamNow,
                    keepalive = true,
                )
                if(completionNs == null)
                {
                    synchronized(writerLock) {
                        noteHapticsSendFailureLocked(SystemClock.elapsedRealtimeNanos())
                    }
                    continue
                }
                if(restartHapticsStreamNow)
                {
                    hapticsCadenceAnchorNs = 0L
                    hapticsCadencePacketsSent = 0L
                    nextHapticsDeadlineNs = 0L
                }
                val cadenceState = advanceHapticsCadenceState(
                    previousAnchorNs = hapticsCadenceAnchorNs,
                    previousPacketsSent = hapticsCadencePacketsSent,
                    completionNs = completionNs,
                    framesSent = 1,
                    skipMissedDeadlines = true,
                )
                hapticsCadenceAnchorNs = cadenceState.anchorNs
                hapticsCadencePacketsSent = cadenceState.packetsSent
                nextHapticsDeadlineNs = cadenceState.nextDeadlineNs
            }
        }
    }

    private fun sendHapticsReport(
        bridge: DualSenseBtHidBridge,
        packetBuffer: ByteArray,
        secondPacketBuffer: ByteArray?,
        reportBuffer: ByteArray,
        combinedReportBuffer: ByteArray,
        transportMode: DualSenseBtHidBridge.OutputTransportMode,
        sendSamples: Boolean,
        sendFrameCount: Int,
        restartStream: Boolean,
        keepalive: Boolean,
    ): Long?
    {
        val attemptStartNs = SystemClock.elapsedRealtimeNanos()
        synchronized(writerLock) {
            hapticsSendAttempts += 1L
        }
        if(USE_COMBINED_BT_HAPTICS)
        {
            if(restartStream)
            {
                synchronized(writerLock) {
                    resetHapticsBufferLocked()
                    combinedHapticsRouteArmed = false
                    pendingCombinedStateFlush = true
                    lastAudioStateCarrierMs = 0L
                    hapticsSequence = 0
                }
            }

            if(!armCombinedHapticsRouteIfNeeded(bridge, transportMode))
            {
                val failureNs = SystemClock.elapsedRealtimeNanos()
                synchronized(writerLock) {
                    recordHapticsSendResultLocked(success = false, startedNs = attemptStartNs, finishedNs = failureNs)
                }
                return null
            }
            if(!sendSamples && !keepalive)
            {
                val failureNs = SystemClock.elapsedRealtimeNanos()
                synchronized(writerLock) {
                    recordHapticsSendResultLocked(success = false, startedNs = attemptStartNs, finishedNs = failureNs)
                }
                return null
            }
        }

        val sequence = synchronized(writerLock) {
            hapticsSequence and 0xFF
        }
        val concealedSamples = if(!sendSamples && keepalive)
        {
            synchronized(writerLock) {
                val gainPercent = hapticsConcealmentGainPercentLocked(SystemClock.elapsedRealtimeNanos())
                if(gainPercent > 0)
                {
                    writeFadedLastRealHapticsPacketLocked(packetBuffer, gainPercent)
                    true
                }
                else false
            }
        }
        else false
        val effectiveSendSamples = sendSamples || concealedSamples

        val combinedSnapshot: ControllerOutputSnapshot?
        val report = if(USE_COMBINED_BT_HAPTICS)
        {
            if(sendSamples && sendFrameCount == 2 && secondPacketBuffer != null)
            {
                // reuse the two-frame carrier only when no state flush is pending.
                combinedSnapshot = null
                DualSenseBtReportBuilder.buildTwoFrameHapticsCatchupReport(
                    packedHaptics1 = packetBuffer,
                    packedHaptics2 = secondPacketBuffer,
                )
            }
            else
            {
                val opusFrame = ControllerSpeakerBus.popFrame()
                if(USE_DIRECT_BT_HAPTICS_REPORT && effectiveSendSamples && sendFrameCount == 1)
                {
                    combinedSnapshot = null
                    DualSenseBtReportBuilder.writeDirectAudioHapticsReport(
                        report = combinedReportBuffer,
                        packedHaptics = packetBuffer,
                        opusFrame = opusFrame,
                    )
                }
                else
                {
                    val snapshot = synchronized(writerLock) { snapshotOutputStateLocked() }
                    combinedSnapshot = snapshot
                    DualSenseBtReportBuilder.writeCombinedControllerHapticsReport(
                        report = combinedReportBuffer,
                        state = snapshot.persistentState,
                        packedHaptics = if(effectiveSendSamples) packetBuffer else null,
                        opusFrame = opusFrame,
                        ledReady = synchronized(writerLock) { isVisualSteadyStateLocked() },
                        leftTrigger = snapshot.leftTriggerControllerData,
                        rightTrigger = snapshot.rightTriggerControllerData,
                    )
                }
                combinedReportBuffer
            }
        }
        else
        {
            combinedSnapshot = null
            if(effectiveSendSamples)
                DualSenseBtAudioHapticsBuilder.writeSampleReport(reportBuffer, sequence, packetBuffer, packetBuffer.size)
            else
                DualSenseBtAudioHapticsBuilder.writeSilenceReport(reportBuffer, sequence)
            reportBuffer
        }

        // Try fast path first; fall back to the general send path on miss so a
        // transient BT scheduler bubble doesn't silently drop the haptics packet.
        val fastSent = bridge.sendOutputReportFast(report, transportMode)
        var fallbackSent = false
        if(!fastSent)
        {
            fallbackSent = bridge.sendOutputReport(report, streaming = true, transportMode = transportMode).success
            synchronized(writerLock) {
                hapticsFastPathFallbackCount += 1L
                if(fallbackSent)
                    hapticsFastPathFallbackSuccessCount += 1L
            }
        }
        val sent = fastSent || fallbackSent
        if(!sent)
        {
            val failureNs = SystemClock.elapsedRealtimeNanos()
            synchronized(writerLock) {
                recordHapticsSendResultLocked(success = false, startedNs = attemptStartNs, finishedNs = failureNs)
            }
            return null
        }

        val nowNs = SystemClock.elapsedRealtimeNanos()
        val nowMs = SystemClock.uptimeMillis()
        synchronized(writerLock) {
            recordHapticsSendResultLocked(success = true, startedNs = attemptStartNs, finishedNs = nowNs)
            hapticsSequence = (hapticsSequence + sendFrameCount.coerceIn(1, 2)) and 0xFF
            lastSuccessfulHapticsSendNs = nowNs
            clearHapticsSendFailureLocked()
            recordBufferedHapticsSendLocked(nowNs)
            if(keepalive)
                hapticsKeepaliveSendCount += 1L
            if(sendSamples)
                rememberRealHapticsPacketLocked(
                    packet = if(sendFrameCount == 2 && secondPacketBuffer != null) secondPacketBuffer else packetBuffer,
                    nowNs = nowNs,
                )
            else if(concealedSamples)
                hapticsConcealmentPeriodsSent += 1
            if(restartStream)
                restartCombinedStreamPending = false
            combinedSnapshot?.let { snapshot ->
                markCombinedStateFlushedLocked(snapshot, nowMs)
            }
            if(sendSamples)
                repeat(sendFrameCount.coerceIn(1, 2)) {
                    advanceHapticsOutputReadLocked()
                }
            if(sendSamples && sendFrameCount >= 2)
                hapticsImmediateCatchupCount += 1L
        }
        return nowNs
    }

    private fun parkWriterUntil(deadlineNs: Long)
    {
        // Keep the spin window short on Android. Long busy-spin sections here can
        // steal time from stream decode and look like hitches/freezes.
        val SPIN_THRESHOLD_NS = 100_000L
        while(!writerStopRequested)
        {
            val remainingNs = deadlineNs - SystemClock.elapsedRealtimeNanos()
            if(remainingNs <= 0L)
                return
            if(remainingNs > 2_000_000L)
            {
                val sleepMs = (remainingNs / 1_000_000L) - 1L
                if(sleepMs > 0L)
                {
                    try
                    {
                        Thread.sleep(sleepMs)
                    }
                    catch(_: InterruptedException)
                    {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
                continue
            }
            if(remainingNs > SPIN_THRESHOLD_NS)
            {
                LockSupport.parkNanos(remainingNs - SPIN_THRESHOLD_NS)
                if(Thread.interrupted())
                    return
                continue
            }
            while(SystemClock.elapsedRealtimeNanos() < deadlineNs)
            {
                if(writerStopRequested)
                    return
                Thread.onSpinWait()
            }
            return
        }
    }

    private fun armCombinedHapticsRouteIfNeeded(
        bridge: DualSenseBtHidBridge,
        transportMode: DualSenseBtHidBridge.OutputTransportMode,
    ): Boolean
    {
        if(!USE_COMBINED_BT_HAPTICS)
            return true

        synchronized(writerLock) {
            if(combinedHapticsRouteArmed)
                return true
        }

        val startupSnapshot = synchronized(writerLock) { snapshotOutputStateLocked() }
        val triggerStateReport = startupSnapshot.triggerStateReport?.copyOf()
        val needsStartupWarmup = synchronized(writerLock) { restartCombinedStreamPending }

        // Single-packet arm: 0xFF at payload[0] enables all field sections, 0x07 at
        // REGULAR_BT_FLAG_INDEX selects speaker routing. One packet does what prime +
        // activation + ctrlFalse/ctrlTrue previously did across multiple round-trips.
        val arm = bridge.sendOutputReport(
            DualSenseBtReportBuilder.buildCombinedArmPacket(
                state = startupSnapshot.persistentState,
                triggerStateReport = triggerStateReport,
            ),
            streaming = true,
            transportMode = transportMode,
        )
        if(!arm.success)
            return false
        // One settle wait for the hardware audio route switch — still needed.
        parkWriterUntil(SystemClock.elapsedRealtimeNanos() + COMBINED_ROUTE_LED_SETTLE_MS * 1_000_000L)
        if(writerStopRequested)
            return false

        if(needsStartupWarmup)
        {
            val initResult = bridge.sendOutputReport(
                DualSenseBtReportBuilder.buildBluetoothInitPacket(),
                streaming = true,
                transportMode = transportMode,
            )
            if(!initResult.success)
                return false
            parkWriterUntil(SystemClock.elapsedRealtimeNanos() + 50_000_000L)
            if(writerStopRequested)
                return false

            val warmupSequence = synchronized(writerLock) { hapticsSequence and 0xFF }
            val silenceWarmup = bridge.sendOutputReport(
                DualSenseBtAudioHapticsBuilder.buildSilenceReport(warmupSequence, false, 6),
                streaming = true,
                transportMode = transportMode,
            )
            if(!silenceWarmup.success)
                return false
            synchronized(writerLock) {
                hapticsSequence = (hapticsSequence + 1) and 0xFF
            }
            parkWriterUntil(SystemClock.elapsedRealtimeNanos() + DualSenseBtAudioHapticsBuilder.REPORT_PERIOD_NANOS)
            if(writerStopRequested)
                return false

            if(SEND_DIRECT_36_WAKE_PACKET_ON_ARM)
            {
                val wakeReport = DualSenseBtReportBuilder.buildDirectAudioHapticsReport(
                    packedHaptics = ByteArray(BT_PACKET_SAMPLE_BYTES),
                    opusFrame = null,
                )
                val wakeResult = bridge.sendOutputReport(
                    wakeReport,
                    streaming = true,
                    transportMode = transportMode,
                )
                if(!wakeResult.success)
                    return false
                parkWriterUntil(SystemClock.elapsedRealtimeNanos() + DualSenseBtAudioHapticsBuilder.REPORT_PERIOD_NANOS)
                if(writerStopRequested)
                    return false
            }
        }

        synchronized(writerLock) {
            combinedHapticsRouteArmed = true
            pendingRouteUpdate = false
            lastSlowStateReportMs = SystemClock.uptimeMillis()
            lastRouteUpdateMs = lastSlowStateReportMs
            // Any lightbar/trigger state that arrived during the 408ms arm sequence was
            // queued into pendingFastState/pendingSlowState because combined stream did
            // not own state yet. Now that the route is armed those states are stranded:
            // fastDue is blocked (combinedHapticsRouteArmed=true) and
            // combinedStateCarrierDue needs pendingCombinedStateFlush which was never
            // set from the stuck states. Absorb them now and reset lastAudioStateCarrierMs
            // so the carrier fires immediately on the next writer loop iteration.
            if(pendingFastState != null || pendingSlowState != null)
                pendingCombinedStateFlush = true
            // Discard all haptic packets accumulated during the ~408ms arm sequence.
            // PS5 sends haptic data from t=0; by arm-complete those packets are stale.
            // Only post-arm fresh data should play. Staging is flushed below (outside lock).
            clearQueuedHapticsQueueLocked()
            pendingFastState = null
            pendingSlowState = null
            lastAudioStateCarrierMs = 0L
        }
        resetHapticsPackingState()  // flush staging PCM buffered during the arm; packetizer sees empty on next tick
        DualSenseBtSpeakerAudio.resetCounters()
        return true
    }

    private fun resetHapticsBufferLocked()
    {
        lastSuccessfulHapticsSendNs = 0L
        lastHapticsPrimeNs = 0L
        primeBeforeNextHaptics = false
        resetHapticsConcealmentLocked()
    }

    private fun recordBufferedHapticsSendLocked(@Suppress("UNUSED_PARAMETER") nowNs: Long)
    {
        // Output ring policy is enforced during enqueue. Keep send-side logic cadence-only.
    }

    private fun resetHapticsConcealmentLocked()
    {
        lastRealHapticsPacketNs = 0L
        hasLastRealHapticsPacket = false
        hapticsConcealmentPeriodsSent = 0
    }

    private fun rememberRealHapticsPacketLocked(packet: ByteArray, nowNs: Long)
    {
        System.arraycopy(packet, 0, lastRealHapticsPacket, 0, BT_PACKET_SAMPLE_BYTES)
        lastRealHapticsPacketNs = nowNs
        hasLastRealHapticsPacket = true
        hapticsConcealmentPeriodsSent = 0
    }

    private fun hapticsConcealmentGainPercentLocked(nowNs: Long): Int
    {
        if(!hasLastRealHapticsPacket)
            return 0
        if(hapticsConcealmentPeriodsSent >= HAPTICS_CONCEALMENT_MAX_PERIODS)
            return 0
        val sourceAgeNs = nowNs - lastRealHapticsPacketNs
        if(sourceAgeNs < 0L || sourceAgeNs > HAPTICS_CONCEALMENT_MAX_SOURCE_AGE_NS)
            return 0
        return if(hapticsConcealmentPeriodsSent == 0) 70 else 35
    }

    private fun writeFadedLastRealHapticsPacketLocked(target: ByteArray, gainPercent: Int)
    {
        for(i in 0 until BT_PACKET_SAMPLE_BYTES)
        {
            val sample = lastRealHapticsPacket[i].toInt()
            target[i] = ((sample * gainPercent) / 100).coerceIn(-127, 127).toByte()
        }
    }

    private fun recordHapticsSendResultLocked(success: Boolean, startedNs: Long, finishedNs: Long)
    {
        val durationNs = (finishedNs - startedNs).coerceAtLeast(0L)
        hapticsLastSendDurationNs = durationNs
        if(durationNs > hapticsMaxSendDurationNs)
            hapticsMaxSendDurationNs = durationNs
        if(durationNs >= 8_000_000L)
            hapticsSlowSendOver8MsCount += 1L
        if(durationNs >= 12_000_000L)
            hapticsSlowSendOver12MsCount += 1L
        if(durationNs >= 20_000_000L)
            hapticsSlowSendOver20MsCount += 1L
        if(durationNs >= 50_000_000L)
            hapticsSlowSendOver50MsCount += 1L
        if(success)
        {
            val lastSuccessNs = lastSuccessfulHapticsSendNs
            if(lastSuccessNs > 0L)
            {
                val intervalNs = (finishedNs - lastSuccessNs).coerceAtLeast(0L)
                hapticsLastSendIntervalNs = intervalNs
                if(intervalNs > hapticsMaxSendIntervalNs)
                    hapticsMaxSendIntervalNs = intervalNs
                if(intervalNs >= 15_000_000L)
                {
                    hapticsSendIntervalOver15MsCount += 1L
                    hapticsLastLargeSendIntervalNs = intervalNs
                    hapticsLastLargeSendIntervalTimestampNs = finishedNs
                }
                if(intervalNs >= 20_000_000L)
                    hapticsSendIntervalOver20MsCount += 1L
                if(intervalNs >= 30_000_000L)
                    hapticsSendIntervalOver30MsCount += 1L
                if(intervalNs >= 50_000_000L)
                    hapticsSendIntervalOver50MsCount += 1L
            }
            hapticsSendSuccesses += 1L
        }
        else
        {
            hapticsSendFailures += 1L
            lastFailedHapticsSendNs = finishedNs
        }
    }

    private fun noteHapticsDeadlineMissLocked(lateByNs: Long)
    {
        hapticsLastDeadlineMissNs = lateByNs
        if(lateByNs > hapticsMaxDeadlineMissNs)
            hapticsMaxDeadlineMissNs = lateByNs
        hapticsDeadlineMissCount += 1L
    }

    private fun advanceHapticsCadenceState(
        previousAnchorNs: Long,
        previousPacketsSent: Long,
        completionNs: Long,
        framesSent: Int,
        skipMissedDeadlines: Boolean,
    ): HapticsCadenceState
    {
        val periodNs = DualSenseBtAudioHapticsBuilder.REPORT_PERIOD_NANOS
        val sentFrames = framesSent.coerceAtLeast(1).toLong()
        if(previousAnchorNs <= 0L || previousPacketsSent <= 0L)
        {
            return HapticsCadenceState(
                anchorNs = completionNs,
                packetsSent = sentFrames,
                nextDeadlineNs = completionNs + sentFrames * periodNs,
            )
        }

        var packetsSent = previousPacketsSent + sentFrames
        var nextDeadlineNs = previousAnchorNs + packetsSent * periodNs
        if(skipMissedDeadlines)
        {
            var catchupCount = 0L
            while(nextDeadlineNs <= completionNs)
            {
                packetsSent += 1L
                nextDeadlineNs = previousAnchorNs + packetsSent * periodNs
                catchupCount += 1L
            }
            if(catchupCount > 0L)
                hapticsDeadlineCatchupCount += catchupCount
        }
        // If a real haptics send is late, keep nextDeadlineNs in the past. That makes
        // the writer drain actual queued packets instead of counting unsent source
        // haptics as delivered.
        return HapticsCadenceState(
            anchorNs = previousAnchorNs,
            packetsSent = packetsSent,
            nextDeadlineNs = nextDeadlineNs,
        )
    }

    private fun selectDeliveryPeriodNsLocked(): Long
    {
        return if(USE_COMBINED_BT_HAPTICS)
            DualSenseBtAudioHapticsBuilder.REPORT_PERIOD_NANOS
        else
            LIVE_STREAM_HAPTICS_PERIOD_NS
    }

    private fun maybeResetHapticsCadenceLocked(nowNs: Long): Boolean
    {
        val lastSendNs = lastSuccessfulHapticsSendNs
        if(lastSendNs == 0L)
            return false

        if(nowNs - lastSendNs < hapticsDrainResetGraceNs())
            return false

        val noBufferedSamples = hapticsOutputFrameCount == 0 && nativeHapticsStagingCountVolatile == 0

        // restart live streaming only after source and buffers go idle.
        if(!noBufferedSamples)
            return false

        // send explicit trigger-off when cadence ends.
        if(hasTriggerStateLocked())
        {
            clearTriggerStateLocked()
            pendingTriggerOffReport = true
        }

        clearQueuedHapticsQueueLocked()
        lastSuccessfulHapticsSendNs = 0L
        lastHapticsPrimeNs = 0L
        primeBeforeNextHaptics = false
        pendingCombinedStateFlush = USE_COMBINED_BT_HAPTICS
        lastAudioStateCarrierMs = 0L
        restartCombinedStreamPending = false
        hapticsSendBackoffUntilNs = 0L
        hapticsSendFailureCount = 0
        hapticsCadenceResetCount += 1L
        return true
    }

    private fun appendNativeHapticsDataLocked(data: ByteArray)
    {
        synchronized(sourcePcmLock) {
            // fixed circular buffers keep the newest samples on overflow.
            val capacity = nativeHapticsStaging.size
            if(data.size >= capacity)
            {
                val sourceOffset = data.size - capacity
                System.arraycopy(data, sourceOffset, nativeHapticsStaging, 0, capacity)
                nativeHapticsStagingHead = 0
                nativeHapticsStagingCount = capacity
                nativeHapticsStagingCountVolatile = nativeHapticsStagingCount
                noteHapticsStagingOverwriteLocked(sourceOffset)
                return
            }

            val overflowBytes = nativeHapticsStagingCount + data.size - capacity
            if(overflowBytes > 0)
                dropNativeHapticsBytesLocked(overflowBytes)

            var sourceOffset = 0
            var remaining = data.size
            while(remaining > 0)
            {
                val writeIndex = (nativeHapticsStagingHead + nativeHapticsStagingCount) % nativeHapticsStaging.size
                val writable = minOf(remaining, nativeHapticsStaging.size - writeIndex)
                System.arraycopy(data, sourceOffset, nativeHapticsStaging, writeIndex, writable)
                sourceOffset += writable
                remaining -= writable
                nativeHapticsStagingCount += writable
            }
            nativeHapticsStagingCountVolatile = nativeHapticsStagingCount
        }
        lastHapticsSourceNs = SystemClock.elapsedRealtimeNanos()
    }

    private fun copyNativeHapticsFromRingLocked(target: ByteArray, targetOffset: Int, length: Int)
    {
        if(length <= 0)
            return
        var sourceIndex = nativeHapticsStagingHead
        var destinationOffset = targetOffset
        var remaining = length
        while(remaining > 0)
        {
            val chunk = minOf(remaining, nativeHapticsStaging.size - sourceIndex)
            System.arraycopy(nativeHapticsStaging, sourceIndex, target, destinationOffset, chunk)
            sourceIndex = (sourceIndex + chunk) % nativeHapticsStaging.size
            destinationOffset += chunk
            remaining -= chunk
        }
    }

    private fun popNativeHapticsBlockLocked(target: ByteArray, length: Int)
    {
        copyNativeHapticsFromRingLocked(target, 0, length)
        nativeHapticsStagingHead = (nativeHapticsStagingHead + length) % nativeHapticsStaging.size
        nativeHapticsStagingCount -= length
        nativeHapticsStagingCountVolatile = nativeHapticsStagingCount
    }

    private fun tryPopNativeHapticsBlock(target: ByteArray, length: Int): Boolean
    {
        synchronized(sourcePcmLock) {
            if(nativeHapticsStagingCount < length)
                return false
            popNativeHapticsBlockLocked(target, length)
            return true
        }
    }

    private fun dropNativeHapticsBytesLocked(length: Int)
    {
        if(length <= 0 || nativeHapticsStagingCount <= 0)
            return
        val dropBytes = minOf(length, nativeHapticsStagingCount)
        nativeHapticsStagingHead = (nativeHapticsStagingHead + dropBytes) % nativeHapticsStaging.size
        nativeHapticsStagingCount -= dropBytes
        nativeHapticsStagingCountVolatile = nativeHapticsStagingCount
        noteHapticsStagingOverwriteLocked(dropBytes)
    }

    private fun noteHapticsStagingOverwriteLocked(bytes: Int)
    {
        if(bytes <= 0)
            return
        hapticsStagingOverwriteBytes += bytes.toLong()
        lastHapticsStagingOverwriteBytes = bytes.toLong()
        lastHapticsStagingOverwriteNs = SystemClock.elapsedRealtimeNanos()
    }

    private fun convertNativeBlockToPacket(
        source: ByteArray,
        sourceOffset: Int,
        sourceLength: Int,
        target: ByteArray,
    )
    {
        target.fill(0)

        val sourceFrames = (sourceLength / PCM16_FRAME_BYTES).coerceAtLeast(1)
        if(sourceFrames == BT_HAPTICS_OUTPUT_FRAMES)
        {
            convertNativeDirectBlockToPacket(
                source = source,
                sourceOffset = sourceOffset,
                target = target,
            )
            return
        }
        if(sourceFrames == 1)
        {
            val left = pcm16ToSigned8(readSignedInt16LE(source, sourceOffset))
            val right = pcm16ToSigned8(readSignedInt16LE(source, sourceOffset + 2))
            for(outputFrame in 0 until BT_HAPTICS_OUTPUT_FRAMES)
            {
                val outputOffset = outputFrame * 2
                target[outputOffset] = left
                target[outputOffset + 1] = right
            }
            return
        }

        resampleNativeBlockToPacket(
            source = source,
            sourceOffset = sourceOffset,
            sourceFrames = sourceFrames,
            target = target,
        )
    }

    private fun resampleNativeBlockToPacket(
        source: ByteArray,
        sourceOffset: Int,
        sourceFrames: Int,
        target: ByteArray,
    )
    {
        target.fill(0)
        val sourceToOutputRatio = sourceFrames.toDouble() / BT_HAPTICS_OUTPUT_FRAMES.toDouble()
        for(outputFrame in 0 until BT_HAPTICS_OUTPUT_FRAMES)
        {
            val center = (outputFrame + 0.5) * sourceToOutputRatio - 0.5
            val centerFrame = floor(center).toInt()
            var weightedLeft = 0.0
            var weightedRight = 0.0
            var weightSum = 0.0

            for(tap in -SINC_RESAMPLER_RADIUS..SINC_RESAMPLER_RADIUS)
            {
                val sourceFrame = (centerFrame + tap).coerceIn(0, sourceFrames - 1)
                val weight = windowedSincWeight(center - sourceFrame)
                if(weight == 0.0)
                    continue
                val sampleOffset = sourceOffset + sourceFrame * PCM16_FRAME_BYTES
                weightedLeft += readSignedInt16LE(source, sampleOffset) * weight
                weightedRight += readSignedInt16LE(source, sampleOffset + 2) * weight
                weightSum += weight
            }

            val outputOffset = outputFrame * 2
            val left = if(weightSum == 0.0) 0 else (weightedLeft / weightSum).roundToInt()
            val right = if(weightSum == 0.0) 0 else (weightedRight / weightSum).roundToInt()
            target[outputOffset] = pcm16ToSigned8Scaled(left, NATIVE_STREAM_HAPTICS_GAIN)
            target[outputOffset + 1] = pcm16ToSigned8Scaled(right, NATIVE_STREAM_HAPTICS_GAIN)
        }
    }

    private fun windowedSincWeight(distance: Double): Double
    {
        val radius = SINC_RESAMPLER_RADIUS.toDouble()
        val absoluteDistance = abs(distance)
        if(absoluteDistance >= radius)
            return 0.0
        if(absoluteDistance < 1.0e-9)
            return 1.0

        val sinc = sin(PI * distance) / (PI * distance)
        val window = 0.5 + 0.5 * cos(PI * absoluteDistance / radius)
        return sinc * window
    }

    private fun convertNativeDirectBlockToPacket(
        source: ByteArray,
        sourceOffset: Int,
        target: ByteArray,
    )
    {
        target.fill(0)
        for(outputFrame in 0 until BT_HAPTICS_OUTPUT_FRAMES)
        {
            val sampleOffset = sourceOffset + outputFrame * PCM16_FRAME_BYTES
            val outputOffset = outputFrame * 2
            target[outputOffset] = pcm16ToSigned8(readSignedInt16LE(source, sampleOffset))
            target[outputOffset + 1] = pcm16ToSigned8(readSignedInt16LE(source, sampleOffset + 2))
        }
    }

    private fun readSignedInt16LE(buffer: ByteArray, offset: Int): Int
    {
        val value = (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
        return if((value and 0x8000) != 0) value - 0x10000 else value
    }

    private fun pcm16ToSigned8(sample: Int): Byte = (sample shr 8).coerceIn(-128, 127).toByte()

    private fun pcm16ToSigned8Scaled(sample: Int, gain: Double): Byte
    {
        val scaled = ((sample shr 8) * gain).roundToInt()
        return scaled.coerceIn(-128, 127).toByte()
    }

    private fun copyPacketIntoOutputRingLocked(packet: ByteArray)
    {
        while(hapticsOutputFrameCount >= HAPTICS_OUTPUT_LEAD_TARGET_PACKETS && hapticsOutputFrameCount > 0)
        {
            hapticsOutputReadFrame = (hapticsOutputReadFrame + 1) % HAPTICS_QUEUE_PACKET_LIMIT
            hapticsOutputFrameCount -= 1
            hapticsPacketOverwriteCount += 1L
        }

        val writeFrame = if(hapticsOutputFrameCount < HAPTICS_QUEUE_PACKET_LIMIT)
        {
            val frame = (hapticsOutputReadFrame + hapticsOutputFrameCount) % HAPTICS_QUEUE_PACKET_LIMIT
            hapticsOutputFrameCount += 1
            frame
        }
        else
        {
            val frame = hapticsOutputReadFrame
            hapticsOutputReadFrame = (hapticsOutputReadFrame + 1) % HAPTICS_QUEUE_PACKET_LIMIT
            hapticsPacketOverwriteCount += 1L
            frame
        }
        val destinationOffset = writeFrame * BT_PACKET_SAMPLE_BYTES
        System.arraycopy(packet, 0, hapticsOutputFrameRing, destinationOffset, BT_PACKET_SAMPLE_BYTES)
    }

    private fun signalHapticsFramesAvailableLocked(producedPackets: Int)
    {
        if(producedPackets <= 0)
            return
        hapticsFramesAvailableGeneration += producedPackets.toLong()
        // wake the writer after every encode completion.
        writerLock.notifyAll()
    }

    private fun waitForHapticsFramesAvailableLocked()
    {
        val observedGeneration = hapticsFramesAvailableGeneration
        while(
            !writerStopRequested &&
            pendingFastState == null &&
            pendingSlowState == null &&
            !pendingRouteUpdate &&
            !pendingCombinedStateFlush &&
            hapticsOutputFrameCount == 0 &&
            hapticsFramesAvailableGeneration == observedGeneration
        ) {
            try { writerLock.wait() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
    }

    private fun clearQueuedHapticsQueueLocked()
    {
        hapticsOutputReadFrame = 0
        hapticsOutputFrameCount = 0
        resetHapticsConcealmentLocked()
    }

    private fun clearQueuedHapticsLocked(resetStaging: Boolean)
    {
        clearQueuedHapticsQueueLocked()
        if(resetStaging)
            resetHapticsPackingState()
    }

    private fun resetHapticsPackingState()
    {
        synchronized(sourcePcmLock) {
            nativeHapticsStagingHead = 0
            nativeHapticsStagingCount = 0
            nativeHapticsStagingCountVolatile = 0
        }
    }

    private fun dequeueHapticsPacketLocked(packet: ByteArray): Boolean
    {
        if(!peekHapticsPacketLocked(packet))
            return false
        advanceHapticsOutputReadLocked()
        return true
    }

    private fun peekHapticsPacketLocked(packet: ByteArray, frameOffset: Int = 0): Boolean
    {
        if(frameOffset < 0 || hapticsOutputFrameCount <= frameOffset)
            return false
        val sourceFrame = (hapticsOutputReadFrame + frameOffset) % HAPTICS_QUEUE_PACKET_LIMIT
        val sourceOffset = sourceFrame * BT_PACKET_SAMPLE_BYTES
        System.arraycopy(hapticsOutputFrameRing, sourceOffset, packet, 0, packet.size)
        return true
    }

    private fun advanceHapticsOutputReadLocked()
    {
        if(hapticsOutputFrameCount == 0)
            return
        hapticsOutputReadFrame = (hapticsOutputReadFrame + 1) % HAPTICS_QUEUE_PACKET_LIMIT
        hapticsOutputFrameCount -= 1
    }

    private fun queueLightbarFastStateLocked()
    {
        if(combinedStreamOwnsStateLocked())
        {
            pendingCombinedStateFlush = true
            return
        }
        val snapshot = snapshotOutputStateLocked()
        val semantic = semanticStateSnapshot(snapshot, PendingStateSnapshot.Kind.FAST)
        if(lastSentStateSemantic?.contentEquals(semantic) == true)
            return
        if(pendingFastState?.semantic?.contentEquals(semantic) == true)
            return
        pendingFastState = PendingStateSnapshot(
            snapshot = snapshot,
            semantic = semantic,
            kind = PendingStateSnapshot.Kind.FAST,
        )
    }

    private fun queueSlowStateLocked(force: Boolean)
    {
        if(combinedStreamOwnsStateLocked())
        {
            pendingCombinedStateFlush = true
            return
        }
        val snapshot = snapshotOutputStateLocked()
        val semantic = semanticStateSnapshot(snapshot, PendingStateSnapshot.Kind.SLOW)
        if(!force)
        {
            if(lastSentStateSemantic?.contentEquals(semantic) == true)
                return
            if(pendingFastState?.semantic?.contentEquals(semantic) == true)
                return
            if(pendingSlowState?.semantic?.contentEquals(semantic) == true)
                return
        }
        pendingSlowState = PendingStateSnapshot(
            snapshot = snapshot,
            semantic = semantic,
            kind = PendingStateSnapshot.Kind.SLOW,
        )
    }

    private fun markCombinedStateFlushedLocked(snapshot: ControllerOutputSnapshot, nowMs: Long)
    {
        val hadFastState = pendingFastState != null
        val hadSlowState = pendingSlowState != null
        val hadCombinedFlush = pendingCombinedStateFlush

        lastAudioStateCarrierMs = nowMs

        if(!hadFastState && !hadSlowState && !hadCombinedFlush)
            return

        lastPrimeTriggerStateReport = snapshot.triggerStateReport?.copyOf()
        if(hadFastState || hadCombinedFlush)
            lastFastStateReportMs = nowMs
        if(hadSlowState || hadCombinedFlush)
            lastSlowStateReportMs = nowMs

        lastSentStateSemantic = when
        {
            hadFastState || hadCombinedFlush -> semanticStateSnapshot(snapshot, PendingStateSnapshot.Kind.FAST)
            hadSlowState -> semanticStateSnapshot(snapshot, PendingStateSnapshot.Kind.SLOW)
            else -> lastSentStateSemantic
        }

        pendingFastState = null
        pendingSlowState = null
        pendingCombinedStateFlush = false
    }

    private fun sendRouteUpdate(
        bridge: DualSenseBtHidBridge,
        transportMode: DualSenseBtHidBridge.OutputTransportMode,
    ): Boolean
    {
        val shouldSend = synchronized(writerLock) {
            pendingRouteUpdate && SystemClock.uptimeMillis() - lastRouteUpdateMs >= LIGHTBAR_ROUTE_MIN_INTERVAL_MS
        }
        if(!shouldSend)
            return false

        val report = DualSenseBtReportBuilder.buildSpeakerAudioRouteOnlyReportFixed()
        val skipDueToDedupe = synchronized(writerLock) { shouldSkipStateTransportReportLocked(report) }
        if(skipDueToDedupe)
        {
            synchronized(writerLock) {
                pendingRouteUpdate = false
                lastRouteUpdateMs = SystemClock.uptimeMillis()
            }
            return true
        }
        val sent = bridge.sendOutputReportFast(report, transportMode) || bridge.sendOutputReport(
            report,
            streaming = true,
            transportMode = transportMode,
        ).success
        if(!sent)
            return false

        synchronized(writerLock) {
            pendingRouteUpdate = false
            markStateTransportReportSentLocked(report)
            lastRouteUpdateMs = SystemClock.uptimeMillis()
        }
        return true
    }

    private fun shouldPrearmCombinedRouteLocked(): Boolean
    {
        if(!USE_COMBINED_BT_HAPTICS)
            return false
        if(combinedHapticsRouteArmed || !restartCombinedStreamPending)
            return false
        if(hapticsOutputFrameCount > 0 || nativeHapticsStagingCountVolatile >= NATIVE_HAPTICS_BLOCK_BYTES_STREAM)
            return false
        if(pendingFastState != null || pendingSlowState != null)
            return false
        if(pendingTriggerOffReport || pendingCombinedStateFlush || pendingRouteUpdate)
            return false
        return true
    }

    private fun clearMatchingPendingStatesLocked(semantic: ByteArray)
    {
        if(pendingFastState?.semantic?.contentEquals(semantic) == true)
            pendingFastState = null
        if(pendingSlowState?.semantic?.contentEquals(semantic) == true)
            pendingSlowState = null
    }

    private fun hasTriggerStateLocked(): Boolean =
        outputState.triggerStateReport != null ||
            outputState.leftTriggerControllerData != null ||
            outputState.rightTriggerControllerData != null

    private fun clearTriggerStateLocked()
    {
        outputState.triggerStateReport = null
        outputState.leftTriggerControllerData = null
        outputState.rightTriggerControllerData = null
        lastPrimeTriggerStateReport = null
        lastTriggerEffectsUpdateMs = 0L
    }

    private fun ensureWriterStartedLocked()
    {
        // start the packetizer before the writer so data is ready to drain.
        ensurePacketizerStartedLocked()

        val existing = writerThread
        if(existing != null && existing.isAlive)
            return

        writerStopRequested = false
        val worker = Thread {
            try
            {
                writerLoop()
            }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finally
            {
                synchronized(writerLock) {
                    if(Thread.currentThread() === writerThread)
                        writerThread = null
                }
            }
        }.apply {
            name = "DualSenseBtWriter"
            isDaemon = true
        }
        writerThread = worker
        worker.start()
    }

    private fun stopWriterThread()
    {
        // Stop packetizer first so it does not race to enqueue while we clear buffers
        packetizerRunning = false
        synchronized(writerLock) {
            writerStopRequested = true
            clearQueuedHapticsQueueLocked()
            resetHapticsBufferLocked()
            writerLock.notifyAll()
        }
        resetHapticsPackingState()
        val worker = writerThread
        writerThread = null
        if(worker != null)
        {
            worker.interrupt()
            if(worker !== Thread.currentThread())
            {
                runCatching { worker.join(WRITER_JOIN_TIMEOUT_MS) }
            }
        }
        val packetizer = packetizerThread
        packetizerThread = null
        if(packetizer != null)
        {
            packetizer.interrupt()
            if(packetizer !== Thread.currentThread())
            {
                runCatching { packetizer.join(WRITER_JOIN_TIMEOUT_MS) }
            }
        }
    }

    private fun slowStateMinIntervalLocked(): Long
    {
        if(hasReadyHapticsFramesLocked() || isHapticsCadenceLiveLocked(SystemClock.elapsedRealtimeNanos()))
            return SLOW_STATE_REPORT_MIN_INTERVAL_DURING_HAPTICS_MS
        return FAST_STATE_REPORT_MIN_INTERVAL_MS
    }

    private fun fastStateMinIntervalLocked(): Long
    {
        if(hasReadyHapticsFramesLocked() || isHapticsCadenceLiveLocked(SystemClock.elapsedRealtimeNanos()))
            return FAST_STATE_REPORT_MIN_INTERVAL_DURING_HAPTICS_MS
        return FAST_STATE_REPORT_MIN_INTERVAL_MS
    }

    private fun combinedStreamOwnsStateLocked(): Boolean
    {
        return USE_COMBINED_BT_HAPTICS && combinedHapticsRouteArmed
    }

    private fun isVisualSteadyStateLocked(nowMs: Long = SystemClock.uptimeMillis()): Boolean
    {
        val startedMs = visualActivationStartedMs
        return startedMs != 0L && nowMs - startedMs >= VISUAL_WARMUP_MS
    }

    private fun wantsHapticsLocked(): Boolean
    {
        return hasReadyHapticsFramesLocked() ||
            isHapticsCadenceLiveLocked(SystemClock.elapsedRealtimeNanos())
    }

    private fun shouldSendKeepaliveLocked(nowNs: Long, nextHapticsDeadlineNs: Long): Boolean
    {
        if(hasReadyHapticsFramesLocked())
            return false
        if(lastSuccessfulHapticsSendNs == 0L)
            return false
        val withinGrace = isHapticsCadenceLiveLocked(nowNs)
        val producerActive = hapticsOutputFrameCount > 0 || nativeHapticsStagingCountVolatile >= NATIVE_HAPTICS_BLOCK_BYTES_STREAM
        if(!producerActive && !withinGrace)
            return false
        if(nextHapticsDeadlineNs == 0L)
            return true
        return nowNs >= nextHapticsDeadlineNs
    }

    private fun canSendQueuedHapticsLocked(): Boolean
    {
        return hapticsOutputFrameCount > 0
    }

    private fun canInterleaveStateLocked(timeUntilNextHapticsNs: Long, cadenceLive: Boolean): Boolean
    {
        if(!wantsHapticsLocked() && !cadenceLive)
            return true
        if(timeUntilNextHapticsNs <= HAPTICS_STATE_GUARD_NS)
            return false
        if(hapticsOutputFrameCount <= 0)
            return true   // ring empty - no queued haptics at risk; 1.5 ms deadline guard above already protects the window
        return true
    }

    private fun computeWriterWaitNsLocked(nowMs: Long, nowNs: Long, nextHapticsDeadlineNs: Long): Long
    {
        var waitNs = Long.MAX_VALUE

        pendingFastState?.let {
            val dueAtMs = lastFastStateReportMs + fastStateMinIntervalLocked()
            waitNs = minOf(waitNs, millisToWaitNs(dueAtMs - nowMs))
        }

        pendingSlowState?.let {
            val dueAtMs = lastSlowStateReportMs + slowStateMinIntervalLocked()
            waitNs = minOf(waitNs, millisToWaitNs(dueAtMs - nowMs))
        }

        if(pendingRouteUpdate)
        {
            val dueAtMs = lastRouteUpdateMs + LIGHTBAR_ROUTE_MIN_INTERVAL_MS
            waitNs = minOf(waitNs, millisToWaitNs(dueAtMs - nowMs))
        }

        if(pendingCombinedStateFlush)
        {
            val dueAtMs = lastAudioStateCarrierMs + fastStateMinIntervalLocked()
            waitNs = minOf(waitNs, millisToWaitNs(dueAtMs - nowMs))
        }

        if(pendingTriggerOffReport)
            waitNs = minOf(waitNs, 1L)

        if(
            (hapticsOutputFrameCount > 0 || isHapticsCadenceLiveLocked(nowNs) || shouldSendKeepaliveLocked(nowNs, nextHapticsDeadlineNs)) &&
            nextHapticsDeadlineNs > nowNs
        )
        {
            waitNs = minOf(waitNs, nextHapticsDeadlineNs - nowNs)
        }

        return waitNs
    }

    private fun millisToWaitNs(deltaMs: Long): Long
    {
        if(deltaMs <= 0L)
            return 1L
        return deltaMs * 1_000_000L
    }

    private fun waitOnWriterLockLocked(waitNs: Long)
    {
        val clampedWaitNs = waitNs.coerceAtLeast(1L)
        val waitMs = clampedWaitNs / 1_000_000L
        val waitSubNs = (clampedWaitNs % 1_000_000L).toInt()
        try { writerLock.wait(waitMs, waitSubNs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun readyBridge(): DualSenseBtHidBridge?
    {
        if(!isSupported() || !hasBluetoothPermission())
            return null
        return bridge ?: DualSenseBtHidBridge(appContext).also {
            bridge = it
            it.start()
        }
    }

    private fun getBridge(): DualSenseBtHidBridge
    {
        return bridge ?: DualSenseBtHidBridge(appContext).also {
            bridge = it
        }
    }

    private fun hasReadyHapticsFramesLocked(): Boolean
    {
        // wake the writer on encoded frames, not raw staging depth.
        return hapticsOutputFrameCount > 0
    }

    private fun hapticsKeepaliveGraceNs(): Long =
        DualSenseBtAudioHapticsBuilder.REPORT_PERIOD_NANOS * HAPTICS_KEEPALIVE_GRACE_PERIODS

    private fun hapticsDrainResetGraceNs(): Long =
        DualSenseBtAudioHapticsBuilder.REPORT_PERIOD_NANOS * HAPTICS_DRAIN_RESET_GRACE_PERIODS

    private fun isHapticsCadenceLiveLocked(nowNs: Long): Boolean
    {
        val lastSendNs = lastSuccessfulHapticsSendNs
        if(lastSendNs == 0L)
            return false
        return nowNs - lastSendNs <= hapticsKeepaliveGraceNs()
    }

    private fun noteHapticsSendFailureLocked(nowNs: Long)
    {
        hapticsSendFailureCount = (hapticsSendFailureCount + 1).coerceAtMost(8)
        val multiplier = when(hapticsSendFailureCount)
        {
            1 -> 1L
            2 -> 2L
            3 -> 4L
            4 -> 8L
            else -> 12L
        }
        val backoffNs = (HAPTICS_SEND_FAILURE_BASE_BACKOFF_NS * multiplier)
            .coerceAtMost(HAPTICS_SEND_FAILURE_MAX_BACKOFF_NS)
        hapticsSendBackoffUntilNs = nowNs + backoffNs
    }

    private fun clearHapticsSendFailureLocked()
    {
        hapticsSendFailureCount = 0
        hapticsSendBackoffUntilNs = 0L
    }

    private fun shouldPrioritizeCombinedHapticsLocked(nowNs: Long, nextHapticsDeadlineNs: Long): Boolean
    {
        if(!USE_COMBINED_BT_HAPTICS)
            return false
        if(hasReadyHapticsFramesLocked())
            return true
        if(pendingCombinedStateFlush || pendingTriggerOffReport)
            return true
        if(combinedHapticsRouteArmed && shouldSendKeepaliveLocked(nowNs, nextHapticsDeadlineNs))
            return true
        return false
    }

    private fun shouldSkipStateTransportReportLocked(report: ByteArray): Boolean
    {
        if(hasReadyHapticsFramesLocked())
            return false
        val previous = lastStateTransportReport ?: return false
        return previous.size == report.size && previous.contentEquals(report)
    }

    private fun markStateTransportReportSentLocked(report: ByteArray)
    {
        val existing = lastStateTransportReport
        if(existing != null && existing.size == report.size)
        {
            System.arraycopy(report, 0, existing, 0, report.size)
            return
        }
        lastStateTransportReport = report.copyOf()
    }

    private fun resolveStateTransport(bridge: DualSenseBtHidBridge): DualSenseBtHidBridge.OutputTransportMode
    {
        // prefer the bt interrupt/send-data lane for live control traffic.
        return firstAvailableTransport(
            bridge,
            DualSenseBtHidBridge.OutputTransportMode.SEND_DATA_RAW,
            DualSenseBtHidBridge.OutputTransportMode.SEND_DATA_HEX,
            DualSenseBtHidBridge.OutputTransportMode.SET_REPORT_RAW,
            DualSenseBtHidBridge.OutputTransportMode.SET_REPORT_HEX,
        )
    }

    private fun resolveHapticsTransport(bridge: DualSenseBtHidBridge): DualSenseBtHidBridge.OutputTransportMode
    {
        return firstAvailableTransport(
            bridge,
            DualSenseBtHidBridge.OutputTransportMode.SEND_DATA_RAW,
            DualSenseBtHidBridge.OutputTransportMode.SEND_DATA_HEX,
            DualSenseBtHidBridge.OutputTransportMode.SET_REPORT_RAW,
            DualSenseBtHidBridge.OutputTransportMode.SET_REPORT_HEX,
        )
    }

    private fun firstAvailableTransport(
        bridge: DualSenseBtHidBridge,
        vararg preferredModes: DualSenseBtHidBridge.OutputTransportMode,
    ): DualSenseBtHidBridge.OutputTransportMode
    {
        preferredModes.firstOrNull { bridge.hasTransportMode(it) }?.let { return it }
        return DualSenseBtHidBridge.OutputTransportMode.AUTO
    }

    private fun semanticStateSnapshot(snapshot: ControllerOutputSnapshot, kind: PendingStateSnapshot.Kind): ByteArray
    {
        val semantic = ByteArray(1 + 7 + 1 + 22 + 11 + 11)
        semantic[0] = kind.ordinal.toByte()
        semantic[1] = snapshot.persistentState.playerLedBrightness.toByte()
        semantic[2] = snapshot.persistentState.playerLedMask.toByte()
        semantic[3] = snapshot.persistentState.triggerSoftnessLevel.toByte()
        semantic[4] = snapshot.persistentState.softRumbleReduce.toByte()
        semantic[5] = snapshot.persistentState.lightbarRed.toByte()
        semantic[6] = snapshot.persistentState.lightbarGreen.toByte()
        semantic[7] = snapshot.persistentState.lightbarBlue.toByte()
        semantic[8] = if(snapshot.speakerRegularBtOutput) 1 else 0
        snapshot.triggerStateReport?.let { report ->
            if(report.size >= 34)
                System.arraycopy(report, 12, semantic, 9, 22)
        }
        snapshot.leftTriggerControllerData?.let { trigger ->
            System.arraycopy(trigger, 0, semantic, 31, minOf(trigger.size, 11))
        }
        snapshot.rightTriggerControllerData?.let { trigger ->
            System.arraycopy(trigger, 0, semantic, 42, minOf(trigger.size, 11))
        }
        return semantic
    }

    private fun buildReportForPendingState(state: PendingStateSnapshot): ByteArray
    {
        return DualSenseBtReportBuilder.buildStateReport(
            state = state.snapshot.persistentState,
            triggerStateReport = state.snapshot.triggerStateReport,
            speakerRegularBtOutput = false,
            ledReady = isVisualSteadyStateLocked(),
        )
    }

    private fun syncPersistentOutputStateLocked()
    {
        outputState.playerLedBrightness = PLAYER_LED_BRIGHTNESS
        outputState.triggerSoftnessLevel = triggerIntensityNibble(currentTriggerIntensity)
        outputState.softRumbleReduce = hapticIntensityNibble(currentHapticIntensity)
        // Push brightness into the report builder so the first slow state packet
        // goes out with brightness 0x01, not the default 0x00 that turns the player
        // LED off the moment connect is pressed (before setPlayerIndex arrives).
        DualSenseBtReportBuilder.setPersistentPlayerLeds(
            mask = outputState.playerLedMask,
            brightness = PLAYER_LED_BRIGHTNESS,
        )
    }

    private fun snapshotOutputStateLocked(): ControllerOutputSnapshot =
        // Keep speaker route armed when live system audio service is active,
        // so btFeedback's 0x31 state reports don't override the service's speaker arm
        outputState.snapshot(speakerRegularBtOutput = combinedHapticsRouteArmed || ControllerSystemAudioService.isRunning)

    private fun triggerStateEquals(first: ByteArray?, second: ByteArray?): Boolean
    {
        if(first == null || second == null)
            return first == null && second == null
        if(first.size < 34 || second.size < 34)
            return first.contentEquals(second)
        return first.copyOfRange(12, 34).contentEquals(second.copyOfRange(12, 34))
    }

    private fun triggerDataEquals(first: ByteArray?, second: ByteArray?): Boolean
    {
        if(first == null || second == null)
            return first == null && second == null
        return first.contentEquals(second)
    }

    private fun packControllerDataTrigger(type: Int, data: ByteArray): ByteArray
    {
        val packed = ByteArray(11)
        packed[0] = type.toByte()
        val copyLength = minOf(10, data.size)
        if(copyLength > 0)
            System.arraycopy(data, 0, packed, 1, copyLength)
        return packed
    }

    private fun hasBluetoothPermission(): Boolean
    {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            return true
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
}
