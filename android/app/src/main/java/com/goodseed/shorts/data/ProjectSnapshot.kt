package com.goodseed.shorts.data

import com.goodseed.shorts.logic.Attribution
import com.goodseed.shorts.logic.FreeProvider
import com.goodseed.shorts.render.SubFont
import com.goodseed.shorts.render.SubSize
import com.goodseed.shorts.render.Templates
import org.json.JSONArray
import org.json.JSONObject

/**
 * 영상을 저장할 때의 작품 상태(Project 전체)를 앱 내부 json 으로 적어 두었다가, 최근 작품의 [수정]에서 되살린다.
 * 알 수 없는 값(옛 글꼴 이름 등)은 기본값으로, 깨진 파일은 null 로 — 어떤 경우에도 예외를 밖으로 내지 않는다.
 */
object ProjectSnapshot {
    const val VERSION = 1

    fun encode(p: Project): String = JSONObject().apply {
        put("v", VERSION)
        put("id", p.id)
        put("voiceMode", p.voiceMode.name)
        put("rawText", p.rawText)
        put("scenes", JSONArray().also { a -> p.scenes.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("text", s.text).put("mediaId", s.mediaId ?: JSONObject.NULL).put("size", s.size.name)
                .put("myVoice", s.myVoice ?: JSONObject.NULL).put("myVoiceMs", s.myVoiceMs))
        } })
        put("voice", p.voice?.let { JSONObject().put("name", it.name).put("label", it.label).put("net", it.needsNetwork) } ?: JSONObject.NULL)
        put("speed", p.speed.name)
        put("media", JSONArray().also { a -> p.media.forEach { m ->
            a.put(JSONObject().put("id", m.id).put("uri", m.uri).put("isVideo", m.isVideo).put("durMs", m.durationMs).put("label", m.label)
                .put("origin", m.origin.name).put("credit", m.credit).put("attr", m.attribution?.let(::attrJson) ?: JSONObject.NULL))
        } })
        put("music", p.music?.let { JSONObject().put("uri", it.uri).put("label", it.label).put("durMs", it.durationMs).put("attr", it.attribution?.let(::attrJson) ?: JSONObject.NULL) } ?: JSONObject.NULL)
        put("musicVolume", p.musicVolume.toDouble())
        put("font", p.font.name)
        put("templateId", p.templateId)
        put("headerTitle", p.headerTitle)
        put("headerSource", p.headerSource)
        put("showCredits", p.showCredits)
    }.toString()

    private fun attrJson(a: Attribution) = JSONObject().put("title", a.title).put("author", a.author).put("license", a.licenseLabel)
        .put("licenseUrl", a.licenseUrl).put("source", a.sourceUrl).put("provider", a.provider.name).put("requiresCredit", a.requiresCredit)

    private fun JSONObject.s(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)
    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E = enumValues<E>().firstOrNull { it.name == name } ?: default

    private fun attrOf(o: JSONObject?): Attribution? {
        if (o == null) return null
        val provider = FreeProvider.entries.firstOrNull { it.name == o.s("provider") } ?: return null
        return Attribution(o.s("title").orEmpty(), o.s("author").orEmpty(), o.s("license").orEmpty(), o.s("licenseUrl").orEmpty(), o.s("source").orEmpty(), provider, o.optBoolean("requiresCredit", false))
    }

    /** 읽을 수 없으면 null. 글이 하나도 없는 스냅샷도 쓸 수 없으므로 null. */
    fun decode(text: String): Project? = runCatching {
        val o = JSONObject(text)
        if (o.optInt("v", 0) !in 1..VERSION) return null
        val id = o.s("id")?.takeIf { it.isNotBlank() } ?: return null
        val sa = o.getJSONArray("scenes")
        val scenes = (0 until sa.length()).map { i ->
            val s = sa.getJSONObject(i)
            Scene(s.getLong("id"), s.getString("text"), s.s("mediaId"), enumOf(s.s("size"), SubSize.NORMAL), s.s("myVoice"), s.optLong("myVoiceMs", 0))
        }
        if (scenes.isEmpty()) return null
        val ma = o.optJSONArray("media") ?: JSONArray()
        val media = (0 until ma.length()).mapNotNull { i ->
            val m = ma.getJSONObject(i)
            val uri = m.s("uri")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MediaRef(m.s("id") ?: return@mapNotNull null, uri, m.optBoolean("isVideo", false), m.optLong("durMs", 0), m.s("label").orEmpty(),
                enumOf(m.s("origin"), MediaOrigin.PHONE), m.s("credit").orEmpty(), attrOf(m.optJSONObject("attr")))
        }
        val mu = o.optJSONObject("music")?.let { m -> m.s("uri")?.takeIf { it.isNotBlank() }?.let { u -> MusicChoice(u, m.s("label").orEmpty(), m.optLong("durMs", 0), attrOf(m.optJSONObject("attr"))) } }
        val vo = o.optJSONObject("voice")?.let { v -> v.s("name")?.let { VoiceChoice(it, v.s("label").orEmpty(), v.optBoolean("net", false)) } }
        Project(
            id = id, voiceMode = enumOf(o.s("voiceMode"), VoiceMode.AI), rawText = o.s("rawText").orEmpty(), scenes = scenes, voice = vo,
            speed = enumOf(o.s("speed"), Speed.SLOW), media = media, music = mu, musicVolume = o.optDouble("musicVolume", 0.5).toFloat().coerceIn(0f, 1f),
            font = enumOf(o.s("font"), SubFont.PRETENDARD), templateId = Templates.byId(o.s("templateId") ?: Templates.DEFAULT.id).id,
            headerTitle = o.s("headerTitle").orEmpty(), headerSource = o.s("headerSource").orEmpty(), showCredits = o.optBoolean("showCredits", true),
        )
    }.getOrNull()
}

/** 복원한 작품에서 이 폰이 지금 열 수 없는 자료를 걸러 낸 결과. */
class Restored(val project: Project, val droppedMedia: Int, val droppedMusic: Boolean, val lostVoices: Int) {
    val anyDropped: Boolean get() = droppedMedia > 0 || droppedMusic || lostVoices > 0

    /** 홈·미리보기에 보여 줄 안내(걸러 낸 것이 없으면 null). */
    fun notice(): String? {
        if (!anyDropped) return null
        val parts = ArrayList<String>()
        if (droppedMedia > 0) parts += "사진·영상 ${droppedMedia}개"
        if (droppedMusic) parts += "배경 음악"
        val head = if (parts.isEmpty()) "" else "이 폰에서 열 수 없어 뺀 것: ${parts.joinToString(", ")}. [배경과 음악]에서 다시 골라 주세요."
        val voice = if (lostVoices > 0) "녹음 파일이 없어진 문장 ${lostVoices}개는 [목소리]에서 다시 녹음해 주세요(그대로 두면 AI 목소리로 읽습니다)." else ""
        return listOf(head, voice).filter { it.isNotBlank() }.joinToString("\n")
    }
}

object SnapshotRestore {
    /** mediaOk/musicOk/voiceOk: 그 자료(주소·파일 경로)를 지금 열 수 있는가. 못 여는 자료는 빼고, 그 자료를 쓰던 장면은 기본 배경(순번 배정)으로 돌린다. */
    fun apply(p: Project, mediaOk: (MediaRef) -> Boolean, musicOk: (MusicChoice) -> Boolean, voiceOk: (String) -> Boolean): Restored {
        val keep = p.media.filter(mediaOk)
        val gone = p.media.map { it.id }.toSet() - keep.map { it.id }.toSet()
        var lost = 0
        val scenes = p.scenes.map { s ->
            var n = if (s.mediaId != null && s.mediaId in gone) s.copy(mediaId = null) else s
            if (n.myVoice != null && !voiceOk(n.myVoice!!)) { if (p.voiceMode == VoiceMode.MINE) lost++; n = n.copy(myVoice = null, myVoiceMs = 0) }   // 내 목소리 모드가 아니면 녹음은 영상에 쓰이지 않았으므로 알리지 않는다
            n
        }
        val musicDropped = p.music != null && !musicOk(p.music)
        return Restored(p.copy(scenes = scenes, media = keep, music = if (musicDropped) null else p.music), p.media.size - keep.size, musicDropped, lost)
    }
}
