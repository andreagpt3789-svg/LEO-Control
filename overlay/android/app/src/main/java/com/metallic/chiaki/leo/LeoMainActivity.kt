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

        private const val BG = 0xFF06090EL
        private const val SURFACE = 0xFF0E141DL
        private const val SURFACE_2 = 0xFF151D29L
        private const val BORDER = 0xFF26354AL
        private const val TEXT = 0xFFF4F7FAL
        private const val MUTED = 0xFF93A0B4L
        private const val ACCENT = 0xFF55CFF3L
        private const val GREEN = 0xFF5CDB9AL
        private const val ORANGE = 0xFFFFB35CL
        private const val RED = 0xFFFF6F7DL
    }

    private lateinit var hub: LeoHubClient
    private lateinit var vidaa: LeoVidaaClient
    private lateinit var fire: LeoFireClient
    private var registrationPending = false

    private lateinit var pcStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var fireStatus: TextView
    private lateinit var psStatus: TextView
    private lateinit var homeSubline: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = LeoHubClient(this)
        vidaa = LeoVidaaClient(this)
        fire = LeoFireClient(this)
        window.statusBarColor = BG.toInt()
        window.navigationBarColor = BG.toInt()
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

    override fun onDestroy() {
        Thread {
            runCatching { vidaa.disconnect() }
            runCatching { fire.close() }
        }.apply {
            name = "LEO-Home-Cleanup"
            isDaemon = true
        }.start()
        super.onDestroy()
    }

    private fun buildHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(7, 11, 18), BG.toInt())
            )
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        scroll.addView(body)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val system = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            body.setPadding(dp(20), system.top + dp(8), dp(20), system.bottom + dp(28))
            insets
        }

        body.addView(header())
        body.addView(heroCard(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(10), 0, dp(26)) })

        body.addView(sectionHeader("DISPOSITIVI", "Tutto da qui"))

        body.addView(deviceCard(
            badge = "PC",
            title = "PC Windows",
            subtitle = "Mouse, tastiera e controlli media",
            mode = "AGENT",
            accent = Color.rgb(85, 207, 243),
            statusSetter = { pcStatus = it }
        ) { openDevice(LeoRemoteActivity.DEVICE_PC) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(104)
        ).apply { setMargins(0, dp(8), 0, dp(4)) })

        body.addView(deviceCard(
            badge = "TV",
            title = "Hisense",
            subtitle = "Telecomando, app e navigazione",
            mode = "DIRECT",
            accent = Color.rgb(92, 219, 154),
            statusSetter = { tvStatus = it }
        ) { openDevice(LeoRemoteActivity.DEVICE_TV) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(104)
        ).apply { setMargins(0, dp(4), 0, dp(4)) })

        body.addView(deviceCard(
            badge = "FT",
            title = "Fire TV",
            subtitle = "Telecomando, testo e gesti",
            mode = "DIRECT",
            accent = Color.rgb(255, 179, 92),
            statusSetter = { fireStatus = it }
        ) { openDevice(LeoRemoteActivity.DEVICE_FIRE) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(104)
        ).apply { setMargins(0, dp(4), 0, dp(4)) })

        body.addView(deviceCard(
            badge = "PS",
            title = "PlayStation 5",
            subtitle = "Joypad LEO diretto",
            mode = "DIRECT",
            accent = Color.rgb(112, 139, 255),
            statusSetter = { psStatus = it }
        ) { openPs5() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(104)
        ).apply { setMargins(0, dp(4), 0, dp(8)) })

        body.addView(sectionHeader("SCORCIATOIE", "Un tocco e vai"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(22), 0, dp(6)) })

        val quick1 = row()
        quick1.addView(quickAction("TV", "Telecomando") {
            openDevice(LeoRemoteActivity.DEVICE_TV)
        }, weight())
        quick1.addView(quickAction("FIRE", "Telecomando") {
            openDevice(LeoRemoteActivity.DEVICE_FIRE)
        }, weight())
        body.addView(quick1)

        val quick2 = row()
        quick2.addView(quickAction("PS5", "Joypad") { openPs5() }, weight())
        quick2.addView(quickAction("NETFLIX", "Su Hisense") { launchNetflixDirect() }, weight())
        body.addView(quick2)

        body.addView(footer())
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    private fun header(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(8))
        }

        val mark = TextView(this).apply {
            text = "L"
            gravity = Gravity.CENTER
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(5, 14, 20))
            background = rounded(ACCENT.toInt(), dp(18).toFloat(), Color.TRANSPARENT)
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        left.addView(TextView(this).apply {
            text = "LEO CONTROL"
            textSize = 9.5f
            letterSpacing = 0.22f
            setTextColor(ACCENT.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        left.addView(TextView(this).apply {
            text = "Control Center"
            textSize = 27f
            setTextColor(TEXT.toInt())
            setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
        })

        val settings = TextView(this).apply {
            text = "SET"
            gravity = Gravity.CENTER
            textSize = 9f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT.toInt())
            background = rounded(Color.rgb(17, 25, 36), dp(22).toFloat(), Color.rgb(38, 53, 74))
            isClickable = true
            isFocusable = true
            setOnClickListener { showSettings() }
        }

        wrap.addView(mark, LinearLayout.LayoutParams(dp(46), dp(46)))
        wrap.addView(left, LinearLayout.LayoutParams(0, dp(58), 1f))
        wrap.addView(settings, LinearLayout.LayoutParams(dp(48), dp(44)))
        return wrap
    }

    private fun heroCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(18, 51, 70),
                    Color.rgb(11, 24, 36),
                    Color.rgb(8, 14, 22)
                )
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.rgb(37, 82, 103))
            }
            elevation = dp(3).toFloat()
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "LIVE"
            textSize = 8.5f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(7, 25, 20))
            background = rounded(GREEN.toInt(), dp(10).toFloat(), Color.TRANSPARENT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })
        top.addView(TextView(this).apply {
            text = "  RETE LOCALE"
            textSize = 9.5f
            letterSpacing = 0.14f
            setTextColor(Color.rgb(165, 190, 205))
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(top)

        card.addView(TextView(this).apply {
            text = "Tutto sotto controllo."
            textSize = 25f
            setTextColor(TEXT.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(13), 0, dp(5))
        })
        homeSubline = TextView(this).apply {
            text = "PC · Hisense · Fire TV · PlayStation 5"
            textSize = 12f
            setTextColor(Color.rgb(173, 192, 208))
        }
        card.addView(homeSubline)

        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(15), 0, 0)
        }
        listOf(
            Color.rgb(85, 207, 243),
            Color.rgb(72, 218, 190),
            Color.rgb(255, 170, 74),
            Color.rgb(112, 139, 255)
        ).forEach { color ->
            line.addView(View(this).apply {
                background = rounded(color, dp(2).toFloat(), Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(0, dp(4), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        card.addView(line)

        return card
    }

    private fun sectionHeader(title: String, subtitle: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(6))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = title
            textSize = 11f
            letterSpacing = 0.15f
            setTextColor(TEXT.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        left.addView(TextView(this).apply {
            text = subtitle
            textSize = 10.5f
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(17, 24, 34), Color.rgb(9, 13, 20))
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(
                    dp(1),
                    Color.argb(88, Color.red(accent), Color.green(accent), Color.blue(accent))
                )
            }
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

        val accentBar = View(this).apply {
            background = rounded(accent, dp(3).toFloat(), Color.TRANSPARENT)
        }
        card.addView(accentBar, LinearLayout.LayoutParams(dp(4), dp(56)).apply {
            setMargins(0, 0, dp(10), 0)
        })

        val badgeView = TextView(this).apply {
            text = badge
            gravity = Gravity.CENTER
            textSize = 11.5f
            setTextColor(Color.rgb(6, 13, 18))
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(accent, dp(18).toFloat(), Color.TRANSPARENT)
        }
        card.addView(badgeView, LinearLayout.LayoutParams(dp(50), dp(50)))

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
        }
        center.addView(TextView(this).apply {
            text = title
            textSize = 17.5f
            includeFontPadding = false
            setTextColor(TEXT.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        center.addView(TextView(this).apply {
            text = subtitle
            textSize = 10.8f
            setTextColor(MUTED.toInt())
            setPadding(0, dp(3), 0, dp(5))
            maxLines = 1
        })
        val status = TextView(this).apply {
            text = "controllo…"
            textSize = 9.8f
            setTextColor(MUTED.toInt())
        }
        statusSetter(status)
        center.addView(status)
        card.addView(center, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        right.addView(TextView(this).apply {
            text = mode
            gravity = Gravity.CENTER
            textSize = 8f
            letterSpacing = 0.10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            background = rounded(
                Color.argb(25, Color.red(accent), Color.green(accent), Color.blue(accent)),
                dp(10).toFloat(),
                Color.argb(55, Color.red(accent), Color.green(accent), Color.blue(accent))
            )
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })
        right.addView(TextView(this).apply {
            text = "›"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(accent)
        }, LinearLayout.LayoutParams(dp(34), dp(38)))
        card.addView(right, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.MATCH_PARENT))
        return card
    }

    private fun quickAction(title: String, subtitle: String, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(19, 27, 38), Color.rgb(12, 17, 25))
            ).apply {
                cornerRadius = dp(19).toFloat()
                setStroke(dp(1), Color.rgb(36, 49, 67))
            }
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(TextView(this@LeoMainActivity).apply {
                text = title
                textSize = 10f
                letterSpacing = 0.11f
                setTextColor(ACCENT.toInt())
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@LeoMainActivity).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(TEXT.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun footer(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(24), dp(2), 0)
            addView(TextView(this@LeoMainActivity).apply {
                text = "LEO Control  0.13.8"
                textSize = 10f
                setTextColor(Color.rgb(81, 94, 112))
            }, LinearLayout.LayoutParams(0, dp(28), 1f))
            addView(TextView(this@LeoMainActivity).apply {
                text = "PHONE FIRST"
                textSize = 8.5f
                letterSpacing = 0.14f
                setTextColor(Color.rgb(81, 94, 112))
            })
        }

    private fun refreshStatuses() {
        lifecycleScope.launch {
            val registered = firstRegisteredPs5()
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

        Thread {
            val tvReachable = vidaa.isReachable()
            val tvPaired = vidaa.isPaired()
            val fireIp = fire.savedIp()
            val fireReachable = fire.isReachable()
            runOnUiThread {
                if (::tvStatus.isInitialized) {
                    when {
                        tvReachable && tvPaired -> {
                            tvStatus.text = "● diretto"
                            tvStatus.setTextColor(GREEN.toInt())
                        }
                        tvReachable -> {
                            tvStatus.text = "● da associare"
                            tvStatus.setTextColor(ORANGE.toInt())
                        }
                        else -> {
                            tvStatus.text = "● TV offline"
                            tvStatus.setTextColor(MUTED.toInt())
                        }
                    }
                }
                if (::fireStatus.isInitialized) {
                    when {
                        fireReachable -> {
                            fireStatus.text = "● diretto"
                            fireStatus.setTextColor(GREEN.toInt())
                        }
                        fireIp.isBlank() -> {
                            fireStatus.text = "● da trovare"
                            fireStatus.setTextColor(ORANGE.toInt())
                        }
                        else -> {
                            fireStatus.text = "● Fire TV offline"
                            fireStatus.setTextColor(MUTED.toInt())
                        }
                    }
                }
            }
        }.start()

        hub.getStatus { reachable, paired, _ ->
            runOnUiThread {
                if (::pcStatus.isInitialized) {
                    when {
                        !reachable -> {
                            pcStatus.text = "● agent offline"
                            pcStatus.setTextColor(MUTED.toInt())
                        }
                        paired -> {
                            pcStatus.text = "● agent pronto"
                            pcStatus.setTextColor(GREEN.toInt())
                        }
                        else -> {
                            pcStatus.text = "● agent da associare"
                            pcStatus.setTextColor(ORANGE.toInt())
                        }
                    }
                }
            }
        }
    }

    private fun openDevice(device: String) {
        if (device == LeoRemoteActivity.DEVICE_PC) {
            openPcRemote()
        } else {
            startActivity(Intent(this, LeoRemoteActivity::class.java).putExtra(
                LeoRemoteActivity.EXTRA_DEVICE,
                device
            ))
        }
    }

    private fun openPcRemote() {
        hub.getStatus { reachable, paired, _ ->
            runOnUiThread {
                when {
                    !reachable -> AlertDialog.Builder(this)
                        .setTitle("PC non raggiungibile")
                        .setMessage(
                            "Solo il controllo di Windows richiede LEO Agent sul PC. " +
                                "Hisense, Fire TV e PS5 non passano più dal computer."
                        )
                        .setPositiveButton("Impostazioni") { _, _ -> showSettings() }
                        .setNegativeButton("Chiudi", null)
                        .show()

                    !paired -> showPairDialog {
                        startActivity(Intent(this, LeoRemoteActivity::class.java).putExtra(
                            LeoRemoteActivity.EXTRA_DEVICE,
                            LeoRemoteActivity.DEVICE_PC
                        ))
                    }

                    else -> startActivity(Intent(this, LeoRemoteActivity::class.java).putExtra(
                        LeoRemoteActivity.EXTRA_DEVICE,
                        LeoRemoteActivity.DEVICE_PC
                    ))
                }
            }
        }
    }

    private fun launchNetflixDirect() {
        if (!vidaa.isPaired()) {
            openDevice(LeoRemoteActivity.DEVICE_TV)
            return
        }
        Thread {
            val result = vidaa.launchApp("netflix")
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (result.isSuccess) "Netflix avviato sulla Hisense"
                    else result.exceptionOrNull()?.message ?: "Hisense non raggiungibile",
                    Toast.LENGTH_LONG
                ).show()
                refreshStatuses()
            }
        }.start()
    }

    private fun showPairDialog(afterSuccess: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Codice a 6 cifre"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Associa LEO Agent")
            .setMessage("Questo pairing riguarda solo il controllo del PC Windows.")
            .setView(input)
            .setPositiveButton("Associa") { _, _ ->
                hub.pair(input.text.toString().trim()) { ok, msg ->
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

        fun fieldLabel(value: String) {
            box.addView(TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(10), 0, 0)
            })
        }

        fieldLabel("Hisense VIDAA")
        val tvIp = EditText(this).apply {
            hint = "IP TV"
            setText(vidaa.savedIp())
            inputType = InputType.TYPE_CLASS_PHONE
        }
        box.addView(tvIp)

        fieldLabel("Fire TV")
        val fireIp = EditText(this).apply {
            hint = "IP Fire TV (vuoto = ricerca automatica)"
            setText(fire.savedIp())
            inputType = InputType.TYPE_CLASS_PHONE
        }
        box.addView(fireIp)

        fieldLabel("PlayStation 5")
        val ps5 = EditText(this).apply {
            hint = "IP PS5"
            setText(ps5Ip())
            inputType = InputType.TYPE_CLASS_PHONE
        }
        box.addView(ps5)

        fieldLabel("PC Windows · LEO Agent")
        val hubInput = EditText(this).apply {
            hint = "192.168.31.75:8765"
            setText(hub.hubUrl())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(hubInput)

        AlertDialog.Builder(this)
            .setTitle("Dispositivi")
            .setView(box)
            .setPositiveButton("Salva") { _, _ ->
                vidaa.setIp(tvIp.text.toString())
                fire.setIp(fireIp.text.toString())
                prefs.edit().putString(KEY_PS5_IP, ps5.text.toString().trim()).apply()
                hub.setHubUrl(hubInput.text.toString())
                refreshStatuses()
            }
            .setNeutralButton("Associa PC") { _, _ -> showPairDialog() }
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
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun weight() = LinearLayout.LayoutParams(0, dp(72), 1f).apply {
        setMargins(dp(4), 0, dp(4), 0)
    }

    private fun rounded(color: Int, radius: Float, stroke: Int) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun gradient(start: Int, end: Int, radius: Float) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = radius
            setStroke(dp(1), Color.rgb(38, 58, 76))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
