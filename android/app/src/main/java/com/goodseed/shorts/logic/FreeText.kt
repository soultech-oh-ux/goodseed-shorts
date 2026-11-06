package com.goodseed.shorts.logic

import java.io.InputStream
import java.io.OutputStream

/** 한국어 검색어를 영어 검색어로 바꾼다(위키미디어·Openverse 는 영어 설명이 대부분이다). */
object KoreanTopics {
    /** 자주 쓰는 주제 칩 — 화면 순서 그대로. */
    val CHIPS: List<Pair<String, String>> = listOf(
        "일출" to "sunrise", "노을" to "sunset", "바다" to "sea", "하늘" to "sky",
        "구름" to "clouds", "숲" to "forest", "꽃" to "flowers", "별" to "stars night sky",
        "빛" to "sunbeams light rays", "십자가" to "cross", "교회" to "church", "산" to "mountain",
        "강" to "river", "비" to "rain", "눈" to "snow", "들판" to "meadow",
    )

    /** 명화(메트 미술관) 탭의 주제 버튼. */
    val MET_CHIPS: List<Pair<String, String>> = listOf(
        "목자" to "shepherd", "십자가" to "cross", "기도" to "prayer", "천사" to "angel", "성경" to "bible", "풍경" to "landscape",
    )

    /** 무료 음악 찾기의 주제 버튼(화면에 보이는 이름 → 영어 검색어). */
    val MUSIC_CHIPS: List<Pair<String, String>> = listOf(
        "잔잔한 피아노" to "calm piano", "기타" to "acoustic guitar", "현악" to "strings", "자연 소리" to "nature sounds",
        "찬양 분위기" to "worship", "명상" to "meditation",
    )

    private val EXTRA: Map<String, String> = mapOf(
        "해" to "sun", "태양" to "sun", "달" to "moon", "새벽" to "dawn", "아침" to "morning", "저녁" to "evening", "밤" to "night",
        "호수" to "lake", "물" to "water", "폭포" to "waterfall", "시냇물" to "stream", "해변" to "beach", "바닷가" to "beach", "파도" to "waves",
        "섬" to "island", "안개" to "fog", "무지개" to "rainbow", "바람" to "wind", "폭풍" to "storm", "번개" to "lightning", "천둥" to "thunderstorm",
        "나무" to "tree", "잎" to "leaves", "단풍" to "autumn leaves", "가을" to "autumn", "봄" to "spring", "여름" to "summer", "겨울" to "winter",
        "풀" to "grass", "풀밭" to "meadow", "초원" to "meadow", "정원" to "garden", "벚꽃" to "cherry blossom", "장미" to "rose", "백합" to "lily",
        "씨앗" to "seed", "새싹" to "sprout", "열매" to "fruit", "포도" to "grapes", "밀" to "wheat", "보리" to "barley", "올리브" to "olive",
        "새" to "bird", "비둘기" to "dove", "독수리" to "eagle", "양" to "sheep", "목자" to "shepherd", "물고기" to "fish", "사자" to "lion",
        "광야" to "desert", "사막" to "desert", "언덕" to "hills", "골짜기" to "valley", "길" to "path", "다리" to "bridge", "문" to "door", "계단" to "stairs",
        "등대" to "lighthouse", "배" to "boat", "촛불" to "candle", "불" to "fire", "불꽃" to "flame", "별빛" to "starry sky", "은하수" to "milky way",
        "성경" to "bible", "지구" to "earth", "우주" to "space", "행성" to "planet", "은하" to "galaxy", "성운" to "nebula", "화성" to "mars", "목성" to "jupiter", "토성" to "saturn", "로켓" to "rocket", "우주인" to "astronaut", "소리" to "sound", "천사" to "angel", "피아노" to "piano", "잔잔한" to "calm", "기타" to "guitar", "현악" to "strings", "찬양" to "worship", "명상" to "meditation", "음악" to "music", "연주" to "instrumental", "오르간" to "organ", "바이올린" to "violin", "첼로" to "cello", "하프" to "harp", "책" to "book", "기도" to "prayer", "손" to "hands", "아이" to "child", "가족" to "family", "예배" to "worship",
        "평화" to "peaceful nature", "고요" to "calm", "자연" to "nature", "풍경" to "landscape", "도시" to "city", "집" to "house",
        "눈송이" to "snowflakes", "빗방울" to "raindrops", "이슬" to "dew", "서리" to "frost", "얼음" to "ice", "시간" to "time lapse", "타임랩스" to "time lapse",
    )

    private val ALL: Map<String, String> = CHIPS.toMap() + EXTRA

    /** 낱말 끝에 붙는 조사(길이가 긴 것부터 시험한다). */
    private val PARTICLES = listOf("에서", "으로", "에게", "처럼", "까지", "부터", "이다", "의", "이", "가", "은", "는", "을", "를", "에", "로", "와", "과", "도", "만")

    data class Plan(
        val english: String,           // 실제로 보낼 검색어
        val unknown: List<String>,     // 영어로 못 바꾼 한글 낱말(없으면 빈 목록)
        val rawKorean: Boolean,        // 하나도 못 바꿔서 입력한 한글 그대로 보냈는가
    )

    fun plan(input: String): Plan {
        val tokens = input.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val eng = ArrayList<String>(); val unknown = ArrayList<String>()
        for (t in tokens) {
            if (t.none { it in '가'..'힣' }) { eng += t; continue }     // 영어·숫자는 그대로
            val hit = lookup(t)
            if (hit != null) eng += hit else unknown += t
        }
        val joined = eng.joinToString(" ").trim()
        if (joined.isNotEmpty()) return Plan(joined, unknown, false)
        // 하나도 못 바꿨다 — 위키미디어에는 한글 설명이 있는 자료도 있으니 입력한 그대로 보낸다
        return Plan(input.trim(), unknown, input.isNotBlank())
    }

    private fun lookup(word: String): String? {
        ALL[word]?.let { return it }
        for (p in PARTICLES) if (word.endsWith(p) && word.length > p.length) ALL[word.dropLast(p.length)]?.let { return it }
        return null
    }

    /** 사전에 있는 낱말만 영어로 바꾸고, 모르는 한글 낱말이 있으면 알려 줄 문구를 만든다(없으면 ""). */
    fun notice(plan: Plan): String = when {
        plan.rawKorean -> "한글 낱말을 영어로 바꾸지 못해 입력한 그대로 찾았습니다. 결과가 적으면 아래 주제 버튼을 눌러 보세요."
        plan.unknown.isNotEmpty() -> "'${plan.unknown.joinToString(", ")}'은(는) 영어로 바꾸지 못해 빼고 찾았습니다."
        else -> ""
    }
}

/** 영상 끝 글자와 유튜브 설명란에 넣는 출처 문구. */
object CreditText {
    /** 같은 자료(원본 주소가 같은 것)는 한 번만 센다. */
    fun unique(list: List<Attribution>): List<Attribution> = list.distinctBy { it.sourceUrl.ifBlank { it.title + "|" + it.author } }

    private fun cut(s: String, n: Int) = if (s.length > n) s.take(n - 1).trimEnd() + "…" else s

    /** 영상 안에 작게 넣는 한두 줄. 예: "자료: 홍길동(CC BY 4.0) · Jane(CC0) 외 2건 · 위키미디어 공용". */
    fun onScreen(list: List<Attribution>, maxNames: Int = 3): String {
        val u = unique(list)
        if (u.isEmpty()) return ""
        val shown = u.take(maxNames).joinToString(" · ") { "${cut(it.author, 22)}(${it.licenseLabel})" }
        val more = if (u.size > maxNames) " 외 ${u.size - maxNames}건" else ""
        val providers = u.map { it.provider.label }.distinct().joinToString("·")
        return "자료: $shown$more · $providers"
    }

    /** 유튜브 설명란에 붙여 넣는 문구(자료마다 제목·작가·이용 조건·원본 링크). */
    fun forDescription(list: List<Attribution>): String {
        val u = unique(list)
        if (u.isEmpty()) return ""
        val sb = StringBuilder("[사진·영상 출처]\n")
        u.forEachIndexed { i, a ->
            sb.append("${i + 1}. \"${a.title}\" — ${a.author} / ${a.licenseLabel}")
            if (a.licenseUrl.isNotBlank()) sb.append(" (${a.licenseUrl})")
            if (a.sourceUrl.isNotBlank()) sb.append("\n   원본: ${a.sourceUrl}")
            sb.append("\n")
        }
        val providers = u.map { it.provider }.distinct()
        sb.append("자료 제공: ").append(providers.joinToString(", ") { it.label })
        providers.mapNotNull { it.creditLine }.forEach { sb.append("\n").append(it) }
        return sb.toString()
    }

    /** 출처 표기가 의무인 자료가 하나라도 있는가(CC BY). */
    fun anyRequired(list: List<Attribution>): Boolean = list.any { it.requiresCredit }
}

/** 받은 파일의 Content-Type 이 사진·영상·음악일 때만 받는다(오류 페이지(html)·json·xml 을 파일로 저장하지 않도록). */
object ContentTypes {
    fun acceptable(contentType: String?): Boolean {
        val t = contentType.orEmpty().substringBefore(';').trim().lowercase()
        if (t.isEmpty()) return true            // 서버가 알려 주지 않으면 받은 뒤 파일을 직접 읽어 확인한다
        return t.startsWith("image/") || t.startsWith("video/") || t.startsWith("audio/") ||
            t == "application/octet-stream" || t == "binary/octet-stream" || t == "application/ogg"
    }
}

/** 받는 중 크기 상한을 지키며 복사한다. 상한을 넘으면 즉시 멈춘다(받은 양을 알려 준다). */
object BoundedCopy {
    sealed class Result {
        data class Done(val bytes: Long) : Result()
        data class TooBig(val bytes: Long) : Result()
        object Cancelled : Result()
    }

    fun copy(input: InputStream, output: OutputStream, maxBytes: Long, isCancelled: () -> Boolean = { false }, onBytes: (Long) -> Unit = {}): Result {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (isCancelled()) return Result.Cancelled
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > maxBytes) return Result.TooBig(total)
            output.write(buf, 0, n)
            onBytes(total)
        }
        return Result.Done(total)
    }

    /** 서버가 알려 준 크기(Content-Length)로 미리 거른다. 모르면(-1) 통과. */
    fun precheck(contentLength: Long, maxBytes: Long): Boolean = contentLength < 0 || contentLength <= maxBytes
}

/** 찾기 결과 아래에 보여 주는 안내 문구. */
object FreeMessages {
    /** notice=한글→영어 변환 안내, count=보여 줄 결과 수, hidden=숨긴 건수, error=위키미디어 실패 문구, waiting=다른 출처를 더 기다리는 중. */
    fun search(notice: String, count: Int, hidden: Int, error: String?, waiting: Boolean): String {
        val parts = ArrayList<String>()
        if (notice.isNotBlank()) parts += notice
        when {
            count == 0 && waiting -> {}
            count == 0 -> parts += (error ?: "결과가 없습니다. 다른 낱말을 쓰거나 위의 주제 버튼을 눌러 보세요.")
            hidden > 0 -> parts += "이용 조건이 맞지 않는 자료 ${hidden}건은 숨겼습니다."
        }
        return parts.joinToString("\n")
    }
}
