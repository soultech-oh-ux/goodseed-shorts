package com.goodseed.shorts.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 최근 작품 목록(앱 내부 저장소의 작은 json). 최대 12개. */
class RecentStore(private val ctx: Context) {
    private val file get() = File(ctx.filesDir, "recent.json")
    val thumbDir get() = File(ctx.filesDir, "thumbs").also { it.mkdirs() }
    val snapshotDir get() = File(ctx.filesDir, "projects").also { it.mkdirs() }

    fun load(): List<RecentWork> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val fs = o.optJSONArray("files")
            RecentWork(o.getString("title"), o.getString("uri"), o.getLong("at"), if (o.isNull("thumb")) "" else o.optString("thumb"),
                if (o.isNull("pid")) "" else o.optString("pid"), if (fs == null) emptyList() else (0 until fs.length()).map { fs.getString(it) },
                if (o.isNull("snap")) "" else o.optString("snap"))
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<RecentWork>) {
        val arr = JSONArray()
        list.forEach { w ->
            arr.put(JSONObject().put("title", w.title).put("uri", w.uri).put("at", w.savedAtMillis).put("thumb", w.thumbPath)
                .put("pid", w.projectId).put("files", JSONArray(w.files)).put("snap", w.snapshot))
        }
        file.writeText(arr.toString())
    }

    /** 새 작품을 맨 앞에 넣는다. 12개를 넘어 목록에서 밀려난 작품의 스냅샷 파일은 함께 지운다(받은 파일·녹음은 7일 정리가 맡는다). */
    fun add(w: RecentWork) {
        val before = load()
        val next = (listOf(w) + before.filter { it.uri != w.uri }).take(12)
        write(next)
        before.filter { o -> o.snapshot.isNotBlank() && next.none { it.snapshot == o.snapshot } }.forEach { deleteSnapshot(it.snapshot) }
    }

    fun remove(uri: String) = write(load().filter { it.uri != uri })

    // ---------- 스냅샷 파일 ----------
    /** 안전한 파일 이름(영숫자·하이픈·.json)만 받는다 — 목록 파일이 바뀌어 있어도 projects/ 밖은 건드리지 않는다. */
    private fun snapFile(name: String): File? = name.takeIf { Regex("[A-Za-z0-9-]{8,64}\\.json").matches(it) }?.let { File(snapshotDir, it) }

    /** 스냅샷을 쓰고 파일 이름을 돌려준다(실패하면 ""). */
    fun saveSnapshot(p: Project): String = runCatching {
        val name = java.util.UUID.randomUUID().toString() + ".json"
        val f = File(snapshotDir, name)
        val tmp = File(snapshotDir, "$name.tmp")
        tmp.writeText(ProjectSnapshot.encode(p), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { tmp.delete(); return@runCatching "" }
        name
    }.getOrDefault("")

    fun readSnapshot(name: String): Project? = runCatching { snapFile(name)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.let(ProjectSnapshot::decode) }.getOrNull()

    fun deleteSnapshot(name: String) { runCatching { snapFile(name)?.delete() } }
}
