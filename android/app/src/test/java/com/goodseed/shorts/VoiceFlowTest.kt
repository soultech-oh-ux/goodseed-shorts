package com.goodseed.shorts

import android.app.Application
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

/** 마이크 대신 정해 둔 소리를 돌려주는 녹음기. */
class FakeRecorder(var result: MonoPcm? = null, var startOk: Boolean = true) : VoiceRecorder {
    private var cb: ((Float) -> Unit)? = null
    var stops = 0
    override var isRecording = false
    override fun start(onLevel: (Float) -> Unit): Boolean { if (!startOk) return false; cb = onLevel; isRecording = true; return true }
    override fun stop(): MonoPcm? { if (!isRecording) return null; isRecording = false; stops++; return result }
    fun emit(level: Float) { cb?.invoke(level) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceFlowTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var vm: AppViewModel
    private lateinit var fake: FakeRecorder
    private fun sent(letters: Int) = "가".repeat(letters) + "."

    @Before fun setUp() {
        AppViewModel.startupChecks = false
        vm = AppViewModel(app)
        fake = FakeRecorder()
        vm.recorder = fake
        vm.acceptText("${sent(10)} ${sent(20)} ${sent(10)}")
        assertEquals(3, vm.project.scenes.size)
    }

    private fun await(what: String, ms: Long = 30_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("시간 초과: $what")
            Thread.sleep(15)
        }
    }

    private fun speech(ms: Long, amp: Int = 3000, noise: Int = 200) = Synth.build(listOf(Synth.q(800), Synth.s(ms), Synth.q(900)), amp = amp, noise = noise)

    private fun record(pcm: MonoPcm?) {
        fake.result = pcm
        vm.startRecording(); assertTrue(vm.rec.recording)
        vm.stopRecording()
        await("녹음 처리") { !vm.rec.saving && !vm.rec.recording }
    }

    // ---------- (A) 문장별 녹음 ----------
    @Test fun recordingIsTrimmedNormalizedAndSavedForTheScene() {
        vm.openRecord(); assertEquals(Screen.RECORD, vm.screen); assertEquals(0, vm.rec.index)
        fake.result = speech(2000)
        vm.startRecording()
        fake.emit(0.4f)
        assertTrue(vm.rec.recording); assertEquals(0.4f, vm.recLevel, 0.001f)
        vm.stopRecording()
        await("녹음 처리") { !vm.rec.saving }
        val s = vm.project.scenes[0]
        val f = File(s.myVoice!!)
        assertTrue(f.isFile)
        assertTrue("앱 내부 저장소(녹음 폴더)에 저장", f.absolutePath.startsWith(File(app.filesDir, "recordings").absolutePath))
        val info = WavInfo.parse(f)!!
        assertEquals(1, info.channels); assertEquals(16, info.bitsPerSample)
        assertEquals(2200.0, info.durationUs / 1000.0, 80.0)            // 말소리 2.0초 + 앞뒤 0.1초
        assertEquals(s.myVoiceMs.toDouble(), info.durationUs / 1000.0, 2.0)
        assertEquals(Loudness.TARGET_RMS_DB, Loudness.speechRmsDb(VoiceAudio.readWav(f)!!)!!, 1.5)
        assertEquals(0f, vm.recLevel, 0f)                                 // 멈추면 막대는 0
        assertEquals("", vm.rec.message)
        assertNull(vm.project.scenes[1].myVoice)
    }

    @Test fun silentAndTooShortRecordingsAreRefusedWithAMessage() {
        record(Synth.build(listOf(Synth.q(2000)), noise = 100))
        assertNull(vm.project.scenes[0].myVoice); assertTrue(vm.rec.warn); assertTrue(vm.rec.message.contains("소리가 들리지 않았"))
        record(Synth.build(listOf(Synth.q(500), Synth.s(120), Synth.q(500)), amp = 3000))
        assertNull(vm.project.scenes[0].myVoice); assertTrue(vm.rec.warn); assertTrue(vm.rec.message.contains("너무 짧"))
    }

    @Test fun veryQuietVoiceGetsHintAndLoudClipGetsWarning() {
        record(speech(1500, amp = 120, noise = 8))
        assertNotNull(vm.project.scenes[0].myVoice); assertTrue(vm.rec.warn); assertTrue(vm.rec.message.contains("작아"))
        record(speech(1500, amp = 32767))
        assertTrue(vm.rec.warn); assertTrue(vm.rec.message.contains("커서"))
    }

    @Test fun rerecordReplacesTheFileAndDeletesTheOldOne() {
        record(speech(1500)); val first = File(vm.project.scenes[0].myVoice!!)
        Thread.sleep(5)
        record(speech(2500)); val second = File(vm.project.scenes[0].myVoice!!)
        assertNotEquals(first.absolutePath, second.absolutePath)
        assertFalse(first.exists()); assertTrue(second.exists())
        assertEquals(2700.0, vm.project.scenes[0].myVoiceMs.toDouble(), 80.0)
        assertEquals(1, vm.project.recordedCount)
    }

    @Test fun microphoneThatCannotOpenGivesMessageNotCrash() {
        fake.startOk = false
        vm.startRecording()
        assertFalse(vm.rec.recording); assertTrue(vm.rec.warn); assertTrue(vm.rec.message.contains("마이크"))
    }

    @Test fun leavingTheScreenWhileRecordingThrowsTheRecordingAway() {
        vm.openRecord(); fake.result = speech(1500); vm.startRecording()
        vm.back()
        assertEquals(Screen.VOICE, vm.screen); assertFalse(vm.rec.recording); assertEquals(1, fake.stops)
        assertNull(vm.project.scenes[0].myVoice)
    }

    @Test fun nextPreviousAndFinish() {
        vm.openRecord()
        record(speech(1500)); vm.recNext(); assertEquals(1, vm.rec.index)
        vm.recPrev(); assertEquals(0, vm.rec.index)
        vm.recSelect(2); vm.recNext()                    // 마지막 문장에서 [녹음 마치기]
        assertEquals(Screen.VOICE, vm.screen)
    }

    @Test fun openRecordStartsAtFirstSentenceWithoutRecording() {
        record(speech(1500)); vm.recSelect(0)
        vm.openRecord(); assertEquals(1, vm.rec.index)
    }

    @Test fun permissionDeniedFlagTogglesGuide() {
        vm.micDenied(true); assertTrue(vm.rec.micDenied)
        vm.micDenied(false); assertFalse(vm.rec.micDenied)
    }

    // ---------- 녹음과 글의 관계 ----------
    @Test fun editingTextDeletesThatSentencesRecording() {
        record(speech(1500)); val f = File(vm.project.scenes[0].myVoice!!)
        val id = vm.project.scenes[0].id
        vm.updateScene(id) { it.copy(size = SubSizeOther.other(it.size)) }          // 글이 아닌 것을 고치면 녹음은 그대로
        assertTrue(f.exists()); assertNotNull(vm.project.scenes[0].myVoice)
        vm.updateScene(id) { it.copy(text = "다른 글입니다.") }
        assertFalse(f.exists()); assertNull(vm.project.scenes[0].myVoice)
    }

    @Test fun reSplittingTextKeepsRecordingsOfSameSentences() {
        record(speech(1500)); val f = File(vm.project.scenes[0].myVoice!!)
        vm.acceptText("${sent(5)} ${sent(10)} ${sent(20)} ${sent(10)}")           // 첫 문장 앞에 새 문장이 끼어듦
        assertEquals(4, vm.project.scenes.size)
        assertEquals(f.absolutePath, vm.project.scenes[1].myVoice)
        assertNull(vm.project.scenes[0].myVoice); assertTrue(f.exists())
        vm.acceptText(sent(7))                                                       // 같은 문장이 없어지면 파일도 지운다
        assertFalse(f.exists())
    }

    @Test fun deletingASceneDeletesItsRecording() {
        record(speech(1500)); val f = File(vm.project.scenes[0].myVoice!!)
        vm.deleteScene(vm.project.scenes[0].id)
        assertFalse(f.exists()); assertEquals(2, vm.project.scenes.size)
    }

    // ---------- [다음] 규칙(부분 녹음 → AI 채움 묻기) ----------
    @Test fun nextButtonRules() {
        vm.go(Screen.VOICE)
        vm.setVoiceMode(VoiceMode.MINE)
        vm.voiceNext(); assertEquals(Screen.VOICE, vm.screen); assertFalse(vm.voiceFillAsk)        // 녹음이 하나도 없으면 못 간다
        record(speech(1500))
        vm.voiceNext(); assertEquals(Screen.VOICE, vm.screen); assertTrue(vm.voiceFillAsk)          // 일부만 → 묻는다
        vm.closeFillAsk(); assertFalse(vm.voiceFillAsk); assertEquals(Screen.VOICE, vm.screen)      // 돌아가서 녹음하기
        vm.voiceNext(); vm.fillMissingWithAi(); assertEquals(Screen.MEDIA, vm.screen)               // AI 로 채우기
        vm.go(Screen.VOICE)
        vm.recSelect(1); record(speech(1500)); vm.recSelect(2); record(speech(1500))
        assertEquals(0, vm.project.missingCount)
        vm.voiceNext(); assertEquals(Screen.MEDIA, vm.screen); assertFalse(vm.voiceFillAsk)         // 전부 녹음 → 바로 간다
        vm.go(Screen.VOICE); vm.setVoiceMode(VoiceMode.AI)
        vm.voiceNext(); assertEquals(Screen.MEDIA, vm.screen)
    }

    // ---------- (B) 녹음 파일 올리기 → 경계 추정 → 수동 이동 → 확정 ----------
    private fun uploadWav(): File {
        // 앞 0.7초 무음 · 문장 1.0 / 2.0 / 1.0초(글자 10·20·10) · 문장 사이 0.5초 쉼 · 뒤 0.6초, 소리 작게(-30dB 근처)
        val p = Synth.build(listOf(Synth.q(700), Synth.s(1000), Synth.q(500), Synth.s(2000), Synth.q(500), Synth.s(1000), Synth.q(600)), amp = 900, noise = 15)
        return File(app.cacheDir, "upload_test.wav").also { VoiceAudio.writeWav(it, p) }
    }

    @Test fun uploadedFileIsSplitAdjustedAndSavedPerSentence() {
        vm.go(Screen.VOICE); vm.setVoiceMode(VoiceMode.MINE)
        vm.importVoiceFile(Uri.fromFile(uploadWav()))
        assertTrue(vm.voiceImport.busy)
        await("파일 읽기") { vm.screen == Screen.ALIGN }
        val a = vm.align!!
        assertTrue(a.confident); assertEquals(3, a.starts.size)
        assertEquals(550.0, a.starts[0].toDouble(), 70.0)
        assertEquals(700.0 + 1250, a.starts[1].toDouble(), 70.0)
        assertEquals(700.0 + 3750, a.starts[2].toDouble(), 70.0)
        assertEquals(Loudness.TARGET_RMS_DB, Loudness.speechRmsDb(a.pcm)!!, 1.5)          // 올린 파일 전체의 소리 크기를 맞춤
        assertFalse(vm.voiceImport.busy)

        // 수동 이동: 0.5초 / 0.1초 단위, 이웃을 넘지 않음
        val s1 = a.starts[1]
        vm.alignShift(1, 500); assertEquals(s1 + 500, vm.align!!.starts[1])
        vm.alignShift(1, -100); assertEquals(s1 + 400, vm.align!!.starts[1])
        vm.alignShift(1, -500); vm.alignShift(1, -5000)
        assertTrue(vm.align!!.starts[1] >= a.starts[0] + SentenceAligner.MIN_SEG_MS)
        vm.alignShift(1, 5000); assertTrue(vm.align!!.starts[1] <= a.starts[2] - SentenceAligner.MIN_SEG_MS)
        vm.alignShift(1, (s1 - vm.align!!.starts[1]))                                       // 처음 위치로 되돌림
        assertEquals(s1, vm.align!!.starts[1])

        vm.alignConfirm()
        await("확정") { vm.screen == Screen.VOICE }
        assertNull(vm.align)
        val sc = vm.project.scenes
        assertTrue(sc.all { it.myVoice != null && File(it.myVoice!!).isFile })
        assertEquals(1200.0, sc[0].myVoiceMs.toDouble(), 100.0)       // 1.0초 + 앞뒤 0.1초
        assertEquals(2200.0, sc[1].myVoiceMs.toDouble(), 100.0)
        assertEquals(1200.0, sc[2].myVoiceMs.toDouble(), 100.0)

        // 자막이 목소리에 정확히 맞는다: 장면 길이 = 앞 여백 + 내 목소리 + 뒤 여백, 낭독 줄도 정확히 그 길이
        val durs = sc.map { WavInfo.parse(File(it.myVoice!!))!!.durationUs }
        val times = Timing.scenes(durs)
        val dir = File(app.cacheDir, "pad_test")
        for ((i, f) in sc.withIndex()) {
            val got = WavPad.pad(File(f.myVoice!!), File(dir, "s$i.wav"), Timing.LEAD_US, times[i].durationUs)!!
            assertEquals("장면 $i", times[i].durationUs.toDouble(), got.toDouble(), 30.0)
            assertEquals(Timing.LEAD_US + durs[i], times[i].speechEndUs - times[i].startUs)
        }
    }

    @Test fun uploadedGarbageOrSilentFileGivesMessageAndStaysOnVoiceScreen() {
        vm.go(Screen.VOICE); vm.setVoiceMode(VoiceMode.MINE)
        val junk = File(app.cacheDir, "junk.m4a").also { it.writeBytes(ByteArray(5000) { (it * 31).toByte() }) }
        vm.importVoiceFile(Uri.fromFile(junk))
        await("잘못된 파일") { !vm.voiceImport.busy }
        assertTrue(vm.voiceImport.warn); assertTrue(vm.voiceImport.message.isNotBlank()); assertEquals(Screen.VOICE, vm.screen); assertNull(vm.align)

        val quiet = File(app.cacheDir, "quiet.wav").also { VoiceAudio.writeWav(it, Synth.build(listOf(Synth.q(3000)), noise = 0)) }
        vm.importVoiceFile(Uri.fromFile(quiet))
        await("무음 파일") { !vm.voiceImport.busy }
        assertTrue(vm.voiceImport.warn); assertTrue(vm.voiceImport.message.contains("말소리를 찾지 못")); assertEquals(Screen.VOICE, vm.screen)

        vm.importVoiceFile(Uri.fromFile(File(app.cacheDir, "없는파일.wav")))
        await("없는 파일") { !vm.voiceImport.busy }
        assertTrue(vm.voiceImport.warn); assertEquals(Screen.VOICE, vm.screen)
    }

    @Test fun confirmStopsWhenASegmentHasNoSound() {
        vm.go(Screen.VOICE); vm.setVoiceMode(VoiceMode.MINE)
        // 문장 2 구간이 통째로 무음이 되도록 경계를 억지로 옮긴다
        vm.importVoiceFile(Uri.fromFile(uploadWav())); await("읽기") { vm.screen == Screen.ALIGN }
        val a = vm.align!!
        vm.align = a.copy(starts = listOf(a.starts[0], a.pcm.durationMs - 700, a.pcm.durationMs - 350))   // 문장 1 이 거의 전부, 문장 2·3 은 뒤 무음 구간
        vm.alignConfirm()
        await("실패 안내") { vm.align?.saving == false && vm.align?.message?.isNotBlank() == true }
        assertEquals(Screen.ALIGN, vm.screen)
        assertTrue(vm.align!!.message.contains("번째 문장"))
        assertTrue(vm.project.scenes.all { it.myVoice == null })              // 하나도 저장하지 않는다
    }

    @Test fun backFromAlignDropsTheDecodedAudio() {
        vm.go(Screen.VOICE); vm.setVoiceMode(VoiceMode.MINE)
        vm.importVoiceFile(Uri.fromFile(uploadWav())); await("읽기") { vm.screen == Screen.ALIGN }
        vm.back(); assertEquals(Screen.VOICE, vm.screen); assertNull(vm.align)
    }

    @Test fun startingNewProjectGetsNewRecordingFolderAndOldOnesAreCleaned() {
        record(speech(1500))
        val oldDir = File(app.filesDir, "recordings/${vm.project.id}")
        assertTrue(oldDir.isDirectory)
        val oldId = vm.project.id
        vm.startNew()
        assertNotEquals(oldId, vm.project.id)
        assertTrue(oldDir.isDirectory)                         // 7일 안이면 남긴다
        oldDir.setLastModified(System.currentTimeMillis() - 10L * 86_400_000)
        vm.startNew()
        await("정리") { !oldDir.exists() }
    }
}

private object SubSizeOther { fun other(s: com.goodseed.shorts.render.SubSize) = com.goodseed.shorts.render.SubSize.entries.first { it != s } }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioDecoderTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun wavFileDecodesToMono44k() {
        val p = Synth.build(listOf(Synth.q(300), Synth.s(1000), Synth.q(300)), rate = 16000)
        val f = File(app.cacheDir, "d16k.wav").also { VoiceAudio.writeWav(it, MonoPcm(p.samples, 16000)) }
        val r = AudioDecoder.decodeFile(f) as DecodeResult.Ok
        assertEquals(44100, r.pcm.rate)
        assertEquals(1600.0, r.pcm.durationMs.toDouble(), 10.0)
        assertFalse(r.truncated)
    }

    @Test fun longFileIsCutAtTheLimitAndSaysSo() {
        val p = Synth.build(listOf(Synth.s(5000)))
        val f = File(app.cacheDir, "long.wav").also { VoiceAudio.writeWav(it, p) }
        val r = AudioDecoder.decodeFile(f, maxMs = 2000) as DecodeResult.Ok
        assertTrue(r.truncated); assertEquals(2000.0, r.pcm.durationMs.toDouble(), 5.0)
    }

    @Test fun brokenFilesFailWithoutCrashing() {
        for ((name, bytes) in listOf("empty.bin" to ByteArray(0), "junk.mp3" to ByteArray(3000) { (it * 7).toByte() }, "textfile.m4a" to "안녕하세요 이것은 소리가 아닙니다".toByteArray())) {
            val f = File(app.cacheDir, name).also { it.writeBytes(bytes) }
            assertTrue("$name", AudioDecoder.decodeFile(f) is DecodeResult.Fail)
        }
        val trunc = File(app.cacheDir, "cut.wav")
        VoiceAudio.writeWav(trunc, Synth.build(listOf(Synth.s(1000))))
        trunc.writeBytes(trunc.readBytes().copyOf(30))                     // 헤더 중간에서 끊긴 wav
        assertTrue(AudioDecoder.decodeFile(trunc) is DecodeResult.Fail)
    }

    @Test fun uriThatCannotBeOpenedFails() {
        val r = AudioDecoder.decode(app, Uri.fromFile(File(app.cacheDir, "nope.wav")))
        assertTrue(r is DecodeResult.Fail)
    }
}
