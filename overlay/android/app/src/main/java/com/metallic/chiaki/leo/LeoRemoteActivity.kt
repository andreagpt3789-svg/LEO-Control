// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.leo

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.Executors
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

    private val tvCommandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LEO-VIDAA-Commands").apply { isDaemon = true }
    }
    private val fireCommandExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LEO-FireTV-Commands").apply { isDaemon = true }
    }

    private val pcSocketLock = Any()
    private val pcPendingMessages = ArrayDeque<String>()
    @Volatile private var pcSocketOpen = false

    private var surfaceMouseManager: SensorManager? = null
    private var surfaceMouseListener: SensorEventListener? = null
    private var surfaceMouseEnabled = false
    private var surfaceMouseButton: TextView? = null
    private var surfaceMouseStatus: TextView? = null
    private var surfaceMouseSensitivity = 10000f
    private var surfaceLastAccelNs = 0L
    private var surfaceVelocityX = 0f
    private var surfaceVelocityY = 0f
    private var surfaceRemainderX = 0f
    private var surfaceRemainderY = 0f
    private var surfaceGyroMagnitude = 0f
    private var surfaceBiasX = 0f
    private var surfaceBiasY = 0f
    private var surfaceStillSeconds = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG.toInt()
        window.navigationBarColor = BG.toInt()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        hub = LeoHubClient(this)
        device = intent.getStringExtra(EXTRA_DEVICE) ?: DEVICE_PC
        if (device == DEVICE_TV) vidaa = LeoVidaaClient(this)
        if (device == DEVICE_FIRE) fire = LeoFireClient(this)
        buildUi()

        when (device) {
            DEVICE_PC -> ensureSocket("/ws/pc")
            DEVICE_TV -> {
                if (vidaa?.isPaired() == true) {
                    setStatus("● connessione…", MUTED.toInt())
                    tvCommandExecutor.execute {
                        val result = vidaa?.warmUp()
                            ?: Result.failure(IllegalStateException("Hisense non disponibile"))
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (result.isSuccess) {
                                setStatus("● diretto", GREEN.toInt())
                            } else {
                                setStatus("● errore", RED.toInt())
                            }
                        }
                    }
                } else {
                    setStatus("● da associare", ORANGE.toInt())
                    showVidaaPairingIntro()
                }
            }
            DEVICE_FIRE -> {
                setStatus("AVVIO", MUTED.toInt())
                window.decorView.post {
                    if (!isFinishing && !isDestroyed) ensureFireAutoConnected()
                }
            }
        }
    }

    override fun onDestroy() {
        stopSurfaceMouse()

        synchronized(pcSocketLock) {
            pcSocketOpen = false
            pcPendingMessages.clear()
            socket?.close(1000, "close")
            socket = null
        }

        tvCommandExecutor.shutdownNow()
        fireCommandExecutor.shutdownNow()

        // Never let network teardown block Android's main thread.
        val tv = vidaa
        val ft = fire
        Thread {
            runCatching { tv?.disconnect() }
            runCatching { ft?.close() }
        }.apply {
            name = "LEO-Remote-Cleanup"
            isDaemon = true
        }.start()

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
        body.addView(infoCard("PC WINDOWS", "LEO Agent · controllo diretto sulla rete locale"))

        body.addView(section("TOUCHPAD", "Movimento fluido · due dita per scorrere"))

        val pad = PcPadView(this) { dx, dy, tap, scroll ->
            ensureSocket("/ws/pc")
            when {
                tap -> wsSend(JSONObject().put("type", "click").put("button", "left"))
                scroll != 0 -> wsSend(JSONObject().put("type", "scroll").put("delta", scroll))
                dx != 0 || dy != 0 -> wsSend(
                    JSONObject()
                        .put("type", "move")
                        .put("dx", dx)
                        .put("dy", dy)
                )
            }
        }

        val padWrap = FrameLayout(this).apply {
            background = gradientPanel(deviceAccent())
            elevation = dp(2).toFloat()
        }
        padWrap.addView(
            pad,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val padHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), 0)
            addView(TextView(this@LeoRemoteActivity).apply {
                text = "TRACKPAD"
                textSize = 9f
                letterSpacing = 0.16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(deviceAccent())
            }, LinearLayout.LayoutParams(0, dp(28), 1f))
            addView(TextView(this@LeoRemoteActivity).apply {
                text = "SYNC"
                textSize = 8f
                letterSpacing = 0.08f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(164, 182, 202))
                background = rounded(Color.rgb(19, 29, 42), dp(10).toFloat(), Color.rgb(42, 63, 86))
                setPadding(dp(8), dp(4), dp(8), dp(4))
            })
        }
        padWrap.addView(
            padHeader,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
                Gravity.TOP
            )
        )

        val hintRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), dp(12))
            addView(TextView(this@LeoRemoteActivity).apply {
                text = "TAP = CLICK"
                textSize = 8.5f
                letterSpacing = 0.10f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(112, 132, 155))
            })
            addView(TextView(this@LeoRemoteActivity).apply {
                text = "   ·   "
                textSize = 9f
                setTextColor(Color.rgb(73, 91, 112))
            })
            addView(TextView(this@LeoRemoteActivity).apply {
                text = "2 DITA = SCROLL"
                textSize = 8.5f
                letterSpacing = 0.10f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(112, 132, 155))
            })
        }
        padWrap.addView(
            hintRow,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40),
                Gravity.BOTTOM
            )
        )

        body.addView(
            padWrap,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(286)).apply {
                setMargins(0, dp(6), 0, dp(10))
            }
        )

        body.addView(
            twoButtons(
                "CLICK SINISTRO" to { pc("click", "button", "left") },
                "CLICK DESTRO" to { pc("click", "button", "right") }
            )
        )

        body.addView(section("MOUSE DA SUPERFICIE", "Appoggia il telefono e spostalo come un mouse"))

        val surfaceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = gradientPanel(deviceAccent())
        }

        val surfaceIntro = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        surfaceIntro.addView(ImageView(this).apply {
            setImageDrawable(LeoIconDrawable("phone_move", deviceAccent()))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(
                Color.rgb(13, 22, 32),
                dp(14).toFloat(),
                Color.argb(
                    80,
                    Color.red(deviceAccent()),
                    Color.green(deviceAccent()),
                    Color.blue(deviceAccent())
                )
            )
            contentDescription = "Mouse da superficie"
        }, LinearLayout.LayoutParams(dp(46), dp(46)))

        val surfaceCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        surfaceCopy.addView(TextView(this).apply {
            text = "SURFACE MOUSE"
            textSize = 10f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(deviceAccent())
        })
        surfaceMouseStatus = TextView(this).apply {
            text = "Fermo · appoggia il telefono su una superficie"
            textSize = 11.5f
            setTextColor(MUTED.toInt())
            setPadding(0, dp(3), 0, 0)
        }
        surfaceCopy.addView(surfaceMouseStatus)
        surfaceIntro.addView(
            surfaceCopy,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        surfaceCard.addView(surfaceIntro)

        surfaceMouseButton = primaryButton("ATTIVA") {
            if (surfaceMouseEnabled) stopSurfaceMouse() else startSurfaceMouse()
        }
        surfaceCard.addView(
            surfaceMouseButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            ).apply {
                setMargins(0, dp(12), 0, 0)
            }
        )

        val surfaceControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }

        surfaceControls.addView(
            actionButton("MENO SENSIBILE") {
                surfaceMouseSensitivity =
                    (surfaceMouseSensitivity - 1500f).coerceAtLeast(4500f)
                surfaceMouseStatus?.text =
                    "Sensibilità " + (surfaceMouseSensitivity / 1000f).toInt()
            },
            LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        )

        surfaceControls.addView(
            actionButton("RICALIBRA") {
                resetSurfaceMouseCalibration()
            },
            LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        )

        surfaceControls.addView(
            actionButton("PIÙ SENSIBILE") {
                surfaceMouseSensitivity =
                    (surfaceMouseSensitivity + 1500f).coerceAtMost(19000f)
                surfaceMouseStatus?.text =
                    "Sensibilità " + (surfaceMouseSensitivity / 1000f).toInt()
            },
            LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        )

        surfaceCard.addView(surfaceControls)

        body.addView(
            surfaceCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(6), 0, dp(8))
            }
        )

        body.addView(section("TASTIERA LIVE", "Quello che scrivi viene digitato subito sul PC"))

        val keyboardCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = gradientPanel(deviceAccent())
        }

        val liveLabel = TextView(this).apply {
            text = "LIVE"
            textSize = 8.5f
            letterSpacing = 0.14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(deviceAccent())
            setPadding(dp(4), 0, 0, dp(7))
        }
        keyboardCard.addView(liveLabel)

        var mirroredText = ""
        var suppressMirror = false

        val type = EditText(this).apply {
            hint = "Scrivi qui…"
            textSize = 18f
            setTextColor(TEXT.toInt())
            setHintTextColor(Color.rgb(101, 118, 139))
            background = rounded(
                Color.rgb(8, 14, 21),
                dp(18).toFloat(),
                Color.argb(
                    100,
                    Color.red(deviceAccent()),
                    Color.green(deviceAccent()),
                    Color.blue(deviceAccent())
                )
            )
            setPadding(dp(16), dp(12), dp(16), dp(12))
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_NONE
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            isHorizontalScrollBarEnabled = false
        }

        type.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) = Unit

            override fun afterTextChanged(editable: Editable?) {
                val current = editable?.toString().orEmpty()
                if (suppressMirror) {
                    mirroredText = current
                    return
                }
                if (current == mirroredText) return

                ensureSocket("/ws/pc")

                var common = 0
                val maxCommon = minOf(mirroredText.length, current.length)
                while (
                    common < maxCommon &&
                    mirroredText[common] == current[common]
                ) {
                    common++
                }

                val removeCount = mirroredText.length - common
                repeat(removeCount.coerceAtMost(256)) {
                    wsSend(
                        JSONObject()
                            .put("type", "key")
                            .put("key", "backspace")
                    )
                }

                val inserted = current.substring(common)
                if (inserted.isNotEmpty()) {
                    wsSend(
                        JSONObject()
                            .put("type", "text")
                            .put("text", inserted)
                    )
                }

                mirroredText = current
            }
        })

        type.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.postDelayed({
                    view.requestRectangleOnScreen(
                        android.graphics.Rect(
                            0,
                            0,
                            view.width,
                            view.height + dp(120)
                        ),
                        true
                    )
                }, 180L)
            }
        }

        keyboardCard.addView(
            type,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val liveActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }

        liveActions.addView(
            actionButton("INVIO") {
                ensureSocket("/ws/pc")
                wsSend(JSONObject().put("type", "key").put("key", "enter"))
            },
            LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        )

        liveActions.addView(
            actionButton("BACKSPACE") {
                if (type.text.isNotEmpty()) {
                    val last = type.text.length - 1
                    type.text.delete(last, type.text.length)
                } else {
                    pc("key", "key", "backspace")
                }
            },
            LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        )

        liveActions.addView(
            actionButton("PULISCI") {
                suppressMirror = true
                type.text.clear()
                mirroredText = ""
                suppressMirror = false
            },
            LinearLayout.LayoutParams(0, dp(70), 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        )

        keyboardCard.addView(liveActions)
        body.addView(
            keyboardCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(6), 0, dp(8))
            }
        )
    }

    private fun buildTv(body: LinearLayout) {
        body.addView(infoCard("CONTROLLO DIRETTO", "Telefono → TV · rete locale"))

        body.addView(section("NAVIGAZIONE", "Comandi principali"))
        body.addView(commandRow(
            "POWER" to { cmd("power") },
            "SORGENTI" to { cmd("source") },
            "HOME" to { cmd("home") }
        ))
        body.addView(remoteDpad())
        body.addView(commandRow(
            "INDIETRO" to { cmd("back") },
            "MENU" to { cmd("menu") },
            "ESCI" to { cmd("exit") }
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

        body.addView(section("TASTIERINO NUMERICO", "Canali e inserimento numerico"))
        body.addView(numericRow(
            "1" to { cmd("digit_1") },
            "2" to { cmd("digit_2") },
            "3" to { cmd("digit_3") }
        ))
        body.addView(numericRow(
            "4" to { cmd("digit_4") },
            "5" to { cmd("digit_5") },
            "6" to { cmd("digit_6") }
        ))
        body.addView(numericRow(
            "7" to { cmd("digit_7") },
            "8" to { cmd("digit_8") },
            "9" to { cmd("digit_9") }
        ))
        body.addView(numericZeroRow { cmd("digit_0") })

        body.addView(section("INGRESSI", "Accesso diretto"))
        body.addView(twoButtons(
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
            "INDIETRO" to { cmd("back") },
            "PLAY" to { cmd("play_pause") },
            "CERCA" to { cmd("search") }
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

            addView(ImageView(this@LeoRemoteActivity).apply {
                val iconName = when (device) {
                    DEVICE_TV -> "tv"
                    DEVICE_FIRE -> "fire"
                    else -> "pc"
                }
                setImageDrawable(LeoIconDrawable(iconName, Color.rgb(5, 12, 18)))
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = rounded(accent, dp(16).toFloat(), Color.TRANSPARENT)
                contentDescription = title
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

        if (command == "source") {
            showVidaaSources()
            return
        }

        tvCommandExecutor.execute {
            val result = if (command.startsWith("digit_")) {
                val digit = command.removePrefix("digit_")
                if (digit.length == 1 && digit[0] in '0'..'9') {
                    tv.sendKey("KEY_$digit")
                } else {
                    Result.failure(IllegalArgumentException("Numero VIDAA non valido"))
                }
            } else when (command) {
                "power" -> tv.sendKey("KEY_POWER")
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
                else -> Result.failure(
                    IllegalArgumentException("Comando VIDAA non supportato")
                )
            }

            if (result.isFailure) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setStatus("● errore", RED.toInt())
                    Toast.makeText(
                        this,
                        result.exceptionOrNull()?.message ?: "Comando TV non riuscito",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showVidaaSources() {
        val tv = vidaa ?: return
        setStatus("● sorgenti…", MUTED.toInt())
        tv.requestSources { sources ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                val fallback = listOf(
                    LeoVidaaClient.VidaaSource("0", "TV", "TV"),
                    LeoVidaaClient.VidaaSource("3", "HDMI1", "HDMI 1"),
                    LeoVidaaClient.VidaaSource("4", "HDMI2", "HDMI 2"),
                    LeoVidaaClient.VidaaSource("5", "HDMI3", "HDMI 3"),
                    LeoVidaaClient.VidaaSource("6", "HDMI4", "HDMI 4")
                )
                val list = if (sources.isNotEmpty()) sources else fallback
                val labels = list.map { it.displayName.ifBlank { it.name } }.toTypedArray()

                setStatus("● diretto", GREEN.toInt())
                AlertDialog.Builder(this)
                    .setTitle("Sorgenti")
                    .setItems(labels) { _, which ->
                        val src = list[which]
                        setStatus("● cambio…", MUTED.toInt())
                        tvCommandExecutor.execute {
                            val result = tv.setSource(src.id, src.name)
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                if (result.isSuccess) {
                                    setStatus("● diretto", GREEN.toInt())
                                } else {
                                    setStatus("● errore", RED.toInt())
                                    Toast.makeText(
                                        this,
                                        result.exceptionOrNull()?.message ?: "Cambio sorgente non riuscito",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton("Chiudi", null)
                    .show()
            }
        }
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
        } ?: return

        fireCommandExecutor.execute {
            val result = fire?.sendKey(key)
                ?: Result.failure(IllegalStateException("Fire TV non disponibile"))

            if (result.isFailure) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setStatus("● errore", RED.toInt())
                    Toast.makeText(
                        this,
                        result.exceptionOrNull()?.message ?: "Comando Fire TV non riuscito",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun sendFireText(value: String) {
        fireCommandExecutor.execute {
            val result = fire?.sendText(value)
                ?: Result.failure(IllegalStateException("Fire TV non disponibile"))

            if (result.isFailure) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setStatus("● errore", RED.toInt())
                    Toast.makeText(
                        this,
                        result.exceptionOrNull()?.message ?: "Invio testo non riuscito",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun ensureFireAutoConnected() {
        if (fireConnecting || isFinishing || isDestroyed) return
        fireConnecting = true
        setStatus("CERCO FIRE TV", MUTED.toInt())

        fireCommandExecutor.execute {
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
        }
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
        fireCommandExecutor.execute {
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
        }
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
            .setTitle("Collega Hisense")
            .setMessage(
                "LEO prova prima il collegamento VIDAA diretto senza PIN, lo stesso metodo che ha già funzionato. " +
                    "Solo se la TV lo rifiuta passa automaticamente al pairing moderno con PIN."
            )
            .setPositiveButton("Collega") { _, _ ->
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

    private fun startSurfaceMouse() {
        if (surfaceMouseEnabled || device != DEVICE_PC) return

        val manager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val linear = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (linear == null) {
            Toast.makeText(
                this,
                "Questo telefono non espone il sensore di accelerazione lineare.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        resetSurfaceMouseCalibration()
        surfaceMouseManager = manager

        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

            override fun onSensorChanged(event: SensorEvent) {
                if (!surfaceMouseEnabled) return

                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> {
                        val gx = event.values[0]
                        val gy = event.values[1]
                        val gz = event.values[2]
                        surfaceGyroMagnitude =
                            kotlin.math.sqrt(gx * gx + gy * gy + gz * gz)
                    }

                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        handleSurfaceAcceleration(event)
                    }
                }
            }
        }

        surfaceMouseListener = listener
        manager.registerListener(
            listener,
            linear,
            SensorManager.SENSOR_DELAY_GAME
        )
        if (gyro != null) {
            manager.registerListener(
                listener,
                gyro,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        surfaceMouseEnabled = true
        surfaceMouseButton?.text = "FERMA"
        surfaceMouseStatus?.text =
            "Attivo · muovi fisicamente il telefono"
        setStatus("● surface mouse", GREEN.toInt())
    }

    private fun stopSurfaceMouse() {
        if (!surfaceMouseEnabled && surfaceMouseListener == null) return

        surfaceMouseEnabled = false
        val listener = surfaceMouseListener
        if (listener != null) {
            surfaceMouseManager?.unregisterListener(listener)
        }

        surfaceMouseListener = null
        surfaceMouseManager = null
        surfaceVelocityX = 0f
        surfaceVelocityY = 0f
        surfaceRemainderX = 0f
        surfaceRemainderY = 0f
        surfaceMouseButton?.text = "ATTIVA"
        surfaceMouseStatus?.text =
            "Fermo · appoggia il telefono su una superficie"

        if (!isFinishing && !isDestroyed && device == DEVICE_PC) {
            setStatus("● agent", GREEN.toInt())
        }
    }

    private fun resetSurfaceMouseCalibration() {
        surfaceLastAccelNs = 0L
        surfaceVelocityX = 0f
        surfaceVelocityY = 0f
        surfaceRemainderX = 0f
        surfaceRemainderY = 0f
        surfaceStillSeconds = 0f
        surfaceBiasX = 0f
        surfaceBiasY = 0f
        surfaceMouseStatus?.text =
            if (surfaceMouseEnabled) {
                "Ricalibrato · ora muovi il telefono"
            } else {
                "Ricalibrato · premi ATTIVA"
            }
    }

    private fun handleSurfaceAcceleration(event: SensorEvent) {
        val timestamp = event.timestamp
        if (surfaceLastAccelNs == 0L) {
            surfaceLastAccelNs = timestamp
            return
        }

        val dt = ((timestamp - surfaceLastAccelNs) / 1_000_000_000f)
            .coerceIn(0.004f, 0.05f)
        surfaceLastAccelNs = timestamp

        val rawX = event.values[0]
        val rawY = event.values[1]
        val rawMagnitude =
            kotlin.math.sqrt(rawX * rawX + rawY * rawY)

        val probablyStill =
            rawMagnitude < 0.14f && surfaceGyroMagnitude < 0.08f

        if (probablyStill) {
            val learn = 0.025f
            surfaceBiasX += (rawX - surfaceBiasX) * learn
            surfaceBiasY += (rawY - surfaceBiasY) * learn
            surfaceStillSeconds += dt
        } else {
            surfaceStillSeconds = 0f
        }

        var ax = rawX - surfaceBiasX
        var ay = rawY - surfaceBiasY

        val deadZone = 0.055f
        ax = when {
            ax > deadZone -> ax - deadZone
            ax < -deadZone -> ax + deadZone
            else -> 0f
        }
        ay = when {
            ay > deadZone -> ay - deadZone
            ay < -deadZone -> ay + deadZone
            else -> 0f
        }

        surfaceVelocityX += ax * dt
        surfaceVelocityY += ay * dt

        val damping = if (probablyStill) 0.82f else 0.992f
        surfaceVelocityX *= damping
        surfaceVelocityY *= damping

        if (surfaceStillSeconds > 0.22f) {
            surfaceVelocityX = 0f
            surfaceVelocityY = 0f
        }

        surfaceVelocityX = surfaceVelocityX.coerceIn(-0.55f, 0.55f)
        surfaceVelocityY = surfaceVelocityY.coerceIn(-0.55f, 0.55f)

        surfaceRemainderX +=
            surfaceVelocityX * dt * surfaceMouseSensitivity
        surfaceRemainderY +=
            -surfaceVelocityY * dt * surfaceMouseSensitivity

        val dx = surfaceRemainderX.toInt().coerceIn(-90, 90)
        val dy = surfaceRemainderY.toInt().coerceIn(-90, 90)

        surfaceRemainderX -= dx
        surfaceRemainderY -= dy

        if (dx != 0 || dy != 0) {
            ensureSocket("/ws/pc")
            wsSend(
                JSONObject()
                    .put("type", "move")
                    .put("dx", dx)
                    .put("dy", dy)
            )
            surfaceMouseStatus?.text = "Movimento rilevato"
        } else if (surfaceStillSeconds > 0.22f) {
            surfaceMouseStatus?.text = "Fermo"
        }
    }

    private fun pc(type: String, key: String, value: String) {
        ensureSocket("/ws/pc")
        wsSend(JSONObject().put("type", type).put(key, value))
    }

    private fun ensureSocket(path: String) {
        if (device != DEVICE_PC) return

        synchronized(pcSocketLock) {
            if (socket != null) return

            socket = hub.webSocket(path, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val pending = synchronized(pcSocketLock) {
                        pcSocketOpen = true
                        val copy = pcPendingMessages.toList()
                        pcPendingMessages.clear()
                        copy
                    }

                    pending.forEach { payload ->
                        webSocket.send(payload)
                    }

                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            setStatus("● agent", GREEN.toInt())
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(pcSocketLock) {
                        pcSocketOpen = false
                        if (socket === webSocket) socket = null
                    }

                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        setStatus("● offline", MUTED.toInt())
                        if (code == 4401) showPairDialog()
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    synchronized(pcSocketLock) {
                        pcSocketOpen = false
                        if (socket === webSocket) socket = null
                    }

                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        setStatus("● riconnessione…", MUTED.toInt())
                        window.decorView.postDelayed({
                            if (!isFinishing && !isDestroyed) {
                                ensureSocket("/ws/pc")
                            }
                        }, 140L)
                    }
                }
            })
        }
    }

    private fun wsSend(json: JSONObject) {
        val payload = json.toString()
        val kind = json.optString("type")
        var needConnect = false

        synchronized(pcSocketLock) {
            val current = socket
            if (pcSocketOpen && current != null && current.send(payload)) {
                return
            }

            // Movement/scroll packets become stale almost immediately.
            // Do not replay them after a reconnect, but preserve discrete
            // commands, clicks, keys and live keyboard text.
            if (kind != "move" && kind != "scroll") {
                while (pcPendingMessages.size >= 96) {
                    pcPendingMessages.removeFirst()
                }
                pcPendingMessages.addLast(payload)
            }

            needConnect = current == null
        }

        if (needConnect) {
            ensureSocket("/ws/pc")
        }
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

            val sectionIcon = when (title) {
                "TOUCHPAD" -> "mouse"
                "TASTIERA", "TESTO" -> "keyboard"
                "NAVIGAZIONE" -> "menu"
                "APP E INGRESSI" -> "apps"
                "GESTI" -> "mouse"
                else -> "apps"
            }
            addView(ImageView(this@LeoRemoteActivity).apply {
                setImageDrawable(LeoIconDrawable(sectionIcon, deviceAccent()))
                setPadding(dp(7), dp(7), dp(7), dp(7))
                background = rounded(Color.rgb(17, 26, 38), dp(11).toFloat(), Color.argb(
                    72,
                    Color.red(deviceAccent()),
                    Color.green(deviceAccent()),
                    Color.blue(deviceAccent())
                ))
                contentDescription = title
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
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

    private fun numericRow(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val r = row()
        items.forEach { (label, action) ->
            r.addView(numericButton(label, action), LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
        return r
    }

    private fun numericZeroRow(action: () -> Unit): LinearLayout {
        return row().apply {
            addView(View(this@LeoRemoteActivity), LinearLayout.LayoutParams(0, dp(64), 1f))
            addView(
                numericButton("0", action),
                LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
            )
            addView(View(this@LeoRemoteActivity), LinearLayout.LayoutParams(0, dp(64), 1f))
        }
    }

    private fun numericButton(textValue: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT.toInt())
            background = buttonPanel(false)
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
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
            textSize = 10.5f
            letterSpacing = 0.035f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT.toInt())
            background = buttonPanel(false)
            elevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true

            val icon = LeoIconDrawable.fromLabel(textValue, deviceAccent()).apply {
                setBounds(0, 0, dp(23), dp(23))
            }
            setCompoundDrawables(null, icon, null, null)
            compoundDrawablePadding = dp(5)
            setPadding(dp(4), dp(6), dp(4), dp(5))
            setOnClickListener { action() }
        }

    private fun primaryButton(textValue: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = 11f
            letterSpacing = 0.05f
            setTextColor(Color.rgb(4, 12, 18))
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(deviceAccent(), dp(20).toFloat(), Color.TRANSPARENT)
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

        private var pendingX = 0f
        private var pendingY = 0f
        private var pendingScroll = 0f
        private var frameScheduled = false
        private var movedDistance = 0f

        init {
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = context.resources.displayMetrics.density * 24f
                setColor(Color.TRANSPARENT)
            }
        }

        private fun currentX(e: MotionEvent, historical: Int = -1): Float {
            var sum = 0f
            for (p in 0 until e.pointerCount) {
                sum += if (historical >= 0) e.getHistoricalX(p, historical) else e.getX(p)
            }
            return sum / e.pointerCount.coerceAtLeast(1)
        }

        private fun currentY(e: MotionEvent, historical: Int = -1): Float {
            var sum = 0f
            for (p in 0 until e.pointerCount) {
                sum += if (historical >= 0) e.getHistoricalY(p, historical) else e.getY(p)
            }
            return sum / e.pointerCount.coerceAtLeast(1)
        }

        private fun queueSample(x: Float, y: Float, pointers: Int) {
            val rawDx = x - lastX
            val rawDy = y - lastY
            lastX = x
            lastY = y

            if (pointers >= 2) {
                pendingScroll += -rawDy * 0.38f
            } else {
                val distance = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
                val gain = when {
                    distance < 1.5f -> 1.15f
                    distance < 5f -> 1.55f
                    distance < 12f -> 1.90f
                    else -> 2.15f
                }
                pendingX += rawDx * gain
                pendingY += rawDy * gain
                movedDistance += abs(rawDx) + abs(rawDy)
            }
            scheduleFrame()
        }

        private fun scheduleFrame() {
            if (frameScheduled) return
            frameScheduled = true
            postOnAnimation {
                frameScheduled = false

                val dx = pendingX.toInt().coerceIn(-360, 360)
                val dy = pendingY.toInt().coerceIn(-360, 360)
                val scroll = pendingScroll.toInt().coerceIn(-16, 16)

                pendingX -= dx
                pendingY -= dy
                pendingScroll -= scroll

                if (dx != 0 || dy != 0 || scroll != 0) {
                    callback(dx, dy, false, scroll)
                }

                if (abs(pendingX) >= 1f || abs(pendingY) >= 1f || abs(pendingScroll) >= 1f) {
                    scheduleFrame()
                }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastX = currentX(e)
                    lastY = currentY(e)
                    downX = lastX
                    downY = lastY
                    downAt = System.currentTimeMillis()
                    pendingX = 0f
                    pendingY = 0f
                    pendingScroll = 0f
                    movedDistance = 0f
                }

                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastX = currentX(e)
                    lastY = currentY(e)
                }

                MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)

                    for (h in 0 until e.historySize) {
                        queueSample(currentX(e, h), currentY(e, h), e.pointerCount)
                    }
                    queueSample(currentX(e), currentY(e), e.pointerCount)
                }

                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (
                        movedDistance < 14f &&
                        System.currentTimeMillis() - downAt < 320L
                    ) {
                        performClick()
                        callback(0, 0, true, 0)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    pendingX = 0f
                    pendingY = 0f
                    pendingScroll = 0f
                    movedDistance = 0f
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
