package com.goodseed.shorts

import com.goodseed.shorts.data.*
import com.goodseed.shorts.logic.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private fun fixture(name: String): String =
    FreeMediaTest::class.java.classLoader!!.getResourceAsStream("free/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

/** 0.4.0 무료 사진·영상 찾기 — 순수 로직 시험(이용 조건 판정표·파서·한영 사전·출처 문구·크기 상한). 픽스처는 2026-11-06 실제 API 응답이다. */
class FreeMediaTest {

    // ---------- 이용 조건 판정표 ----------
    @Test fun licenseTable_allowed() {
        val allowed = mapOf(
            "CC0" to "CC0 1.0", "CC0 1.0" to "CC0 1.0", "cc-zero" to "CC0 1.0",
            "CC BY 4.0" to "CC BY 4.0", "CC BY 3.0" to "CC BY 3.0", "CC BY 2.5" to "CC BY 2.5", "CC BY 2.0" to "CC BY 2.0", "CC BY 1.0" to "CC BY 1.0",
            "cc-by-4.0" to "CC BY 4.0", "CC BY" to "CC BY", "CC BY 3.0 de" to "CC BY 3.0 DE", "CC BY 2.0 fr" to "CC BY 2.0 FR", "CC BY 3.0 IGO" to "CC BY 3.0 IGO",
            "Public domain" to "공개 도메인", "public domain" to "공개 도메인", "PD" to "공개 도메인", "PD-US" to "공개 도메인", "pd-old-100" to "공개 도메인", "Public Domain Mark" to "공개 도메인",
        )
        for ((raw, label) in allowed) {
            val v = LicenseJudge.judgeText(raw)
            assertTrue("허용이어야 함: $raw (${v.reason})", v.allowed)
            assertEquals(raw, label, v.label)
        }
    }

    @Test fun licenseTable_denied() {
        val denied = listOf(
            "CC BY-SA 4.0", "CC BY-SA 3.0", "CC BY-SA 3.0 us", "cc-by-sa-4.0", "CC BY-NC 4.0", "CC BY-NC-SA 3.0", "CC BY-ND 4.0", "CC BY-NC-ND 2.0", "CC-BY-NC",
            "GFDL", "GFDL 1.2", "GPL", "FAL", "OGL 3.0", "Copyrighted free use", "All rights reserved", "", "   ", "Unknown", "Attribution", "Free art license",
            "CC BY 3.0 / CC BY-SA 3.0", "CC BY 4.0, CC BY-SA 4.0", "CC BY 3.0 or GFDL", "CC BY 4.0 and CC0", "cc-by-3.0,gfdl", "CC BY 4.0+", "CC BY foo", "CC BY 4.0 abcd", "CC BY x.y",
            "Sampling+", "nc-sampling+", "cc by-sa",
        )
        for (raw in denied) assertFalse("제외여야 함: '$raw'", LicenseJudge.judgeText(raw).allowed)
        assertFalse(LicenseJudge.judgeText(null).allowed)
    }

    @Test fun licenseUrlsAndCreditFlags() {
        assertTrue(LicenseJudge.judgeText("CC BY 4.0").requiresCredit)
        assertEquals("https://creativecommons.org/licenses/by/4.0/", LicenseJudge.judgeText("CC BY 4.0").url)
        assertFalse(LicenseJudge.judgeText("CC0").requiresCredit)
        assertFalse(LicenseJudge.judgeText("Public domain").requiresCredit)
        assertEquals("https://creativecommons.org/publicdomain/zero/1.0/", LicenseJudge.judgeText("CC0").url)
    }

    @Test fun commonsJudgeNeedsBothLabelsToAgree() {
        assertTrue(LicenseJudge.judgeCommons("CC BY 4.0", "cc-by-4.0", "https://creativecommons.org/licenses/by/4.0").allowed)
        assertEquals("https://creativecommons.org/licenses/by/4.0", LicenseJudge.judgeCommons("CC BY 4.0", "cc-by-4.0", "https://creativecommons.org/licenses/by/4.0").url)
        assertTrue(LicenseJudge.judgeCommons("CC0", "cc0", null).allowed)
        assertTrue(LicenseJudge.judgeCommons("Public domain", "pd", null).allowed)
        assertTrue("표시 이름이 비어도 분류 키가 허용이면 통과", LicenseJudge.judgeCommons("", "cc-by-4.0", null).allowed)
        assertFalse("한쪽이라도 BY-SA 면 제외", LicenseJudge.judgeCommons("CC BY 4.0", "cc-by-sa-4.0", null).allowed)
        assertFalse(LicenseJudge.judgeCommons("CC BY-SA 4.0", "cc-by-4.0", null).allowed)
        assertFalse("서로 다른 허용 조건도 제외(확신할 수 없음)", LicenseJudge.judgeCommons("CC0", "cc-by-4.0", null).allowed)
        assertFalse(LicenseJudge.judgeCommons(null, null, null).allowed)
        assertFalse(LicenseJudge.judgeCommons("", "", "https://creativecommons.org/licenses/by/4.0").allowed)
        val bad = LicenseJudge.judgeCommons("CC BY 4.0", null, "http://evil.example/license")
        assertTrue(bad.allowed); assertTrue("엉뚱한 주소는 쓰지 않는다", bad.url.startsWith("https://creativecommons.org/"))
    }

    @Test fun openverseCodes() {
        assertEquals("CC0 1.0", LicenseJudge.judgeOpenverse("cc0", "1.0").label)
        assertEquals("공개 도메인", LicenseJudge.judgeOpenverse("pdm", "1.0").label)
        assertEquals("CC BY 2.0", LicenseJudge.judgeOpenverse("by", "2.0").label)
        assertEquals("CC BY", LicenseJudge.judgeOpenverse("by", null).label)
        for (c in listOf("by-sa", "by-nc", "by-nd", "by-nc-sa", "by-nc-nd", "sampling+", "nc-sampling+", "", "weird")) assertFalse(c, LicenseJudge.judgeOpenverse(c, "2.0").allowed)
        assertFalse(LicenseJudge.judgeOpenverse(null, null).allowed)
    }

    // ---------- HTML 정리 ----------
    @Test fun htmlText() {
        assertEquals("Jagriti Zinta", HtmlText.text("<a href=\"//commons.wikimedia.org/w/index.php?title=User:Jagriti_Zinta&amp;action=edit&amp;redlink=1\" class=\"new\" title=\"User:Jagriti Zinta (page does not exist)\">Jagriti Zinta</a>"))
        assertEquals("A & B <c> \"d\" 'e' – f", HtmlText.text("A &amp; B &lt;c&gt; &quot;d&quot; &#39;e&#39; &#x2013; f"))
        assertEquals("하나 둘", HtmlText.text("<p>하나</p><p>둘</p>"))
        assertEquals("a b", HtmlText.text("a<br/>b"))
        assertEquals("", HtmlText.text(null)); assertEquals("", HtmlText.text("  <b></b> "))
        assertEquals("&unknown;", HtmlText.text("&unknown;"))
        val long = HtmlText.text("x".repeat(300), 50); assertEquals(50, long.length); assertTrue(long.endsWith("…"))
    }

    // ---------- 위키미디어 응답 파싱 (실제 응답 픽스처) ----------
    private fun byTitle(p: FreePage, part: String) = p.items.firstOrNull { it.attribution.title.contains(part) }

    @Test fun wikimediaVideo_plainSearch_filtersLicenseFormatAndSize() {
        val page = WikimediaParser.parse(fixture("wikimedia_video_sunrise.json"), FreeKind.VIDEO)
        // 20건 중 CC BY-SA 7건(인덱스 2·5·11·13·15·17·19)은 이용 조건 때문에 제외
        assertEquals(7, page.excluded.license)
        assertTrue(page.items.isNotEmpty())
        assertEquals("실제 응답: 다음 쪽 시작 위치", 20, page.next)
        for (m in page.items) {
            assertEquals(FreeKind.VIDEO, m.kind)
            assertTrue("허용 이용 조건만: ${m.attribution.licenseLabel}", m.attribution.licenseLabel.let { it.startsWith("CC BY") && !it.contains("SA") || it == "CC0 1.0" || it == "공개 도메인" })
            assertTrue("폰이 읽는 형식: ${m.mime}", m.mime == "video/webm" || m.mime == "video/mp4")
            assertTrue("크기 상한 100MB 이내: ${m.bytes}", m.bytes in 1..SizeCaps.MAX_VIDEO_BYTES)
            assertTrue(m.downloadUrl.startsWith("https://upload.wikimedia.org/"))
            assertTrue(m.thumbUrl.startsWith("https://"))
            assertTrue(m.attribution.sourceUrl.startsWith("https://commons.wikimedia.org/wiki/File:"))
            assertEquals(FreeProvider.WIKIMEDIA, m.attribution.provider)
            assertTrue(m.id.startsWith("wm:"))
        }
        assertEquals("같은 자료가 두 번 나오지 않는다", page.items.size, page.items.map { it.id }.distinct().size)
        // 순서는 검색 순위(index)를 따른다: 'Yogyakarta Sunrise, One'(1번)이 'Time-lapse sunrise Berlin'(4번)보다 먼저
        val order = page.items.map { it.attribution.title }
        assertTrue(order.indexOf("Yogyakarta Sunrise, One") in 0 until order.indexOf("Time-lapse sunrise Berlin Drachenberg"))
    }

    @Test fun wikimediaVideo_hugeOriginalUsesSmallerVp9Derivative() {
        val page = WikimediaParser.parse(fixture("wikimedia_video_sunrise.json"), FreeKind.VIDEO)
        // 원본 375MB(CC BY 4.0) → 1080p VP9 파생 파일(약 72MB)
        val yogya = byTitle(page, "Yogyakarta Sunrise, One")!!
        assertTrue(yogya.downloadUrl, yogya.downloadUrl.endsWith("Yogyakarta_Sunrise%2C_One.webm.1080p.vp9.webm"))
        assertEquals(1920, yogya.width); assertEquals(1080, yogya.height); assertEquals(357, yogya.durationSec)
        assertEquals(72.1, yogya.bytes / 1e6, 0.2)
        assertEquals("CC BY 4.0", yogya.attribution.licenseLabel); assertTrue(yogya.attribution.requiresCredit)
        // 원본 182MB(CC0) → 1080p 파생 약 22MB
        val berlin = byTitle(page, "Time-lapse sunrise Berlin")!!
        assertTrue(berlin.downloadUrl.endsWith(".1080p.vp9.webm")); assertEquals(22.0, berlin.bytes / 1e6, 0.2)
        assertEquals("CC0 1.0", berlin.attribution.licenseLabel); assertFalse(berlin.attribution.requiresCredit)
        // 원본이 작아도(5.7MB) 같은 선명도면 VP9 파생을 먼저 쓴다(원본은 AV1 일 수 있다)
        val small = byTitle(page, "3000 percent")!!
        assertTrue(small.downloadUrl.endsWith(".1080p.vp9.webm"))
        // 파생 중 mp4v(quicktime) 는 쓰지 않는다
        assertTrue(page.items.none { it.downloadUrl.endsWith(".mov") })
    }

    @Test fun wikimediaVideo_ogvOriginalIsNotUsedButItsWebmDerivativeIs() {
        val page = WikimediaParser.parse(fixture("wikimedia_video_sunrise.json"), FreeKind.VIDEO)
        val shuttle = byTitle(page, "Final Shuttle Mission")!!             // 공개 도메인, 원본은 .ogv(Theora) — 파생 webm 을 받는다
        assertEquals("공개 도메인", shuttle.attribution.licenseLabel)
        assertTrue(shuttle.downloadUrl.endsWith(".webm")); assertEquals("video/webm", shuttle.mime)
        assertTrue(shuttle.downloadUrl.contains("/transcoded/"))
    }

    @Test fun wikimediaVideo_freeCategoryFixture_allAllowed_authorsClean() {
        val page = WikimediaParser.parse(fixture("wikimedia_video_sea_free.json"), FreeKind.VIDEO)
        assertEquals("분류 필터를 건 검색은 이용 조건 제외가 없다", 0, page.excluded.license)
        assertTrue(page.items.size >= 12)
        for (m in page.items) {
            assertFalse("작가 칸에 HTML 이 남으면 안 된다: ${m.attribution.author}", m.attribution.author.contains("<") || m.attribution.author.contains("&amp;"))
            assertTrue(m.attribution.author.isNotBlank()); assertTrue(m.attribution.title.isNotBlank())
        }
        assertTrue(page.items.any { it.attribution.author == "bellergy" })     // 'Aerial Shot Of Sea With Speedboat' 의 작가(일반 글자)
    }

    @Test fun wikimediaPhoto_bySaOnlyResultIsFullyExcluded() {
        val page = WikimediaParser.parse(fixture("wikimedia_photo_sunrise.json"), FreeKind.PHOTO)    // 'Quality images' 15건 전부 CC BY-SA
        assertEquals(0, page.items.size); assertEquals(15, page.excluded.license); assertEquals(15, page.next)
    }

    @Test fun wikimediaPhoto_freeCategoryFixture() {
        val page = WikimediaParser.parse(fixture("wikimedia_photo_sunrise_free.json"), FreeKind.PHOTO)
        assertEquals(20, page.items.size); assertEquals(0, page.excluded.total)
        // 원본이 20MB 이하·4000만 화소 이하이면 원본을 그대로 받는다
        val ok = byTitle(page, "Sunrise at Kramat village beach")!!
        assertEquals("CC0 1.0", ok.attribution.licenseLabel)
        assertEquals(2336, ok.width); assertEquals(4160, ok.height)
        assertEquals(1_915_427L, ok.bytes); assertTrue(ok.downloadUrl.startsWith("https://upload.wikimedia.org/")); assertEquals("", ok.resizeTitle); assertEquals("image/jpeg", ok.mime)
        // 원본 25MB(6499x4311) → 원본 대신 줄인 그림을 받는다(주소는 받을 때 다시 묻는다)
        val big = page.items.first { it.attribution.title.contains("Escale") }
        assertEquals("", big.downloadUrl); assertTrue(big.resizeTitle.startsWith("File:Escale")); assertEquals(-1L, big.bytes)
        // 작가 칸의 HTML 링크는 글자만
        assertEquals("CC BY 2.5", byTitle(page, "Bryce Canon Sunrise very early")!!.attribution.licenseLabel)
        for (m in page.items) { assertFalse(m.attribution.author.contains("<")); assertTrue(m.attribution.requiresCredit == m.attribution.licenseLabel.startsWith("CC BY")) }
    }

    @Test fun wikimediaParser_brokenInputs() {
        for (bad in listOf("", "not json", "{}", "[]", "{\"query\":{}}", "{\"query\":{\"pages\":{}}}", "{\"query\":{\"pages\":{\"1\":{}}}}")) {
            val p = WikimediaParser.parse(bad, FreeKind.PHOTO)
            assertTrue(bad, p.items.isEmpty())
        }
        // 일부가 깨져도 나머지는 살린다
        val json = """{"query":{"pages":{"1":{"pageid":1,"ns":6,"title":"File:A.jpg","index":2,"imageinfo":[{"size":1000,"width":2000,"height":3000,"url":"https://upload.wikimedia.org/a.jpg","descriptionurl":"https://commons.wikimedia.org/wiki/File:A.jpg","mime":"image/jpeg","mediatype":"BITMAP","extmetadata":{"LicenseShortName":{"value":"CC0"}}}]},"2":{"pageid":2,"title":"File:B.jpg","index":1,"imageinfo":[]},"3":"oops"}}}"""
        val p = WikimediaParser.parse(json, FreeKind.PHOTO)
        assertEquals(1, p.items.size); assertEquals("A", p.items[0].attribution.title); assertEquals("작가 미상", p.items[0].attribution.author)
    }

    @Test fun wikimediaPhoto_formatRules() {
        fun item(mime: String, w: Int, h: Int, license: String = "CC0", url: String = "https://upload.wikimedia.org/x.jpg", mediatype: String = "BITMAP") =
            """{"query":{"pages":{"1":{"pageid":1,"title":"File:X.jpg","index":1,"imageinfo":[{"size":5000,"width":$w,"height":$h,"url":"$url","mime":"$mime","mediatype":"$mediatype","extmetadata":{"LicenseShortName":{"value":"$license"}}}]}}}}"""
        assertEquals(1, WikimediaParser.parse(item("image/jpeg", 2000, 1500), FreeKind.PHOTO).items.size)
        assertEquals(1, WikimediaParser.parse(item("image/png", 1000, 600), FreeKind.PHOTO).items.size)
        assertEquals(1, WikimediaParser.parse(item("image/webp", 1000, 600), FreeKind.PHOTO).items.size)
        for (mime in listOf("image/tiff", "image/gif", "image/svg+xml", "application/pdf", "image/vnd.djvu")) {
            val p = WikimediaParser.parse(item(mime, 3000, 2000), FreeKind.PHOTO); assertEquals(mime, 0, p.items.size); assertEquals(1, p.excluded.format)
        }
        val tiny = WikimediaParser.parse(item("image/jpeg", 640, 480), FreeKind.PHOTO); assertEquals(0, tiny.items.size); assertEquals(1, tiny.excluded.format)
        assertEquals("https 가 아닌 주소는 버린다", 0, WikimediaParser.parse(item("image/jpeg", 2000, 1500, url = "http://upload.wikimedia.org/x.jpg"), FreeKind.PHOTO).items.size)
        assertEquals(0, WikimediaParser.parse(item("image/jpeg", 2000, 1500, license = "CC BY-SA 4.0"), FreeKind.PHOTO).items.size)
        // 4000만 화소를 넘으면(크기가 작아도) 원본 대신 줄인 그림
        val px = WikimediaParser.parse(item("image/jpeg", 9000, 6000), FreeKind.PHOTO).items.single(); assertEquals("", px.downloadUrl); assertTrue(px.resizeTitle.isNotEmpty())
    }

    @Test fun authorFallbacks() {
        assertEquals("홍길동", WikimediaParser.author("<a href=\"x\">홍길동</a>", "<span>Own work</span>"))
        assertEquals("작가 미상", WikimediaParser.author("Unknown author", ""))
        assertEquals("Flickr User", WikimediaParser.author("", "Flickr User"))
        assertEquals("작가 미상", WikimediaParser.author("", "<span class=\"int-own-work\">Own work</span>"))
        assertEquals("작가 미상", WikimediaParser.author("", ""))
    }

    @Test fun thumbUrlFromResizeQuery() {
        val ok = """{"query":{"pages":{"9":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/X.jpg/1920px-X.jpg","url":"https://upload.wikimedia.org/x.jpg"}]}}}}"""
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/X.jpg/1920px-X.jpg", WikimediaParser.thumbUrlFrom(ok))
        assertNull(WikimediaParser.thumbUrlFrom("{}")); assertNull(WikimediaParser.thumbUrlFrom("nope"))
        assertNull(WikimediaParser.thumbUrlFrom("""{"query":{"pages":{"9":{"imageinfo":[{"thumburl":"http://x/1.jpg"}]}}}}"""))
    }

    // ---------- 영상 파일 고르기 ----------
    private fun vs(minSide: Int, bytes: Long, original: Boolean = false, mime: String = "video/webm; codecs=\"vp9, opus\"") =
        VideoSource("https://upload.wikimedia.org/v$minSide${if (original) "o" else "d"}.webm", minSide * 16 / 9, minSide, bytes, mime, original)

    @Test fun videoPick() {
        val cap = SizeCaps.MAX_VIDEO_BYTES
        assertNull("후보가 없으면 null", VideoPick.choose(emptyList(), cap))
        assertNull("전부 상한 초과면 null(용량 때문에 제외)", VideoPick.choose(listOf(vs(1080, cap + 1, true), vs(1080, cap * 2)), cap))
        assertEquals("상한 안에서 가장 선명한 것", 1080, VideoPick.choose(listOf(vs(240, 1_000_000), vs(480, 5_000_000), vs(1080, 50_000_000)), cap)!!.minSide)
        assertEquals("1080 이 상한을 넘으면 480", 480, VideoPick.choose(listOf(vs(480, 5_000_000), vs(1080, cap + 5)), cap)!!.minSide)
        assertEquals("4K 보다 1080 파생을 먼저(디코딩 부담)", 1080, VideoPick.choose(listOf(vs(2160, 60_000_000, true), vs(1080, 30_000_000)), cap)!!.minSide)
        assertEquals("1080 이하가 없으면 큰 것도 쓴다", 2160, VideoPick.choose(listOf(vs(2160, 60_000_000, true)), cap)!!.minSide)
        assertFalse("같은 선명도면 파생 우선", VideoPick.choose(listOf(vs(1080, 5_000_000, true), vs(1080, 6_000_000)), cap)!!.original)
        assertNull("mp4v quicktime·theora ogg 는 쓰지 않는다", VideoPick.choose(listOf(vs(360, 1000, false, "video/quicktime; codecs=\"mp4v\""), vs(720, 1000, true, "application/ogg"), vs(720, 1000, false, "video/ogg; codecs=\"theora\"")), cap))
        assertNull("크기를 모르는(0) 후보는 쓰지 않는다", VideoPick.choose(listOf(vs(720, 0, true)), cap))
        assertNull("https 가 아닌 후보는 쓰지 않는다", VideoPick.choose(listOf(VideoSource("http://x/a.webm", 1920, 1080, 1000, "video/webm", true)), cap))
        assertEquals("mp4 도 허용", "video/mp4", VideoPick.choose(listOf(vs(720, 1000, true, "video/mp4")), cap)!!.mime)
    }

    // ---------- Openverse ----------
    @Test fun openverse_realResponse() {
        val page = OpenverseParser.parse(fixture("openverse_sunrise.json"))
        assertEquals("실제 응답 20건 전부 허용 이용 조건(요청에서 이미 걸렀다)", 20, page.items.size + page.excluded.total)
        assertTrue(page.items.isNotEmpty())
        val first = page.items[0]
        assertEquals("ov:f3d01c11-2375-4e70-b254-dfb3d716dab8", first.id)
        assertEquals("Sunrise at Ramp 25", first.attribution.title); assertEquals("CapeHatterasNPS", first.attribution.author)
        assertEquals("공개 도메인", first.attribution.licenseLabel); assertFalse(first.attribution.requiresCredit)
        assertEquals("https://www.flickr.com/photos/106602675@N05/52045018236", first.attribution.sourceUrl)
        assertEquals("https://live.staticflickr.com/65535/52045018236_d29dd6c1c4_b.jpg", first.downloadUrl)
        assertEquals(1024, first.width); assertEquals(678, first.height); assertEquals(FreeProvider.OPENVERSE, first.attribution.provider)
        assertEquals(-1L, first.bytes)
        assertTrue(page.items.any { it.attribution.licenseLabel == "CC BY 2.0" && it.attribution.requiresCredit })
        assertTrue(page.items.any { it.attribution.licenseLabel == "CC0 1.0" })
        for (m in page.items) assertTrue(m.downloadUrl.startsWith("https://"))
    }

    @Test fun openverse_filtersAndBrokenInputs() {
        fun r(extra: String = "", license: String = "by", url: String = "https://x.org/a.jpg", w: Int = 1024, h: Int = 700) =
            """{"id":"i1","title":"T","creator":"C","license":"$license","license_version":"2.0","url":"$url","foreign_landing_url":"https://x.org/p","width":$w,"height":$h$extra}"""
        fun parse(vararg rs: String) = OpenverseParser.parse("""{"results":[${rs.joinToString(",")}]}""")
        assertEquals(1, parse(r()).items.size)
        assertEquals("by-sa 제외", 0, parse(r(license = "by-sa")).items.size); assertEquals(1, parse(r(license = "by-nc")).excluded.license)
        assertEquals("http 주소 제외", 0, parse(r(url = "http://x.org/a.jpg")).items.size)
        assertEquals("작은 그림 제외", 0, parse(r(w = 500, h = 400)).items.size)
        assertEquals("mature 제외", 0, parse(r(extra = ",\"mature\":true")).items.size)
        assertEquals("gif 제외", 0, parse(r(extra = ",\"filetype\":\"gif\"")).items.size)
        assertEquals("filetype 없으면 통과", 1, parse(r(extra = ",\"filetype\":null")).items.size)
        val noCreator = OpenverseParser.parse("""{"results":[{"id":"i2","title":"","creator":null,"license":"cc0","license_version":"1.0","url":"https://x.org/a.png","filetype":"png","width":null,"height":null}]}""")
        assertEquals("작가 미상", noCreator.items.single().attribution.author); assertEquals("무제", noCreator.items.single().attribution.title); assertEquals("image/png", noCreator.items.single().mime)
        for (bad in listOf("", "x", "{}", "{\"results\":null}", "{\"results\":[1,\"a\",null]}")) assertTrue(bad, OpenverseParser.parse(bad).items.isEmpty())
    }

    // ---------- 요청 주소 ----------
    @Test fun cleanTerms() {
        assertEquals("sunrise", FreeUrls.cleanTerms("  sunrise  "))
        assertEquals("sunrise mountain", FreeUrls.cleanTerms("sunrise   mountain"))
        assertEquals("incategory CC-BY-SA-4 0", FreeUrls.cleanTerms("incategory:\"CC-BY-SA-4.0\""))   // 연산자 기호가 사라져 단순 낱말이 된다
        assertEquals("a b c", FreeUrls.cleanTerms("a|b<c>"))
        assertEquals("sea", FreeUrls.cleanTerms("-sea"))
        assertEquals("푸른 하늘", FreeUrls.cleanTerms("푸른　하늘!"))
        assertEquals(80, FreeUrls.cleanTerms("a".repeat(200)).length)
        assertEquals("", FreeUrls.cleanTerms("!!!"))
    }

    @Test fun wikimediaUrls() {
        val v = FreeUrls.wikimediaSearch("sea", FreeKind.VIDEO)
        assertTrue(v.startsWith("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6"))
        assertTrue(v.contains("filetype%3Avideo")); assertTrue(v.contains("prop=videoinfo")); assertTrue(v.contains("viprop=url%7Csize%7Cmime%7Cextmetadata%7Cmediatype%7Cderivatives"))
        assertTrue("서버 쪽 이용 조건 분류 필터", v.contains("incategory%3ACC-Zero%7CCC-BY-4.0")); assertFalse(v.contains("gsroffset"))
        assertTrue(FreeUrls.wikimediaSearch("sea", FreeKind.VIDEO, 30).contains("&gsroffset=30"))
        val p = FreeUrls.wikimediaSearch("sea", FreeKind.PHOTO)
        assertTrue(p.contains("filetype%3Abitmap")); assertTrue(p.contains("filew%3A%3E1000")); assertTrue(p.contains("prop=imageinfo")); assertTrue(p.contains("iiurlwidth=500"))
        assertFalse(p.contains("derivatives"))
        assertTrue(FreeUrls.wikimediaThumb("File:A b.jpg", 1920).let { it.contains("titles=File%3AA+b.jpg") && it.endsWith("iiurlwidth=1920") })
        val o = FreeUrls.openverse("sunrise")
        assertEquals("https://api.openverse.org/v1/images/?q=sunrise&license=cc0,pdm,by&category=photograph&mature=false&page_size=20", o)
        assertTrue(FreeUrls.LICENSE_CATEGORIES.split('|').none { it.contains("SA", ignoreCase = false) && it.startsWith("CC-BY-SA") })
    }

    // ---------- 한→영 사전 ----------
    @Test fun koreanChipsAllTranslate() {
        assertEquals(16, KoreanTopics.CHIPS.size)
        assertEquals(listOf("일출", "노을", "바다", "하늘", "구름", "숲", "꽃", "별", "빛", "십자가", "교회", "산", "강", "비", "눈", "들판"), KoreanTopics.CHIPS.map { it.first })
        for ((ko, en) in KoreanTopics.CHIPS) {
            val p = KoreanTopics.plan(ko)
            assertEquals(ko, en, p.english); assertTrue(p.unknown.isEmpty()); assertFalse(p.rawKorean); assertEquals("", KoreanTopics.notice(p))
        }
    }

    @Test fun koreanPlans() {
        assertEquals("sunrise sea", KoreanTopics.plan("일출 바다").english)
        assertEquals("조사를 떼고 찾는다", "sea", KoreanTopics.plan("바다를").english)
        assertEquals("sky", KoreanTopics.plan("하늘이").english); assertEquals("mountain river", KoreanTopics.plan("산과 강").english)
        assertEquals("sunrise", KoreanTopics.plan("일출에서").english)
        assertEquals("영어는 그대로", "blue sky", KoreanTopics.plan("blue 하늘").english)
        assertEquals("church 2024", KoreanTopics.plan("church 2024").english)
        assertEquals("sea", KoreanTopics.plan("바다 푸르른").english)
        val partial = KoreanTopics.plan("바다 푸르른"); assertEquals(listOf("푸르른"), partial.unknown); assertFalse(partial.rawKorean)
        assertTrue(KoreanTopics.notice(partial).contains("푸르른")); assertTrue(KoreanTopics.notice(partial).contains("빼고"))
        val raw = KoreanTopics.plan("푸르른"); assertEquals("푸르른", raw.english); assertTrue(raw.rawKorean); assertTrue(KoreanTopics.notice(raw).contains("그대로"))
        val empty = KoreanTopics.plan("   "); assertFalse(empty.rawKorean); assertEquals("", empty.english)
        assertEquals("공백이 여럿이어도", "sunrise sea", KoreanTopics.plan("  일출    바다 ").english)
    }

    @Test fun koreanDictionaryHasNoBlankOrDuplicateTargets() {
        val words = listOf("해", "달", "새벽", "호수", "폭포", "나무", "단풍", "비둘기", "양", "목자", "광야", "성경", "기도", "무지개", "은하수", "정원")
        for (w in words) assertTrue(w, KoreanTopics.plan(w).english.isNotBlank() && KoreanTopics.plan(w).english != w)
    }

    // ---------- 출처 문구 ----------
    private fun att(title: String, author: String, lic: String = "CC BY 4.0", url: String = "https://commons.wikimedia.org/wiki/File:$title", prov: FreeProvider = FreeProvider.WIKIMEDIA, req: Boolean = lic.startsWith("CC BY")) =
        Attribution(title, author, lic, "https://creativecommons.org/licenses/by/4.0/", url, prov, req)

    @Test fun creditOnScreen() {
        assertEquals("", CreditText.onScreen(emptyList()))
        assertEquals("자료: 홍길동(CC BY 4.0) · 위키미디어 공용", CreditText.onScreen(listOf(att("A", "홍길동"))))
        val three = listOf(att("A", "가", "CC BY 4.0"), att("B", "나", "CC0 1.0"), att("C", "다", "공개 도메인"))
        assertEquals("자료: 가(CC BY 4.0) · 나(CC0 1.0) · 다(공개 도메인) · 위키미디어 공용", CreditText.onScreen(three))
        val five = three + att("D", "라") + att("E", "마", prov = FreeProvider.OPENVERSE)
        assertEquals("자료: 가(CC BY 4.0) · 나(CC0 1.0) · 다(공개 도메인) 외 2건 · 위키미디어 공용·Openverse", CreditText.onScreen(five))
        assertEquals("같은 자료는 한 번만", "자료: 가(CC BY 4.0) · 위키미디어 공용", CreditText.onScreen(listOf(att("A", "가"), att("A", "가"))))
        assertTrue("긴 작가 이름은 줄인다", CreditText.onScreen(listOf(att("A", "가".repeat(60)))).contains("…("))
    }

    @Test fun creditForDescription() {
        assertEquals("", CreditText.forDescription(emptyList()))
        val t = CreditText.forDescription(listOf(att("Sunrise", "홍길동"), att("Sea", "Jane", "CC0 1.0", prov = FreeProvider.OPENVERSE), att("Pexels 영상 1", "Kim", "Pexels 라이선스", "https://www.pexels.com/video/1/", FreeProvider.PEXELS, false)))
        assertTrue(t.startsWith("[사진·영상 출처]\n"))
        assertTrue(t.contains("1. \"Sunrise\" — 홍길동 / CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/)\n   원본: https://commons.wikimedia.org/wiki/File:Sunrise\n"))
        assertTrue(t.contains("2. \"Sea\" — Jane / CC0 1.0")); assertTrue(t.contains("3. \"Pexels 영상 1\" — Kim / Pexels 라이선스"))
        assertTrue(t.endsWith("자료 제공: 위키미디어 공용, Openverse, Pexels"))
        assertTrue(CreditText.anyRequired(listOf(att("A", "a"))))
        assertFalse(CreditText.anyRequired(listOf(att("A", "a", "CC0 1.0"))))
    }

    @Test fun projectUsedAttributionsOnlyForScenesThatUseThem() {
        val m1 = MediaRef("1", "file:///1.jpg", false, 0, "a", MediaOrigin.FREE, "x", att("One", "가"))
        val m2 = MediaRef("2", "file:///2.jpg", false, 0, "b", MediaOrigin.FREE, "y", att("Two", "나"))
        val m3 = MediaRef("3", "file:///3.jpg", false, 0, "c", MediaOrigin.PHONE)
        val scenes2 = listOf(Scene(1, "하나."), Scene(2, "둘."))
        // 장면 2개, 자료 3개: 순서대로 돌아가며 1·2번만 쓰인다
        assertEquals(listOf("One", "Two"), Project(scenes = scenes2, media = listOf(m1, m2, m3)).usedAttributions().map { it.title })
        // 장면 1개: 1번만 쓰인다
        assertEquals(listOf("One"), Project(scenes = scenes2.take(1), media = listOf(m1, m2)).usedAttributions().map { it.title })
        // 장면별로 고른 자료가 우선
        assertEquals(listOf("Two"), Project(scenes = listOf(Scene(1, "하나.", mediaId = "2")), media = listOf(m1, m2)).usedAttributions().map { it.title })
        // 내 사진(표기 정보 없음)은 빠진다
        assertTrue(Project(scenes = scenes2.take(1), media = listOf(m3)).usedAttributions().isEmpty())
        // 같은 자료가 여러 장면에 쓰여도 한 번
        assertEquals(1, Project(scenes = scenes2, media = listOf(m1)).usedAttributions().size)
        assertTrue(Project().showCredits)
    }

    // ---------- 크기 상한 ----------
    @Test fun boundedCopy() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val seen = ArrayList<Long>()
        val ok = BoundedCopy.copy(ByteArrayInputStream(data), out, 400_000) { seen += it }
        assertEquals(BoundedCopy.Result.Done(300_000), ok); assertArrayEquals(data, out.toByteArray()); assertEquals(300_000L, seen.last())
        assertEquals("정확히 상한이면 통과", BoundedCopy.Result.Done(300_000), BoundedCopy.copy(ByteArrayInputStream(data), ByteArrayOutputStream(), 300_000))
        val big = ByteArrayOutputStream()
        val r = BoundedCopy.copy(ByteArrayInputStream(data), big, 299_999)
        assertTrue(r is BoundedCopy.Result.TooBig); assertTrue("상한을 넘기 전까지만 썼다", big.size() <= 299_999)
        var n = 0
        assertEquals(BoundedCopy.Result.Cancelled, BoundedCopy.copy(ByteArrayInputStream(data), ByteArrayOutputStream(), 1_000_000, isCancelled = { n++ >= 2 }))
        assertEquals(BoundedCopy.Result.Done(0), BoundedCopy.copy(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), 10))
    }

    @Test fun sizeCapsAndPrecheck() {
        assertEquals(100L * 1024 * 1024, SizeCaps.MAX_VIDEO_BYTES); assertEquals(20L * 1024 * 1024, SizeCaps.MAX_PHOTO_BYTES)
        assertEquals(SizeCaps.MAX_VIDEO_BYTES, SizeCaps.maxFor(FreeKind.VIDEO)); assertEquals(SizeCaps.MAX_PHOTO_BYTES, SizeCaps.maxFor(FreeKind.PHOTO))
        assertTrue(BoundedCopy.precheck(-1, 10)); assertTrue(BoundedCopy.precheck(10, 10)); assertFalse(BoundedCopy.precheck(11, 10))
        assertEquals("100.0MB", SizeCaps.mb(SizeCaps.MAX_VIDEO_BYTES)); assertEquals("1.8MB", SizeCaps.mb(1_915_427)); assertEquals("293KB", SizeCaps.mb(300_000)); assertEquals("?", SizeCaps.mb(-1))
    }

    // ---------- 안내 문구 ----------
    @Test fun searchMessages() {
        assertEquals("", FreeMessages.search("", 5, 0, null, false))
        assertEquals("이용 조건이 맞지 않는 자료 3건은 숨겼습니다.", FreeMessages.search("", 5, 3, null, false))
        assertTrue(FreeMessages.search("", 0, 0, null, false).contains("결과가 없습니다"))
        assertEquals("인터넷에 연결되지 않았거나 응답이 없습니다.", FreeMessages.search("", 0, 0, "인터넷에 연결되지 않았거나 응답이 없습니다.", false))
        assertEquals("다른 출처를 기다리는 동안은 '결과 없음'을 말하지 않는다", "", FreeMessages.search("", 0, 0, null, true))
        assertEquals("안내A\n이용 조건이 맞지 않는 자료 1건은 숨겼습니다.", FreeMessages.search("안내A", 2, 1, null, false))
    }

    // ---------- 서버 요청(가짜 인터넷) ----------
    @Test fun serviceRetriesServerErrorsOnceAndStopsOnBadRequests() {
        val json = fixture("wikimedia_photo_sunrise_free.json")
        var calls = 0; val pauses = ArrayList<Long>()
        val svc = FreeMediaService({ calls++; if (calls == 1) throw FreeError(FreeError.Kind.SERVER, "503") else json }, { pauses += it })
        assertEquals(20, svc.searchWikimedia("sunrise", FreeKind.PHOTO).items.size); assertEquals(2, calls); assertEquals(listOf(1500L), pauses)

        calls = 0
        val svc2 = FreeMediaService({ calls++; throw FreeError(FreeError.Kind.BAD, "400") }, { })
        try { svc2.searchWikimedia("x", FreeKind.PHOTO); fail() } catch (e: FreeError) { assertEquals(FreeError.Kind.BAD, e.kind) }
        assertEquals("재시도하지 않는다", 1, calls)

        calls = 0
        val svc3 = FreeMediaService({ calls++; throw FreeError(FreeError.Kind.RATE_LIMIT, "429") }, { })
        try { svc3.searchWikimedia("x", FreeKind.PHOTO); fail() } catch (e: FreeError) { assertEquals(FreeError.Kind.RATE_LIMIT, e.kind) }
        assertEquals("한 번만 다시 시도", 2, calls)
        assertTrue(FreeError(FreeError.Kind.OFFLINE, "x").retryable.not())
        assertTrue(FreeError(FreeError.Kind.TIMEOUT, "x").retryable)
    }

    @Test fun serviceOpenverseDoesNotRetryAndPropagates() {
        var calls = 0
        val svc = FreeMediaService({ calls++; throw FreeError(FreeError.Kind.SERVER, "504") }, { })
        try { svc.searchOpenverse("sunrise"); fail() } catch (e: FreeError) { assertEquals(FreeError.Kind.SERVER, e.kind) }
        assertEquals(1, calls)
        val ok = FreeMediaService({ fixture("openverse_sunrise.json") }, { })
        assertTrue(ok.searchOpenverse("sunrise").items.isNotEmpty())
    }

    @Test fun serviceResizedPhotoUrlsAsksForThumbWidths() {
        val asked = ArrayList<String>()
        val svc = FreeMediaService({ url ->
            asked += url
            if (url.contains("iiurlwidth=3840")) """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/t/3840px-X.jpg"}]}}}}"""
            else """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/t/1920px-X.jpg"}]}}}}"""
        }, { })
        val big = FreeMedia(FreeProvider.WIKIMEDIA, "wm:1", FreeKind.PHOTO, att("X", "a"), "", 6000, 4000, 0, "", -1, "image/jpeg", "File:X.jpg")
        assertEquals(listOf("https://upload.wikimedia.org/t/3840px-X.jpg", "https://upload.wikimedia.org/t/1920px-X.jpg"), svc.resizedPhotoUrls(big))
        assertEquals(2, asked.size)
        asked.clear()
        assertEquals(listOf("https://upload.wikimedia.org/t/1920px-X.jpg"), svc.resizedPhotoUrls(big.copy(width = 3000, height = 2000)))
        val failing = FreeMediaService({ throw FreeError(FreeError.Kind.OFFLINE, "x") }, { })
        assertTrue(failing.resizedPhotoUrls(big).isEmpty())
    }

    @Test fun userAgentNamesTheAppAndHasNoPersonalEmail() {
        val ua = freeUserAgent("0.4.0")
        assertEquals("GoodseedShorts/0.4.0 (+https://github.com/soultech-oh-ux/goodseed-shorts/releases)", ua)
        assertFalse(ua.contains("@"))
        assertTrue(ua.all { it.code in 32..126 })
    }

    @Test fun fileFetcherRefusesNonHttps() {
        val r = HttpFileFetcher("x").fetch("http://example.org/a.jpg", java.io.File.createTempFile("gsx", ".jpg"), 100, { false }, { })
        assertTrue(r is FetchResult.Failed)
        try { HttpUrlGet("x").get("http://example.org/"); fail() } catch (e: FreeError) { assertEquals(FreeError.Kind.BAD, e.kind) }
    }
}
