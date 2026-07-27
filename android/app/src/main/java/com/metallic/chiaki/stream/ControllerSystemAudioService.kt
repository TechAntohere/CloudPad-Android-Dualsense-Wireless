// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import com.pylux.stream.R
import com.metallic.chiaki.lib.OpusEncoder
import com.metallic.chiaki.settings.DualSenseBtReportBuilder
import com.metallic.chiaki.settings.DualSenseBtSpeakerAudio

/**
 * Foreground service that captures Android system audio in real time and streams it
 * to the connected DualSense controller's built-in speaker via the BT 0x39 Opus path.
 *
 * Frames are pushed into [ControllerSpeakerBus]; [DualSenseBtStreamFeedback]'s
 * speakerLoop() drains and sends them through its own single bridge — no dual-bridge
 * conflict.
 *
 * Start with [buildStartIntent], stop with [buildStopIntent].
 * Requires API 29+ (AudioPlaybackCapture).
 */

enum class AudioOutputRoute { SPEAKER, HEADPHONE }

class ControllerSystemAudioService : Service() {

    companion object {
        private const val TAG = "CtrlSpeakerSvc"

        const val ACTION_START = "com.metallic.chiaki.ACTION_CONTROLLER_AUDIO_START"
        const val ACTION_STOP  = "com.metallic.chiaki.ACTION_CONTROLLER_AUDIO_STOP"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"

        private const val NOTIFICATION_ID   = 8842
        private const val CHANNEL_ID        = "controller_speaker_live"

        /** 48 kHz stereo — must match DualSense Opus spec */
        private const val SAMPLE_RATE   = 48_000
        private const val CHANNELS      = AudioFormat.CHANNEL_IN_STEREO
        private const val ENCODING      = AudioFormat.ENCODING_PCM_16BIT
        /** Samples per channel per Opus frame (10 ms) */
        private const val SAMPLES_PER_CHANNEL = DualSenseBtSpeakerAudio.SAMPLES_PER_CHANNEL_PER_FRAME
        /** Interleaved samples (L+R) per frame */
        private const val SAMPLES_INTERLEAVED = DualSenseBtSpeakerAudio.SAMPLES_PER_FRAME_INTERLEAVED
        /** Android playback capture is already 48 kHz; one Opus frame is exactly 480 stereo frames. */
        private const val SOURCE_SAMPLES_PER_CHANNEL = 512 // 512 source samples -> downsample to 480 to match controller 45kHz effective rate
        private const val SOURCE_INTERLEAVED = SOURCE_SAMPLES_PER_CHANNEL * 2

        @JvmStatic
        fun buildStartIntent(ctx: Context, resultCode: Int, projectionData: Intent): Intent =
            Intent(ctx, ControllerSystemAudioService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROJECTION_DATA, projectionData)
                putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
            }

        @JvmStatic
        fun buildStopIntent(ctx: Context): Intent =
            Intent(ctx, ControllerSystemAudioService::class.java).apply {
                action = ACTION_STOP
            }

        /** True while the service is actively capturing and streaming. */
        @Volatile var isRunning = false
            private set

        /**
         * Controls how the stereo Opus channels are routed to DualSense outputs.
         * The DualSense firmware hardwires: Ch0 (Left)  → 3.5 mm headphone jack (mono-upmixed to both ears)
         *                                   Ch1 (Right) → internal speaker
         * Both channels receive a (L+R)/2 mono downmix of the captured audio.
         */
        @Volatile var audioOutputRoute: AudioOutputRoute = AudioOutputRoute.SPEAKER
    }

    @Volatile private var captureThread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var captureWakeLock: PowerManager.WakeLock? = null

    // -- Lifecycle -----------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Log.w(TAG, "AudioPlaybackCapture requires API 29+; service stopping.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val projData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, -1)
                if (projData == null) {
                    Log.e(TAG, "No projection data; stopping.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
                isRunning = true
                ControllerSpeakerBus.enabled = true
                acquireCaptureWakeLock()
                // Sync volume and audio routing from prefs so service starts at the user-chosen state
                val prefs = com.metallic.chiaki.common.Preferences(applicationContext)
                DualSenseBtReportBuilder.speakerVolumePercent = prefs.controllerSpeakerVolumePercent
                DualSenseBtReportBuilder.headphoneVolumePercent = prefs.controllerHeadphoneVolumePercent
                DualSenseBtReportBuilder.headphoneMode = prefs.controllerHeadphoneOutput
                audioOutputRoute = if (prefs.controllerHeadphoneOutput) AudioOutputRoute.HEADPHONE else AudioOutputRoute.SPEAKER
                startCapture(resultCode, projData)
            }
            ACTION_STOP -> {
                ControllerSpeakerBus.enabled = false
                ControllerSpeakerBus.flush()
                stopCapture()
                releaseCaptureWakeLock()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ControllerSpeakerBus.enabled = false
        ControllerSpeakerBus.flush()
        stopCapture()
        releaseCaptureWakeLock()
        isRunning = false
    }

    private fun acquireCaptureWakeLock() {
        val existing = captureWakeLock
        if (existing?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        captureWakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chiaki:ControllerSpeakerCapture")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseCaptureWakeLock() {
        captureWakeLock?.let {
            if (it.isHeld) it.release()
        }
        captureWakeLock = null
    }

    // -- Capture -------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startCapture(resultCode: Int, projectionData: Intent) {
        if (captureThread?.isAlive == true) return

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = mpManager.getMediaProjection(resultCode, projectionData)

        stopRequested = false
        captureThread = Thread({
            runCaptureLoop(projection)
        }, "ControllerSpeakerCapture").also { it.start() }
    }

    private fun stopCapture() {
        stopRequested = true
        captureThread?.interrupt()
        captureThread = null
    }

    // -- Capture loop (runs on dedicated thread) ------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun runCaptureLoop(projection: MediaProjection) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        Log.i(TAG, "Capture loop starting")

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        val bufSize    = maxOf(minBufSize, SOURCE_INTERLEAVED * 2 * 8)

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        @SuppressLint("MissingPermission")
        val record = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNELS)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            projection.stop()
            stopSelf()
            return
        }

        // Opus encoder
        val encoder = OpusEncoder(SAMPLE_RATE, 2)
        if (!encoder.isValid) {
            Log.e(TAG, "Failed to create Opus encoder")
            record.release()
            projection.stop()
            stopSelf()
            return
        }

        val pcmFrame = ShortArray(SAMPLES_INTERLEAVED)
        val opusOut = ByteArray(DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)
        val silenceOpus = ByteArray(DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)
        val silenceBytes = encoder.encode(ShortArray(SAMPLES_INTERLEAVED), SAMPLES_PER_CHANNEL, silenceOpus, silenceOpus.size)
        if (silenceBytes > 0) {
            ControllerSpeakerBus.setSilenceFrame(silenceOpus.copyOf(silenceBytes).padTo(DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME))
        }

        record.startRecording()

        // Accumulate partial frames
        val accumulator = ShortArray(SOURCE_INTERLEAVED)
        var accumulated = 0

        try {
            while (!stopRequested && !Thread.currentThread().isInterrupted) {
                val samplesToRead = SOURCE_INTERLEAVED - accumulated
                val read = record.read(accumulator, accumulated, samplesToRead, AudioRecord.READ_BLOCKING)
                if (read > 0) accumulated += read

                if (accumulated < SOURCE_INTERLEAVED) continue

                accumulated = 0

                // 1. Downsample 512→480 stereo (corrects ~6.25% pitch artefact from DualSense 45 kHz clock)
                downsample512to480Stereo(accumulator, pcmFrame)

                // 2. Apply volume gain: headphone volume when headphone mode, else speaker volume
                val activeVolumePercent = if (audioOutputRoute == AudioOutputRoute.HEADPHONE)
                    DualSenseBtReportBuilder.headphoneVolumePercent
                else
                    DualSenseBtReportBuilder.speakerVolumePercent
                val volGain = (activeVolumePercent / 100f).coerceIn(0f, 1f)
                if (volGain < 0.99f) {
                    for (j in pcmFrame.indices) pcmFrame[j] = (pcmFrame[j] * volGain).toInt().toShort()
                }

                // 3. Apply audio routing:
                //    SPEAKER : Ch0=Ch1=mono(L+R)/2  — firmware downmixes both to mono for speaker
                //    HEADPHONE: Ch0=L, Ch1=R         — true stereo; firmware auto-mutes speaker when jack plugged
                applyOutputRouting(pcmFrame, audioOutputRoute)

                val nb = encoder.encode(pcmFrame, SAMPLES_PER_CHANNEL, opusOut, opusOut.size)

                val opus = if (nb > 0) opusOut.copyOf(nb).padTo(DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)
                           else ByteArray(DualSenseBtSpeakerAudio.OPUS_BYTES_PER_FRAME)

                // Push to speaker bus — btFeedback's speakerLoop drains and sends via its own bridge
                ControllerSpeakerBus.onSpeakerFrame(opus)
            }
        } catch (e: InterruptedException) {
            // normal stop
        } finally {
            record.stop()
            record.release()
            encoder.close()
            projection.stop()
            releaseCaptureWakeLock()
            Log.i(TAG, "Capture loop stopped")
        }
    }

    // -- Notification --------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Controller Speaker", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Live audio streaming to DualSense speaker" }
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            buildStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_rumble)
                .setContentTitle("Controller Speaker Active")
                .setContentText("Streaming system audio to DualSense")
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_rumble)
                .setContentTitle("Controller Speaker Active")
                .setContentText("Streaming system audio to DualSense")
                .setOngoing(true)
                .build()
        }
    }
    // -- Helpers --------------------------------------------------------------

    private fun copyStereoPcmFrame(input: ShortArray, inputOffset: Int, output: ShortArray) {
        System.arraycopy(input, inputOffset, output, 0, output.size)
    }

    /**
     * Resample 512 stereo input samples (captured at 48 kHz) down to 480 stereo output
     * samples. The DualSense controller plays Opus audio at an effective 45 kHz rate
     * (512 controller samples per 10.667 ms period). Encoding 480 samples representing
     * 512/48000 s of audio corrects the 6.25% pitch-down artefact.
     * Simple linear interpolation is sufficient; the 16/15 ratio is gentle.
     */
    /**
     * Route the captured stereo PCM into the correct Opus channels.
     *
     * DualSense firmware routing (from hardware, verified by daidr tester):
     *   Ch0 (Left Opus)  → 3.5 mm headphone jack  (stereo-L when jack plugged)
     *   Ch1 (Right Opus) → internal speaker AND headphone-R
     *   No headphone    → firmware downmixes Ch0+Ch1 to mono for speaker
     *   Headphone plugged → firmware auto-mutes internal speaker, routes Ch0→L, Ch1→R
     *
     * SPEAKER mode: write (L+R)/2 mono into BOTH channels so the speaker's
     *   downmix gets the full stereo mix rather than only the right channel.
     * HEADPHONE mode: leave Ch0=gameL, Ch1=gameR — true stereo for the jack.
     */
    private fun applyOutputRouting(frame: ShortArray, route: AudioOutputRoute) {
        if (route == AudioOutputRoute.SPEAKER) {
            // Mono downmix: Ch0=Ch1=(L+R)/2
            var i = 0
            while (i < frame.size) {
                val mono = ((frame[i].toInt() + frame[i + 1].toInt()) shr 1).toShort()
                frame[i]     = mono
                frame[i + 1] = mono
                i += 2
            }
        }
        // HEADPHONE: Ch0=L, Ch1=R already correct — no modification needed
    }

    private fun downsample512to480Stereo(input: ShortArray, output: ShortArray) {
        val outFrames = output.size / 2  // 480
        val inFrames  = input.size  / 2  // 512
        for (outIdx in 0 until outFrames) {
            val srcPos = outIdx.toDouble() * inFrames / outFrames  // 0..511
            val lo = srcPos.toInt().coerceAtMost(inFrames - 1)
            val hi = (lo + 1).coerceAtMost(inFrames - 1)
            val frac = (srcPos - lo).toFloat()
            output[outIdx * 2]     = (input[lo * 2]     + frac * (input[hi * 2]     - input[lo * 2]    )).toInt().toShort()
            output[outIdx * 2 + 1] = (input[lo * 2 + 1] + frac * (input[hi * 2 + 1] - input[lo * 2 + 1])).toInt().toShort()
        }
    }

    private fun ByteArray.padTo(size: Int): ByteArray =
        if (this.size >= size) this.copyOf(size) else this.copyOf(size) // copyOf zero-pads
}
