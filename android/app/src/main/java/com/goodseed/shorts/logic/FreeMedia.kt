package com.goodseed.shorts.logic

/** 무료 자료의 출처(어디서 받았는지). 영상 끝 글자와 유튜브 설명란 문구의 재료가 된다. */
enum class FreeProvider(val label: String, val creditLine: String? = null) {
    WIKIMEDIA("위키미디어 공용"), OPENVERSE("Openverse"), PEXELS("Pexels"),
    NASA("NASA", "Credit: NASA"),
    MET("메트로폴리탄 미술관", "The Metropolitan Museum of Art, Public Domain (Open Access)"),
}

enum class FreeKind { PHOTO, VIDEO }

/** 자료 하나의 표기 정보. 프로젝트에 저장해 두었다가 영상 끝 글자·설명란 문구로 쓴다. */
data class Attribution(
    val title: String,
    val author: String,
    val licenseLabel: String,      // 예: "CC BY 4.0", "CC0 1.0", "공개 도메인"
    val licenseUrl: String,
    val sourceUrl: String,         // 원본 페이지 주소
    val provider: FreeProvider,
    val requiresCredit: Boolean,   // CC BY 처럼 출처 표기가 의무인 자료
)

/** 찾기 결과 한 건(받기 전). */
data class FreeMedia(
    val provider: FreeProvider,
    val id: String,                // 예: "wm:12345" (공급처 접두어 + 공급처 번호)
    val kind: FreeKind,
    val attribution: Attribution,
    val thumbUrl: String,
    val width: Int,
    val height: Int,
    val durationSec: Int,
    val downloadUrl: String,       // 곧바로 받을 주소. 비어 있으면 resizeTitle 로 줄인 그림 주소를 다시 물어야 한다
    val bytes: Long,               // 받을 파일 크기(모르면 -1)
    val mime: String,
    val resizeTitle: String = "",  // 위키미디어 사진이 용량 상한보다 클 때: 줄인 그림을 물을 파일 이름("File:..." 형태)
    val altUrls: List<String> = emptyList(),   // downloadUrl 이 용량 상한을 넘으면 차례로 시도할 더 작은 주소(메트 미술관의 작은 그림 등)
    val collectionUrl: String = "",            // NASA 영상: mp4 목록(collection.json) 주소 — 받을 때 크기를 확인해 고른다
) {
    val fileExt: String get() = when {
        mime.contains("webm") -> "webm"
        mime.contains("mp4") -> "mp4"
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        else -> "jpg"
    }
    /** 파일 이름에 쓰는 안전한 번호(예: "wm_12345"). */
    val fileKey: String get() = id.replace(Regex("[^A-Za-z0-9]+"), "_")
}

/** 이용 조건 판정 결과. */
data class LicenseVerdict(val allowed: Boolean, val label: String, val url: String, val requiresCredit: Boolean, val reason: String)

/** 크기 상한(영상 100MB · 사진 20MB). */
object SizeCaps {
    const val MAX_VIDEO_BYTES = 100L * 1024 * 1024
    const val MAX_PHOTO_BYTES = 20L * 1024 * 1024
    const val MAX_MUSIC_BYTES = 20L * 1024 * 1024
    fun maxFor(kind: FreeKind): Long = if (kind == FreeKind.VIDEO) MAX_VIDEO_BYTES else MAX_PHOTO_BYTES
    fun mb(bytes: Long): String = if (bytes < 0) "?" else if (bytes < 1_048_576L) "${(bytes + 1023) / 1024}KB" else "%.1fMB".format(java.util.Locale.US, bytes / 1_048_576.0)
}

/**
 * 이용 조건 판정표 — 기본으로 허용하는 것은 CC0 · 공개 도메인 · CC BY(버전 무관)뿐이다.
 * CC BY-SA(영상 전체에 같은 조건이 번진다)·NC(상업 불가)·ND(변형 불가)·GFDL·정보 없음·알 수 없는 문구는 모두 제외한다.
 * 문구가 두 가지 이상 겹쳐 있으면("CC BY 3.0 / CC BY-SA 3.0") 어느 쪽을 쓸 수 있는지 앱이 확신할 수 없으므로 제외한다.
 */
object LicenseJudge {
    private val DENY_TOKENS = setOf("sa", "nc", "nd", "gfdl", "gpl", "lgpl", "fdl", "fal", "ogl", "copyrighted", "restricted", "unknown", "all", "rights", "reserved", "sampling", "sampling+")
    private val MULTI = Regex("""[,;/+&]|\bor\b|\band\b""")
    private val TOKEN_SPLIT = Regex("""[^a-z0-9.]+""")
    private val VERSION = Regex("""\d\.\d""")

    private fun deny(reason: String) = LicenseVerdict(false, "", "", false, reason)

    private fun ccUrl(kind: String, version: String?): String = when (kind) {
        "by" -> "https://creativecommons.org/licenses/by/${version ?: "4.0"}/"
        else -> "https://creativecommons.org/publicdomain/zero/1.0/"
    }

    /** 위키미디어 공용의 LicenseShortName / License 문구 한 개를 판정한다. */
    fun judgeText(raw: String?): LicenseVerdict {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return deny("이용 조건 정보가 없습니다")
        val lower = s.lowercase().replace('‐', '-').replace('‑', '-').replace('–', '-').replace('—', '-')
        if (MULTI.containsMatchIn(lower)) return deny("이용 조건이 둘 이상 겹쳐 있습니다")
        val t = lower.split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        if (t.isEmpty()) return deny("이용 조건을 읽을 수 없습니다")
        if (t.any { it in DENY_TOKENS }) return deny("동일조건·비영리·변경금지 등 쓸 수 없는 조건입니다")
        // CC0 / CC Zero
        if (t[0] == "cc0" || (t[0] == "cc" && t.getOrNull(1) in setOf("zero", "0"))) {
            return LicenseVerdict(true, "CC0 1.0", ccUrl("zero", null), false, "CC0")
        }
        // CC BY [버전] [나라]
        if (t[0] == "cc" && t.getOrNull(1) == "by") {
            val rest = t.drop(2)
            val ver = rest.firstOrNull()?.takeIf { VERSION.matches(it) }
            val after = if (ver != null) rest.drop(1) else rest
            val port = after.firstOrNull()
            val portOk = after.isEmpty() || (after.size == 1 && port!!.length in 2..3 && port.all { it in 'a'..'z' })
            if (rest.isNotEmpty() && ver == null) return deny("알 수 없는 CC BY 표기입니다")
            if (!portOk) return deny("알 수 없는 CC BY 표기입니다")
            val label = "CC BY" + (ver?.let { " $it" } ?: "") + (port?.let { " ${it.uppercase()}" } ?: "")
            return LicenseVerdict(true, label, ccUrl("by", ver), true, "CC BY")
        }
        // 공개 도메인
        if ((t[0] == "public" && t.getOrNull(1) == "domain") || t[0] == "pd" || t[0] == "pdm") {
            return LicenseVerdict(true, "공개 도메인", "https://creativecommons.org/publicdomain/mark/1.0/", false, "공개 도메인")
        }
        return deny("허용 목록에 없는 이용 조건입니다")
    }

    /** 위키미디어 공용: 표시 이름과 분류 키가 둘 다 있으면 둘 다 허용이어야 통과한다(한쪽이라도 어긋나면 제외). */
    fun judgeCommons(shortName: String?, licenseKey: String?, licenseUrl: String? = null): LicenseVerdict {
        val a = shortName?.takeIf { it.isNotBlank() }?.let { judgeText(it) }
        val b = licenseKey?.takeIf { it.isNotBlank() }?.let { judgeText(it) }
        if (a == null && b == null) return deny("이용 조건 정보가 없습니다")
        if (a != null && !a.allowed) return a
        if (b != null && !b.allowed) return b
        if (a != null && b != null && a.reason != b.reason) return deny("이용 조건 표시가 서로 다릅니다")
        val v = a ?: b!!
        val url = licenseUrl?.takeIf { it.startsWith("https://") && it.contains("creativecommons.org") } ?: v.url
        return v.copy(url = url)
    }

    /** Openverse: license 코드("cc0","pdm","by",…)와 버전. */
    fun judgeOpenverse(code: String?, version: String?): LicenseVerdict {
        val c = code?.trim()?.lowercase().orEmpty()
        val v = version?.trim()?.takeIf { VERSION.matches(it) }
        return when (c) {
            "cc0" -> LicenseVerdict(true, "CC0 1.0", ccUrl("zero", null), false, "CC0")
            "pdm" -> LicenseVerdict(true, "공개 도메인", "https://creativecommons.org/publicdomain/mark/1.0/", false, "공개 도메인")
            "by" -> LicenseVerdict(true, "CC BY" + (v?.let { " $it" } ?: ""), ccUrl("by", v), true, "CC BY")
            "" -> deny("이용 조건 정보가 없습니다")
            else -> deny("허용 목록에 없는 이용 조건입니다($c)")
        }
    }
}

/** 위키미디어가 돌려주는 HTML 조각(작가·설명)에서 글자만 뽑는다. */
object HtmlText {
    private val TAG = Regex("""<[^>]*>""")
    private val BREAKS = Regex("""(?i)</?(br|p|div|li|tr)[^>]*>""")
    private val ENTITY = Regex("""&(#x?[0-9A-Fa-f]+|[A-Za-z]+);""")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—", "copy" to "©", "middot" to "·")

    fun text(html: String?, maxLen: Int = 100): String {
        if (html.isNullOrBlank()) return ""
        var s = BREAKS.replace(html, " ")
        s = TAG.replace(s, "")
        s = ENTITY.replace(s) { m ->
            val g = m.groupValues[1]
            when {
                g.startsWith("#x") || g.startsWith("#X") -> g.drop(2).toIntOrNull(16)?.let { cp(it) } ?: m.value
                g.startsWith("#") -> g.drop(1).toIntOrNull()?.let { cp(it) } ?: m.value
                else -> NAMED[g.lowercase()] ?: m.value
            }
        }
        s = s.replace(Regex("""\s+"""), " ").trim()
        return if (s.length > maxLen) s.take(maxLen - 1).trimEnd() + "…" else s
    }

    private fun cp(v: Int): String = if (v in 1..0x10FFFF && v !in 0xD800..0xDFFF) String(Character.toChars(v)) else ""
}
