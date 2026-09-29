// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.leo

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.metallic.chiaki.common.MacAddress
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.lib.ChiakiLog
import com.metallic.chiaki.lib.Regist
import com.metallic.chiaki.lib.RegistEvent
import com.metallic.chiaki.lib.RegistEventCanceled
import com.metallic.chiaki.lib.RegistEventFailed
import com.metallic.chiaki.lib.RegistEventSuccess
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LeoRegisterActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_HOST = "leo_ps5_host"

        private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
        private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
        private const val REDIRECT_URI = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
        private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
        private val LOGIN_URL =
            "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
            "?service_entity=urn:service-entity:psn" +
            "&response_type=code" +
            "&client_id=$CLIENT_ID" +
            "&redirect_uri=$REDIRECT_URI" +
            "&scope=psn:clientapp" +
            "&request_locale=it_IT" +
            "&ui=pr" +
            "&service_logo=ps" +
            "&layout_type=popup" +
            "&smcid=remoteplay" +
            "&prompt=always" +
            "&PlatformPrivacyWs1=minimal&"
    }

    private lateinit var root: LinearLayout
    private var accountIdBase64: String? = null
    private var regist: Regist? = null
    private var registrationFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(6, 9, 14)
        window.navigationBarColor = Color.rgb(6, 9, 14)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(7, 11, 18), Color.rgb(6, 9, 14)))
        }
        setContentView(root)
        showLogin()
    }

    private fun showLogin() {
        root.removeAllViews()
        root.addView(header("Collega PS5", "Accesso PlayStation"))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(Color.rgb(14, 20, 29), dp(22).toFloat(), Color.rgb(38, 53, 74))
            addView(TextView(this@LeoRegisterActivity).apply {
                text = "ACCESSO SICURO"
                textSize = 10f
                letterSpacing = 0.14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(85, 207, 243))
            })
            addView(TextView(this@LeoRegisterActivity).apply {
                text = "La password resta nella pagina Sony. LEO Control non la legge e non la salva."
                textSize = 12.5f
                setTextColor(Color.rgb(147, 160, 180))
                setLineSpacing(0f, 1.08f)
                setPadding(0, dp(7), 0, 0)
            })
        }
        root.addView(info, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(18), dp(8), dp(18), dp(12)) })

        val webShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = rounded(Color.rgb(14, 20, 29), dp(22).toFloat(), Color.rgb(38, 53, 74))
        }
        val webView = WebView(this)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                maybeHandleRedirect(request?.url)

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                maybeHandleRedirect(url?.let(Uri::parse))
        }
        webShell.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(webShell, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { setMargins(dp(18), 0, dp(18), dp(18)) })

        webView.loadUrl(LOGIN_URL)
    }

    private fun maybeHandleRedirect(uri: Uri?): Boolean {
        if (uri == null) return false
        if (!uri.toString().startsWith(REDIRECT_URI)) return false

        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            Toast.makeText(this, "Accesso PlayStation non completato. Riprova.", Toast.LENGTH_LONG).show()
            showLogin()
            return true
        }

        showWorking("Recupero Account ID PlayStation…")
        lifecycleScope.launch {
            try {
                val account = withContext(Dispatchers.IO) { fetchAccountId(code) }
                accountIdBase64 = account
                showPinStep()
            } catch (e: Exception) {
                Toast.makeText(
                    this@LeoRegisterActivity,
                    "Errore accesso PlayStation: ${e.message ?: "sconosciuto"}",
                    Toast.LENGTH_LONG
                ).show()
                showLogin()
            }
        }
        return true
    }

    private fun fetchAccountId(code: String): String {
        val basic = Base64.encodeToString(
            "$CLIENT_ID:$CLIENT_SECRET".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val post = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Authorization", "Basic $basic")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        val body = "grant_type=authorization_code" +
            "&code=${enc(code)}" +
            "&redirect_uri=${enc(REDIRECT_URI)}&"

        post.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val tokenText = readResponse(post)
        val accessToken = JSONObject(tokenText).optString("access_token")
        if (accessToken.isBlank()) error("Sony non ha restituito il token di accesso")

        val infoUrl = "$TOKEN_URL/${enc(accessToken)}"
        val get = (URL(infoUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Authorization", "Basic $basic")
            setRequestProperty("Accept", "application/json")
        }
        val accountText = readResponse(get)
        val userId = JSONObject(accountText).optString("user_id")
        if (userId.isBlank()) error("Account ID non presente nella risposta Sony")

        val value = BigInteger(userId)
        val bytes = ByteArray(8)
        var current = value
        val mask = BigInteger.valueOf(255L)
        for (i in 0 until 8) {
            bytes[i] = current.and(mask).toByte()
            current = current.shiftRight(8)
        }
        if (current != BigInteger.ZERO) error("Account ID non valido")

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val clean = try {
                val json = JSONObject(body)
                json.optString("error_description").ifBlank { json.optString("error") }
            } catch (_: Exception) {
                body.take(180)
            }
            error("Sony HTTP $status${if (clean.isNotBlank()) ": $clean" else ""}")
        }
        return body
    }

    private fun showPinStep() {
        root.removeAllViews()
        root.addView(header("Collega PS5", "Ultimo passaggio"))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(Color.rgb(14, 20, 29), dp(26).toFloat(), Color.rgb(38, 53, 74))
        }
        card.addView(TextView(this).apply {
            text = "✓  Account PlayStation verificato"
            textSize = 13f
            setTextColor(Color.rgb(92, 219, 154))
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "Apri sulla PS5:"
            textSize = 12f
            setTextColor(Color.rgb(147, 160, 180))
            setPadding(0, dp(18), 0, dp(5))
        })
        card.addView(TextView(this).apply {
            text = "Impostazioni  →  Sistema  →\nRiproduzione remota  →  Collega dispositivo"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        card.addView(TextView(this).apply {
            text = "Inserisci il PIN di 8 cifre mostrato dalla PS5."
            textSize = 11.5f
            setTextColor(Color.rgb(147, 160, 180))
            setPadding(0, dp(16), 0, dp(10))
        })

        val pin = EditText(this).apply {
            hint = "00000000"
            textSize = 28f
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(75, 88, 105))
            inputType = InputType.TYPE_CLASS_NUMBER
            background = rounded(Color.rgb(21, 29, 41), dp(18).toFloat(), Color.rgb(38, 53, 74))
            setPadding(dp(18), 0, dp(18), 0)
        }
        card.addView(pin, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(68)
        ).apply { setMargins(0, dp(6), 0, dp(14)) })

        val button = TextView(this).apply {
            text = "Collega PS5"
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(5, 18, 24))
            background = rounded(Color.rgb(85, 207, 243), dp(20).toFloat(), Color.TRANSPARENT)
            isClickable = true
            setOnClickListener {
                val value = pin.text.toString().trim()
                if (value.length != 8 || value.any { !it.isDigit() }) {
                    pin.error = "Inserisci il PIN di 8 cifre"
                    return@setOnClickListener
                }
                startRegistration(value.toInt())
            }
        }
        card.addView(button, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
        ))

        root.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(18), dp(16), dp(18), 0) })
    }

    private fun startRegistration(pin: Int) {
        val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
        val account = accountIdBase64
        if (host.isBlank() || account.isNullOrBlank()) {
            Toast.makeText(this, "Dati di registrazione incompleti", Toast.LENGTH_LONG).show()
            return
        }

        showWorking("Collegamento alla PS5…")
        val accountBytes = Base64.decode(account, Base64.DEFAULT)
        val info = RegistInfo(
            Target.PS5_1,
            host,
            false,
            null,
            accountBytes,
            pin
        )

        val log = ChiakiLog(ChiakiLog.Level.ALL.value) { _, _ -> }
        try {
            regist = Regist(info, log) { event -> handleRegistEvent(event) }
        } catch (e: Exception) {
            Toast.makeText(this, "Impossibile avviare la registrazione: ${e.message}", Toast.LENGTH_LONG).show()
            showPinStep()
        }
    }

    private fun handleRegistEvent(event: RegistEvent) {
        when (event) {
            is RegistEventSuccess -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = getDatabase(this@LeoRegisterActivity)
                    val registered = RegisteredHost(event.host)
                    db.registeredHostDao().deleteByMac(MacAddress(event.host.serverMac))
                    db.registeredHostDao().insert(registered)
                    withContext(Dispatchers.Main) {
                        registrationFinished = true
                        Toast.makeText(this@LeoRegisterActivity, "PS5 collegata a LEO Control", Toast.LENGTH_SHORT).show()
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                }
            }
            is RegistEventFailed -> runOnUiThread {
                Toast.makeText(this, "Registrazione PS5 non riuscita. Genera un nuovo PIN e riprova.", Toast.LENGTH_LONG).show()
                showPinStep()
            }
            is RegistEventCanceled -> runOnUiThread {
                if (!registrationFinished) showPinStep()
            }
        }
    }

    private fun showWorking(message: String) {
        root.removeAllViews()
        root.addView(header("Collega PS5", "Connessione in corso"))
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(30), dp(24), dp(30))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(30), dp(28), dp(30))
            background = rounded(Color.rgb(14, 20, 29), dp(28).toFloat(), Color.rgb(38, 53, 74))
        }
        card.addView(ProgressBar(this))
        card.addView(TextView(this).apply {
            text = message
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(4))
        })
        card.addView(TextView(this).apply {
            text = "Non chiudere questa schermata."
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(147, 160, 180))
        })
        wrap.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun header(title: String, subtitle: String = ""): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(10))
        }
        row.addView(TextView(this).apply {
            text = "LEO CONTROL"
            textSize = 9.5f
            letterSpacing = 0.18f
            setTextColor(Color.rgb(85, 207, 243))
            setTypeface(typeface, Typeface.BOLD)
        })
        row.addView(TextView(this).apply {
            text = title
            textSize = 27f
            includeFontPadding = false
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        })
        if (subtitle.isNotBlank()) {
            row.addView(TextView(this).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.rgb(147, 160, 180))
                setPadding(0, dp(3), 0, 0)
            })
        }
        return row
    }

    private fun rounded(color: Int, radius: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    override fun onDestroy() {
        if (!registrationFinished) {
            try { regist?.stop() } catch (_: Exception) {}
        }
        try { regist?.dispose() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
