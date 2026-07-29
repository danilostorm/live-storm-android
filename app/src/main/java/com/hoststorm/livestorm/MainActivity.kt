package com.hoststorm.livestorm

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.MediaCodecInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
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
import com.pedro.library.generic.GenericStream
import java.util.Locale

class MainActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var binding: ActivityMainBinding

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        restoreProfile()
        applyRequestedOrientationForMode(lockForLive = false)
        setupPreview()
        setupActions()
        updateProfileUi()
        updateConnectionLabel()

        if (hasPermissions()) {
            prepareStream(showResult = false)
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
            if (stream.isStreaming || connecting) stopLive() else startLive()
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

        if (fps == 60 && maxSupportedFps() < 60) {
            showToast(
                "Esta câmera não entrega 60 FPS reais em ${profile.resolutionLabel}. " +
                    "Experimente 720p ou a câmera traseira."
            )
            binding.capabilityHint.text = "60 FPS indisponível nesta câmera e resolução"
            return
        }

        profile = profile.copy(fps = fps)
        saveProfile()
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
        if (stream.isStreaming || connecting) {
            showToast("Encerre a live antes de alterar qualidade, FPS ou formato.")
            return false
        }
        return true
    }

    private fun prepareStream(showResult: Boolean) {
        if (!hasPermissions()) return

        syncAutomaticOrientation()
        binding.preparingProgress.visibility = View.VISIBLE
        setProfileControlsEnabled(false)
        streamPrepared = false
        stable60Samples = 0
        low60Samples = 0
        binding.encoderFps.text = "FPS real --"

        try {
            stopAndReleaseCurrentSession()

            val cameraMaxFps = maxSupportedFps()
            if (profile.fps == 60 && cameraMaxFps < 60) {
                setStatus(Status.ERROR, "60 FPS INDISPONÍVEL")
                binding.capabilityHint.text =
                    "A câmera atual chega a $cameraMaxFps FPS em ${profile.resolutionLabel}"
                if (showResult) {
                    showToast(
                        "O app não vai fingir 60 FPS nem reduzir para 30 automaticamente. " +
                            "Escolha 720p ou troque de câmera."
                    )
                }
                return
            }

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
        if (!streamPrepared || stream.isOnPreview || !binding.cameraPreview.holder.surface.isValid) {
            return
        }
        try {
            stream.startPreview(binding.cameraPreview)
        } catch (error: Exception) {
            showToast("Não foi possível abrir a prévia: ${error.message}")
        }
    }

    private fun startLive() {
        val settings = loadStreamSettings()
        val validation = settings.validationError()
        if (validation != null) {
            showToast(validation)
            showSettingsDialog()
            return
        }

        if (profile.fps == 60 && maxSupportedFps() < 60) {
            streamPrepared = false
            setStatus(Status.ERROR, "60 FPS INDISPONÍVEL")
            showToast("A câmera atual não suporta 60 FPS reais neste perfil.")
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
        val camera = stream.videoSource as? Camera2Source ?: return
        try {
            val liveNow = stream.isStreaming || connecting
            if (!liveNow && stream.isOnPreview) stream.stopPreview()

            camera.switchCamera()
            binding.flashButton.setBackgroundResource(R.drawable.bg_icon_button)
            val supported = maxSupportedFps()

            if (profile.fps == 60 && supported < 60) {
                if (liveNow) {
                    camera.switchCamera()
                    showToast("A outra câmera não suporta 60 FPS nesta live.")
                } else {
                    streamPrepared = false
                    setStatus(Status.ERROR, "60 FPS INDISPONÍVEL")
                    binding.capabilityHint.text =
                        "Câmera selecionada: máximo de $supported FPS em ${profile.resolutionLabel}"
                    showToast(
                        "A nova câmera não suporta 60 FPS. Mantive o perfil em 60 para evitar uma live falsa em 30 FPS."
                    )
                }
            } else if (!liveNow) {
                prepareStream(showResult = false)
            } else {
                binding.capabilityHint.text =
                    "Câmera selecionada: até $supported FPS em ${profile.resolutionLabel}"
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
                    binding.capabilityHint.text =
                        "60 FPS reais validados • YouTube recebendo ${profile.resolutionLabel}60"
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

    private fun showSettingsDialog() {
        val dialogBinding = DialogStreamSettingsBinding.inflate(layoutInflater)
        val current = loadStreamSettings()
        dialogBinding.streamKeyInput.setText(current.streamKey)
        dialogBinding.serverInput.setText(current.server)

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

        binding.fps60Button.alpha = if (maxSupportedFps() >= 60) 1f else 0.55f
        binding.profileSummary.text =
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
        if (enabled && maxSupportedFps() < 60) binding.fps60Button.alpha = 0.55f
    }

    private fun updateStartButton() {
        if (stream.isStreaming || connecting) {
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
        if (stream.isStreaming || connecting) return

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

    override fun onDestroy() {
        timerHandler.removeCallbacksAndMessages(null)
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
        private const val DEFAULT_YOUTUBE_RTMPS =
            "rtmps://a.rtmps.youtube.com:443/live2"
    }
}
