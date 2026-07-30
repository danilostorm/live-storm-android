from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 bloco, encontrado {count}")
    return text.replace(old, new, 1)


root = Path(__file__).resolve().parents[1]
main_path = root / "app/src/main/java/com/hoststorm/livestorm/MainActivity.kt"
gradle_path = root / "app/build.gradle.kts"
readme_path = root / "README.md"

main = main_path.read_text(encoding="utf-8")

main = replace_once(
    main,
    "import com.hoststorm.livestorm.databinding.SheetMoreOptionsBinding\n",
    "import com.hoststorm.livestorm.databinding.SheetMoreOptionsBinding\n"
    "import com.hoststorm.livestorm.databinding.SheetStreamQualityBinding\n",
    "import do painel de qualidade",
)

main = replace_once(
    main,
    "    private var hudVisible = true\n    private var qualityExpanded = false\n",
    "    private var hudVisible = true\n",
    "remove estado de expansão inline",
)

main = replace_once(
    main,
    """        binding.qualityToggleButton.setOnClickListener {
            qualityExpanded = !qualityExpanded
            updateQualityPanel()
        }
""",
    """        binding.qualityToggleButton.setOnClickListener { showQualitySheet() }
""",
    "abre painel separado",
)

main = replace_once(
    main,
    """        sourceMode = mode
        screenAudioMode = audioMode
        qualityExpanded = false
        saveSourceMode()
""",
    """        sourceMode = mode
        screenAudioMode = audioMode
        saveSourceMode()
""",
    "troca de modo sem expansão",
)

main = replace_once(
    main,
    """        binding.cameraTools.visibility = visibility
        binding.qualityOptionsContainer.visibility =
            if (hudVisible && qualityExpanded) View.VISIBLE else View.GONE

""",
    """        binding.cameraTools.visibility = visibility
        binding.qualityOptionsContainer.visibility = View.GONE

""",
    "HUD sem opções inline",
)

main = replace_once(
    main,
    """    private fun updateQualityPanel() {
        val action = if (qualityExpanded) "FECHAR" else "AJUSTAR"
        val orientation = when (profile.orientationMode) {
            OrientationMode.AUTO -> "AUTO"
            OrientationMode.PORTRAIT -> "RETRATO 9:16"
            OrientationMode.LANDSCAPE -> "PAISAGEM 16:9"
        }
        binding.qualityToggleButton.text =
            "${profile.resolutionLabel} • ${profile.fps} FPS • $orientation    $action"
        binding.qualityOptionsContainer.visibility =
            if (hudVisible && qualityExpanded) View.VISIBLE else View.GONE
    }

""",
    """    private fun updateQualityPanel() {
        val orientation = when (profile.orientationMode) {
            OrientationMode.AUTO -> "AUTO"
            OrientationMode.PORTRAIT -> "RETRATO 9:16"
            OrientationMode.LANDSCAPE -> "PAISAGEM 16:9"
        }
        binding.qualityToggleButton.text =
            "QUALIDADE  •  ${profile.resolutionLabel}  •  ${profile.fps} FPS  •  $orientation   ›"
        binding.qualityOptionsContainer.visibility = View.GONE
    }

    private fun showQualitySheet() {
        if (!canChangeProfile()) return

        val sheetBinding = SheetStreamQualityBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        var selectedWidth = profile.width
        var selectedHeight = profile.height
        var selectedFps = profile.fps
        var selectedOrientation = profile.orientationMode

        fun orientationLabel(): String = when (selectedOrientation) {
            OrientationMode.AUTO -> "Automático"
            OrientationMode.PORTRAIT -> "Retrato 9:16"
            OrientationMode.LANDSCAPE -> "Paisagem 16:9"
        }

        fun refreshSelections() {
            setChip(sheetBinding.quality720Button, selectedWidth == 1280)
            setChip(sheetBinding.quality1080Button, selectedWidth == 1920)
            setChip(sheetBinding.quality30Button, selectedFps == 30)
            setChip(sheetBinding.quality60Button, selectedFps == 60)
            setChip(sheetBinding.qualityAutoButton, selectedOrientation == OrientationMode.AUTO)
            setChip(
                sheetBinding.qualityPortraitButton,
                selectedOrientation == OrientationMode.PORTRAIT
            )
            setChip(
                sheetBinding.qualityLandscapeButton,
                selectedOrientation == OrientationMode.LANDSCAPE
            )
            val resolution = if (selectedWidth >= 1920) "1080p" else "720p"
            sheetBinding.qualitySummary.text =
                "$resolution • $selectedFps FPS • ${orientationLabel()}"
        }

        sheetBinding.quality720Button.setOnClickListener {
            selectedWidth = 1280
            selectedHeight = 720
            refreshSelections()
        }
        sheetBinding.quality1080Button.setOnClickListener {
            selectedWidth = 1920
            selectedHeight = 1080
            refreshSelections()
        }
        sheetBinding.quality30Button.setOnClickListener {
            selectedFps = 30
            refreshSelections()
        }
        sheetBinding.quality60Button.setOnClickListener {
            selectedFps = 60
            refreshSelections()
        }
        sheetBinding.qualityAutoButton.setOnClickListener {
            selectedOrientation = OrientationMode.AUTO
            refreshSelections()
        }
        sheetBinding.qualityPortraitButton.setOnClickListener {
            selectedOrientation = OrientationMode.PORTRAIT
            refreshSelections()
        }
        sheetBinding.qualityLandscapeButton.setOnClickListener {
            selectedOrientation = OrientationMode.LANDSCAPE
            refreshSelections()
        }
        sheetBinding.cancelQualityButton.setOnClickListener { dialog.dismiss() }
        sheetBinding.saveQualityButton.setOnClickListener {
            val resolvedVertical = when (selectedOrientation) {
                OrientationMode.AUTO -> currentDeviceIsPortrait()
                OrientationMode.PORTRAIT -> true
                OrientationMode.LANDSCAPE -> false
            }
            val changed = profile.width != selectedWidth ||
                profile.height != selectedHeight ||
                profile.fps != selectedFps ||
                profile.orientationMode != selectedOrientation ||
                profile.vertical != resolvedVertical

            if (!changed) {
                dialog.dismiss()
                return@setOnClickListener
            }

            profile = profile.copy(
                width = selectedWidth,
                height = selectedHeight,
                fps = selectedFps,
                orientationMode = selectedOrientation,
                vertical = resolvedVertical
            )
            saveProfile()
            applyRequestedOrientationForMode(lockForLive = false)
            updateProfileUi()
            updateQualityPanel()
            dialog.dismiss()

            binding.root.postDelayed({
                syncAutomaticOrientation()
                if (sourceMode == SourceMode.CAMERA) {
                    prepareStream(showResult = true)
                } else {
                    showScreenReady()
                    updateProfileUi()
                }
            }, if (selectedOrientation == OrientationMode.AUTO) 250L else 0L)
        }

        refreshSelections()
        dialog.show()
    }

""",
    "painel de qualidade separado",
)

main_path.write_text(main, encoding="utf-8")

gradle = gradle_path.read_text(encoding="utf-8")
gradle = replace_once(
    gradle,
    '        versionCode = 5\n        versionName = "0.5.0"',
    '        versionCode = 6\n        versionName = "0.5.1"',
    "versão Android",
)
gradle_path.write_text(gradle, encoding="utf-8")

if readme_path.exists():
    readme = readme_path.read_text(encoding="utf-8")
    if "0.5.0" in readme:
        readme = readme.replace("0.5.0", "0.5.1")
        readme_path.write_text(readme, encoding="utf-8")

print("Live Storm 0.5.1 aplicado")
