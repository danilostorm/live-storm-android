from pathlib import Path
import xml.etree.ElementTree as ET


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 bloco, encontrado {count}")
    return text.replace(old, new, 1)


root = Path(__file__).resolve().parents[1]
main_path = root / "app/src/main/java/com/hoststorm/livestorm/MainActivity.kt"
layout_path = root / "app/src/main/res/layout/activity_main.xml"
settings_path = root / "app/src/main/res/layout/dialog_stream_settings.xml"
readme_path = root / "README.md"

main = main_path.read_text(encoding="utf-8")
main = replace_once(
    main,
    "import android.content.Context\n",
    "import android.content.Context\nimport android.content.Intent\n",
    "import Intent",
)
main = replace_once(
    main,
    "import android.media.MediaCodecInfo\n",
    "import android.media.MediaCodecInfo\nimport android.net.Uri\n",
    "import Uri",
)
main = replace_once(
    main,
    "import android.view.SurfaceHolder\n",
    "import android.view.KeyEvent\nimport android.view.SurfaceHolder\n",
    "import KeyEvent",
)
main = replace_once(
    main,
    "    private lateinit var binding: ActivityMainBinding\n",
    "    private lateinit var binding: ActivityMainBinding\n" \
    "    private lateinit var cameraProController: CameraProController\n" \
    "    private lateinit var overlayController: OverlayController\n",
    "controladores",
)

old_on_create = """        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        restoreProfile()
"""
new_on_create = """        binding = ActivityMainBinding.inflate(layoutInflater)
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
            },
            onExperimental60Changed = {
                updateProfileUi()
                if (profile.fps == 60 && !stream.isStreaming && !connecting) {
                    prepareStream(showResult = true)
                }
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

        restoreProfile()
"""
main = replace_once(main, old_on_create, new_on_create, "onCreate")

old_actions = """        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.flashButton.setOnClickListener { toggleFlash() }
        binding.micButton.setOnClickListener { toggleMicrophone() }
    }
"""
new_actions = """        binding.switchCameraButton.setOnClickListener { switchCamera() }
        binding.flashButton.setOnClickListener { toggleFlash() }
        binding.micButton.setOnClickListener { toggleMicrophone() }
        binding.proButton.setOnClickListener { cameraProController.showProDialog() }
        binding.overlayButton.setOnClickListener { overlayController.showDialog() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::cameraProController.isInitialized && cameraProController.handleVolumeKey(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }
"""
main = replace_once(main, old_actions, new_actions, "ações Pro")

main = replace_once(
    main,
    "        if (fps == 60 && maxSupportedFps() < 60) {\n",
    "        if (fps == 60 && maxSupportedFps() < 60 && !cameraProController.experimental60Enabled) {\n",
    "bloqueio selectFps",
)
main = replace_once(
    main,
    "            if (profile.fps == 60 && cameraMaxFps < 60) {\n",
    "            if (profile.fps == 60 && cameraMaxFps < 60 && !cameraProController.experimental60Enabled) {\n",
    "bloqueio prepare",
)
main = replace_once(
    main,
    "        if (profile.fps == 60 && maxSupportedFps() < 60) {\n",
    "        if (profile.fps == 60 && maxSupportedFps() < 60 && !cameraProController.experimental60Enabled) {\n",
    "bloqueio start",
)

main = replace_once(
    main,
    """    private fun stopAndReleaseCurrentSession() {
        try {
""",
    """    private fun stopAndReleaseCurrentSession() {
        if (::overlayController.isInitialized) overlayController.clear()
        if (::cameraProController.isInitialized) cameraProController.release()
        try {
""",
    "liberação dos controladores",
)

main = replace_once(
    main,
    "            stream.startPreview(binding.cameraPreview)\n",
    """            stream.startPreview(binding.cameraPreview)
            cameraProController.applyAfterCameraStart()
            overlayController.applyIfConfigured()
""",
    "aplicar Pro na prévia",
)

main = replace_once(
    main,
    """            camera.switchCamera()
            binding.flashButton.setBackgroundResource(R.drawable.bg_icon_button)
""",
    """            camera.switchCamera()
            binding.cameraPreview.postDelayed({ cameraProController.applyAfterCameraStart() }, 500L)
            binding.flashButton.setBackgroundResource(R.drawable.bg_icon_button)
""",
    "restaurar Pro após trocar câmera",
)

main = replace_once(
    main,
    """        dialogBinding.streamKeyInput.setText(current.streamKey)
        dialogBinding.serverInput.setText(current.server)

        val dialog = MaterialAlertDialogBuilder(this)
""",
    """        dialogBinding.streamKeyInput.setText(current.streamKey)
        dialogBinding.serverInput.setText(current.server)
        dialogBinding.connectYoutubeButton.setOnClickListener { showYoutubeConnectionInfo() }

        val dialog = MaterialAlertDialogBuilder(this)
""",
    "botão conectar YouTube",
)

insert_before_ui = """    private fun updateProfileUi() {
"""
new_helpers = """    private fun showYoutubeConnectionInfo() {
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
"""
main = replace_once(main, insert_before_ui, new_helpers, "helpers de UI")

main = replace_once(
    main,
    "        binding.fps60Button.alpha = if (maxSupportedFps() >= 60) 1f else 0.55f\n",
    """        val reported60 = maxSupportedFps() >= 60
        binding.fps60Button.alpha =
            if (reported60 || cameraProController.experimental60Enabled) 1f else 0.55f
""",
    "alpha FPS",
)
main = replace_once(
    main,
    "        if (enabled && maxSupportedFps() < 60) binding.fps60Button.alpha = 0.55f\n",
    """        if (
            enabled && maxSupportedFps() < 60 && !cameraProController.experimental60Enabled
        ) binding.fps60Button.alpha = 0.55f
""",
    "alpha FPS controles",
)
main = replace_once(
    main,
    """    override fun onDestroy() {
        timerHandler.removeCallbacksAndMessages(null)
        try {
""",
    """    override fun onDestroy() {
        timerHandler.removeCallbacksAndMessages(null)
        if (::overlayController.isInitialized) overlayController.release()
        if (::cameraProController.isInitialized) cameraProController.release()
        try {
""",
    "onDestroy",
)
main_path.write_text(main, encoding="utf-8")

layout = layout_path.read_text(encoding="utf-8")
focus_view = """
    <View
        android:id="@+id/focusIndicator"
        android:layout_width="64dp"
        android:layout_height="64dp"
        android:background="@drawable/bg_focus_indicator"
        android:elevation="8dp"
        android:visibility="gone"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

"""
layout = replace_once(
    layout,
    "    <LinearLayout\n        android:id=\"@+id/topBar\"",
    focus_view + "    <LinearLayout\n        android:id=\"@+id/topBar\"",
    "indicador de foco",
)
old_mic_end = """        <TextView
            android:id="@+id/micButton"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:layout_marginTop="10dp"
            android:background="@drawable/bg_icon_button"
            android:gravity="center"
            android:text="MIC"
            android:textColor="@color/white"
            android:textSize="10sp"
            android:textStyle="bold"
            android:contentDescription="Microfone" />
    </LinearLayout>
"""
new_mic_end = """        <TextView
            android:id="@+id/micButton"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:layout_marginTop="10dp"
            android:background="@drawable/bg_icon_button"
            android:gravity="center"
            android:text="MIC"
            android:textColor="@color/white"
            android:textSize="10sp"
            android:textStyle="bold"
            android:contentDescription="Microfone" />

        <TextView
            android:id="@+id/zoomLabel"
            android:layout_width="48dp"
            android:layout_height="30dp"
            android:layout_marginTop="8dp"
            android:gravity="center"
            android:text="1.0×"
            android:textColor="@color/storm_green"
            android:textSize="11sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/proButton"
            android:layout_width="48dp"
            android:layout_height="44dp"
            android:layout_marginTop="6dp"
            android:background="@drawable/bg_icon_button"
            android:gravity="center"
            android:text="PRO"
            android:textColor="@color/white"
            android:textSize="10sp"
            android:textStyle="bold"
            android:contentDescription="Modo Pro da câmera" />

        <TextView
            android:id="@+id/overlayButton"
            android:layout_width="48dp"
            android:layout_height="44dp"
            android:layout_marginTop="8dp"
            android:background="@drawable/bg_icon_button"
            android:gravity="center"
            android:text="WEB"
            android:textColor="@color/white"
            android:textSize="9sp"
            android:textStyle="bold"
            android:contentDescription="Overlay por URL" />
    </LinearLayout>
"""
layout = replace_once(layout, old_mic_end, new_mic_end, "botões Pro/overlay")
layout_path.write_text(layout, encoding="utf-8")

settings = settings_path.read_text(encoding="utf-8")
connect_block = """
        <TextView
            android:id="@+id/connectYoutubeButton"
            android:layout_width="match_parent"
            android:layout_height="48dp"
            android:layout_marginTop="14dp"
            android:background="@drawable/bg_chip_selected"
            android:gravity="center"
            android:text="CONECTAR CONTA YOUTUBE"
            android:textColor="@color/white"
            android:textSize="12sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="6dp"
            android:text="O login automático exige OAuth 2.0 do Google. Uma chave de API sozinha não autoriza a conta."
            android:textColor="@color/storm_muted"
            android:textSize="10sp" />

"""
settings = replace_once(
    settings,
    "        <TextView\n            android:layout_width=\"wrap_content\"\n            android:layout_height=\"wrap_content\"\n            android:layout_marginTop=\"16dp\"\n            android:text=\"Chave de transmissão do YouTube\"",
    connect_block + "        <TextView\n            android:layout_width=\"wrap_content\"\n            android:layout_height=\"wrap_content\"\n            android:layout_marginTop=\"16dp\"\n            android:text=\"Chave de transmissão do YouTube\"",
    "conexão YouTube",
)
settings_path.write_text(settings, encoding="utf-8")

readme = readme_path.read_text(encoding="utf-8")
if "## Modo Pro e overlays" not in readme:
    readme += """

## Modo Pro e overlays

- Zoom por gesto de pinça, controle na tela e teclas de volume.
- Foco contínuo, foco por toque com trava e foco manual por distância.
- Compensação de exposição, OIS e EIS quando disponibilizados pela Camera2.
- Diagnóstico das faixas de FPS expostas pelo fabricante.
- Modo experimental de 60 FPS, sempre acompanhado do medidor de FPS real.
- Overlay web por URL HTTPS, renderizado dentro do vídeo transmitido.

### Conexão com a conta do YouTube

A API key não autoriza operações na conta. Para criar transmissões, streams e obter a
chave automaticamente, o aplicativo precisará de OAuth 2.0 com o escopo
`youtube.force-ssl`, Client ID Android, pacote e SHA-1 da assinatura. Até essa
configuração ser adicionada, a transmissão direta por RTMPS e chave continua ativa.
"""
readme_path.write_text(readme, encoding="utf-8")

ET.parse(layout_path)
ET.parse(settings_path)
ET.parse(root / "app/src/main/res/layout/dialog_camera_pro.xml")
ET.parse(root / "app/src/main/res/layout/dialog_overlay_settings.xml")
ET.parse(root / "app/src/main/res/drawable/bg_focus_indicator.xml")
print("Atualização Pro aplicada e XML validado")
