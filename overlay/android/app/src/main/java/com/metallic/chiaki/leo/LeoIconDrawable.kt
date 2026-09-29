// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.leo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.min

class LeoIconDrawable(
    private val icon: String,
    private val tint: Int
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val s = min(w, h)
        val ox = b.left + (w - s) / 2f
        val oy = b.top + (h - s) / 2f
        fun x(v: Float) = ox + s * v
        fun y(v: Float) = oy + s * v
        paint.strokeWidth = (s * 0.075f).coerceAtLeast(1.6f)
        paint.style = Paint.Style.STROKE
        paint.color = tint

        when (icon) {
            "power" -> {
                canvas.drawArc(RectF(x(.20f), y(.18f), x(.80f), y(.82f)), -48f, 276f, false, paint)
                canvas.drawLine(x(.50f), y(.08f), x(.50f), y(.48f), paint)
            }
            "home" -> {
                val p = Path().apply {
                    moveTo(x(.15f), y(.48f)); lineTo(x(.50f), y(.18f)); lineTo(x(.85f), y(.48f))
                    moveTo(x(.24f), y(.43f)); lineTo(x(.24f), y(.82f)); lineTo(x(.76f), y(.82f)); lineTo(x(.76f), y(.43f))
                }
                canvas.drawPath(p, paint)
            }
            "source" -> {
                canvas.drawRoundRect(RectF(x(.12f), y(.18f), x(.88f), y(.72f)), s*.07f, s*.07f, paint)
                canvas.drawLine(x(.38f), y(.84f), x(.62f), y(.84f), paint)
                canvas.drawLine(x(.50f), y(.72f), x(.50f), y(.84f), paint)
                canvas.drawLine(x(.30f), y(.42f), x(.70f), y(.42f), paint)
                canvas.drawLine(x(.60f), y(.32f), x(.70f), y(.42f), paint)
                canvas.drawLine(x(.60f), y(.52f), x(.70f), y(.42f), paint)
            }
            "menu" -> {
                for (yy in listOf(.30f,.50f,.70f)) canvas.drawLine(x(.20f),y(yy),x(.80f),y(yy),paint)
            }
            "back" -> {
                canvas.drawLine(x(.78f),y(.25f),x(.30f),y(.50f),paint)
                canvas.drawLine(x(.30f),y(.50f),x(.78f),y(.75f),paint)
            }
            "exit" -> {
                canvas.drawRoundRect(RectF(x(.18f),y(.18f),x(.64f),y(.82f)),s*.05f,s*.05f,paint)
                canvas.drawLine(x(.46f),y(.50f),x(.88f),y(.50f),paint)
                canvas.drawLine(x(.72f),y(.36f),x(.88f),y(.50f),paint)
                canvas.drawLine(x(.72f),y(.64f),x(.88f),y(.50f),paint)
            }
            "volume" -> {
                val p=Path().apply{ moveTo(x(.16f),y(.43f)); lineTo(x(.34f),y(.43f)); lineTo(x(.54f),y(.27f)); lineTo(x(.54f),y(.73f)); lineTo(x(.34f),y(.57f)); lineTo(x(.16f),y(.57f)); close() }
                canvas.drawPath(p,paint)
                canvas.drawArc(RectF(x(.48f),y(.30f),x(.82f),y(.70f)),-55f,110f,false,paint)
            }
            "mute" -> {
                val p=Path().apply{ moveTo(x(.12f),y(.43f)); lineTo(x(.30f),y(.43f)); lineTo(x(.50f),y(.28f)); lineTo(x(.50f),y(.72f)); lineTo(x(.30f),y(.57f)); lineTo(x(.12f),y(.57f)); close() }
                canvas.drawPath(p,paint)
                canvas.drawLine(x(.65f),y(.35f),x(.86f),y(.65f),paint)
                canvas.drawLine(x(.86f),y(.35f),x(.65f),y(.65f),paint)
            }
            "play" -> {
                val p=Path().apply{ moveTo(x(.30f),y(.20f)); lineTo(x(.78f),y(.50f)); lineTo(x(.30f),y(.80f)); close() }
                canvas.drawPath(p,paint)
            }
            "channel" -> {
                canvas.drawLine(x(.22f),y(.35f),x(.78f),y(.35f),paint)
                canvas.drawLine(x(.22f),y(.65f),x(.78f),y(.65f),paint)
                canvas.drawLine(x(.38f),y(.18f),x(.30f),y(.82f),paint)
                canvas.drawLine(x(.70f),y(.18f),x(.62f),y(.82f),paint)
            }
            "search" -> {
                canvas.drawCircle(x(.43f),y(.43f),s*.23f,paint)
                canvas.drawLine(x(.60f),y(.60f),x(.84f),y(.84f),paint)
            }
            "keyboard" -> {
                canvas.drawRoundRect(RectF(x(.10f),y(.24f),x(.90f),y(.76f)),s*.06f,s*.06f,paint)
                for (i in 0..3) for (j in 0..1) canvas.drawCircle(x(.25f+i*.16f),y(.39f+j*.17f),s*.025f,paint)
                canvas.drawLine(x(.30f),y(.66f),x(.70f),y(.66f),paint)
            }
            "mouse" -> {
                canvas.drawRoundRect(RectF(x(.28f),y(.10f),x(.72f),y(.90f)),s*.20f,s*.20f,paint)
                canvas.drawLine(x(.50f),y(.10f),x(.50f),y(.38f),paint)
                canvas.drawLine(x(.28f),y(.38f),x(.72f),y(.38f),paint)
            }
            "pc" -> {
                canvas.drawRoundRect(RectF(x(.12f),y(.18f),x(.88f),y(.68f)),s*.05f,s*.05f,paint)
                canvas.drawLine(x(.38f),y(.84f),x(.62f),y(.84f),paint)
                canvas.drawLine(x(.50f),y(.68f),x(.50f),y(.84f),paint)
            }
            "tv" -> {
                canvas.drawRoundRect(RectF(x(.10f),y(.22f),x(.90f),y(.76f)),s*.07f,s*.07f,paint)
                canvas.drawLine(x(.40f),y(.86f),x(.60f),y(.86f),paint)
            }
            "fire" -> {
                val p=Path().apply{ moveTo(x(.52f),y(.08f)); cubicTo(x(.72f),y(.30f),x(.80f),y(.44f),x(.72f),y(.62f)); cubicTo(x(.66f),y(.78f),x(.54f),y(.88f),x(.40f),y(.83f)); cubicTo(x(.22f),y(.77f),x(.17f),y(.58f),x(.28f),y(.43f)); cubicTo(x(.36f),y(.32f),x(.45f),y(.27f),x(.52f),y(.08f)) }
                canvas.drawPath(p,paint)
            }
            "gamepad" -> {
                canvas.drawRoundRect(RectF(x(.12f),y(.30f),x(.88f),y(.75f)),s*.16f,s*.16f,paint)
                canvas.drawLine(x(.30f),y(.44f),x(.30f),y(.61f),paint)
                canvas.drawLine(x(.22f),y(.525f),x(.38f),y(.525f),paint)
                paint.style=Paint.Style.FILL
                canvas.drawCircle(x(.68f),y(.47f),s*.035f,paint)
                canvas.drawCircle(x(.76f),y(.58f),s*.035f,paint)
            }
            "apps" -> {
                paint.style=Paint.Style.FILL
                for (xx in listOf(.30f,.70f)) for (yy in listOf(.30f,.70f)) canvas.drawCircle(x(xx),y(yy),s*.09f,paint)
            }
            "settings" -> {
                canvas.drawCircle(x(.50f),y(.50f),s*.18f,paint)
                for (i in 0 until 8) {
                    val a=Math.toRadians((i*45).toDouble())
                    val x1=x(.50f)+(Math.cos(a)*s*.28).toFloat(); val y1=y(.50f)+(Math.sin(a)*s*.28).toFloat()
                    val x2=x(.50f)+(Math.cos(a)*s*.38).toFloat(); val y2=y(.50f)+(Math.sin(a)*s*.38).toFloat()
                    canvas.drawLine(x1,y1,x2,y2,paint)
                }
            }
            else -> {
                canvas.drawCircle(x(.50f),y(.50f),s*.28f,paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        fun fromLabel(label: String, color: Int): LeoIconDrawable {
            val l = label.lowercase()
            val name = when {
                "power" in l -> "power"
                "home" in l -> "home"
                "source" in l || "sorgent" in l || "hdmi" in l -> "source"
                "menu" in l -> "menu"
                "back" in l || "indietro" in l -> "back"
                "exit" in l || "esci" in l -> "exit"
                "mute" in l -> "mute"
                "vol" in l -> "volume"
                "play" in l || "media" in l -> "play"
                "ch " in l || "channel" in l -> "channel"
                "search" in l || "cerca" in l -> "search"
                "netflix" in l || "youtube" in l || "prime" in l || "disney" in l -> "apps"
                "keyboard" in l || "tastiera" in l || "invio" in l || "backspace" in l -> "keyboard"
                "mouse" in l || "click" in l -> "mouse"
                else -> "apps"
            }
            return LeoIconDrawable(name, color)
        }
    }
}
