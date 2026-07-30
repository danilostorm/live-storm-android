from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: esperado 1 bloco, encontrado {count}")
    return text.replace(old, new, 1)


path = Path("app/src/main/java/com/hoststorm/livestorm/MainActivity.kt")
text = path.read_text(encoding="utf-8")

launcher = '''    private val youtubeAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val server = data?.getStringExtra(YoutubeAuthActivity.EXTRA_SERVER)
            val streamKey = data?.getStringExtra(YoutubeAuthActivity.EXTRA_STREAM_KEY)
            if (!server.isNullOrBlank() && !streamKey.isNullOrBlank()) {
                saveStreamSettings(
                    StreamSettings(
                        server = server.trim().trimEnd('/'),
                        streamKey = streamKey.trim()
                    )
                )
                updateConnectionLabel()
                val title = data.getStringExtra(YoutubeAuthActivity.EXTRA_BROADCAST_TITLE)
                binding.capabilityHint.text = if (title.isNullOrBlank()) {
                    "Conta YouTube conectada e chave RTMPS configurada"
                } else {
                    "Live selecionada: $title"
                }
                showToast("Conta YouTube conectada. A transmissão está pronta.")
            }
        }
    }

'''

text = replace_once(
    text,
    "    override fun onCreate(savedInstanceState: Bundle?) {\n",
    launcher + "    override fun onCreate(savedInstanceState: Bundle?) {\n",
    "launcher OAuth"
)

text = replace_once(
    text,
    "        dialogBinding.connectYoutubeButton.setOnClickListener { showYoutubeConnectionInfo() }\n",
    "",
    "botão OAuth antigo"
)

text = replace_once(
    text,
    '''        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
''',
    '''        dialog.setOnShowListener {
            dialogBinding.connectYoutubeButton.setOnClickListener {
                dialog.dismiss()
                openYoutubeAuth()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
''',
    "ação do botão OAuth"
)

open_method = '''    private fun openYoutubeAuth() {
        val intent = Intent(this, YoutubeAuthActivity::class.java)
            .putExtra(YoutubeAuthActivity.EXTRA_RESOLUTION, profile.resolutionLabel)
            .putExtra(
                YoutubeAuthActivity.EXTRA_FRAME_RATE,
                if (profile.fps >= 60) "60fps" else "30fps"
            )
        youtubeAuthLauncher.launch(intent)
    }

'''

text = replace_once(
    text,
    "    private fun showYoutubeConnectionInfo() {\n",
    open_method + "    private fun showYoutubeConnectionInfo() {\n",
    "método para abrir OAuth"
)

path.write_text(text, encoding="utf-8")
print("MainActivity integrada ao OAuth YouTube")
