package com.hoststorm.livestorm

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.pedro.encoder.input.gl.render.filters.AndroidViewFilterRender
import com.pedro.library.generic.GenericStream

/** Renderiza uma página HTTPS transparente sobre a imagem codificada. */
class OverlayController(
    private val activity: AppCompatActivity,
    private val streamProvider: () -> GenericStream,
    private val geometryProvider: () -> VideoGeometry,
    private val onInfo: (String) -> Unit,
    private val onStateChanged: (Boolean) -> Unit
) {

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var overlayWebView: WebView? = null
    private var overlayFilter: AndroidViewFilterRender? = null

    val enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)

    val configuredUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""

    fun showDialog() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_overlay_settings, null)
        val enabledSwitch = view.findViewById<SwitchMaterial>(R.id.overlayEnabledSwitch)
        val urlInput = view.findViewById<EditText>(R.id.overlayUrlInput)
        val refreshSeek = view.findViewById<SeekBar>(R.id.overlayFpsSeek)
        val refreshValue = view.findViewById<TextView>(R.id.overlayFpsValue)

        enabledSwitch.isChecked = enabled
        urlInput.setText(configuredUrl)
        refreshSeek.max = 15
        refreshSeek.progress = (prefs.getInt(KEY_FPS, 20) - 15).coerceIn(0, 15)
        fun updateRefreshLabel() {
            refreshValue.text = "${15 + refreshSeek.progress} FPS"
        }
        updateRefreshLabel()
        refreshSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateRefreshLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Overlay por URL")
            .setView(view)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Remover", null)
            .setPositiveButton("Aplicar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                prefs.edit().putBoolean(KEY_ENABLED, false).putString(KEY_URL, "").apply()
                clear()
                onStateChanged(false)
                onInfo("Overlay removido")
                dialog.dismiss()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = urlInput.text.toString().trim()
                val shouldEnable = enabledSwitch.isChecked
                val error = validate(url, shouldEnable)
                if (error != null) {
                    onInfo(error)
                    return@setOnClickListener
                }
                prefs.edit()
                    .putBoolean(KEY_ENABLED, shouldEnable)
                    .putString(KEY_URL, url)
                    .putInt(KEY_FPS, 15 + refreshSeek.progress)
                    .apply()
                if (shouldEnable) applyIfConfigured() else clear()
                onStateChanged(shouldEnable)
                onInfo(if (shouldEnable) "Overlay web ativado" else "Overlay desativado")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    fun applyIfConfigured() {
        if (!enabled) {
            clear()
            return
        }
        val url = configuredUrl
        if (validate(url, true) != null) return
        val stream = streamProvider()
        val geometry = geometryProvider()

        clear()

        val outputWidth = if (geometry.rotation == 90 || geometry.rotation == 270) {
            geometry.height
        } else {
            geometry.width
        }
        val outputHeight = if (geometry.rotation == 90 || geometry.rotation == 270) {
            geometry.width
        } else {
            geometry.height
        }

        val webView = WebView(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.evaluateJavascript(
                        "(function(){" +
                            "document.documentElement.style.background='transparent';" +
                            "document.body.style.background='transparent';" +
                            "document.body.style.backgroundColor='transparent';" +
                            "document.body.style.margin='0';" +
                            "document.body.style.overflow='hidden';" +
                            "})();",
                        null
                    )
                }
            }
        }

        val widthSpec = View.MeasureSpec.makeMeasureSpec(outputWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(outputHeight, View.MeasureSpec.EXACTLY)
        webView.measure(widthSpec, heightSpec)
        webView.layout(0, 0, outputWidth, outputHeight)
        webView.loadUrl(url)

        val filter = AndroidViewFilterRender().apply {
            // RootEncoder 2.7.2 usa a cadência interna padrão do filtro.
            view = webView
            setPosition(0f, 0f)
            setScale(100f, 100f)
        }

        stream.getGlInterface().setFilter(filter)
        overlayWebView = webView
        overlayFilter = filter
        onStateChanged(true)
    }

    fun clear() {
        runCatching { streamProvider().getGlInterface().clearFilters() }
        overlayFilter?.view = null
        overlayFilter = null
        overlayWebView?.let { webView ->
            runCatching { webView.stopLoading() }
            runCatching { webView.loadUrl("about:blank") }
            runCatching { webView.clearHistory() }
            runCatching { webView.removeAllViews() }
            runCatching { webView.destroy() }
        }
        overlayWebView = null
        onStateChanged(false)
    }

    fun release() = clear()

    private fun validate(url: String, enabled: Boolean): String? {
        if (!enabled) return null
        if (!url.startsWith("https://", ignoreCase = true)) {
            return "Use uma URL HTTPS para o overlay."
        }
        if (url.length < 12 || url.length > 2000) {
            return "A URL do overlay parece inválida."
        }
        return null
    }

    data class VideoGeometry(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val fps: Int
    )

    companion object {
        private const val PREFS = "web_overlay_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_URL = "url"
        private const val KEY_FPS = "fps"
    }
}
