// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.leo

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
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

        private const val BG = 0xFF090B0FL
        private const val SURFACE = 0xFF11151BL
        private const val SURFACE_2 = 0xFF171C24L
        private const val BORDER = 0xFF27303CL
        private const val TEXT = 0xFFF4F7FAL
        private const val MUTED = 0xFF8B96A5L
        private const val ACCENT = 0xFF63D3E9L
        private const val GREEN = 0xFF62D39AL
        private const val ORANGE = 0xFFF2A65AL
        private const val RED = 0xFFFF7373L
    }

    private lateinit var hub: LeoHubClient
    private var registrationPending = false

    private lateinit var pcStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var fireStatus: TextView
    private lateinit var psStatus: TextView
    private lateinit var homeHeadline: TextView
    private lateinit var homeSubline: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = LeoHubClient(this)
        window.statusBarColor = Color.rgb(9, 11, 15)
        window.navigationBarColor = Color.rgb(9, 11, 15)
        buildHome()
        refreshStatuses()
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
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
            setBackgroundColor(BG.toInt())
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(28))
        }
        scroll.addView(body)

        body.addView(header())
        body.addView(heroCard(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(12), 0, dp(18)) })

        body.addView(sectionHeader("DISPOSITIVI", "Tutto in un'unica Home"))

        val grid = GridLayout(this).apply {
            columnCount = 2
            useDefaultMargins = false
        }

        val pc = deviceCard(
            badge = "PC",
            title = "PC Windows",
            subtitle = "Mouse · tastiera · media",
            mode = "AGENT",
            accent = Color.rgb(99, 211, 233),
            statusSetter = { pcStatus = it }
        ) { openHubRemote(LeoRemoteActivity.DEVICE_PC) }

        val tv = deviceCard(
            badge = "TV",
            title = "Hisense",
            subtitle = "Telecomando · app · touchpad",
            mode = "HUB",
            accent = Color.rgb(104, 215, 160),
            statusSetter = { tvStatus = it }
        ) { openHubRemote(LeoRemoteActivity.DEVICE_TV) }

        val fire = deviceCard(
            badge = "FT",
            title = "Fire TV",
            subtitle = "Telecomando · testo · touchpad",
            mode = "HUB",
            accent = Color.rgb(242, 166, 90),
            statusSetter = { fireStatus = it }
        ) { openHubRemote(LeoRemoteActivity.DEVICE_FIRE) }

        val ps5 = deviceCard(
            badge = "PS",
            title = "PlayStation 5",
            subtitle = "Joypad LEO · diretto",
            mode = "DIRECT",
            accent = Color.rgb(109, 137, 255),
            statusSetter = { psStatus = it }
        ) { openPs5() }

        grid.addView(pc, gridLp())
        grid.addView(tv, gridLp())
        grid.addView(fire, gridLp())
        grid.addView(ps5, gridLp())
        body.addView(grid)

        body.addView(sectionHeader("SCENE RAPIDE", "Un tocco e parti"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(18), 0, dp(4)) })

        val quick1 = row()
        quick1.addView(quickAction("TV", "Accendi e apri TV") { runScene("tv", false) }, weight())
        quick1.addView(quickAction("FIRE", "Apri Fire TV") { runScene("fire_tv", false) }, weight())
        body.addView(quick1)

        val quick2 = row()
        quick2.addView(quickAction("PS5", "Avvia console") { runScene("ps5", true) }, weight())
        quick2.addView(quickAction("NETFLIX", "Apri Netflix") { runScene("netflix", false) }, weight())
        body.addView(quick2)

        body.addView(footer())

        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun header(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = "LEO"
            textSize = 11f
            letterSpacing = 0.22f
            setTextColor(ACCENT.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        left.addView(TextView(this).apply {
            text = "Control"
            textSize = 30f
            setTextColor(TEXT.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })

        val settings = TextView(this).apply {
            text = "⚙"
            gravity = Gravity.CENTER
            textSize = 19f
            setTextColor(TEXT.toInt())
            background = rounded(SURFACE_2.toInt(), dp(16).toFloat(), BORDER.toInt())
            setOnClickListener { showSettings() }
        }

        wrap.addView(left, LinearLayout.LayoutParams(0, dp(66), 1f))
        wrap.addView(settings, LinearLayout.LayoutParams(dp(50), dp(50)))
        return wrap
    }

    private fun heroCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(SURFACE.toInt(), dp(22).toFloat(), BORDER.toInt())
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = TextView(this).apply {
            text = "●"
            textSize = 14f
            setTextColor(GREEN.toInt())
        }
        val label = TextView(this).apply {
            text = "  CASA CONNESSA"
            textSize = 11f
            letterSpacing = 0.10f
            setTextColor(MUTED.toInt())
            setTypeface(typeface, Typeface.BOLD)
        }
        top.addView(dot)
        top.addView(label)
        card.addView(top)

        homeHeadline = TextView(this).apply {
            text = "I tuoi dispositivi, senza confusione."
            textSize = 20f
            setTextColor(TEXT.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }
        homeSubline = TextView(this).apply {
            text = "PS5 diretta dal telefono. PC, TV e Fire TV seguono lo stato del LEO Agent."
            textSize = 12.5f
            setTextColor(MUTED.toInt())
            setLineSpacing(0f, 1.08f)
        }
        card.addView(homeHeadline)
        card.addView(homeSubline)
        return card
    }

    private fun sectionHeader(title: String, subtitle: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = title
            textSize = 12f
            letterSpacing = 0.14f
            setTextColor(TEXT.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        left.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(MUTED.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun deviceCard(
        badge: String,
        title: String,
        subtitle: String,
        mode: String,
        accent: Int,
        statusSetter: (TextView) -> Unit,
        action: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(SURFACE.toInt(), dp(22).toFloat(), BORDER.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badgeView = TextView(this).apply {
            text = badge
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(accent, dp(14).toFloat(), Color.TRANSPARENT)
        }
        val modeView = TextView(this).apply {
            text = mode
            gravity = Gravity.CENTER
            textSize = 9f
            letterSpacing = 0.08f
            setTextColor(MUTED.toInt())
            background = rounded(SURFACE_2.toInt(), dp(10).toFloat(), BORDER.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        top.addView(badgeView, LinearLayout.LayoutParams(dp(42), dp(42)))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(modeView)
        card.addView(top)

        card.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(TEXT.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(3))
        })
        card.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(MUTED.toInt())
            maxLines = 2
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val status = TextView(this).apply {
            text = "● controllo…"
            textSize = 10.5f
            setTextColor(MUTED.toInt())
        }
        statusSetter(status)
        val chevron = TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(MUTED.toInt())
        }
        bottom.addView(status, LinearLayout.LayoutParams(0, dp(28), 1f))
        bottom.addView(chevron, LinearLayout.LayoutParams(dp(24), dp(28)))
        card.addView(bottom)
        return card
    }

    private fun quickAction(title: String, subtitle: String, action: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(SURFACE_2.toInt(), dp(18).toFloat(), BORDER.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(TextView(this@LeoMainActivity).apply {
                text = title
                textSize = 12f
                letterSpacing = 0.08f
                setTextColor(TEXT.toInt())
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@LeoMainActivity).apply {
                text = subtitle
                textSize = 10.5f
                setTextColor(MUTED.toInt())
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    private fun footer(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(18), dp(2), 0)
            addView(TextView(this@LeoMainActivity).apply {
                text = "LEO Control  0.11.0"
                textSize = 10.5f
                setTextColor(Color.rgb(90, 98, 110))
            }, LinearLayout.LayoutParams(0, dp(28), 1f))
            addView(TextView(this@LeoMainActivity).apply {
                text = "LOCAL FIRST"
                textSize = 9f
                letterSpacing = 0.12f
                setTextColor(Color.rgb(90, 98, 110))
            })
        }
    }

    private fun refreshStatuses() {
        lifecycleScope.launch {
            val registered = firstRegisteredPs5()
            runOnUiThread {
                if (::psStatus.isInitialized) {
                    if (registered != null) {
                        psStatus.text = "● pronto"
                        psStatus.setTextColor(GREEN.toInt())
                    } else {
                        psStatus.text = "● da associare"
                        psStatus.setTextColor(ORANGE.toInt())
                    }
                }
            }
        }

        hub.getStatus { reachable, paired, _ ->
            runOnUiThread {
                val text: String
                val color: Int
                when {
                    !reachable -> {
                        text = "● agent offline"
                        color = ORANGE.toInt()
                        homeSubline.text = "PS5 è disponibile. Per PC, Hisense e Fire TV l'Agent Windows non è attivo."
                    }
                    paired -> {
                        text = "● pronto"
                        color = GREEN.toInt()
                        homeSubline.text = "Tutti i controlli locali sono pronti. PS5 lavora direttamente dal telefono."
                    }
                    else -> {
                        text = "● da associare"
                        color = ORANGE.toInt()
                        homeSubline.text = "LEO Agent è raggiungibile ma questo telefono deve ancora essere associato."
                    }
                }
                listOfNotNull(
                    if (::pcStatus.isInitialized) pcStatus else null,
                    if (::tvStatus.isInitialized) tvStatus else null,
                    if (::fireStatus.isInitialized) fireStatus else null
                ).forEach {
                    it.text = text
                    it.setTextColor(color)
                }
            }
        }
    }

    private fun openHubRemote(device: String) {
        hub.getStatus { reachable, paired, _ ->
            runOnUiThread {
                when {
                    !reachable -> showAgentOffline(device)
                    !paired -> showPairDialog {
                        startActivity(Intent(this, LeoRemoteActivity::class.java).putExtra(LeoRemoteActivity.EXTRA_DEVICE, device))
                    }
                    else -> startActivity(Intent(this, LeoRemoteActivity::class.java).putExtra(LeoRemoteActivity.EXTRA_DEVICE, device))
                }
            }
        }
    }

    private fun showAgentOffline(device: String) {
        val name = when (device) {
            LeoRemoteActivity.DEVICE_TV -> "Hisense"
            LeoRemoteActivity.DEVICE_FIRE -> "Fire TV"
            else -> "PC"
        }
        AlertDialog.Builder(this)
            .setTitle("$name non raggiungibile")
            .setMessage(
                "LEO Agent sul PC non è attivo. In questa build PC, Hisense e Fire TV passano ancora dall'Agent. " +
                    "La PS5 invece funziona già direttamente dal telefono."
            )
            .setPositiveButton("Impostazioni") { _, _ -> showSettings() }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun runScene(scene: String, openNativePs5: Boolean) {
        if (openNativePs5) {
            openPs5()
            return
        }
        hub.runScene(scene) { ok, _, message, code ->
            runOnUiThread {
                when {
                    code == 401 -> showPairDialog { runScene(scene, false) }
                    !ok -> Toast.makeText(this, message.ifBlank { "LEO Agent non disponibile" }, Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showPairDialog(afterSuccess: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Codice a 6 cifre"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Associa LEO Agent")
            .setMessage("Inserisci il codice mostrato da LEO Agent sul PC. L'associazione viene salvata su questo telefono.")
            .setView(input)
            .setPositiveButton("Associa") { _, _ ->
                val code = input.text.toString().trim()
                hub.pair(code) { ok, msg ->
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        refreshStatuses()
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

        box.addView(TextView(this).apply {
            text = "LEO Agent"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, 0)
        })
        val hubInput = EditText(this).apply {
            hint = "192.168.31.75:8765"
            setText(hub.hubUrl())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(hubInput)

        box.addView(TextView(this).apply {
            text = "PlayStation 5"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(12), 0, 0)
        })
        val ps5 = EditText(this).apply {
            hint = "IP PS5"
            setText(ps5Ip())
            inputType = InputType.TYPE_CLASS_PHONE
        }
        box.addView(ps5)

        AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setView(box)
            .setPositiveButton("Salva") { _, _ ->
                hub.setHubUrl(hubInput.text.toString())
                prefs.edit().putString(KEY_PS5_IP, ps5.text.toString().trim()).apply()
                refreshStatuses()
            }
            .setNeutralButton("Associa Agent") { _, _ -> showPairDialog() }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun ps5Ip(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_PS5_IP, DEFAULT_PS5_IP)?.trim().orEmpty()

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

    private fun gridLp(): GridLayout.LayoutParams =
        GridLayout.LayoutParams().apply {
            width = 0
            height = dp(176)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun weight() = LinearLayout.LayoutParams(0, dp(72), 1f).apply {
        setMargins(dp(5), 0, dp(5), 0)
    }

    private fun rounded(color: Int, radius: Float, stroke: Int) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
