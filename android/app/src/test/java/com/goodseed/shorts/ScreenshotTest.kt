package com.goodseed.shorts

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.goodseed.shorts.data.*
import com.goodseed.shorts.ui.*
import com.goodseed.shorts.logic.*
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.unit.dp
import com.goodseed.shorts.render.*
import com.goodseed.shorts.logic.*
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 에뮬레이터 없이 JVM(Robolectric 네이티브 그래픽)에서 실제 Compose 화면을 PNG 로 그린다.
 * 실제 폰의 화면과 완전히 같지는 않지만(글꼴 렌더링·기기 차이), 화면 구성·글자 크기·터치 영역을 확인하는 증거로 쓴다.
 * 결과: _evidence/0.2.0 폴더의 PNG
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val out = File("../../_evidence/0.2.0").also { it.mkdirs() }
    private fun path(n: String) = File(out, n).path

    @org.junit.Before fun noStartupNetwork() { AppViewModel.startupChecks = false }   // 시험에서는 시작 때 GitHub 에 접속하지 않는다

    private fun vm(): AppViewModel = AppViewModel(ApplicationProvider.getApplicationContext<Application>())

    private fun picture(name: String, top: Int, bottom: Int): MediaRef {
        val dir = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "shots").also { it.mkdirs() }
        val f = File(dir, "$name.png")
        val b = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(0f, 0f, 540f, 960f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, 960f, top, bottom, Shader.TileMode.CLAMP) })
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return MediaRef(name, f.toURI().toString().replace("file:/", "file:///"), false, 0, "$name.png")
    }

    private val text = "주님은 나의 목자시니 부족함이 없으리로다. 그가 나를 푸른 풀밭에 누이시며 쉴 만한 물가로 인도하시는도다. 내 영혼을 소생시키시고 자기 이름을 위하여 의의 길로 인도하시는도다."

    private fun filledVm(): AppViewModel {
        val v = vm()
        val ss = v.sentencesOf(text)
        val media = listOf(picture("아침바다", 0xFF4A90C2.toInt(), 0xFF173B57.toInt()), picture("푸른들판", 0xFF8CC084.toInt(), 0xFF2F5D3A.toInt()), picture("저녁노을", 0xFFF2A65A.toInt(), 0xFF5B2A4B.toInt()))
        v.project = Project(rawText = text, scenes = ss.mapIndexed { i, t -> Scene(i + 1L, t) }, media = media,
            voice = VoiceChoice("ko-kr-x-1", "한국어 음성 1  (폰 안에서 만듦 · 고품질)", false), speed = Speed.SLOW,
            music = MusicChoice("content://x/music", "잔잔한 피아노.mp3", 180_000))
        return v
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { ShortsTheme { content() } }
        rule.waitForIdle()
        Thread.sleep(400)   // 썸네일 비동기 로딩
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(path(name))
        assertTrue(File(path(name)).length() > 5_000)
    }

    @Test fun s1_home() {
        val v = vm()
        v.recents = listOf(RecentWork("주님은 나의 목자시니 부족함이 없으리로다", "content://x/1", System.currentTimeMillis(), ""), RecentWork("사랑은 오래 참고 온유하며", "content://x/2", System.currentTimeMillis() - 86_400_000, ""))
        shot("1-home.png") { HomeScreen(v) }
    }

    @Test fun s2_paste() {
        val v = vm(); v.project = Project(rawText = text)
        shot("2-paste.png") { PasteScreen(v) }
    }

    @Test fun s3_voice() {
        val v = filledVm()
        v.voices = listOf(VoiceChoice("ko-kr-x-1", "한국어 음성 1  (폰 안에서 만듦 · 고품질)", false), VoiceChoice("ko-kr-x-2", "한국어 음성 2  (폰 안에서 만듦)", false), VoiceChoice("ko-kr-x-3", "한국어 음성 3  (인터넷 필요)", true))
        v.voicesState = "ready"
        shot("3-voice.png") { VoiceScreen(v) }
    }

    @Test fun s4_media() {
        val v = filledVm()
        shot("4-media.png") { MediaScreen(v) }
    }

    @Test fun s5_preview() {
        val v = filledVm()
        shot("5-preview.png") { PreviewScreen(v) }
    }

    @Test fun s5b_preview_gowun_large() {
        val v = filledVm()
        v.project = v.project.copy(font = SubFont.GOWUN, scenes = v.project.scenes.mapIndexed { i, s -> if (i == 0) s.copy(size = SubSize.XLARGE) else s })
        shot("5b-preview-gowun-xlarge.png") { PreviewScreen(v) }
    }

    @Test fun s6_export_idle() {
        val v = filledVm()
        shot("6-export-idle.png") { ExportScreen(v) }
    }

    @Test fun s6b_export_progress() {
        val v = filledVm(); v.export = ExportUi(Phase.RENDER, 42, "영상을 만드는 중입니다. 화면을 켜 둔 채 기다려 주세요.")
        shot("6b-export-progress.png") { ExportScreen(v) }
    }

    @Test fun s6c_export_done() {
        val v = filledVm(); v.export = ExportUi(Phase.DONE, 100, "저장했습니다.", "content://x/1", "제목")
        shot("6c-export-done.png") { ExportScreen(v) }
    }

    @Test fun s7_about() {
        val v = vm()
        rule.setContent { ShortsTheme { HomeScreen(v) } }
        rule.onAllNodes(androidx.compose.ui.test.hasScrollAction()).onFirst().performScrollToNode(androidx.compose.ui.test.hasText("굿씨드AI연구소 · 정보와 라이선스"))
        rule.onNodeWithText("굿씨드AI연구소 · 정보와 라이선스").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(path("7-about-licenses.png"))
        assertTrue(File(path("7-about-licenses.png")).length() > 5_000)
    }

    @Test fun s8_scene_editor() {
        val v = filledVm()
        rule.setContent { ShortsTheme { PreviewScreen(v) } }
        rule.waitForIdle()
        Thread.sleep(400)
        rule.mainClock.autoAdvance = false   // 글자 입력칸의 커서 깜빡임이 끝없는 애니메이션이라 시간을 직접 진행시킨다
        rule.onNodeWithText("주님은 나의 목자시니 부족함이 없으리로다.").performClick()
        rule.mainClock.advanceTimeBy(1000)
        rule.onRoot().captureRoboImage(path("8-scene-editor.png"))
        assertTrue(File(path("8-scene-editor.png")).length() > 5_000)
    }

    // ---------- 0.2.0: 템플릿·새 디자인·업데이트 ----------
    private val demoTitle = "오늘의 묵상"
    private val demoSource = "시편 23:1-3"

    private fun templateVm(id: String, withPhotos: Boolean): AppViewModel {
        val v = filledVm()
        val t = Templates.byId(id)
        v.project = v.project.copy(templateId = id, font = t.font, headerTitle = demoTitle, headerSource = demoSource,
            media = if (withPhotos) v.project.media else emptyList())
        return v
    }

    private fun previewOf(id: String, photos: Boolean = false) {
        val v = templateVm(id, photos)
        shot("5-preview-$id${if (photos) "-photo" else ""}.png") { PreviewScreen(v) }
    }

    @Test fun t1_preview_dawn() = previewOf("dawn")
    @Test fun t2_preview_verse_card() = previewOf("verse_card")
    @Test fun t3_preview_film() = previewOf("film")
    @Test fun t4_preview_minimal() = previewOf("minimal")
    @Test fun t5_preview_night() = previewOf("night")
    @Test fun t6_preview_sunset() = previewOf("sunset")
    @Test fun t7_preview_verse_focus() = previewOf("verse_focus")
    @Test fun t8_preview_quote() = previewOf("quote")
    @Test fun t9_preview_dawn_with_photos() = previewOf("dawn", photos = true)

    @Test fun n1_home_new() {
        val v = vm()
        v.recents = listOf(RecentWork("주님은 나의 목자시니 부족함이 없으리로다", "content://x/1", System.currentTimeMillis(), ""))
        shot("1-home-new.png") { HomeScreen(v) }
    }

    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun n1b_home_full_height() {
        val v = vm()
        v.recents = listOf(RecentWork("주님은 나의 목자시니 부족함이 없으리로다", "content://x/1", System.currentTimeMillis(), ""))
        shot("1b-home-full.png") { HomeScreen(v) }
    }

    @Test fun n3_template_pick() {
        val v = filledVm(); v.project = v.project.copy(headerTitle = demoTitle, headerSource = demoSource, templateId = "night")
        shot("3-template-pick.png") { TemplateScreen(v) }
    }

    @Test @Config(qualifiers = "w360dp-h2300dp-xxhdpi") fun n3b_template_pick_all() {
        val v = filledVm(); v.project = v.project.copy(headerTitle = demoTitle, headerSource = demoSource, templateId = "night")
        shot("3b-template-pick-all.png") { TemplateScreen(v) }
    }

    private val info = UpdateInfo(Semver(0, 3, 0), "v0.3.0", "goodseed-shorts-0.3.0.apk", "https://github.com/x", 42_000_000, "a".repeat(64), UpdateLogic.RELEASES_PAGE, "")

    private fun updateShot(name: String, ui: UpdateUi) {
        val v = vm(); v.update = ui
        shot(name) { HomeScreen(v) }
    }

    @Test fun u1_banner() = updateShot("u1-update-banner.png", UpdateUi(info = info, phase = UpdPhase.AVAILABLE))
    @Test fun u2_progress() = updateShot("u2-update-progress.png", UpdateUi(info = info, phase = UpdPhase.DOWNLOADING, percent = 63, message = "새 버전을 받는 중입니다…"))
    @Test fun u3_ready() = updateShot("u3-update-ready.png", UpdateUi(info = info, phase = UpdPhase.READY, percent = 100, message = "설치 화면이 열립니다. 화면의 [업데이트] 또는 [설치]를 눌러 주세요."))
    @Test fun u4_error() = updateShot("u4-update-error.png", UpdateUi(info = info, phase = UpdPhase.ERROR, message = "받은 파일이 올바르지 않아 설치하지 않았습니다. 잠시 뒤 다시 시도하거나 직접 받아 주세요."))
    @Test fun u5_howto() = updateShot("u5-update-howto.png", UpdateUi(info = info, phase = UpdPhase.AVAILABLE, showHowTo = true))
    @Test fun u6_permission() = updateShot("u6-update-permission.png", UpdateUi(info = info, phase = UpdPhase.NEED_PERMISSION))
    @Test fun u7_whatsnew() {
        val v = vm(); v.whatsNew = WhatsNew.notesFor("0.2.0")
        shot("u7-whatsnew.png") { HomeScreen(v) }
    }
    @Test fun u8_manual_message() = updateShot("u8-update-latest-message.png", UpdateUi(message = "지금 쓰는 버전(0.2.0)이 가장 새 버전입니다."))

    // ---------- 화면 규칙 점검 ----------
    private fun assertTouchTargets(name: String) {
        val min = with(rule.density) { 56.dp.toPx() } - 1f
        val bad = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.size.width < min || it.size.height < min }
        assertTrue("$name: 56dp 미만 터치 칸 ${bad.map { "${it.size.width}x${it.size.height}" }}", bad.isEmpty())
    }

    private fun touch(name: String, content: @Composable () -> Unit) {
        rule.setContent { ShortsTheme { content() } }
        rule.waitForIdle(); Thread.sleep(300); rule.waitForIdle()
        assertTouchTargets(name)
    }

    @Test fun a1_touch_home() { val v = vm(); v.update = UpdateUi(info = info, phase = UpdPhase.AVAILABLE); touch("홈") { HomeScreen(v) } }
    @Test fun a2_touch_paste() { val v = vm(); v.project = Project(rawText = text); touch("붙여넣기") { PasteScreen(v) } }
    @Test fun a3_touch_template() { val v = filledVm(); touch("템플릿") { TemplateScreen(v) } }
    @Test fun a4_touch_media() { val v = filledVm(); touch("배경") { MediaScreen(v) } }
    @Test fun a5_touch_preview() { val v = filledVm(); touch("미리보기") { PreviewScreen(v) } }
    @Test fun a6_touch_export() { val v = filledVm(); touch("저장") { ExportScreen(v) } }
    @Test fun a7_touch_voice() { val v = filledVm(); v.voices = listOf(VoiceChoice("a", "한국어 음성 1", false)); v.voicesState = "ready"; touch("목소리") { VoiceScreen(v) } }
    @Test fun a8_touch_update_dialog() { val v = vm(); v.update = UpdateUi(info = info, phase = UpdPhase.AVAILABLE, showHowTo = true); touch("업데이트 창") { HomeScreen(v) } }

    // ---------- 템플릿이 실제로 그려지는지(픽셀) ----------
    private fun distinctColors(b: Bitmap, step: Int = 7): Int {
        val set = HashSet<Int>()
        var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { set += b.getPixel(x, y); x += step }; y += step }
        return set.size
    }

    private fun diffPixels(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until a.height step 3) for (x in 0 until a.width step 3) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun r1_backgroundsAreRealArtAndDiffer() {
        val arts = Templates.ALL.map { it to BackgroundArt.draw(it, 270, 480) }
        for ((t, b) in arts) {
            assertEquals(270, b.width); assertEquals(480, b.height)
            assertTrue("${t.id} 배경이 단색이다", distinctColors(b) > 20)
        }
        for (i in arts.indices) for (j in i + 1 until arts.size) assertTrue("${arts[i].first.id}/${arts[j].first.id} 배경이 같다", diffPixels(arts[i].second, arts[j].second) > 500)
    }

    @Test fun r2_patternsAddSomethingOnTopOfTheGradient() {
        for (t in Templates.ALL.filter { it.pattern != Pattern.NONE }) {
            val plain = BackgroundArt.draw(t.copy(pattern = Pattern.NONE), 270, 480)
            val full = BackgroundArt.draw(t, 270, 480)
            assertTrue("${t.id} 무늬가 보이지 않는다", diffPixels(plain, full) > 80)
        }
    }

    @Test fun r3_subtitleIsDrawnForEveryTemplate() {
        val lines = listOf("주님은 나의 목자시니", "부족함이 없으리로다.")
        for (t in Templates.ALL) {
            val bare = FrameComposer.compose(app, t, null, emptyList(), t.font, SubSize.NORMAL, "", "", 360)
            val with = FrameComposer.compose(app, t, null, lines, t.font, SubSize.NORMAL, "", "", 360)
            assertEquals(360, with.width); assertEquals(640, with.height)
            assertTrue("${t.id} 자막이 그려지지 않았다", diffPixels(bare, with) > 100)
            // 자막이 자기 위치(anchorY) 근처 띠 안에만 그려진다
            val cy = ((1f - t.subtitle.anchorY) / 2f * 640).toInt()
            var minY = 640; var maxY = 0
            for (y in 0 until 640) for (x in 0 until 360 step 2) if (bare.getPixel(x, y) != with.getPixel(x, y)) { minY = minOf(minY, y); maxY = maxOf(maxY, y); break }
            assertTrue("${t.id} 자막 위치 y=$minY..$maxY (기대 중심 $cy)", minY >= cy - 120 && maxY <= cy + 120)
        }
    }

    @Test fun r4_headerShowsOnlyWhenTemplateHasHeader() {
        for (t in Templates.ALL) {
            val none = FrameComposer.compose(app, t, null, emptyList(), t.font, SubSize.NORMAL, "", "", 360)
            val given = FrameComposer.compose(app, t, null, emptyList(), t.font, SubSize.NORMAL, demoTitle, demoSource, 360)
            val d = diffPixels(none, given)
            if (t.header != null) assertTrue("${t.id} 제목이 그려지지 않았다($d)", d > 60) else assertEquals("${t.id} 제목 줄이 없는 템플릿인데 그려졌다", 0, d)
        }
    }

    @Test fun r5_outlineAndShadowChangeThePixels() {
        val film = Templates.byId("film"); val lines = listOf("고요한 시간")
        val plain = film.copy(subtitle = film.subtitle.copy(outlineColor = 0, outlinePx = 0f, shadowColor = 0, shadowRadius = 0f))
        val a = FrameComposer.compose(app, plain, null, lines, film.font, SubSize.XLARGE, "", "", 540)
        val b = FrameComposer.compose(app, film, null, lines, film.font, SubSize.XLARGE, "", "", 540)
        assertTrue("외곽선·그림자가 보이지 않는다", diffPixels(a, b) > 300)
    }

    @Test fun r6_photoBackgroundReplacesTheGeneratedOne() {
        val t = Templates.byId("dawn")
        val photo = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(0xFFFF0000.toInt()) }
        val withPhoto = FrameComposer.compose(app, t, photo, emptyList(), t.font, SubSize.NORMAL, "", "", 180)
        assertEquals(0xFFFF0000.toInt(), withPhoto.getPixel(90, 160))
        val art = FrameComposer.compose(app, t, null, emptyList(), t.font, SubSize.NORMAL, "", "", 180)
        assertNotEquals(0xFFFF0000.toInt(), art.getPixel(90, 160))
    }

    @Test fun r7_endCardHasTextOnTemplateBackground() {
        for (t in Templates.ALL.filter { it.endCard != null }) {
            val card = FrameComposer.endCard(app, t, t.font, "시편 23:1-3", "오늘의 묵상")
            assertEquals(1080, card.width); assertEquals(1920, card.height)
            val bare = BackgroundArt.draw(t, 1080, 1920)
            assertTrue("${t.id} 끝 화면에 글자가 없다", diffPixels(bare, card) > 300)
        }
    }

    // ---------- 합성 연결(빌드된 Composition 의 구조) ----------
    @Test fun c1_compositionWiresTemplateHeaderMotionAndEndCard() {
        val dir = File(app.cacheDir, "ct").also { it.mkdirs() }
        val wav = File(dir, "v.wav").also { it.writeBytes(ByteArray(44)) }
        val times = Timing.scenes(listOf(2_000_000L, 3_000_000L))
        val photo = picture("p", 0xFF123456.toInt(), 0xFF654321.toInt())
        fun input(t: VideoTemplate, endUs: Long, end: File?) = RenderInput(
            listOf(RenderScene("첫째 문장입니다.", SubSize.NORMAL, photo.uri, false, 0, wav), RenderScene("둘째 문장입니다.", SubSize.NORMAL, null, false, 0, wav)),
            times, t.font, null, 0, 0.5f, t, "제목", "출처", end, endUs)
        val night = Templates.byId("night")           // 움직임 있음 · 제목 줄 없음 · 끝 화면 없음
        val c1 = ShortsRenderer.build(app, input(night, 0, null), dir)
        val v1 = c1.sequences[0].editedMediaItems
        assertEquals(2, v1.size)
        assertTrue("사진 장면에 움직임 효과가 없다", v1[0].effects.videoEffects.any { it is KenBurnsEffect })
        assertFalse("배경 없는 장면(기본 배경)에는 움직임을 걸지 않는다", v1[1].effects.videoEffects.any { it is KenBurnsEffect })

        val focus = Templates.byId("verse_focus")     // 제목 줄 + 끝 화면 있음
        val endFile = FrameComposer.saveEndCard(app, focus, focus.font, "출처", "제목", dir)
        val endUs = focus.endCard!!.durationMs * 1000L
        val c2 = ShortsRenderer.build(app, input(focus, endUs, endFile), dir)
        val v2 = c2.sequences[0].editedMediaItems
        assertEquals("끝 화면 항목이 영상 줄 끝에 붙어야 한다", 3, v2.size)
        assertEquals(endUs, v2.last().durationUs)
        assertTrue(endFile.length() > 1000)

        val minimal = Templates.byId("minimal")       // 움직임 없음
        val c3 = ShortsRenderer.build(app, input(minimal, 0, null), dir)
        assertFalse(c3.sequences[0].editedMediaItems[0].effects.videoEffects.any { it is KenBurnsEffect })
    }

    @Test fun c2_chooseTemplateFollowsFontAndRemembers() {
        val v = vm()
        v.startNew("film")
        assertEquals("film", v.project.templateId); assertEquals(SubFont.GOWUN, v.project.font)
        v.chooseTemplate("minimal")
        assertEquals(SubFont.PRETENDARD, v.project.font)
        v.setFont(SubFont.GOWUN); assertEquals(SubFont.GOWUN, v.project.font)   // 글꼴만 따로 바꿀 수 있다
        v.setHeader("제목", "출처"); assertEquals("출처", v.project.headerSource)
        v.startNew(); assertEquals("minimal", v.project.templateId)             // 마지막으로 쓴 템플릿 기억
        v.startNew("bogus"); assertEquals(Templates.DEFAULT.id, v.project.templateId)
    }
}
