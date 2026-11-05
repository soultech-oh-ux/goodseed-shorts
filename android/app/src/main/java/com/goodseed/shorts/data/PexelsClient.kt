package com.goodseed.shorts.data

import com.goodseed.shorts.logic.PexelsParser
import com.goodseed.shorts.logic.PexelsVideo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Pexels 무료 영상 검색(사용자가 직접 입력한 API 키로만 동작 — 앱에는 키가 들어 있지 않다).
 * 요금이 드는 호출은 없다(Pexels API 는 무료). 응답 코드별 한국어 안내를 돌려준다.
 */
class PexelsClient(private val apiKey: String) {
    class PexelsError(message: String) : Exception(message)

    fun search(query: String, perPage: Int = 15): List<PexelsVideo> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val conn = (URL("https://api.pexels.com/videos/search?query=$q&orientation=portrait&size=medium&per_page=$perPage").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Authorization", apiKey.trim())
        }
        try {
            val code = conn.responseCode
            if (code == 401 || code == 403) throw PexelsError("API 키가 맞지 않습니다. 키를 다시 확인해 주세요.")
            if (code == 429) throw PexelsError("검색 횟수 한도에 도달했습니다. 잠시 뒤 다시 해 주세요.")
            if (code !in 200..299) throw PexelsError("검색에 실패했습니다(코드 $code).")
            return PexelsParser.parse(conn.inputStream.bufferedReader().readText())
        } catch (e: PexelsError) { throw e
        } catch (e: java.io.IOException) { throw PexelsError("인터넷에 연결되지 않았거나 응답이 없습니다.")
        } finally { conn.disconnect() }
    }

    fun download(v: PexelsVideo, dest: File, onProgress: (Int) -> Unit = {}) {
        dest.parentFile?.mkdirs()
        val conn = (URL(v.fileUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 30_000 }
        try {
            if (conn.responseCode !in 200..299) throw PexelsError("영상을 내려받지 못했습니다(코드 ${conn.responseCode}).")
            val total = conn.contentLengthLong
            conn.inputStream.use { inp -> dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) { val n = inp.read(buf); if (n <= 0) break; out.write(buf, 0, n); done += n; if (total > 0) onProgress((done * 100 / total).toInt()) }
            } }
        } catch (e: PexelsError) { dest.delete(); throw e
        } catch (e: java.io.IOException) { dest.delete(); throw PexelsError("내려받는 중 인터넷이 끊겼습니다.")
        } finally { conn.disconnect() }
    }
}
