package com.goodseed.shorts

import com.goodseed.shorts.data.*
import com.goodseed.shorts.logic.*
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * PC 에서 실제 위키미디어·Openverse 로 검색→파싱→받기를 1회 실측한다(인터넷이 필요하므로 평소에는 건너뛴다).
 * 실행: 환경변수 GS_LIVE=1 로 `:app:testDebugUnitTest --tests "*FreeLiveTest*"`. 결과: _evidence/0.4.0/live-wikimedia.txt
 */
class FreeLiveTest {
    private val out = File("../../_evidence/0.4.0").also { it.mkdirs() }
    private val log = StringBuilder()
    private fun say(s: String) { println(s); log.appendLine(s) }

    /** JPEG 머리의 SOF 표지에서 가로·세로를 읽는다(안드로이드 시험 환경에는 ImageIO 가 없다). */
    private fun jpegSize(f: File): Pair<Int, Int> {
        val b = f.readBytes()
        require(b.size > 4 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8) { "JPEG 가 아닙니다" }
        var i = 2
        while (i + 9 < b.size) {
            require((b[i].toInt() and 0xFF) == 0xFF) { "JPEG 표지 위치 오류" }
            val m = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return w to h
            }
            i += 2 + len
        }
        error("SOF 표지를 찾지 못했습니다")
    }

    @Test fun liveWikimediaSearchParseDownload() {
        assumeTrue("GS_LIVE=1 일 때만 실제 인터넷으로 시험한다", System.getenv("GS_LIVE") == "1")
        val ua = freeUserAgent("0.4.0")
        val svc = FreeMediaService(HttpUrlGet(ua))
        val dl = HttpFileFetcher(ua)
        val tmp = java.nio.file.Files.createTempDirectory("gslive").toFile()
        say("실측 시각: ${java.util.Date()}"); say("User-Agent: $ua"); say("")
        try {
            // 1) 사진: 한글 '일출' → sunrise
            val plan = KoreanTopics.plan("일출"); say("[사진] 검색어 '일출' → '${plan.english}'")
            val t0 = System.currentTimeMillis()
            val photos = svc.searchWikimedia(plan.english, FreeKind.PHOTO)
            say("  위키미디어 사진 ${photos.items.size}건 (숨김 ${photos.excluded.total}: 이용조건 ${photos.excluded.license}·형식 ${photos.excluded.format}·용량 ${photos.excluded.size}) · ${System.currentTimeMillis() - t0}ms · 다음 쪽=${photos.next}")
            assertTrue("실제 사진 결과가 있어야 한다", photos.items.isNotEmpty())
            for (m in photos.items.take(5)) say("   - ${m.attribution.title} | ${m.attribution.author} | ${m.attribution.licenseLabel} | ${m.width}x${m.height} | ${SizeCaps.mb(m.bytes)} | ${if (m.downloadUrl.isBlank()) "줄인 그림 받기" else "원본 받기"}")
            assertTrue(photos.items.all { it.attribution.licenseLabel.startsWith("CC BY") && !it.attribution.licenseLabel.contains("SA") || it.attribution.licenseLabel == "CC0 1.0" || it.attribution.licenseLabel == "공개 도메인" })

            // 2) 사진 받기: 원본이 상한 안에 있는 가장 작은 것
            val direct = photos.items.filter { it.downloadUrl.isNotBlank() }.minByOrNull { it.bytes }!!
            val pf = File(tmp, "photo.jpg")
            val t1 = System.currentTimeMillis(); var lastPct = -1
            val r1 = dl.fetch(direct.downloadUrl, pf, SizeCaps.MAX_PHOTO_BYTES, { false }) { lastPct = it }
            say("[사진 받기] ${direct.attribution.title} → $r1 · 진행률 마지막=$lastPct% · ${System.currentTimeMillis() - t1}ms · 파일 ${pf.length()}B")
            assertTrue(r1 is FetchResult.Ok); assertTrue(pf.length() in 1..SizeCaps.MAX_PHOTO_BYTES)
            val (iw, ih) = jpegSize(pf)
            say("  JPEG 머리 해독: ${iw}x${ih} (목록의 ${direct.width}x${direct.height})"); assertEquals(direct.width, iw); assertEquals(direct.height, ih)

            // 3) 용량 상한을 넘는 원본 → 줄인 그림 주소를 묻고 받기
            val big = svc.searchWikimedia("sunrise", FreeKind.PHOTO).items.firstOrNull { it.resizeTitle.isNotEmpty() }
            if (big != null) {
                val urls = svc.resizedPhotoUrls(big)
                say("[큰 사진] ${big.attribution.title} 원본 ${big.width}x${big.height} → 줄인 그림 주소 ${urls.size}개: ${urls.firstOrNull()}")
                assertTrue(urls.isNotEmpty())
                val rf = File(tmp, "resized.jpg")
                val rr = dl.fetch(urls.first(), rf, SizeCaps.MAX_PHOTO_BYTES, { false }) { }
                say("  받기 → $rr · ${rf.length()}B"); assertTrue(rr is FetchResult.Ok)
                val (rw, rh) = jpegSize(rf); say("  JPEG 머리 해독: ${rw}x${rh}"); assertTrue(maxOf(rw, rh) >= 1000)
            } else say("[큰 사진] 이번 결과에는 용량 상한을 넘는 원본이 없었다")

            // 4) 영상
            val vplan = KoreanTopics.plan("바다"); say(""); say("[영상] 검색어 '바다' → '${vplan.english}'")
            val t2 = System.currentTimeMillis()
            val videos = svc.searchWikimedia(vplan.english, FreeKind.VIDEO)
            say("  위키미디어 영상 ${videos.items.size}건 (숨김 ${videos.excluded.total}: 이용조건 ${videos.excluded.license}·형식 ${videos.excluded.format}·용량 ${videos.excluded.size}) · ${System.currentTimeMillis() - t2}ms")
            assertTrue(videos.items.isNotEmpty())
            for (m in videos.items.take(5)) say("   - ${m.attribution.title} | ${m.attribution.author} | ${m.attribution.licenseLabel} | ${m.durationSec}초 ${m.width}x${m.height} | 약 ${SizeCaps.mb(m.bytes)} | ${m.mime}")
            val v = videos.items.filter { it.bytes in 1..15_000_000 }.minByOrNull { it.bytes } ?: videos.items.minByOrNull { it.bytes }!!
            val vf = File(tmp, "video.webm")
            val t3 = System.currentTimeMillis(); var vp = -1
            val r2 = dl.fetch(v.downloadUrl, vf, SizeCaps.MAX_VIDEO_BYTES, { false }) { vp = it }
            say("[영상 받기] ${v.attribution.title} (${v.durationSec}초, 목록상 약 ${SizeCaps.mb(v.bytes)}) → $r2 · 진행률 마지막=$vp% · ${System.currentTimeMillis() - t3}ms · 파일 ${vf.length()}B(${SizeCaps.mb(vf.length())})")
            assertTrue(r2 is FetchResult.Ok); assertTrue(vf.length() in 1..SizeCaps.MAX_VIDEO_BYTES)
            val head = vf.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            say("  파일 머리 4바이트 = ${head.joinToString(" ") { "%02X".format(it) }} (webm/Matroska 는 1A 45 DF A3)")
            assertArrayEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), head)

            // 5) 크기 상한: 같은 영상을 1MB 상한으로 받으면 중단되고 파일이 남지 않는다
            val cf = File(tmp, "capped.webm")
            val r3 = dl.fetch(v.downloadUrl, cf, 1000, { false }) { }
            say("[상한 시험] 1000바이트 상한으로 같은 영상 받기 → $r3 · 남은 파일 존재=${cf.exists()} · .part 존재=${File(tmp, "capped.webm.part").exists()}")
            assertTrue(r3 is FetchResult.TooBig); assertFalse(cf.exists()); assertFalse(File(tmp, "capped.webm.part").exists())

            // 6) Openverse (보조 · 실패해도 정상)
            say(""); say("[Openverse] 사진 'sunrise'")
            val t4 = System.currentTimeMillis()
            val ov = runCatching { svc.searchOpenverse("sunrise") }
            ov.onSuccess { p -> say("  성공 ${p.items.size}건 (숨김 ${p.excluded.total}) · ${System.currentTimeMillis() - t4}ms · 첫 건: ${p.items.firstOrNull()?.let { "${it.attribution.title} | ${it.attribution.author} | ${it.attribution.licenseLabel}" }}") }
            ov.onFailure { e -> say("  실패(앱은 조용히 위키미디어만 보여 준다): ${e.message} · ${System.currentTimeMillis() - t4}ms") }
            ov.getOrNull()?.items?.firstOrNull { it.downloadUrl.startsWith("https://") }?.let { m ->
                val of = File(tmp, "ov.jpg")
                val r4 = dl.fetch(m.downloadUrl, of, SizeCaps.MAX_PHOTO_BYTES, { false }) { }
                say("  Openverse 사진 받기 → $r4 · ${of.length()}B")
            }
            say(""); say("결론: 위키미디어 사진·영상 검색→파싱→받기 실측 통과")
        } finally {
            File(out, "live-wikimedia.txt").writeText(log.toString(), Charsets.UTF_8)
            tmp.deleteRecursively()
        }
    }
}
