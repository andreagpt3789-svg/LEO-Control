// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.leo

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.stream.StreamActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LeoMainActivity : AppCompatActivity() {
    companion object {
        private const val PREFS = "leo_control_native"
        private const val KEY_HUB_URL = "hub_url"
        private const val KEY_PS5_IP = "ps5_ip"
        private const val DEFAULT_HUB_URL = "http://192.168.31.75:8765/"
        private const val DEFAULT_PS5_IP = "192.168.31.94"
    }

    private lateinit var webView: WebView
    private var registrationPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 13, 16)
        window.navigationBarColor = Color.rgb(11, 13, 16)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 13, 16))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(17, 20, 25))
        }
        val title = TextView(this).apply {
            text = "LEO Control"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val settings = Button(this).apply {
            text = "⚙"
            textSize = 18f
            setOnClickListener { showSettings() }
        }
        top.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))
        top.addView(settings, LinearLayout.LayoutParams(dp(58), dp(50)))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        webView = WebView(this)
        configureWebView()
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(Color.rgb(17, 20, 25))
        }
        val hub = Button(this).apply {
            text = "⌂ Hub"
            setOnClickListener { loadHub() }
        }
        val ps5 = Button(this).apply {
            text = "🎮 PS5"
            setOnClickListener { openPs5() }
        }
        bottom.addView(hub, LinearLayout.LayoutParams(0, dp(54), 1f))
        bottom.addView(ps5, LinearLayout.LayoutParams(0, dp(54), 1f))
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        loadHub()
    }

    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString + " LEOControlAndroid/0.8.0"
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                handleLeoUri(request?.url?.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                handleLeoUri(url)
        }
    }

    private fun handleLeoUri(url: String?): Boolean {
        if (url == null) return false
        if (url.startsWith("leo://ps5")) {
            openPs5()
            return true
        }
        return false
    }

    private fun loadHub() {
        val url = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_HUB_URL, DEFAULT_HUB_URL) ?: DEFAULT_HUB_URL
        webView.loadUrl(normalizeUrl(url))
    }

    private fun normalizeUrl(raw: String): String {
        var v = raw.trim()
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "http://$v"
        if (!v.endsWith("/")) v += "/"
        return v
    }

    private fun showSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val hub = EditText(this).apply {
            hint = "LEO Hub (es. 192.168.31.75:8765)"
            setText(prefs.getString(KEY_HUB_URL, DEFAULT_HUB_URL))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val ps5 = EditText(this).apply {
            hint = "IP PS5"
            setText(prefs.getString(KEY_PS5_IP, DEFAULT_PS5_IP))
            inputType = InputType.TYPE_CLASS_PHONE
        }
        box.addView(hub)
        box.addView(ps5)
        AlertDialog.Builder(this)
            .setTitle("LEO Control")
            .setView(box)
            .setPositiveButton("Salva") { _, _ ->
                prefs.edit()
                    .putString(KEY_HUB_URL, normalizeUrl(hub.text.toString()))
                    .putString(KEY_PS5_IP, ps5.text.toString().trim())
                    .apply()
                loadHub()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun openPs5() {
        val ip = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_PS5_IP, DEFAULT_PS5_IP)?.trim().orEmpty()
        if (ip.isBlank()) {
            showSettings()
            return
        }
        lifecycleScope.launch {
            val registered = firstRegisteredPs5()
            if (registered == null) {
                registrationPending = true
                Toast.makeText(
                    this@LeoMainActivity,
                    "Prima registrazione PS5: inserisci Account-ID e PIN una sola volta.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(this@LeoMainActivity, RegistActivity::class.java).apply {
                    putExtra(RegistActivity.EXTRA_HOST, ip)
                    putExtra(RegistActivity.EXTRA_BROADCAST, false)
                })
            } else {
                startController(registered, ip)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!registrationPending) return
        registrationPending = false
        val ip = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_PS5_IP, DEFAULT_PS5_IP)?.trim().orEmpty()
        lifecycleScope.launch {
            val registered = firstRegisteredPs5()
            if (registered != null && ip.isNotBlank()) startController(registered, ip)
        }
    }

    private suspend fun firstRegisteredPs5(): RegisteredHost? = withContext(Dispatchers.IO) {
        getDatabase(this@LeoMainActivity)
            .registeredHostDao()
            .getAll()
            .first()
            .firstOrNull { it.target.isPS5 }
    }

    private fun startController(host: RegisteredHost, ip: String) {
        Preferences(this).apply {
            onScreenControlsEnabled = true
            touchpadOnlyEnabled = false
        }
        val profile = ConnectVideoProfile.preset(
            VideoResolutionPreset.RES_360P,
            VideoFPSPreset.FPS_30,
            Codec.CODEC_H264
        ).copy(bitrate = 2000)
        val connectInfo = ConnectInfo(
            host.target.isPS5,
            ip,
            host.rpRegistKey,
            host.rpKey,
            profile
        )
        startActivity(Intent(this, StreamActivity::class.java).apply {
            putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
            putExtra(StreamActivity.EXTRA_CONTROLLER_ONLY, true)
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
