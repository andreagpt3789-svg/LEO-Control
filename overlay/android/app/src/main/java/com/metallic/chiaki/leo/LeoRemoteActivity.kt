// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.leo

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    }

    private lateinit var hub: LeoHubClient
    private lateinit var device: String
    private lateinit var status: TextView
    private var socket: WebSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = LeoHubClient(this)
        device = intent.getStringExtra(EXTRA_DEVICE) ?: DEVICE_PC
        buildUi()
    }

    override fun onDestroy() {
        socket?.close(1000, "close")
        socket = null
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 13, 16))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.rgb(18, 21, 27))
        }
        val back = button("←") { finish() }
        val title = TextView(this).apply {
            text = when (device) {
                DEVICE_TV -> "Hisense TV"
                DEVICE_FIRE -> "Fire TV"
                else -> "PC Windows"
            }
            textSize = 21f
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, 0, 0)
        }
        status = TextView(this).apply {
            text = "● pronto"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        top.addView(back, LinearLayout.LayoutParams(dp(54), dp(48)))
        top.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        top.addView(status, LinearLayout.LayoutParams(dp(110), dp(48)))
        root.addView(top)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
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

    private fun buildPc(body: LinearLayout) {
        val pad = PadView(this) { dx, dy, tap, scroll ->
            ensureSocket("/ws/pc")
            when {
                tap -> wsSend(JSONObject().put("type", "click").put("button", "left"))
                scroll != 0 -> wsSend(JSONObject().put("type", "scroll").put("delta", scroll))
                dx != 0 || dy != 0 -> wsSend(JSONObject().put("type", "move").put("dx", dx).put("dy", dy))
            }
        }
        body.addView(label("Touchpad"))
        body.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))

        val clicks = row()
        clicks.addView(button("Click SX") { pc("click", "button", "left") }, weight())
        clicks.addView(button("Click DX") { pc("click", "button", "right") }, weight())
        body.addView(clicks)

        val type = EditText(this).apply {
            hint = "Scrivi sul PC…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
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
        body.addView(type)

        body.addView(commandRow(
            "Invio" to { pc("key", "key", "enter") },
            "Backspace" to { pc("key", "key", "backspace") },
            "Alt+Tab" to { pc("shortcut", "name", "alt_tab") }
        ))
        body.addView(commandRow(
            "Vol -" to { pc("key", "key", "volume_down") },
            "Play/Pausa" to { pc("key", "key", "media_play_pause") },
            "Vol +" to { pc("key", "key", "volume_up") }
        ))
        body.addView(commandRow(
            "Copia" to { pc("shortcut", "name", "copy") },
            "Incolla" to { pc("shortcut", "name", "paste") },
            "Blocca" to { wsSend(JSONObject().put("type", "action").put("name", "lock")) }
        ))
        ensureSocket("/ws/pc")
    }

    private fun buildTv(body: LinearLayout) {
        body.addView(commandRow(
            "Power" to { cmd("power") },
            "Source" to { cmd("source") },
            "Home" to { cmd("home") }
        ))
        body.addView(dpad())
        body.addView(commandRow(
            "Back" to { cmd("back") },
            "Menu" to { cmd("menu") },
            "Exit" to { cmd("exit") }
        ))
        body.addView(commandRow(
            "Vol -" to { cmd("volume_down") },
            "Mute" to { cmd("mute") },
            "Vol +" to { cmd("volume_up") }
        ))
        body.addView(commandRow(
            "CH -" to { cmd("channel_down") },
            "Play/Pausa" to { cmd("play") },
            "CH +" to { cmd("channel_up") }
        ))
        body.addView(label("App"))
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
        addDevicePad(body)
    }

    private fun buildFire(body: LinearLayout) {
        body.addView(commandRow(
            "Power" to { cmd("power") },
            "Home" to { cmd("home") },
            "Menu" to { cmd("menu") }
        ))
        body.addView(dpad())
        body.addView(commandRow(
            "Back" to { cmd("back") },
            "Play/Pausa" to { cmd("play_pause") },
            "Search" to { cmd("search") }
        ))
        body.addView(commandRow(
            "Vol -" to { cmd("volume_down") },
            "Mute" to { cmd("mute") },
            "Vol +" to { cmd("volume_up") }
        ))

        val text = EditText(this).apply {
            hint = "Scrivi su Fire TV…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        val send = button("Invia testo") {
            val value = text.text.toString()
            if (value.isNotBlank()) {
                hub.text(device, value) { ok, msg, code ->
                    runOnUiThread {
                        updateStatus(ok, msg)
                        if (code == 401) showPairDialog()
                    }
                }
            }
        }
        body.addView(text)
        body.addView(send)
        addDevicePad(body)
    }

    private fun dpad(): View {
        val wrap = FrameLayout(this)
        val s = dp(72)
        val c = resources.displayMetrics.widthPixels / 2 - s / 2 - dp(12)
        fun add(label: String, command: String, x: Int, y: Int) {
            wrap.addView(button(label) { cmd(command) }, FrameLayout.LayoutParams(s, s).apply {
                leftMargin = x
                topMargin = y
            })
        }
        add("▲", "up", c, 0)
        add("◀", "left", c - s, s)
        add("OK", "ok", c, s)
        add("▶", "right", c + s, s)
        add("▼", "down", c, s * 2)
        wrap.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, s * 3)
        return wrap
    }

    private fun addDevicePad(body: LinearLayout) {
        body.addView(label("Touchpad"))
        val pad = PadView(this) { dx, dy, tap, scroll ->
            ensureSocket("/ws/device/$device/pointer")
            wsSend(JSONObject().put("dx", dx).put("dy", dy).put("tap", tap).put("scroll", scroll))
        }
        body.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)))
    }

    private fun cmd(command: String) {
        hub.command(device, command) { ok, msg, code ->
            runOnUiThread {
                updateStatus(ok, msg)
                if (code == 401) showPairDialog()
            }
        }
    }

    private fun pc(type: String, key: String, value: String) {
        ensureSocket("/ws/pc")
        wsSend(JSONObject().put("type", type).put(key, value))
    }

    private fun ensureSocket(path: String) {
        if (socket != null) return
        socket = hub.webSocket(path, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { status.text = "● connesso"; status.setTextColor(Color.rgb(90, 230, 150)) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    status.text = "● disconnesso"
                    status.setTextColor(Color.LTGRAY)
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
        val input = EditText(this).apply {
            hint = "Codice a 6 cifre"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("Associa telefono al LEO Hub")
            .setMessage("Inserisci il codice mostrato nella finestra LEO Control sul PC.")
            .setView(input)
            .setPositiveButton("Associa") { _, _ ->
                hub.pair(input.text.toString().trim()) { ok, msg ->
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        if (ok) {
                            socket?.close(1000, "reconnect")
                            socket = null
                            if (device == DEVICE_PC) ensureSocket("/ws/pc")
                        }
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun updateStatus(ok: Boolean, msg: String) {
        status.text = if (ok) "● ok" else "● errore"
        status.setTextColor(if (ok) Color.rgb(90, 230, 150) else Color.rgb(255, 110, 110))
        if (!ok && msg.isNotBlank()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun commandRow(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val r = row()
        items.forEach { (label, action) -> r.addView(button(label, action), weight()) }
        return r
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun weight() = LinearLayout.LayoutParams(0, dp(58), 1f).apply {
        setMargins(dp(4), 0, dp(4), 0)
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 14f
        setTextColor(Color.LTGRAY)
        setPadding(dp(4), dp(12), dp(4), dp(6))
    }

    private fun button(textValue: String, action: () -> Unit): Button =
        Button(this).apply {
            text = textValue
            textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(42, 48, 59))
            setOnClickListener { action() }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private class PadView(
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
                setColor(Color.rgb(28, 32, 40))
                setStroke((context.resources.displayMetrics.density).toInt(), Color.rgb(66, 74, 90))
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; downAt = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ((e.x - lastX) * 1.35f).toInt().coerceIn(-320, 320)
                    val dy = ((e.y - lastY) * 1.35f).toInt().coerceIn(-320, 320)
                    lastX = e.x; lastY = e.y
                    if (e.pointerCount >= 2) callback(0, 0, false, (-dy / 3).coerceIn(-12, 12))
                    else if (dx != 0 || dy != 0) callback(dx, dy, false, 0)
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(e.x - downX) + abs(e.y - downY)
                    if (moved < 18f && System.currentTimeMillis() - downAt < 300) callback(0, 0, true, 0)
                }
            }
            return true
        }
    }
}
