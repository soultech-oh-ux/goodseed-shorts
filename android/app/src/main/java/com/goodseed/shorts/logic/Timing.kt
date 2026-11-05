package com.goodseed.shorts.logic

import kotlin.math.max
import kotlin.math.roundToLong

/** 장면 하나의 시간표(합성 시간 기준, 마이크로초). */
data class SceneTime(val index: Int, val startUs: Long, val durationUs: Long, val speechStartUs: Long, val speechEndUs: Long) {
    val endUs: Long get() = startUs + durationUs
}

data class SubtitleCue(val startUs: Long, val endUs: Long, val sceneIndex: Int, val lines: List<String>)

/** 배경 음악을 합성 길이만큼 이어 붙이는 조각(합성 시작 시각, 음악에서 잘라 쓰는 길이). */
data class MusicSegment(val startUs: Long, val lengthUs: Long)

object Timing {
    const val LEAD_US = 250_000L       // 장면이 시작하고 낭독이 시작하기까지(자막이 먼저 보임)
    const val GAP_US = 650_000L        // 낭독이 끝나고 다음 장면으로 넘어가기까지 숨 쉬는 시간
    const val MIN_SCENE_US = 1_800_000L
    const val END_PAD_US = 600_000L    // 마지막 장면 뒤 여운

    /** 낭독 길이(마이크로초) 목록 → 장면 시간표. 마지막 장면에는 여운을 더한다. */
    fun scenes(voiceUs: List<Long>): List<SceneTime> {
        var t = 0L
        val out = ArrayList<SceneTime>(voiceUs.size)
        for ((i, v) in voiceUs.withIndex()) {
            val last = i == voiceUs.lastIndex
            val raw = max(MIN_SCENE_US, LEAD_US + v + GAP_US + if (last) END_PAD_US else 0L)
            val dur = (raw + 999L) / 1000L * 1000L // 영상 조각 길이가 ms 단위이므로 장면 길이도 ms 단위로 맞춘다
            out.add(SceneTime(i, t, dur, t + LEAD_US, t + LEAD_US + v))
            t += dur
        }
        return out
    }

    fun totalUs(scenes: List<SceneTime>): Long = scenes.lastOrNull()?.endUs ?: 0L

    fun speechSpans(scenes: List<SceneTime>): List<SpeechSpan> = scenes.map { SpeechSpan(it.speechStartUs, it.speechEndUs) }

    /**
     * 자막 장 시간표. 한 장면의 자막이 여러 쪽이면(줄 수가 많을 때) 글자 수 비율로 장면 시간을 나눈다.
     * 첫 쪽은 장면이 시작하자마자 보인다.
     */
    fun cues(scenes: List<SceneTime>, pagesPerScene: List<List<List<String>>>): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        for (s in scenes) {
            val pages = pagesPerScene.getOrNull(s.index).orEmpty()
            if (pages.isEmpty()) continue
            val weights = pages.map { p -> max(1, p.sumOf { it.length }) }
            val sum = weights.sum().toDouble()
            var acc = 0.0
            var cursor = s.startUs
            for ((k, p) in pages.withIndex()) {
                acc += weights[k]
                val end = if (k == pages.lastIndex) s.endUs else s.startUs + (s.durationUs * (acc / sum)).roundToLong()
                out.add(SubtitleCue(cursor, end, s.index, p))
                cursor = end
            }
        }
        return out
    }

    /** 음악 길이가 합성보다 짧으면 반복해서 이어 붙인다(마지막 조각은 남은 만큼만). */
    fun musicSegments(totalUs: Long, musicUs: Long): List<MusicSegment> {
        if (totalUs <= 0 || musicUs <= 0) return emptyList()
        val out = ArrayList<MusicSegment>()
        var t = 0L
        while (t < totalUs) {
            val len = minOf(musicUs, totalUs - t)
            out.add(MusicSegment(t, len))
            t += len
        }
        return out
    }
}
