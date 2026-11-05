package com.goodseed.shorts

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.goodseed.shorts.logic.*
import com.goodseed.shorts.render.DuckingAudioProcessor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPadTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun writeWav(f: File, rate: Int, ch: Int, samples: ShortArray) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> samples.forEach { b.putShort(it) } }.array()
        val hdr = WavPad.header(WavInfo(rate, ch, 16, data.size.toLong()), data.size.toLong())
        f.writeBytes(hdr + data)
    }

    @Test fun padsLeadAndTailToExactSceneLength() {
        val src = tmp.newFile("a.wav"); val dst = tmp.newFile("b.wav")
        val rate = 22050
        val pcm = ShortArray(rate / 2) { (it % 100 + 1).toShort() }   // 0.5초(0이 아닌 값)
        writeWav(src, rate, 1, pcm)
        val us = WavPad.pad(src, dst, leadUs = 250_000, totalUs = 1_000_000)!!
        val info = WavInfo.parse(dst)!!
        assertEquals(us, info.durationUs)
        assertEquals(1_000_000L, info.durationUs)
        // 앞 250ms = 무음, 그 다음이 원래 소리, 마지막은 무음
        val all = ByteBuffer.wrap(dst.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val leadSamples = rate / 4
        all.position(info.dataOffset)
        for (i in 0 until leadSamples) assertEquals(0, all.getShort().toInt())
        for (i in pcm.indices) assertEquals(pcm[i], all.getShort())
        while (all.remaining() >= 2) assertEquals(0, all.getShort().toInt())
    }

    @Test fun stereoStaysFrameAligned() {
        val src = tmp.newFile("s.wav"); val dst = tmp.newFile("t.wav")
        writeWav(src, 44100, 2, ShortArray(44100) { 5 })        // 0.5초 스테레오
        WavPad.pad(src, dst, 100_000, 900_000)!!
        val info = WavInfo.parse(dst)!!
        assertEquals(2, info.channels)
        assertEquals(0L, info.dataBytes % info.frameBytes)
        assertEquals(900_000L, info.durationUs)
    }

    @Test fun neverShortensVoiceEvenIfTotalIsTooSmall() {
        val src = tmp.newFile("c.wav"); val dst = tmp.newFile("d.wav")
        writeWav(src, 16000, 1, ShortArray(16000) { 3 })         // 1초
        WavPad.pad(src, dst, 100_000, 500_000)!!
        assertTrue(WavInfo.parse(dst)!!.durationUs >= 1_100_000L)
    }

    @Test fun nonPcm16IsRejected() {
        val src = tmp.newFile("e.wav")
        val b = ByteBuffer.allocate(44 + 8).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + 8).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(3).putShort(1).putInt(16000).putInt(64000).putShort(4).putShort(32)   // float32
            .put("data".toByteArray()).putInt(8).put(ByteArray(8))
        src.writeBytes(b.array())
        assertNull(WavPad.pad(src, tmp.newFile("f.wav"), 1, 1_000_000))
    }
}

class DuckingProcessorTest {
    private fun run(rate: Int, ch: Int, frames: Int, curve: DuckingCurve, offsetUs: Long, base: Float, value: Short = 10000): ShortArray {
        val p = DuckingAudioProcessor(curve, offsetUs, base)
        val inFmt = AudioProcessor.AudioFormat(rate, ch, C.ENCODING_PCM_16BIT)
        p.configure(inFmt); p.flush()
        val inBuf = ByteBuffer.allocateDirect(frames * ch * 2).order(ByteOrder.nativeOrder())
        repeat(frames * ch) { inBuf.putShort(value) }
        inBuf.flip()
        p.queueInput(inBuf)
        val out = p.output
        val res = ShortArray(out.remaining() / 2)
        out.order(ByteOrder.nativeOrder())
        for (i in res.indices) res[i] = out.getShort()
        return res
    }

    @Test fun appliesBaseVolumeAndDucksInsideSpeech() {
        val curve = DuckingCurve(listOf(SpeechSpan(400_000, 600_000)), duckGain = 0.2f, attackUs = 100_000, releaseUs = 100_000)
        val rate = 10_000
        val out = run(rate, 1, rate, curve, 0, base = 0.5f)       // 1초 = 10000 프레임
        assertEquals(10000, out.size)
        assertEquals(5000, out[0].toInt())                          // 0.0초: 기본 크기 0.5
        assertEquals(5000, out[2500].toInt())                       // 0.25초: 아직 램프 전
        assertEquals((10000 * 0.5f * 0.2f).toInt(), out[5000].toInt()) // 0.5초: 낭독 중(0.2배)
        assertEquals(5000, out[8000].toInt())                       // 0.8초: 다시 원래
        // 램프 중간(0.35초): 1.0 → 0.2 의 중간 근처
        val mid = out[3500].toInt()
        assertTrue("ramp=$mid", mid in 2000..5000)
    }

    @Test fun offsetShiftsTheCurveForLaterSegments() {
        val curve = DuckingCurve(listOf(SpeechSpan(10_400_000, 10_600_000)), 0.2f, 100_000, 100_000)
        val rate = 10_000
        val out = run(rate, 1, rate, curve, offsetUs = 10_000_000, base = 1f)
        assertEquals(10000, out[0].toInt())
        assertEquals(2000, out[5000].toInt())
    }

    @Test fun stereoAndClipping() {
        val curve = DuckingCurve(emptyList())
        val out = run(8000, 2, 100, curve, 0, base = 2f, value = 30000)
        assertEquals(200, out.size)
        assertTrue(out.all { it.toInt() == 32767 })                 // 넘치면 자른다
    }

    @Test(expected = AudioProcessor.UnhandledAudioFormatException::class)
    fun rejectsNon16BitInput() {
        DuckingAudioProcessor(DuckingCurve(emptyList()), 0, 1f).configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT))
    }
}

class TimingRoundingTest {
    @Test fun sceneDurationsAreWholeMilliseconds() {
        val s = Timing.scenes(listOf(1_234_567L, 2_000_001L, 777L))
        for (sc in s) assertEquals(0L, sc.durationUs % 1000L)
        for (i in 1 until s.size) assertEquals(s[i - 1].endUs, s[i].startUs)
    }
}

class PexelsParserTest {
    private val sample = """
    {"page":1,"videos":[
      {"id":1,"duration":12,"url":"https://www.pexels.com/video/1/","image":"https://img/1.jpg","user":{"name":"홍길동"},
       "video_files":[
         {"link":"https://v/1-land.mp4","file_type":"video/mp4","width":1920,"height":1080},
         {"link":"https://v/1-sd.mp4","file_type":"video/mp4","width":540,"height":960},
         {"link":"https://v/1-hd.mp4","file_type":"video/mp4","width":1080,"height":1920},
         {"link":"https://v/1.webm","file_type":"video/webm","width":1080,"height":1920}]},
      {"id":2,"duration":5,"image":"x","video_files":[]},
      "garbage",
      {"id":3,"duration":8,"url":"u3","image":"i3","user":{"name":"A"},"video_files":[{"link":"https://v/3.mp4","file_type":"video/mp4","width":1280,"height":720}]}
    ]}"""

    @Test fun picksPortraitMp4NearestToFullHdAndSkipsBrokenEntries() {
        val r = PexelsParser.parse(sample)
        assertEquals(2, r.size)
        assertEquals("https://v/1-hd.mp4", r[0].fileUrl)
        assertEquals("홍길동", r[0].photographer)
        assertEquals(1080, r[0].width)
        assertEquals("https://v/3.mp4", r[1].fileUrl)       // 세로가 없으면 가로라도
    }

    @Test fun badJsonGivesEmptyList() {
        assertTrue(PexelsParser.parse("not json").isEmpty())
        assertTrue(PexelsParser.parse("{}").isEmpty())
    }
}
