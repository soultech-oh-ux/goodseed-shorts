package com.goodseed.shorts

import com.goodseed.shorts.data.*
import com.goodseed.shorts.logic.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * PC 에서 실제 API 로 새 출처 4곳(NASA · 메트로폴리탄 미술관 · Openverse 사진 · Openverse 음악)을 검색→파싱→받기 1회씩 실측한다.
 * 인터넷이 필요하므로 평소에는 건너뛴다. 실행: 환경변수 GS_LIVE=1 + `:app:testDebugUnitTest --tests "*FreeLive050Test*"`. 결과: _evidence/0.5.0/live-sources.txt
 * Openverse 는 서버가 자주 504 를 돌려주므로(실측) 실패해도 시험은 실패로 만들지 않고 그대로 기록한다.
 */
class FreeLive050Test {
    private val out = File("../../_evidence/0.5.0").also { it.mkdirs() }
    private val log = StringBuilder()
    private fun say(s: String) { println(s); log.appendLine(s) }

    private fun jpegSize(f: File): Pair<Int, Int> {
        val b = f.readBytes()
        require(b.size > 4 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8) { "JPEG 가 아닙니다" }
        var i = 2
        while (i + 9 < b.size) {
            val m = b[i + 1].toInt() and 0xFF; val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return (((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)) to (((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF))
            i += 2 + len
        }
        error("SOF 없음")
    }

    private fun <T> attempt(label: String, tries: Int, f: () -> T): Result<T> {
        var last: Result<T> = Result.failure(IllegalStateException("시도 안 함"))
        for (n in 1..tries) {
            val t0 = System.currentTimeMillis()
            last = runCatching(f)
            say("  [$label] 시도 $n/$tries → ${if (last.isSuccess) "성공" else "실패: " + last.exceptionOrNull()?.message} (${System.currentTimeMillis() - t0}ms)")
            if (last.isSuccess) break
            Thread.sleep(3000)
        }
        return last
    }

    @Test fun liveNewSources() {
        assumeTrue("GS_LIVE=1 일 때만 실제 인터넷으로 시험한다", System.getenv("GS_LIVE") == "1")
        val ua = freeUserAgent("0.5.0")
        val svc = FreeMediaService(HttpUrlGet(ua), head = HttpUrlHead(ua))
        val dl = HttpFileFetcher(ua)
        val tmp = java.nio.file.Files.createTempDirectory("gslive50").toFile()
        say("실측 시각: ${java.util.Date()}"); say("User-Agent: $ua"); say("")
        try {
            // 1) NASA 사진
            val plan = KoreanTopics.plan("일출"); say("[NASA 사진] '일출' → '${plan.english}'")
            val np = svc.searchNasa(plan.english, FreeKind.PHOTO)
            say("  ${np.items.size}건 (숨김 ${np.excluded.total}: 형식 ${np.excluded.format}·용량 ${np.excluded.size}·저작권표시 ${np.excluded.license}·로고/휘장 ${np.excluded.other}) 다음 쪽=${np.next}")
            assertTrue(np.items.isNotEmpty())
            for (m in np.items.take(4)) say("   - ${m.attribution.title} | ${m.attribution.author} | ${m.attribution.licenseLabel} | ${m.width}x${m.height} | ${SizeCaps.mb(m.bytes)}")
            val ph = np.items.filter { it.bytes in 1..400_000 }.minByOrNull { it.bytes } ?: np.items.minByOrNull { it.bytes }!!
            val pf = File(tmp, "nasa.jpg"); var pct = -1
            val r1 = dl.fetch(ph.downloadUrl, pf, SizeCaps.MAX_PHOTO_BYTES, { false }) { pct = it }
            say("  받기: ${ph.attribution.title} → $r1 · 진행률 $pct% · ${pf.length()}B")
            assertTrue(r1 is FetchResult.Ok); val (w, h) = jpegSize(pf); say("  JPEG 머리 해독 ${w}x${h} (목록 ${ph.width}x${ph.height})"); assertEquals(ph.width, w)

            // 2) NASA 영상
            say(""); say("[NASA 영상] '지구' → '${KoreanTopics.plan("지구").english}' (사전에 없으면 입력 그대로)")
            val nv = svc.searchNasa("earth", FreeKind.VIDEO)
            say("  ${nv.items.size}건 (숨김 ${nv.excluded.total})"); assertTrue(nv.items.isNotEmpty())
            val v = nv.items.first(); say("   - ${v.attribution.title} | ${v.attribution.author} | ${v.collectionUrl}")
            val urls = svc.nasaVideoUrls(v)
            val sizes = urls.map { u -> u.substringAfter('~') to HttpUrlHead(ua).contentLength(u) }
            say("  mp4 후보(화질 순, 크기가 100MB 이하인 것만): $sizes"); assertTrue(urls.isNotEmpty())
            val smallest = urls.last(); val vf = File(tmp, "nasa.mp4"); var vp = -1
            val r2 = dl.fetch(smallest, vf, SizeCaps.MAX_VIDEO_BYTES, { false }) { vp = it }
            val head = vf.inputStream().use { s -> ByteArray(12).also { s.read(it) } }
            say("  받기(가장 작은 후보 ${smallest.substringAfter('~')}) → $r2 · 진행률 $vp% · ${SizeCaps.mb(vf.length())} · 머리 12바이트 ${head.joinToString(" ") { "%02X".format(it) }} (mp4 는 4~7번째가 'ftyp' = 66 74 79 70)")
            assertTrue(r2 is FetchResult.Ok); assertEquals("ftyp", String(head, 4, 4, Charsets.US_ASCII))
            val over = dl.fetch(urls.first(), File(tmp, "cap.mp4"), 1000, { false }) { }
            say("  상한 시험: 가장 선명한 후보를 1000바이트 상한으로 받기 → $over · 남은 파일 ${File(tmp, "cap.mp4").exists()}"); assertTrue(over is FetchResult.TooBig)

            // 3) 메트로폴리탄 미술관
            say(""); say("[명화] '목자' → '${KoreanTopics.plan("목자").english}' (회화만, 공개 도메인·그림 있음)")
            val t3 = System.currentTimeMillis()
            val mp = svc.searchMet("shepherd", 0)
            say("  ${mp.items.size}건 · 상세 확인에서 뺀 작품 ${mp.excluded.other} · ${System.currentTimeMillis() - t3}ms · 다음 쪽 cursor=${mp.next}"); assertTrue(mp.items.isNotEmpty())
            for (m in mp.items.take(4)) say("   - ${m.attribution.title} | ${m.attribution.author} | ${m.attribution.licenseLabel} | ${m.attribution.sourceUrl}")
            val met = mp.items.first()
            val bigSize = HttpUrlHead(ua).contentLength(met.downloadUrl)
            say("  원본 그림 ${met.downloadUrl.substringAfterLast('/')} 크기(HEAD) ${SizeCaps.mb(bigSize)} / 대체(작은 그림) ${met.altUrls.firstOrNull()?.substringAfterLast('/')}")
            val mf = File(tmp, "met.jpg")
            val r3 = (listOf(met.downloadUrl) + met.altUrls).map { u -> dl.fetch(u, mf, SizeCaps.MAX_PHOTO_BYTES, { false }) { } }.first { it is FetchResult.Ok }
            val (mw, mh) = jpegSize(mf); say("  받기 → $r3 · ${SizeCaps.mb(mf.length())} · JPEG 해독 ${mw}x${mh}"); assertTrue(mf.length() in 1..SizeCaps.MAX_PHOTO_BYTES)
            val v11 = runCatching { HttpUrlGet(ua).get("https://collectionapi.metmuseum.org/public/collection/v1.1/objects/${met.id.removePrefix("met:")}") }
            say("  (참고) v1.1/objects/{id} → ${v11.exceptionOrNull()?.message ?: "응답 옴"} / v1/objects/{id} 는 위 상세 확인에 쓰여 성공")

            // 4) Openverse 사진 · 음악 (실패해도 기록만)
            say(""); say("[Openverse 사진] 'sunrise'")
            val ovp = attempt("사진 검색", 3) { svc.searchOpenverse("sunrise") }
            ovp.getOrNull()?.let { p ->
                say("  ${p.items.size}건 (숨김 ${p.excluded.total}) 첫 건: ${p.items.firstOrNull()?.let { "${it.attribution.title} | ${it.attribution.author} | ${it.attribution.licenseLabel}" }}")
                p.items.firstOrNull()?.let { m ->
                    val of = File(tmp, "ov.jpg"); val r = dl.fetch(m.downloadUrl, of, SizeCaps.MAX_PHOTO_BYTES, { false }) { }
                    say("  받기 → $r · ${of.length()}B" + if (r is FetchResult.Ok) " · JPEG ${runCatching { jpegSize(of) }.getOrNull()}" else "")
                }
            }
            say(""); say("[Openverse 음악] 'calm piano'")
            val ovm = attempt("음악 검색", 3) { svc.searchMusic("calm piano") }
            ovm.getOrNull()?.let { p ->
                say("  ${p.items.size}건 (숨김 ${p.excluded.total})")
                for (m in p.items.take(3)) say("   - ${m.attribution.title} | ${m.attribution.author} | ${m.attribution.licenseLabel} | ${m.durationText()} | ${SizeCaps.mb(m.bytes)} | ${m.ext}")
                val m = p.items.filter { it.bytes in 1..1_500_000 }.minByOrNull { it.bytes } ?: p.items.first()
                val af = File(tmp, "song.${m.ext}"); var ap = -1
                val r = dl.fetch(m.downloadUrl, af, SizeCaps.MAX_MUSIC_BYTES, { false }) { ap = it }
                val head = af.takeIf { it.isFile }?.inputStream()?.use { s -> ByteArray(4).also { s.read(it) } }
                say("  받기: ${m.attribution.title} → $r · 진행률 $ap% · ${af.length()}B · 머리 4바이트 ${head?.joinToString(" ") { "%02X".format(it) }} (mp3 는 49 44 33 'ID3' 또는 FF Fx)")
                assertTrue(r is FetchResult.Ok)
            }
            say(""); say("결론: NASA(사진·영상)·메트 미술관은 검색→파싱→받기 통과. Openverse 사진 ${if (ovp.isSuccess) "성공" else "실패(서버 응답 없음 — 앱은 조용히 건너뜀)"} · 음악 ${if (ovm.isSuccess) "성공" else "실패(서버 응답 없음 — 앱은 오류 안내를 보여 줌)"}")
        } finally {
            File(out, "live-sources.txt").writeText(log.toString(), Charsets.UTF_8)
            tmp.deleteRecursively()
        }
    }
}
