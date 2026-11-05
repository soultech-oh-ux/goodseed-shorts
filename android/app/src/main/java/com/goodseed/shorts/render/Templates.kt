package com.goodseed.shorts.render

import com.goodseed.shorts.logic.Contrast

/** 배경 무늬(앱이 직접 그림 — 외부 이미지 없음). */
enum class Pattern { NONE, STARS, BOKEH, RAYS, GRAIN, FRAME, SUN }

/** 사진에 거는 천천히 움직이는 효과(Ken Burns). */
enum class Motion(val label: String) {
    NONE("고정"), ZOOM_IN("천천히 확대"), ZOOM_OUT("천천히 축소"), PAN_LEFT("왼쪽으로 이동"), PAN_RIGHT("오른쪽으로 이동"), PAN_UP("위로 이동")
}

enum class TextAlign { CENTER, START }

/**
 * 자막 모양. 색은 ARGB 정수, 0 은 '없음'.
 * anchorY: 자막 중심의 세로 위치(−1 맨 아래 … 1 맨 위).
 */
data class SubtitleSpec(
    val anchorY: Float,
    val sizeFactor: Float = 1f,
    val textColor: Int,
    val boxColor: Int = 0,
    val shadowColor: Int = 0,
    val shadowRadius: Float = 0f,
    val shadowDy: Float = 0f,
    val outlineColor: Int = 0,
    val outlinePx: Float = 0f,
    val align: TextAlign = TextAlign.CENTER,
)

/** 제목·출처 줄(사용자가 쓴 글자가 있을 때만 보임). */
data class HeaderSpec(
    val anchorY: Float,
    val titlePx: Float,
    val sourcePx: Float,
    val titleColor: Int,
    val sourceColor: Int,
    val shadowColor: Int = 0,
)

/** 끝 화면(마지막 장면 뒤에 붙는 카드 — 출처나 제목을 쓴 경우에만). */
data class EndCardSpec(val durationMs: Long = 2500L, val textColor: Int, val subColor: Int)

/**
 * 영상 템플릿 하나 = 자막 모양 + 기본 배경 + 사진 효과 + 제목/출처 줄 + 끝 화면.
 * 새 템플릿은 [Templates.ALL] 목록에 이 데이터를 하나 더 적으면 된다(다른 코드는 고치지 않는다).
 */
data class VideoTemplate(
    val id: String,
    val name: String,
    val tagline: String,
    val font: SubFont,
    val subtitle: SubtitleSpec,
    val bgColors: List<Int>,            // 위 → 아래 그라데이션(2개 이상)
    val pattern: Pattern = Pattern.NONE,
    val patternColor: Int = 0xFFFFFFFF.toInt(),
    val seed: Long = 1L,
    val motion: Motion = Motion.NONE,
    val motionAmount: Float = 0.10f,    // 확대/이동 크기(0.10 = 10%)
    val header: HeaderSpec? = null,
    val endCard: EndCardSpec? = null,
) {
    /** 자막이 놓이는 높이 띠(0 위 … 1 아래)의 그라데이션 위 색 — 대비 점검용. */
    fun bgColorAt(fracY: Float): Int = Contrast.lerpGradient(bgColors, fracY.coerceIn(0f, 1f))
}

object Templates {
    private fun c(v: Long) = v.toInt()

    val ALL: List<VideoTemplate> = listOf(
        VideoTemplate(
            id = "dawn", name = "새벽 묵상", tagline = "고요한 푸른 새벽빛", font = SubFont.GOWUN,
            subtitle = SubtitleSpec(anchorY = -0.40f, textColor = c(0xFFFFFFFF), boxColor = c(0x99000000), shadowColor = c(0xCC000000), shadowRadius = 6f, shadowDy = 3f),
            bgColors = listOf(c(0xFF14213D), c(0xFF35597D), c(0xFFE9A97C)), pattern = Pattern.SUN, patternColor = c(0xFFFFE2B8), seed = 11,
            motion = Motion.ZOOM_IN, motionAmount = 0.10f,
        ),
        VideoTemplate(
            id = "verse_card", name = "말씀 카드", tagline = "종이 카드에 쓴 한 구절", font = SubFont.GOWUN,
            subtitle = SubtitleSpec(anchorY = 0.0f, sizeFactor = 1.0f, textColor = c(0xFF2B2118)),
            bgColors = listOf(c(0xFFFFF8EA), c(0xFFF3E2C2)), pattern = Pattern.FRAME, patternColor = c(0xFF8A6A3B), seed = 12,
            motion = Motion.NONE,
            header = HeaderSpec(anchorY = 0.62f, titlePx = 60f, sourcePx = 42f, titleColor = c(0xFF5C3F17), sourceColor = c(0xFF6B4F25)),
            endCard = EndCardSpec(textColor = c(0xFF2B2118), subColor = c(0xFF6B4F25)),
        ),
        VideoTemplate(
            id = "film", name = "감성 필름", tagline = "빛바랜 필름 느낌", font = SubFont.GOWUN,
            subtitle = SubtitleSpec(anchorY = -0.50f, textColor = c(0xFFF7EBDA), shadowColor = c(0xE6000000), shadowRadius = 8f, shadowDy = 3f, outlineColor = c(0xFF1A110C), outlinePx = 5f),
            bgColors = listOf(c(0xFF4A3A31), c(0xFF1C1512)), pattern = Pattern.GRAIN, seed = 13,
            motion = Motion.ZOOM_IN, motionAmount = 0.12f,
        ),
        VideoTemplate(
            id = "minimal", name = "미니멀 화이트", tagline = "깨끗한 흰 바탕", font = SubFont.PRETENDARD,
            subtitle = SubtitleSpec(anchorY = -0.05f, sizeFactor = 0.95f, textColor = c(0xFF1B1B1F), align = TextAlign.START),
            bgColors = listOf(c(0xFFFFFFFF), c(0xFFECEEF4)), seed = 14,
            motion = Motion.NONE,
            header = HeaderSpec(anchorY = 0.70f, titlePx = 52f, sourcePx = 38f, titleColor = c(0xFF1B1B1F), sourceColor = c(0xFF55576A)),
            endCard = EndCardSpec(textColor = c(0xFF1B1B1F), subColor = c(0xFF55576A)),
        ),
        VideoTemplate(
            id = "night", name = "밤하늘", tagline = "별이 쏟아지는 밤", font = SubFont.PRETENDARD,
            subtitle = SubtitleSpec(anchorY = -0.20f, textColor = c(0xFFFFFFFF), shadowColor = c(0xFF050820), shadowRadius = 8f, shadowDy = 2f, outlineColor = c(0xFF0A0F2E), outlinePx = 6f),
            bgColors = listOf(c(0xFF060A24), c(0xFF1B2A63), c(0xFF3A2D6B)), pattern = Pattern.STARS, seed = 15,
            motion = Motion.ZOOM_OUT, motionAmount = 0.10f,
        ),
        VideoTemplate(
            id = "sunset", name = "따뜻한 노을", tagline = "붉게 물든 저녁", font = SubFont.GOWUN,
            subtitle = SubtitleSpec(anchorY = -0.35f, textColor = c(0xFFFFFFFF), boxColor = c(0xB3331326), shadowColor = c(0x99000000), shadowRadius = 5f, shadowDy = 2f),
            bgColors = listOf(c(0xFF4B2352), c(0xFFB93F63), c(0xFFF39A4C)), pattern = Pattern.SUN, patternColor = c(0xFFFFD79A), seed = 16,
            motion = Motion.PAN_RIGHT, motionAmount = 0.10f,
        ),
        VideoTemplate(
            id = "verse_focus", name = "성경 구절 강조", tagline = "큰 글씨와 구절 표시", font = SubFont.PRETENDARD,
            subtitle = SubtitleSpec(anchorY = -0.12f, sizeFactor = 1.15f, textColor = c(0xFFFFE9A8), shadowColor = c(0xE6000000), shadowRadius = 8f, shadowDy = 3f),
            bgColors = listOf(c(0xFF0D2236), c(0xFF1C4A5A)), pattern = Pattern.RAYS, patternColor = c(0xFFFFF1C2), seed = 17,
            motion = Motion.ZOOM_IN, motionAmount = 0.08f,
            header = HeaderSpec(anchorY = 0.62f, titlePx = 64f, sourcePx = 58f, titleColor = c(0xFFFFFFFF), sourceColor = c(0xFFFFD36B), shadowColor = c(0xCC000000)),
            endCard = EndCardSpec(textColor = c(0xFFFFE9A8), subColor = c(0xFFFFFFFF)),
        ),
        VideoTemplate(
            id = "quote", name = "인용 명언", tagline = "마음에 남는 한 문장", font = SubFont.GOWUN,
            subtitle = SubtitleSpec(anchorY = 0.0f, sizeFactor = 1.05f, textColor = c(0xFFFFFFFF), shadowColor = c(0x80000000), shadowRadius = 4f, shadowDy = 2f, align = TextAlign.START),
            bgColors = listOf(c(0xFF2A1A52), c(0xFF5B3A92)), pattern = Pattern.BOKEH, patternColor = c(0xFFE8D9FF), seed = 18,
            motion = Motion.PAN_UP, motionAmount = 0.08f,
            header = HeaderSpec(anchorY = -0.68f, titlePx = 48f, sourcePx = 46f, titleColor = c(0xFFE8D9FF), sourceColor = c(0xFFFFFFFF), shadowColor = c(0x80000000)),
            endCard = EndCardSpec(textColor = c(0xFFFFFFFF), subColor = c(0xFFE8D9FF)),
        ),
    )

    val DEFAULT: VideoTemplate = ALL.first()

    fun byId(id: String?): VideoTemplate = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /** 자막이 놓이는 띠(앵커 ±0.12 높이)에서 글자 색과 배경의 가장 낮은 대비(상자가 있으면 상자를 얹은 색으로 계산). */
    fun worstSubtitleContrast(t: VideoTemplate): Double {
        val s = t.subtitle
        val center = (1f - s.anchorY) / 2f
        return listOf(center - 0.10f, center, center + 0.10f).minOf { y ->
            var bg = t.bgColorAt(y)
            if ((s.boxColor ushr 24) != 0) bg = Contrast.over(s.boxColor, bg)
            Contrast.ratio(s.textColor, bg)
        }
    }
}
