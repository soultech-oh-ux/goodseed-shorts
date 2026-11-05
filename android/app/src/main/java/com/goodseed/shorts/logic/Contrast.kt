package com.goodseed.shorts.logic

import kotlin.math.pow
import kotlin.math.roundToInt

/** WCAG 2.x 대비 계산(순수 Kotlin). 색은 ARGB 정수. */
object Contrast {
    private fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    /** 대비비(1~21). 둘 다 불투명으로 본다. */
    fun ratio(fg: Int, bg: Int): Double {
        val a = luminance(fg); val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** 반투명 fg 를 불투명 bg 위에 얹은 색. */
    fun over(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255.0
        fun ch(s: Int) = (((fg shr s) and 0xFF) * a + ((bg shr s) and 0xFF) * (1 - a)).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** 위→아래 균등 간격 그라데이션의 t(0~1) 위치 색. */
    fun lerpGradient(colors: List<Int>, t: Float): Int {
        if (colors.size == 1) return colors[0]
        val pos = t * (colors.size - 1)
        val i = pos.toInt().coerceIn(0, colors.size - 2)
        val f = pos - i
        fun ch(s: Int) = ((((colors[i] shr s) and 0xFF) * (1 - f)) + (((colors[i + 1] shr s) and 0xFF) * f)).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
