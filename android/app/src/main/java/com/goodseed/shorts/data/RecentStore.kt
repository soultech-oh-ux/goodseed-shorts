package com.goodseed.shorts.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 최근 작품 목록(앱 내부 저장소의 작은 json). 최대 12개. */
class RecentStore(private val ctx: Context) {
    private val file get() = File(ctx.filesDir, "recent.json")
    val thumbDir get() = File(ctx.filesDir, "thumbs").also { it.mkdirs() }

    fun load(): List<RecentWork> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RecentWork(o.getString("title"), o.getString("uri"), o.getLong("at"), o.optString("thumb"))
        }
    }.getOrDefault(emptyList())

    fun add(w: RecentWork) {
        val list = (listOf(w) + load().filter { it.uri != w.uri }).take(12)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("title", it.title).put("uri", it.uri).put("at", it.savedAtMillis).put("thumb", it.thumbPath)) }
        file.writeText(arr.toString())
    }

    fun remove(uri: String) {
        val list = load().filter { it.uri != uri }
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("title", it.title).put("uri", it.uri).put("at", it.savedAtMillis).put("thumb", it.thumbPath)) }
        file.writeText(arr.toString())
    }
}
