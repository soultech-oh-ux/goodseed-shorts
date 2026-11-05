package com.goodseed.shorts.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Path
import java.io.File
import java.util.Random

/**
 * 템플릿의 기본 배경을 앱이 직접 그린다(그라데이션 + 무늬). 외부 이미지·소재를 쓰지 않는다.
 * 좌표는 모두 가로·세로 비율이라 어떤 크기로 그려도 같은 모양이다(미리보기 270x480 ~ 합성 1080x1920).
 */
object BackgroundArt {
    private const val VERSION = 2

    fun draw(t: VideoTemplate, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val wf = w.toFloat(); val hf = h.toFloat(); val s = wf / 1080f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, hf, t.bgColors.toIntArray(), null, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, wf, hf, p)
        p.shader = null
        val rnd = Random(t.seed)
        val pc = t.patternColor
        fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

        when (t.pattern) {
            Pattern.NONE -> {}
            Pattern.STARS -> {
                repeat(170) {
                    val x = rnd.nextFloat() * wf; val y = rnd.nextFloat().let { it * it } * hf * 0.85f
                    val r = (1.2f + rnd.nextFloat() * 2.6f) * s
                    p.color = withAlpha(pc, 110 + rnd.nextInt(145)); cv.drawCircle(x, y, r, p)
                }
                repeat(12) {   // 반짝이는 큰 별(십자 빛)
                    val x = rnd.nextFloat() * wf; val y = rnd.nextFloat() * hf * 0.6f
                    val len = (16f + rnd.nextFloat() * 18f) * s
                    p.color = withAlpha(pc, 220); p.strokeWidth = 2.2f * s
                    cv.drawLine(x - len, y, x + len, y, p); cv.drawLine(x, y - len, x, y + len, p)
                    cv.drawCircle(x, y, 3.2f * s, p)
                }
            }
            Pattern.BOKEH -> repeat(18) {
                val x = rnd.nextFloat() * wf; val y = rnd.nextFloat() * hf
                val r = (40f + rnd.nextFloat() * 130f) * s
                p.shader = RadialGradient(x, y, r, intArrayOf(withAlpha(pc, 70), withAlpha(pc, 28), withAlpha(pc, 0)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
                cv.drawCircle(x, y, r, p); p.shader = null
            }
            Pattern.RAYS -> {   // 위쪽 가운데에서 퍼지는 빛줄기
                val ox = wf * 0.5f; val oy = -hf * 0.05f
                val path = Path()
                for (i in 0 until 9) {
                    val ang = Math.toRadians(62.0 + i * 7.0 + rnd.nextInt(3))
                    val spread = Math.toRadians(2.6 + rnd.nextInt(30) / 10.0)
                    val len = hf * 1.3f
                    path.reset(); path.moveTo(ox, oy)
                    path.lineTo(ox + (Math.cos(ang - spread) * len).toFloat(), oy + (Math.sin(ang - spread) * len).toFloat())
                    path.lineTo(ox + (Math.cos(ang + spread) * len).toFloat(), oy + (Math.sin(ang + spread) * len).toFloat())
                    path.close()
                    p.shader = LinearGradient(ox, oy, ox, hf, withAlpha(pc, 46), withAlpha(pc, 0), Shader.TileMode.CLAMP)
                    cv.drawPath(path, p)
                }
                p.shader = null
            }
            Pattern.GRAIN -> {
                repeat((w * h / 45).coerceAtLeast(2000)) {
                    val x = rnd.nextInt(w).toFloat(); val y = rnd.nextInt(h).toFloat()
                    p.color = if (rnd.nextBoolean()) withAlpha(0xFFFFFF, 6 + rnd.nextInt(26)) else withAlpha(0x000000, 10 + rnd.nextInt(36))
                    cv.drawRect(x, y, x + 1.6f * s + 0.6f, y + 1.6f * s + 0.6f, p)
                }
                p.shader = RadialGradient(wf / 2, hf / 2, hf * 0.72f, intArrayOf(0x00000000, 0x00000000, 0x88000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                cv.drawRect(0f, 0f, wf, hf, p); p.shader = null   // 가장자리가 어두워지는 비네팅
            }
            Pattern.FRAME -> {
                p.style = Paint.Style.STROKE
                p.color = withAlpha(pc, 150); p.strokeWidth = 4f * s
                cv.drawRoundRect(RectF(wf * 0.07f, hf * 0.045f, wf * 0.93f, hf * 0.955f), 26f * s, 26f * s, p)
                p.color = withAlpha(pc, 90); p.strokeWidth = 2f * s
                cv.drawRoundRect(RectF(wf * 0.09f, hf * 0.06f, wf * 0.91f, hf * 0.94f), 18f * s, 18f * s, p)
                p.style = Paint.Style.FILL
            }
            Pattern.SUN -> {   // 지평선 가까이 떠오르는 해의 빛
                val cx = wf * 0.5f; val cy = hf * 0.66f; val r = wf * 0.62f
                p.shader = RadialGradient(cx, cy, r, intArrayOf(withAlpha(pc, 200), withAlpha(pc, 80), withAlpha(pc, 0)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                cv.drawCircle(cx, cy, r, p); p.shader = null
                p.color = withAlpha(pc, 235); cv.drawCircle(cx, cy, wf * 0.085f, p)
            }
        }
        return bmp
    }

    /** 합성에 쓰는 1080x1920 배경 파일(같은 버전이면 재사용). */
    fun file(cacheDir: File, t: VideoTemplate): File {
        val f = File(cacheDir, "bg-${t.id}-v$VERSION.png")
        if (f.exists() && f.length() > 0) return f
        cacheDir.mkdirs()
        val b = draw(t, SubtitleLayout.FRAME_W, SubtitleLayout.FRAME_H)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
        return f
    }
}
