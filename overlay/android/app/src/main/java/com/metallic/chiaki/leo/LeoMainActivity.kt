// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.leo

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LeoMainActivity : AppCompatActivity() {
    companion object {
        private const val PREFS = "leo_control_native"
        private const val KEY_PS5_IP = "ps5_ip"
        private const val DEFAULT_PS5_IP = "192.168.31.94"
    }

    private lateinit var hub: LeoHubClient
    private lateinit var hubStatus: TextView
    private var registrationPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = LeoHubClient(this)
        window.statusBarColor = Color.rgb(11, 13, 16)
        window.navigationBarColor = Color.rgb(11, 13, 16)
        buildHome()
        refreshHubStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshHubStatus()
        if (!registrationPending) return
        registrationPending = false
        val ip = ps5Ip()
        lifecycleScope.launch {
            val registered = firstRegisteredPs5()
            if (registered != null && ip.isNotBlank()) startController(registered, ip)
        }
    }

    private fun buildHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 13, 16))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(12), dp(10))
        }
        val titleWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleWrap.addView(TextView(this).apply {
            text = "LEO Control"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        hubStatus = TextView(this).apply {
            text = "● Hub…"
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }
        titleWrap.addView(hubStatus)

        val settings = Button(this).apply {
            text = "⚙"
            textSize = 18f
            minWidth = 0
            setOnClickListener { showSettings() }
        }
        top.addView(titleWrap, LinearLayout.LayoutParams(0, dp(70), 1f))
        top.addView(settings, LinearLayout.LayoutParams(dp(58), dp(52)))
        root.addView(top)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(26))
        }
        scroll.addView(body)

        body.addView(section("I tuoi dispositivi"))

        val grid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
            useDefaultMargins = false
        }

        grid.addView(tile("🖥", "PC", "Mouse · tastiera · media") {
            openHubRemote(LeoRemoteActivity.DEVICE_PC)
        }, gridLp())
        grid.addView(tile("📺", "Hisense", "Telecomando · app · touchpad") {
            openHubRemote(LeoRemoteActivity.DEVICE_TV)
        }, gridLp())
        grid.addView(tile("🔥", "Fire TV", "Telecomando · testo · touchpad") {
            openHubRemote(LeoRemoteActivity.DEVICE_FIRE)
        }, gridLp())
        grid.addView(tile("🎮", "PS5", "Joypad LEO · diretto dal telefono") {
            openPs5()
        }, gridLp())

        body.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body.addView(section("Scene rapide"))

        val scenes1 = row()
        scenes1.addView(sceneButton("📺 TV") { runScene("tv", false) }, weight())
        scenes1.addView(sceneButton("🔥 Fire TV") { runScene("fire_tv", false) }, weight())
        scenes1.addView(sceneButton("🎮 PS5") { runScene("ps5", true) }, weight())
        body.addView(scenes1)

        val scenes2 = row()
        scenes2.addView(sceneButton("N  Netflix") { runScene("netflix", false) }, weight())
        scenes2.addView(sceneButton("▶  YouTube") { runScene("youtube", false) }, weight())
        body.addView(scenes2)

        val pairButton = Button(this).apply {
            text = "Associa / riassocia LEO Hub"
            setOnClickListener { showPairDialog() }
        }
        body.addView(pairButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            setMargins(0, dp(18), 0, 0)
        })

        body.addView(TextView(this).apply {
            text = "v0.10.0  •  una sola Home per tutti i telecomandi e il joypad"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(0, dp(20), 0, 0)
        })

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun refreshHubStatus() {
        if (!::hubStatus.isInitialized) return
        hub.getStatus { reachable, paired, _ ->
            runOnUiThread {
                when {
                    !reachable -> {
                        hubStatus.text = "● Hub offline"
                        hubStatus.setTextColor(Color.rgb(255, 151, 91))
                    }
                    paired -> {
                        hubStatus.text = "● Hub connesso"
                        hubStatus.setTextColor(Color.rgb(90, 230, 150))
                    }
                    else -> {
                        hubStatus.text = "● Hub da associare"
                        hubStatus.setTextColor(Color.rgb(255, 211, 91))
                    }
                }
            }
        }
    }

    private fun openHubRemote(device: String) {
        hub.getStatus { reachable, paired, _ ->
            runOnUiThread {
                when {
                    !reachable -> Toast.makeText(
                        this,
                        "LEO Hub non raggiungibile. Avvialo sul PC e controlla l'indirizzo nelle impostazioni.",
                        Toast.LENGTH_LONG
                    ).show()
                    !paired -> showPairDialog {
                        startActivity(Intent(this, LeoRemoteActivity::class.java).putExtra(LeoRemoteActivity.EXTRA_DEVICE, device))
                    }
                    else -> startActivity(Intent(this, LeoRemoteActivity::class.java).putExtra(LeoRemoteActivity.EXTRA_DEVICE, device))
                }
            }
        }
    }

    private fun runScene(scene: String, openNativePs5: Boolean) {
        hub.runScene(scene) { ok, _, message, code ->
            runOnUiThread {
                if (code == 401) {
                    showPairDialog { runScene(scene, openNativePs5) }
                    return@runOnUiThread
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                if (ok && openNativePs5) openPs5()
            }
        }
    }

    private fun showPairDialog(afterSuccess: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Codice a 6 cifre"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Associa telefono al LEO Hub")
            .setMessage("Inserisci il codice mostrato nella finestra LEO Control sul PC. Serve una sola volta.")
            .setView(input)
            .setPositiveButton("Associa") { _, _ ->
                val code = input.text.toString().trim()
                hub.pair(code) { ok, msg ->
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        refreshHubStatus()
                        if (ok) afterSuccess?.invoke()
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val hubInput = EditText(this).apply {
            hint = "LEO Hub (es. 192.168.31.75:8765)"
            setText(hub.hubUrl())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val ps5 = EditText(this).apply {
            hint = "IP PS5"
            setText(ps5Ip())
            inputType = InputType.TYPE_CLASS_PHONE
        }
        box.addView(hubInput)
        box.addView(ps5)

        AlertDialog.Builder(this)
            .setTitle("LEO Control")
            .setView(box)
            .setPositiveButton("Salva") { _, _ ->
                hub.setHubUrl(hubInput.text.toString())
                prefs.edit().putString(KEY_PS5_IP, ps5.text.toString().trim()).apply()
                refreshHubStatus()
            }
            .setNeutralButton("Associa Hub") { _, _ -> showPairDialog() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun ps5Ip(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PS5_IP, DEFAULT_PS5_IP)?.trim().orEmpty()

    private fun openPs5() {
        val ip = ps5Ip()
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
                    "Prima registrazione PS5: login PlayStation e PIN una sola volta.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(this@LeoMainActivity, LeoRegisterActivity::class.java).apply {
                    putExtra(LeoRegisterActivity.EXTRA_HOST, ip)
                })
            } else {
                startController(registered, ip)
            }
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
        startActivity(Intent(this, LeoPs5ControllerActivity::class.java).apply {
            putExtra(LeoPs5ControllerActivity.EXTRA_CONNECT_INFO, connectInfo)
        })
    }

    private fun tile(icon: String, title: String, subtitle: String, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(14))
            background = rounded(Color.rgb(24, 28, 35), dp(22).toFloat())
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(TextView(this@LeoMainActivity).apply {
                text = icon
                textSize = 34f
                gravity = Gravity.CENTER
            })
            addView(TextView(this@LeoMainActivity).apply {
                text = title
                textSize = 19f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@LeoMainActivity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            })
        }

    private fun gridLp(): GridLayout.LayoutParams =
        GridLayout.LayoutParams().apply {
            width = 0
            height = dp(160)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(6), dp(6), dp(6), dp(6))
        }

    private fun section(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.LTGRAY)
        setPadding(dp(4), dp(14), dp(4), dp(6))
    }

    private fun sceneButton(value: String, action: () -> Unit): Button =
        Button(this).apply {
            text = value
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(34, 39, 49))
            setOnClickListener { action() }
        }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun weight() = LinearLayout.LayoutParams(0, dp(58), 1f).apply {
        setMargins(dp(4), 0, dp(4), 0)
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
        setStroke(dp(1), Color.rgb(55, 63, 77))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
