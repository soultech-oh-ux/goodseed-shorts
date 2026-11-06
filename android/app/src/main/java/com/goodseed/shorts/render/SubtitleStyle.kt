package com.goodseed.shorts.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.TypefaceSpan
import com.goodseed.shorts.logic.SubtitleWrapper
import kotlin.math.roundToInt

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

/** 글자 그림자(StaticLayout 이 TextPaint 에 그대로 적용). */
class ShadowStyle(private val radius: Float, private val dy: Float, private val color: Int) : CharacterStyle() {
    override fun updateDrawState(tp: android.text.TextPaint) { tp.setShadowLayer(radius, 0f, dy, color) }
}

/** 글자 외곽선: 같은 글자를 선으로 한 번, 채움으로 한 번 그린다(한 줄에 하나씩 씌운다). */
class OutlineTextSpan(
    private val tf: Typeface, private val sizePx: Float, private val fill: Int,
    private val stroke: Int, private val strokePx: Float, private val shadowColor: Int, private val shadowR: Float, private val shadowDy: Float,
) : ReplacementSpan() {
    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = sizePx }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val p = paint()
        if (fm != null) { val m = p.fontMetricsInt; fm.ascent = m.ascent; fm.descent = m.descent; fm.top = m.top; fm.bottom = m.bottom }
        return (p.measureText(text, start, end) + strokePx).roundToInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val tx = x + strokePx / 2f
        val sp = paint().apply { style = Paint.Style.STROKE; strokeWidth = strokePx; strokeJoin = Paint.Join.ROUND; color = stroke
            if ((shadowColor ushr 24) != 0) setShadowLayer(shadowR, 0f, shadowDy, shadowColor) }
        canvas.drawText(text, start, end, tx, y.toFloat(), sp)
        val fp = paint().apply { style = Paint.Style.FILL; color = fill }
        canvas.drawText(text, start, end, tx, y.toFloat(), fp)
    }
}

/**
 * 자막 배치(미리보기와 합성이 같은 규칙을 쓴다). 좌표는 1080x1920 프레임 기준 픽셀.
 * 줄바꿈은 SubtitleWrapper(직접 구현)가 하고, 글자 폭은 실제 글꼴로 잰다.
 */
object SubtitleLayout {
    const val FRAME_W = 1080
    const val FRAME_H = 1920
    const val SIDE_MARGIN = 80f
    const val PAD_CHAR = ' '          // 글자 배경 상자의 좌우 여백(공백 문자 한 칸)
    const val BOTTOM_ANCHOR_Y = -0.46f     // (0.1.0 기본) 자막 중심의 세로 위치(−1 아래 … 1 위)

    fun maxLinesFor(size: SubSize): Int = when (size) { SubSize.SMALL -> 5; SubSize.NORMAL -> 5; SubSize.LARGE -> 4; SubSize.XLARGE -> 3 }

    fun px(size: SubSize, factor: Float): Float = size.px * factor

    /** 글꼴·크기에 맞춰 줄바꿈한 줄 목록. factor 는 템플릿의 글자 크기 배율. */
    fun lines(text: String, typeface: Typeface, size: SubSize, factor: Float = 1f): List<String> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = px(size, factor) }
        val padW = paint.measureText(PAD_CHAR.toString()) * 2
        val maxW = FRAME_W - 2 * SIDE_MARGIN - padW
        return SubtitleWrapper.wrap(text, maxW) { paint.measureText(it) }
    }

    /** 줄 목록을 한 화면에 들어갈 쪽으로 나눈다(길면 여러 쪽이 시간에 따라 차례로 보임). */
    fun pages(lines: List<String>, size: SubSize): List<List<String>> = SubtitleWrapper.paginate(lines, maxLinesFor(size))

    /** Media3 TextOverlay 에 줄 SpannableString — 템플릿의 글자색·상자·그림자·외곽선·정렬을 적용. */
    fun spannable(lines: List<String>, typeface: Typeface, size: SubSize, spec: SubtitleSpec = DEFAULT_SPEC): SpannableString {
        val text = lines.joinToString("\n") { "$PAD_CHAR$it$PAD_CHAR" }
        val s = SpannableString(text)
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        val px = px(size, spec.sizeFactor)
        s.setSpan(AbsoluteSizeSpan(px.toInt(), false), 0, text.length, flags)
        s.setSpan(ForegroundColorSpan(spec.textColor), 0, text.length, flags)
        if ((spec.boxColor ushr 24) != 0) s.setSpan(BackgroundColorSpan(spec.boxColor), 0, text.length, flags)
        s.setSpan(TypefaceSpan(typeface), 0, text.length, flags)
        s.setSpan(AlignmentSpan.Standard(if (spec.align == TextAlign.START) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER), 0, text.length, flags)
        val hasShadow = (spec.shadowColor ushr 24) != 0 && spec.shadowRadius > 0f
        if (spec.outlineColor != 0 && spec.outlinePx > 0f) {
            var off = 0
            for (l in lines) {
                val len = l.length + 2
                s.setSpan(OutlineTextSpan(typeface, px, spec.textColor, spec.outlineColor, spec.outlinePx,
                    if (hasShadow) spec.shadowColor else 0, spec.shadowRadius, spec.shadowDy), off, off + len, flags)
                off += len + 1
            }
        } else if (hasShadow) {
            s.setSpan(ShadowStyle(spec.shadowRadius, spec.shadowDy, spec.shadowColor), 0, text.length, flags)
        }
        return s
    }

    val DEFAULT_SPEC = SubtitleSpec(anchorY = BOTTOM_ANCHOR_Y, textColor = Color.WHITE, boxColor = 0xB3000000.toInt())

    /** 제목·출처 줄. 둘 다 비면 null. */
    fun headerSpannable(h: HeaderSpec, typeface: Typeface, title: String, source: String): SpannableString? {
        if (title.isBlank() && source.isBlank()) return null
        val t = if (title.isBlank()) "" else fitHeaderText(title, typeface, h.titlePx)
        val src = if (source.isBlank()) "" else fitHeaderText(source, typeface, h.sourcePx)
        val parts = ArrayList<Triple<String, Float, Int>>()
        if (t.isNotEmpty()) parts += Triple(t, h.titlePx, h.titleColor)
        if (src.isNotEmpty()) parts += Triple(src, h.sourcePx, h.sourceColor)
        val text = parts.joinToString("\n") { it.first }
        val s = SpannableString(text)
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        s.setSpan(TypefaceSpan(typeface), 0, text.length, flags)
        s.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), 0, text.length, flags)
        var off = 0
        for ((str, px, color) in parts) {
            s.setSpan(AbsoluteSizeSpan(px.toInt(), false), off, off + str.length, flags)
            s.setSpan(ForegroundColorSpan(color), off, off + str.length, flags)
            if ((h.shadowColor ushr 24) != 0) s.setSpan(ShadowStyle(6f, 2f, h.shadowColor), off, off + str.length, flags)
            off += str.length + 1
        }
        return s
    }

    /** 사진·영상 출처 글자(마지막 장면 아래쪽에 작게). 비면 null. 최대 4줄. */
    fun creditSpannable(text: String, typeface: Typeface): SpannableString? {
        if (text.isBlank()) return null
        val px = 30f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = px }
        val joined = SubtitleWrapper.wrap(text.trim(), FRAME_W - 2 * SIDE_MARGIN) { p.measureText(it) }.take(4).joinToString("\n")
        val s = SpannableString(joined)
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        s.setSpan(TypefaceSpan(typeface), 0, joined.length, flags)
        s.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), 0, joined.length, flags)
        s.setSpan(AbsoluteSizeSpan(px.toInt(), false), 0, joined.length, flags)
        s.setSpan(ForegroundColorSpan(Color.WHITE), 0, joined.length, flags)
        s.setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, joined.length, flags)
        s.setSpan(ShadowStyle(5f, 2f, 0xE6000000.toInt()), 0, joined.length, flags)
        return s
    }

    /** 제목은 폭을 넘으면 줄바꿈이 필요하므로 미리 맞춘다(한 줄 최대 폭 안에서 SubtitleWrapper 로 나눔). */
    fun fitHeaderText(text: String, typeface: Typeface, px: Float): String {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = px }
        return SubtitleWrapper.wrap(text.trim(), FRAME_W - 2 * SIDE_MARGIN) { p.measureText(it) }.joinToString("\n")
    }

    /** 글자 덩어리를 그리는 데 쓰는 폭(가장 긴 줄) — 미리보기·끝 화면 공용. */
    fun desiredWidth(sp: CharSequence): Float = Layout.getDesiredWidth(sp, TextPaint(Paint.ANTI_ALIAS_FLAG))
}
