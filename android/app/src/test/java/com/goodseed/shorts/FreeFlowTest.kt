package com.goodseed.shorts

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodseed.shorts.data.*
import com.goodseed.shorts.logic.*
import com.goodseed.shorts.render.CreditOverlay
import com.goodseed.shorts.render.SubtitleLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

private fun res(name: String): String =
    FreeFlowTest::class.java.classLoader!!.getResourceAsStream("free/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

/** 가짜 인터넷: 주소에 따라 정해 둔 응답(또는 실패)을 돌려주고 요청 주소를 기록한다. */
class FakeNet(
    var wikimedia: (String) -> String = { res("wikimedia_photo_sunrise_free.json") },
    var openverse: () -> String = { res("openverse_sunrise.json") },
    var thumb: (String) -> String = { """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/t/1920px-X.jpg"}]}}}}""" },
    var nasa: (String) -> String = { """{"collection":{"items":[]}}""" },
    var met: (String) -> String = { """{"total":0,"objectIDs":null}""" },
    var metObject: (Long) -> String = { throw FreeError(FreeError.Kind.BAD, "404") },
    var audio: () -> String = { res("openverse_audio_piano.json") },
    var collection: () -> String = { res("nasa_video_collection.json") },
) : HttpGet {
    val urls = java.util.Collections.synchronizedList(ArrayList<String>())
    override fun get(url: String): String {
        urls += url
        return when {
            url.startsWith(FreeUrls.OPENVERSE_AUDIO_API) -> audio()
            url.startsWith(FreeUrls.OPENVERSE_API) -> openverse()
            url.startsWith(FreeUrls.NASA_API) -> nasa(url)
            url.startsWith(FreeUrls.MET_SEARCH) -> met(url)
            url.startsWith(FreeUrls.MET_OBJECT) -> metObject(url.removePrefix(FreeUrls.MET_OBJECT).toLong())
            url.contains("collection.json") -> collection()
            url.contains("titles=") -> thumb(url)
            url.startsWith(FreeUrls.COMMONS_API) -> wikimedia(url)
            else -> throw FreeError(FreeError.Kind.BAD, "모르는 주소 $url")
        }
    }
    fun count(part: String) = urls.count { it.contains(part) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeFlowTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var vm: AppViewModel
    private lateinit var net: FakeNet
    private val fetched = ArrayList<String>()
    private var headSizes: (String) -> Long = { -1 }
    private var fetchBehavior: (String, File, (Int) -> Unit, () -> Boolean) -> FetchResult = { _, dest, _, _ -> dest.parentFile?.mkdirs(); dest.writeBytes(ByteArray(2048) { 7 }); FetchResult.Ok(2048) }
    private var probeResult: (File, FreeMedia) -> MediaUtil.FileProbe = { f, m ->
        MediaUtil.FileProbe(MediaRef("id-${m.id}", f.toURI().toString(), m.kind == FreeKind.VIDEO, if (m.kind == FreeKind.VIDEO) 12_000 else 0, "${m.attribution.provider.label} · ${m.attribution.author}", MediaOrigin.FREE, m.attribution.author, m.attribution))
    }

    @Before fun setUp() {
        AppViewModel.startupChecks = false
        vm = AppViewModel(app)
        net = FakeNet()
        vm.freeService = FreeMediaService(net, { }, HttpHead { headSizes(it) })
        vm.freeFetcher = FileFetcher { url, dest, _, cancelled, progress -> fetched += url; fetchBehavior(url, dest, progress, cancelled) }
        vm.freeProbe = { f, m -> probeResult(f, m) }
        File(app.filesDir, "free").deleteRecursively()
        vm.acceptText("주님은 나의 목자시니 부족함이 없으리로다. 그가 나를 푸른 풀밭에 누이시며 쉴 만한 물가로 인도하시는도다.")
    }

    private fun await(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("시간 초과: $what")
            Thread.sleep(15)
        }
    }

    private fun search(q: String) { vm.searchFree(q); await("검색 끝") { !vm.free.busy && !vm.free.otherWaiting } }

    // ---------- 검색 ----------
    @Test fun photoSearchShowsWikimediaFirstThenOpenverse() {
        vm.openFree(); assertEquals(Screen.FREE, vm.screen)
        search("일출")
        val items = vm.free.items
        assertTrue(items.size > 20)
        assertEquals("위키미디어 결과가 먼저", FreeProvider.WIKIMEDIA, items.first().attribution.provider)
        assertTrue(items.any { it.attribution.provider == FreeProvider.OPENVERSE })
        assertEquals("같은 번호는 한 번만", items.size, items.map { it.id }.distinct().size)
        assertTrue(items.all { it.attribution.licenseLabel.let { l -> l.startsWith("CC BY") || l == "CC0 1.0" || l == "공개 도메인" } })
        assertEquals("일출", vm.free.query); assertTrue(vm.free.searched)
        // 한글 낱말 '일출' 은 영어 sunrise 로 바꿔 두 곳에 보낸다
        assertTrue(net.urls.single { it.startsWith(FreeUrls.COMMONS_API) }.contains("gsrsearch=sunrise"))
        assertTrue(net.urls.single { it.startsWith(FreeUrls.OPENVERSE_API) }.contains("q=sunrise"))
        assertEquals("", vm.free.message.lines().firstOrNull { it.contains("영어로") }.orEmpty())
    }

    @Test fun openverseFailureIsSilent() {
        net.openverse = { throw FreeError(FreeError.Kind.SERVER, "504") }
        search("바다")
        assertEquals(20, vm.free.items.size)
        assertTrue(vm.free.items.all { it.attribution.provider == FreeProvider.WIKIMEDIA })
        assertEquals("Openverse 실패는 화면에 아무 말도 하지 않는다", "", vm.free.message)
        assertFalse(vm.free.otherWaiting)
        assertEquals("Openverse 는 재시도하지 않는다", 1, net.count("api.openverse.org"))
    }

    @Test fun openverseGarbageIsAlsoSilent() {
        net.openverse = { "<html>504 Gateway Time-out</html>" }
        search("바다")
        assertEquals(20, vm.free.items.size); assertEquals("", vm.free.message)
    }

    @Test fun wikimediaDownButOpenverseWorksShowsOpenverse() {
        net.wikimedia = { throw FreeError(FreeError.Kind.OFFLINE, "인터넷에 연결되지 않았거나 응답이 없습니다.") }
        search("하늘")
        assertTrue(vm.free.items.isNotEmpty()); assertTrue(vm.free.items.all { it.attribution.provider == FreeProvider.OPENVERSE })
        assertFalse("결과가 있으면 오류 문구를 띄우지 않는다", vm.free.message.contains("인터넷"))
    }

    @Test fun bothDownShowsTheWikimediaErrorMessage() {
        net.wikimedia = { throw FreeError(FreeError.Kind.OFFLINE, "인터넷에 연결되지 않았거나 응답이 없습니다.") }
        net.openverse = { throw FreeError(FreeError.Kind.TIMEOUT, "느림") }
        net.nasa = { throw FreeError(FreeError.Kind.OFFLINE, "x") }; net.met = { throw FreeError(FreeError.Kind.OFFLINE, "x") }   // 전부 실패해야 오류 문구가 뜬다
        search("하늘")
        assertTrue(vm.free.items.isEmpty()); assertEquals("인터넷에 연결되지 않았거나 응답이 없습니다.", vm.free.message)
        assertFalse(vm.free.busy)
    }

    @Test fun noResultsMessageAndUnknownWords() {
        net.wikimedia = { """{"batchcomplete":""}""" }; net.openverse = { """{"result_count":0,"results":[]}""" }
        search("푸르른 바다")
        assertTrue(vm.free.items.isEmpty())
        assertTrue(vm.free.message.contains("푸르른")); assertTrue(vm.free.message.contains("결과가 없습니다"))
        assertTrue("모르는 낱말은 빼고 sea 로 찾는다", net.urls.first { it.startsWith(FreeUrls.COMMONS_API) }.contains("gsrsearch=sea"))
    }

    @Test fun hiddenCountIsReported() {
        net.wikimedia = { res("wikimedia_photo_sunrise.json") }      // 전부 CC BY-SA
        net.openverse = { throw FreeError(FreeError.Kind.SERVER, "x") }
        search("일출")
        assertTrue(vm.free.items.isEmpty()); assertTrue(vm.free.message.contains("결과가 없습니다"))
        // 쓸 수 있는 결과가 12개가 안 되면 3쪽까지 이어서 찾는다
        assertEquals(3, net.count("generator=search"))
    }

    @Test fun videoSearchUsesWikimediaOnly() {
        vm.setFreeKind(FreeKind.VIDEO)
        net.wikimedia = { res("wikimedia_video_sea_free.json") }
        search("바다")
        assertTrue(vm.free.items.isNotEmpty()); assertTrue(vm.free.items.all { it.kind == FreeKind.VIDEO })
        assertEquals(0, net.count("api.openverse.org"))
        assertTrue(net.urls.any { it.contains("filetype%3Avideo") })
    }

    @Test fun switchingKindClearsResults() {
        search("일출"); assertTrue(vm.free.items.isNotEmpty())
        vm.setFreeKind(FreeKind.VIDEO)
        assertEquals(FreeKind.VIDEO, vm.free.kind); assertTrue(vm.free.items.isEmpty()); assertFalse(vm.free.searched)
    }

    @Test fun moreAppendsNextPageWithoutDuplicates() {
        vm.setFreeKind(FreeKind.VIDEO)
        net.wikimedia = { url -> if (url.contains("gsroffset=")) res("wikimedia_video_sunrise.json") else res("wikimedia_video_sea_free.json") }
        search("바다")
        val first = vm.free.items; assertEquals(20, vm.free.next[FreeSource.WIKIMEDIA])
        vm.freeMore(); await("더 보기") { !vm.free.moreBusy }
        assertTrue(vm.free.items.size > first.size)
        assertEquals(first, vm.free.items.take(first.size)); assertEquals(vm.free.items.size, vm.free.items.map { it.id }.distinct().size)
        assertTrue(net.urls.any { it.contains("gsroffset=20") })
    }

    @Test fun blankSearchDoesNothing() {
        vm.searchFree("   "); assertEquals(0, net.urls.size); assertFalse(vm.free.busy)
    }

    // ---------- 받기 ----------
    private fun firstPhoto(): FreeMedia { search("일출"); return vm.free.items.first { it.attribution.provider == FreeProvider.WIKIMEDIA && it.downloadUrl.isNotBlank() } }

    @Test fun addPhotoDownloadsProbesAndStoresAttribution() {
        val m = firstPhoto()
        var seenPercent = -1
        fetchBehavior = { _, dest, progress, _ -> dest.parentFile?.mkdirs(); dest.writeBytes(ByteArray(1000)); progress(40); seenPercent = vm.free.percent; FetchResult.Ok(1000) }
        vm.previewFree(m); assertEquals(m, vm.free.preview)
        vm.addFree(m); assertNotNull(vm.free.downloadId)
        await("받기") { vm.free.downloadId == null }
        assertEquals(40, seenPercent)
        val ref = vm.project.media.single()
        assertEquals(MediaOrigin.FREE, ref.origin); assertEquals(m.attribution, ref.attribution); assertFalse(ref.isVideo)
        assertTrue(ref.uri.contains("/free/")); assertTrue(File(java.net.URI(ref.uri)).isFile)
        assertNull("미리보기 창은 닫힌다", vm.free.preview); assertTrue(vm.free.message.contains("추가했습니다"))
        assertEquals(listOf(m.downloadUrl), fetched)
        assertTrue(vm.isFreeAdded(m))
        // 같은 자료를 또 넣으려 하면 다시 받지 않고 알려 준다
        vm.addFree(m); assertEquals("이미 넣은 자료입니다.", vm.free.message); assertEquals(1, fetched.size); assertEquals(1, vm.project.media.size)
        // 장면에 쓰이는 자료의 출처 문구
        assertTrue(vm.creditDescription().contains(m.attribution.title)); assertTrue(vm.creditDescription().contains(m.attribution.licenseLabel))
    }

    @Test fun removingMediaAllowsAddingAgain() {
        val m = firstPhoto()
        vm.addFree(m); await("받기") { vm.free.downloadId == null }
        vm.removeMedia(vm.project.media.single().id); assertFalse(vm.isFreeAdded(m))
        vm.addFree(m); await("받기") { vm.free.downloadId == null }
        assertEquals(1, vm.project.media.size)
        assertEquals("이미 받은 파일은 다시 받지 않는다", 1, fetched.size)
    }

    @Test fun oversizedOriginalAsksForResizedPictureAndFallsBack() {
        search("일출")
        val big = vm.free.items.first { it.resizeTitle.isNotEmpty() }                // 원본 25MB(6499x4311)
        net.thumb = { url -> if (url.contains("iiurlwidth=3840")) """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/t/3840px-X.jpg"}]}}}}""" else """{"query":{"pages":{"1":{"imageinfo":[{"thumburl":"https://upload.wikimedia.org/t/1920px-X.jpg"}]}}}}""" }
        fetchBehavior = { url, dest, _, _ -> if (url.contains("3840px")) FetchResult.TooBig else { dest.parentFile?.mkdirs(); dest.writeBytes(ByteArray(500)); FetchResult.Ok(500) } }
        vm.addFree(big); await("받기") { vm.free.downloadId == null }
        assertEquals(listOf("https://upload.wikimedia.org/t/3840px-X.jpg", "https://upload.wikimedia.org/t/1920px-X.jpg"), fetched)
        assertEquals(1, vm.project.media.size)
    }

    @Test fun tooBigIsRefusedWithMessageAndNothingStored() {
        val m = firstPhoto()
        fetchBehavior = { _, _, _, _ -> FetchResult.TooBig }
        vm.addFree(m); await("받기") { vm.free.downloadId == null }
        assertTrue(vm.project.media.isEmpty()); assertTrue(vm.free.message.contains("20.0MB")); assertTrue(vm.free.message.contains("용량"))
    }

    @Test fun failedDownloadShowsReasonAndLeavesProjectUntouched() {
        val m = firstPhoto()
        fetchBehavior = { _, _, _, _ -> FetchResult.Failed("내려받는 중 인터넷이 끊겼습니다.") }
        vm.addFree(m); await("받기") { vm.free.downloadId == null }
        assertTrue(vm.project.media.isEmpty()); assertEquals("내려받는 중 인터넷이 끊겼습니다.", vm.free.message)
        // 다시 눌러 성공하면 들어간다
        fetchBehavior = { _, dest, _, _ -> dest.parentFile?.mkdirs(); dest.writeBytes(ByteArray(10)); FetchResult.Ok(10) }
        vm.addFree(m); await("받기") { vm.free.downloadId == null }
        assertEquals(1, vm.project.media.size)
    }

    @Test fun cancelStopsTheDownload() {
        val m = firstPhoto()
        fetchBehavior = { _, _, _, cancelled -> var n = 0; while (!cancelled() && n++ < 2000) Thread.sleep(5); FetchResult.Cancelled }
        vm.addFree(m); await("받기 시작") { vm.free.downloadId != null }
        vm.cancelFreeDownload()
        await("취소") { vm.free.downloadId == null }
        assertEquals("받기를 취소했습니다.", vm.free.message); assertTrue(vm.project.media.isEmpty())
    }

    @Test fun unreadableDownloadIsDeletedAndExplained() {
        val m = firstPhoto()
        probeResult = { _, _ -> MediaUtil.FileProbe(null) }
        vm.addFree(m); await("받기") { vm.free.downloadId == null }
        assertTrue(vm.free.message.contains("읽지 못했습니다")); assertTrue(vm.project.media.isEmpty())
        assertTrue("읽을 수 없는 파일은 남기지 않는다", File(app.filesDir, "free").listFiles().orEmpty().isEmpty())
    }

    @Test fun phoneCannotDecodeVideoGivesGuidance() {
        vm.setFreeKind(FreeKind.VIDEO); net.wikimedia = { res("wikimedia_video_sea_free.json") }
        search("바다")
        val v = vm.free.items.first()
        probeResult = { _, _ -> MediaUtil.FileProbe(null, "이 폰은 이 영상의 형식(VP9)을 읽지 못합니다. 다른 영상이나 사진을 골라 주세요.") }
        vm.addFree(v); await("받기") { vm.free.downloadId == null }
        assertEquals("이 폰은 이 영상의 형식(VP9)을 읽지 못합니다. 다른 영상이나 사진을 골라 주세요.", vm.free.message)
        assertTrue(vm.project.media.isEmpty())
    }

    @Test fun videoIsAddedWithItsDurationAndCap100MB() {
        vm.setFreeKind(FreeKind.VIDEO); net.wikimedia = { res("wikimedia_video_sea_free.json") }
        search("바다")
        val v = vm.free.items.first()
        var cap = 0L
        vm.freeFetcher = FileFetcher { _, dest, max, _, _ -> cap = max; dest.parentFile?.mkdirs(); dest.writeBytes(ByteArray(64)); FetchResult.Ok(64) }
        vm.addFree(v); await("받기") { vm.free.downloadId == null }
        assertEquals(SizeCaps.MAX_VIDEO_BYTES, cap)
        val ref = vm.project.media.single(); assertTrue(ref.isVideo); assertEquals(12_000L, ref.durationMs); assertTrue(ref.uri.endsWith(".webm") || ref.uri.endsWith(".mp4"))
    }

    @Test fun photoCapIs20MB() {
        val m = firstPhoto(); var cap = 0L
        vm.freeFetcher = FileFetcher { _, dest, max, _, _ -> cap = max; dest.parentFile?.mkdirs(); dest.writeBytes(ByteArray(64)); FetchResult.Ok(64) }
        vm.addFree(m); await("받기") { vm.free.downloadId == null }
        assertEquals(SizeCaps.MAX_PHOTO_BYTES, cap)
    }

    @Test fun cannotStartTwoDownloadsOrSearchWhileDownloading() {
        val m = firstPhoto()
        fetchBehavior = { _, dest, _, cancelled -> var n = 0; while (!cancelled() && n++ < 2000) Thread.sleep(5); dest.delete(); FetchResult.Cancelled }
        vm.addFree(m); await("받기 시작") { vm.free.downloadId != null }
        val other = vm.free.items.last { it.id != m.id }
        vm.addFree(other); vm.searchFree("바다"); vm.setFreeKind(FreeKind.VIDEO)
        assertEquals(m.id, vm.free.downloadId); assertEquals(FreeKind.PHOTO, vm.free.kind)
        vm.cancelFreeDownload(); await("취소") { vm.free.downloadId == null }
        assertEquals(1, fetched.size)
    }

    @Test fun backFromFreeScreenGoesToMediaAndCancels() {
        vm.go(Screen.MEDIA); vm.openFree(); assertEquals(Screen.FREE, vm.screen)
        val m = firstPhoto()
        fetchBehavior = { _, _, _, cancelled -> var n = 0; while (!cancelled() && n++ < 2000) Thread.sleep(5); FetchResult.Cancelled }
        vm.addFree(m); await("받기 시작") { vm.free.downloadId != null }
        vm.back(); assertEquals(Screen.MEDIA, vm.screen)
        await("취소") { vm.free.downloadId == null }; assertTrue(vm.project.media.isEmpty())
    }

    @Test fun startNewResetsFreeStateAndCleansOldFiles() {
        search("일출"); assertTrue(vm.free.items.isNotEmpty())
        val dir = File(app.filesDir, "free").also { it.mkdirs() }
        val old = File(dir, "old.jpg").also { it.writeBytes(ByteArray(5)); it.setLastModified(System.currentTimeMillis() - 8L * 86_400_000L) }
        val fresh = File(dir, "fresh.jpg").also { it.writeBytes(ByteArray(5)) }
        vm.startNew()
        assertEquals(FreeUi(), vm.free)
        await("정리") { !old.exists() }
        assertTrue("최근 파일은 남는다", fresh.exists())
    }

    // ---------- 출처 표기 ----------
    @Test fun creditsOptionAndTextFollowTheScenesThatUseTheMedia() {
        val m = firstPhoto()
        vm.addFree(m); await("받기") { vm.free.downloadId == null }
        assertTrue(vm.project.showCredits)
        val onScreen = CreditText.onScreen(vm.project.usedAttributions())
        assertTrue(onScreen.startsWith("자료: ")); assertTrue(onScreen.contains(m.attribution.licenseLabel)); assertTrue(onScreen.endsWith("위키미디어 공용"))
        vm.setShowCredits(false); assertFalse(vm.project.showCredits)
        assertTrue("옵션을 꺼도 설명란 문구는 만들 수 있다", vm.creditDescription().startsWith("[사진·영상 출처]"))
        // 내 사진만 있으면 출처 문구가 없다
        vm.clearMedia(); assertEquals("", vm.creditDescription())
    }

    // ---------- 영상 위 출처 글자 ----------
    @Test fun creditOverlayShowsFromLastSceneStartToTheEnd() {
        val tf = android.graphics.Typeface.DEFAULT
        val sp = SubtitleLayout.creditSpannable("자료: 홍길동(CC BY 4.0) · 위키미디어 공용", tf)!!
        val o = CreditOverlay(sp, 5_000_000L)
        assertFalse(o.visibleAt(0)); assertFalse(o.visibleAt(4_999_999)); assertTrue(o.visibleAt(5_000_000)); assertTrue(o.visibleAt(60_000_000))
        assertEquals(sp.toString(), o.getText(6_000_000L).toString()); assertEquals(" ", o.getText(1_000_000L).toString())
        assertNull(SubtitleLayout.creditSpannable("   ", tf))
        val long = SubtitleLayout.creditSpannable("가나다라마바사 ".repeat(80), tf)!!
        assertTrue("최대 4줄", long.toString().lines().size <= 4)
    }
}
