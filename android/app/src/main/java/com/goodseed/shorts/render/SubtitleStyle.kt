package com.goodseed.shorts.render

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import com.goodseed.shorts.logic.SubtitleWrapper

/** 자막 글꼴(둘 다 SIL OFL 1.1 — assets/licenses 에 라이선스 전문). */
enum class SubFont(val label: String, val asset: String) {
    PRETENDARD("Pretendard", "fonts/Pretendard-Bold.otf"),
    GOWUN("고운바탕", "fonts/GowunBatang-Bold.ttf"),
}

/** 자막 크기 4단계(1080 폭 기준 픽셀). */
enum class SubSize(val label: String, val px: Float) {
    SMALL("작게", 52f), NORMAL("보통", 64f), LARGE("크게", 78f), XLARGE("아주 크게", 94f);
    companion object { fun fromIndex(i: Int) = entries.getOrElse(i) { NORMAL } }
}

object Fonts {
    private val cache = HashMap<SubFont, Typeface>()
    @Synchronized fun typeface(ctx: Context, f: SubFont): Typeface =
        cache.getOrPut(f) { runCatching { Typeface.createFromAsset(ctx.assets, f.asset) }.getOrDefault(Typeface.DEFAULT_BOLD) }
}

/**
 * 자막 배치(미리보기와 합성이 같은 규칙을 쓴다). 좌표는 1080x1920 프레임 기준 픽셀.
 * 줄바꿈은 SubtitleWrapper(직접 구현)가 하고, 글자 폭은 실제 글꼴로 잰다.
 */
object SubtitleLayout {
    const val FRAME_W = 1080
    const val FRAME_H = 1920
    const val SIDE_MARGIN = 80f
    const val PAD_CHAR = ' '          // 글자 배경 상자의 좌우 여백(공백 문자 한 칸)
    const val BOTTOM_ANCHOR_Y = -0.46f     // 자막 중심의 세로 위치(−1 아래 … 1 위)

    fun maxLinesFor(size: SubSize): Int = when (size) { SubSize.SMALL -> 5; SubSize.NORMAL -> 5; SubSize.LARGE -> 4; SubSize.XLARGE -> 3 }

    /** 글꼴·크기에 맞춰 줄바꿈한 줄 목록. */
    fun lines(text: String, typeface: Typeface, size: SubSize): List<String> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = size.px }
        val padW = paint.measureText(PAD_CHAR.toString()) * 2
        val maxW = FRAME_W - 2 * SIDE_MARGIN - padW
        return SubtitleWrapper.wrap(text, maxW) { paint.measureText(it) }
    }

    /** 줄 목록을 한 화면에 들어갈 쪽으로 나눈다(길면 여러 쪽이 시간에 따라 차례로 보임). */
    fun pages(lines: List<String>, size: SubSize): List<List<String>> = SubtitleWrapper.paginate(lines, maxLinesFor(size))

    /** Media3 TextOverlay 에 줄 SpannableString — 가운데 정렬·반투명 검정 글자 배경·흰 글자. */
    fun spannable(lines: List<String>, typeface: Typeface, size: SubSize): SpannableString {
        val text = lines.joinToString("\n") { "$PAD_CHAR$it$PAD_CHAR" }
        val s = SpannableString(text)
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        s.setSpan(AbsoluteSizeSpan(size.px.toInt(), false), 0, text.length, flags)
        s.setSpan(ForegroundColorSpan(Color.WHITE), 0, text.length, flags)
        s.setSpan(BackgroundColorSpan(0xB3000000.toInt()), 0, text.length, flags)
        s.setSpan(TypefaceSpan(typeface), 0, text.length, flags)
        s.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), 0, text.length, flags)
        return s
    }
}
