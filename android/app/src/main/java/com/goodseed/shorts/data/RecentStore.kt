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
            val fs = o.optJSONArray("files")
            RecentWork(o.getString("title"), o.getString("uri"), o.getLong("at"), if (o.isNull("thumb")) "" else o.optString("thumb"),
                if (o.isNull("pid")) "" else o.optString("pid"), if (fs == null) emptyList() else (0 until fs.length()).map { fs.getString(it) })
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<RecentWork>) {
        val arr = JSONArray()
        list.forEach { w ->
            arr.put(JSONObject().put("title", w.title).put("uri", w.uri).put("at", w.savedAtMillis).put("thumb", w.thumbPath)
                .put("pid", w.projectId).put("files", JSONArray(w.files)))
        }
        file.writeText(arr.toString())
    }

    fun add(w: RecentWork) = write((listOf(w) + load().filter { it.uri != w.uri }).take(12))

    fun remove(uri: String) = write(load().filter { it.uri != uri })
}
