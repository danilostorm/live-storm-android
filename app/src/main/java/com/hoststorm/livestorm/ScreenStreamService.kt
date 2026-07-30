package com.hoststorm.livestorm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import java.io.File

class ScreenStreamService : Service(), ConnectChecker {

    interface Callback {
        fun onScreenConnectionStarted()
        fun onScreenConnectionSuccess()
        fun onScreenConnectionFailed(reason: String)
        fun onScreenBitrate(bitrate: Long)
        fun onScreenFps(fps: Int)
        fun onScreenDisconnected()
        fun onScreenRecordingChanged(recording: Boolean, savedPath: String? = null)
        fun onScreenMessage(message: String)
    }

    private lateinit var stream: GenericStream
    private var projection: MediaProjection? = null
    private var recordingFile: File? = null
    private var audioMode = ScreenAudioMode.MIX

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            callback?.onScreenMessage("A captura de tela foi encerrada pelo Android.")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startProjection(intent)
        }
        return START_STICKY
    }

    private fun startProjection(intent: Intent) {
        startProjectionForeground()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = projectionIntent(intent)
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        val width = intent.getIntExtra(EXTRA_WIDTH, 1920)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1080)
        val fps = intent.getIntExtra(EXTRA_FPS, 60)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 12_000_000)
        val rotation = intent.getIntExtra(EXTRA_ROTATION, 0)
        audioMode = runCatching {
            ScreenAudioMode.valueOf(intent.getStringExtra(EXTRA_AUDIO_MODE) ?: ScreenAudioMode.MIX.name)
        }.getOrDefault(ScreenAudioMode.MIX)

        if (resultCode == 0 || resultData == null || endpoint.isBlank()) {
            callback?.onScreenConnectionFailed("Dados da captura de tela incompletos.")
            stopSelf()
            return
        }

        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("O Android não entregou a autorização da tela")
            projection?.registerCallback(projectionCallback, null)

            stream = GenericStream(applicationContext, this, NoVideoSource(), MicrophoneSource()).apply {
                setVideoCodec(VideoCodec.H264)
                setAudioCodec(AudioCodec.AAC)
                getGlInterface().setForceRender(true, fps)
                getStreamClient().setReTries(10)
                getStreamClient().setSocketTimeout(10_000)
                getStreamClient().setCheckServerAlive(true)
                setFpsListener { value -> callback?.onScreenFps(value) }
            }

            val videoPrepared = stream.prepareVideo(
                width,
                height,
                bitrate,
                fps,
                2,
                rotation
            )
            val audioPrepared = try {
                stream.prepareAudio(44_100, true, 128_000, true, true)
            } catch (_: IllegalArgumentException) {
                stream.prepareAudio(44_100, true, 128_000, false, false)
            }
            if (!videoPrepared || !audioPrepared) error("O codificador não aceitou o perfil de tela selecionado")

            val mediaProjection = projection ?: error("Projeção de tela indisponível")
            stream.changeVideoSource(ScreenSource(applicationContext, mediaProjection))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when (audioMode) {
                    ScreenAudioMode.MIX -> stream.changeAudioSource(MixAudioSource(mediaProjection))
                    ScreenAudioMode.INTERNAL -> stream.changeAudioSource(InternalAudioSource(mediaProjection))
                    ScreenAudioMode.MICROPHONE -> Unit
                }
            } else if (audioMode != ScreenAudioMode.MICROPHONE) {
                callback?.onScreenMessage("Áudio interno exige Android 10 ou superior; usando microfone.")
            }
            stream.startStream(endpoint)
        } catch (error: Exception) {
            callback?.onScreenConnectionFailed(error.message ?: "Falha ao iniciar captura da tela")
            stopSelf()
        }
    }

    fun isStreamingNow(): Boolean = ::stream.isInitialized && stream.isStreaming

    fun isRecordingNow(): Boolean = ::stream.isInitialized && stream.isRecording

    fun toggleRecord(): Boolean {
        if (!::stream.isInitialized || !stream.isStreaming) {
            callback?.onScreenMessage("Inicie a live da tela antes de gravar.")
            return false
        }
        return if (!stream.isRecording) {
            val file = LocalRecordingUtils.createTempFile(this, "LiveStorm_Tela")
            recordingFile = file
            stream.startRecord(file.absolutePath) { status ->
                if (status == RecordController.Status.RECORDING) {
                    callback?.onScreenRecordingChanged(true)
                }
            }
            true
        } else {
            stopRecordingAndPublish()
            false
        }
    }

    fun toggleMicrophone(): Boolean? {
        if (!::stream.isInitialized) return null
        return when (val source = stream.audioSource) {
            is MicrophoneSource -> {
                if (source.isMuted()) source.unMute() else source.mute()
                source.isMuted()
            }
            is MixAudioSource -> {
                if (source.isMuted()) source.unMute() else source.mute()
                source.isMuted()
            }
            else -> null
        }
    }

    private fun stopRecordingAndPublish() {
        if (!::stream.isInitialized || !stream.isRecording) return
        runCatching { stream.stopRecord() }
        val file = recordingFile
        recordingFile = null
        callback?.onScreenRecordingChanged(false)
        if (file != null && file.exists()) {
            LocalRecordingUtils.publish(this, file) { path ->
                callback?.onScreenRecordingChanged(false, path)
            }
        }
    }

    override fun onDestroy() {
        stopRecordingAndPublish()
        if (::stream.isInitialized) {
            runCatching { if (stream.isStreaming) stream.stopStream() }
            runCatching { stream.release() }
        }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
        if (instance === this) instance = null
        callback?.onScreenDisconnected()
        super.onDestroy()
    }

    override fun onConnectionStarted(url: String) {
        callback?.onScreenConnectionStarted()
    }

    override fun onConnectionSuccess() {
        callback?.onScreenConnectionSuccess()
    }

    override fun onConnectionFailed(reason: String) {
        if (::stream.isInitialized && stream.getStreamClient().reTry(3_000, reason, null)) {
            callback?.onScreenMessage("Reconectando transmissão da tela...")
        } else {
            callback?.onScreenConnectionFailed(reason)
            stopSelf()
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        callback?.onScreenBitrate(bitrate)
    }

    override fun onDisconnect() {
        callback?.onScreenDisconnected()
    }

    override fun onAuthError() {
        callback?.onScreenConnectionFailed("O YouTube recusou a chave de transmissão.")
        stopSelf()
    }

    override fun onAuthSuccess() = Unit

    private fun startProjectionForeground() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Live Storm transmitindo a tela")
            .setContentText("Toque para voltar ao controle da transmissão")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Transmissão da tela",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun projectionIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    companion object {
        private const val CHANNEL_ID = "live_storm_screen_stream"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_START = "com.hoststorm.livestorm.START_SCREEN"
        private const val ACTION_STOP = "com.hoststorm.livestorm.STOP_SCREEN"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_ENDPOINT = "endpoint"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_FPS = "fps"
        private const val EXTRA_BITRATE = "bitrate"
        private const val EXTRA_ROTATION = "rotation"
        private const val EXTRA_AUDIO_MODE = "audio_mode"

        @Volatile
        var instance: ScreenStreamService? = null
            private set

        @Volatile
        var callback: Callback? = null

        fun startIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            endpoint: String,
            width: Int,
            height: Int,
            fps: Int,
            bitrate: Int,
            rotation: Int,
            audioMode: ScreenAudioMode
        ): Intent = Intent(context, ScreenStreamService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            putExtra(EXTRA_ENDPOINT, endpoint)
            putExtra(EXTRA_WIDTH, width)
            putExtra(EXTRA_HEIGHT, height)
            putExtra(EXTRA_FPS, fps)
            putExtra(EXTRA_BITRATE, bitrate)
            putExtra(EXTRA_ROTATION, rotation)
            putExtra(EXTRA_AUDIO_MODE, audioMode.name)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenStreamService::class.java).setAction(ACTION_STOP))
        }

        fun isStreaming(): Boolean = instance?.isStreamingNow() == true

        fun isRecording(): Boolean = instance?.isRecordingNow() == true
    }
}
