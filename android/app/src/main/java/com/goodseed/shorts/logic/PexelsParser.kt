package com.goodseed.shorts.logic

import org.json.JSONObject

/** Pexels 영상 검색 결과 한 건. 세로 영상 위주로 가장 알맞은 파일 하나를 고른다. */
data class PexelsVideo(
    val id: Long, val durationSec: Int, val thumbUrl: String, val fileUrl: String,
    val width: Int, val height: Int, val photographer: String, val pageUrl: String,
)

object PexelsParser {
    /** https://www.pexels.com/api/documentation/#videos-search 의 응답 형식. 깨진 항목은 건너뛴다. */
    fun parse(json: String, targetHeight: Int = 1920): List<PexelsVideo> {
        val root = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
        val arr = root.optJSONArray("videos") ?: return emptyList()
        val out = ArrayList<PexelsVideo>()
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i) ?: continue
            val files = v.optJSONArray("video_files") ?: continue
            var best: JSONObject? = null
            var bestScore = Long.MAX_VALUE
            for (k in 0 until files.length()) {
                val f = files.optJSONObject(k) ?: continue
                if (f.optString("file_type") != "video/mp4") continue
                val link = f.optString("link"); if (link.isEmpty()) continue
                val w = f.optInt("width"); val h = f.optInt("height")
                if (w <= 0 || h <= 0) continue
                // 세로(h>=w)를 우선하고, 목표 높이에 가까운 것(너무 크면 용량이 큼)을 고른다
                val portraitPenalty = if (h >= w) 0L else 100_000L
                val score = portraitPenalty + kotlin.math.abs(h - targetHeight).toLong()
                if (score < bestScore) { bestScore = score; best = f }
            }
            val b = best ?: continue
            out.add(PexelsVideo(
                id = v.optLong("id"), durationSec = v.optInt("duration"),
                thumbUrl = v.optString("image"), fileUrl = b.optString("link"),
                width = b.optInt("width"), height = b.optInt("height"),
                photographer = v.optJSONObject("user")?.optString("name").orEmpty(), pageUrl = v.optString("url"),
            ))
        }
        return out
    }
}
