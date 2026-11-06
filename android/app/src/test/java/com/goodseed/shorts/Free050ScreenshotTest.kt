package com.goodseed.shorts

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.goodseed.shorts.data.*
import com.goodseed.shorts.logic.*
import com.goodseed.shorts.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

private fun f(name: String): String =
    Free050ScreenshotTest::class.java.classLoader!!.getResourceAsStream("free/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

/**
 * 0.5.0 새 화면(최근 작품 [삭제]·확인 창·출처 탭·NASA·명화·무료 음악 찾기)을 JVM(Robolectric)에서 실제 Compose 로 그려 PNG 로 남긴다. 결과: _evidence/0.5.0
 * 목록의 글자 내용은 2026-11-06 실제 API 응답(픽스처)을 그대로 파싱한 것이고, 미리보기 그림만 색 그라데이션 자리표시 그림이다(시험 환경에 인터넷 없음).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class Free050ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val out = File("../../_evidence/0.5.0").also { it.mkdirs() }
    private fun path(n: String) = File(out, n).path
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun noStartupNetwork() { AppViewModel.startupChecks = false }

    private val nasaPhotos by lazy { NasaParser.parse(f("nasa_image_sunrise.json"), FreeKind.PHOTO).items }
    private val nasaVideos by lazy { NasaParser.parse(f("nasa_video_earth.json"), FreeKind.VIDEO).items }
    private val metWorks by lazy { listOf(436535L, 435858L, 211316L, 931445L, 15248L).mapNotNull { MetParser.parseObject(f("met_object_$it.json")) } }
    private val wiki by lazy { WikimediaParser.parse(f("wikimedia_photo_sunrise_free.json"), FreeKind.PHOTO).items }
    private val songs by lazy { OpenverseAudioParser.parse(f("openverse_audio_piano.json")).items }

    private fun seed(list: List<FreeMedia>) {
        val palette = listOf(0xFF0B3D91 to 0xFFFC3D21, 0xFF1C1C3A to 0xFF7B68EE, 0xFF9FC5E8 to 0xFF274E13, 0xFFEAD1DC to 0xFF674EA7, 0xFFF6B26B to 0xFF6D4C8F, 0xFFB6D7A8 to 0xFF1C4587)
        list.forEachIndexed { i, m ->
            val (a, b) = palette[i % palette.size]
            val bmp = Bitmap.createBitmap(250, 300, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawRect(0f, 0f, 250f, 300f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, 300f, a.toInt(), b.toInt(), Shader.TileMode.CLAMP) })
            ThumbCache.put(m.thumbUrl, bmp.asImageBitmap())
        }
    }

    private fun vm(): AppViewModel {
        val v = AppViewModel(app)
        v.project = Project(scenes = v.sentencesOf("주님은 나의 목자시니 부족함이 없으리로다. 그가 나를 푸른 풀밭에 누이시며 쉴 만한 물가로 인도하시는도다.").mapIndexed { i, t -> Scene(i + 1L, t) })
        return v
    }

    private fun findVm(kind: FreeKind, tab: FreeTab, items: List<FreeMedia>, query: String, message: String = "", next: Map<FreeSource, Int> = mapOf(FreeSource.WIKIMEDIA to 30)): AppViewModel {
        val v = vm(); seed(items)
        v.free = FreeUi(kind = kind, tab = tab, query = query, searched = true, items = items, hidden = 2, next = next, message = message)
        return v
    }

    private fun musicVm(items: List<FreeMusic> = songs.take(5), query: String = "잔잔한 피아노", message: String = ""): AppViewModel {
        val v = vm(); v.music = MusicUi(query = query, searched = true, items = items, message = message); return v
    }

    private fun works() = listOf(
        RecentWork("주님은 나의 목자시니 부족함이 없으리로다", "content://media/1", System.currentTimeMillis() - 3_600_000, "/none/1.jpg"),
        RecentWork("사랑은 오래 참고 사랑은 온유하며", "content://media/2", System.currentTimeMillis() - 86_400_000, "/none/2.jpg"),
        RecentWork("여호와는 나의 빛이요 나의 구원이시니", "content://media/3", System.currentTimeMillis() - 172_800_000, "/none/3.jpg"),
    )

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { ShortsTheme { content() } }
        rule.waitForIdle(); Thread.sleep(300); rule.waitForIdle()
        rule.onRoot().captureRoboImage(path(name))
        assertTrue(File(path(name)).length() > 5_000)
    }

    private fun touch(name: String, content: @Composable () -> Unit) {
        rule.setContent { ShortsTheme { content() } }
        rule.waitForIdle(); Thread.sleep(300); rule.waitForIdle()
        val min = with(rule.density) { 56.dp.toPx() } - 1f
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val bad = nodes.filter { it.size.width < min || it.size.height < min }
        assertTrue("$name: 56dp 미만 터치 칸 ${bad.map { "${it.size.width}x${it.size.height}" }}", bad.isEmpty())
        assertTrue("$name: 누를 수 있는 칸이 없다", nodes.isNotEmpty())
    }

    private fun usedMedia(vararg m: FreeMedia): List<MediaRef> = m.mapIndexed { i, x -> MediaRef("m$i", "file:///x/$i.jpg", x.kind == FreeKind.VIDEO, 0, "x", MediaOrigin.FREE, x.attribution.author, x.attribution) }

    // ---------- 삭제 ----------
    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun s1_home_recents_have_delete_button() { val v = vm(); v.recents = works(); shot("1-home-recent-delete-button.png") { HomeScreen(v) } }

    @Test fun s2_delete_dialog_default() { val v = vm(); v.recents = works(); v.askDelete(v.recents.first()); shot("2-delete-dialog.png") { HomeScreen(v) } }

    @Test fun s2b_delete_dialog_gallery_checked() { val v = vm(); v.recents = works(); v.askDelete(v.recents.first()); v.chooseDeleteAlsoGallery(true); shot("2b-delete-dialog-gallery-checked.png") { HomeScreen(v) } }

    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun s3_delete_done_notice() { val v = vm(); v.recents = works().drop(1); v.homeNotice = "지웠습니다."; shot("3-delete-done.png") { HomeScreen(v) } }

    // ---------- 출처 탭 · NASA · 명화 ----------
    @Test fun s4_find_tabs_all() = shot("4-find-tabs-all.png") { FreeFindScreen(findVm(FreeKind.PHOTO, FreeTab.ALL, emptyList(), "")) }

    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun s5_find_nasa_photos() = shot("5-find-nasa.png") { FreeFindScreen(findVm(FreeKind.PHOTO, FreeTab.NASA, nasaPhotos.take(10), "일출", "이용 조건이 맞지 않는 자료 3건은 숨겼습니다.", mapOf(FreeSource.NASA to 2))) }

    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun s5b_find_nasa_videos() = shot("5b-find-nasa-video.png") { FreeFindScreen(findVm(FreeKind.VIDEO, FreeTab.NASA, nasaVideos.take(8), "지구", "", mapOf(FreeSource.NASA to 2))) }

    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun s6_find_met_paintings() = shot("6-find-met.png") { FreeFindScreen(findVm(FreeKind.PHOTO, FreeTab.MET, metWorks, "목자", "", mapOf(FreeSource.MET to MetParser.cursor(16, false)))) }

    @Test @Config(qualifiers = "w360dp-h2200dp-xxhdpi") fun s6b_find_all_mixed() {
        val mixed = FreeMerge.interleave(listOf(wiki.take(3), nasaPhotos.take(3), metWorks.take(3)))
        shot("6b-find-all-mixed.png") { FreeFindScreen(findVm(FreeKind.PHOTO, FreeTab.ALL, mixed, "목자")) }
    }

    @Test fun s7_preview_nasa() { val v = findVm(FreeKind.PHOTO, FreeTab.NASA, nasaPhotos.take(6), "일출"); v.free = v.free.copy(preview = nasaPhotos.first()); shot("7-preview-nasa.png") { FreeFindScreen(v) } }

    @Test fun s7b_preview_nasa_video() { val v = findVm(FreeKind.VIDEO, FreeTab.NASA, nasaVideos.take(6), "지구"); v.free = v.free.copy(preview = nasaVideos.first()); shot("7b-preview-nasa-video.png") { FreeFindScreen(v) } }

    @Test fun s7c_preview_met() { val v = findVm(FreeKind.PHOTO, FreeTab.MET, metWorks, "목자"); v.free = v.free.copy(preview = metWorks.first()); shot("7c-preview-met.png") { FreeFindScreen(v) } }

    // ---------- 무료 음악 ----------
    @Test fun m1_music_start() = shot("8-music-start.png") { MusicFindScreen(musicVm(emptyList(), "")) }

    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun m2_music_results_playing_and_downloading() {
        val v = musicVm(); v.music = v.music.copy(previewId = songs[0].id, previewLoading = false, downloadId = songs[2].id, percent = 38)
        shot("9-music-results.png") { MusicFindScreen(v) }
    }

    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun m3_music_results_idle_with_current() {
        val v = musicVm(); val s = songs[1]
        v.project = v.project.copy(music = MusicChoice("file:///x/a.mp3", s.attribution.title, s.durationMs, s.attribution)); v.music = v.music.copy(message = "배경 음악으로 넣었습니다: ${s.attribution.title}")
        shot("9b-music-results-chosen.png") { MusicFindScreen(v) }
    }

    @Test fun m4_music_error() = shot("9c-music-error.png") { MusicFindScreen(musicVm(emptyList(), "기타", "음악 서버(Openverse)에서 가져오지 못했습니다. 자료 서버가 지금 응답하지 못합니다(코드 504). 잠시 뒤 다시 해 보세요.")) }

    @Test @Config(qualifiers = "w360dp-h2500dp-xxhdpi") fun m5_media_screen_with_music_button_and_credits() {
        val v = vm(); val s = songs[0]
        v.project = v.project.copy(media = usedMedia(nasaPhotos.first(), metWorks.first()), music = MusicChoice("file:///x/a.mp3", s.attribution.title, s.durationMs, s.attribution))
        shot("10-media-music-button-credits.png") { MediaScreen(v) }
    }

    @Test fun m6_whatsnew() { val v = vm(); v.whatsNew = WhatsNew.notesFor("0.5.0"); shot("11-whatsnew-0.5.0.png") { HomeScreen(v) } }

    @Test fun m7_update_banner_from_0_4_0() {
        val v = AppViewModel(app)
        v.update = UpdateUi(info = UpdateInfo(Semver(0, 5, 0), "v0.5.0", "goodseed-shorts-0.5.0.apk", "https://github.com/x", 43_000_000, "a".repeat(64), UpdateLogic.RELEASES_PAGE, ""), phase = UpdPhase.AVAILABLE)
        shot("12-update-banner-0.5.0.png") { HomeScreen(v) }
    }

    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun m8_credit_section_with_nasa_met_music() {
        val v = vm(); val s = songs[0]
        v.project = v.project.copy(media = usedMedia(nasaPhotos.first(), metWorks.first()), music = MusicChoice("file:///x/a.mp3", s.attribution.title, s.durationMs, s.attribution))
        shot("13-credit-section.png") { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) { CreditSection(v) } }
    }

    // ---------- 터치 칸 56dp ----------
    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun t1_home_with_recents() { val v = vm(); v.recents = works(); touch("홈(최근 작품)") { HomeScreen(v) } }
    @Test fun t2_delete_dialog() { val v = vm(); v.recents = works(); v.askDelete(v.recents.first()); touch("삭제 확인 창") { HomeScreen(v) } }
    @Test fun t2b_delete_dialog_checked() { val v = vm(); v.recents = works(); v.askDelete(v.recents.first()); v.chooseDeleteAlsoGallery(true); touch("삭제 확인 창(체크)") { HomeScreen(v) } }
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun t3_find_nasa() = touch("찾기(NASA 탭)") { FreeFindScreen(findVm(FreeKind.PHOTO, FreeTab.NASA, nasaPhotos.take(10), "일출")) }
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun t4_find_met() = touch("찾기(명화 탭)") { FreeFindScreen(findVm(FreeKind.PHOTO, FreeTab.MET, metWorks, "목자")) }
    @Test fun t5_preview_nasa() { val v = findVm(FreeKind.PHOTO, FreeTab.NASA, nasaPhotos.take(6), "일출"); v.free = v.free.copy(preview = nasaPhotos.first()); touch("NASA 미리보기") { FreeFindScreen(v) } }
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun t6_music_results() { val v = musicVm(); v.music = v.music.copy(previewId = songs[0].id, downloadId = songs[2].id, percent = 10); touch("음악 찾기") { MusicFindScreen(v) } }
    @Test fun t7_music_start() = touch("음악 찾기 시작") { MusicFindScreen(musicVm(emptyList(), "")) }
    @Test @Config(qualifiers = "w360dp-h2500dp-xxhdpi") fun t8_media_screen() {
        val v = vm(); v.project = v.project.copy(media = usedMedia(nasaPhotos.first()))
        touch("배경 화면(음악 찾기 버튼)") { MediaScreen(v) }
    }
}
