package com.hoststorm.livestorm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.MediaCodecInfo
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hoststorm.livestorm.databinding.ActivityMainBinding
import com.hoststorm.livestorm.databinding.DialogStreamSettingsBinding
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity(), ConnectChecker, ScreenStreamService.Callback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraProController: CameraProController
    private lateinit var overlayController: OverlayController

    private val stream: GenericStream by lazy {
        GenericStream(applicationContext, this).apply {
            setVideoCodec(VideoCodec.H264)
            setAudioCodec(AudioCodec.AAC)
            getGlInterface().autoHandleOrientation = false
            getStreamClient().setReTries(10)
            getStreamClient().setSocketTimeout(10_000)
            getStreamClient().setCheckServerAlive(true)
            setFpsListener { fps -> onEncodedFps(fps) }
        }
    }

    private var profile = StreamProfile()
    private var streamPrepared = false
    private var connected = false
    private var connecting = false
    private var liveStartedElapsed = 0L
    private var stable60Samples = 0
    private var low60Samples = 0
    private var sourceMode = SourceMode.CAMERA
    private var screenAudioMode = ScreenAudioMode.MIX
    private var cameraRecordFile: File? = null

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (connected && liveStartedElapsed > 0L) {
                val elapsed = (SystemClock.elapsedRealtime() - liveStartedElapsed) / 1000
                val hours = elapsed / 3600
                val minutes = (elapsed % 3600) / 60
                val seconds = elapsed % 60
                binding.liveTimer.text = String.format(
                    Locale.getDefault(),
                    "%02d:%02d:%02d",
                    hours,
                    minutes,
                    seconds
                )
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.CAMERA] == true &&
            result[Manifest.permission.RECORD_AUDIO] == true
        if (granted) {
            prepareStream(showResult = true)
        } else {
            showToast("A câmera e o microfone são obrigatórios para transmitir.")
            setStatus(Status.ERROR, "PERMISSÕES")
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startScreenService(result.resultCode, data)
        } else {
            connecting = false
            restoreAutomaticOrientationAfterLive()
            setStatus(Status.IDLE, "TELA PRONTA")
            updateStartButton()
            showToast("A permissão para capturar a tela foi cancelada.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraProController = CameraProController(
            activity = this,
            preview = binding.cameraPreview,
            focusIndicator = binding.focusIndicator,
            cameraProvider = { stream.videoSource as? Camera2Source },
            resolutionProvider = { Size(profile.width, profile.height) },
            targetFpsProvider = { profile.fps },
            onZoomChanged = { zoom -> updateZoomLabel(zoom) },
            onInfo = { message ->
                binding.capabilityHint.text = message
                showToast(message)
            }
        )
        overlayController = OverlayController(
            activity = this,
            streamProvider = { stream },
            geometryProvider = {
                OverlayController.VideoGeometry(
                    width = profile.width,
                    height = profile.height,
                    rotation = profile.rotation,
                    fps = profile.fps
                )
            },
            onInfo = { message ->
                binding.capabilityHint.text = message
                showToast(message)
            },
            onStateChanged = { active -> updateOverlayButton(active) }
        )
        cameraProController.attachPreviewControls()
        updateZoomLabel(1f)
        updateOverlayButton(overlayController.enabled)
        ScreenStreamService.callback = this

        restoreProfile()
        restoreSourceMode()
        applyRequestedOrientationForMode(lockForLive = false)
        setupPreview()
        setupActions()
        updateProfileUi()
        updateConnectionLabel()
        updateSourceUi()

        if (hasPermissions()) {
            if (sourceMode == SourceMode.CAMERA) prepareStream(showResult = false)
            else showScreenReady()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun setupPreview() {
        binding.cameraPreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startPreviewWhenReady()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                if (stream.isOnPreview) {
                    stream.getGlInterface().setPreviewResolution(width, height)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (stream.isOnPreview) stream.stopPreview()
            }
        })
    }

    private fun setupActions() {
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
        binding.startStopButton.setOnClickListener {
            if (isLiveSessionActive()) stopLive() else startLive()
        }

        binding.resolution720Button.setOnClickListener { selectResolution(1280, 720) }
        binding.resolution1080Button.setOnClickListener { selectResolution(1920, 1080) }
        binding.fps30Button.setOnClickListener { selectFps(30) }
        binding.fps60Button.setOnClickListener { selectFps(60) }
        binding.autoOrientationButton.setOnClickListener {
            selectOrientation(OrientationMode.AUTO)
        }
        binding.portraitOrientationButton.setOnClickListener {
            selectOrientation(OrientationMode.PORTRAIT)
        }
        binding.landscapeOrientationButton.setOnClickListener {
            selectOrientation(OrientationMode.LANDSCAPE)
        }

        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.flashButton.setOnClickListener { toggleFlash() }
        binding.micButton.setOnClickListener { toggleMicrophone() }
        binding.proButton.setOnClickListener { cameraProController.showProDialog() }
        binding.overlayButton.setOnClickListener { overlayController.showDialog() }
        binding.sourceButton.setOnClickListener { showSourceDialog() }
        binding.recordButton.setOnClickListener { toggleLocalRecording() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (
            sourceMode == SourceMode.CAMERA &&
            ::cameraProController.isInitialized &&
            cameraProController.handleVolumeKey(event)
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun selectResolution(width: Int, height: Int) {
        if (!canChangeProfile()) return
        if (profile.width == width && profile.height == height) return
        profile = profile.copy(width = width, height = height)
        saveProfile()
        prepareStream(showResult = true)
    }

    private fun selectFps(fps: Int) {
        if (!canChangeProfile()) return
        if (profile.fps == fps) return

        profile = profile.copy(fps = fps)
        saveProfile()
        if (fps == 60) {
            binding.capabilityHint.text = if (
                sourceMode == SourceMode.CAMERA && cameraProController.isNative60Verified()
            ) "60 FPS nativo já validado nesta câmera" else "60 FPS será validado pelos quadros reais"
        }
        prepareStream(showResult = true)
    }

    private fun selectOrientation(mode: OrientationMode) {
        if (!canChangeProfile()) return

        val resolvedVertical = when (mode) {
            OrientationMode.AUTO -> currentDeviceIsPortrait()
            OrientationMode.PORTRAIT -> true
            OrientationMode.LANDSCAPE -> false
        }
        if (profile.orientationMode == mode && profile.vertical == resolvedVertical) return

        profile = profile.copy(orientationMode = mode, vertical = resolvedVertical)
        saveProfile()
        applyRequestedOrientationForMode(lockForLive = false)
        updateProfileUi()

        // Ao sair de uma orientação fixa para Automático, damos tempo para o Android
        // aplicar a posição física atual antes de preparar novamente câmera e encoder.
        binding.root.postDelayed({
            syncAutomaticOrientation()
            prepareStream(showResult = true)
        }, if (mode == OrientationMode.AUTO) 250L else 0L)
    }

    private fun canChangeProfile(): Boolean {
        if (isLiveSessionActive() || isAnyRecording()) {
            showToast("Encerre a live ou a gravação antes de alterar qualidade, FPS ou formato.")
            return false
        }
        return true
    }

    private fun prepareStream(showResult: Boolean) {
        if (!hasPermissions() || sourceMode != SourceMode.CAMERA) {
            updateProfileUi()
            return
        }

        syncAutomaticOrientation()
        binding.preparingProgress.visibility = View.VISIBLE
        setProfileControlsEnabled(false)
        streamPrepared = false
        stable60Samples = 0
        low60Samples = 0
        binding.encoderFps.text = "FPS real --"

        try {
            stopAndReleaseCurrentSession()

            stream.setVideoCodec(VideoCodec.H264)
            stream.setAudioCodec(AudioCodec.AAC)

            val videoOk = prepareVideoEncoder()
            val audioOk = prepareAudioEncoder()
            streamPrepared = videoOk && audioOk

            if (streamPrepared) {
                startPreviewWhenReady()
                setStatus(Status.IDLE, "PRONTO")
                binding.capabilityHint.text = if (profile.fps == 60) {
                    "Perfil YouTube ${profile.resolutionLabel}60 • ${profile.aspectLabel} • keyframe 2 s"
                } else {
                    "Perfil YouTube ${profile.resolutionLabel}30 • ${profile.aspectLabel} • keyframe 2 s"
                }
                if (showResult) {
                    showToast(
                        "Perfil preparado: ${profile.resolutionLabel} a ${profile.fps} FPS, " +
                            "H.264 a ${profile.bitrateLabel}."
                    )
                }
            } else {
                setStatus(Status.ERROR, "CODIFICADOR")
                binding.capabilityHint.text =
                    "O codificador do aparelho não aceitou ${profile.resolutionLabel} a ${profile.fps} FPS"
                if (showResult) {
                    showToast(
                        "Não foi possível preparar este perfil. O FPS não foi reduzido automaticamente."
                    )
                }
            }
        } catch (error: Exception) {
            streamPrepared = false
            setStatus(Status.ERROR, "ERRO DA CÂMERA")
            binding.capabilityHint.text = error.message ?: "Falha ao preparar a câmera"
            if (showResult) {
                showToast("Falha ao preparar transmissão: ${error.message ?: "erro desconhecido"}")
            }
        } finally {
            binding.preparingProgress.visibility = View.GONE
            setProfileControlsEnabled(true)
            updateProfileUi()
        }
    }

    private fun stopAndReleaseCurrentSession() {
        if (stream.isRecording) stopCameraRecording(publish = true)
        if (::overlayController.isInitialized) overlayController.clear()
        if (::cameraProController.isInitialized) cameraProController.release()
        try {
            if (stream.isStreaming) stream.stopStream()
        } catch (_: Exception) {
        }
        try {
            if (stream.isOnPreview) stream.stopPreview()
        } catch (_: Exception) {
        }
        try {
            stream.release()
        } catch (_: Exception) {
        }
    }

    private fun prepareVideoEncoder(): Boolean {
        val preparedWithYouTubeProfile = stream.prepareVideo(
            profile.width,
            profile.height,
            profile.videoBitrate,
            profile.fps,
            2,
            profile.rotation,
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            profile.avcLevel
        )
        if (preparedWithYouTubeProfile) return true

        // Alguns fabricantes recusam o nível forçado, embora aceitem os mesmos 60 FPS.
        // A segunda tentativa preserva resolução, bitrate, keyframe e FPS; nunca cai para 30 FPS.
        stream.release()
        stream.setVideoCodec(VideoCodec.H264)
        return stream.prepareVideo(
            profile.width,
            profile.height,
            profile.videoBitrate,
            profile.fps,
            2,
            profile.rotation
        )
    }

    private fun prepareAudioEncoder(): Boolean {
        return try {
            stream.prepareAudio(44_100, true, 128_000, true, true)
        } catch (_: IllegalArgumentException) {
            stream.prepareAudio(44_100, true, 128_000, false, false)
        }
    }

    private fun startPreviewWhenReady() {
        if (
            sourceMode != SourceMode.CAMERA || !streamPrepared || stream.isOnPreview ||
            !binding.cameraPreview.holder.surface.isValid
        ) {
            return
        }
        try {
            stream.startPreview(binding.cameraPreview)
            cameraProController.applyAfterCameraStart()
            overlayController.applyIfConfigured()
        } catch (error: Exception) {
            showToast("Não foi possível abrir a prévia: ${error.message}")
        }
    }

    private fun startLive() {
        if (sourceMode == SourceMode.SCREEN) {
            startScreenLive()
            return
        }
        val settings = loadStreamSettings()
        val validation = settings.validationError()
        if (validation != null) {
            showToast(validation)
            showSettingsDialog()
            return
        }

        if (profile.orientationMode == OrientationMode.AUTO) {
            val orientationChanged = syncAutomaticOrientation()
            if (orientationChanged) streamPrepared = false
        }

        if (!streamPrepared) {
            prepareStream(showResult = true)
            if (!streamPrepared) return
        }

        applyRequestedOrientationForMode(lockForLive = true)

        try {
            stable60Samples = 0
            low60Samples = 0
            connecting = true
            connected = false
            setStatus(Status.CONNECTING, "YOUTUBE")
            setProfileControlsEnabled(false)
            binding.startStopButton.text = "■  CANCELAR CONEXÃO"
            binding.startStopButton.setBackgroundResource(R.drawable.bg_live_button_stop)
            stream.startStream(settings.endpoint())
        } catch (error: Exception) {
            connecting = false
            setProfileControlsEnabled(true)
            setStatus(Status.ERROR, "FALHA")
            updateStartButton()
            restoreAutomaticOrientationAfterLive()
            showToast("Não foi possível iniciar: ${error.message}")
        }
    }

    private fun stopLive() {
        if (sourceMode == SourceMode.SCREEN || ScreenStreamService.isStreaming()) {
            ScreenStreamService.stop(this)
            connecting = false
            connected = false
            stopTimer()
            binding.uploadBitrate.text = "Upload -- Mb/s"
            binding.encoderFps.text = "FPS real --"
            setStatus(Status.IDLE, "TELA PRONTA")
            setProfileControlsEnabled(true)
            updateStartButton()
            restoreAutomaticOrientationAfterLive()
            return
        }
        try {
            if (stream.isStreaming) stream.stopStream()
        } catch (_: Exception) {
        }
        connecting = false
        connected = false
        stable60Samples = 0
        low60Samples = 0
        stopTimer()
        binding.uploadBitrate.text = "Upload -- Mb/s"
        binding.encoderFps.text = "FPS real --"
        setStatus(Status.IDLE, "PRONTO")
        setProfileControlsEnabled(true)
        updateStartButton()
        restoreAutomaticOrientationAfterLive()
        startPreviewWhenReady()
    }

    private fun switchCamera() {
        if (sourceMode != SourceMode.CAMERA) {
            showToast("Troca de lente disponível apenas na fonte Câmera.")
            return
        }
        val camera = stream.videoSource as? Camera2Source ?: return
        try {
            val liveNow = stream.isStreaming || connecting
            if (!liveNow && stream.isOnPreview) stream.stopPreview()

            camera.switchCamera()
            binding.cameraPreview.postDelayed({ cameraProController.applyAfterCameraStart() }, 500L)
            binding.flashButton.setBackgroundResource(R.drawable.bg_icon_button)
            val supported = maxSupportedFps()
            if (!liveNow) {
                prepareStream(showResult = false)
            } else {
                binding.capabilityHint.text = if (profile.fps == 60) {
                    "Nova lente selecionada; validando 60 FPS reais..."
                } else {
                    "Câmera selecionada: até $supported FPS em ${profile.resolutionLabel}"
                }
            }
        } catch (error: Exception) {
            showToast("Não foi possível trocar a câmera: ${error.message}")
        }
    }

    private fun toggleFlash() {
        val camera = stream.videoSource as? Camera2Source ?: return
        try {
            if (camera.isLanternEnabled()) {
                camera.disableLantern()
                binding.flashButton.setBackgroundResource(R.drawable.bg_icon_button)
            } else {
                camera.enableLantern()
                if (camera.isLanternEnabled()) {
                    binding.flashButton.setBackgroundResource(R.drawable.bg_icon_button_active)
                } else {
                    showToast("O flash não está disponível nesta câmera.")
                }
            }
        } catch (error: Exception) {
            showToast("Flash indisponível: ${error.message}")
        }
    }

    private fun toggleMicrophone() {
        if (sourceMode == SourceMode.SCREEN) {
            val muted = ScreenStreamService.instance?.toggleMicrophone()
            when (muted) {
                true -> {
                    binding.micButton.text = "MUDO"
                    binding.micButton.setBackgroundResource(R.drawable.bg_icon_button_active)
                }
                false -> {
                    binding.micButton.text = "MIC"
                    binding.micButton.setBackgroundResource(R.drawable.bg_icon_button)
                }
                null -> showToast("O modo de áudio interno atual não possui microfone para silenciar.")
            }
            return
        }
        val microphone = stream.audioSource as? MicrophoneSource ?: return
        if (microphone.isMuted()) {
            microphone.unMute()
            binding.micButton.text = "MIC"
            binding.micButton.setBackgroundResource(R.drawable.bg_icon_button)
            showToast("Microfone ativado")
        } else {
            microphone.mute()
            binding.micButton.text = "MUDO"
            binding.micButton.setBackgroundResource(R.drawable.bg_icon_button_active)
            showToast("Microfone silenciado")
        }
    }

    private fun maxSupportedFps(): Int {
        return try {
            val camera = stream.videoSource as? Camera2Source ?: return 30
            camera.getMaxSupportedFps(Size(profile.width, profile.height))
        } catch (_: Exception) {
            30
        }
    }

    private fun onEncodedFps(fps: Int) {
        runOnUiThread {
            binding.encoderFps.text = "FPS real $fps"
            binding.encoderFps.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (profile.fps == 60 && fps < 55) R.color.storm_warning else R.color.storm_green
                )
            )

            if (!connected || profile.fps != 60) return@runOnUiThread

            if (fps >= 55) {
                stable60Samples++
                low60Samples = 0
            } else {
                low60Samples++
                stable60Samples = 0
            }

            when {
                stable60Samples >= 3 -> {
                    if (
                        sourceMode == SourceMode.CAMERA && stable60Samples == 3 &&
                        cameraProController.markNative60Verified()
                    ) {
                        showToast("60 FPS nativo confirmado e salvo para esta câmera.")
                    }
                    binding.capabilityHint.text =
                        "60 FPS nativos validados • YouTube recebendo ${profile.resolutionLabel}60"
                    binding.capabilityHint.setTextColor(
                        ContextCompat.getColor(this, R.color.storm_green)
                    )
                }

                low60Samples >= 5 -> {
                    binding.capabilityHint.text =
                        "Atenção: saída caiu para $fps FPS; reduza aquecimento ou use 720p60"
                    binding.capabilityHint.setTextColor(
                        ContextCompat.getColor(this, R.color.storm_warning)
                    )
                }

                else -> {
                    binding.capabilityHint.text = "Validando 60 FPS reais com o codificador..."
                    binding.capabilityHint.setTextColor(
                        ContextCompat.getColor(this, R.color.storm_muted)
                    )
                }
            }
        }
    }

    private fun isLiveSessionActive(): Boolean {
        return stream.isStreaming || ScreenStreamService.isStreaming() || connecting
    }

    private fun isAnyRecording(): Boolean {
        return stream.isRecording || ScreenStreamService.isRecording()
    }

    private fun showSourceDialog() {
        if (isLiveSessionActive() || isAnyRecording()) {
            showToast("Encerre a live ou a gravação antes de trocar a fonte.")
            return
        }
        val items = arrayOf(
            "Câmera do celular",
            "Tela/Jogo • áudio do app + microfone",
            "Tela/Jogo • somente áudio do app",
            "Tela/Jogo • somente microfone"
        )
        val selected = when {
            sourceMode == SourceMode.CAMERA -> 0
            screenAudioMode == ScreenAudioMode.MIX -> 1
            screenAudioMode == ScreenAudioMode.INTERNAL -> 2
            else -> 3
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Fonte da transmissão")
            .setSingleChoiceItems(items, selected) { dialog, which ->
                when (which) {
                    0 -> setSourceMode(SourceMode.CAMERA, screenAudioMode)
                    1 -> setSourceMode(SourceMode.SCREEN, ScreenAudioMode.MIX)
                    2 -> setSourceMode(SourceMode.SCREEN, ScreenAudioMode.INTERNAL)
                    3 -> setSourceMode(SourceMode.SCREEN, ScreenAudioMode.MICROPHONE)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setSourceMode(mode: SourceMode, audioMode: ScreenAudioMode) {
        sourceMode = mode
        screenAudioMode = audioMode
        saveSourceMode()
        if (mode == SourceMode.CAMERA) {
            binding.screenModeOverlay.visibility = View.GONE
            binding.micButton.text = "MIC"
            prepareStream(showResult = false)
        } else {
            if (stream.isOnPreview) runCatching { stream.stopPreview() }
            overlayController.clear()
            showScreenReady()
        }
        updateSourceUi()
        updateProfileUi()
        updateStartButton()
    }

    private fun showScreenReady() {
        binding.screenModeOverlay.visibility = View.VISIBLE
        setStatus(Status.IDLE, "TELA PRONTA")
        binding.capabilityHint.text = when (screenAudioMode) {
            ScreenAudioMode.MIX -> "Tela/Jogo: áudio interno + microfone"
            ScreenAudioMode.INTERNAL -> "Tela/Jogo: somente áudio interno"
            ScreenAudioMode.MICROPHONE -> "Tela/Jogo: somente microfone"
        }
    }

    private fun updateSourceUi() {
        binding.sourceButton.text = if (sourceMode == SourceMode.CAMERA) "CÂMERA" else "TELA / JOGO"
        binding.screenModeOverlay.visibility = if (sourceMode == SourceMode.SCREEN) View.VISIBLE else View.GONE
        binding.proButton.alpha = if (sourceMode == SourceMode.CAMERA) 1f else 0.45f
        binding.proButton.isEnabled = sourceMode == SourceMode.CAMERA
        binding.flashButton.alpha = if (sourceMode == SourceMode.CAMERA) 1f else 0.45f
        binding.flashButton.isEnabled = sourceMode == SourceMode.CAMERA
    }

    private fun startScreenLive() {
        val settings = loadStreamSettings()
        val validation = settings.validationError()
        if (validation != null) {
            showToast(validation)
            showSettingsDialog()
            return
        }
        if (profile.orientationMode == OrientationMode.AUTO) syncAutomaticOrientation()
        applyRequestedOrientationForMode(lockForLive = true)
        connecting = true
        connected = false
        setStatus(Status.CONNECTING, "AUTORIZAR TELA")
        updateStartButton()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startScreenService(resultCode: Int, data: Intent) {
        val settings = loadStreamSettings()
        ScreenStreamService.callback = this
        val intent = ScreenStreamService.startIntent(
            context = this,
            resultCode = resultCode,
            resultData = data,
            endpoint = settings.endpoint(),
            width = profile.width,
            height = profile.height,
            fps = profile.fps,
            bitrate = profile.videoBitrate,
            rotation = profile.rotation,
            audioMode = screenAudioMode
        )
        ContextCompat.startForegroundService(this, intent)
        setStatus(Status.CONNECTING, "TELA")
    }

    private fun toggleLocalRecording() {
        if (sourceMode == SourceMode.SCREEN) {
            val service = ScreenStreamService.instance
            if (service == null || !service.isStreamingNow()) {
                showToast("Inicie a live da tela antes de ativar a gravação local.")
                return
            }
            service.toggleRecord()
            return
        }
        if (!streamPrepared) {
            prepareStream(showResult = true)
            if (!streamPrepared) return
        }
        if (stream.isRecording) stopCameraRecording(publish = true) else startCameraRecording()
    }

    private fun startCameraRecording() {
        val file = LocalRecordingUtils.createTempFile(this, "LiveStorm_Camera")
        cameraRecordFile = file
        try {
            stream.startRecord(file.absolutePath) { status ->
                runOnUiThread {
                    if (status == RecordController.Status.RECORDING) {
                        updateRecordButton(true)
                        showToast("Gravação local iniciada")
                    }
                }
            }
        } catch (error: Exception) {
            cameraRecordFile = null
            showToast("Não foi possível iniciar a gravação: ${error.message}")
        }
    }

    private fun stopCameraRecording(publish: Boolean) {
        if (!stream.isRecording) return
        runCatching { stream.stopRecord() }
        updateRecordButton(false)
        val file = cameraRecordFile
        cameraRecordFile = null
        if (publish && file != null && file.exists()) {
            LocalRecordingUtils.publish(this, file) { path ->
                showToast("Gravação salva em $path")
            }
        }
    }

    private fun updateRecordButton(recording: Boolean) {
        binding.recordButton.text = if (recording) "● GRAVANDO" else "● REC LOCAL"
        binding.recordButton.setBackgroundResource(
            if (recording) R.drawable.bg_live_button_stop else R.drawable.bg_chip
        )
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogStreamSettingsBinding.inflate(layoutInflater)
        val current = loadStreamSettings()
        dialogBinding.streamKeyInput.setText(current.streamKey)
        dialogBinding.serverInput.setText(current.server)
        dialogBinding.connectYoutubeButton.setOnClickListener { showYoutubeConnectionInfo() }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Transmissão direta para o YouTube")
            .setView(dialogBinding.root)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("URL padrão", null)
            .setPositiveButton("Salvar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialogBinding.serverInput.setText(DEFAULT_YOUTUBE_RTMPS)
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newSettings = StreamSettings(
                    server = dialogBinding.serverInput.text.toString().trim().trimEnd('/'),
                    streamKey = dialogBinding.streamKeyInput.text.toString().trim()
                )
                val error = newSettings.validationError()
                if (error != null) {
                    showToast(error)
                } else {
                    saveStreamSettings(newSettings)
                    updateConnectionLabel()
                    dialog.dismiss()
                    showToast("Chave do YouTube salva somente neste aparelho.")
                }
            }
        }
        dialog.show()
    }

    private fun showYoutubeConnectionInfo() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Conectar conta YouTube")
            .setMessage(
                "Uma chave de API não permite acessar ou criar lives na sua conta. " +
                    "O Google exige login OAuth 2.0 com o escopo youtube.force-ssl, " +
                    "um Client ID Android vinculado ao pacote do Live Storm e ao SHA-1 da assinatura.\n\n" +
                    "A transmissão por chave RTMPS continua funcionando. O login automático será " +
                    "ativado quando o Client ID do projeto Google Cloud for cadastrado no aplicativo."
            )
            .setNeutralButton("Abrir YouTube Studio") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://studio.youtube.com")))
                }
            }
            .setPositiveButton("Entendi", null)
            .create()
        dialog.show()
    }

    private fun updateZoomLabel(zoom: Float) {
        binding.zoomLabel.text = String.format(Locale.getDefault(), "%.1f×", zoom)
    }

    private fun updateOverlayButton(active: Boolean) {
        binding.overlayButton.text = if (active) "WEB✓" else "WEB"
        binding.overlayButton.setBackgroundResource(
            if (active) R.drawable.bg_icon_button_active else R.drawable.bg_icon_button
        )
    }

    private fun updateProfileUi() {
        setChip(binding.resolution720Button, profile.width == 1280)
        setChip(binding.resolution1080Button, profile.width == 1920)
        setChip(binding.fps30Button, profile.fps == 30)
        setChip(binding.fps60Button, profile.fps == 60)
        setChip(binding.autoOrientationButton, profile.orientationMode == OrientationMode.AUTO)
        setChip(
            binding.portraitOrientationButton,
            profile.orientationMode == OrientationMode.PORTRAIT
        )
        setChip(
            binding.landscapeOrientationButton,
            profile.orientationMode == OrientationMode.LANDSCAPE
        )

        binding.fps60Button.alpha = 1f
        binding.profileSummary.text =
            "${if (sourceMode == SourceMode.CAMERA) "Câmera" else "Tela"} • " +
                "${profile.resolutionLabel} • ${profile.fps} FPS • ${profile.aspectLabel} • ${profile.bitrateLabel}"
    }

    private fun setChip(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        view.setTextColor(Color.WHITE)
    }

    private fun setProfileControlsEnabled(enabled: Boolean) {
        listOf(
            binding.resolution720Button,
            binding.resolution1080Button,
            binding.fps30Button,
            binding.fps60Button,
            binding.autoOrientationButton,
            binding.portraitOrientationButton,
            binding.landscapeOrientationButton
        ).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.45f
        }
    }

    private fun updateStartButton() {
        if (isLiveSessionActive()) {
            binding.startStopButton.text = "■  ENCERRAR LIVE"
            binding.startStopButton.setBackgroundResource(R.drawable.bg_live_button_stop)
        } else {
            binding.startStopButton.text = "●  INICIAR NO YOUTUBE"
            binding.startStopButton.setBackgroundResource(R.drawable.bg_live_button)
        }
    }

    private fun updateConnectionLabel() {
        val settings = loadStreamSettings()
        if (settings.validationError() == null) {
            binding.connectionLabel.text = "YOUTUBE PRONTO"
            binding.connectionLabel.setTextColor(
                ContextCompat.getColor(this, R.color.storm_green)
            )
        } else {
            binding.connectionLabel.text = "COLE SUA CHAVE"
            binding.connectionLabel.setTextColor(
                ContextCompat.getColor(this, R.color.storm_warning)
            )
        }
    }

    private fun setStatus(status: Status, text: String) {
        binding.statusPill.text = when (status) {
            Status.LIVE -> "● AO VIVO"
            Status.CONNECTING -> "● $text"
            Status.ERROR -> "! $text"
            Status.IDLE -> "● $text"
        }
        binding.statusPill.setBackgroundResource(
            if (status == Status.LIVE) R.drawable.bg_status_live else R.drawable.bg_status_idle
        )
        binding.statusPill.setTextColor(
            when (status) {
                Status.ERROR -> ContextCompat.getColor(this, R.color.storm_warning)
                Status.CONNECTING -> Color.rgb(255, 220, 150)
                else -> Color.WHITE
            }
        )
    }

    private fun startTimer() {
        liveStartedElapsed = SystemClock.elapsedRealtime()
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.post(timerRunnable)
    }

    private fun stopTimer() {
        timerHandler.removeCallbacks(timerRunnable)
        liveStartedElapsed = 0L
        binding.liveTimer.text = "00:00:00"
    }

    private fun currentDeviceIsPortrait(
        configuration: Configuration = resources.configuration
    ): Boolean {
        return configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
    }

    private fun syncAutomaticOrientation(): Boolean {
        if (profile.orientationMode != OrientationMode.AUTO) return false
        val verticalNow = currentDeviceIsPortrait()
        if (profile.vertical == verticalNow) return false

        profile = profile.copy(vertical = verticalNow)
        saveProfile()
        updateProfileUi()
        return true
    }

    private fun applyRequestedOrientationForMode(lockForLive: Boolean) {
        requestedOrientation = when (profile.orientationMode) {
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.AUTO -> {
                if (!lockForLive) {
                    ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                } else if (profile.vertical) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
    }

    private fun restoreAutomaticOrientationAfterLive() {
        if (profile.orientationMode == OrientationMode.AUTO) {
            applyRequestedOrientationForMode(lockForLive = false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::binding.isInitialized) return
        if (profile.orientationMode != OrientationMode.AUTO) return
        if (isLiveSessionActive()) return

        val verticalNow = currentDeviceIsPortrait(newConfig)
        if (profile.vertical == verticalNow) {
            updateProfileUi()
            return
        }

        profile = profile.copy(vertical = verticalNow)
        saveProfile()
        updateProfileUi()
        binding.root.post { prepareStream(showResult = false) }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onConnectionStarted(url: String) {
        runOnUiThread {
            connecting = true
            setStatus(Status.CONNECTING, "YOUTUBE")
        }
    }

    override fun onConnectionSuccess() {
        runOnUiThread {
            connecting = false
            connected = true
            setStatus(Status.LIVE, "AO VIVO")
            setProfileControlsEnabled(false)
            updateStartButton()
            startTimer()
            binding.capabilityHint.setTextColor(
                ContextCompat.getColor(this, R.color.storm_muted)
            )
            binding.capabilityHint.text = if (profile.fps == 60) {
                "Conectado ao YouTube; validando os 60 FPS reais..."
            } else {
                "Conectado diretamente ao YouTube em ${profile.resolutionLabel}30"
            }
            showToast("Conectado diretamente ao YouTube. Nenhum servidor HostStorm foi usado.")
        }
    }

    override fun onConnectionFailed(reason: String) {
        if (stream.getStreamClient().reTry(3_000, reason, null)) {
            runOnUiThread {
                connecting = true
                connected = false
                setStatus(Status.CONNECTING, "RECONECTANDO")
            }
        } else {
            runOnUiThread {
                stopLive()
                setStatus(Status.ERROR, "SEM CONEXÃO")
                showToast("Falha na transmissão para o YouTube: $reason")
            }
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        runOnUiThread {
            binding.uploadBitrate.text = String.format(
                Locale.getDefault(),
                "Upload %.2f Mb/s",
                bitrate / 1_000_000f
            )
        }
    }

    override fun onDisconnect() {
        runOnUiThread {
            if (connected || connecting) {
                connected = false
                connecting = false
                stopTimer()
                restoreAutomaticOrientationAfterLive()
                setStatus(Status.IDLE, "DESCONECTADO")
                setProfileControlsEnabled(true)
                updateStartButton()
            }
        }
    }

    override fun onAuthError() {
        runOnUiThread {
            stopLive()
            setStatus(Status.ERROR, "CHAVE RECUSADA")
            showToast("O YouTube recusou a chave ou o endereço de transmissão.")
        }
    }

    override fun onAuthSuccess() {
        // O YouTube normalmente confirma a conexão em onConnectionSuccess().
    }

    override fun onScreenConnectionStarted() {
        runOnUiThread {
            connecting = true
            setStatus(Status.CONNECTING, "TELA")
        }
    }

    override fun onScreenConnectionSuccess() {
        runOnUiThread {
            connecting = false
            connected = true
            setStatus(Status.LIVE, "AO VIVO")
            setProfileControlsEnabled(false)
            updateStartButton()
            startTimer()
            binding.capabilityHint.text = if (profile.fps == 60) {
                "Tela conectada; validando 60 FPS reais..."
            } else {
                "Tela conectada diretamente ao YouTube"
            }
            showToast("Transmissão da tela iniciada. Abra o jogo que deseja mostrar.")
        }
    }

    override fun onScreenConnectionFailed(reason: String) {
        runOnUiThread {
            connecting = false
            connected = false
            stopTimer()
            restoreAutomaticOrientationAfterLive()
            setStatus(Status.ERROR, "FALHA NA TELA")
            setProfileControlsEnabled(true)
            updateStartButton()
            showToast("Falha na transmissão da tela: $reason")
        }
    }

    override fun onScreenBitrate(bitrate: Long) {
        runOnUiThread {
            binding.uploadBitrate.text = String.format(
                Locale.getDefault(),
                "Upload %.2f Mb/s",
                bitrate / 1_000_000f
            )
        }
    }

    override fun onScreenFps(fps: Int) {
        onEncodedFps(fps)
    }

    override fun onScreenDisconnected() {
        runOnUiThread {
            connecting = false
            connected = false
            stopTimer()
            restoreAutomaticOrientationAfterLive()
            setStatus(Status.IDLE, "TELA PRONTA")
            setProfileControlsEnabled(true)
            updateStartButton()
            updateRecordButton(false)
        }
    }

    override fun onScreenRecordingChanged(recording: Boolean, savedPath: String?) {
        runOnUiThread {
            updateRecordButton(recording)
            if (savedPath != null) showToast("Gravação salva em $savedPath")
        }
    }

    override fun onScreenMessage(message: String) {
        runOnUiThread {
            binding.capabilityHint.text = message
            showToast(message)
        }
    }

    override fun onDestroy() {
        timerHandler.removeCallbacksAndMessages(null)
        if (ScreenStreamService.callback === this) ScreenStreamService.callback = null
        if (stream.isRecording) stopCameraRecording(publish = true)
        if (::overlayController.isInitialized) overlayController.release()
        if (::cameraProController.isInitialized) cameraProController.release()
        try {
            stream.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun loadStreamSettings(): StreamSettings {
        val prefs = getSharedPreferences(PREF_STREAM_CONFIG, Context.MODE_PRIVATE)
        return StreamSettings(
            server = prefs.getString("server", DEFAULT_YOUTUBE_RTMPS)
                ?: DEFAULT_YOUTUBE_RTMPS,
            streamKey = prefs.getString("stream_key", "") ?: ""
        )
    }

    private fun saveStreamSettings(settings: StreamSettings) {
        getSharedPreferences(PREF_STREAM_CONFIG, Context.MODE_PRIVATE)
            .edit()
            .putString("server", settings.server)
            .putString("stream_key", settings.streamKey)
            .apply()
    }

    private fun restoreSourceMode() {
        val prefs = getSharedPreferences(PREF_SOURCE_MODE, Context.MODE_PRIVATE)
        sourceMode = runCatching {
            SourceMode.valueOf(prefs.getString("source", SourceMode.CAMERA.name) ?: SourceMode.CAMERA.name)
        }.getOrDefault(SourceMode.CAMERA)
        screenAudioMode = runCatching {
            ScreenAudioMode.valueOf(
                prefs.getString("screen_audio", ScreenAudioMode.MIX.name) ?: ScreenAudioMode.MIX.name
            )
        }.getOrDefault(ScreenAudioMode.MIX)
    }

    private fun saveSourceMode() {
        getSharedPreferences(PREF_SOURCE_MODE, Context.MODE_PRIVATE)
            .edit()
            .putString("source", sourceMode.name)
            .putString("screen_audio", screenAudioMode.name)
            .apply()
    }

    private fun restoreProfile() {
        val prefs = getSharedPreferences(PREF_PROFILE, Context.MODE_PRIVATE)
        val legacyVertical = prefs.getBoolean("vertical", false)
        val fallbackMode = if (legacyVertical) OrientationMode.PORTRAIT else OrientationMode.LANDSCAPE
        val orientationMode = runCatching {
            OrientationMode.valueOf(
                prefs.getString("orientation_mode", fallbackMode.name) ?: fallbackMode.name
            )
        }.getOrDefault(fallbackMode)
        val resolvedVertical = when (orientationMode) {
            OrientationMode.AUTO -> currentDeviceIsPortrait()
            OrientationMode.PORTRAIT -> true
            OrientationMode.LANDSCAPE -> false
        }

        profile = StreamProfile(
            width = prefs.getInt("width", 1920),
            height = prefs.getInt("height", 1080),
            fps = prefs.getInt("fps", 60).let { if (it == 30) 30 else 60 },
            orientationMode = orientationMode,
            vertical = resolvedVertical
        )
    }

    private fun saveProfile() {
        getSharedPreferences(PREF_PROFILE, Context.MODE_PRIVATE)
            .edit()
            .putInt("width", profile.width)
            .putInt("height", profile.height)
            .putInt("fps", profile.fps)
            .putString("orientation_mode", profile.orientationMode.name)
            .putBoolean("vertical", profile.vertical)
            .apply()
    }

    private data class StreamProfile(
        val width: Int = 1920,
        val height: Int = 1080,
        val fps: Int = 60,
        val orientationMode: OrientationMode = OrientationMode.LANDSCAPE,
        val vertical: Boolean = false
    ) {
        val rotation: Int get() = if (vertical) 90 else 0
        val resolutionLabel: String get() = if (width >= 1920) "1080p" else "720p"
        val aspectLabel: String
            get() = when (orientationMode) {
                OrientationMode.AUTO -> "Auto → ${if (vertical) "9:16" else "16:9"}"
                OrientationMode.PORTRAIT -> "Retrato 9:16"
                OrientationMode.LANDSCAPE -> "Paisagem 16:9"
            }
        val videoBitrate: Int
            get() = when {
                width >= 1920 && fps >= 60 -> 12_000_000
                width >= 1920 -> 10_000_000
                fps >= 60 -> 6_000_000
                else -> 4_000_000
            }
        val bitrateLabel: String get() = "${videoBitrate / 1_000_000} Mb/s"
        val avcLevel: Int
            get() = when {
                width >= 1920 && fps >= 60 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42
                width >= 1920 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
                fps >= 60 -> MediaCodecInfo.CodecProfileLevel.AVCLevel32
                else -> MediaCodecInfo.CodecProfileLevel.AVCLevel31
            }
    }

    private data class StreamSettings(
        val server: String,
        val streamKey: String
    ) {
        fun endpoint(): String = "${server.trimEnd('/')}/${streamKey.trim()}"

        fun validationError(): String? {
            if (!server.startsWith("rtmps://", ignoreCase = true)) {
                return "Use o URL RTMPS copiado da Sala de Controle ao Vivo do YouTube."
            }
            if (!streamKey.matches(Regex("[A-Za-z0-9_-]{8,200}"))) {
                return "Cole uma chave de transmissão válida do YouTube, sem espaços."
            }
            return null
        }
    }

    private enum class OrientationMode { AUTO, PORTRAIT, LANDSCAPE }

    private enum class Status { IDLE, CONNECTING, LIVE, ERROR }

    companion object {
        private const val PREF_STREAM_CONFIG = "youtube_stream_config"
        private const val PREF_PROFILE = "youtube_stream_profile"
        private const val PREF_SOURCE_MODE = "stream_source_mode"
        private const val DEFAULT_YOUTUBE_RTMPS =
            "rtmps://a.rtmps.youtube.com:443/live2"
    }
}
