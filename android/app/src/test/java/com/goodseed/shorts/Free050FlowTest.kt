package com.goodseed.shorts

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodseed.shorts.data.*
import com.goodseed.shorts.logic.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

private fun r(name: String): String =
    Free050FlowTest::class.java.classLoader!!.getResourceAsStream("free/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

class FakePreview : UrlPreviewPlayer {
    val played = ArrayList<String>(); var stops = 0
    private var cb: ((UrlPreviewPlayer.State) -> Unit)? = null
    override fun play(url: String, onState: (UrlPreviewPlayer.State) -> Unit) { played += url; cb = onState; onState(UrlPreviewPlayer.State.LOADING) }
    override fun stop() { stops++ }
    fun emit(s: UrlPreviewPlayer.State) { cb?.invoke(s) }
}

/** 0.5.0: 출처 탭(NASA·명화)·무료 음악 찾기·최근 작품 삭제 흐름 시험. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Free050FlowTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var vm: AppViewModel
    private lateinit var net: FakeNet
    private lateinit var preview: FakePreview
    private val fetched = ArrayList<String>()
    private var headSizes: (String) -> Long = { -1 }
    private var fetchBehavior: (String, File, () -> Boolean) -> FetchResult = { _, dest, _ -> dest.parentFile?.mkdirs(); dest.writeBytes(ByteArray(2048) { 7 }); FetchResult.Ok(2048) }
    private val metIds = listOf(211316L, 931445L, 435858L, 436535L, 15248L)

    @Before fun setUp() {
        AppViewModel.startupChecks = false
        vm = AppViewModel(app)
        net = FakeNet(
            nasa = { url -> if (url.contains("media_type=video")) r("nasa_video_earth.json") else r("nasa_image_sunrise.json") },
            met = { """{"total":40,"objectIDs":[211316,931445,435858,436535,15248]}""" },
            metObject = { id -> if (id in metIds) r("met_object_$id.json") else throw FreeError(FreeError.Kind.BAD, "404") },
        )
        vm.freeService = FreeMediaService(net, { }, HttpHead { headSizes(it) })
        vm.freeFetcher = FileFetcher { url, dest, _, cancelled, _ -> fetched += url; fetchBehavior(url, dest, cancelled) }
        vm.freeProbe = { f, m -> MediaUtil.FileProbe(MediaRef("id-${m.id}", f.toURI().toString(), m.kind == FreeKind.VIDEO, if (m.kind == FreeKind.VIDEO) 9_000 else 0, "x", MediaOrigin.FREE, m.attribution.author, m.attribution)) }
        preview = FakePreview(); vm.previewPlayer = preview
        vm.galleryDeleter = { galleryCalls += it; galleryResult }
        vm.musicProbe = { f, m -> MusicChoice(f.toURI().toString(), m.attribution.title, m.durationMs.coerceAtLeast(1000), m.attribution) }
        File(app.filesDir, "free").deleteRecursively(); File(app.filesDir, "recordings").deleteRecursively(); File(app.filesDir, "recent.json").delete()
        vm.acceptText("주님은 나의 목자시니 부족함이 없으리로다. 그가 나를 푸른 풀밭에 누이시며 쉴 만한 물가로 인도하시는도다.")
    }

    private fun await(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { shadowOf(Looper.getMainLooper()).idle(); if (System.currentTimeMillis() > end) fail("시간 초과: $what"); Thread.sleep(15) }
    }
    private fun search(q: String) { vm.searchFree(q); await("검색 끝") { !vm.free.busy && !vm.free.otherWaiting } }

    // ---------- 출처 탭 ----------
    @Test fun nasaTabOnlyAsksNasa() {
        vm.setFreeTab(FreeTab.NASA); search("일출")
        assertEquals(17, vm.free.items.size); assertTrue(vm.free.items.all { it.attribution.provider == FreeProvider.NASA })
        assertEquals(0, net.count("commons.wikimedia.org")); assertEquals(0, net.count("api.openverse.org")); assertEquals(0, net.count("metmuseum"))
        assertTrue(net.urls.single().contains("q=sunrise") && net.urls.single().contains("media_type=image"))
        assertEquals(2, vm.free.next[FreeSource.NASA]); assertEquals("이용 조건 외 이유로 숨긴 3건 안내", "이용 조건이 맞지 않는 자료 3건은 숨겼습니다.", vm.free.message)
    }

    @Test fun allTabMixesSourcesRoundRobin() {
        search("목자")
        val items = vm.free.items
        assertEquals("출처 순서: 위키미디어 → NASA → 명화 → Openverse", listOf(FreeProvider.WIKIMEDIA, FreeProvider.NASA, FreeProvider.MET, FreeProvider.OPENVERSE), items.take(4).map { it.attribution.provider })
        assertTrue(items.any { it.attribution.provider == FreeProvider.MET }); assertEquals(items.size, items.map { it.id }.distinct().size)
        assertTrue(net.urls.any { it.contains("gsrsearch=shepherd") && it.startsWith(FreeUrls.COMMONS_API) })
        assertTrue(net.urls.any { it.startsWith(FreeUrls.NASA_API) && it.contains("q=shepherd") })
        assertTrue(net.urls.any { it.startsWith(FreeUrls.MET_SEARCH) && it.contains("q=shepherd") })
    }

    @Test fun oneFailingSourceIsSkippedSilently() {
        net.nasa = { throw FreeError(FreeError.Kind.SERVER, "서버 503") }
        net.met = { throw FreeError(FreeError.Kind.OFFLINE, "끊김") }
        net.openverse = { throw FreeError(FreeError.Kind.SERVER, "504") }
        search("일출")
        assertEquals("위키미디어 결과만", 20, vm.free.items.size); assertTrue(vm.free.items.all { it.attribution.provider == FreeProvider.WIKIMEDIA })
        assertEquals("", vm.free.message); assertFalse(vm.free.otherWaiting)
    }

    @Test fun failureInSingleSourceTabIsShown() {
        net.nasa = { throw FreeError(FreeError.Kind.OFFLINE, "인터넷에 연결되지 않았거나 응답이 없습니다.") }
        vm.setFreeTab(FreeTab.NASA); search("일출")
        assertTrue(vm.free.items.isEmpty()); assertEquals("인터넷에 연결되지 않았거나 응답이 없습니다.", vm.free.message)
    }

    @Test fun allSourcesAnsweredButEmptyIsNoResultsNotAnError() {
        net.wikimedia = { """{"batchcomplete":""}""" }; net.openverse = { throw FreeError(FreeError.Kind.SERVER, "x") }
        net.nasa = { """{"collection":{"items":[]}}""" }; net.met = { """{"total":0,"objectIDs":null}""" }
        search("일출")
        assertTrue(vm.free.items.isEmpty()); assertTrue(vm.free.message.contains("결과가 없습니다"))
    }

    @Test fun metTabSearchesPaintingsAndRecheckedObjectsOnly() {
        net.met = { r("met_search_shepherd.json") }        // 실제 검색 응답: 31건(그중 우리가 상세 응답을 갖고 있는 작품만 확인에 성공, 나머지는 404 로 가정)
        val ids = MetParser.parseSearch(r("met_search_shepherd.json")).ids
        val expected = ids.filter { it in metIds }
        vm.setFreeTab(FreeTab.MET); search("목자")
        assertEquals(expected.map { "met:$it" }, vm.free.items.map { it.id }); assertTrue(expected.size >= 3)
        assertTrue(vm.free.items.all { it.attribution.licenseLabel == "공개 도메인" && it.attribution.provider == FreeProvider.MET })
        assertEquals("상세 확인에서 빠진 작품", ids.size - expected.size, vm.free.hidden)
        assertEquals(1, net.count("v1.1/search")); assertEquals(ids.size, net.count("/v1/objects/")); assertEquals(0, net.count("commons.wikimedia.org"))
        assertEquals(16, MetParser.offsetOf(vm.free.next[FreeSource.MET]!!))
    }

    @Test fun videoModeHasNoMetOrOpenverse() {
        vm.setFreeTab(FreeTab.MET); vm.setFreeKind(FreeKind.VIDEO)
        assertEquals("영상에서는 명화 탭이 없으므로 전체로", FreeTab.ALL, vm.free.tab)
        net.wikimedia = { r("wikimedia_video_sea_free.json") }
        search("바다")
        assertEquals(0, net.count("metmuseum")); assertEquals(0, net.count("api.openverse.org"))
        assertTrue(vm.free.items.any { it.attribution.provider == FreeProvider.NASA && it.kind == FreeKind.VIDEO })
        assertTrue(vm.free.items.any { it.attribution.provider == FreeProvider.WIKIMEDIA })
        vm.setFreeKind(FreeKind.PHOTO); vm.setFreeTab(FreeTab.OPENVERSE); vm.setFreeKind(FreeKind.VIDEO); assertEquals(FreeTab.ALL, vm.free.tab)
    }

    @Test fun moreLoadsTheNextNasaPage() {
        vm.setFreeTab(FreeTab.NASA); search("일출")
        val before = vm.free.items
        vm.freeMore(); await("더 보기") { !vm.free.moreBusy }
        assertTrue(net.urls.any { it.contains("page=2") }); assertEquals(before, vm.free.items)        // 같은 응답을 돌려줬으므로 새 자료가 없다
        assertEquals("더 찾을 수 있는 자료가 없습니다.", vm.free.message)
    }

    @Test fun moreAppendsNewItemsAfterTheOldOnes() {
        vm.setFreeTab(FreeTab.NASA)
        net.nasa = { url -> if (url.contains("page=2")) r("nasa_image_sunrise.json").replace("GRC-2012-C-04984", "ZZZ-NEW-1") else r("nasa_image_sunrise.json") }
        search("일출"); val first = vm.free.items
        vm.freeMore(); await("더 보기") { !vm.free.moreBusy }
        assertEquals(first.size + 1, vm.free.items.size); assertEquals(first, vm.free.items.take(first.size)); assertEquals("nasa:ZZZ-NEW-1", vm.free.items.last().id)
    }

    // ---------- 받기: NASA · 메트 ----------
    @Test fun nasaVideoPicksMediumThenFallsBackToSmall() {
        vm.setFreeKind(FreeKind.VIDEO); vm.setFreeTab(FreeTab.NASA); search("earth")
        val v = vm.free.items.first()
        headSizes = { u -> when { u.endsWith("~medium.mp4") -> 65_068_407L; u.endsWith("~small.mp4") -> 42_550_744L; u.endsWith("~mobile.mp4") -> 9_575_575L; else -> -1L } }
        fetchBehavior = { url, dest, _ -> if (url.endsWith("~medium.mp4")) FetchResult.TooBig else { dest.parentFile?.mkdirs(); dest.writeBytes(ByteArray(100)); FetchResult.Ok(100) } }
        vm.addFree(v); await("받기") { vm.free.downloadId == null }
        assertEquals(listOf("medium", "small"), fetched.map { it.substringAfter('~').substringBefore('.') })
        val ref = vm.project.media.single(); assertTrue(ref.isVideo); assertTrue(ref.uri.endsWith(".mp4")); assertEquals("NASA / Jefferson Beck, Katrina Jackson", ref.attribution!!.author)
        assertTrue(vm.free.message.contains("추가했습니다"))
    }

    @Test fun nasaVideoWithoutUsableMp4Explains() {
        vm.setFreeKind(FreeKind.VIDEO); vm.setFreeTab(FreeTab.NASA); search("earth")
        headSizes = { -1 }
        vm.addFree(vm.free.items.first()); await("받기") { vm.free.downloadId == null }
        assertTrue(vm.free.message.contains("mp4")); assertTrue(vm.project.media.isEmpty()); assertTrue(fetched.isEmpty())
    }

    @Test fun metImageFallsBackToTheSmallerPictureWhenOriginalIsTooBig() {
        vm.setFreeTab(FreeTab.MET); search("목자")
        val gogh = vm.free.items.first { it.id == "met:436535" }
        fetchBehavior = { url, dest, _ -> if (url.contains("/original/")) FetchResult.TooBig else { dest.parentFile?.mkdirs(); dest.writeBytes(ByteArray(100)); FetchResult.Ok(100) } }
        vm.addFree(gogh); await("받기") { vm.free.downloadId == null }
        assertEquals(listOf("https://images.metmuseum.org/CRDImages/ep/original/DP-42549-001.jpg", "https://images.metmuseum.org/CRDImages/ep/web-large/DP-42549-001.jpg"), fetched)
        assertEquals(1, vm.project.media.size)
        val d = vm.creditDescription()
        assertTrue(d.contains("\"Wheat Field with Cypresses (1889)\" — Vincent van Gogh / 공개 도메인")); assertTrue(d.contains("The Metropolitan Museum of Art, Public Domain (Open Access)"))
    }

    @Test fun nasaPhotoCreditsAppearInTheDescription() {
        vm.setFreeTab(FreeTab.NASA); search("일출")
        vm.addFree(vm.free.items.first()); await("받기") { vm.free.downloadId == null }
        val d = vm.creditDescription()
        assertTrue(d.contains("Hangar at Sunrise")); assertTrue(d.contains("Credit: NASA")); assertTrue(CreditText.onScreen(vm.project.usedAttributions()).contains("NASA / Marvin Smith(공개 도메인)"))
    }

    // ---------- 무료 음악 ----------
    private fun musicSearch(chip: Boolean = true) {
        if (chip) vm.searchMusic("잔잔한 피아노", "calm piano") else vm.searchMusic("잔잔한 피아노")
        await("음악 검색") { !vm.music.busy }
    }

    @Test fun musicSearchByChipAndByTypedText() {
        vm.openMusic(); assertEquals(Screen.MUSIC, vm.screen)
        musicSearch(true)
        assertTrue(vm.music.items.size >= 18); assertEquals("잔잔한 피아노", vm.music.query)
        assertTrue(net.urls.single().startsWith(FreeUrls.OPENVERSE_AUDIO_API) && net.urls.single().contains("q=calm+piano"))
        net.urls.clear(); musicSearch(false)
        assertTrue("직접 입력은 사전으로 영어로 바꾼다", net.urls.single().contains("q=calm+piano"))
    }

    @Test fun musicSearchFailureExplains() {
        net.audio = { throw FreeError(FreeError.Kind.SERVER, "자료 서버가 지금 응답하지 못합니다(코드 504).") }
        musicSearch()
        assertTrue(vm.music.items.isEmpty()); assertTrue(vm.music.message.contains("음악 서버(Openverse)")); assertTrue(vm.music.message.contains("504"))
        assertEquals("재시도 한 번", 2, net.count("v1/audio"))
    }

    @Test fun musicPreviewTogglesAndStops() {
        musicSearch(); val a = vm.music.items[0]; val b = vm.music.items[1]
        vm.previewMusic(a); assertEquals(a.id, vm.music.previewId); assertTrue(vm.music.previewLoading); assertEquals(listOf(a.downloadUrl), preview.played)
        preview.emit(UrlPreviewPlayer.State.PLAYING); assertFalse(vm.music.previewLoading); assertEquals(a.id, vm.music.previewId)
        vm.previewMusic(a); assertNull("같은 곡을 다시 누르면 멈춘다", vm.music.previewId)
        vm.previewMusic(a); vm.previewMusic(b); assertEquals(b.id, vm.music.previewId); assertEquals(listOf(a.downloadUrl, a.downloadUrl, b.downloadUrl), preview.played)
        preview.emit(UrlPreviewPlayer.State.ERROR); assertNull(vm.music.previewId); assertTrue(vm.music.message.contains("재생하지 못했습니다"))
        vm.previewMusic(a); preview.emit(UrlPreviewPlayer.State.ENDED); assertNull(vm.music.previewId)
        val stops = preview.stops; vm.go(Screen.MUSIC); vm.previewMusic(a); vm.back(); assertNull("화면을 떠나면 멈춘다", vm.music.previewId); assertTrue(preview.stops > stops); assertEquals(Screen.MEDIA, vm.screen)
    }

    @Test fun addMusicDownloadsProbesAndStoresAttribution() {
        musicSearch(); val m = vm.music.items.first(); var cap = 0L
        vm.freeFetcher = FileFetcher { url, dest, max, _, progress -> fetched += url; cap = max; dest.parentFile?.mkdirs(); dest.writeBytes(ByteArray(300)); progress(50); FetchResult.Ok(300) }
        vm.previewMusic(m)
        vm.addMusic(m); assertNotNull(vm.music.downloadId); assertNull("받기 시작하면 미리듣기는 멈춘다", vm.music.previewId)
        await("받기") { vm.music.downloadId == null }
        assertEquals(SizeCaps.MAX_MUSIC_BYTES, cap); assertEquals(listOf(m.downloadUrl), fetched)
        val c = vm.project.music!!; assertEquals(m.attribution.title, c.label); assertEquals(m.attribution, c.attribution)
        assertTrue(c.uri.contains("/free/ova_") && c.uri.endsWith(".mp3")); assertTrue(File(java.net.URI(c.uri)).isFile)
        assertTrue(vm.music.message.contains("배경 음악으로 넣었습니다"))
        assertTrue(vm.creditDescription().contains(m.attribution.title)); assertTrue(CreditText.onScreen(vm.project.usedAttributions()).contains(m.attribution.author))
    }

    @Test fun addMusicTooBigFailedUnreadableAndCancelled() {
        musicSearch(); val m = vm.music.items.first()
        vm.freeFetcher = FileFetcher { _, _, _, _, _ -> FetchResult.TooBig }
        vm.addMusic(m); await("받기") { vm.music.downloadId == null }
        assertNull(vm.project.music); assertTrue(vm.music.message.contains("20.0MB"))
        vm.freeFetcher = FileFetcher { _, _, _, _, _ -> FetchResult.Failed("내려받는 중 인터넷이 끊겼습니다.") }
        vm.addMusic(m); await("받기") { vm.music.downloadId == null }; assertEquals("내려받는 중 인터넷이 끊겼습니다.", vm.music.message)
        vm.freeFetcher = FileFetcher { _, dest, _, _, _ -> dest.parentFile?.mkdirs(); dest.writeBytes(ByteArray(10)); FetchResult.Ok(10) }
        vm.musicProbe = { _, _ -> null }
        vm.addMusic(m); await("받기") { vm.music.downloadId == null }
        assertTrue(vm.music.message.contains("읽지 못했습니다")); assertNull(vm.project.music); assertTrue(File(app.filesDir, "free").listFiles().orEmpty().isEmpty())
        vm.freeFetcher = FileFetcher { _, _, _, cancelled, _ -> var n = 0; while (!cancelled() && n++ < 2000) Thread.sleep(5); FetchResult.Cancelled }
        vm.addMusic(m); await("받기 시작") { vm.music.downloadId != null }; vm.cancelMusicDownload(); await("취소") { vm.music.downloadId == null }
        assertEquals("받기를 취소했습니다.", vm.music.message); assertNull(vm.project.music)
    }

    @Test fun startNewResetsMusicScreen() {
        musicSearch(); assertTrue(vm.music.items.isNotEmpty()); vm.startNew(); assertEquals(MusicUi(), vm.music)
    }

    // ---------- 최근 작품 삭제 ----------
    private val galleryCalls = ArrayList<Uri>()
    private var galleryResult: GalleryResult = GalleryResult.Deleted

    private fun makeWork(name: String, pid: String, vararg files: File): RecentWork {
        val thumb = File(vm.recentStore.thumbDir, "$name.jpg").also { it.writeBytes(ByteArray(10)) }
        val w = RecentWork(name, "content://media/external/video/media/$name", 1_000L, thumb.absolutePath, pid, files.map { it.absolutePath })
        vm.recentStore.add(w); vm.recents = vm.recentStore.load()
        return w
    }

    private fun file(dir: String, n: String) = File(File(app.filesDir, dir), n).also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(8)) }

    @Test fun askDeleteStartsUncheckedAndCancelKeepsEverything() {
        val w = makeWork("w1", "11111111-1111-1111-1111-111111111111")
        vm.chooseDeleteAlsoGallery(true); vm.askDelete(w)
        assertEquals(w, vm.deleteAsk); assertFalse("체크는 기본 해제", vm.deleteAlsoGallery)
        vm.cancelDelete(); assertNull(vm.deleteAsk); assertEquals(1, vm.recents.size); assertTrue(galleryCalls.isEmpty())
    }

    @Test fun deleteRemovesListEntryThumbRecordingsAndDownloadedFilesButNotTheGalleryVideo() {
        val pid = "22222222-2222-2222-2222-222222222222"
        val f1 = file("free", "wm_1.jpg"); val f2 = file("free", "nasa_2.mp4"); val rec = file("recordings/$pid", "s1_1.wav")
        val w = makeWork("w2", pid, f1, f2)
        vm.askDelete(w); vm.confirmDelete(); assertNull(vm.deleteAsk)
        await("지움") { vm.homeNotice.isNotEmpty() }
        assertEquals("지웠습니다.", vm.homeNotice); assertTrue(vm.recents.isEmpty()); assertTrue(vm.recentStore.load().isEmpty())
        assertFalse(f1.exists()); assertFalse(f2.exists()); assertFalse(rec.exists()); assertFalse(File(app.filesDir, "recordings/$pid").exists()); assertFalse(File(w.thumbPath).exists())
        assertTrue("체크하지 않았으므로 갤러리는 건드리지 않는다", galleryCalls.isEmpty())
    }

    @Test fun deleteWithGalleryCheckedDeletesTheMediaStoreVideo() {
        val w = makeWork("w3", "33333333-3333-3333-3333-333333333333")
        vm.askDelete(w); vm.chooseDeleteAlsoGallery(true); vm.confirmDelete()
        await("지움") { vm.homeNotice.isNotEmpty() }
        assertEquals(listOf(Uri.parse(w.uri)), galleryCalls); assertEquals("지웠습니다.", vm.homeNotice); assertNull(vm.pendingGalleryDelete)
        // 갤러리에서 이미 지워 두었어도(Missing) 정상
        galleryCalls.clear(); galleryResult = GalleryResult.Missing
        val w2 = makeWork("w3b", "44444444-4444-4444-4444-444444444444"); vm.homeNotice = ""
        vm.askDelete(w2); vm.chooseDeleteAlsoGallery(true); vm.confirmDelete(); await("지움") { vm.homeNotice.isNotEmpty() }; assertEquals("지웠습니다.", vm.homeNotice)
    }

    @Test fun deleteNeedingUserConfirmationHandsOverTheIntentSender() {
        val sender = PendingIntent.getBroadcast(app, 0, Intent("gs.test"), PendingIntent.FLAG_IMMUTABLE).intentSender
        galleryResult = GalleryResult.NeedUser(sender)
        val w = makeWork("w4", "55555555-5555-5555-5555-555555555555", file("free", "x.jpg"))
        vm.askDelete(w); vm.chooseDeleteAlsoGallery(true); vm.confirmDelete()
        await("확인 요청") { vm.pendingGalleryDelete != null }
        assertSame(sender, vm.pendingGalleryDelete); assertTrue("목록과 내부 파일은 이미 정리됨", vm.recents.isEmpty())
        vm.onGalleryDeleteResult(true); assertNull(vm.pendingGalleryDelete); assertEquals("지웠습니다.", vm.homeNotice)
        vm.onGalleryDeleteResult(false); assertTrue(vm.homeNotice.contains("그대로 두었습니다"))
    }

    @Test fun galleryFailureIsReportedButTheListEntryIsGone() {
        galleryResult = GalleryResult.Failed("갤러리 영상을 지울 수 없습니다.")
        val w = makeWork("w5", "66666666-6666-6666-6666-666666666666")
        vm.askDelete(w); vm.chooseDeleteAlsoGallery(true); vm.confirmDelete(); await("알림") { vm.homeNotice.isNotEmpty() }
        assertEquals("목록에서는 지웠지만 갤러리 영상을 지울 수 없습니다.", vm.homeNotice); assertTrue(vm.recents.isEmpty())
    }

    @Test fun sharedFilesStayWhileAnotherWorkOrTheCurrentProjectUsesThem() {
        val shared = file("free", "shared.jpg"); val own = file("free", "own.jpg"); val current = file("free", "current.jpg")
        val a = makeWork("wa", "77777777-7777-7777-7777-777777777777", shared, own, current)
        makeWork("wb", "88888888-8888-8888-8888-888888888888", shared)
        vm.project = vm.project.copy(media = listOf(MediaRef("c", Uri.fromFile(current).toString(), false, 0, "c")))
        vm.askDelete(a); vm.confirmDelete(); await("지움") { vm.homeNotice.isNotEmpty() }
        assertFalse(own.exists()); assertTrue("다른 작품이 쓰는 파일은 남긴다", shared.exists()); assertTrue("지금 만드는 작품이 쓰는 파일은 남긴다", current.exists())
        assertEquals(listOf("wb"), vm.recents.map { it.title })
    }

    @Test fun deleteNeverTouchesFilesOutsideTheAppFolderOrOddRecordingNames() {
        val outside = File.createTempFile("gs-outside", ".txt").also { it.writeText("keep") }
        val sibling = File(app.filesDir.parentFile, "keepdir").also { it.mkdirs() }; val sf = File(sibling, "a.txt").also { it.writeText("keep") }
        val w = makeWork("w6", "../keepdir", )
        val tampered = w.copy(files = listOf(outside.absolutePath, sf.absolutePath, File(app.filesDir, "../keepdir/a.txt").path), thumbPath = outside.absolutePath, projectId = "..")
        vm.recentStore.add(tampered); vm.recents = vm.recentStore.load()
        vm.askDelete(tampered); vm.confirmDelete(); await("지움") { vm.homeNotice.isNotEmpty() }
        assertTrue(outside.exists()); assertTrue(sf.exists()); assertTrue(sibling.exists()); assertTrue(File(app.filesDir, "recordings").let { true })
        outside.delete(); sf.delete(); sibling.delete()
    }

    @Test fun currentProjectsRecordingsAreKept() {
        val pid = vm.project.id; val rec = file("recordings/$pid", "s1.wav")
        val w = makeWork("w7", pid); vm.askDelete(w); vm.confirmDelete(); await("지움") { vm.homeNotice.isNotEmpty() }
        assertTrue(rec.exists())
    }

    @Test fun oldRecentFileFormatStillLoads() {
        File(app.filesDir, "recent.json").writeText("""[{"title":"옛 작품","uri":"content://x/1","at":5,"thumb":"/t.jpg"}]""")
        val l = RecentStore(app).load(); assertEquals(1, l.size); assertEquals("", l[0].projectId); assertTrue(l[0].files.isEmpty()); assertEquals("옛 작품", l[0].title)
        RecentStore(app).add(RecentWork("새", "content://x/2", 6, "", "pid", listOf("/a")))
        assertEquals(listOf("새", "옛 작품"), RecentStore(app).load().map { it.title }); assertEquals(listOf("/a"), RecentStore(app).load()[0].files)
    }

    @Test fun homeNoticeClearsItselfAfterAWhile() {
        val w = makeWork("w8", "99999999-9999-9999-9999-999999999999")
        vm.askDelete(w); vm.confirmDelete(); await("알림") { vm.homeNotice.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))      // 시험에서는 시계를 직접 5초 앞으로 돌린다
        assertEquals("", vm.homeNotice)
    }
}
