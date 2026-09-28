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

class LeoRemoteActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DEVICE = "leo_device"
        const val DEVICE_PC = "pc"
        const val DEVICE_TV = "tv_hisense"
        const val DEVICE_FIRE = "fire_tv"

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
    private lateinit var device: String
    private lateinit var status: TextView
    private var socket: WebSocket? = null
    private var vidaa: LeoVidaaClient? = null
    private var fire: LeoFireClient? = null
    private var fireConnecting = false
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
            DEVICE_FIRE -> ensureFireConnected()
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
            setBackgroundColor(BG.toInt())
        }
        root.addView(topBar())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        when (device) {
            DEVICE_PC -> buildPc(body)
            DEVICE_TV -> buildTv(body)
            DEVICE_FIRE -> buildFire(body)
        }
        setContentView(root)
    }

    private fun topBar(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(SURFACE.toInt(), 0f, Color.TRANSPARENT)
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(TEXT.toInt())
            setOnClickListener { finish() }
        }

        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = when (device) {
                DEVICE_TV -> "Hisense"
                DEVICE_FIRE -> "Fire TV"
                else -> "PC Windows"
            }
            textSize = 20f
            setTextColor(TEXT.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            text = when (device) {
                DEVICE_TV -> "Telefono → TV · VIDAA locale"
                DEVICE_FIRE -> "Telefono → Fire TV · ADB locale"
                else -> "Mouse, tastiera e media · LEO Agent"
            }
            textSize = 10.5f
            setTextColor(MUTED.toInt())
        })

        status = TextView(this).apply {
            text = "● connessione…"
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(MUTED.toInt())
            background = rounded(SURFACE_2.toInt(), dp(12).toFloat(), BORDER.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        wrap.addView(back, LinearLayout.LayoutParams(dp(44), dp(50)))
        wrap.addView(titles, LinearLayout.LayoutParams(0, dp(50), 1f))
        wrap.addView(status)
        return wrap
    }

    private fun buildPc(body: LinearLayout) {
        body.addView(section("TOUCHPAD", "Scorri con due dita · tocca per click"))

        val pad = PcPadView(this) { dx, dy, tap, scroll ->
            ensureSocket("/ws/pc")
            when {
                tap -> wsSend(JSONObject().put("type", "click").put("button", "left"))
                scroll != 0 -> wsSend(JSONObject().put("type", "scroll").put("delta", scroll))
                dx != 0 || dy != 0 -> wsSend(JSONObject().put("type", "move").put("dx", dx).put("dy", dy))
            }
        }
        body.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)).apply {
            setMargins(0, dp(6), 0, dp(10))
        })

        body.addView(twoButtons(
            "Click sinistro" to { pc("click", "button", "left") },
            "Click destro" to { pc("click", "button", "right") }
        ))

        body.addView(section("TASTIERA", "Scrivi direttamente sul PC"))
        val type = EditText(this).apply {
            hint = "Scrivi sul PC…"
            setTextColor(TEXT.toInt())
            setHintTextColor(MUTED.toInt())
            background = rounded(SURFACE_2.toInt(), dp(16).toFloat(), BORDER.toInt())
            setPadding(dp(16), 0, dp(16), 0)
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
        body.addView(type, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
            setMargins(0, dp(6), 0, dp(10))
        })

        body.addView(section("COMANDI RAPIDI", "Le azioni che usi più spesso"))
        body.addView(commandRow(
            "Invio" to { pc("key", "key", "enter") },
            "Backspace" to { pc("key", "key", "backspace") },
            "Alt+Tab" to { pc("shortcut", "name", "alt_tab") }
        ))
        body.addView(commandRow(
            "Vol −" to { pc("key", "key", "volume_down") },
            "Play / Pausa" to { pc("key", "key", "media_play_pause") },
            "Vol +" to { pc("key", "key", "volume_up") }
        ))
        body.addView(commandRow(
            "Copia" to { pc("shortcut", "name", "copy") },
            "Incolla" to { pc("shortcut", "name", "paste") },
            "Blocca PC" to { wsSend(JSONObject().put("type", "action").put("name", "lock")) }
        ))
    }

    private fun buildTv(body: LinearLayout) {
        body.addView(infoCard(
            "CONTROLLO DIRETTO",
            "La TV comunica direttamente con questo telefono sulla rete locale. Il PC non partecipa."
        ))

        body.addView(section("CONTROLLO", "Navigazione e volume"))
        body.addView(commandRow(
            "Power" to { cmd("power") },
            "Sorgente" to { cmd("source") },
            "Home" to { cmd("home") }
        ))
        body.addView(remoteDpad())
        body.addView(commandRow(
            "Indietro" to { cmd("back") },
            "Menu" to { cmd("menu") },
            "Esci" to { cmd("exit") }
        ))
        body.addView(commandRow(
            "Vol −" to { cmd("volume_down") },
            "Mute" to { cmd("mute") },
            "Vol +" to { cmd("volume_up") }
        ))
        body.addView(commandRow(
            "CH −" to { cmd("channel_down") },
            "Play" to { cmd("play") },
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
        body.addView(infoCard(
            "CONTROLLO DIRETTO",
            "LEO cerca la Fire TV sulla rete e usa ADB direttamente dal telefono. La prima volta la TV può chiedere di autorizzare questo telefono."
        ))

        body.addView(section("CONTROLLO", "Navigazione Fire TV"))
        body.addView(commandRow(
            "Power" to { cmd("power") },
            "Home" to { cmd("home") },
            "Menu" to { cmd("menu") }
        ))
        body.addView(remoteDpad())
        body.addView(commandRow(
            "Indietro" to { cmd("back") },
            "Play / Pausa" to { cmd("play_pause") },
            "Cerca" to { cmd("search") }
        ))
        body.addView(commandRow(
            "Vol −" to { cmd("volume_down") },
            "Mute" to { cmd("mute") },
            "Vol +" to { cmd("volume_up") }
        ))

        body.addView(section("TESTO", "Digita dal telefono"))
        val text = EditText(this).apply {
            hint = "Scrivi su Fire TV…"
            setTextColor(TEXT.toInt())
            setHintTextColor(MUTED.toInt())
            background = rounded(SURFACE_2.toInt(), dp(16).toFloat(), BORDER.toInt())
            setPadding(dp(16), 0, dp(16), 0)
            setSingleLine(true)
        }
        val send = actionButton("Invia testo") {
            val value = text.text.toString()
            if (value.isNotBlank()) sendFireText(value)
        }
        body.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
            setMargins(0, dp(6), 0, dp(8))
        })
        body.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        addGesturePad(body)
    }

    private fun infoCard(title: String, text: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(SURFACE.toInt(), dp(18).toFloat(), BORDER.toInt())
            addView(TextView(this@LeoRemoteActivity).apply {
                this.text = title
                textSize = 10.5f
                letterSpacing = 0.12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ACCENT.toInt())
            })
            addView(TextView(this@LeoRemoteActivity).apply {
                this.text = text
                textSize = 11f
                setLineSpacing(0f, 1.08f)
                setTextColor(MUTED.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun remoteDpad(): View {
        val wrap = FrameLayout(this).apply {
            background = rounded(SURFACE.toInt(), dp(24).toFloat(), BORDER.toInt())
        }
        val s = dp(68)
        val c = resources.displayMetrics.widthPixels / 2 - s / 2 - dp(16)

        fun add(label: String, command: String, x: Int, y: Int, primary: Boolean = false) {
            wrap.addView(
                if (primary) primaryButton(label) { cmd(command) } else actionButton(label) { cmd(command) },
                FrameLayout.LayoutParams(s, s).apply {
                    leftMargin = x
                    topMargin = y
                }
            )
        }

        add("▲", "up", c, dp(10))
        add("◀", "left", c - s, s + dp(10))
        add("OK", "ok", c, s + dp(10), true)
        add("▶", "right", c + s, s + dp(10))
        add("▼", "down", c, s * 2 + dp(10))

        wrap.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, s * 3 + dp(20)).apply {
            setMargins(0, dp(8), 0, dp(8))
        }
        return wrap
    }

    private fun addGesturePad(body: LinearLayout) {
        body.addView(section("GESTI", "Tocca = OK · scorri = direzione"))
        val pad = GesturePadView(this) { command -> cmd(command) }
        body.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)).apply {
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

    private fun ensureFireConnected(after: (() -> Unit)? = null) {
        if (fireConnecting) return
        fireConnecting = true
        setStatus("● cerco Fire TV…", MUTED.toInt())
        Thread {
            val result = fire?.connectOrDiscover() ?: Result.failure(IllegalStateException("Fire TV non disponibile"))
            runOnUiThread {
                fireConnecting = false
                if (result.isSuccess) {
                    setStatus("● diretto", GREEN.toInt())
                    after?.invoke()
                } else {
                    setStatus("● autorizza", ORANGE.toInt())
                    AlertDialog.Builder(this)
                        .setTitle("Collega Fire TV")
                        .setMessage(
                            result.exceptionOrNull()?.message
                                ?: "Attiva Debug ADB sulla Fire TV e autorizza questo telefono."
                        )
                        .setPositiveButton("Riprova") { _, _ -> ensureFireConnected(after) }
                        .setNegativeButton("Chiudi", null)
                        .show()
                }
            }
        }.start()
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
                        if (result.ok) {
                            setStatus("● attendo TV…", MUTED.toInt())
                            Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                        } else {
                            setStatus("● errore PIN", RED.toInt())
                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(14), dp(2), dp(6))
            addView(TextView(this@LeoRemoteActivity).apply {
                text = title
                textSize = 11f
                letterSpacing = 0.13f
                setTextColor(TEXT.toInt())
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@LeoRemoteActivity).apply {
                text = subtitle
                textSize = 10.5f
                setTextColor(MUTED.toInt())
                setPadding(0, dp(2), 0, 0)
            })
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
        LinearLayout.LayoutParams(0, dp(56), 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }

    private fun actionButton(textValue: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = 12.5f
            setTextColor(TEXT.toInt())
            background = rounded(SURFACE_2.toInt(), dp(16).toFloat(), BORDER.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun primaryButton(textValue: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.rgb(7, 24, 30))
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(ACCENT.toInt(), dp(18).toFloat(), Color.TRANSPARENT)
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
                    lastX = e.x
                    lastY = e.y
                    downX = e.x
                    downY = e.y
                    downAt = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ((e.x - lastX) * 1.35f).toInt().coerceIn(-320, 320)
                    val dy = ((e.y - lastY) * 1.35f).toInt().coerceIn(-320, 320)
                    lastX = e.x
                    lastY = e.y
                    if (e.pointerCount >= 2) {
                        callback(0, 0, false, (-dy / 3).coerceIn(-12, 12))
                    } else if (dx != 0 || dy != 0) {
                        callback(dx, dy, false, 0)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(e.x - downX) + abs(e.y - downY)
                    if (moved < 18f && System.currentTimeMillis() - downAt < 300) {
                        callback(0, 0, true, 0)
                    }
                }
            }
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
