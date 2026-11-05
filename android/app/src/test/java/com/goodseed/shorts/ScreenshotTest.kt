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
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.goodseed.shorts.data.*
import com.goodseed.shorts.render.SubFont
import com.goodseed.shorts.render.SubSize
import com.goodseed.shorts.ui.*
import org.junit.Assert.assertTrue
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
 * 결과: _evidence/0.1.0 폴더의 PNG
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val out = File("../../_evidence/0.1.0").also { it.mkdirs() }
    private fun path(n: String) = File(out, n).path

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
}
