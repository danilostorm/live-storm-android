package com.hoststorm.livestorm

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.hoststorm.livestorm.databinding.ActivityYoutubeAuthBinding
import java.util.concurrent.Executors

class YoutubeAuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityYoutubeAuthBinding
    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }
    private val api = YoutubeLiveApi()
    private val executor = Executors.newSingleThreadExecutor()
    private val scopes = listOf(Scope(YOUTUBE_FORCE_SSL_SCOPE))

    private var accessToken: String? = null
    private var channel: YoutubeLiveApi.ChannelInfo? = null
    private var broadcasts: List<YoutubeLiveApi.BroadcastInfo> = emptyList()

    private val requestedProfile: YoutubeLiveApi.StreamProfile by lazy {
        YoutubeLiveApi.StreamProfile(
            resolution = intent.getStringExtra(EXTRA_RESOLUTION) ?: "1080p",
            frameRate = intent.getStringExtra(EXTRA_FRAME_RATE) ?: "60fps"
        )
    }

    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            try {
                processAuthorizationResult(
                    authorizationClient.getAuthorizationResultFromIntent(data)
                )
            } catch (error: ApiException) {
                showAuthorizationError(error)
            }
        } else {
            setLoading(false)
            showMessage("A autorização da conta Google foi cancelada.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityYoutubeAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.connectButton.setOnClickListener { authorize() }
        binding.disconnectButton.setOnClickListener { disconnectAccount() }
        binding.refreshButton.setOnClickListener { authorize() }
        binding.createLiveButton.setOnClickListener { showCreateLiveDialog() }

        renderSavedAccount()
    }

    private fun authorize() {
        setLoading(true, "Abrindo autorização do Google...")
        val savedEmail = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        val requestBuilder = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)

        if (savedEmail.isNullOrBlank()) {
            requestBuilder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        } else {
            requestBuilder.setAccount(Account(savedEmail, GOOGLE_ACCOUNT_TYPE))
        }

        authorizationClient.authorize(requestBuilder.build())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    launchAuthorization(result.pendingIntent)
                } else {
                    processAuthorizationResult(result)
                }
            }
            .addOnFailureListener { error ->
                setLoading(false)
                if (error is ApiException) {
                    showAuthorizationError(error)
                } else {
                    showError("Não foi possível abrir o login do Google: ${error.message ?: "erro desconhecido"}")
                }
            }
    }

    private fun launchAuthorization(pendingIntent: PendingIntent?) {
        if (pendingIntent == null) {
            setLoading(false)
            showError("O Google não retornou a tela de autorização.")
            return
        }
        try {
            authorizationLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        } catch (error: Exception) {
            setLoading(false)
            showError("Não foi possível abrir a seleção de conta: ${error.message}")
        }
    }

    private fun processAuthorizationResult(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            setLoading(false)
            showError("O Google autorizou a conta, mas não retornou um token de acesso.")
            return
        }

        val account = result.toGoogleSignInAccount()
        val email = account?.email ?: prefs.getString(KEY_ACCOUNT_EMAIL, null)
        val displayName = account?.displayName
            ?: prefs.getString(KEY_ACCOUNT_NAME, null)
            ?: email
            ?: "Conta Google"

        prefs.edit()
            .putString(KEY_ACCOUNT_EMAIL, email)
            .putString(KEY_ACCOUNT_NAME, displayName)
            .apply()

        accessToken = token
        renderSavedAccount()
        loadChannelAndLives(token)
    }

    private fun loadChannelAndLives(token: String) {
        setLoading(true, "Carregando canal e transmissões...")
        executor.execute {
            runCatching {
                val channelInfo = api.loadChannel(token)
                val liveItems = api.listBroadcasts(token)
                channelInfo to liveItems
            }.onSuccess { (channelInfo, liveItems) ->
                runOnUiThread {
                    channel = channelInfo
                    broadcasts = liveItems
                    setLoading(false)
                    renderDashboard()
                }
            }.onFailure { error ->
                runOnUiThread {
                    setLoading(false)
                    handleApiFailure(error)
                }
            }
        }
    }

    private fun renderSavedAccount() {
        val email = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        val name = prefs.getString(KEY_ACCOUNT_NAME, null)
        val connected = !email.isNullOrBlank()

        binding.accountName.text = if (connected) name ?: "Conta Google" else "Nenhuma conta autorizada"
        binding.accountEmail.text = if (connected) {
            email
        } else {
            "Conecte uma conta Google que possua um canal do YouTube."
        }
        binding.connectButton.text = if (connected) {
            "AUTORIZAR E CARREGAR LIVES"
        } else {
            "CONECTAR COM GOOGLE"
        }
        binding.disconnectButton.visibility = if (connected) View.VISIBLE else View.GONE
        binding.authPill.text = if (connected) "CONTA SALVA" else "DESCONECTADO"
        binding.authPill.setTextColor(
            ContextCompat.getColor(
                this,
                if (connected) R.color.storm_green else R.color.white
            )
        )
    }

    private fun renderDashboard() {
        val channelInfo = channel ?: return
        binding.channelCard.visibility = View.VISIBLE
        binding.channelName.text = channelInfo.title
        binding.channelHint.text =
            "Perfil solicitado: ${requestedProfile.resolution} • ${requestedProfile.frameRate}. " +
                "Selecione uma live existente ou crie uma nova."
        binding.authPill.text = "AUTORIZADO"
        binding.authPill.setTextColor(ContextCompat.getColor(this, R.color.storm_green))
        binding.livesTitle.visibility = View.VISIBLE
        binding.livesContainer.removeAllViews()

        if (broadcasts.isEmpty()) {
            binding.emptyLivesText.visibility = View.VISIBLE
            return
        }

        binding.emptyLivesText.visibility = View.GONE
        broadcasts.forEach { broadcast ->
            binding.livesContainer.addView(createBroadcastView(broadcast))
        }
    }

    private fun createBroadcastView(broadcast: YoutubeLiveApi.BroadcastInfo): View {
        return TextView(this).apply {
            val privacy = when (broadcast.privacyStatus) {
                "public" -> "PÚBLICA"
                "private" -> "PRIVADA"
                else -> "NÃO LISTADA"
            }
            val lifecycle = when (broadcast.lifecycleStatus) {
                "live" -> "AO VIVO"
                "testing" -> "TESTANDO"
                "ready" -> "PRONTA"
                "created" -> "CRIADA"
                else -> "AGENDADA"
            }
            text = "${broadcast.title}\n$lifecycle • $privacy"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setBackgroundResource(R.drawable.bg_glass_card)
            setOnClickListener { prepareExistingLive(broadcast) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun prepareExistingLive(broadcast: YoutubeLiveApi.BroadcastInfo) {
        val token = accessToken
        if (token.isNullOrBlank()) {
            authorize()
            return
        }
        setLoading(true, "Preparando chave RTMPS...")
        executor.execute {
            runCatching {
                api.prepareExistingBroadcast(token, broadcast, requestedProfile)
            }.onSuccess { prepared ->
                runOnUiThread {
                    setLoading(false)
                    showPreparedLive(prepared)
                }
            }.onFailure { error ->
                runOnUiThread {
                    setLoading(false)
                    handleApiFailure(error)
                }
            }
        }
    }

    private fun showCreateLiveDialog() {
        val token = accessToken
        if (token.isNullOrBlank()) {
            showMessage("Conecte e autorize sua conta antes de criar uma live.")
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val titleInput = EditText(this).apply {
            hint = "Título da live"
            setText("Live pelo Live Storm")
            setTextColor(Color.WHITE)
            setHintTextColor(ContextCompat.getColor(this@YoutubeAuthActivity, R.color.storm_muted))
            setSingleLine(true)
        }
        val descriptionInput = EditText(this).apply {
            hint = "Descrição opcional"
            setTextColor(Color.WHITE)
            setHintTextColor(ContextCompat.getColor(this@YoutubeAuthActivity, R.color.storm_muted))
            minLines = 3
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val privacySpinner = Spinner(this)
        privacySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Não listada", "Pública", "Privada")
        )
        val kidsCheck = CheckBox(this).apply {
            text = "Conteúdo destinado a crianças"
            setTextColor(Color.WHITE)
        }

        container.addView(sectionLabel("Título"))
        container.addView(titleInput)
        container.addView(sectionLabel("Descrição"))
        container.addView(descriptionInput)
        container.addView(sectionLabel("Visibilidade"))
        container.addView(privacySpinner)
        container.addView(kidsCheck)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Criar nova live")
            .setMessage(
                "O YouTube criará a transmissão e uma chave RTMPS compatível com " +
                    "${requestedProfile.resolution} • ${requestedProfile.frameRate}."
            )
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Criar live", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text.toString().trim()
                if (title.isBlank()) {
                    titleInput.error = "Informe um título"
                    return@setOnClickListener
                }
                val privacy = when (privacySpinner.selectedItemPosition) {
                    1 -> "public"
                    2 -> "private"
                    else -> "unlisted"
                }
                dialog.dismiss()
                createLive(
                    token = token,
                    title = title,
                    description = descriptionInput.text.toString().trim(),
                    privacy = privacy,
                    madeForKids = kidsCheck.isChecked
                )
            }
        }
        dialog.show()
    }

    private fun createLive(
        token: String,
        title: String,
        description: String,
        privacy: String,
        madeForKids: Boolean
    ) {
        setLoading(true, "Criando transmissão e chave RTMPS...")
        executor.execute {
            runCatching {
                api.createLive(
                    accessToken = token,
                    title = title,
                    description = description,
                    privacyStatus = privacy,
                    madeForKids = madeForKids,
                    profile = requestedProfile
                )
            }.onSuccess { prepared ->
                runOnUiThread {
                    setLoading(false)
                    showPreparedLive(prepared)
                }
            }.onFailure { error ->
                runOnUiThread {
                    setLoading(false)
                    handleApiFailure(error)
                }
            }
        }
    }

    private fun showPreparedLive(prepared: YoutubeLiveApi.PreparedLive) {
        val profileText = listOf(
            prepared.streamResolution,
            prepared.streamFrameRate
        ).filter { it.isNotBlank() }.joinToString(" • ")

        MaterialAlertDialogBuilder(this)
            .setTitle("Live pronta para transmitir")
            .setMessage(
                buildString {
                    append(prepared.title)
                    if (profileText.isNotBlank()) {
                        append("\n\nPerfil do YouTube: ")
                        append(profileText)
                    }
                    append("\n\nA URL e a chave serão transferidas automaticamente para o Live Storm.")
                }
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Usar esta live") { _, _ ->
                val result = Intent()
                    .putExtra(EXTRA_SERVER, prepared.server)
                    .putExtra(EXTRA_STREAM_KEY, prepared.streamKey)
                    .putExtra(EXTRA_BROADCAST_TITLE, prepared.title)
                    .putExtra(EXTRA_WATCH_URL, prepared.watchUrl)
                setResult(Activity.RESULT_OK, result)
                finish()
            }
            .show()
    }

    private fun disconnectAccount() {
        val email = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        if (email.isNullOrBlank()) {
            clearLocalAccount()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Desconectar conta")
            .setMessage(
                "O acesso do Live Storm ao YouTube será revogado. Para usar novamente, " +
                    "será necessário autorizar a conta outra vez."
            )
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Desconectar") { _, _ ->
                setLoading(true, "Revogando autorização...")
                val request = RevokeAccessRequest.builder()
                    .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
                    .setScopes(scopes)
                    .build()
                authorizationClient.revokeAccess(request)
                    .addOnCompleteListener {
                        clearLocalAccount()
                        setLoading(false)
                        showMessage("Conta desconectada.")
                    }
            }
            .show()
    }

    private fun clearLocalAccount() {
        accessToken = null
        channel = null
        broadcasts = emptyList()
        prefs.edit().clear().apply()
        binding.channelCard.visibility = View.GONE
        binding.livesTitle.visibility = View.GONE
        binding.livesContainer.removeAllViews()
        binding.emptyLivesText.visibility = View.GONE
        renderSavedAccount()
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@YoutubeAuthActivity, R.color.storm_muted))
            textSize = 11f
            setPadding(0, dp(12), 0, dp(4))
        }
    }

    private fun handleApiFailure(error: Throwable) {
        if (error is YoutubeLiveApi.ApiException && error.httpCode == 401) {
            accessToken = null
            showError("A sessão do Google expirou. Toque em autorizar para entrar novamente.")
            return
        }
        showError(error.message ?: "Não foi possível concluir a solicitação no YouTube.")
    }

    private fun showAuthorizationError(error: ApiException) {
        val message = if (error.statusCode == 10) {
            "O cliente OAuth Android ainda não está registrado para o pacote " +
                "com.hoststorm.livestorm e para o certificado usado nesta instalação."
        } else {
            "Falha na autorização Google (${error.statusCode}): ${error.message ?: "erro desconhecido"}"
        }
        showError(message)
    }

    private fun setLoading(loading: Boolean, message: String = "Conectando ao YouTube...") {
        binding.loadingText.text = message
        binding.loadingOverlay.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Não foi possível continuar")
            .setMessage(message)
            .setPositiveButton("Entendi", null)
            .show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private val prefs
        get() = getSharedPreferences(PREF_YOUTUBE_AUTH, MODE_PRIVATE)

    companion object {
        const val EXTRA_RESOLUTION = "youtube_resolution"
        const val EXTRA_FRAME_RATE = "youtube_frame_rate"
        const val EXTRA_SERVER = "youtube_server"
        const val EXTRA_STREAM_KEY = "youtube_stream_key"
        const val EXTRA_BROADCAST_TITLE = "youtube_broadcast_title"
        const val EXTRA_WATCH_URL = "youtube_watch_url"

        private const val PREF_YOUTUBE_AUTH = "youtube_oauth"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val YOUTUBE_FORCE_SSL_SCOPE =
            "https://www.googleapis.com/auth/youtube.force-ssl"
    }
}
