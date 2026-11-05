package com.goodseed.shorts.render

import android.text.SpannableString
import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay

/** 시간 구간별 자막 한 장. text 는 같은 장면에서 같은 인스턴스를 재사용한다(TextOverlay 가 인스턴스 비교로 비트맵을 다시 만들지 판단). */
class TimedText(val startUs: Long, val endUs: Long, val text: SpannableString)

/**
 * 시간별 자막 — 합성 전체에 한 번 걸어 쓰는 TextOverlay.
 * getText(presentationTimeUs) 가 그 시각에 보여 줄 자막(없으면 보이지 않는 한 칸)을 돌려준다.
 */
@OptIn(UnstableApi::class)
class SubtitleOverlay(cues: List<TimedText>) : TextOverlay() {
    private val sorted = cues.sortedBy { it.startUs }
    private val starts = LongArray(sorted.size) { sorted[it].startUs }
    private val blank = SpannableString(" ")
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setOverlayFrameAnchor(0f, 0f)
        .setBackgroundFrameAnchor(0f, SubtitleLayout.BOTTOM_ANCHOR_Y)
        .build()

    /** 시각 t 에 보여 줄 자막(없으면 null). 시험에서도 쓴다. */
    fun cueAt(tUs: Long): TimedText? {
        var lo = 0; var hi = sorted.size - 1; var idx = -1
        while (lo <= hi) { val m = (lo + hi) ushr 1; if (starts[m] <= tUs) { idx = m; lo = m + 1 } else hi = m - 1 }
        if (idx < 0) return null
        val c = sorted[idx]
        return if (tUs < c.endUs) c else null
    }

    override fun getText(presentationTimeUs: Long): SpannableString = cueAt(presentationTimeUs)?.text ?: blank

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}
