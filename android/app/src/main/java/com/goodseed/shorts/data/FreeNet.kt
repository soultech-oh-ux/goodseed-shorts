package com.goodseed.shorts.data

import com.goodseed.shorts.logic.BoundedCopy
import com.goodseed.shorts.logic.Excluded
import com.goodseed.shorts.logic.FreeKind
import com.goodseed.shorts.logic.FreeMedia
import com.goodseed.shorts.logic.FreePage
import com.goodseed.shorts.logic.FreeUrls
import com.goodseed.shorts.logic.OpenverseParser
import com.goodseed.shorts.logic.SizeCaps
import com.goodseed.shorts.logic.WikimediaParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** 요청 실패의 종류(화면에 보여 줄 한국어 문구는 message). */
class FreeError(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { OFFLINE, TIMEOUT, SERVER, RATE_LIMIT, BAD }
    val retryable: Boolean get() = kind == Kind.SERVER || kind == Kind.RATE_LIMIT || kind == Kind.TIMEOUT
}

/** 글자(JSON) 가져오기. 시험에서는 가짜로 바꿔 끼운다. */
fun interface HttpGet { fun get(url: String): String }

/** 실제 인터넷 요청. https 만 허용하고, 위키미디어 규칙대로 앱 이름·연락처가 든 User-Agent 를 보낸다. */
class HttpUrlGet(private val userAgent: String, private val connectMs: Int = 10_000, private val readMs: Int = 20_000) : HttpGet {
    override fun get(url: String): String {
        if (!url.startsWith("https://")) throw FreeError(FreeError.Kind.BAD, "안전하지 않은 주소입니다.")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"; conn.connectTimeout = connectMs; conn.readTimeout = readMs
        conn.setRequestProperty("User-Agent", userAgent); conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            when {
                code == 429 -> throw FreeError(FreeError.Kind.RATE_LIMIT, "요청이 많아 잠시 막혔습니다. 조금 뒤에 다시 해 주세요.")
                code in 500..599 -> throw FreeError(FreeError.Kind.SERVER, "자료 서버가 지금 응답하지 못합니다(코드 $code).")
                code !in 200..299 -> throw FreeError(FreeError.Kind.BAD, "검색에 실패했습니다(코드 $code).")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: FreeError) { throw e
        } catch (e: SocketTimeoutException) { throw FreeError(FreeError.Kind.TIMEOUT, "응답이 너무 늦습니다.")
        } catch (e: IOException) { throw FreeError(FreeError.Kind.OFFLINE, "인터넷에 연결되지 않았거나 응답이 없습니다.")
        } finally { conn.disconnect() }
    }
}

/**
 * 키가 필요 없는 무료 자료 찾기.
 *  - 위키미디어 공용: 사진·영상. 서버·연결 문제는 한 번 더 시도한다.
 *  - Openverse: 사진 보조. 자주 실패하므로 한 번만 시도하고, 실패는 호출한 쪽이 조용히 넘긴다.
 */
class FreeMediaService(private val http: HttpGet, private val pause: (Long) -> Unit = { Thread.sleep(it) }) {
    private fun <T> withRetry(block: () -> T): T {
        try { return block() } catch (e: FreeError) {
            if (!e.retryable) throw e
            pause(1500)
            return block()
        }
    }

    fun searchWikimedia(terms: String, kind: FreeKind, offset: Int = 0): FreePage =
        withRetry { WikimediaParser.parse(http.get(FreeUrls.wikimediaSearch(terms, kind, offset)), kind) }

    fun searchOpenverse(terms: String): FreePage = OpenverseParser.parse(http.get(FreeUrls.openverse(terms)))

    /** 용량 상한을 넘는 위키미디어 사진: 줄인 그림 주소를 묻는다(원본 폭이 3840 이상이면 3840, 아니면 1920 폭). */
    fun resizedPhotoUrls(m: FreeMedia): List<String> {
        val widths = if (m.width >= 3840) listOf(3840, 1920) else listOf(1920)
        return widths.mapNotNull { w ->
            runCatching { withRetry { WikimediaParser.thumbUrlFrom(http.get(FreeUrls.wikimediaThumb(m.resizeTitle, w))) } }.getOrNull()
        }
    }
}

/** 파일 받기 결과. */
sealed class FetchResult {
    data class Ok(val bytes: Long) : FetchResult()
    object TooBig : FetchResult()
    object Cancelled : FetchResult()
    data class Failed(val message: String) : FetchResult()
}

fun interface FileFetcher { fun fetch(url: String, dest: File, maxBytes: Long, isCancelled: () -> Boolean, onProgress: (Int) -> Unit): FetchResult }

/** 파일을 받는다. 크기 상한을 지키고(미리 Content-Length 로, 받는 중에도), 진행률·취소를 지원하며, 실패하면 반쯤 받은 파일을 지운다. */
class HttpFileFetcher(private val userAgent: String) : FileFetcher {
    override fun fetch(url: String, dest: File, maxBytes: Long, isCancelled: () -> Boolean, onProgress: (Int) -> Unit): FetchResult {
        if (!url.startsWith("https://")) return FetchResult.Failed("안전하지 않은 주소라 받지 않았습니다.")
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return FetchResult.Failed(if (code == 429) "요청이 많아 잠시 막혔습니다. 조금 뒤에 다시 해 주세요." else "내려받지 못했습니다(코드 $code).")
            val type = conn.contentType.orEmpty().lowercase()
            if (!(type.startsWith("image/") || type.startsWith("video/") || type.startsWith("application/octet-stream") || type.isEmpty())) {
                return FetchResult.Failed("받은 파일이 사진·영상이 아닙니다.")
            }
            val total = conn.contentLengthLong
            if (!BoundedCopy.precheck(total, maxBytes)) return FetchResult.TooBig
            var last = -1
            val r = conn.inputStream.use { inp -> part.outputStream().use { out ->
                BoundedCopy.copy(inp, out, maxBytes, isCancelled) { done ->
                    if (total > 0) { val p = (done * 100 / total).toInt().coerceIn(0, 100); if (p != last) { last = p; onProgress(p) } }
                }
            } }
            return when (r) {
                is BoundedCopy.Result.Done -> {
                    if (r.bytes <= 0) { part.delete(); FetchResult.Failed("빈 파일이 내려왔습니다.") }
                    else { dest.delete(); if (part.renameTo(dest)) FetchResult.Ok(r.bytes) else { part.delete(); FetchResult.Failed("파일을 저장하지 못했습니다.") } }
                }
                is BoundedCopy.Result.TooBig -> { part.delete(); FetchResult.TooBig }
                BoundedCopy.Result.Cancelled -> { part.delete(); FetchResult.Cancelled }
            }
        } catch (e: SocketTimeoutException) { part.delete(); return FetchResult.Failed("응답이 너무 늦어 받지 못했습니다.")
        } catch (e: IOException) { part.delete(); return FetchResult.Failed("내려받는 중 인터넷이 끊겼습니다.")
        } finally { conn.disconnect() }
    }
}

/** 요청에 붙이는 앱 식별 문구(위키미디어는 앱 이름·연락처가 든 User-Agent 를 요구한다). 개인 이메일은 넣지 않는다. */
fun freeUserAgent(version: String): String = "GoodseedShorts/$version (+https://github.com/soultech-oh-ux/goodseed-shorts/releases)"

/** 카드에 보여 줄 크기 문구. */
fun FreeMedia.sizeText(): String = if (bytes > 0) SizeCaps.mb(bytes) else ""

/** 의도치 않은 Excluded 없이 쓰는 빈 페이지. */
val EMPTY_PAGE = FreePage(emptyList(), null, Excluded())
