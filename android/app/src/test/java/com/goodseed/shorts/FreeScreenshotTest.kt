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

private fun fx(name: String): String =
    FreeScreenshotTest::class.java.classLoader!!.getResourceAsStream("free/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

/**
 * 0.4.0 무료 사진·영상 찾기 화면들을 JVM(Robolectric)에서 실제 Compose 로 그려 PNG 로 남기고 터치 칸 크기를 점검한다. 결과: _evidence/0.4.0
 * 주의: 결과 목록은 2026-11-06 실제 위키미디어·Openverse 응답(픽스처)을 그대로 파싱한 것이지만, 시험 환경에는 인터넷이 없으므로
 * 미리보기 그림만은 색 그라데이션 자리표시 그림으로 채웠다(실제 폰에서는 위키미디어의 작은 미리보기 그림이 뜬다).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class FreeScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val out = File("../../_evidence/0.4.0").also { it.mkdirs() }
    private fun path(n: String) = File(out, n).path
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun noStartupNetwork() { AppViewModel.startupChecks = false }

    private val photos: List<FreeMedia> by lazy { WikimediaParser.parse(fx("wikimedia_photo_sunrise_free.json"), FreeKind.PHOTO).items + OpenverseParser.parse(fx("openverse_sunrise.json")).items }
    private val videos: List<FreeMedia> by lazy { WikimediaParser.parse(fx("wikimedia_video_sea_free.json"), FreeKind.VIDEO).items }

    private fun seedThumbs(list: List<FreeMedia>) {
        val palette = listOf(0xFFF6B26B to 0xFF6D4C8F, 0xFF9FC5E8 to 0xFF3D5A80, 0xFFFFD966 to 0xFFE06666, 0xFFA2C4C9 to 0xFF274E13, 0xFFEAD1DC to 0xFF674EA7, 0xFFB6D7A8 to 0xFF1C4587)
        list.forEachIndexed { i, m ->
            val (a, b) = palette[i % palette.size]
            val bmp = Bitmap.createBitmap(250, 300, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawRect(0f, 0f, 250f, 300f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, 300f, a.toInt(), b.toInt(), Shader.TileMode.CLAMP) })
            ThumbCache.put(m.thumbUrl, bmp.asImageBitmap())
        }
    }

    private fun vm(kind: FreeKind = FreeKind.PHOTO, withItems: Boolean = true, message: String = "", otherWaiting: Boolean = false, query: String = ""): AppViewModel {
        val v = AppViewModel(app)
        v.project = Project(scenes = v.sentencesOf("주님은 나의 목자시니 부족함이 없으리로다. 그가 나를 푸른 풀밭에 누이시며 쉴 만한 물가로 인도하시는도다.").mapIndexed { i, t -> Scene(i + 1L, t) })
        val items = if (!withItems) emptyList() else if (kind == FreeKind.PHOTO) photos.take(14) else videos.take(10)
        seedThumbs(items)   // ThumbCache 는 40장까지만 담으므로 화면에 쓰는 것만 채운다
        v.free = FreeUi(kind = kind, query = query, searched = withItems || query.isNotEmpty(), items = items, hidden = if (withItems) 3 else 0, next = if (withItems) 30 else null, message = message, otherWaiting = otherWaiting)
        return v
    }

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

    private fun usedMedia(vararg m: FreeMedia): List<MediaRef> = m.mapIndexed { i, f ->
        MediaRef("m$i", "file:///x/$i.jpg", f.kind == FreeKind.VIDEO, if (f.kind == FreeKind.VIDEO) 12_000 else 0, "${f.attribution.provider.label} · ${f.attribution.author}", MediaOrigin.FREE, f.attribution.author, f.attribution)
    }

    // ---------- 화면 증거 ----------
    @Test fun f1_find_start() = shot("1-find-start.png") { FreeFindScreen(vm(withItems = false)) }

    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun f2_results_photo() = shot("2-results-photo.png") { FreeFindScreen(vm(query = "일출", message = "이용 조건이 맞지 않는 자료 3건은 숨겼습니다.")) }

    @Test @Config(qualifiers = "w360dp-h1400dp-xxhdpi") fun f3_results_video() = shot("3-results-video.png") { FreeFindScreen(vm(FreeKind.VIDEO, query = "바다", message = "이용 조건이 맞지 않는 자료 4건은 숨겼습니다.")) }

    @Test fun f4_preview_ccby() { val v = vm(query = "일출"); v.free = v.free.copy(preview = photos.first { it.attribution.licenseLabel.startsWith("CC BY") && it.downloadUrl.isNotBlank() }); shot("4-preview-ccby.png") { FreeFindScreen(v) } }

    @Test fun f5_preview_cc0() { val v = vm(query = "일출"); v.free = v.free.copy(preview = photos.first { it.attribution.licenseLabel == "CC0 1.0" }); shot("5-preview-cc0.png") { FreeFindScreen(v) } }

    @Test fun f5b_preview_video_webm() { val v = vm(FreeKind.VIDEO, query = "바다"); v.free = v.free.copy(preview = videos.first()); shot("5b-preview-video.png") { FreeFindScreen(v) } }

    @Test fun f6_preview_downloading() {
        val v = vm(query = "일출"); val m = photos.first { it.attribution.licenseLabel.startsWith("CC BY") && it.downloadUrl.isNotBlank() }
        v.free = v.free.copy(preview = m, downloadId = m.id, percent = 47); shot("6-preview-downloading.png") { FreeFindScreen(v) }
    }

    @Test fun f7_error_message() = shot("7-error-offline.png") { FreeFindScreen(vm(withItems = false, query = "하늘", message = "인터넷에 연결되지 않았거나 응답이 없습니다.")) }

    @Test fun f7b_waiting_other_source() = shot("7b-searching-other.png") { FreeFindScreen(vm(query = "구름", otherWaiting = true)) }

    @Test fun f7c_unknown_word_notice() = shot("7c-unknown-word.png") { FreeFindScreen(vm(withItems = false, query = "푸르른 바다", message = "'푸르른'은(는) 영어로 바꾸지 못해 빼고 찾았습니다.\n결과가 없습니다. 다른 낱말을 쓰거나 위의 주제 버튼을 눌러 보세요.")) }

    @Test @Config(qualifiers = "w360dp-h2300dp-xxhdpi") fun f8_media_screen_with_credits() {
        val v = vm()
        val two = listOf(photos.first { it.attribution.licenseLabel.startsWith("CC BY") && it.attribution.provider == FreeProvider.WIKIMEDIA }, photos.first { it.attribution.licenseLabel == "CC0 1.0" })
        v.project = v.project.copy(media = usedMedia(*two.toTypedArray()))
        shot("8-media-screen-credits.png") { MediaScreen(v) }
    }

    @Test fun f9_credit_options_off_warning() {
        val v = vm(); val m = photos.first { it.attribution.licenseLabel.startsWith("CC BY") && it.attribution.provider == FreeProvider.WIKIMEDIA }
        v.project = v.project.copy(media = usedMedia(m), showCredits = false)
        shot("9-credit-off-warning.png") { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) { CreditSection(v) } }
    }

    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi") fun f10_export_done_copy_credits() {
        val v = vm(); val m = photos.first { it.attribution.licenseLabel.startsWith("CC BY") && it.attribution.provider == FreeProvider.WIKIMEDIA }
        v.project = v.project.copy(media = usedMedia(m))
        v.export = ExportUi(Phase.DONE, 100, "저장했습니다.", "content://media/external/video/1", "묵상")
        shot("10-export-done-credit-copy.png") { ExportScreen(v) }
    }

    @Test fun f11_export_idle_credit_note() {
        val v = vm(); val m = photos.first { it.attribution.licenseLabel == "CC0 1.0" }
        v.project = v.project.copy(media = usedMedia(m))
        shot("11-export-idle-credit-note.png") { ExportScreen(v) }
    }

    @Test fun f12_whatsnew() { val v = vm(); v.whatsNew = WhatsNew.notesFor("0.4.0"); shot("12-whatsnew-0.4.0.png") { HomeScreen(v) } }

    @Test fun f13_update_banner_from_0_3_0() {
        val v = AppViewModel(app)
        v.update = UpdateUi(info = UpdateInfo(Semver(0, 4, 0), "v0.4.0", "goodseed-shorts-0.4.0.apk", "https://github.com/x", 43_000_000, "a".repeat(64), UpdateLogic.RELEASES_PAGE, ""), phase = UpdPhase.AVAILABLE)
        shot("13-update-banner-0.4.0.png") { HomeScreen(v) }
    }

    // ---------- 터치 칸 56dp ----------
    @Test fun a1_touch_find_start() = touch("찾기 시작") { FreeFindScreen(vm(withItems = false)) }
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun a2_touch_results() = touch("결과 격자") { FreeFindScreen(vm(query = "일출")) }
    @Test fun a3_touch_preview() { val v = vm(query = "일출"); v.free = v.free.copy(preview = photos.first { it.downloadUrl.isNotBlank() }); touch("미리보기 창") { FreeFindScreen(v) } }
    @Test fun a4_touch_preview_downloading() { val v = vm(query = "일출"); val m = photos.first { it.downloadUrl.isNotBlank() }; v.free = v.free.copy(preview = m, downloadId = m.id, percent = 10); touch("받는 중 창") { FreeFindScreen(v) } }
    @Test @Config(qualifiers = "w360dp-h2300dp-xxhdpi") fun a5_touch_media_with_credits() {
        val v = vm(); v.project = v.project.copy(media = usedMedia(photos.first { it.attribution.provider == FreeProvider.WIKIMEDIA && it.downloadUrl.isNotBlank() }))
        touch("배경 화면(출처 설정)") { MediaScreen(v) }
    }
    @Test @Config(qualifiers = "w360dp-h1000dp-xxhdpi") fun a6_touch_export_done() {
        val v = vm(); v.project = v.project.copy(media = usedMedia(photos.first { it.attribution.provider == FreeProvider.WIKIMEDIA && it.downloadUrl.isNotBlank() }))
        v.export = ExportUi(Phase.DONE, 100, "저장했습니다.", "content://media/external/video/1", "묵상")
        touch("저장 완료 화면") { ExportScreen(v) }
    }

    // ---------- 글자·배지 색 대비 ----------
    @Test fun licenseLabelContrast() {
        assertTrue(Contrast.ratio(Palette.ACCENT_DARK, Palette.CARD) >= 4.5)
        assertTrue(Contrast.ratio(0xFFFFFFFF.toInt(), 0xCC000000.toInt()) >= 4.5)
    }
}
