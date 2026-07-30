package com.hoststorm.livestorm

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.pedro.encoder.input.sources.video.Camera2Source
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Controles de câmera que continuam funcionando durante a prévia e a transmissão.
 *
 * O modo de 60 FPS nativo tenta a faixa real de 60 quadros mesmo quando o
 * fabricante omite esse perfil da consulta Camera2. A validação é memorizada por
 * câmera e resolução depois que o encoder sustenta 55+ FPS durante a live.
 */
class CameraProController(
    private val activity: AppCompatActivity,
    private val preview: View,
    private val focusIndicator: View,
    private val cameraProvider: () -> Camera2Source?,
    private val resolutionProvider: () -> Size,
    private val targetFpsProvider: () -> Int,
    private val onZoomChanged: (Float) -> Unit,
    private val onInfo: (String) -> Unit
) {

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var focusMode = runCatching {
        FocusMode.valueOf(prefs.getString(KEY_FOCUS_MODE, FocusMode.AUTO.name) ?: FocusMode.AUTO.name)
    }.getOrDefault(FocusMode.AUTO)

    private var savedZoom = prefs.getFloat(KEY_ZOOM, 1f).coerceAtLeast(1f)
    private var manualFocusProgress = prefs.getInt(KEY_MANUAL_FOCUS, 0).coerceIn(0, 1000)
    private var exposureValue = prefs.getInt(KEY_EXPOSURE, 0)
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var multiTouchUsed = false

    private var volumeZoomEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_ZOOM, true)
        set(value) {
            prefs.edit().putBoolean(KEY_VOLUME_ZOOM, value).apply()
        }

    private val scaleDetector = ScaleGestureDetector(
        activity,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                multiTouchUsed = true
                val camera = runningCamera() ?: return false
                val current = camera.getZoom().takeIf { it > 0f } ?: 1f
                setZoom(current * detector.scaleFactor)
                return true
            }
        }
    )

    fun attachPreviewControls() {
        preview.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    multiTouchUsed = false
                }

                MotionEvent.ACTION_POINTER_DOWN -> multiTouchUsed = true

                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.x - touchDownX) + abs(event.y - touchDownY)
                    if (!multiTouchUsed && moved < activity.resources.displayMetrics.density * 22f) {
                        focusAt(view, event)
                    }
                }
            }
            true
        }
    }

    fun handleVolumeKey(event: KeyEvent): Boolean {
        if (!volumeZoomEnabled || event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        val camera = runningCamera() ?: return false
        val range = camera.getZoomRange()
        val step = ((range.upper - range.lower) / 24f).coerceAtLeast(0.08f)
        val direction = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1f else -1f
        setZoom((camera.getZoom().takeIf { it > 0f } ?: range.lower) + step * direction)
        return true
    }

    fun applyAfterCameraStart() {
        preview.postDelayed({
            val camera = runningCamera() ?: return@postDelayed
            runCatching { setZoom(savedZoom) }
            runCatching { camera.setExposure(exposureValue) }
            applyFocusMode(showMessage = false)
            applyStabilizationFromPrefs()
            if (targetFpsProvider() == 60) {
                applyNative60Request()
            }
        }, 450L)
    }

    fun release() {
        runCatching { cameraProvider()?.setCustomOnCaptureCompletedCallback(null) }
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun showProDialog() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_camera_pro, null)
        val diagnosticText = view.findViewById<TextView>(R.id.cameraDiagnosticText)
        val zoomValueText = view.findViewById<TextView>(R.id.zoomValueText)
        val zoomSeek = view.findViewById<SeekBar>(R.id.zoomSeek)
        val focusAutoButton = view.findViewById<TextView>(R.id.focusAutoButton)
        val focusLockButton = view.findViewById<TextView>(R.id.focusLockButton)
        val focusManualButton = view.findViewById<TextView>(R.id.focusManualButton)
        val manualFocusSeek = view.findViewById<SeekBar>(R.id.manualFocusSeek)
        val manualFocusValue = view.findViewById<TextView>(R.id.manualFocusValue)
        val exposureSeek = view.findViewById<SeekBar>(R.id.exposureSeek)
        val exposureValueText = view.findViewById<TextView>(R.id.exposureValueText)
        val oisSwitch = view.findViewById<SwitchMaterial>(R.id.oisSwitch)
        val eisSwitch = view.findViewById<SwitchMaterial>(R.id.eisSwitch)
        val volumeZoomSwitch = view.findViewById<SwitchMaterial>(R.id.volumeZoomSwitch)
        val native60Info = view.findViewById<TextView>(R.id.native60Info)
        val preset1Button = view.findViewById<TextView>(R.id.preset1Button)
        val preset2Button = view.findViewById<TextView>(R.id.preset2Button)
        val preset3Button = view.findViewById<TextView>(R.id.preset3Button)

        diagnosticText.text = buildDiagnostics()
        setupZoomControls(zoomSeek, zoomValueText)
        setupFocusControls(
            focusAutoButton,
            focusLockButton,
            focusManualButton,
            manualFocusSeek,
            manualFocusValue
        )
        setupExposureControls(exposureSeek, exposureValueText)

        val camera = runningCamera()
        oisSwitch.isChecked = camera?.isOpticalVideoStabilizationEnabled() == true
        eisSwitch.isChecked = camera?.isVideoStabilizationEnabled() == true
        volumeZoomSwitch.isChecked = volumeZoomEnabled
        native60Info.text = native60Status()
        setupPresetButton(preset1Button, 1)
        setupPresetButton(preset2Button, 2)
        setupPresetButton(preset3Button, 3)

        oisSwitch.setOnCheckedChangeListener { button, checked ->
            val currentCamera = runningCamera()
            val success = if (checked) {
                runCatching { currentCamera?.enableOpticalVideoStabilization() == true }.getOrDefault(false)
            } else {
                runCatching { currentCamera?.disableOpticalVideoStabilization() }
                prefs.edit().putBoolean(KEY_OIS, false).apply()
                true
            }
            if (checked && !success) {
                button.isChecked = false
                onInfo("A câmera atual não disponibilizou estabilização óptica para aplicativos.")
            } else {
                prefs.edit().putBoolean(KEY_OIS, checked).apply()
            }
        }

        eisSwitch.setOnCheckedChangeListener { button, checked ->
            val currentCamera = runningCamera()
            val success = if (checked) {
                runCatching { currentCamera?.enableVideoStabilization() == true }.getOrDefault(false)
            } else {
                runCatching { currentCamera?.disableVideoStabilization() }
                prefs.edit().putBoolean(KEY_EIS, false).apply()
                true
            }
            if (checked && !success) {
                button.isChecked = false
                onInfo("A câmera atual não disponibilizou estabilização eletrônica.")
            } else {
                prefs.edit().putBoolean(KEY_EIS, checked).apply()
            }
        }

        volumeZoomSwitch.setOnCheckedChangeListener { _, checked ->
            volumeZoomEnabled = checked
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("Modo Pro da câmera")
            .setView(view)
            .setPositiveButton("Concluir", null)
            .show()
    }

    private fun setupZoomControls(seekBar: SeekBar, valueText: TextView) {
        val camera = runningCamera()
        val range = runCatching { camera?.getZoomRange() ?: Range(1f, 1f) }
            .getOrDefault(Range(1f, 1f))
        seekBar.max = 1000
        val current = (camera?.getZoom()?.takeIf { it > 0f } ?: savedZoom)
            .coerceIn(range.lower, range.upper)
        seekBar.progress = zoomToProgress(current, range)
        valueText.text = formatZoom(current)
        seekBar.isEnabled = range.upper > range.lower
        seekBar.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val zoom = progressToZoom(progress, range)
                setZoom(zoom)
                valueText.text = formatZoom(zoom)
            }
        })
    }

    private fun setupFocusControls(
        autoButton: TextView,
        lockButton: TextView,
        manualButton: TextView,
        manualSeek: SeekBar,
        manualValue: TextView
    ) {
        fun refresh() {
            setSelected(autoButton, focusMode == FocusMode.AUTO)
            setSelected(lockButton, focusMode == FocusMode.TOUCH_LOCK)
            setSelected(manualButton, focusMode == FocusMode.MANUAL)
            manualSeek.isEnabled = focusMode == FocusMode.MANUAL && maxFocusDistance() > 0f
            manualSeek.alpha = if (manualSeek.isEnabled) 1f else 0.45f
        }

        autoButton.setOnClickListener {
            focusMode = FocusMode.AUTO
            persistFocusMode()
            applyFocusMode(showMessage = true)
            refresh()
        }
        lockButton.setOnClickListener {
            focusMode = FocusMode.TOUCH_LOCK
            persistFocusMode()
            applyFocusMode(showMessage = true)
            refresh()
        }
        manualButton.setOnClickListener {
            focusMode = FocusMode.MANUAL
            persistFocusMode()
            applyManualFocus(manualFocusProgress)
            refresh()
        }

        manualSeek.max = 1000
        manualSeek.progress = manualFocusProgress
        manualValue.text = manualFocusLabel(manualFocusProgress)
        manualSeek.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                manualFocusProgress = progress
                prefs.edit().putInt(KEY_MANUAL_FOCUS, progress).apply()
                manualValue.text = manualFocusLabel(progress)
                if (fromUser && focusMode == FocusMode.MANUAL) applyManualFocus(progress)
            }
        })
        refresh()
    }

    private fun setupExposureControls(seekBar: SeekBar, valueText: TextView) {
        val range = exposureRange()
        if (range == null) {
            seekBar.isEnabled = false
            valueText.text = "Indisponível"
            return
        }
        exposureValue = exposureValue.coerceIn(range.lower, range.upper)
        seekBar.max = range.upper - range.lower
        seekBar.progress = exposureValue - range.lower
        valueText.text = signed(exposureValue)
        seekBar.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                exposureValue = range.lower + progress
                prefs.edit().putInt(KEY_EXPOSURE, exposureValue).apply()
                runningCamera()?.setExposure(exposureValue)
                valueText.text = signed(exposureValue)
            }
        })
    }

    private fun focusAt(view: View, event: MotionEvent) {
        val camera = runningCamera() ?: return
        if (focusMode == FocusMode.MANUAL) {
            onInfo("Foco manual ativo. Use o controle Pro para alterar a distância.")
            showFocusIndicator(event.x, event.y, false)
            return
        }

        var capturedDistance: Float? = null
        if (focusMode == FocusMode.TOUCH_LOCK) {
            camera.setCustomOnCaptureCompletedCallback { _, _, result ->
                result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { capturedDistance = it }
            }
        }

        val focused = runCatching { camera.tapToFocus(view, event) }.getOrDefault(false)
        showFocusIndicator(event.x, event.y, focused)
        if (!focused) {
            onInfo("O foco por toque não está disponível nesta lente.")
            return
        }

        if (focusMode == FocusMode.TOUCH_LOCK) {
            mainHandler.postDelayed({
                camera.setCustomOnCaptureCompletedCallback(null)
                val distance = capturedDistance
                val locked = if (distance != null) {
                    camera.setCustomRequest { request ->
                        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        request.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                    }
                } else {
                    camera.disableAutoFocus()
                }
                onInfo(if (locked) "Foco travado no ponto selecionado" else "Não foi possível travar o foco")
            }, 900L)
        } else {
            mainHandler.postDelayed({ camera.enableAutoFocus() }, 1300L)
        }
    }

    private fun applyFocusMode(showMessage: Boolean) {
        val camera = runningCamera() ?: return
        val success = when (focusMode) {
            FocusMode.AUTO -> camera.enableAutoFocus()
            FocusMode.TOUCH_LOCK -> camera.enableAutoFocus()
            FocusMode.MANUAL -> applyManualFocus(manualFocusProgress)
        }
        if (showMessage) {
            val message = when (focusMode) {
                FocusMode.AUTO -> "Foco contínuo para vídeo ativado"
                FocusMode.TOUCH_LOCK -> "Toque na imagem para focar e travar"
                FocusMode.MANUAL -> "Foco manual ativado"
            }
            onInfo(if (success) message else "Este modo de foco não é suportado pela lente atual")
        }
    }

    private fun applyManualFocus(progress: Int): Boolean {
        val camera = runningCamera() ?: return false
        val maximum = maxFocusDistance()
        if (maximum <= 0f) return false
        val distance = maximum * (progress.coerceIn(0, 1000) / 1000f)
        return camera.setCustomRequest { request ->
            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
        }
    }

    private fun setZoom(value: Float) {
        val camera = runningCamera() ?: return
        val range = camera.getZoomRange()
        val zoom = value.coerceIn(range.lower, range.upper)
        camera.setZoom(zoom)
        savedZoom = zoom
        prefs.edit().putFloat(KEY_ZOOM, zoom).apply()
        onZoomChanged(zoom)
    }

    private fun applyNative60Request() {
        val camera = runningCamera() ?: return
        val success = runCatching {
            camera.setCustomRequest { request ->
                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(60, 60))
            }
        }.getOrDefault(false)
        if (!isNative60Verified()) {
            onInfo(
                if (success) "60 FPS nativo solicitado; a live vai validar os quadros reais."
                else "O driver recusou a faixa nativa de 60 FPS nesta lente."
            )
        }
    }

    fun markNative60Verified(): Boolean {
        val key = native60Key() ?: return false
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }

    fun isNative60Verified(): Boolean {
        val key = native60Key() ?: return false
        return prefs.getBoolean(key, false)
    }

    private fun native60Status(): String {
        return if (isNative60Verified()) {
            "60 FPS nativo validado nesta câmera e resolução."
        } else {
            "60 FPS será solicitado diretamente e validado pelo contador real da live."
        }
    }

    private fun native60Key(): String? {
        val camera = cameraProvider() ?: return null
        val id = runCatching { camera.getCurrentCameraId() }.getOrNull() ?: return null
        val size = resolutionProvider()
        return "${KEY_NATIVE_60_PREFIX}${id}_${size.width}x${size.height}"
    }

    private fun setupPresetButton(button: TextView, slot: Int) {
        val savedKey = "${KEY_PRESET_PREFIX}${slot}_saved"
        fun refresh() {
            button.text = if (prefs.getBoolean(savedKey, false)) "PERFIL $slot ✓" else "PERFIL $slot"
        }
        button.setOnClickListener {
            if (!prefs.getBoolean(savedKey, false)) {
                onInfo("O Perfil $slot ainda está vazio. Pressione e segure para salvar.")
                return@setOnClickListener
            }
            focusMode = runCatching {
                FocusMode.valueOf(
                    prefs.getString("${KEY_PRESET_PREFIX}${slot}_focus", FocusMode.AUTO.name)
                        ?: FocusMode.AUTO.name
                )
            }.getOrDefault(FocusMode.AUTO)
            savedZoom = prefs.getFloat("${KEY_PRESET_PREFIX}${slot}_zoom", 1f)
            manualFocusProgress = prefs.getInt("${KEY_PRESET_PREFIX}${slot}_manual", 0)
            exposureValue = prefs.getInt("${KEY_PRESET_PREFIX}${slot}_exposure", 0)
            volumeZoomEnabled = prefs.getBoolean("${KEY_PRESET_PREFIX}${slot}_volume", true)
            prefs.edit()
                .putString(KEY_FOCUS_MODE, focusMode.name)
                .putFloat(KEY_ZOOM, savedZoom)
                .putInt(KEY_MANUAL_FOCUS, manualFocusProgress)
                .putInt(KEY_EXPOSURE, exposureValue)
                .putBoolean(KEY_OIS, prefs.getBoolean("${KEY_PRESET_PREFIX}${slot}_ois", false))
                .putBoolean(KEY_EIS, prefs.getBoolean("${KEY_PRESET_PREFIX}${slot}_eis", false))
                .apply()
            applyAfterCameraStart()
            onInfo("Perfil Pro $slot carregado")
        }
        button.setOnLongClickListener {
            prefs.edit()
                .putBoolean(savedKey, true)
                .putString("${KEY_PRESET_PREFIX}${slot}_focus", focusMode.name)
                .putFloat("${KEY_PRESET_PREFIX}${slot}_zoom", savedZoom)
                .putInt("${KEY_PRESET_PREFIX}${slot}_manual", manualFocusProgress)
                .putInt("${KEY_PRESET_PREFIX}${slot}_exposure", exposureValue)
                .putBoolean("${KEY_PRESET_PREFIX}${slot}_volume", volumeZoomEnabled)
                .putBoolean("${KEY_PRESET_PREFIX}${slot}_ois", prefs.getBoolean(KEY_OIS, false))
                .putBoolean("${KEY_PRESET_PREFIX}${slot}_eis", prefs.getBoolean(KEY_EIS, false))
                .apply()
            refresh()
            onInfo("Configuração atual salva no Perfil Pro $slot")
            true
        }
        refresh()
    }

    private fun applyStabilizationFromPrefs() {
        val camera = runningCamera() ?: return
        if (prefs.getBoolean(KEY_OIS, false)) runCatching { camera.enableOpticalVideoStabilization() }
        if (prefs.getBoolean(KEY_EIS, false)) runCatching { camera.enableVideoStabilization() }
    }

    private fun buildDiagnostics(): String {
        val camera = cameraProvider() ?: return "Câmera ainda não iniciada"
        return runCatching {
            val id = camera.getCurrentCameraId()
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val ranges = characteristics
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.joinToString { "${it.lower}-${it.upper}" }
                ?: "não informado"
            val size = resolutionProvider()
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val duration = map?.getOutputMinFrameDuration(SurfaceTexture::class.java, size) ?: 0L
            val sizeFps = if (duration > 0L) (1_000_000_000L / duration).toInt() else 0
            val libraryFps = camera.getMaxSupportedFps(size)
            val focusMax = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            "Câmera ID $id • ${size.width}×${size.height}\n" +
                "Camera2: até $libraryFps FPS neste tamanho" +
                (if (sizeFps > 0) " • duração indica ~$sizeFps FPS" else "") +
                "\nFaixas AE: $ranges\nFoco manual: " +
                (if (focusMax > 0f) "sim (${String.format(Locale.US, "%.2f", focusMax)} D)" else "não exposto") +
                "\n60 FPS nativo: " + (if (isNative60Verified()) "validado" else "aguardando teste em live")
        }.getOrElse { "Não foi possível ler o diagnóstico: ${it.message}" }
    }

    private fun maxFocusDistance(): Float {
        return runCatching {
            val camera = cameraProvider() ?: return 0f
            cameraManager.getCameraCharacteristics(camera.getCurrentCameraId())
                .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        }.getOrDefault(0f)
    }

    private fun exposureRange(): Range<Int>? {
        return runCatching {
            val camera = cameraProvider() ?: return null
            cameraManager.getCameraCharacteristics(camera.getCurrentCameraId())
                .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        }.getOrNull()
    }

    private fun runningCamera(): Camera2Source? {
        val camera = cameraProvider() ?: return null
        return camera.takeIf { it.isRunning() }
    }

    private fun showFocusIndicator(x: Float, y: Float, success: Boolean) {
        focusIndicator.animate().cancel()
        focusIndicator.translationX = (x - focusIndicator.width / 2f)
            .coerceIn(0f, (preview.width - focusIndicator.width).coerceAtLeast(0).toFloat())
        focusIndicator.translationY = (y - focusIndicator.height / 2f)
            .coerceIn(0f, (preview.height - focusIndicator.height).coerceAtLeast(0).toFloat())
        focusIndicator.alpha = 1f
        focusIndicator.visibility = View.VISIBLE
        focusIndicator.isActivated = success
        focusIndicator.animate()
            .alpha(0f)
            .setStartDelay(700L)
            .setDuration(350L)
            .withEndAction { focusIndicator.visibility = View.GONE }
            .start()
    }

    private fun persistFocusMode() {
        prefs.edit().putString(KEY_FOCUS_MODE, focusMode.name).apply()
    }

    private fun setSelected(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        view.alpha = if (selected) 1f else 0.82f
    }

    private fun zoomToProgress(value: Float, range: Range<Float>): Int {
        if (range.upper <= range.lower) return 0
        return (((value - range.lower) / (range.upper - range.lower)) * 1000f)
            .roundToInt().coerceIn(0, 1000)
    }

    private fun progressToZoom(progress: Int, range: Range<Float>): Float {
        return range.lower + (range.upper - range.lower) * (progress.coerceIn(0, 1000) / 1000f)
    }

    private fun formatZoom(value: Float): String = String.format(Locale.getDefault(), "%.1f×", value)

    private fun manualFocusLabel(progress: Int): String {
        val max = maxFocusDistance()
        if (max <= 0f) return "Indisponível"
        val distance = max * (progress / 1000f)
        return if (progress == 0) "∞ Infinito" else String.format(Locale.getDefault(), "%.2f D", distance)
    }

    private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

    private open class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = Unit
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }

    private enum class FocusMode { AUTO, TOUCH_LOCK, MANUAL }

    companion object {
        private const val PREFS = "camera_pro_settings"
        private const val KEY_FOCUS_MODE = "focus_mode"
        private const val KEY_MANUAL_FOCUS = "manual_focus"
        private const val KEY_EXPOSURE = "exposure"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_OIS = "ois"
        private const val KEY_EIS = "eis"
        private const val KEY_VOLUME_ZOOM = "volume_zoom"
        private const val KEY_NATIVE_60_PREFIX = "native_60_"
        private const val KEY_PRESET_PREFIX = "preset_"
    }
}
