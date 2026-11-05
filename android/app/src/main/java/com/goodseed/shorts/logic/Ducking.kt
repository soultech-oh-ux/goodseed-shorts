package com.goodseed.shorts.logic

import kotlin.math.max
import kotlin.math.min

/** 낭독 구간(합성 시간 기준, 마이크로초). */
data class SpeechSpan(val startUs: Long, val endUs: Long)

/**
 * 음악 자동 볼륨 낮춤(덕킹) 곡선.
 *  - 낭독 시작 attackUs 전부터 duckGain 까지 직선으로 줄이고
 *  - 낭독이 끝난 뒤 releaseUs 동안 직선으로 원래 볼륨(1.0)으로 되돌린다
 * 반환값은 음악 원음에 곱하는 배율(0..1). 사용자가 정한 기본 음악 크기는 따로 곱한다.
 */
class DuckingCurve(
    spans: List<SpeechSpan>,
    val duckGain: Float = 0.2f,
    val attackUs: Long = 250_000,
    val releaseUs: Long = 600_000,
) {
    private val sorted = spans.filter { it.endUs > it.startUs }.sortedBy { it.startUs }
    private val starts = LongArray(sorted.size) { sorted[it].startUs - attackUs }

    fun gainAt(tUs: Long): Float {
        if (sorted.isEmpty()) return 1f
        // starts 는 (정렬된 startUs - 상수)이므로 오름차순 — tUs 이하인 마지막 위치를 이분탐색
        var lo = 0
        var hi = sorted.size - 1
        var idx = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (starts[m] <= tUs) { idx = m; lo = m + 1 } else hi = m - 1
        }
        if (idx < 0) return 1f
        var g = 1f
        var k = idx
        while (k >= 0 && k >= idx - 3) {
            g = min(g, gainFor(sorted[k], tUs))
            k--
        }
        return g
    }

    private fun gainFor(s: SpeechSpan, t: Long): Float {
        val rampStart = s.startUs - attackUs
        return when {
            t < rampStart -> 1f
            t < s.startUs -> {
                val f = (t - rampStart).toFloat() / max(1L, attackUs)
                1f + (duckGain - 1f) * f
            }
            t <= s.endUs -> duckGain
            t < s.endUs + releaseUs -> {
                val f = (t - s.endUs).toFloat() / max(1L, releaseUs)
                duckGain + (1f - duckGain) * f
            }
            else -> 1f
        }
    }
}
