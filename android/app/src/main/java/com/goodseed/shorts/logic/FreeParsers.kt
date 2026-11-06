package com.goodseed.shorts.logic

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** JSON null 은 안드로이드에서 optString 이 "null" 글자로 돌려주므로(JVM 시험 라이브러리와 다르다) 빈 글자로 통일한다. */
internal fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

/** 걸러낸 건수(화면에 "이용 조건이 맞지 않아 N건 숨김"으로 알려 준다). */
data class Excluded(val license: Int = 0, val format: Int = 0, val size: Int = 0, val other: Int = 0) {
    val total: Int get() = license + format + size + other
    operator fun plus(o: Excluded) = Excluded(license + o.license, format + o.format, size + o.size, other + o.other)
}

/** 검색 한 쪽(page)의 결과. next 가 null 이면 더 없다. */
data class FreePage(val items: List<FreeMedia>, val next: Int?, val excluded: Excluded)

/** 영상 파일 후보(원본 또는 위키미디어가 미리 만들어 둔 작은 파일). */
data class VideoSource(val url: String, val width: Int, val height: Int, val bytes: Long, val mime: String, val original: Boolean) {
    val minSide: Int get() = minOf(width, height)
}

object VideoPick {
    private fun okType(mime: String): Boolean {
        val m = mime.lowercase()
        return (m.startsWith("video/webm") || m.startsWith("video/mp4")) && !m.contains("theora")
    }

    /**
     * 받을 영상 파일을 고른다. 폰이 읽을 수 있는 형식(webm·mp4)만, 크기 상한 안에서, 가로·세로 중 짧은 변이 1080 이하인 것 중
     * 가장 선명한 것을 고르고 같으면 작은 파일을 고른다. 하나도 없으면 null(용량 때문에 제외).
     */
    fun choose(candidates: List<VideoSource>, maxBytes: Long): VideoSource? {
        val ok = candidates.filter { okType(it.mime) && it.url.startsWith("https://") && (it.bytes in 1..maxBytes) }
        if (ok.isEmpty()) return null
        val pool = ok.filter { it.minSide <= 1080 }.ifEmpty { ok }
        // 선명도가 같으면 위키미디어가 만들어 둔 VP9·VP8 파일을 먼저 쓴다(원본은 AV1 처럼 폰이 못 푸는 형식일 수 있다), 그다음 작은 파일
        return pool.sortedWith(compareByDescending<VideoSource> { it.minSide.coerceAtMost(1080) }.thenBy { it.original }.thenBy { it.bytes }).first()
    }
}

object WikimediaParser {
    private const val MAX_PHOTO_PIXELS = 40_000_000L   // 이보다 큰 사진은 원본 대신 줄인 그림을 받는다
    private const val MIN_PHOTO_LONG_SIDE = 1000
    private val PHOTO_MIMES = setOf("image/jpeg", "image/png", "image/webp")

    /** 위키미디어 공용 action=query&generator=search 응답(imageinfo 또는 videoinfo)을 읽는다. 깨진 항목은 건너뛴다. */
    fun parse(json: String, kind: FreeKind, maxBytes: Long = SizeCaps.maxFor(kind)): FreePage {
        val root = try { JSONObject(json) } catch (e: Exception) { return FreePage(emptyList(), null, Excluded()) }
        val next = root.optJSONObject("continue")?.takeIf { it.has("gsroffset") }?.optInt("gsroffset")?.takeIf { it > 0 }
        val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return FreePage(emptyList(), next, Excluded())
        val list = ArrayList<JSONObject>()
        val keys = pages.keys()
        while (keys.hasNext()) pages.optJSONObject(keys.next())?.let { list += it }
        list.sortBy { it.optInt("index", Int.MAX_VALUE) }
        val out = ArrayList<FreeMedia>()
        var lic = 0; var fmt = 0; var siz = 0
        for (p in list) {
            val info = (p.optJSONArray(if (kind == FreeKind.VIDEO) "videoinfo" else "imageinfo") ?: p.optJSONArray("imageinfo") ?: p.optJSONArray("videoinfo"))?.optJSONObject(0) ?: continue
            val title = p.str("title")
            val pageId = p.optLong("pageid")
            if (title.isEmpty() || pageId <= 0) continue
            val meta = info.optJSONObject("extmetadata")
            fun m(key: String) = meta?.optJSONObject(key)?.str("value").orEmpty()
            val verdict = LicenseJudge.judgeCommons(m("LicenseShortName").ifBlank { m("UsageTerms") }, m("License"), m("LicenseUrl"))
            if (!verdict.allowed) { lic++; continue }
            val w = info.optInt("width"); val h = info.optInt("height")
            val mime = info.str("mime")
            val size = info.optLong("size", -1)
            val pageUrl = info.str("descriptionurl").takeIf { it.startsWith("https://") } ?: "https://commons.wikimedia.org/wiki/" + title.replace(' ', '_')
            val att = Attribution(
                title = HtmlText.text(m("ObjectName")).ifBlank { title.removePrefix("File:").substringBeforeLast('.') },
                author = author(m("Artist"), m("Credit")),
                licenseLabel = verdict.label, licenseUrl = verdict.url, sourceUrl = pageUrl,
                provider = FreeProvider.WIKIMEDIA, requiresCredit = verdict.requiresCredit,
            )
            val thumb = info.str("thumburl").takeIf { it.startsWith("https://") }.orEmpty()
            if (kind == FreeKind.PHOTO) {
                if (info.str("mediatype") != "BITMAP" && info.has("mediatype") || mime !in PHOTO_MIMES) { fmt++; continue }
                if (maxOf(w, h) < MIN_PHOTO_LONG_SIDE) { fmt++; continue }
                val url = info.str("url").takeIf { it.startsWith("https://") } ?: continue
                val tooBig = size > maxBytes || size <= 0 || w.toLong() * h > MAX_PHOTO_PIXELS
                // 원본이 상한보다 크면 위키미디어가 줄여 주는 그림(1920 또는 3840 폭)을 받는다 — 받을 때 주소를 다시 묻는다
                out += FreeMedia(FreeProvider.WIKIMEDIA, "wm:$pageId", FreeKind.PHOTO, att, thumb, w, h, 0,
                    downloadUrl = if (tooBig) "" else url, bytes = if (tooBig) -1 else size, mime = mime,
                    resizeTitle = if (tooBig) title else "")
            } else {
                val dur = info.optDouble("duration", 0.0)
                val cands = ArrayList<VideoSource>()
                info.str("url").takeIf { it.startsWith("https://") }?.let { cands += VideoSource(it, w, h, size, mime, true) }
                val ds = info.optJSONArray("derivatives") ?: JSONArray()
                for (i in 0 until ds.length()) {
                    val d = ds.optJSONObject(i) ?: continue
                    val src = d.str("src"); if (!src.startsWith("https://")) continue
                    val bw = d.optLong("bandwidth", 0)
                    if (bw <= 0 || dur <= 0) continue
                    cands += VideoSource(src, d.optInt("width"), d.optInt("height"), (bw * dur / 8.0).toLong(), d.str("type"), false)
                }
                if (cands.none { it.mime.lowercase().let { t -> t.startsWith("video/webm") || t.startsWith("video/mp4") } }) { fmt++; continue }
                val pick = VideoPick.choose(cands, maxBytes)
                if (pick == null) { siz++; continue }
                out += FreeMedia(FreeProvider.WIKIMEDIA, "wm:$pageId", FreeKind.VIDEO, att, thumb, pick.width, pick.height, Math.round(dur).toInt(),
                    downloadUrl = pick.url, bytes = pick.bytes, mime = pick.mime.substringBefore(';').trim().ifBlank { "video/webm" })
            }
        }
        return FreePage(out, next, Excluded(lic, fmt, siz))
    }

    /** 위키미디어 작가 칸은 HTML 이다. 글자만 뽑고, 비었거나 '알 수 없음'이면 출처(Credit) 칸을 쓰고, 그래도 없으면 '작가 미상'. */
    fun author(artistHtml: String, creditHtml: String): String {
        val a = HtmlText.text(artistHtml, 60)
        if (a.isNotBlank() && !isUnknown(a)) return a
        val c = HtmlText.text(creditHtml, 60)
        if (c.isNotBlank() && !isUnknown(c) && !c.equals("own work", true)) return c
        return "작가 미상"
    }

    private fun isUnknown(s: String) = s.lowercase().let { it.startsWith("unknown") || it == "anonymous" || it == "n/a" }

    /** prop=imageinfo&iiurlwidth=N 로 줄인 그림 주소를 물은 응답에서 thumburl 을 꺼낸다. */
    fun thumbUrlFrom(json: String): String? {
        val root = try { JSONObject(json) } catch (e: Exception) { return null }
        val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val k = pages.keys()
        while (k.hasNext()) {
            val u = pages.optJSONObject(k.next())?.optJSONArray("imageinfo")?.optJSONObject(0)?.str("thumburl").orEmpty()
            if (u.startsWith("https://")) return u
        }
        return null
    }
}

object OpenverseParser {
    private const val MIN_LONG_SIDE = 800
    private val TYPES = setOf("jpg", "jpeg", "png", "webp")

    /** https://api.openverse.org/v1/images/ 응답. 허용 이용 조건·https 주소·충분한 크기만 남긴다. */
    fun parse(json: String): FreePage {
        val root = try { JSONObject(json) } catch (e: Exception) { return FreePage(emptyList(), null, Excluded()) }
        val arr = root.optJSONArray("results") ?: return FreePage(emptyList(), null, Excluded())
        val out = ArrayList<FreeMedia>()
        var lic = 0; var fmt = 0
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.str("id"); val url = r.str("url")
            if (id.isEmpty() || !url.startsWith("https://")) { fmt++; continue }
            if (r.optBoolean("mature", false)) continue
            val v = LicenseJudge.judgeOpenverse(r.str("license"), r.str("license_version"))
            if (!v.allowed) { lic++; continue }
            val ft = r.str("filetype").lowercase()
            if (ft.isNotEmpty() && ft !in TYPES) { fmt++; continue }
            val w = r.optInt("width"); val h = r.optInt("height")
            if (w > 0 && h > 0 && maxOf(w, h) < MIN_LONG_SIDE) { fmt++; continue }
            val landing = r.str("foreign_landing_url").takeIf { it.startsWith("https://") } ?: r.str("detail_url")
            val licUrl = r.str("license_url").takeIf { it.startsWith("https://creativecommons.org/") } ?: v.url
            val att = Attribution(
                title = HtmlText.text(r.str("title")).ifBlank { "무제" },
                author = HtmlText.text(r.str("creator"), 60).ifBlank { "작가 미상" },
                licenseLabel = v.label, licenseUrl = licUrl, sourceUrl = landing,
                provider = FreeProvider.OPENVERSE, requiresCredit = v.requiresCredit,
            )
            val thumb = r.str("thumbnail").takeIf { it.startsWith("https://") } ?: url
            val mime = when (ft) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
            out += FreeMedia(FreeProvider.OPENVERSE, "ov:$id", FreeKind.PHOTO, att, thumb, w, h, 0, url,
                bytes = if (r.isNull("filesize")) -1 else r.optLong("filesize", -1), mime = mime)
        }
        return FreePage(out, null, Excluded(license = lic, format = fmt))
    }
}

/** 요청 주소 만들기(순수 함수라 시험할 수 있다). 검색어는 위키 검색 연산자가 섞이지 않게 먼저 닦는다. */
object FreeUrls {
    const val COMMONS_API = "https://commons.wikimedia.org/w/api.php"
    const val OPENVERSE_API = "https://api.openverse.org/v1/images/"

    /** 서버 쪽에서 이용 조건이 맞는 분류의 파일만 찾게 한다(이걸 빼면 결과의 대부분이 CC BY-SA 라 쓸 수가 없다 — 실측). */
    const val LICENSE_CATEGORIES = "CC-Zero|CC-BY-4.0|CC-BY-3.0|CC-BY-2.5|CC-BY-2.0|PD-self|PD-USGov"
    const val EXTMETADATA = "LicenseShortName|License|UsageTerms|Artist|Credit|ObjectName|LicenseUrl"
    const val PAGE_SIZE = 30

    /** 글자·숫자·공백·하이픈·작은따옴표만 남긴다(따옴표·콜론·세로줄·꺾쇠 등 검색 연산자 제거, 낱말 앞 하이픈 제거). */
    fun cleanTerms(q: String): String =
        q.map { if (it.isLetterOrDigit() || it == '\'' || it == '-') it else ' ' }.joinToString("")
            .split(' ').map { it.trimStart('-') }.filter { it.isNotEmpty() }.joinToString(" ").take(80)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun wikimediaSearch(terms: String, kind: FreeKind, offset: Int = 0): String {
        val clean = cleanTerms(terms)
        val search = if (kind == FreeKind.VIDEO) "$clean filetype:video incategory:$LICENSE_CATEGORIES"
        else "$clean filetype:bitmap filew:>1000 incategory:$LICENSE_CATEGORIES"
        val p = if (kind == FreeKind.VIDEO) "video" else "image"
        val prefix = if (kind == FreeKind.VIDEO) "vi" else "ii"
        return COMMONS_API + "?action=query&format=json&generator=search&gsrnamespace=6" +
            "&gsrsearch=${enc(search)}&gsrlimit=$PAGE_SIZE" + (if (offset > 0) "&gsroffset=$offset" else "") +
            "&prop=${p}info&${prefix}prop=" + enc("url|size|mime|extmetadata|mediatype" + (if (kind == FreeKind.VIDEO) "|derivatives" else "")) +
            "&${prefix}urlwidth=500&${prefix}extmetadatafilter=${enc(EXTMETADATA)}"
    }

    /** 사진 한 장의 줄인 그림 주소를 묻는 요청(원본이 용량 상한보다 클 때). */
    fun wikimediaThumb(fileTitle: String, width: Int): String =
        COMMONS_API + "?action=query&format=json&titles=${enc(fileTitle)}&prop=imageinfo&iiprop=url&iiurlwidth=$width"

    const val NASA_API = "https://images-api.nasa.gov/search"
    const val MET_SEARCH = "https://collectionapi.metmuseum.org/public/collection/v1.1/search"
    /** 메트 미술관: 검색은 v1.1(v1 검색은 폐지), 작품 상세(objects/{id})는 v1 에서만 동작한다(v1.1 은 404 — 2026-11-06 실측). */
    const val MET_OBJECT = "https://collectionapi.metmuseum.org/public/collection/v1/objects/"
    const val OPENVERSE_AUDIO_API = "https://api.openverse.org/v1/audio/"
    const val NASA_PAGE_SIZE = 24
    const val MET_LIMIT = 16

    fun nasaSearch(terms: String, kind: FreeKind, page: Int = 1): String =
        NASA_API + "?q=${enc(cleanTerms(terms))}&media_type=${if (kind == FreeKind.VIDEO) "video" else "image"}&page_size=$NASA_PAGE_SIZE" + (if (page > 1) "&page=$page" else "")

    /** 메트: 제목에 낱말이 든 공개 도메인·그림 있는 작품. 기본은 회화(Paintings)만, broad 이면 모든 분류. */
    fun metSearch(terms: String, offset: Int, broad: Boolean): String =
        MET_SEARCH + "?q=${enc(cleanTerms(terms))}&hasImages=true&isPublicDomain=true&title=true" + (if (broad) "" else "&medium=Paintings") + "&limit=$MET_LIMIT&offset=$offset"

    fun metObject(id: Long): String = MET_OBJECT + id

    fun openverseAudio(terms: String): String =
        OPENVERSE_AUDIO_API + "?q=${enc(cleanTerms(terms))}&license=cc0,pdm,by&mature=false&page_size=20"

    fun openverse(terms: String): String =
        OPENVERSE_API + "?q=${enc(cleanTerms(terms))}&license=cc0,pdm,by&category=photograph&mature=false&page_size=20"
}
