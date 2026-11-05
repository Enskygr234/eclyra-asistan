package com.enskygr234.eclyra.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.enskygr234.eclyra.Phase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Asistanın durumuna göre nefes alan, dalgalanan ve dönen ışık küresi. */
@Composable
fun Orb(phase: Phase, level: Float, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val breath by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "breath"
    )
    val spin by t.animateFloat(
        0f, 360f, infiniteRepeatable(tween(if (phase == Phase.THINKING) 1400 else 9000, easing = LinearEasing)), label = "spin"
    )
    val wave by t.animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "wave"
    )
    val lvl by animateFloatAsState(if (phase == Phase.LISTENING) level else 0f, tween(120), label = "lvl")
    val glow by animateFloatAsState(
        when (phase) {
            Phase.IDLE -> 0.55f
            Phase.LISTENING -> 1f
            Phase.THINKING -> 0.8f
            Phase.SPEAKING -> 0.95f
        }, tween(400), label = "glow"
    )

    Canvas(modifier) {
        val r0 = min(size.width, size.height) / 2f
        val c = center
        val base = r0 * (0.52f + 0.04f * breath + 0.14f * lvl)

        // dış hale
        drawCircle(
            Brush.radialGradient(
                listOf(C.VioletDeep.copy(alpha = 0.45f * glow), Color.Transparent),
                center = c, radius = r0
            ), radius = r0, center = c
        )

        // dinlerken ses halkaları
        if (phase == Phase.LISTENING || phase == Phase.SPEAKING) {
            for (k in 0..2) {
                val ph = ((wave / (2 * PI).toFloat()) + k / 3f) % 1f
                val rr = base * (1.05f + ph * (0.55f + lvl * 0.4f))
                drawCircle(
                    C.Ice.copy(alpha = (1f - ph) * 0.35f), radius = rr, center = c,
                    style = Stroke(width = 2.5f)
                )
            }
        }

        // ana küre
        rotate(spin, c) {
            drawCircle(
                Brush.sweepGradient(
                    listOf(C.VioletDeep, C.Ice, C.Rose, C.Violet, C.VioletDeep), center = c
                ), radius = base, center = c
            )
        }
        // iç parlaklık
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.55f), C.Violet.copy(alpha = 0.15f), Color.Transparent),
                center = Offset(c.x - base * 0.25f, c.y - base * 0.3f), radius = base * 1.1f
            ), radius = base, center = c
        )
        // koyu çekirdek (hilal etkisi)
        drawCircle(
            Brush.radialGradient(
                listOf(C.Night.copy(alpha = 0.85f), C.Night.copy(alpha = 0f)),
                center = Offset(c.x + base * 0.28f, c.y - base * 0.08f), radius = base * 0.85f
            ), radius = base, center = c
        )

        // konuşurken dalga çizgisi
        if (phase == Phase.SPEAKING) {
            val n = 28
            val w = base * 1.2f
            for (k in 0 until n) {
                val x = c.x - w / 2 + w * k / (n - 1)
                val amp = base * 0.18f * (0.4f + 0.6f * sin(wave * 2 + k * 0.6f).let { it * it })
                val env = sin(PI.toFloat() * k / (n - 1))
                drawLine(
                    Color.White.copy(alpha = 0.8f),
                    Offset(x, c.y - amp * env), Offset(x, c.y + amp * env),
                    strokeWidth = 4f
                )
            }
        }

        // düşünürken dönen noktalar
        if (phase == Phase.THINKING) {
            for (k in 0 until 3) {
                val a = Math.toRadians((spin + k * 120).toDouble())
                val rr = base * 1.25f
                drawCircle(
                    C.Ice, radius = 6f,
                    center = Offset(c.x + (rr * cos(a)).toFloat(), c.y + (rr * sin(a)).toFloat())
                )
            }
        }
    }
}
