package com.goodseed.shorts.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import com.goodseed.shorts.logic.SubtitleWrapper
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil

/**
 * 템플릿이 입힌 한 장(미리보기·템플릿 썸네일·끝 화면)을 그린다.
 * 배경은 [BackgroundArt](합성과 같은 함수), 자막·제목은 합성에 쓰는 것과 같은 SpannableString([SubtitleLayout])을
 * StaticLayout 으로 그린다. (Transformer 가 만든 실제 영상 프레임은 아니다 — 같은 부품을 쓴 정지 장면이다.)
 */
object FrameComposer {
    private val artCache = object : android.util.LruCache<String, Bitmap>(12) {}

    private fun art(t: VideoTemplate, w: Int, h: Int): Bitmap {
        val k = "${t.id}#$w"
        return artCache.get(k) ?: BackgroundArt.draw(t, w, h).also { artCache.put(k, it) }
    }

    /** 사진을 가운데 기준으로 가득 채워 자른다(합성의 LAYOUT_SCALE_TO_FIT_WITH_CROP 과 같은 방식). */
    private fun drawCover(cv: Canvas, src: Bitmap, w: Int, h: Int) {
        val s = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val m = Matrix().apply { postScale(s, s); postTranslate((w - src.width * s) / 2f, (h - src.height * s) / 2f) }
        cv.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** 1080x1920 좌표계에서 sp 를 가운데 정렬로 그린다. anchorY 는 −1(아래)…1(위), 글 덩어리의 가운데가 그 높이. */
    fun drawOverlay(cv: Canvas, sp: SpannableString, anchorY: Float, startAligned: Boolean) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val w = ceil(SubtitleLayout.desiredWidth(sp)).toInt().coerceIn(1, SubtitleLayout.FRAME_W)
        val layout = StaticLayout.Builder.obtain(sp, 0, sp.length, tp, w)
            .setAlignment(if (startAligned) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false).build()
        val cy = (1f - anchorY) / 2f * SubtitleLayout.FRAME_H
        cv.save()
        cv.translate((SubtitleLayout.FRAME_W - w) / 2f, cy - layout.height / 2f)
        layout.draw(cv)
        cv.restore()
    }

    /**
     * outW 가로 픽셀의 9:16 그림. photo 가 있으면 사진 위에, 없으면 템플릿 기본 배경 위에 자막(lines)과 제목·출처를 올린다.
     */
    fun compose(ctx: Context, t: VideoTemplate, photo: Bitmap?, lines: List<String>, font: SubFont, size: SubSize, title: String, source: String, outW: Int): Bitmap {
        val outH = outW * 16 / 9
        val tf = Fonts.typeface(ctx, font)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        if (photo != null) drawCover(cv, photo, outW, outH) else cv.drawBitmap(art(t, outW, outH), 0f, 0f, null)
        cv.save()
        val k = outW / SubtitleLayout.FRAME_W.toFloat()
        cv.scale(k, k)
        t.header?.let { h -> SubtitleLayout.headerSpannable(h, tf, title, source)?.let { drawOverlay(cv, it, h.anchorY, false) } }
        if (lines.isNotEmpty()) drawOverlay(cv, SubtitleLayout.spannable(lines, tf, size, t.subtitle), t.subtitle.anchorY, t.subtitle.align == TextAlign.START)
        cv.restore()
        return out
    }

    /** 끝 화면 글: 출처가 있으면 출처를 크게, 제목이 있으면 작게. 둘 다 없으면 null(끝 화면을 만들지 않음). */
    fun endCardTexts(title: String, source: String): Pair<String, String>? {
        val t = title.trim(); val s = source.trim()
        return when {
            s.isNotEmpty() -> s to t
            t.isNotEmpty() -> t to ""
            else -> null
        }
    }

    /** 끝 화면 1080x1920 그림. */
    fun endCard(ctx: Context, t: VideoTemplate, font: SubFont, main: String, sub: String): Bitmap {
        val spec = t.endCard ?: return BackgroundArt.draw(t, SubtitleLayout.FRAME_W, SubtitleLayout.FRAME_H)
        val tf = Fonts.typeface(ctx, font)
        val out = BackgroundArt.draw(t, SubtitleLayout.FRAME_W, SubtitleLayout.FRAME_H)
        val cv = Canvas(out)
        val mainPx = 78f; val subPx = 48f
        val mainLines = wrap(main, tf, mainPx)
        val subLines = if (sub.isBlank()) emptyList() else wrap(sub, tf, subPx)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf }
        val lineGap = 14f
        val mainH = mainLines.size * (mainPx + lineGap); val subH = subLines.size * (subPx + lineGap)
        var y = (SubtitleLayout.FRAME_H - (mainH + subH + if (subLines.isEmpty()) 0f else 40f)) / 2f + mainPx
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = mainPx; tp.color = spec.textColor
        for (l in mainLines) { cv.drawText(l, SubtitleLayout.FRAME_W / 2f, y, tp); y += mainPx + lineGap }
        if (subLines.isNotEmpty()) {
            y += 40f - lineGap + subPx
            tp.textSize = subPx; tp.color = spec.subColor
            for (l in subLines) { cv.drawText(l, SubtitleLayout.FRAME_W / 2f, y, tp); y += subPx + lineGap }
        }
        return out
    }

    private fun wrap(text: String, tf: Typeface, px: Float): List<String> {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = px }
        return SubtitleWrapper.wrap(text.trim(), SubtitleLayout.FRAME_W - 2 * SubtitleLayout.SIDE_MARGIN) { p.measureText(it) }
    }

    fun saveEndCard(ctx: Context, t: VideoTemplate, font: SubFont, main: String, sub: String, dir: File): File {
        dir.mkdirs()
        val f = File(dir, "endcard.png")
        val b = endCard(ctx, t, font, main, sub)
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        return f
    }
}
