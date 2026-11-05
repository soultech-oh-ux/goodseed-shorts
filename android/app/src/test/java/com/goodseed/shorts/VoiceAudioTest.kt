package com.goodseed.shorts

import com.goodseed.shorts.logic.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** 시험용 소리 만들기: 말소리 = 180Hz 사인파(앞뒤 5ms 서서히), 쉼 = 작은 잡음. */
object Synth {
    const val RATE = 44100

    fun build(parts: List<Pair<Boolean, Long>>, amp: Int = 8000, noise: Int = 200, rate: Int = RATE, seed: Long = 7): MonoPcm {
        val rnd = Random(seed)
        val out = ArrayList<Short>()
        for ((speech, ms) in parts) {
            val n = (ms * rate / 1000).toInt()
            for (i in 0 until n) {
                val nz = if (noise > 0) (rnd.nextInt(2 * noise + 1) - noise) else 0
                var v = nz.toDouble()
                if (speech) {
                    val fade = minOf(1.0, minOf(i, n - 1 - i) / (rate * 0.005))
                    v += amp * fade * sin(2 * PI * 180.0 * i / rate)
                }
                out += v.toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return MonoPcm(out.toShortArray(), rate)
    }

    fun s(ms: Long) = true to ms
    fun q(ms: Long) = false to ms
}

class SilenceTrimTest {
    @Test fun trimsLeadingAndTrailingSilence() {
        val p = Synth.build(listOf(Synth.q(1000), Synth.s(2000), Synth.q(1500)))
        val t = SilenceTrim.trim(p)!!
        // 말소리 2.0초 + 앞뒤 여유 0.1초씩
        assertEquals(2200.0, t.durationMs.toDouble(), 60.0)
        assertTrue(t.durationMs < p.durationMs)
    }

    @Test fun keepsInnerPauses() {
        val p = Synth.build(listOf(Synth.q(500), Synth.s(1000), Synth.q(800), Synth.s(1000), Synth.q(500)))
        val t = SilenceTrim.trim(p)!!
        assertEquals(1000 + 800 + 1000 + 200.0, t.durationMs.toDouble(), 60.0)
    }

    @Test fun allSilenceReturnsNull() {
        assertNull(SilenceTrim.trim(Synth.build(listOf(Synth.q(2000)), noise = 0)))
        assertNull(SilenceTrim.trim(Synth.build(listOf(Synth.q(2000)), noise = 200)))   // 잡음만
        assertNull(SilenceTrim.trim(MonoPcm(ShortArray(0), 44100)))
    }

    @Test fun shortClickIsNotSpeech() {
        val p = Synth.build(listOf(Synth.q(1000), Synth.s(10), Synth.q(1000)))
        assertNull(SilenceTrim.trim(p))
    }

    @Test fun noisyRoomStillTrims() {
        val p = Synth.build(listOf(Synth.q(800), Synth.s(1500), Synth.q(800)), amp = 9000, noise = 1200)
        val t = SilenceTrim.trim(p)!!
        assertEquals(1700.0, t.durationMs.toDouble(), 80.0)
    }

    @Test fun trimmedAudioDoesNotCutSpeech() {
        val p = Synth.build(listOf(Synth.q(600), Synth.s(1000), Synth.q(600)))
        val t = SilenceTrim.trim(p)!!
        // 잘린 결과 안에 말소리 전체가 있다 — 가운데 1초의 에너지가 그대로
        assertTrue(Loudness.peak(t) > 7000)
        assertTrue(t.durationMs >= 1000)
    }
}

class SilenceGapsTest {
    @Test fun findsPausesLongerThanThreshold() {
        val p = Synth.build(listOf(Synth.s(1000), Synth.q(600), Synth.s(1000), Synth.q(200), Synth.s(1000)))
        val g = SilenceGaps.find(p)
        assertEquals(1, g.size)                    // 0.2초 쉼은 낱말 사이로 보고 무시
        assertEquals(1300.0, g[0].midMs.toDouble(), 60.0)
        assertEquals(600.0, g[0].lengthMs.toDouble(), 60.0)
    }

    @Test fun lowerThresholdFindsShortPauses() {
        val p = Synth.build(listOf(Synth.s(1000), Synth.q(200), Synth.s(1000)))
        assertEquals(0, SilenceGaps.find(p).size)
        assertEquals(1, SilenceGaps.find(p, 150).size)
    }

    @Test fun noGapsInContinuousSpeechOrSilence() {
        assertEquals(0, SilenceGaps.find(Synth.build(listOf(Synth.s(3000)))).size)
        assertEquals(0, SilenceGaps.find(Synth.build(listOf(Synth.q(3000)))).size)
    }
}

class SentenceAlignerTest {
    private fun sent(letters: Int) = "가".repeat(letters) + "."

    @Test fun findsBoundariesAtTheSilences() {
        // 앞 0.7초 무음 → 문장 4개(글자 10·20·10·20 = 1.0·2.0·1.0·2.0초), 문장 사이 0.5초 쉼, 뒤 0.6초
        val p = Synth.build(listOf(Synth.q(700), Synth.s(1000), Synth.q(500), Synth.s(2000), Synth.q(500), Synth.s(1000), Synth.q(500), Synth.s(2000), Synth.q(600)))
        val r = SentenceAligner.estimate(p, listOf(sent(10), sent(20), sent(10), sent(20)))
        assertTrue(r.confident)
        assertEquals(4, r.startsMs.size)
        assertEquals(700.0 - 150, r.startsMs[0].toDouble(), 60.0)       // 첫 문장: 말소리 시작 직전
        assertEquals(700.0 + 1250, r.startsMs[1].toDouble(), 60.0)
        assertEquals(700.0 + 3750, r.startsMs[2].toDouble(), 60.0)
        assertEquals(700.0 + 5250, r.startsMs[3].toDouble(), 60.0)
        assertEquals(r.startsMs.sorted(), r.startsMs)
    }

    @Test fun ignoresBreathPausesInsideASentence() {
        // 둘째 문장(글자 20) 한가운데에 0.35초 숨 쉼 — 문장 경계로 착각하면 안 된다
        val p = Synth.build(listOf(Synth.s(1000), Synth.q(500), Synth.s(1000), Synth.q(350), Synth.s(1000), Synth.q(500), Synth.s(1000)))
        val r = SentenceAligner.estimate(p, listOf(sent(10), sent(20), sent(10)))
        assertEquals(3, r.startsMs.size)
        assertEquals(1250.0, r.startsMs[1].toDouble(), 60.0)
        assertEquals(4100.0, r.startsMs[2].toDouble(), 60.0)
    }

    @Test fun fewGapsFallsBackToProportionalAndSaysNotConfident() {
        // 문장은 4개인데 뚜렷한 쉼은 1개뿐
        val p = Synth.build(listOf(Synth.s(2000), Synth.q(500), Synth.s(6000)))
        val r = SentenceAligner.estimate(p, listOf(sent(10), sent(10), sent(10), sent(10)))
        assertFalse(r.confident)
        assertEquals(4, r.startsMs.size)
        for (i in 1 until 4) assertTrue("간격 ${r.startsMs}", r.startsMs[i] - r.startsMs[i - 1] >= SentenceAligner.MIN_SEG_MS)
        assertTrue(r.startsMs.last() < p.durationMs)
        // 쉼이 있는 곳(2.25초 근처)이 경계 하나로 잡힌다
        assertTrue(r.startsMs.any { abs(it - 2250) < 120 })
    }

    @Test fun silentFileGivesEvenSplitNotConfident() {
        val p = Synth.build(listOf(Synth.q(4000)), noise = 0)
        val r = SentenceAligner.estimate(p, listOf(sent(5), sent(5), sent(5), sent(5)))
        assertFalse(r.confident)
        assertEquals(listOf(0L, 1000L, 2000L, 3000L), r.startsMs)
    }

    @Test fun oneSentenceAndEmptyList() {
        val p = Synth.build(listOf(Synth.q(500), Synth.s(1000), Synth.q(500)))
        assertEquals(1, SentenceAligner.estimate(p, listOf(sent(5))).startsMs.size)
        assertEquals(0, SentenceAligner.estimate(p, emptyList()).startsMs.size)
    }

    @Test fun moreSentencesThanFitStillGivesValidOrderedStarts() {
        val p = Synth.build(listOf(Synth.s(1500)))          // 1.5초에 문장 10개
        val r = SentenceAligner.estimate(p, List(10) { sent(3) })
        assertEquals(10, r.startsMs.size)
        for (i in 1 until 10) assertTrue(r.startsMs[i] >= r.startsMs[i - 1])
        assertTrue(r.startsMs.all { it in 0..p.durationMs })
    }

    @Test fun weightCountsLettersNotPunctuationAndSpaces() {
        assertEquals(5 + 3, SentenceAligner.weight("가 나 다, 라마.", false))
        assertEquals(5, SentenceAligner.weight("가 나 다, 라마.", true))
        assertEquals(1 + 3, SentenceAligner.weight("...", false))   // 글자가 없어도 최소 1
    }
}

class BoundaryEditTest {
    private val starts = listOf(500L, 3000L, 6000L, 9000L)
    private val total = 12000L

    @Test fun movesByDelta() {
        assertEquals(listOf(500L, 3500L, 6000L, 9000L), BoundaryEdit.shift(starts, 1, 500, total))
        assertEquals(listOf(500L, 2500L, 6000L, 9000L), BoundaryEdit.shift(starts, 1, -500, total))
    }

    @Test fun cannotCrossNeighbours() {
        // 앞 문장 시작(500)+0.3초 아래로, 뒤 문장 시작(6000)-0.3초 위로는 못 간다
        assertEquals(800L, BoundaryEdit.shift(starts, 1, -5000, total)[1])
        assertEquals(5700L, BoundaryEdit.shift(starts, 1, 5000, total)[1])
    }

    @Test fun firstAndLastBounds() {
        assertEquals(0L, BoundaryEdit.shift(starts, 0, -5000, total)[0])
        assertEquals(11700L, BoundaryEdit.shift(starts, 3, 9000, total)[3])
    }

    @Test fun invalidIndexOrNoRoomLeavesUnchanged() {
        assertEquals(starts, BoundaryEdit.shift(starts, 9, 500, total))
        assertEquals(starts, BoundaryEdit.shift(starts, -1, 500, total))
        val tight = listOf(0L, 400L, 800L)       // 문장마다 0.4초 — 가운데를 움직일 틈이 0.1초뿐
        val moved = BoundaryEdit.shift(tight, 1, 500, 1200)
        assertTrue(moved[1] in 500..500)         // 800-300
        assertEquals(listOf(0L, 100L), BoundaryEdit.shift(listOf(0L, 100L), 1, 500, 200))
    }

    @Test fun segmentSpan() {
        assertEquals(500L to 3000L, BoundaryEdit.span(starts, 0, total))
        assertEquals(9000L to 12000L, BoundaryEdit.span(starts, 3, total))
    }

    @Test fun manyShiftsKeepOrderAndMinimumLength() {
        var s = starts
        val rnd = Random(3)
        repeat(500) {
            s = BoundaryEdit.shift(s, rnd.nextInt(4), (rnd.nextInt(21) - 10) * 500L, total)
            for (i in 1 until s.size) assertTrue("$s", s[i] - s[i - 1] >= SentenceAligner.MIN_SEG_MS)
            assertTrue(total - s.last() >= SentenceAligner.MIN_SEG_MS)
            assertTrue(s[0] >= 0)
        }
    }
}

class LoudnessTest {
    private fun rmsDb(p: MonoPcm) = Loudness.speechRmsDb(p)!!

    @Test fun quietVoiceIsRaisedToTarget() {
        val p = Synth.build(listOf(Synth.q(300), Synth.s(1500), Synth.q(300)), amp = 1000, noise = 20)
        assertTrue(rmsDb(p) < -30)
        val n = Loudness.normalize(p)
        assertEquals(Loudness.TARGET_RMS_DB, rmsDb(n), 1.0)
        assertTrue(Loudness.peak(n) <= (Loudness.PEAK_CEIL * 32767).toInt() + 1)
    }

    @Test fun loudVoiceIsLoweredToTarget() {
        val p = Synth.build(listOf(Synth.q(300), Synth.s(1500), Synth.q(300)), amp = 15000, noise = 20)
        val n = Loudness.normalize(p)
        assertEquals(Loudness.TARGET_RMS_DB, rmsDb(n), 1.0)
    }

    @Test fun twoDifferentLevelsEndUpClose() {
        val a = Loudness.normalize(Synth.build(listOf(Synth.q(300), Synth.s(1500), Synth.q(300)), amp = 2000, noise = 20))
        val b = Loudness.normalize(Synth.build(listOf(Synth.q(300), Synth.s(1500), Synth.q(300)), amp = 15000, noise = 20))
        assertEquals(rmsDb(a), rmsDb(b), 1.5)
    }

    @Test fun peakNeverClips() {
        // 가끔 큰 봉우리가 있는 소리: 목표 크기로 키우면 봉우리가 넘치므로 배율이 제한된다
        val base = Synth.build(listOf(Synth.q(300), Synth.s(1500), Synth.q(300)), amp = 3000, noise = 20)
        val s = base.samples.copyOf(); s[base.samples.size / 2] = 20000
        val p = MonoPcm(s, base.rate)
        val n = Loudness.normalize(p)
        assertTrue(Loudness.peak(n) <= (Loudness.PEAK_CEIL * 32767).toInt() + 1)
        assertTrue(Loudness.gainFor(p) < 2.0)
    }

    @Test fun gainIsLimitedForNearSilence() {
        val p = Synth.build(listOf(Synth.q(300), Synth.s(1500), Synth.q(300)), amp = 150, noise = 30)
        val g = Loudness.gainFor(p)
        assertEquals(Math.pow(10.0, Loudness.MAX_GAIN_DB / 20), g, 0.01)      // 최대 +20dB
    }

    @Test fun inaudibleSoundWithoutAnyPauseIsTreatedAsNoSpeech() {
        val p = Synth.build(listOf(Synth.s(2000)), amp = 90, noise = 0)      // -54dB 로 쉼 없이 이어짐
        assertNull(Loudness.speechRmsDb(p))
        assertNull(SilenceTrim.trim(p))
    }

    @Test fun silenceIsLeftAlone() {
        val p = Synth.build(listOf(Synth.q(1000)), noise = 0)
        assertEquals(1.0, Loudness.gainFor(p), 0.0)
        assertSame(p, Loudness.normalize(p))
        assertNull(Loudness.speechRmsDb(p))
    }
}

class PcmWavTest {
    @Test fun downmixAveragesChannels() {
        val st = shortArrayOf(1000, 3000, -2000, 2000, 500, 500)
        assertEquals(listOf<Short>(2000, 0, 500), Pcm.downmix(st, 2).toList())
        assertEquals(listOf<Short>(1, 2), Pcm.downmix(shortArrayOf(1, 2), 1).toList())
    }

    @Test fun resampleKeepsDurationAndLevel() {
        val src = ShortArray(16000) { (8000 * sin(2 * PI * 200 * it / 16000)).toInt().toShort() }   // 1초 16kHz
        val out = Pcm.resample(src, 16000, 44100)
        assertEquals(44100.0, out.size.toDouble(), 2.0)
        assertTrue(out.maxOrNull()!! in 7900..8000)
        assertEquals(src.size, Pcm.resample(src, 16000, 16000).size)
        val down = Pcm.resample(ShortArray(48000) { 100 }, 48000, 44100)
        assertEquals(44100.0, down.size.toDouble(), 2.0)
        assertTrue(down.all { it.toInt() == 100 })
        assertEquals(0, Pcm.resample(ShortArray(0), 16000, 44100).size)
    }

    @Test fun wavRoundTrip() {
        val p = Synth.build(listOf(Synth.q(200), Synth.s(500), Synth.q(200)))
        val f = File.createTempFile("voice", ".wav")
        try {
            VoiceAudio.writeWav(f, p)
            val info = WavInfo.parse(f)!!
            assertEquals(44100, info.sampleRate); assertEquals(1, info.channels); assertEquals(16, info.bitsPerSample)
            assertEquals(p.durationUs, info.durationUs)
            val back = VoiceAudio.readWav(f)!!
            assertArrayEquals(p.samples, back.samples)
            assertEquals(f.length(), 44L + p.samples.size * 2)
        } finally { f.delete() }
    }

    @Test fun stereoWavAt22kIsConvertedToMono44k() {
        val frames = 22050
        val inter = ShortArray(frames * 2) { if (it % 2 == 0) 4000 else 2000 }
        val f = File.createTempFile("stereo", ".wav")
        try {
            val bytes = inter.size * 2L
            f.outputStream().use { o ->
                o.write(WavPad.header(WavInfo(22050, 2, 16, bytes), bytes))
                val bb = java.nio.ByteBuffer.allocate(inter.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                inter.forEach { bb.putShort(it) }; o.write(bb.array())
            }
            val m = VoiceAudio.readWav(f)!!
            assertEquals(44100, m.rate)
            assertEquals(44100.0, m.samples.size.toDouble(), 3.0)
            assertTrue(m.samples.all { it.toInt() == 3000 })
        } finally { f.delete() }
    }

    @Test fun nonWavAndEightBitAreRejected() {
        val f = File.createTempFile("bad", ".bin")
        try {
            f.writeBytes(ByteArray(2000) { 7 })
            assertNull(VoiceAudio.readWav(f))
            val bytes = 100L
            val h = WavPad.header(WavInfo(8000, 1, 16, bytes), bytes); h[34] = 8      // 비트 수 칸을 8 로
            f.outputStream().use { o -> o.write(h); o.write(ByteArray(100)) }
            assertNull(VoiceAudio.readWav(f))           // 8비트는 읽지 않는다(코덱 경로로)
        } finally { f.delete() }
    }

    @Test fun formatTime() {
        assertEquals("0:00.0", VoiceAudio.fmtMs(0))
        assertEquals("1:05.3", VoiceAudio.fmtMs(65_350))
        assertEquals("0:00.0", VoiceAudio.fmtMs(-5))
    }

    @Test fun sliceByMs() {
        val p = MonoPcm(ShortArray(44100) { it.toShort() }, 44100)
        val s = p.slice(250, 750)
        assertEquals(22050, s.samples.size)
        assertEquals(11025.toShort(), s.samples[0])
        assertEquals(0, p.slice(900, 500).samples.size)
        assertEquals(44100, p.slice(-5, 99999).samples.size)
    }
}

class RecordingStoreTest {
    @Test fun cleansOnlyOldFoldersAndKeepsCurrent() {
        val root = java.nio.file.Files.createTempDirectory("rec").toFile()
        try {
            val now = System.currentTimeMillis()
            val old = RecordingStore.dirFor(root, "old").also { File(it, "s1.wav").writeText("x"); it.setLastModified(now - 30L * 86_400_000) }
            val fresh = RecordingStore.dirFor(root, "fresh").also { File(it, "s1.wav").writeText("x") }
            val cur = RecordingStore.dirFor(root, "cur").also { it.setLastModified(now - 60L * 86_400_000) }
            assertEquals(1, RecordingStore.cleanOld(root, "cur", 14L * 86_400_000, now))
            assertFalse(old.exists()); assertTrue(fresh.exists()); assertTrue(cur.exists())
            assertEquals("s7_5.wav", RecordingStore.sceneFile(fresh, 7, 5).name)
        } finally { root.deleteRecursively() }
    }
}
