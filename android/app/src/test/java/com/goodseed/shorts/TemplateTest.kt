package com.goodseed.shorts

import com.goodseed.shorts.logic.Contrast
import com.goodseed.shorts.logic.Palette
import com.goodseed.shorts.render.*
import org.junit.Assert.*
import org.junit.Test

class ContrastTest {
    @Test fun knownValues() {
        assertEquals(21.0, Contrast.ratio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, Contrast.ratio(0xFF336699.toInt(), 0xFF336699.toInt()), 0.001)
        // 반투명 검정 60% 를 흰색 위에 얹으면 어두운 회색
        val c = Contrast.over(0x99000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(0x66, c and 0xFF)
    }

    @Test fun gradientEndsAndMiddle() {
        val g = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(0xFF000000.toInt(), Contrast.lerpGradient(g, 0f))
        assertEquals(0xFFFFFFFF.toInt(), Contrast.lerpGradient(g, 1f))
        assertEquals(0x80, Contrast.lerpGradient(g, 0.5f) and 0xFF)
    }
}

/** 앱 화면 색의 WCAG AA(본문 4.5:1) 점검 — 고령 사용자 가독성. */
class PaletteContrastTest {
    private fun check(name: String, fg: Int, bg: Int, min: Double = 4.5) {
        val r = Contrast.ratio(fg, bg)
        assertTrue("$name 대비 ${"%.2f".format(r)} < $min", r >= min)
    }

    @Test fun bodyTextOnEverySurface() {
        for ((n, bg) in listOf("바탕 위" to Palette.PAGE_TOP, "바탕 아래" to Palette.PAGE_BOTTOM, "카드" to Palette.CARD, "선택 바탕" to Palette.ACCENT_SOFT)) {
            check("본문/$n", Palette.INK, bg)
            check("보조글자/$n", Palette.MUTED, bg)
            check("제목/$n", Palette.ACCENT_DARK, bg)
            check("강조글자/$n", Palette.ACCENT, bg)
            check("경고/$n", Palette.WARN, bg)
        }
    }

    @Test fun whiteTextOnPrimaryButtonGradient() {
        check("흰 글자/버튼 시작", Palette.ON_ACCENT, Palette.ACCENT)
        check("흰 글자/버튼 끝", Palette.ON_ACCENT, Palette.ACCENT2)
    }

    @Test fun updateBannerText() = check("배너", Palette.BANNER_INK, Palette.BANNER)
}

class TemplateDataTest {
    @Test fun atLeastEightWithUniqueIdsAndNames() {
        assertTrue(Templates.ALL.size >= 8)
        assertEquals(Templates.ALL.size, Templates.ALL.map { it.id }.toSet().size)
        assertEquals(Templates.ALL.size, Templates.ALL.map { it.name }.toSet().size)
        assertTrue(Templates.ALL.all { it.id.isNotBlank() && it.name.isNotBlank() && it.tagline.isNotBlank() })
    }

    @Test fun requiredStylesExist() {
        val names = Templates.ALL.map { it.name }.toSet()
        for (n in listOf("새벽 묵상", "말씀 카드", "감성 필름", "미니멀 화이트", "밤하늘", "따뜻한 노을", "성경 구절 강조", "인용 명언")) assertTrue("$n 없음", n in names)
    }

    @Test fun lookupFallsBackToDefault() {
        assertSame(Templates.DEFAULT, Templates.byId(null))
        assertSame(Templates.DEFAULT, Templates.byId("없는-id"))
        assertEquals("night", Templates.byId("night").id)
    }

    @Test fun valuesAreInSaneRanges() {
        for (t in Templates.ALL) {
            assertTrue("${t.id} 배경 색", t.bgColors.size >= 2 && t.bgColors.all { (it ushr 24) == 0xFF })
            assertTrue("${t.id} 자막 위치", t.subtitle.anchorY in -0.85f..0.85f)
            assertTrue("${t.id} 글자 배율", t.subtitle.sizeFactor in 0.85f..1.3f)
            assertTrue("${t.id} 글자색 불투명", (t.subtitle.textColor ushr 24) == 0xFF)
            assertTrue("${t.id} 움직임 크기", t.motionAmount in 0f..0.30f)
            assertTrue("${t.id} 외곽선 두께", t.subtitle.outlinePx in 0f..12f)
            t.header?.let { assertTrue("${t.id} 제목 위치", it.anchorY in -0.85f..0.85f) }
            t.endCard?.let { assertTrue("${t.id} 끝 화면 길이", it.durationMs in 1000L..6000L) }
        }
    }

    @Test fun subtitleIsReadableOnTheGeneratedBackground() {
        for (t in Templates.ALL) {
            val r = Templates.worstSubtitleContrast(t)
            assertTrue("${t.id} 자막 대비 ${"%.2f".format(r)}", r >= 4.5)
        }
    }

    @Test fun headerAndEndCardTextAreReadable() {
        for (t in Templates.ALL) {
            t.header?.let { h ->
                val y = (1f - h.anchorY) / 2f
                val bg = t.bgColorAt(y)
                assertTrue("${t.id} 제목 대비", Contrast.ratio(h.titleColor, bg) >= 4.5)
                assertTrue("${t.id} 출처 대비", Contrast.ratio(h.sourceColor, bg) >= 4.5)
            }
            t.endCard?.let { e ->
                val bg = t.bgColorAt(0.5f)
                assertTrue("${t.id} 끝 화면 글자", Contrast.ratio(e.textColor, bg) >= 4.5)
                assertTrue("${t.id} 끝 화면 보조", Contrast.ratio(e.subColor, bg) >= 4.5)
            }
        }
    }

    @Test fun atLeastThreeTemplatesHaveHeaderAndEndCard_andMotionIsVaried() {
        assertTrue(Templates.ALL.count { it.header != null } >= 3)
        assertTrue(Templates.ALL.count { it.endCard != null } >= 3)
        assertTrue(Templates.ALL.map { it.motion }.toSet().size >= 4)
        assertTrue(Templates.ALL.map { it.pattern }.toSet().size >= 5)
    }

    @Test fun endCardTextsPreferSourceThenTitle() {
        assertNull(FrameComposer.endCardTexts("", "  "))
        assertEquals("시편 23:1" to "", FrameComposer.endCardTexts("", "시편 23:1"))
        assertEquals("제목" to "", FrameComposer.endCardTexts(" 제목 ", ""))
        assertEquals("시편 23:1" to "오늘의 묵상", FrameComposer.endCardTexts("오늘의 묵상", "시편 23:1"))
    }
}

class KenBurnsMathTest {
    private val eps = 1e-4f

    @Test fun noneIsIdentity() {
        val s = KenBurnsMath.state(Motion.NONE, 0.2f, 0.6f)
        assertEquals(KbState(1f, 0f, 0f), s)
    }

    @Test fun zoomInGrowsAndZoomOutShrinks() {
        assertEquals(1f, KenBurnsMath.state(Motion.ZOOM_IN, 0.1f, 0f).scale, eps)
        assertEquals(1.1f, KenBurnsMath.state(Motion.ZOOM_IN, 0.1f, 1f).scale, eps)
        assertEquals(1.1f, KenBurnsMath.state(Motion.ZOOM_OUT, 0.1f, 0f).scale, eps)
        assertEquals(1f, KenBurnsMath.state(Motion.ZOOM_OUT, 0.1f, 1f).scale, eps)
    }

    @Test fun panMovesAcrossTheWholeRangeAndKeepsEdgesCovered() {
        for (m in listOf(Motion.PAN_LEFT, Motion.PAN_RIGHT, Motion.PAN_UP)) {
            val a = KenBurnsMath.state(m, 0.1f, 0f); val b = KenBurnsMath.state(m, 0.1f, 1f)
            assertNotEquals("$m 움직임 없음", a.tx + a.ty, b.tx + b.ty, 1e-6f)
        }
        for (m in Motion.entries) for (i in 0..20) {
            val p = i / 20f
            val s = KenBurnsMath.state(m, 0.12f, p)
            assertTrue("$m 배율 ≥ 1", s.scale >= 1f - eps)
            // 이동량이 (배율−1)을 넘으면 가장자리가 비어 보인다
            assertTrue("$m p=$p tx", kotlin.math.abs(s.tx) <= s.scale - 1f + eps)
            assertTrue("$m p=$p ty", kotlin.math.abs(s.ty) <= s.scale - 1f + eps)
        }
    }

    @Test fun progressAndAmountAreClamped() {
        assertEquals(KenBurnsMath.state(Motion.ZOOM_IN, 0.1f, 1f), KenBurnsMath.state(Motion.ZOOM_IN, 0.1f, 5f))
        assertEquals(KenBurnsMath.state(Motion.ZOOM_IN, 0.1f, 0f), KenBurnsMath.state(Motion.ZOOM_IN, 0.1f, -3f))
        assertEquals(1.3f, KenBurnsMath.state(Motion.ZOOM_IN, 9f, 1f).scale, eps)
        assertEquals(1f, KenBurnsMath.state(Motion.ZOOM_IN, -1f, 1f).scale, eps)
    }
}
