package com.goodseed.shorts

import com.goodseed.shorts.data.*
import com.goodseed.shorts.logic.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

private fun fx(name: String): String =
    FreeSourcesTest::class.java.classLoader!!.getResourceAsStream("free/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

/** 0.5.0 새 출처(NASA · 메트로폴리탄 미술관 · Openverse 음악) — 순수 로직 시험. 픽스처는 2026-11-06 실제 API 응답이다. */
class FreeSourcesTest {

    // ---------- NASA 사진 ----------
    @Test fun nasaImages_realResponse() {
        val page = NasaParser.parse(fx("nasa_image_sunrise.json"), FreeKind.PHOTO)
        assertEquals("실제 응답 20건 중 800px 미만 3건 제외", 17, page.items.size); assertEquals(3, page.excluded.format); assertEquals(0, page.excluded.license)
        assertEquals("다음 쪽 번호(링크 rel=next 의 page=2)", 2, page.next)
        val hangar = page.items.first()
        assertEquals("nasa:GRC-2012-C-04984", hangar.id); assertEquals("Hangar at Sunrise", hangar.attribution.title)
        assertEquals("NASA / Marvin Smith", hangar.attribution.author)
        assertEquals("공개 도메인", hangar.attribution.licenseLabel); assertFalse(hangar.attribution.requiresCredit)
        assertEquals("https://images.nasa.gov/details/GRC-2012-C-04984", hangar.attribution.sourceUrl); assertEquals(FreeProvider.NASA, hangar.attribution.provider)
        assertTrue("상한 안에서 가장 큰 그림(원본 3000x2250)", hangar.downloadUrl.endsWith("GRC-2012-C-04984~orig.jpg")); assertEquals(3000, hangar.width); assertEquals(1_730_000L, hangar.bytes)
        assertTrue(hangar.thumbUrl.startsWith("https://") && hangar.thumbUrl.endsWith("~thumb.jpg"))
        assertEquals("image/jpeg", hangar.mime); assertEquals(FreeKind.PHOTO, hangar.kind); assertEquals("", hangar.collectionUrl)
        val ksc = page.items.first { it.id == "nasa:KSC-20200715-PH-JBS01_0008" }
        assertTrue("원본 없이 large 가 가장 큰 경우", ksc.downloadUrl.endsWith("~large.jpg")); assertEquals(1280, ksc.width)
        val pia = page.items.first { it.id == "nasa:PIA11350" }
        assertEquals("NASA/Johns Hopkins University Applied Physics Laboratory/Carnegie Institution of Washington", pia.attribution.author)
        for (m in page.items) { assertTrue(m.downloadUrl.startsWith("https://images-assets.nasa.gov/")); assertTrue(maxOf(m.width, m.height) >= 800); assertTrue(m.bytes in 1..SizeCaps.MAX_PHOTO_BYTES) }
        assertEquals(page.items.size, page.items.map { it.id }.distinct().size)
        assertTrue("작은 화성 사진(508px)은 제외", page.items.none { it.id == "nasa:PIA18537" })
    }

    @Test fun nasaLogosAndInsigniaAreExcluded() {
        val page = NasaParser.parse(fx("nasa_image_logo.json"), FreeKind.PHOTO)
        assertEquals("실제 응답 10건 전부 로고·휘장 관련", 0, page.items.size); assertEquals(10, page.excluded.other)
    }

    @Test fun nasaCopyrightMarksAreExcluded() {
        fun item(desc: String, extra: String = "") = """{"collection":{"items":[{"href":"https://images-assets.nasa.gov/image/X/collection.json","data":[{"nasa_id":"X","title":"A view","description":"$desc","media_type":"image"$extra}],"links":[{"href":"https://images-assets.nasa.gov/image/X/X~large.jpg","render":"image","width":1920,"height":1080,"size":300000,"rel":"alternate"}]}]}}"""
        assertEquals(1, NasaParser.parse(item("Plain NASA photo."), FreeKind.PHOTO).items.size)
        assertEquals(1, NasaParser.parse(item("Photo courtesy of NASA."), FreeKind.PHOTO).items.size)
        for (bad in listOf("© ESA", "Copyright 2020 Someone", "All rights reserved", "Used with permission", "Image courtesy of ESA")) {
            val p = NasaParser.parse(item(bad), FreeKind.PHOTO); assertEquals(bad, 0, p.items.size); assertEquals(bad, 1, p.excluded.license)
        }
        assertEquals("작가 칸의 저작권 표시도 거른다", 0, NasaParser.parse(item("ok", ",\"photographer\":\"© Jane\""), FreeKind.PHOTO).items.size)
    }

    @Test fun nasaCredit() {
        assertEquals("NASA", NasaParser.credit("", "")); assertEquals("NASA/JPL", NasaParser.credit("NASA/JPL", "")); assertEquals("NASA / Jane Doe", NasaParser.credit("Jane Doe", ""))
        assertEquals("NASA / Kim", NasaParser.credit("", "Kim")); assertEquals("nasa/gsfc", NasaParser.credit("nasa/gsfc", ""))
    }

    // ---------- NASA 영상 ----------
    @Test fun nasaVideos_realResponse() {
        val page = NasaParser.parse(fx("nasa_video_earth.json"), FreeKind.VIDEO)
        assertEquals(20, page.items.size); assertEquals(2, page.next)
        val orbit = page.items.first()
        assertEquals("nasa:GSFC_20140421_EarthOrbit_m11525", orbit.id); assertEquals("Earth from Orbit 2013", orbit.attribution.title)
        assertEquals("NASA / Jefferson Beck, Katrina Jackson", orbit.attribution.author)
        assertEquals("https://images-assets.nasa.gov/video/GSFC_20140421_EarthOrbit_m11525/collection.json", orbit.collectionUrl)
        assertEquals("영상 파일은 받을 때 고른다", "", orbit.downloadUrl); assertEquals("video/mp4", orbit.mime); assertEquals("mp4", orbit.fileExt)
        assertTrue(orbit.thumbUrl.startsWith("https://") && orbit.thumbUrl.contains("~thumb"))
        assertTrue(page.items.all { it.kind == FreeKind.VIDEO && it.collectionUrl.startsWith("https://") && it.thumbUrl.startsWith("https://") })
        assertEquals("사진을 달라고 했는데 영상 응답이면 거른다", 0, NasaParser.parse(fx("nasa_video_earth.json"), FreeKind.PHOTO).items.size)
        assertEquals("영상 응답은 사진 응답에서 거른다", 0, NasaParser.parse(fx("nasa_image_sunrise.json"), FreeKind.VIDEO).items.size)
    }

    @Test fun nasaMp4Choice_realCollection() {
        val urls = JSONArray(fx("nasa_video_collection.json")).let { a -> (0 until a.length()).map { a.getString(it) } }
        val ranked = NasaPick.rankedMp4(urls)
        assertEquals("화질 순서 · mov/jpg/vtt 제외 · https 로 올림", listOf("medium", "small", "mobile", "preview").map { "https://images-assets.nasa.gov/video/GSFC_20140421_EarthOrbit_m11525/GSFC_20140421_EarthOrbit_m11525~$it.mp4" }, ranked)
        val sizes = mapOf(ranked[0] to 65_068_407L, ranked[1] to 42_550_744L, ranked[2] to 9_575_575L, ranked[3] to -1L)    // 실측 HEAD 크기(preview 는 접근 불가로 가정)
        assertEquals(listOf(ranked[0], ranked[1], ranked[2]), NasaPick.choose(ranked, { sizes[it] ?: -1 }, SizeCaps.MAX_VIDEO_BYTES).map { it.first })
        assertEquals("상한이 50MB 면 medium 은 빠진다", listOf(ranked[1], ranked[2]), NasaPick.choose(ranked, { sizes[it] ?: -1 }, 50L * 1024 * 1024).map { it.first })
        assertEquals(listOf<String>(), NasaPick.choose(ranked, { sizes[it] ?: -1 }, 1_000_000))
        assertEquals("크기를 모르면(접근 불가) 쓰지 않는다", listOf<String>(), NasaPick.choose(ranked, { -1 }, SizeCaps.MAX_VIDEO_BYTES))
        assertEquals(listOf<String>(), NasaPick.rankedMp4(listOf("http://evil.example/x~medium.mp4", "https://images-assets.nasa.gov/video/a/a~orig.mov")))
        assertEquals("large 가 있으면 medium 보다 먼저", "https://images-assets.nasa.gov/v/a~large.mp4", NasaPick.rankedMp4(listOf("http://images-assets.nasa.gov/v/a~medium.mp4", "http://images-assets.nasa.gov/v/a~large.mp4")).first())
    }

    @Test fun nasaImagePick() {
        fun l(name: String, w: Int, size: Long) = NasaLink("https://images-assets.nasa.gov/image/a/a~$name.jpg", w, w * 3 / 4, size, "alternate")
        assertEquals(1920, NasaPick.image(listOf(l("small", 640, 50000), l("large", 1920, 385000), l("medium", 1280, 181000)), SizeCaps.MAX_PHOTO_BYTES)!!.width)
        assertEquals("상한을 넘는 원본은 건너뛰고 그 아래에서 가장 큰 것", 1920, NasaPick.image(listOf(l("orig", 6000, 30_000_000), l("large", 1920, 385000)), SizeCaps.MAX_PHOTO_BYTES)!!.width)
        assertNull("크기를 모르는 그림은 쓰지 않는다", NasaPick.image(listOf(l("orig", 3000, -1)), SizeCaps.MAX_PHOTO_BYTES))
        assertNull(NasaPick.image(listOf(NasaLink("http://evil/a.jpg", 2000, 1500, 100, "x"), NasaLink("https://images-assets.nasa.gov/a.tif", 2000, 1500, 100, "x")), SizeCaps.MAX_PHOTO_BYTES))
    }

    @Test fun nasaBrokenInputs() {
        for (bad in listOf("", "x", "{}", "{\"collection\":null}", "{\"collection\":{}}", "{\"collection\":{\"items\":[1,null,{}]}}")) assertTrue(bad, NasaParser.parse(bad, FreeKind.PHOTO).items.isEmpty())
    }

    @Test fun safeUrl() {
        assertEquals("https://x.org/a", SafeUrl.https("https://x.org/a"))
        assertEquals("https://images-assets.nasa.gov/a.jpg", SafeUrl.https("http://images-assets.nasa.gov/a.jpg"))
        assertEquals("", SafeUrl.https("http://evil.example/a.jpg")); assertEquals("", SafeUrl.https("ftp://images-assets.nasa.gov/a")); assertEquals("", SafeUrl.https(""))
        assertEquals("", SafeUrl.https("http://images-assets.nasa.gov.evil.com/a"))
    }

    // ---------- 메트로폴리탄 미술관 ----------
    @Test fun metSearchResponse() {
        val s = MetParser.parseSearch(fx("met_search_shepherd.json"))
        assertEquals(31, s.total); assertEquals(31, s.ids.size); assertEquals(listOf(211316L, 931445L, 931446L), s.ids.take(3))
        assertEquals(0, MetParser.parseSearch("""{"total":0,"objectIDs":null}""").ids.size)
        assertEquals(0, MetParser.parseSearch("garbage").total)
    }

    @Test fun metObjects_realResponses() {
        val gogh = MetParser.parseObject(fx("met_object_436535.json"))!!
        assertEquals("met:436535", gogh.id); assertEquals("Wheat Field with Cypresses (1889)", gogh.attribution.title); assertEquals("Vincent van Gogh", gogh.attribution.author)
        assertEquals("공개 도메인", gogh.attribution.licenseLabel); assertEquals(FreeProvider.MET, gogh.attribution.provider); assertFalse(gogh.attribution.requiresCredit)
        assertEquals("https://images.metmuseum.org/CRDImages/ep/original/DP-42549-001.jpg", gogh.downloadUrl)
        assertEquals(listOf("https://images.metmuseum.org/CRDImages/ep/web-large/DP-42549-001.jpg"), gogh.altUrls)
        assertEquals("https://images.metmuseum.org/CRDImages/ep/web-large/DP-42549-001.jpg", gogh.thumbUrl)
        assertEquals("https://www.metmuseum.org/art/collection/search/436535", gogh.attribution.sourceUrl); assertEquals(FreeKind.PHOTO, gogh.kind); assertEquals("image/jpeg", gogh.mime)
        assertEquals("작가 이름이 비면 문화권", "American", MetParser.parseObject(fx("met_object_15248.json"))!!.attribution.author)
        assertEquals("작가도 문화권도 비면 작가 미상", "작가 미상", MetParser.parseObject(fx("met_object_211316.json"))!!.attribution.author)
        assertEquals("Shepherd (18th century)", MetParser.parseObject(fx("met_object_211316.json"))!!.attribution.title)
        assertEquals("Francesco Casanova", MetParser.parseObject(fx("met_object_435858.json"))!!.attribution.author)
        assertEquals("Cavalier and Shepherd", MetParser.parseObject(fx("met_object_435858.json"))!!.attribution.title)
    }

    @Test fun metPublicDomainIsRecheckedOnTheObject() {
        fun obj(pd: String = "true", big: String = "https://images.metmuseum.org/a/original/x.jpg", small: String = "https://images.metmuseum.org/a/web-large/x.jpg", url: String = "https://www.metmuseum.org/art/collection/search/5") =
            """{"objectID":5,"isPublicDomain":$pd,"primaryImage":"$big","primaryImageSmall":"$small","title":"T","artistDisplayName":"A","objectDate":"","objectURL":"$url"}"""
        assertNotNull(MetParser.parseObject(obj()))
        assertNull("공개 도메인이 아니면 보이지 않는다", MetParser.parseObject(obj(pd = "false")))
        assertNull("공개 도메인 값이 없으면 보이지 않는다", MetParser.parseObject("""{"objectID":5,"primaryImage":"https://images.metmuseum.org/a.jpg"}"""))
        assertNull("그림이 없으면 보이지 않는다", MetParser.parseObject(obj(big = "", small = "")))
        assertNull("메트 서버가 아닌 그림 주소는 쓰지 않는다", MetParser.parseObject(obj(big = "https://evil.example/x.jpg", small = "http://images.metmuseum.org/a.jpg")))
        val onlySmall = MetParser.parseObject(obj(big = ""))!!; assertEquals("https://images.metmuseum.org/a/web-large/x.jpg", onlySmall.downloadUrl); assertTrue(onlySmall.altUrls.isEmpty())
        assertEquals("objectURL 이 이상하면 직접 만든다", "https://www.metmuseum.org/art/collection/search/5", MetParser.parseObject(obj(url = "http://evil/x"))!!.attribution.sourceUrl)
        for (bad in listOf("", "x", "{}", """{"message":"Not Found"}""", "[]")) assertNull(bad, MetParser.parseObject(bad))
    }

    @Test fun metCursor() {
        assertEquals(32, MetParser.offsetOf(MetParser.cursor(32, false))); assertFalse(MetParser.isBroad(MetParser.cursor(32, false)))
        assertEquals(48, MetParser.offsetOf(MetParser.cursor(48, true))); assertTrue(MetParser.isBroad(MetParser.cursor(48, true)))
        assertEquals(0, MetParser.offsetOf(MetParser.cursor(0, false)))
    }

    // ---------- Openverse 음악 ----------
    @Test fun openverseAudio_realResponse() {
        val page = OpenverseAudioParser.parse(fx("openverse_audio_piano.json"))
        assertEquals(20, page.items.size + page.excluded.total); assertTrue(page.items.size >= 18)
        val first = page.items.first()
        assertEquals("ova:df628b13-b533-41c1-a4ec-aa97f8c84dc2", first.id); assertEquals("Calm Piano Jazz Loop", first.attribution.title); assertEquals("ispeakwaves", first.attribution.author)
        assertEquals("CC BY 4.0", first.attribution.licenseLabel); assertTrue(first.attribution.requiresCredit)
        assertEquals("https://creativecommons.org/licenses/by/4.0/", first.attribution.licenseUrl); assertEquals("https://freesound.org/people/ispeakwaves/sounds/570316", first.attribution.sourceUrl)
        assertEquals("https://cdn.freesound.org/previews/570/570316_7190944-hq.mp3", first.downloadUrl); assertEquals("mp3", first.ext)
        assertEquals(23_991L, first.durationMs); assertEquals(561_629L, first.bytes); assertEquals("0:23", first.durationText()); assertEquals(FreeProvider.OPENVERSE, first.attribution.provider)
        assertTrue(page.items.any { it.attribution.licenseLabel == "CC0 1.0" })
        assertEquals("ova_df628b13_b533_41c1_a4ec_aa97f8c84dc2", first.fileKey)
    }

    @Test fun openverseAudio_filters() {
        fun r(license: String = "by", url: String = "https://cdn.x.org/a.mp3", ft: String = "mp3", extra: String = "") =
            """{"results":[{"id":"i","title":"T","creator":"C","license":"$license","license_version":"4.0","url":"$url","filetype":"$ft","duration":60000$extra}]}"""
        assertEquals(1, OpenverseAudioParser.parse(r()).items.size)
        assertEquals(1, OpenverseAudioParser.parse(r(license = "pdm")).items.size)
        assertEquals(0, OpenverseAudioParser.parse(r(license = "by-sa")).items.size); assertEquals(1, OpenverseAudioParser.parse(r(license = "by-nc")).excluded.license)
        assertEquals(0, OpenverseAudioParser.parse(r(url = "http://cdn.x.org/a.mp3")).items.size)
        assertEquals(0, OpenverseAudioParser.parse(r(ft = "midi", url = "https://cdn.x.org/a.mid")).items.size)
        assertEquals("filetype 이 비면 주소의 확장자로", "ogg", OpenverseAudioParser.parse(r(ft = "", url = "https://cdn.x.org/a.ogg?x=1")).items.single().ext)
        assertEquals(0, OpenverseAudioParser.parse(r(extra = ",\"mature\":true")).items.size)
        assertEquals("작가가 null 이면 작가 미상", "작가 미상", OpenverseAudioParser.parse("""{"results":[{"id":"i","title":"T","creator":null,"license":"cc0","license_version":"1.0","url":"https://x.org/a.wav","filetype":"wav"}]}""").items.single().attribution.author)
        for (bad in listOf("", "x", "{}", "{\"results\":[1,null]}")) assertTrue(bad, OpenverseAudioParser.parse(bad).items.isEmpty())
        assertEquals("길이 모름", FreeMusic("a", Attribution("t", "a", "CC0", "", "", FreeProvider.OPENVERSE, false), 0, -1, "https://x/a.mp3", "mp3").durationText())
        assertEquals("1:05", FreeMusic("a", Attribution("t", "a", "CC0", "", "", FreeProvider.OPENVERSE, false), 65_000, -1, "https://x/a.mp3", "mp3").durationText())
    }

    // ---------- 출처 탭 · 합치기 ----------
    @Test fun sourcesAndTabs() {
        assertEquals(listOf(FreeSource.WIKIMEDIA, FreeSource.NASA, FreeSource.MET, FreeSource.OPENVERSE), FreeSources.forKind(FreeKind.PHOTO))
        assertEquals(listOf(FreeSource.WIKIMEDIA, FreeSource.NASA), FreeSources.forKind(FreeKind.VIDEO))
        assertEquals(listOf("전체", "위키미디어", "Openverse", "NASA", "명화"), FreeSources.tabsFor(FreeKind.PHOTO).map { it.label })
        assertEquals(listOf("전체", "위키미디어", "NASA"), FreeSources.tabsFor(FreeKind.VIDEO).map { it.label })
        assertEquals(FreeSources.forKind(FreeKind.PHOTO), FreeSources.active(FreeTab.ALL, FreeKind.PHOTO))
        assertEquals(listOf(FreeSource.MET), FreeSources.active(FreeTab.MET, FreeKind.PHOTO))
        assertEquals("영상에는 명화 탭이 없다", emptyList<FreeSource>(), FreeSources.active(FreeTab.MET, FreeKind.VIDEO))
        assertEquals(FreeTab.ALL, FreeSources.fixTab(FreeTab.OPENVERSE, FreeKind.VIDEO)); assertEquals(FreeTab.NASA, FreeSources.fixTab(FreeTab.NASA, FreeKind.VIDEO))
        assertEquals(FreeProvider.MET, FreeSource.MET.provider)
    }

    private fun fm(id: String) = FreeMedia(FreeProvider.WIKIMEDIA, id, FreeKind.PHOTO, Attribution(id, "a", "CC0", "", "https://x/$id", FreeProvider.WIKIMEDIA, false), "", 0, 0, 0, "https://x/$id.jpg", 1, "image/jpeg")

    @Test fun interleave() {
        val out = FreeMerge.interleave(listOf(listOf(fm("a1"), fm("a2"), fm("a3")), listOf(fm("b1")), emptyList(), listOf(fm("c1"), fm("c2"))))
        assertEquals(listOf("a1", "b1", "c1", "a2", "c2", "a3"), out.map { it.id })
        assertEquals("같은 번호는 한 번만", listOf("a1", "b1"), FreeMerge.interleave(listOf(listOf(fm("a1")), listOf(fm("a1"), fm("b1")))).map { it.id })
        assertTrue(FreeMerge.interleave(emptyList()).isEmpty())
    }

    // ---------- 출처 문구 · 판정표 ----------
    private fun att(p: FreeProvider, title: String = "T", author: String = "A", lic: String = "공개 도메인") = Attribution(title, author, lic, "https://l/", "https://s/$title", p, false)

    @Test fun creditLinesForNasaAndMet() {
        val t = CreditText.forDescription(listOf(att(FreeProvider.NASA, "Hangar", "NASA / Marvin Smith"), att(FreeProvider.MET, "Wheat Field", "Vincent van Gogh"), att(FreeProvider.WIKIMEDIA, "W", "w", "CC0 1.0")))
        assertTrue(t.contains("1. \"Hangar\" — NASA / Marvin Smith / 공개 도메인")); assertTrue(t.contains("2. \"Wheat Field\" — Vincent van Gogh / 공개 도메인"))
        assertTrue(t.contains("자료 제공: NASA, 메트로폴리탄 미술관, 위키미디어 공용"))
        assertTrue(t.contains("\nCredit: NASA")); assertTrue(t.endsWith("The Metropolitan Museum of Art, Public Domain (Open Access)"))
        assertFalse("NASA 가 없으면 NASA 크레딧 줄도 없다", CreditText.forDescription(listOf(att(FreeProvider.MET))).contains("Credit: NASA"))
        assertEquals("자료: NASA / Jane(공개 도메인) · Vincent(공개 도메인) · NASA·메트로폴리탄 미술관", CreditText.onScreen(listOf(att(FreeProvider.NASA, "a", "NASA / Jane"), att(FreeProvider.MET, "b", "Vincent"))))
    }

    @Test fun musicAttributionJoinsTheProjectCredits() {
        val mus = MusicChoice("file:///x/a.mp3", "Calm", 20_000, att(FreeProvider.OPENVERSE, "Calm", "ispeakwaves", "CC BY 4.0"))
        val scenes = listOf(Scene(1, "하나."))
        assertEquals(listOf("Calm"), Project(scenes = scenes, music = mus).usedAttributions().map { it.title })
        val img = MediaRef("1", "file:///1.jpg", false, 0, "a", MediaOrigin.FREE, "x", att(FreeProvider.NASA, "Img", "NASA"))
        assertEquals(listOf("Img", "Calm"), Project(scenes = scenes, media = listOf(img), music = mus).usedAttributions().map { it.title })
        assertTrue("내 음악(출처 없음)은 빠진다", Project(scenes = scenes, music = MusicChoice("content://m", "내 음악", 1000)).usedAttributions().isEmpty())
    }

    @Test fun pdmIsOnTheLicenseTable() {
        for (raw in listOf("PDM", "pdm", "Public Domain Mark", "Public Domain Mark 1.0")) assertTrue(raw, LicenseJudge.judgeText(raw).allowed)
        assertEquals("공개 도메인", LicenseJudge.judgeOpenverse("pdm", "1.0").label)
        assertTrue(LicenseJudge.judgeOpenverse("pdm", null).allowed)
    }

    // ---------- 받기 형식 확인 ----------
    @Test fun contentTypeGate() {
        for (ok in listOf("audio/mpeg", "audio/mp3", "audio/ogg", "audio/x-wav", "audio/wav", "audio/flac", "audio/mp4", "AUDIO/MPEG", "audio/mpeg; charset=binary", "image/jpeg", "image/png", "video/mp4", "video/webm", "application/octet-stream", "binary/octet-stream", "application/ogg", "", null))
            assertTrue("받아야 함: $ok", ContentTypes.acceptable(ok))
        for (bad in listOf("text/html", "text/html; charset=utf-8", "application/json", "application/xml", "text/plain", "application/pdf", "application/zip"))
            assertFalse("받으면 안 됨: $bad", ContentTypes.acceptable(bad))
    }

    // ---------- 사전 · 주소 ----------
    @Test fun chipsForMetAndMusic() {
        assertEquals(listOf("목자", "십자가", "기도", "천사", "성경", "풍경"), KoreanTopics.MET_CHIPS.map { it.first })
        for ((ko, en) in KoreanTopics.MET_CHIPS) assertEquals(ko, en, KoreanTopics.plan(ko).english)
        assertEquals(listOf("잔잔한 피아노", "기타", "현악", "자연 소리", "찬양 분위기", "명상"), KoreanTopics.MUSIC_CHIPS.map { it.first })
        assertTrue(KoreanTopics.MUSIC_CHIPS.all { it.second.isNotBlank() && it.second.all { c -> c.code < 128 } })
        assertEquals("calm piano", KoreanTopics.plan("잔잔한 피아노").english); assertEquals("nature sound", KoreanTopics.plan("자연 소리").english)
        assertEquals("angel", KoreanTopics.plan("천사").english)
        assertEquals("NASA 검색용 낱말", "earth", KoreanTopics.plan("지구").english); assertEquals("space", KoreanTopics.plan("우주").english); assertEquals("mars", KoreanTopics.plan("화성").english)
    }

    @Test fun newUrls() {
        assertEquals("https://images-api.nasa.gov/search?q=sunrise&media_type=image&page_size=24", FreeUrls.nasaSearch("sunrise", FreeKind.PHOTO))
        assertEquals("https://images-api.nasa.gov/search?q=earth&media_type=video&page_size=24&page=3", FreeUrls.nasaSearch("earth", FreeKind.VIDEO, 3))
        assertTrue(FreeUrls.nasaSearch("a b", FreeKind.PHOTO).contains("q=a+b"))
        val m = FreeUrls.metSearch("shepherd", 0, false)
        assertTrue(m.startsWith("https://collectionapi.metmuseum.org/public/collection/v1.1/search?q=shepherd")); assertTrue(m.contains("hasImages=true")); assertTrue(m.contains("isPublicDomain=true"))
        assertTrue(m.contains("title=true")); assertTrue(m.contains("medium=Paintings")); assertTrue(m.endsWith("&limit=16&offset=0"))
        assertFalse(FreeUrls.metSearch("shepherd", 16, true).contains("medium=")); assertTrue(FreeUrls.metSearch("shepherd", 16, true).endsWith("&limit=16&offset=16"))
        assertEquals("작품 상세는 v1(v1.1 은 404)", "https://collectionapi.metmuseum.org/public/collection/v1/objects/436535", FreeUrls.metObject(436535))
        assertEquals("https://api.openverse.org/v1/audio/?q=calm+piano&license=cc0,pdm,by&mature=false&page_size=20", FreeUrls.openverseAudio("calm piano"))
        assertTrue("검색 연산자는 닦는다", FreeUrls.metSearch("a|b:c", 0, true).contains("q=a+b+c"))
    }

    // ---------- 서비스(가짜 인터넷) ----------
    @Test fun serviceNasaRetriesOnce() {
        var calls = 0
        val svc = FreeMediaService({ calls++; if (calls == 1) throw FreeError(FreeError.Kind.SERVER, "503") else fx("nasa_image_sunrise.json") }, { })
        assertEquals(17, svc.searchNasa("sunrise", FreeKind.PHOTO).items.size); assertEquals(2, calls)
    }

    @Test fun serviceMetFetchesObjectsAndDropsBadOnes() {
        val ids = listOf(211316L, 931445L, 435858L, 436535L, 15248L)
        val asked = java.util.Collections.synchronizedList(ArrayList<String>())
        val search = """{"total":40,"objectIDs":[211316,999,931445,435858,436535,15248,888]}"""
        val pdFalse = fx("met_object_15248.json").replace("\"isPublicDomain\":true", "\"isPublicDomain\":false")
        val svc = FreeMediaService({ url ->
            asked += url
            when {
                url.startsWith(FreeUrls.MET_SEARCH) -> search
                url.endsWith("/999") || url.endsWith("/888") -> throw FreeError(FreeError.Kind.BAD, "404")
                url.endsWith("/15248") -> pdFalse
                else -> fx("met_object_${url.substringAfterLast('/')}.json")
            }
        }, { })
        val page = svc.searchMet("shepherd", 0)
        assertEquals(listOf("met:211316", "met:931445", "met:435858", "met:436535"), page.items.map { it.id })
        assertEquals("404 두 건 + 공개 도메인 아님 한 건", 3, page.excluded.other)
        assertEquals("다음 쪽(16개씩)", MetParser.cursor(16, false), page.next)
        assertTrue(asked.first().contains("v1.1/search")); assertTrue(asked.drop(1).all { it.contains("/v1/objects/") })
        assertEquals("마지막 쪽이면 다음이 없다", null, FreeMediaService({ url -> if (url.startsWith(FreeUrls.MET_SEARCH)) """{"total":5,"objectIDs":[436535]}""" else fx("met_object_436535.json") }, { }).searchMet("x", 0).next)
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test fun serviceMetBroadensWhenNoPaintings() {
        val urls = ArrayList<String>()
        val svc = FreeMediaService({ url -> urls += url; if (url.contains("medium=Paintings")) """{"total":0,"objectIDs":null}""" else if (url.startsWith(FreeUrls.MET_SEARCH)) """{"total":1,"objectIDs":[436535]}""" else fx("met_object_436535.json") }, { })
        val page = svc.searchMet("zzz", 0)
        assertEquals(1, page.items.size); assertTrue(urls[0].contains("medium=Paintings")); assertFalse(urls[1].contains("medium="))
        assertNull(page.next)
        // 넓게 찾은 다음 쪽도 넓게 이어 간다
        val svc2 = FreeMediaService({ url -> urls += url; """{"total":40,"objectIDs":null}""" }, { })
        urls.clear(); svc2.searchMet("zzz", MetParser.cursor(16, true)); assertFalse(urls.single().contains("medium="))
        assertTrue(urls.single().endsWith("offset=16"))
    }

    @Test fun serviceMetSearchFailurePropagates() {
        try { FreeMediaService({ throw FreeError(FreeError.Kind.OFFLINE, "x") }, { }).searchMet("a", 0); fail() } catch (e: FreeError) { assertEquals(FreeError.Kind.OFFLINE, e.kind) }
    }

    @Test fun serviceMusicRetriesAndParses() {
        var calls = 0
        val svc = FreeMediaService({ calls++; if (calls == 1) throw FreeError(FreeError.Kind.SERVER, "504") else fx("openverse_audio_piano.json") }, { })
        assertTrue(svc.searchMusic("calm piano").items.size >= 18); assertEquals(2, calls)
    }

    @Test fun serviceNasaVideoUrlsUsesHeadSizes() {
        val sizes = mapOf("medium" to 65_068_407L, "small" to 42_550_744L, "mobile" to 9_575_575L)
        val svc = FreeMediaService({ fx("nasa_video_collection.json") }, { }, { u -> sizes.entries.firstOrNull { u.endsWith("~${it.key}.mp4") }?.value ?: -1L })
        val v = NasaParser.parse(fx("nasa_video_earth.json"), FreeKind.VIDEO).items.first()
        assertEquals(listOf("medium", "small", "mobile"), svc.nasaVideoUrls(v).map { it.substringAfter('~').substringBefore('.') })
        assertTrue("HEAD 를 못 하면(모름) 후보가 없다", FreeMediaService({ fx("nasa_video_collection.json") }, { }).nasaVideoUrls(v).isEmpty())
        assertTrue("깨진 목록이면 빈 목록", FreeMediaService({ "not json" }, { }).nasaVideoUrls(v).isEmpty())
    }
}
