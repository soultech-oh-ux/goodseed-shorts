package com.goodseed.shorts.logic

/**
 * 앱 색(ARGB 정수). 화면 색은 모두 여기서 나오고, WCAG AA 대비는 단위시험이 이 값으로 계산한다.
 * 밝은 파스텔 바탕 + 보라 계열 그라데이션 포인트(DESIGN-0.2.0.md 참조).
 */
object Palette {
    const val ACCENT = 0xFF5240D6.toInt()         // 주 버튼(그라데이션 시작)
    const val ACCENT2 = 0xFF7A3FE0.toInt()        // 주 버튼(그라데이션 끝)
    const val ACCENT_DARK = 0xFF2E2390.toInt()    // 제목 글자
    const val ACCENT_SOFT = 0xFFEAE6FF.toInt()    // 선택·강조 바탕
    const val PAGE_TOP = 0xFFEFEAFF.toInt()       // 화면 바탕 위
    const val PAGE_BOTTOM = 0xFFFAF9FF.toInt()    // 화면 바탕 아래
    const val CARD = 0xFFFFFFFF.toInt()
    const val INK = 0xFF1C1A2E.toInt()            // 본문
    const val MUTED = 0xFF5A5873.toInt()          // 보조 글자
    const val WARN = 0xFFB3261E.toInt()
    const val ON_ACCENT = 0xFFFFFFFF.toInt()
    const val BANNER = 0xFFFFF3D6.toInt()         // 업데이트 안내 바탕
    const val BANNER_INK = 0xFF4A3300.toInt()
}
