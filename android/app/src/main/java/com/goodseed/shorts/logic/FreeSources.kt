package com.goodseed.shorts.logic

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** 찾기 화면의 출처(검색을 보내는 곳). 크레딧을 적는 공급처는 FreeProvider. */
enum class FreeSource(val label: String, val provider: FreeProvider) {
    WIKIMEDIA("위키미디어", FreeProvider.WIKIMEDIA),
    OPENVERSE("Openverse", FreeProvider.OPENVERSE),
    NASA("NASA", FreeProvider.NASA),
    MET("명화", FreeProvider.MET),
}

/** 찾기 화면 위쪽의 출처 탭. */
enum class FreeTab(val label: String, val source: FreeSource?) {
    ALL("전체", null), WIKIMEDIA("위키미디어", FreeSource.WIKIMEDIA), OPENVERSE("Openverse", FreeSource.OPENVERSE),
    NASA("NASA", FreeSource.NASA), MET("명화", FreeSource.MET),
}

object FreeSources {
    /** 사진은 네 곳, 영상은 위키미디어와 NASA 에만 있다. */
    fun forKind(kind: FreeKind): List<FreeSource> =
        if (kind == FreeKind.PHOTO) listOf(FreeSource.WIKIMEDIA, FreeSource.NASA, FreeSource.MET, FreeSource.OPENVERSE)
        else listOf(FreeSource.WIKIMEDIA, FreeSource.NASA)

    fun tabsFor(kind: FreeKind): List<FreeTab> =
        if (kind == FreeKind.PHOTO) FreeTab.entries.toList() else listOf(FreeTab.ALL, FreeTab.WIKIMEDIA, FreeTab.NASA)

    /** 지금 탭에서 실제로 검색을 보낼 출처들. 그 종류에 없는 출처 탭이면 빈 목록. */
    fun active(tab: FreeTab, kind: FreeKind): List<FreeSource> {
        val all = forKind(kind)
        return tab.source?.let { s -> all.filter { it == s } } ?: all
    }

    /** 영상 탭을 눌렀을 때 사진 전용 탭(Openverse·명화)에 있었다면 '전체'로 돌린다. */
    fun fixTab(tab: FreeTab, kind: FreeKind): FreeTab = if (tab in tabsFor(kind)) tab else FreeTab.ALL
}

object FreeMerge {
    /** 출처별 결과를 번갈아 한 줄로 합친다(여러 출처가 골고루 보이도록). 같은 번호는 한 번만. */
    fun interleave(lists: List<List<FreeMedia>>): List<FreeMedia> {
        val out = ArrayList<FreeMedia>(); val seen = HashSet<String>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { if (seen.add(it.id)) out += it }
        return out
    }
}

/** 찾은 배경 음악 한 건(받기 전). */
data class FreeMusic(
    val id: String,
    val attribution: Attribution,
    val durationMs: Long,
    val bytes: Long,
    val downloadUrl: String,
    val ext: String,
) {
    val fileKey: String get() = id.replace(Regex("[^A-Za-z0-9]+"), "_")
    fun durationText(): String = if (durationMs <= 0) "길이 모름" else "%d:%02d".format((durationMs / 1000) / 60, (durationMs / 1000) % 60)
}

data class MusicPage(val items: List<FreeMusic>, val excluded: Excluded)

/** https 만 쓴다. 알려진 NASA 자료 서버의 http 주소만 https 로 올려 쓴다(그 서버는 https 도 제공한다). */
object SafeUrl {
    private val UPGRADE_HOSTS = setOf("images-assets.nasa.gov", "images-api.nasa.gov")
    fun https(url: String): String {
        if (url.startsWith("https://")) return url
        if (url.startsWith("http://")) {
            val host = url.removePrefix("http://").substringBefore('/')
            if (host in UPGRADE_HOSTS) return "https://" + url.removePrefix("http://")
        }
        return ""
    }
}

// ---------------------------------------------------------------- NASA
data class NasaLink(val href: String, val width: Int, val height: Int, val size: Long, val rel: String)

object NasaPick {
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png")

    /** 사진: 크기 상한 안에서 가장 큰(가로 폭) 그림 한 장. 목록에 크기가 없으면 쓰지 않는다. */
    fun image(links: List<NasaLink>, maxBytes: Long): NasaLink? =
        links.filter { SafeUrl.https(it.href).isNotEmpty() && it.href.substringAfterLast('.').lowercase() in IMAGE_EXT && it.size in 1..maxBytes && it.width > 0 }
            .maxByOrNull { it.width }

    private val VIDEO_RANK = listOf("~orig.mp4", "~large.mp4", "~medium.mp4", "~small.mp4", "~mobile.mp4", "~preview.mp4")

    /** collection.json 의 주소 목록에서 mp4 후보를 화질 순서(orig → large → medium → small → mobile → preview)로 고른다. mov 등은 쓰지 않는다. */
    fun rankedMp4(urls: List<String>): List<String> =
        VIDEO_RANK.mapNotNull { suffix -> urls.firstOrNull { it.endsWith(suffix) }?.let { SafeUrl.https(it).ifEmpty { null } } }

    /** 후보마다 서버가 알려 준 크기(sizeOf, 모르거나 접근 불가면 음수)로 상한 안에 드는 것만, 화질 순서대로 돌려준다. */
    fun choose(ranked: List<String>, sizeOf: (String) -> Long, maxBytes: Long): List<Pair<String, Long>> =
        ranked.mapNotNull { u -> sizeOf(u).takeIf { it in 1..maxBytes }?.let { u to it } }
}

object NasaParser {
    private const val MIN_LONG_SIDE = 800
    /** NASA 로고·휘장(Insignia) 같은 상징물은 쓰면 안 되므로 제목·키워드에 이런 낱말이 있으면 보여 주지 않는다. */
    private val SYMBOL_WORDS = Regex("""(?i)\b(logo|logos|insignia|emblem|patch|seal|meatball|worm)\b""")
    /** 외부 기관·개인의 저작권 표시가 있는 자료는 공개 도메인이 아닐 수 있으므로 보여 주지 않는다. */
    private val COPYRIGHT = Regex("""(?i)(©|\(c\)|copyright|all rights reserved|used with permission|courtesy of (?!nasa))""")

    fun parse(json: String, kind: FreeKind, maxBytes: Long = SizeCaps.maxFor(kind)): FreePage {
        val root = try { JSONObject(json) } catch (e: Exception) { return FreePage(emptyList(), null, Excluded()) }
        val col = root.optJSONObject("collection") ?: return FreePage(emptyList(), null, Excluded())
        var next: Int? = null
        col.optJSONArray("links")?.let { ls ->
            for (i in 0 until ls.length()) {
                val l = ls.optJSONObject(i) ?: continue
                if (l.str("rel") == "next") next = Regex("""[?&]page=(\d+)""").find(l.str("href"))?.groupValues?.get(1)?.toIntOrNull()
            }
        }
        val items = col.optJSONArray("items") ?: return FreePage(emptyList(), next, Excluded())
        val out = ArrayList<FreeMedia>(); var lic = 0; var fmt = 0; var siz = 0; var oth = 0
        val wanted = if (kind == FreeKind.VIDEO) "video" else "image"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val d = it.optJSONArray("data")?.optJSONObject(0) ?: continue
            val id = d.str("nasa_id")
            if (id.isEmpty() || d.str("media_type") != wanted) continue
            val title = HtmlText.text(d.str("title")).ifBlank { id }
            val desc = d.str("description"); val kw = d.optJSONArray("keywords").joinStrings()
            if (COPYRIGHT.containsMatchIn("$title $desc ${d.str("photographer")} ${d.str("secondary_creator")}")) { lic++; continue }
            if (SYMBOL_WORDS.containsMatchIn("$title $kw")) { oth++; continue }
            val links = ArrayList<NasaLink>()
            it.optJSONArray("links")?.let { ls ->
                for (k in 0 until ls.length()) { val l = ls.optJSONObject(k) ?: continue
                    if (l.str("render") == "image") links += NasaLink(l.str("href"), l.optInt("width"), l.optInt("height"), l.optLong("size", -1), l.str("rel")) }
            }
            val thumb = (links.firstOrNull { it.href.contains("~thumb") } ?: links.firstOrNull())?.href?.let { SafeUrl.https(it) }.orEmpty()
            val credit = credit(d.str("photographer"), d.str("secondary_creator"))
            val att = Attribution(title, credit, "공개 도메인", "https://www.nasa.gov/nasa-brand-center/images-and-media/", "https://images.nasa.gov/details/${enc(id)}",
                FreeProvider.NASA, false)
            if (kind == FreeKind.PHOTO) {
                val pick = NasaPick.image(links, maxBytes)
                if (pick == null) { siz++; continue }
                if (maxOf(pick.width, pick.height) < MIN_LONG_SIDE) { fmt++; continue }
                out += FreeMedia(FreeProvider.NASA, "nasa:$id", FreeKind.PHOTO, att, thumb, pick.width, pick.height, 0, SafeUrl.https(pick.href), pick.size,
                    if (pick.href.endsWith(".png", true)) "image/png" else "image/jpeg")
            } else {
                val coll = SafeUrl.https(it.str("href"))
                if (coll.isEmpty()) { fmt++; continue }
                out += FreeMedia(FreeProvider.NASA, "nasa:$id", FreeKind.VIDEO, att, thumb, 0, 0, 0, "", -1, "video/mp4", collectionUrl = coll)
            }
        }
        return FreePage(out, next, Excluded(lic, fmt, siz, oth))
    }

    /** 크레딧 문구: NASA 로 시작하면 그대로, 사람 이름만 있으면 "NASA / 이름", 없으면 "NASA". */
    fun credit(photographer: String, secondary: String): String {
        val p = HtmlText.text(photographer, 120).ifBlank { HtmlText.text(secondary, 120) }
        return when {
            p.isBlank() -> "NASA"
            p.startsWith("NASA", ignoreCase = true) -> p
            else -> "NASA / $p"
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun JSONArray?.joinStrings(): String = if (this == null) "" else (0 until length()).joinToString(" ") { optString(it) }
}

// ---------------------------------------------------------------- 메트로폴리탄 미술관
data class MetSearch(val total: Int, val ids: List<Long>)

object MetParser {
    private const val IMAGE_HOST = "https://images.metmuseum.org/"

    fun parseSearch(json: String): MetSearch {
        val root = try { JSONObject(json) } catch (e: Exception) { return MetSearch(0, emptyList()) }
        val arr = root.optJSONArray("objectIDs")
        val ids = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optLong(it) }.filter { it > 0 }
        return MetSearch(root.optInt("total", ids.size), ids)
    }

    /**
     * objects/{id} 응답을 읽는다. 검색이 '공개 도메인'이라고 해도 여기서 isPublicDomain 과 그림 주소를 다시 확인하고,
     * 하나라도 어긋나면 null(보여 주지 않음).
     */
    fun parseObject(json: String): FreeMedia? {
        val o = try { JSONObject(json) } catch (e: Exception) { return null }
        val id = o.optLong("objectID"); if (id <= 0) return null
        if (!o.optBoolean("isPublicDomain", false)) return null
        val big = o.str("primaryImage").takeIf { it.startsWith(IMAGE_HOST) }
        val small = o.str("primaryImageSmall").takeIf { it.startsWith(IMAGE_HOST) }
        val main = big ?: small ?: return null
        val author = HtmlText.text(o.str("artistDisplayName"), 60).ifBlank { HtmlText.text(o.str("culture"), 60) }.ifBlank { "작가 미상" }
        val date = HtmlText.text(o.str("objectDate"), 30)
        val title = HtmlText.text(o.str("title")).ifBlank { "무제" }.let { if (date.isNotBlank()) "$it ($date)" else it }
        val page = o.str("objectURL").takeIf { it.startsWith("https://www.metmuseum.org/") } ?: "https://www.metmuseum.org/art/collection/search/$id"
        val att = Attribution(title, author, "공개 도메인", "https://www.metmuseum.org/about-the-met/policies-and-documents/open-access", page, FreeProvider.MET, false)
        val alts = if (big != null && small != null && small != big) listOf(small) else emptyList()
        return FreeMedia(FreeProvider.MET, "met:$id", FreeKind.PHOTO, att, small ?: main, 0, 0, 0, main, -1, "image/jpeg", altUrls = alts)
    }

    /** 검색 위치(offset)와 '넓게 찾기' 여부를 한 숫자에 담는다(다음 쪽을 같은 방식으로 이어 찾기 위해). */
    private const val BROAD = 1_000_000
    fun cursor(offset: Int, broad: Boolean): Int = offset + if (broad) BROAD else 0
    fun offsetOf(cursor: Int): Int = cursor % BROAD
    fun isBroad(cursor: Int): Boolean = cursor >= BROAD
}

// ---------------------------------------------------------------- Openverse 음악
object OpenverseAudioParser {
    private val TYPES = mapOf("mp3" to "mp3", "ogg" to "ogg", "oga" to "ogg", "wav" to "wav", "flac" to "flac", "m4a" to "m4a", "aac" to "aac")

    fun parse(json: String): MusicPage {
        val root = try { JSONObject(json) } catch (e: Exception) { return MusicPage(emptyList(), Excluded()) }
        val arr = root.optJSONArray("results") ?: return MusicPage(emptyList(), Excluded())
        val out = ArrayList<FreeMusic>(); var lic = 0; var fmt = 0
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.str("id"); val url = r.str("url")
            if (id.isEmpty() || !url.startsWith("https://")) { fmt++; continue }
            if (r.optBoolean("mature", false)) continue
            val v = LicenseJudge.judgeOpenverse(r.str("license"), r.str("license_version"))
            if (!v.allowed) { lic++; continue }
            val ext = TYPES[r.str("filetype").lowercase()] ?: TYPES[url.substringBefore('?').substringAfterLast('.').lowercase()]
            if (ext == null) { fmt++; continue }
            val landing = r.str("foreign_landing_url").takeIf { it.startsWith("https://") } ?: r.str("detail_url")
            val licUrl = r.str("license_url").takeIf { it.startsWith("https://creativecommons.org/") } ?: v.url
            val att = Attribution(HtmlText.text(r.str("title")).ifBlank { "무제" }, HtmlText.text(r.str("creator"), 60).ifBlank { "작가 미상" },
                v.label, licUrl, landing, FreeProvider.OPENVERSE, v.requiresCredit)
            out += FreeMusic("ova:$id", att, r.optLong("duration", 0), if (r.isNull("filesize")) -1 else r.optLong("filesize", -1), url, ext)
        }
        return MusicPage(out, Excluded(license = lic, format = fmt))
    }
}
