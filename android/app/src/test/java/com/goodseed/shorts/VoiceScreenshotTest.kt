package com.goodseed.shorts

import android.app.Application
import androidx.compose.runtime.Composable
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

/** 0.3.0 내 목소리 화면들을 JVM(Robolectric)에서 실제 Compose 로 그려 PNG 로 남기고, 터치 칸 크기를 점검한다. 결과: _evidence/0.3.0 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class VoiceScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val out = File("../../_evidence/0.3.0").also { it.mkdirs() }
    private fun path(n: String) = File(out, n).path
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun noStartupNetwork() { AppViewModel.startupChecks = false }

    private val text = "주님은 나의 목자시니 부족함이 없으리로다. 그가 나를 푸른 풀밭에 누이시며 쉴 만한 물가로 인도하시는도다. 내 영혼을 소생시키시고 자기 이름을 위하여 의의 길로 인도하시는도다. 내가 사망의 음침한 골짜기로 다닐지라도 해를 두려워하지 않을 것은 주께서 나와 함께 하심이라. 주의 지팡이와 막대기가 나를 안위하시나이다."

    private fun vm(recorded: Set<Int> = emptySet(), mode: VoiceMode = VoiceMode.MINE): AppViewModel {
        val v = AppViewModel(app)
        val ss = v.sentencesOf(text)
        v.project = Project(rawText = text, voiceMode = mode, scenes = ss.mapIndexed { i, t ->
            Scene(i + 1L, t, myVoice = if (i in recorded) "/data/rec/s$i.wav" else null, myVoiceMs = if (i in recorded) 2800L + i * 700 else 0)
        }, voice = VoiceChoice("ko-kr-x-1", "한국어 음성 1  (폰 안에서 만듦 · 고품질)", false))
        v.voices = listOf(VoiceChoice("ko-kr-x-1", "한국어 음성 1  (폰 안에서 만듦 · 고품질)", false), VoiceChoice("ko-kr-x-2", "한국어 음성 2  (폰 안에서 만듦)", false))
        v.voicesState = "ready"
        return v
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { ShortsTheme { content() } }
        rule.waitForIdle(); Thread.sleep(300); rule.waitForIdle()
        rule.onRoot().captureRoboImage(path(name))
        assertTrue(File(path(name)).length() > 5_000)
    }

    private fun assertTouchTargets(name: String) {
        val min = with(rule.density) { 56.dp.toPx() } - 1f
        val bad = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.size.width < min || it.size.height < min }
        assertTrue("$name: 56dp 미만 터치 칸 ${bad.map { "${it.size.width}x${it.size.height}" }}", bad.isEmpty())
        assertTrue("$name: 누를 수 있는 칸이 없다", rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty())
    }

    private fun touch(name: String, content: @Composable () -> Unit) {
        rule.setContent { ShortsTheme { content() } }
        rule.waitForIdle(); Thread.sleep(300); rule.waitForIdle()
        assertTouchTargets(name)
    }

    // ---------- 화면 증거 ----------
    @Test fun v1_voiceChoose_ai() = shot("1-voice-choose-ai.png") { VoiceScreen(vm(mode = VoiceMode.AI)) }
    @Test fun v2_voiceChoose_mine_empty() = shot("2-voice-mine-empty.png") { VoiceScreen(vm()) }

    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun v3_voiceChoose_mine_partial() = shot("3-voice-mine-partial.png") { VoiceScreen(vm(setOf(0, 1))) }

    @Test fun v4_record_ready() { val v = vm(setOf(0, 1)); v.rec = RecUi(index = 2); shot("4-record-ready.png") { RecordScreen(v) } }

    @Test fun v5_record_recording() {
        val v = vm(setOf(0, 1)); v.rec = RecUi(index = 2, recording = true); v.recLevel = 0.18f; v.recMillis = 4300
        shot("5-record-recording.png") { RecordScreen(v) }
    }

    @Test fun v6_record_done() { val v = vm(setOf(0, 1, 2)); v.rec = RecUi(index = 2); shot("6-record-done.png") { RecordScreen(v) } }

    @Test fun v6b_record_playing() { val v = vm(setOf(0, 1, 2)); v.rec = RecUi(index = 2, playing = true); shot("6b-record-playing.png") { RecordScreen(v) } }

    @Test fun v7_record_quiet_hint() {
        val v = vm(setOf(0, 1, 2)); v.rec = RecUi(index = 2, warn = true, message = "목소리가 작아 소리를 키웠습니다. 폰을 입 가까이에 두고 다시 녹음하면 더 깨끗합니다.")
        shot("7-record-hint.png") { RecordScreen(v) }
    }

    @Test fun v7b_record_last_sentence() { val v = vm(setOf(0, 1, 2, 3)); v.rec = RecUi(index = 4); shot("7b-record-last.png") { RecordScreen(v) } }

    @Test fun v8_mic_permission() { val v = vm(setOf(0)); v.rec = RecUi(index = 1, micDenied = true); shot("8-mic-permission.png") { RecordScreen(v) } }

    @Test fun v9_import_busy() { val v = vm(); v.voiceImport = ImportUi(true, "녹음 파일을 읽는 중입니다. 길면 조금 걸립니다…"); shot("9-import-busy.png") { VoiceScreen(v) } }

    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun v9b_import_error() {
        val v = vm(); v.voiceImport = ImportUi(false, "이 파일 형식은 읽을 수 없습니다. m4a, mp3, wav, aac 같은 녹음 파일을 골라 주세요.", true)
        shot("9b-import-error.png") { VoiceScreen(v) }
    }

    private fun alignVm(confident: Boolean, message: String = "", playing: Int = -1): AppViewModel {
        val v = vm()
        val pcm = Synth.build(List(5) { listOf(Synth.s(2400L + it * 300), Synth.q(500)) }.flatten())
        val starts = listOf(120L, 3000L, 6200L, 9800L, 13800L)
        v.align = AlignUi(pcm, starts, confident, message = message, playing = playing)
        return v
    }

    @Test @Config(qualifiers = "w360dp-h2600dp-xxhdpi") fun v10_align_all() = shot("10-align.png") { AlignScreen(alignVm(true, playing = 1)) }
    @Test fun v10b_align_screen() = shot("10b-align-top.png") { AlignScreen(alignVm(true)) }
    @Test fun v11_align_notConfident() = shot("11-align-not-confident.png") { AlignScreen(alignVm(false, "3번째 문장 구간에 소리가 거의 없습니다. 그 문장의 시작 시간을 고쳐 주세요.")) }

    @Test fun v12_fill_missing_dialog() { val v = vm(setOf(0, 1, 2)); v.voiceFillAsk = true; shot("12-fill-missing.png") { VoiceScreen(v) } }

    @Test fun v13_whatsnew() {
        val v = vm(); v.whatsNew = WhatsNew.notesFor("0.3.0")
        shot("13-whatsnew-0.3.0.png") { HomeScreen(v) }
    }

    @Test fun v14_update_banner_from_0_2_0() {
        val v = AppViewModel(app)
        v.update = UpdateUi(info = UpdateInfo(Semver(0, 3, 0), "v0.3.0", "goodseed-shorts-0.3.0.apk", "https://github.com/x", 43_000_000, "a".repeat(64), UpdateLogic.RELEASES_PAGE, ""), phase = UpdPhase.AVAILABLE)
        shot("14-update-banner-0.3.0.png") { HomeScreen(v) }
    }

    // ---------- 터치 칸 56dp ----------
    @Test fun a1_touch_voice_ai() = touch("목소리(AI)") { VoiceScreen(vm(mode = VoiceMode.AI)) }
    @Test fun a2_touch_voice_mine() = touch("목소리(내 목소리)") { VoiceScreen(vm(setOf(0))) }
    @Test fun a3_touch_record_ready() { val v = vm(); v.rec = RecUi(index = 1); touch("녹음(대기)") { RecordScreen(v) } }
    @Test fun a4_touch_record_recording() { val v = vm(); v.rec = RecUi(index = 1, recording = true); touch("녹음(녹음 중)") { RecordScreen(v) } }
    @Test fun a5_touch_record_done() { val v = vm(setOf(1)); v.rec = RecUi(index = 1); touch("녹음(완료)") { RecordScreen(v) } }
    @Test fun a6_touch_align() = touch("자막 맞추기") { AlignScreen(alignVm(true)) }
    @Test fun a7_touch_permission_dialog() { val v = vm(); v.rec = RecUi(index = 0, micDenied = true); touch("마이크 권한 창") { RecordScreen(v) } }
    @Test fun a8_touch_fill_dialog() { val v = vm(setOf(0)); v.voiceFillAsk = true; touch("AI 채우기 창") { VoiceScreen(v) } }

    // ---------- 색 대비(글자·버튼) ----------
    @Test fun recordButtonWhiteTextContrast() {
        assertTrue(Contrast.ratio(0xFFFFFFFF.toInt(), Palette.WARN) >= 4.5)
        assertTrue(Contrast.ratio(0xFFFFFFFF.toInt(), Palette.ACCENT_DARK) >= 4.5)
    }
}
