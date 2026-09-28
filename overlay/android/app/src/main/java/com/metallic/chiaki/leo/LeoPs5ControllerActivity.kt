// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.leo

import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.session.StreamState
import com.metallic.chiaki.session.StreamStateConnected
import com.metallic.chiaki.session.StreamStateConnecting
import com.metallic.chiaki.session.StreamStateCreateError
import com.metallic.chiaki.session.StreamStateLoginPinRequest
import com.metallic.chiaki.session.StreamStateQuit
import com.metallic.chiaki.stream.StreamViewModel
import com.metallic.chiaki.touchcontrols.TouchpadView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.hypot
import kotlin.math.min

class LeoPs5ControllerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_CONNECT_INFO = "leo_connect_info"
        private const val PREFS = "leo_controller"
    }

    private lateinit var viewModel: StreamViewModel
    private lateinit var root: FrameLayout
    private lateinit var controlsLayer: FrameLayout
    private lateinit var statusView: TextView
    private lateinit var videoSurface: SurfaceView

    private var controllerState = ControllerState()
    private var touchpadState = ControllerState()
    private var touchpadJob: Job? = null
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val connectInfo = IntentCompat.getParcelableExtra(intent, EXTRA_CONNECT_INFO, ConnectInfo::class.java)
        if (connectInfo == null) {
            finish()
            return
        }

        viewModel = ViewModelProvider(this, viewModelFactory {
            StreamViewModel(application, connectInfo)
        })[StreamViewModel::class.java]
        viewModel.input.observe(this)

        root = FrameLayout(this)
        setContentView(root)

        videoSurface = SurfaceView(this).apply { alpha = 0.01f }
        root.addView(videoSurface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        controlsLayer = FrameLayout(this)
        root.addView(controlsLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        viewModel.session.attachToSurfaceView(videoSurface)
        viewModel.session.state.observe(this) { onStreamState(it) }
        viewModel.session.rumbleState.observe(this) {
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("haptics", true)) {
                if (it.left.toInt() + it.right.toInt() > 0)
                    controlsLayer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }

        buildControllerUi()
    }

    override fun onResume() {
        super.onResume()
        viewModel.session.resume()
    }

    override fun onPause() {
        viewModel.session.pause()
        super.onPause()
    }

    override fun onDestroy() {
        touchpadJob?.cancel()
        dialog?.dismiss()
        viewModel.session.shutdown()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)

    private data class Theme(
        val background: Int,
        val panel: Int,
        val control: Int,
        val pressed: Int,
        val accent: Int,
        val text: Int
    )

    private fun currentTheme(): Theme {
        val value = getSharedPreferences(PREFS, MODE_PRIVATE).getString("theme", "Midnight")
        return when (value) {
            "OLED" -> Theme(
                Color.rgb(0, 0, 0), Color.rgb(18, 18, 18), Color.rgb(42, 42, 46),
                Color.rgb(78, 78, 84), Color.WHITE, Color.WHITE
            )
            "PlayStation Blue" -> Theme(
                Color.rgb(5, 14, 35), Color.rgb(10, 29, 66), Color.rgb(24, 57, 108),
                Color.rgb(43, 104, 210), Color.rgb(63, 140, 255), Color.WHITE
            )
            else -> Theme(
                Color.rgb(11, 13, 16), Color.rgb(24, 28, 35), Color.rgb(42, 48, 59),
                Color.rgb(75, 86, 105), Color.rgb(92, 145, 255), Color.WHITE
            )
        }
    }

    private fun buildControllerUi() {
        touchpadJob?.cancel()
        controlsLayer.removeAllViews()
        controllerState = ControllerState()
        touchpadState = ControllerState()

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val preset = prefs.getString("preset", "Standard") ?: "Standard"
        val opacity = when (prefs.getString("opacity", "90%")) {
            "60%" -> 0.60f
            "75%" -> 0.75f
            "100%" -> 1.00f
            else -> 0.90f
        }
        val baseAnalogScale = when (prefs.getString("analog_size", "Grande")) {
            "Piccolo" -> 0.88f
            "Medio" -> 1.00f
            "XL" -> 1.28f
            else -> 1.14f
        }
        val baseButtonScale = when (prefs.getString("button_size", "Medio")) {
            "Piccolo" -> 0.88f
            "Grande" -> 1.14f
            else -> 1.00f
        }

        val leftAnalogScale = baseAnalogScale * when (preset) {
            "Racing" -> 1.16f
            "FPS" -> 1.04f
            "Minimal" -> 0.92f
            else -> 1.00f
        }
        val rightAnalogScale = baseAnalogScale * when (preset) {
            "FPS" -> 1.16f
            "Racing" -> 0.92f
            "Minimal" -> 0.92f
            else -> 1.00f
        }
        val buttonScale = baseButtonScale * if (preset == "Minimal") 0.88f else 1.00f

        val theme = currentTheme()
        controlsLayer.setBackgroundColor(theme.background)

        statusView = pill("●  CONNESSIONE…", theme.panel, theme.text, 13f)
        controlsLayer.addView(statusView, frameLp(dp(178), dp(42), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(8)
        })

        val settings = makeTextButton("⚙", theme) { showControllerSettings() }
        controlsLayer.addView(settings, frameLp(dp(46), dp(42), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(10)
            leftMargin = dp(86)
        })

        val close = makeTextButton("←", theme) { finish() }
        controlsLayer.addView(close, frameLp(dp(46), dp(42), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(10)
            rightMargin = dp(86)
        })

        val shoulderW = (82 * buttonScale).toInt()
        val shoulderH = (58 * buttonScale).toInt()

        controlsLayer.addView(makeTrigger("L2", true, theme, opacity), frameLp(dp(shoulderW), dp(shoulderH), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(10); topMargin = dp(10)
        })
        controlsLayer.addView(makeButton("L1", ControllerState.BUTTON_L1, theme, opacity, false), frameLp(dp(shoulderW), dp(shoulderH), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(100); topMargin = dp(10)
        })
        controlsLayer.addView(makeButton("R1", ControllerState.BUTTON_R1, theme, opacity, false), frameLp(dp(shoulderW), dp(shoulderH), Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(100); topMargin = dp(10)
        })
        controlsLayer.addView(makeTrigger("R2", false, theme, opacity), frameLp(dp(shoulderW), dp(shoulderH), Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(10); topMargin = dp(10)
        })

        val touchpad = TouchpadView(this).apply {
            background = rounded(theme.panel, dp(18).toFloat())
            alpha = opacity
            contentDescription = "Touchpad PS5"
        }
        controlsLayer.addView(touchpad, frameLp(dp(250), dp(72), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(58)
        })
        touchpadJob = touchpad.controllerState.onEach {
            touchpadState = it
            sendControllerState()
        }.launchIn(lifecycleScope)

        controlsLayer.addView(makeButton("CREATE", ControllerState.BUTTON_SHARE, theme, opacity, false), frameLp(dp(76), dp(42), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(73); rightMargin = dp(340)
        })
        controlsLayer.addView(makeButton("OPTIONS", ControllerState.BUTTON_OPTIONS, theme, opacity, false), frameLp(dp(82), dp(42), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(73); leftMargin = dp(346)
        })

        val dpadSize = (56 * buttonScale).toInt()
        val dpadX = dp(46)
        val dpadY = dp(118)
        addAt(makeButton("▲", ControllerState.BUTTON_DPAD_UP, theme, opacity, true), dpadX + dp(dpadSize), dpadY, dpadSize, dpadSize)
        addAt(makeButton("▼", ControllerState.BUTTON_DPAD_DOWN, theme, opacity, true), dpadX + dp(dpadSize), dpadY + dp(dpadSize * 2), dpadSize, dpadSize)
        addAt(makeButton("◀", ControllerState.BUTTON_DPAD_LEFT, theme, opacity, true), dpadX, dpadY + dp(dpadSize), dpadSize, dpadSize)
        addAt(makeButton("▶", ControllerState.BUTTON_DPAD_RIGHT, theme, opacity, true), dpadX + dp(dpadSize * 2), dpadY + dp(dpadSize), dpadSize, dpadSize)

        val face = (62 * buttonScale).toInt()
        val right = resources.displayMetrics.widthPixels
        val fx = right - dp(46) - dp(face * 3)
        val fy = dp(112)
        addAt(makeFaceButton("△", ControllerState.BUTTON_PYRAMID, Color.rgb(72, 207, 126), theme, opacity), fx + dp(face), fy, face, face)
        addAt(makeFaceButton("×", ControllerState.BUTTON_CROSS, Color.rgb(74, 142, 255), theme, opacity), fx + dp(face), fy + dp(face * 2), face, face)
        addAt(makeFaceButton("□", ControllerState.BUTTON_BOX, Color.rgb(236, 98, 176), theme, opacity), fx, fy + dp(face), face, face)
        addAt(makeFaceButton("○", ControllerState.BUTTON_MOON, Color.rgb(255, 92, 102), theme, opacity), fx + dp(face * 2), fy + dp(face), face, face)

        val analogBase = dp(154)
        val leftStick = LeoStickView(this, theme.accent).apply {
            alpha = opacity
            onChange = { x, y ->
                controllerState = controllerState.copy(leftX = axis(x), leftY = axis(y))
                sendControllerState()
            }
        }
        val rightStick = LeoStickView(this, theme.accent).apply {
            alpha = opacity
            onChange = { x, y ->
                controllerState = controllerState.copy(rightX = axis(x), rightY = axis(y))
                sendControllerState()
            }
        }
        controlsLayer.addView(leftStick, frameLp((analogBase * leftAnalogScale).toInt(), (analogBase * leftAnalogScale).toInt(), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(190); bottomMargin = dp(16)
        })
        controlsLayer.addView(rightStick, frameLp((analogBase * rightAnalogScale).toInt(), (analogBase * rightAnalogScale).toInt(), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(190); bottomMargin = dp(16)
        })

        controlsLayer.addView(makeButton("L3", ControllerState.BUTTON_L3, theme, opacity, false), frameLp(dp(52), dp(38), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(245); bottomMargin = dp(8)
        })
        controlsLayer.addView(makeButton("R3", ControllerState.BUTTON_R3, theme, opacity, false), frameLp(dp(52), dp(38), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(245); bottomMargin = dp(8)
        })

        controlsLayer.addView(makeButton("PS", ControllerState.BUTTON_PS, theme, opacity, true), frameLp(dp(58), dp(58), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(62)
        })

        val hint = TextView(this).apply {
            text = preset
            textSize = 11f
            setTextColor(theme.text)
            alpha = 0.44f
            gravity = Gravity.CENTER
        }
        controlsLayer.addView(hint, frameLp(dp(120), dp(28), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(34)
        })
    }

    private fun sendControllerState() {
        viewModel.input.touchControllerState = controllerState or touchpadState
        statusView.text = "●  INPUT"
        statusView.alpha = 1f
        statusView.animate().cancel()
        statusView.animate().alpha(0.72f).setDuration(180).start()
    }

    private fun makeButton(label: String, mask: UInt, theme: Theme, opacity: Float, circle: Boolean): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = if (label.length <= 2) 18f else 11f
            setTextColor(theme.text)
            alpha = opacity
            background = rounded(theme.control, if (circle) dp(100).toFloat() else dp(16).toFloat())
            isClickable = true
            isFocusable = true
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        background = rounded(theme.pressed, if (circle) dp(100).toFloat() else dp(16).toFloat())
                        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("haptics", true))
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        controllerState = controllerState.copy(buttons = controllerState.buttons or mask)
                        sendControllerState()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        background = rounded(theme.control, if (circle) dp(100).toFloat() else dp(16).toFloat())
                        controllerState = controllerState.copy(buttons = controllerState.buttons and mask.inv())
                        sendControllerState()
                    }
                }
                true
            }
        }
    }

    private fun makeFaceButton(label: String, mask: UInt, color: Int, theme: Theme, opacity: Float): TextView =
        makeButton(label, mask, theme, opacity, true).apply {
            setTextColor(color)
            textSize = 26f
        }

    private fun makeTrigger(label: String, left: Boolean, theme: Theme, opacity: Float): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(theme.text)
            alpha = opacity
            background = rounded(theme.control, dp(16).toFloat())
            setOnTouchListener { v, e ->
                val down = e.actionMasked == MotionEvent.ACTION_DOWN
                val up = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
                if (down || up) {
                    background = rounded(if (down) theme.pressed else theme.control, dp(16).toFloat())
                    if (down && getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("haptics", true))
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    controllerState = if (left)
                        controllerState.copy(l2State = if (down) 255U else 0U)
                    else
                        controllerState.copy(r2State = if (down) 255U else 0U)
                    sendControllerState()
                }
                true
            }
        }
    }

    private fun makeTextButton(label: String, theme: Theme, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 17f
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            setTextColor(theme.text)
            backgroundTintList = android.content.res.ColorStateList.valueOf(theme.panel)
            setOnClickListener { action() }
        }

    private fun pill(textValue: String, color: Int, textColor: Int, size: Float): TextView =
        TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = size
            setTextColor(textColor)
            background = rounded(color, dp(18).toFloat())
            alpha = 0.72f
        }

    private fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            setStroke(dp(1), Color.argb(70, 255, 255, 255))
        }

    private fun addAt(view: View, x: Int, y: Int, wDp: Int, hDp: Int) {
        controlsLayer.addView(view, FrameLayout.LayoutParams(dp(wDp), dp(hDp)).apply {
            leftMargin = x
            topMargin = y
        })
    }

    private fun frameLp(width: Int, height: Int, gravityValue: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(width, height).apply { gravity = gravityValue }

    private fun axis(value: Float): Short =
        (value.coerceIn(-1f, 1f) * Short.MAX_VALUE.toFloat()).toInt().toShort()

    private fun showControllerSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }

        fun labelledSpinner(label: String, values: Array<String>, current: String): Spinner {
            box.addView(TextView(this).apply {
                text = label
                setTextColor(Color.DKGRAY)
                textSize = 13f
                setPadding(0, dp(10), 0, dp(4))
            })
            return Spinner(this).also { spinner ->
                spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
                spinner.setSelection(values.indexOf(current).coerceAtLeast(0))
                box.addView(spinner)
            }
        }

        val presetValues = arrayOf("Standard", "FPS", "Racing", "Minimal")
        val themeValues = arrayOf("Midnight", "OLED", "PlayStation Blue")
        val analogValues = arrayOf("Piccolo", "Medio", "Grande", "XL")
        val buttonValues = arrayOf("Piccolo", "Medio", "Grande")
        val opacityValues = arrayOf("60%", "75%", "90%", "100%")

        val preset = labelledSpinner("Layout", presetValues, prefs.getString("preset", "Standard") ?: "Standard")
        val themeSpinner = labelledSpinner("Tema", themeValues, prefs.getString("theme", "Midnight") ?: "Midnight")
        val analog = labelledSpinner("Dimensione analogici", analogValues, prefs.getString("analog_size", "Grande") ?: "Grande")
        val buttons = labelledSpinner("Dimensione pulsanti", buttonValues, prefs.getString("button_size", "Medio") ?: "Medio")
        val opacity = labelledSpinner("Opacità", opacityValues, prefs.getString("opacity", "90%") ?: "90%")
        val haptics = CheckBox(this).apply {
            text = "Feedback aptico"
            isChecked = prefs.getBoolean("haptics", true)
            setPadding(0, dp(12), 0, dp(4))
        }
        box.addView(haptics)

        AlertDialog.Builder(this)
            .setTitle("Personalizza joypad")
            .setView(box)
            .setPositiveButton("Applica") { _, _ ->
                prefs.edit()
                    .putString("preset", preset.selectedItem.toString())
                    .putString("theme", themeSpinner.selectedItem.toString())
                    .putString("analog_size", analog.selectedItem.toString())
                    .putString("button_size", buttons.selectedItem.toString())
                    .putString("opacity", opacity.selectedItem.toString())
                    .putBoolean("haptics", haptics.isChecked)
                    .apply()
                buildControllerUi()
            }
            .setNeutralButton("Reset") { _, _ ->
                prefs.edit().clear().apply()
                buildControllerUi()
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun onStreamState(state: StreamState) {
        when (state) {
            is StreamStateConnecting -> {
                statusView.text = "●  CONNESSIONE…"
                statusView.setTextColor(Color.LTGRAY)
            }
            is StreamStateConnected -> {
                statusView.text = "●  PS5 CONNESSA"
                statusView.setTextColor(Color.rgb(90, 230, 150))
            }
            is StreamStateLoginPinRequest -> showLoginPin(state.pinIncorrect)
            is StreamStateCreateError -> {
                Toast.makeText(this, "Errore sessione PS5: ${state.error.errorCode}", Toast.LENGTH_LONG).show()
                finish()
            }
            is StreamStateQuit -> {
                val message = state.reasonString ?: state.reason.toString()
                AlertDialog.Builder(this)
                    .setTitle("Sessione PS5 terminata")
                    .setMessage(message)
                    .setPositiveButton("Riprova") { _, _ ->
                        viewModel.session.shutdown()
                        viewModel.session.resume()
                    }
                    .setNegativeButton("Chiudi") { _, _ -> finish() }
                    .show()
            }
            else -> {}
        }
    }

    private fun showLoginPin(incorrect: Boolean) {
        if (dialog?.isShowing == true) return
        val input = EditText(this).apply {
            hint = "PIN profilo PS5"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(if (incorrect) "PIN errato" else "PIN PS5 richiesto")
            .setView(input)
            .setPositiveButton("Invia") { _, _ ->
                viewModel.session.setLoginPin(input.text.toString())
                dialog = null
            }
            .setNegativeButton("Chiudi") { _, _ ->
                dialog = null
                finish()
            }
            .create()
        dialog?.show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private class LeoStickView(
        context: Context,
        private val accent: Int
    ) : View(context) {
        var onChange: ((Float, Float) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var knobX = 0f
        private var knobY = 0f
        private val deadZone = 0.07f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) * 0.31f
            val knobRadius = min(width, height) * 0.20f

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(70, 255, 255, 255)
            canvas.drawCircle(cx, cy, radius * 1.22f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 2f
            paint.color = Color.argb(145, Color.red(accent), Color.green(accent), Color.blue(accent))
            canvas.drawCircle(cx, cy, radius * 1.22f, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(220, 225, 232, 244)
            canvas.drawCircle(cx + knobX * radius, cy + knobY * radius, knobRadius, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 2f
            paint.color = accent
            canvas.drawCircle(cx + knobX * radius, cy + knobY * radius, knobRadius, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN)
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val cx = width / 2f
                    val cy = height / 2f
                    val maxRadius = min(width, height) * 0.31f
                    var dx = (event.x - cx) / maxRadius
                    var dy = (event.y - cy) / maxRadius
                    val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (length > 1f) {
                        dx /= length
                        dy /= length
                    }
                    val magnitude = min(1f, hypot(dx.toDouble(), dy.toDouble()).toFloat())
                    if (magnitude < deadZone) {
                        knobX = 0f
                        knobY = 0f
                    } else {
                        val scaled = ((magnitude - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
                        val norm = if (magnitude == 0f) 1f else magnitude
                        knobX = (dx / norm) * scaled
                        knobY = (dy / norm) * scaled
                    }
                    invalidate()
                    onChange?.invoke(knobX, knobY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    knobX = 0f
                    knobY = 0f
                    invalidate()
                    onChange?.invoke(0f, 0f)
                }
            }
            return true
        }
    }
}
