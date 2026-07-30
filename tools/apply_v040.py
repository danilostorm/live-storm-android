from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 bloco, encontrado {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 bloco, encontrado {count}")
    return updated


root = Path(__file__).resolve().parents[1]
main_path = root / "app/src/main/java/com/hoststorm/livestorm/MainActivity.kt"
layout_path = root / "app/src/main/res/layout/activity_main.xml"
gradle_path = root / "app/build.gradle.kts"
readme_path = root / "README.md"

main = main_path.read_text(encoding="utf-8")
layout = layout_path.read_text(encoding="utf-8")
gradle = gradle_path.read_text(encoding="utf-8")
readme = readme_path.read_text(encoding="utf-8")

# Estado do HUD.
main = replace_once(
    main,
    "    private var cameraRecordFile: File? = null\n",
    "    private var cameraRecordFile: File? = null\n    private var hudVisible = true\n",
    "estado HUD",
)

# Permissões específicas para cada modo.
main = regex_once(
    main,
    r"    private val permissionLauncher = registerForActivityResult\(\n        ActivityResultContracts\.RequestMultiplePermissions\(\)\n    \) \{ result ->.*?\n    \}\n\n    private val screenCaptureLauncher",
    '''    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasPermissionsForCurrentMode()) {
            if (sourceMode == SourceMode.CAMERA) {
                prepareStream(showResult = true)
            } else {
                showScreenReady()
            }
        } else {
            val required = if (sourceMode == SourceMode.CAMERA) {
                "câmera e microfone"
            } else {
                "microfone"
            }
            showToast("Autorize $required para usar este modo de transmissão.")
            setStatus(Status.ERROR, "PERMISSÕES")
        }
    }

    private val screenCaptureLauncher''',
    "launcher de permissões",
)

# Inicialização: restaura HUD e solicita somente as permissões necessárias.
main = replace_once(
    main,
    "        updateConnectionLabel()\n        updateSourceUi()\n\n        if (hasPermissions()) {\n            if (sourceMode == SourceMode.CAMERA) prepareStream(showResult = false)\n            else showScreenReady()\n        } else {\n            permissionLauncher.launch(\n                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)\n            )\n        }\n",
    "        updateConnectionLabel()\n        updateSourceUi()\n        restoreHudPreference()\n\n        if (hasPermissionsForCurrentMode()) {\n            if (sourceMode == SourceMode.CAMERA) prepareStream(showResult = false)\n            else showScreenReady()\n        } else {\n            requestPermissionsForCurrentMode()\n        }\n",
    "inicialização profissional",
)

# Ações dos novos seletores e HUD.
main = replace_once(
    main,
    "        binding.overlayButton.setOnClickListener { overlayController.showDialog() }\n        binding.sourceButton.setOnClickListener { showSourceDialog() }\n        binding.recordButton.setOnClickListener { toggleLocalRecording() }\n",
    "        binding.overlayButton.setOnClickListener { overlayController.showDialog() }\n        binding.normalModeButton.setOnClickListener {\n            setSourceMode(SourceMode.CAMERA, screenAudioMode)\n        }\n        binding.gamesModeButton.setOnClickListener {\n            setSourceMode(SourceMode.SCREEN, screenAudioMode)\n        }\n        binding.gameAudioButton.setOnClickListener { showGameAudioDialog() }\n        binding.recordButton.setOnClickListener { toggleLocalRecording() }\n        binding.hudToggleButton.setOnClickListener { toggleHud() }\n",
    "ações de modo e HUD",
)

# Substitui o diálogo genérico de fonte pelo ajuste de áudio do modo Games.
main = regex_once(
    main,
    r"    private fun showSourceDialog\(\) \{.*?\n    \}\n\n    private fun setSourceMode",
    '''    private fun showGameAudioDialog() {
        if (sourceMode != SourceMode.SCREEN) {
            showToast("Selecione o modo Games para configurar o áudio da tela.")
            return
        }
        if (isLiveSessionActive() || isAnyRecording()) {
            showToast("Encerre a live ou a gravação antes de alterar o áudio do modo Games.")
            return
        }
        val items = arrayOf(
            "Áudio do jogo + microfone",
            "Somente áudio do jogo",
            "Somente microfone"
        )
        val selected = when (screenAudioMode) {
            ScreenAudioMode.MIX -> 0
            ScreenAudioMode.INTERNAL -> 1
            ScreenAudioMode.MICROPHONE -> 2
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Áudio da live de Games")
            .setSingleChoiceItems(items, selected) { dialog, which ->
                screenAudioMode = when (which) {
                    1 -> ScreenAudioMode.INTERNAL
                    2 -> ScreenAudioMode.MICROPHONE
                    else -> ScreenAudioMode.MIX
                }
                saveSourceMode()
                updateSourceUi()
                showScreenReady()
                dialog.dismiss()
            }
            .setMessage(
                "O Live Storm captura a tela do Android. Ele não abre nenhum jogo; " +
                    "depois de iniciar a transmissão, abra o jogo normalmente."
            )
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun setSourceMode''',
    "diálogo de Games",
)

# Fluxo de seleção Normal/Games com permissões adequadas.
main = regex_once(
    main,
    r"    private fun setSourceMode\(mode: SourceMode, audioMode: ScreenAudioMode\) \{.*?\n    \}\n\n    private fun showScreenReady",
    '''    private fun setSourceMode(mode: SourceMode, audioMode: ScreenAudioMode) {
        if (isLiveSessionActive() || isAnyRecording()) {
            showToast("Encerre a live ou a gravação antes de trocar o tipo de live.")
            return
        }
        sourceMode = mode
        screenAudioMode = audioMode
        saveSourceMode()
        if (!hasPermissionsForCurrentMode()) {
            updateSourceUi()
            requestPermissionsForCurrentMode()
            return
        }
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

    private fun showScreenReady''',
    "seleção Normal/Games",
)

# Mensagens e interface profissional dos dois modos.
main = regex_once(
    main,
    r"    private fun showScreenReady\(\) \{.*?\n    \}\n\n    private fun updateSourceUi\(\) \{.*?\n    \}\n",
    '''    private fun showScreenReady() {
        binding.screenModeOverlay.text =
            "MODO GAMES • CAPTURA DA TELA\\n" +
                "O Live Storm não abre jogos. Autorize a captura e depois abra o jogo."
        binding.screenModeOverlay.visibility =
            if (hudVisible && sourceMode == SourceMode.SCREEN) View.VISIBLE else View.GONE
        setStatus(Status.IDLE, "GAMES PRONTO")
        binding.capabilityHint.text = when (screenAudioMode) {
            ScreenAudioMode.MIX -> "Games: áudio do jogo + microfone"
            ScreenAudioMode.INTERNAL -> "Games: somente áudio interno do jogo"
            ScreenAudioMode.MICROPHONE -> "Games: somente microfone"
        }
    }

    private fun updateSourceUi() {
        setChip(binding.normalModeButton, sourceMode == SourceMode.CAMERA)
        setChip(binding.gamesModeButton, sourceMode == SourceMode.SCREEN)
        binding.broadcastModeHint.text = if (sourceMode == SourceMode.CAMERA) {
            "Live normal com câmera, zoom, foco Pro, flash, microfone e overlay."
        } else {
            "Captura a tela do Android. O app não abre o jogo; você o abre após autorizar."
        }
        binding.gameAudioButton.visibility =
            if (sourceMode == SourceMode.SCREEN) View.VISIBLE else View.GONE
        binding.gameAudioButton.text = when (screenAudioMode) {
            ScreenAudioMode.MIX -> "ÁUDIO: JOGO + MICROFONE"
            ScreenAudioMode.INTERNAL -> "ÁUDIO: SOMENTE JOGO"
            ScreenAudioMode.MICROPHONE -> "ÁUDIO: SOMENTE MICROFONE"
        }
        binding.screenModeOverlay.visibility =
            if (hudVisible && sourceMode == SourceMode.SCREEN) View.VISIBLE else View.GONE
        binding.proButton.alpha = if (sourceMode == SourceMode.CAMERA) 1f else 0.45f
        binding.proButton.isEnabled = sourceMode == SourceMode.CAMERA
        binding.flashButton.alpha = if (sourceMode == SourceMode.CAMERA) 1f else 0.45f
        binding.flashButton.isEnabled = sourceMode == SourceMode.CAMERA
    }
''',
    "UI Normal/Games",
)

# Câmera só prepara quando as duas permissões estão disponíveis.
main = replace_once(
    main,
    "        if (!hasPermissions() || sourceMode != SourceMode.CAMERA) {\n",
    "        if (!hasCameraPermission() || !hasAudioPermission() || sourceMode != SourceMode.CAMERA) {\n",
    "permissão de preparo",
)

# Oculta HUD automaticamente ao começar a captura da tela, mas mantém o botão de retorno.
main = replace_once(
    main,
    "        ContextCompat.startForegroundService(this, intent)\n        setStatus(Status.CONNECTING, \"TELA\")\n",
    "        ContextCompat.startForegroundService(this, intent)\n        setHudVisible(false, announce = false)\n        setStatus(Status.CONNECTING, \"GAMES\")\n",
    "início Games sem HUD",
)

# Insere controlador do HUD antes das configurações.
hud_helpers = '''    private fun restoreHudPreference() {
        hudVisible = getSharedPreferences(PREF_HUD, Context.MODE_PRIVATE)
            .getBoolean("visible", true)
        applyHudVisibility(announce = false)
    }

    private fun toggleHud() {
        setHudVisible(!hudVisible, announce = true)
    }

    private fun setHudVisible(visible: Boolean, announce: Boolean) {
        hudVisible = visible
        getSharedPreferences(PREF_HUD, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("visible", visible)
            .apply()
        applyHudVisibility(announce)
    }

    private fun applyHudVisibility(announce: Boolean) {
        val visibility = if (hudVisible) View.VISIBLE else View.GONE
        binding.topBar.visibility = visibility
        binding.statsCard.visibility = visibility
        binding.cameraTools.visibility = visibility
        binding.controlPanel.visibility = visibility
        binding.topScrim.visibility = visibility
        binding.bottomScrim.visibility = visibility
        if (!hudVisible) {
            binding.screenModeOverlay.visibility = View.GONE
            binding.focusIndicator.visibility = View.GONE
        } else {
            updateSourceUi()
        }
        binding.hudToggleButton.text = if (hudVisible) "HUD−" else "HUD+"
        binding.hudToggleButton.alpha = if (hudVisible) 0.72f else 1f
        binding.hudToggleButton.contentDescription =
            if (hudVisible) "Ocultar informações da tela" else "Mostrar informações da tela"
        if (announce) {
            showToast(if (hudVisible) "HUD exibido" else "HUD ocultado; toque em HUD+ para restaurar")
        }
    }

'''
main = replace_once(
    main,
    "    private fun showSettingsDialog() {\n",
    hud_helpers + "    private fun showSettingsDialog() {\n",
    "helpers do HUD",
)

# Perfil e controles incluem os novos seletores.
main = regex_once(
    main,
    r"    private fun updateProfileUi\(\) \{.*?\n    \}\n\n    private fun setChip",
    '''    private fun updateProfileUi() {
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
        val modeLabel = if (sourceMode == SourceMode.CAMERA) "NORMAL" else "GAMES"
        binding.profileSummary.text =
            "$modeLabel • ${profile.resolutionLabel} • ${profile.fps} FPS • " +
                "${profile.aspectLabel} • ${profile.bitrateLabel}"
    }

    private fun setChip''',
    "resumo profissional",
)

main = replace_once(
    main,
    "            binding.landscapeOrientationButton\n        ).forEach {\n",
    "            binding.landscapeOrientationButton,\n            binding.normalModeButton,\n            binding.gamesModeButton,\n            binding.gameAudioButton\n        ).forEach {\n",
    "bloqueio dos seletores",
)

# Texto principal do botão respeita o tipo de live.
main = replace_once(
    main,
    "        } else {\n            binding.startStopButton.text = \"●  INICIAR NO YOUTUBE\"\n            binding.startStopButton.setBackgroundResource(R.drawable.bg_live_button)\n        }\n",
    "        } else {\n            binding.startStopButton.text = if (sourceMode == SourceMode.CAMERA) {\n                \"●  INICIAR LIVE NORMAL\"\n            } else {\n                \"●  INICIAR LIVE DE GAMES\"\n            }\n            binding.startStopButton.setBackgroundResource(R.drawable.bg_live_button)\n        }\n",
    "texto do botão iniciar",
)

# Permissões separadas por modo.
main = regex_once(
    main,
    r"    private fun hasPermissions\(\): Boolean \{.*?\n    \}\n\n    private fun showToast",
    '''    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasPermissionsForCurrentMode(): Boolean {
        return hasAudioPermission() &&
            (sourceMode == SourceMode.SCREEN || hasCameraPermission())
    }

    private fun requestPermissionsForCurrentMode() {
        val permissions = if (sourceMode == SourceMode.CAMERA) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        permissionLauncher.launch(permissions)
    }

    private fun showToast''',
    "helpers de permissão",
)

# Migração única: AUTO vira a orientação padrão também para quem atualiza da versão anterior.
main = regex_once(
    main,
    r"    private fun restoreProfile\(\) \{.*?\n    \}\n\n    private fun saveProfile",
    '''    private fun restoreProfile() {
        val prefs = getSharedPreferences(PREF_PROFILE, Context.MODE_PRIVATE)
        val migrateToAuto = !prefs.getBoolean("auto_default_v040", false)
        val legacyVertical = prefs.getBoolean("vertical", false)
        val fallbackMode = OrientationMode.AUTO
        val orientationMode = if (migrateToAuto) {
            prefs.edit()
                .putBoolean("auto_default_v040", true)
                .putString("orientation_mode", OrientationMode.AUTO.name)
                .apply()
            OrientationMode.AUTO
        } else {
            runCatching {
                OrientationMode.valueOf(
                    prefs.getString("orientation_mode", fallbackMode.name) ?: fallbackMode.name
                )
            }.getOrDefault(if (legacyVertical) OrientationMode.PORTRAIT else fallbackMode)
        }
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

    private fun saveProfile''',
    "migração de orientação AUTO",
)

main = replace_once(
    main,
    "        val orientationMode: OrientationMode = OrientationMode.LANDSCAPE,\n",
    "        val orientationMode: OrientationMode = OrientationMode.AUTO,\n",
    "default AUTO",
)

# Mensagem do modo Games deixa claro que nenhum jogo é aberto automaticamente.
main = replace_once(
    main,
    "            showToast(\"Transmissão da tela iniciada. Abra o jogo que deseja mostrar.\")\n",
    "            showToast(\"Live de Games iniciada. O Live Storm não abre o jogo; agora abra-o normalmente.\")\n",
    "mensagem Games",
)

main = replace_once(
    main,
    "        private const val PREF_SOURCE_MODE = \"stream_source_mode\"\n",
    "        private const val PREF_SOURCE_MODE = \"stream_source_mode\"\n        private const val PREF_HUD = \"live_storm_hud\"\n",
    "preferência HUD",
)

# Layout: IDs dos degradês para controlar pelo HUD.
layout = replace_once(
    layout,
    "    <View\n        android:layout_width=\"0dp\"\n        android:layout_height=\"180dp\"\n",
    "    <View\n        android:id=\"@+id/topScrim\"\n        android:layout_width=\"0dp\"\n        android:layout_height=\"180dp\"\n",
    "ID scrim superior",
)
layout = replace_once(
    layout,
    "    <View\n        android:layout_width=\"0dp\"\n        android:layout_height=\"360dp\"\n",
    "    <View\n        android:id=\"@+id/bottomScrim\"\n        android:layout_width=\"0dp\"\n        android:layout_height=\"360dp\"\n",
    "ID scrim inferior",
)

# Texto profissional do placeholder Games.
layout = regex_once(
    layout,
    r'android:text="TELA / JOGO SELECIONADO\s*Ao iniciar, o Android pedirá autorização\."',
    'android:text="MODO GAMES • CAPTURA DA TELA&#10;O Live Storm não abre jogos. Autorize a captura e depois abra o jogo."',
    "texto placeholder Games",
)

# Botão flutuante permanente para ocultar/restaurar HUD.
focus_block = '''    <View
        android:id="@+id/focusIndicator"
        android:layout_width="64dp"
        android:layout_height="64dp"
        android:background="@drawable/bg_focus_indicator"
        android:elevation="8dp"
        android:visibility="gone"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />
'''
hud_button = focus_block + '''
    <TextView
        android:id="@+id/hudToggleButton"
        android:layout_width="52dp"
        android:layout_height="42dp"
        android:layout_marginStart="10dp"
        android:background="@drawable/bg_icon_button"
        android:contentDescription="Ocultar informações da tela"
        android:elevation="12dp"
        android:gravity="center"
        android:text="HUD−"
        android:textColor="@color/white"
        android:textSize="10sp"
        android:textStyle="bold"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintVertical_bias="0.48" />
'''
layout = replace_once(layout, focus_block, hud_button, "botão HUD")

# Substitui a antiga linha Fonte/REC por seletor Normal/Games elaborado.
old_mode_block = '''        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="11dp"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/sourceButton"
                style="@style/StreamChip"
                android:layout_width="0dp"
                android:layout_weight="1"
                android:gravity="center"
                android:text="CÂMERA" />

            <TextView
                android:id="@+id/recordButton"
                style="@style/StreamChip"
                android:layout_width="0dp"
                android:layout_marginStart="8dp"
                android:layout_weight="1"
                android:gravity="center"
                android:text="● REC LOCAL" />
        </LinearLayout>
'''
new_mode_block = '''        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:text="Tipo de live"
            android:textColor="@color/storm_muted"
            android:textSize="10sp"
            android:textStyle="bold" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="7dp"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/normalModeButton"
                style="@style/StreamChip"
                android:layout_width="0dp"
                android:layout_weight="1"
                android:gravity="center"
                android:text="NORMAL • CÂMERA" />

            <TextView
                android:id="@+id/gamesModeButton"
                style="@style/StreamChip"
                android:layout_width="0dp"
                android:layout_marginStart="8dp"
                android:layout_weight="1"
                android:gravity="center"
                android:text="GAMES • TELA" />
        </LinearLayout>

        <TextView
            android:id="@+id/broadcastModeHint"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="5dp"
            android:text="Live normal com câmera, zoom, foco Pro, flash, microfone e overlay."
            android:textColor="@color/storm_muted"
            android:textSize="9sp" />

        <TextView
            android:id="@+id/gameAudioButton"
            style="@style/StreamChip"
            android:layout_width="match_parent"
            android:layout_marginTop="7dp"
            android:gravity="center"
            android:text="ÁUDIO: JOGO + MICROFONE"
            android:visibility="gone" />

        <TextView
            android:id="@+id/recordButton"
            style="@style/StreamChip"
            android:layout_width="match_parent"
            android:layout_marginTop="8dp"
            android:gravity="center"
            android:text="● REC LOCAL" />
'''
layout = replace_once(layout, old_mode_block, new_mode_block, "seletor Normal/Games")

# Versão e documentação.
gradle = replace_once(gradle, '        versionCode = 3\n', '        versionCode = 4\n', 'versionCode')
gradle = replace_once(gradle, '        versionName = "0.3.0"\n', '        versionName = "0.4.0"\n', 'versionName')

readme += '''

## Live Storm 0.4.0 — HUD e modos profissionais

- HUD ocultável por botão flutuante, preservando somente o controle HUD+/HUD−.
- Orientação automática aplicada como padrão na primeira execução após a atualização.
- Seletor direto entre **Normal • Câmera** e **Games • Tela**.
- O modo Games nunca abre aplicativos: solicita a captura da tela e o usuário abre o jogo normalmente.
- Áudio de Games configurável entre jogo + microfone, somente jogo ou somente microfone.
- Permissões solicitadas conforme o modo: câmera não é exigida para transmitir a tela.
'''

main_path.write_text(main, encoding="utf-8")
layout_path.write_text(layout, encoding="utf-8")
gradle_path.write_text(gradle, encoding="utf-8")
readme_path.write_text(readme, encoding="utf-8")

print("Live Storm 0.4.0 aplicado com sucesso")
