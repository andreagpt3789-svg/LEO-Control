// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.leo

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

class LeoRemoteActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DEVICE = "leo_device"
        const val DEVICE_PC = "pc"
        const val DEVICE_TV = "tv_hisense"
        const val DEVICE_FIRE = "fire_tv"

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
    private lateinit var device: String
    private lateinit var status: TextView
    private var socket: WebSocket? = null
    private var vidaa: LeoVidaaClient? = null
    private var fire: LeoFireClient? = null
    @Volatile private var fireConnecting = false
    private var vidaaPairDialogVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG.toInt()
        window.navigationBarColor = BG.toInt()
        hub = LeoHubClient(this)
        device = intent.getStringExtra(EXTRA_DEVICE) ?: DEVICE_PC
        if (device == DEVICE_TV) vidaa = LeoVidaaClient(this)
        if (device == DEVICE_FIRE) fire = LeoFireClient(this)
        buildUi()

        when (device) {
            DEVICE_PC -> ensureSocket("/ws/pc")
            DEVICE_TV -> {
                if (vidaa?.isPaired() == true) {
                    setStatus("● diretto", GREEN.toInt())
                } else {
                    setStatus("● da associare", ORANGE.toInt())
                    showVidaaPairingIntro()
                }
            }
            DEVICE_FIRE -> {
                setStatus("AVVIO", MUTED.toInt())
                window.decorView.postDelayed({
                    if (!isFinishing && !isDestroyed) ensureFireAutoConnected()
                }, 350L)
            }
        }
    }

    override fun onDestroy() {
        socket?.close(1000, "close")
        socket = null
        vidaa?.disconnect()
        fire?.close()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(7, 11, 18), BG.toInt())
            )
        }
        val bar = topBar()
        root.addView(bar)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(30))
        }
        scroll.addView(body)

        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Android 15 edge-to-edge: move the complete remote below the status
        // bar/notch and above the gesture/navigation area.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, system.top, 0, system.bottom)
            insets
        }

        when (device) {
            DEVICE_PC -> buildPc(body)
            DEVICE_TV -> buildTv(body)
            DEVICE_FIRE -> buildFire(body)
        }
        setContentView(root)
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    private fun topBar(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 31f
            gravity = Gravity.CENTER
            setTextColor(TEXT.toInt())
            background = rounded(Color.rgb(18, 27, 39), dp(22).toFloat(), Color.rgb(43, 61, 82))
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        titles.addView(TextView(this).apply {
            text = "LEO CONTROL"
            textSize = 9f
            letterSpacing = 0.18f
            setTextColor(deviceAccent())
            setTypeface(typeface, Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            text = when (device) {
                DEVICE_TV -> "Hisense"
                DEVICE_FIRE -> "Fire TV"
                else -> "PC Windows"
            }
            textSize = 25f
            includeFontPadding = false
            setTextColor(TEXT.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            text = when (device) {
                DEVICE_TV -> "VIDAA · controllo diretto"
                DEVICE_FIRE -> "ADB · controllo diretto"
                else -> "LEO Agent · rete locale"
            }
            textSize = 10.5f
            setTextColor(MUTED.toInt())
            setPadding(0, dp(2), 0, 0)
        })

        status = TextView(this).apply {
            text = "PRONTO"
            textSize = 8.5f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(MUTED.toInt())
            background = rounded(Color.rgb(18, 27, 39), dp(16).toFloat(), Color.rgb(43, 61, 82))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        row.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(status)
        outer.addView(row)

        outer.addView(View(this).apply {
            background = rounded(deviceAccent(), dp(2).toFloat(), Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(42), dp(3)).apply {
            setMargins(dp(58), dp(8), 0, 0)
        })
        return outer
    }

    private fun buildPc(body: LinearLayout) {
        body.addView(infoCard("LEO AGENT", "Controllo Windows sulla rete locale"))

        body.addView(section("TOUCHPAD", "Un dito per muovere · due dita per scorrere"))

        val pad = PcPadView(this) { dx, dy, tap, scroll ->
            ensureSocket("/ws/pc")
            when {
                tap -> wsSend(JSONObject().put("type", "click").put("button", "left"))
                scroll != 0 -> wsSend(JSONObject().put("type", "scroll").put("delta", scroll))
                dx != 0 || dy != 0 -> wsSend(JSONObject().put("type", "move").put("dx", dx).put("dy", dy))
            }
        }
        val padWrap = FrameLayout(this).apply {
            background = rounded(SURFACE.toInt(), dp(28).toFloat(), BORDER.toInt())
        }
        padWrap.addView(pad, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        padWrap.addView(TextView(this).apply {
            text = "TOUCHPAD"
            textSize = 10f
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(91, 113, 137))
            isClickable = false
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(34), Gravity.CENTER
        ))
        padWrap.addView(TextView(this).apply {
            text = "tocca per click"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(76, 91, 110))
            isClickable = false
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(30), Gravity.BOTTOM
        ).apply { bottomMargin = dp(10) })
        body.addView(padWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270)).apply {
            setMargins(0, dp(6), 0, dp(10))
        })

        body.addView(twoButtons(
            "●  Click sinistro" to { pc("click", "button", "left") },
            "○  Click destro" to { pc("click", "button", "right") }
        ))

        body.addView(section("TASTIERA", "Scrivi direttamente sul PC"))
        val type = EditText(this).apply {
            hint = "Scrivi sul PC…"
            textSize = 15f
            setTextColor(TEXT.toInt())
            setHintTextColor(Color.rgb(102, 116, 135))
            background = rounded(SURFACE.toInt(), dp(20).toFloat(), BORDER.toInt())
            setPadding(dp(18), 0, dp(18), 0)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        type.setOnEditorActionListener { _, _, _ ->
            val value = type.text.toString()
            if (value.isNotBlank()) {
                wsSend(JSONObject().put("type", "text").put("text", value))
                type.text.clear()
            }
            true
        }
        body.addView(type, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)).apply {
            setMargins(0, dp(6), 0, dp(10))
        })

        body.addView(section("COMANDI", "Azioni rapide"))
        body.addView(commandRow(
            "↵  Invio" to { pc("key", "key", "enter") },
            "⌫  Backspace" to { pc("key", "key", "backspace") },
            "Alt ⇄" to { pc("shortcut", "name", "alt_tab") }
        ))
        body.addView(commandRow(
            "−  Volume" to { pc("key", "key", "volume_down") },
            "▶︎  Media" to { pc("key", "key", "media_play_pause") },
            "+  Volume" to { pc("key", "key", "volume_up") }
        ))
        body.addView(commandRow(
            "Copia" to { pc("shortcut", "name", "copy") },
            "Incolla" to { pc("shortcut", "name", "paste") },
            "Blocca" to { wsSend(JSONObject().put("type", "action").put("name", "lock")) }
        ))
    }

    private fun buildTv(body: LinearLayout) {
        body.addView(infoCard("CONTROLLO DIRETTO", "Telefono → TV · rete locale"))

        body.addView(section("NAVIGAZIONE", "Comandi principali"))
        body.addView(commandRow(
            "POWER" to { cmd("power") },
            "SOURCE" to { cmd("source") },
            "HOME" to { cmd("home") }
        ))
        body.addView(remoteDpad())
        body.addView(commandRow(
            "BACK" to { cmd("back") },
            "MENU" to { cmd("menu") },
            "EXIT" to { cmd("exit") }
        ))
        body.addView(commandRow(
            "VOL -" to { cmd("volume_down") },
            "MUTE" to { cmd("mute") },
            "VOL +" to { cmd("volume_up") }
        ))
        body.addView(commandRow(
            "CH -" to { cmd("channel_down") },
            "PLAY" to { cmd("play") },
            "CH +" to { cmd("channel_up") }
        ))

        body.addView(section("APP E INGRESSI", "Accesso diretto"))
        body.addView(commandRow(
            "Netflix" to { cmd("app_netflix") },
            "YouTube" to { cmd("app_youtube") },
            "Prime" to { cmd("app_prime") }
        ))
        body.addView(commandRow(
            "Disney+" to { cmd("app_disney") },
            "TV" to { cmd("source_tv") },
            "HDMI 1" to { cmd("source_hdmi1") }
        ))
        addGesturePad(body)
    }

    private fun buildFire(body: LinearLayout) {
        body.addView(infoCard("CONTROLLO DIRETTO", "Telefono → Fire TV · ADB locale"))

        body.addView(section("NAVIGAZIONE", "Comandi principali"))
        body.addView(commandRow(
            "POWER" to { cmd("power") },
            "HOME" to { cmd("home") },
            "MENU" to { cmd("menu") }
        ))
        body.addView(remoteDpad())
        body.addView(commandRow(
            "BACK" to { cmd("back") },
            "PLAY" to { cmd("play_pause") },
            "SEARCH" to { cmd("search") }
        ))
        body.addView(commandRow(
            "−  Vol" to { cmd("volume_down") },
            "Mute" to { cmd("mute") },
            "+  Vol" to { cmd("volume_up") }
        ))

        body.addView(section("TESTO", "Digita dal telefono"))
        val text = EditText(this).apply {
            hint = "Scrivi su Fire TV…"
            textSize = 15f
            setTextColor(TEXT.toInt())
            setHintTextColor(Color.rgb(102, 116, 135))
            background = rounded(SURFACE.toInt(), dp(20).toFloat(), BORDER.toInt())
            setPadding(dp(18), 0, dp(18), 0)
            setSingleLine(true)
        }
        val send = primaryButton("Invia testo") {
            val value = text.text.toString()
            if (value.isNotBlank()) sendFireText(value)
        }
        body.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)).apply {
            setMargins(0, dp(6), 0, dp(8))
        })
        body.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        addGesturePad(body)
    }

    private fun infoCard(title: String, text: String): View {
        val accent = deviceAccent()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = gradientPanel(accent)
            elevation = dp(2).toFloat()

            addView(TextView(this@LeoRemoteActivity).apply {
                this.text = when (device) {
                    DEVICE_TV -> "TV"
                    DEVICE_FIRE -> "FT"
                    else -> "PC"
                }
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(5, 12, 18))
                background = rounded(accent, dp(16).toFloat(), Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))

            val copy = LinearLayout(this@LeoRemoteActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }
            copy.addView(TextView(this@LeoRemoteActivity).apply {
                this.text = title
                textSize = 10f
                letterSpacing = 0.12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent)
            })
            copy.addView(TextView(this@LeoRemoteActivity).apply {
                this.text = text
                textSize = 12f
                setTextColor(TEXT.toInt())
                setPadding(0, dp(4), 0, 0)
            })
            addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun remoteDpad(): View {
        val wrap = FrameLayout(this).apply {
            background = gradientPanel(deviceAccent())
            elevation = dp(2).toFloat()
        }
        val s = dp(64)

        fun add(label: String, command: String, gravityValue: Int, left: Int = 0, right: Int = 0, top: Int = 0, bottom: Int = 0, primary: Boolean = false) {
            val v = if (primary) primaryButton(label) { cmd(command) } else circleButton(label) { cmd(command) }
            wrap.addView(v, FrameLayout.LayoutParams(s, s, gravityValue).apply {
                leftMargin = dp(left)
                rightMargin = dp(right)
                topMargin = dp(top)
                bottomMargin = dp(bottom)
            })
        }

        add("▲", "up", Gravity.TOP or Gravity.CENTER_HORIZONTAL, top = 16)
        add("◀", "left", Gravity.START or Gravity.CENTER_VERTICAL, left = 52)
        add("OK", "ok", Gravity.CENTER, primary = true)
        add("▶", "right", Gravity.END or Gravity.CENTER_VERTICAL, right = 52)
        add("▼", "down", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, bottom = 16)

        wrap.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(246)).apply {
            setMargins(0, dp(6), 0, dp(10))
        }
        return wrap
    }

    private fun addGesturePad(body: LinearLayout) {
        body.addView(section("GESTI", "Tocca = OK · scorri = direzione"))
        val pad = GesturePadView(this) { command -> cmd(command) }
        val wrap = FrameLayout(this).apply {
            background = rounded(SURFACE.toInt(), dp(26).toFloat(), BORDER.toInt())
        }
        wrap.addView(pad, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        wrap.addView(TextView(this).apply {
            text = "GESTURE PAD"
            textSize = 10f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(83, 103, 126))
            isClickable = false
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        body.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)).apply {
            setMargins(0, dp(6), 0, 0)
        })
    }

    private fun cmd(command: String) {
        when (device) {
            DEVICE_TV -> sendVidaaCommand(command)
            DEVICE_FIRE -> sendFireCommand(command)
            else -> {
                hub.command(device, command) { ok, msg, code ->
                    runOnUiThread {
                        updateStatus(ok, msg)
                        if (code == 401) showPairDialog()
                    }
                }
            }
        }
    }

    private fun sendVidaaCommand(command: String) {
        val tv = vidaa ?: return
        if (!tv.isPaired()) {
            showVidaaPairingIntro()
            return
        }
        setStatus("● invio…", MUTED.toInt())
        Thread {
            val result = when (command) {
                "power" -> tv.sendKey("KEY_POWER")
                "source" -> tv.sendKey("KEY_SOURCE")
                "home" -> tv.sendKey("KEY_HOME")
                "back" -> tv.sendKey("KEY_RETURNS")
                "menu" -> tv.sendKey("KEY_MENU")
                "exit" -> tv.sendKey("KEY_EXIT")
                "up" -> tv.sendKey("KEY_UP")
                "down" -> tv.sendKey("KEY_DOWN")
                "left" -> tv.sendKey("KEY_LEFT")
                "right" -> tv.sendKey("KEY_RIGHT")
                "ok" -> tv.sendKey("KEY_OK")
                "volume_down" -> tv.sendKey("KEY_VOLUMEDOWN")
                "volume_up" -> tv.sendKey("KEY_VOLUMEUP")
                "mute" -> tv.sendKey("KEY_MUTE")
                "channel_down" -> tv.sendKey("KEY_CHANNELDOWN")
                "channel_up" -> tv.sendKey("KEY_CHANNELUP")
                "play" -> tv.sendKey("KEY_PLAY")
                "source_tv" -> tv.setSource("0")
                "source_hdmi1" -> tv.setSource("3")
                "app_netflix" -> tv.launchApp("netflix")
                "app_youtube" -> tv.launchApp("youtube")
                "app_prime" -> tv.launchApp("prime")
                "app_disney" -> tv.launchApp("disney")
                else -> Result.failure(IllegalArgumentException("Comando VIDAA non supportato"))
            }
            runOnUiThread {
                if (result.isSuccess) {
                    setStatus("● diretto", GREEN.toInt())
                } else {
                    setStatus("● errore", RED.toInt())
                    Toast.makeText(
                        this,
                        result.exceptionOrNull()?.message ?: "Comando TV non riuscito",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun sendFireCommand(command: String) {
        val key = when (command) {
            "power" -> 26
            "home" -> 3
            "menu" -> 82
            "back" -> 4
            "up" -> 19
            "down" -> 20
            "left" -> 21
            "right" -> 22
            "ok" -> 23
            "play_pause" -> 85
            "search" -> 84
            "volume_down" -> 25
            "volume_up" -> 24
            "mute" -> 164
            else -> null
        }
        if (key == null) return
        ensureFireConnected {
            Thread {
                val result = fire?.sendKey(key) ?: Result.failure(IllegalStateException("Fire TV non disponibile"))
                runOnUiThread {
                    if (result.isSuccess) {
                        setStatus("● diretto", GREEN.toInt())
                    } else {
                        setStatus("● errore", RED.toInt())
                        Toast.makeText(
                            this,
                            result.exceptionOrNull()?.message ?: "Comando Fire TV non riuscito",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }
    }

    private fun sendFireText(value: String) {
        ensureFireConnected {
            Thread {
                val result = fire?.sendText(value) ?: Result.failure(IllegalStateException("Fire TV non disponibile"))
                runOnUiThread {
                    if (result.isSuccess) {
                        setStatus("● diretto", GREEN.toInt())
                    } else {
                        setStatus("● errore", RED.toInt())
                        Toast.makeText(
                            this,
                            result.exceptionOrNull()?.message ?: "Invio testo non riuscito",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }
    }

    private fun ensureFireAutoConnected() {
        if (fireConnecting || isFinishing || isDestroyed) return
        fireConnecting = true
        setStatus("CERCO FIRE TV", MUTED.toInt())

        Thread {
            val result = runCatching {
                val saved = fire?.savedIp().orEmpty().trim()

                if (saved.isNotBlank()) {
                    val savedResult = fire?.connectSaved()
                    if (savedResult?.isSuccess == true) {
                        return@runCatching savedResult.getOrThrow()
                    }
                }

                fire?.connectOrDiscover()?.getOrThrow()
                    ?: error("Fire TV non disponibile")
            }

            runOnUiThread {
                fireConnecting = false
                if (isFinishing || isDestroyed) return@runOnUiThread

                if (result.isSuccess) {
                    setStatus("DIRETTO", GREEN.toInt())
                } else {
                    setStatus("ADB OFFLINE", ORANGE.toInt())
                    Toast.makeText(
                        this,
                        result.exceptionOrNull()?.message ?: "Fire TV non raggiungibile",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.apply {
            name = "LEO-FireTV-AutoConnect"
            isDaemon = true
        }.start()
    }

    private fun ensureFireConnected(after: (() -> Unit)? = null) {
        if (fireConnecting || isFinishing || isDestroyed) return

        val target = fire?.savedIp().orEmpty().trim()
        if (target.isBlank()) {
            showFireIpDialog(after)
            return
        }

        fireConnecting = true
        setStatus("CONNESSIONE", MUTED.toInt())
        Thread {
            val result = runCatching {
                fire?.connectSaved()?.getOrThrow()
                    ?: error("Fire TV non disponibile")
            }
            runOnUiThread {
                fireConnecting = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.isSuccess) {
                    setStatus("DIRETTO", GREEN.toInt())
                    after?.invoke()
                } else {
                    setStatus("ADB OFFLINE", ORANGE.toInt())
                    AlertDialog.Builder(this)
                        .setTitle("Fire TV non collegata")
                        .setMessage(
                            (result.exceptionOrNull()?.message ?: "Connessione ADB non riuscita") +
                                "\n\nVerifica Debug ADB sulla Fire TV e l'IP salvato."
                        )
                        .setPositiveButton("Riprova") { _, _ -> ensureFireConnected(after) }
                        .setNeutralButton("Cambia IP") { _, _ -> showFireIpDialog(after) }
                        .setNegativeButton("Chiudi", null)
                        .show()
                }
            }
        }.apply {
            name = "LEO-FireTV-Connect"
            isDaemon = true
        }.start()
    }

    private fun showFireIpDialog(after: (() -> Unit)? = null) {
        if (isFinishing || isDestroyed) return
        val input = EditText(this).apply {
            hint = "192.168.1.50"
            setText(fire?.savedIp().orEmpty())
            inputType = InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this)
            .setTitle("IP Fire TV")
            .setMessage("Inseriscilo una volta: LEO lo salva e poi si collega automaticamente ai comandi.")
            .setView(input)
            .setPositiveButton("Salva e collega") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    fire?.setIp(value)
                    ensureFireConnected(after)
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun showVidaaPairingIntro() {
        if (vidaaPairDialogVisible || isFinishing) return
        vidaaPairDialogVisible = true
        AlertDialog.Builder(this)
            .setTitle("Collega Hisense direttamente")
            .setMessage(
                "LEO si collega alla TV sulla rete locale. La prima volta la TV mostrerà un PIN. " +
                    "Su alcuni modelli moderni serve che l'app ufficiale VIDAA Smart TV sia installata sul telefono: " +
                    "LEO legge localmente il certificato già presente nell'app e lo salva nella propria area privata."
            )
            .setPositiveButton("Avvia pairing") { _, _ ->
                vidaaPairDialogVisible = false
                startVidaaPairing()
            }
            .setNegativeButton("Più tardi") { _, _ ->
                vidaaPairDialogVisible = false
            }
            .setOnCancelListener { vidaaPairDialogVisible = false }
            .show()
    }

    private fun startVidaaPairing() {
        setStatus("● pairing…", MUTED.toInt())
        vidaa?.startPairing { result ->
            runOnUiThread {
                if (!result.ok) {
                    setStatus("● non associata", RED.toInt())
                    AlertDialog.Builder(this)
                        .setTitle("Pairing Hisense")
                        .setMessage(result.message)
                        .setPositiveButton("Riprova") { _, _ -> startVidaaPairing() }
                        .setNegativeButton("Chiudi", null)
                        .show()
                } else if (result.message.contains("PIN", ignoreCase = true)) {
                    showVidaaPinDialog()
                } else if (result.message.contains("associata", ignoreCase = true)) {
                    setStatus("● diretto", GREEN.toInt())
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showVidaaPinDialog() {
        val input = EditText(this).apply {
            hint = "PIN mostrato sulla TV"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("PIN Hisense")
            .setMessage("Inserisci il PIN mostrato sul televisore.")
            .setView(input)
            .setPositiveButton("Associa") { _, _ ->
                vidaa?.submitPin(input.text.toString()) { result ->
                    runOnUiThread {
                        if (result.ok && result.message.contains("associata", ignoreCase = true)) {
                            setStatus("● diretto", GREEN.toInt())
                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        } else if (result.ok) {
                            setStatus("● attendo TV…", MUTED.toInt())
                            Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                        } else {
                            setStatus("● pairing incompleto", RED.toInt())
                            AlertDialog.Builder(this)
                                .setTitle("Pairing Hisense")
                                .setMessage(result.message)
                                .setPositiveButton("Riprova") { _, _ -> startVidaaPairing() }
                                .setNegativeButton("Chiudi", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun pc(type: String, key: String, value: String) {
        ensureSocket("/ws/pc")
        wsSend(JSONObject().put("type", type).put(key, value))
    }

    private fun ensureSocket(path: String) {
        if (device != DEVICE_PC || socket != null) return
        socket = hub.webSocket(path, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { setStatus("● agent", GREEN.toInt()) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    setStatus("● offline", MUTED.toInt())
                    if (code == 4401) showPairDialog()
                }
                socket = null
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { updateStatus(false, t.message ?: "Errore rete") }
                socket = null
            }
        })
    }

    private fun wsSend(json: JSONObject) {
        socket?.send(json.toString())
    }

    private fun showPairDialog() {
        if (device != DEVICE_PC) return
        val input = EditText(this).apply {
            hint = "Codice a 6 cifre"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Associa LEO Agent")
            .setMessage("Inserisci il codice mostrato da LEO Agent sul PC.")
            .setView(input)
            .setPositiveButton("Associa") { _, _ ->
                hub.pair(input.text.toString().trim()) { ok, msg ->
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        if (ok) {
                            socket?.close(1000, "reconnect")
                            socket = null
                            ensureSocket("/ws/pc")
                        }
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun updateStatus(ok: Boolean, msg: String) {
        setStatus(if (ok) "● pronto" else "● errore", if (ok) GREEN.toInt() else RED.toInt())
        if (!ok && msg.isNotBlank()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(value: String, color: Int) {
        if (!::status.isInitialized) return
        status.text = value
        status.setTextColor(color)
    }

    private fun section(title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(24), dp(2), dp(9))

            addView(View(this@LeoRemoteActivity).apply {
                background = rounded(deviceAccent(), dp(2).toFloat(), Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(3), dp(34)).apply {
                setMargins(0, 0, dp(10), 0)
            })

            val left = LinearLayout(this@LeoRemoteActivity).apply { orientation = LinearLayout.VERTICAL }
            left.addView(TextView(this@LeoRemoteActivity).apply {
                text = title
                textSize = 11.5f
                letterSpacing = 0.12f
                setTextColor(TEXT.toInt())
                setTypeface(typeface, Typeface.BOLD)
            })
            left.addView(TextView(this@LeoRemoteActivity).apply {
                text = subtitle
                textSize = 10.5f
                setTextColor(MUTED.toInt())
                setPadding(0, dp(2), 0, 0)
            })
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun twoButtons(
        left: Pair<String, () -> Unit>,
        right: Pair<String, () -> Unit>
    ): LinearLayout {
        val row = row()
        row.addView(actionButton(left.first, left.second), weight())
        row.addView(actionButton(right.first, right.second), weight())
        return row
    }

    private fun commandRow(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val r = row()
        items.forEach { (label, action) -> r.addView(actionButton(label, action), weight()) }
        return r
    }

    private fun row(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun weight() =
        LinearLayout.LayoutParams(0, dp(58), 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }

    private fun actionButton(textValue: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = 11.5f
            letterSpacing = 0.03f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT.toInt())
            background = buttonPanel(false)
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun primaryButton(textValue: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.rgb(4, 12, 18))
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(deviceAccent(), dp(22).toFloat(), Color.TRANSPARENT)
            elevation = dp(3).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun circleButton(textValue: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT.toInt())
            background = rounded(Color.rgb(18, 27, 39), dp(40).toFloat(), Color.argb(100, Color.red(deviceAccent()), Color.green(deviceAccent()), Color.blue(deviceAccent())))
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radius: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun deviceAccent(): Int = when (device) {
        DEVICE_TV -> Color.rgb(72, 218, 190)
        DEVICE_FIRE -> Color.rgb(255, 170, 74)
        else -> Color.rgb(76, 190, 255)
    }

    private fun gradientPanel(accent: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(17, 26, 38), Color.rgb(10, 15, 23))
        ).apply {
            cornerRadius = dp(26).toFloat()
            setStroke(
                dp(1),
                Color.argb(92, Color.red(accent), Color.green(accent), Color.blue(accent))
            )
        }

    private fun buttonPanel(primary: Boolean): GradientDrawable {
        val accent = deviceAccent()
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (primary) intArrayOf(accent, accent)
            else intArrayOf(Color.rgb(24, 34, 48), Color.rgb(17, 25, 36))
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(
                dp(1),
                Color.argb(if (primary) 180 else 56, Color.red(accent), Color.green(accent), Color.blue(accent))
            )
        }
    }

    private fun dp(v: Int) =
        (v * resources.displayMetrics.density).toInt()

    private class PcPadView(
        context: android.content.Context,
        val callback: (Int, Int, Boolean, Int) -> Unit
    ) : View(context) {
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var remainderX = 0f
        private var remainderY = 0f

        init {
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = context.resources.displayMetrics.density * 22f
                setColor(Color.rgb(14, 20, 29))
                setStroke(context.resources.displayMetrics.density.toInt(), Color.rgb(38, 53, 74))
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastX = e.x
                    lastY = e.y
                    downX = e.x
                    downY = e.y
                    downAt = System.currentTimeMillis()
                    remainderX = 0f
                    remainderY = 0f
                }

                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)

                    val rawDx = e.x - lastX
                    val rawDy = e.y - lastY
                    lastX = e.x
                    lastY = e.y

                    if (e.pointerCount >= 2) {
                        val scroll = (-rawDy * 0.55f).toInt().coerceIn(-18, 18)
                        if (scroll != 0) callback(0, 0, false, scroll)
                    } else {
                        val distance = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
                        val gain = when {
                            distance < 2.5f -> 1.45f
                            distance < 8f -> 1.80f
                            else -> 2.15f
                        }

                        remainderX += rawDx * gain
                        remainderY += rawDy * gain

                        val dx = remainderX.toInt().coerceIn(-420, 420)
                        val dy = remainderY.toInt().coerceIn(-420, 420)

                        remainderX -= dx
                        remainderY -= dy

                        if (dx != 0 || dy != 0) {
                            callback(dx, dy, false, 0)
                        }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val moved = abs(e.x - downX) + abs(e.y - downY)
                    if (moved < 18f && System.currentTimeMillis() - downAt < 300) {
                        performClick()
                        callback(0, 0, true, 0)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    remainderX = 0f
                    remainderY = 0f
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private class GesturePadView(
        context: android.content.Context,
        private val callback: (String) -> Unit
    ) : View(context) {
        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L

        init {
            background = GradientDrawable().apply {
                cornerRadius = context.resources.displayMetrics.density * 22f
                setColor(Color.rgb(17, 21, 27))
                setStroke(context.resources.displayMetrics.density.toInt(), Color.rgb(39, 48, 60))
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    downAt = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    val ax = abs(dx)
                    val ay = abs(dy)
                    val threshold = resources.displayMetrics.density * 34f
                    if (ax < threshold && ay < threshold && System.currentTimeMillis() - downAt < 350) {
                        callback("ok")
                    } else if (ax > ay) {
                        callback(if (dx > 0) "right" else "left")
                    } else {
                        callback(if (dy > 0) "down" else "up")
                    }
                }
            }
            return true
        }
    }
}
