package com.goodseed.shorts

import com.goodseed.shorts.logic.*
import org.junit.Assert.*
import org.junit.Test

class SentenceSplitterTest {
    @Test fun splitsOnPeriodQuestionExclaim() {
        val r = SentenceSplitter.split("주님은 나의 목자시니 부족함이 없으리로다. 그가 나를 푸른 풀밭에 누이시며! 쉴 만한 물가로 인도하시는도다?")
        assertEquals(3, r.size)
        assertEquals("주님은 나의 목자시니 부족함이 없으리로다.", r[0])
        assertEquals("그가 나를 푸른 풀밭에 누이시며!", r[1])
        assertEquals("쉴 만한 물가로 인도하시는도다?", r[2])
    }

    @Test fun newlineIsAlwaysBoundary_blankLinesIgnored() {
        val r = SentenceSplitter.split("첫째 줄\n\n둘째 줄\r\n셋째 줄")
        assertEquals(listOf("첫째 줄", "둘째 줄", "셋째 줄"), r)
    }

    @Test fun decimalsAndVerseRefsAreNotSplit() {
        val r = SentenceSplitter.split("온도는 3.5도입니다. 요한복음 3.16절을 읽습니다.")
        assertEquals(listOf("온도는 3.5도입니다.", "요한복음 3.16절을 읽습니다."), r)
    }

    @Test fun closingQuotesStayWithSentence() {
        val r = SentenceSplitter.split("그가 말했다. \"사랑하라.\" 그리고 떠났다.")
        assertEquals(listOf("그가 말했다.", "\"사랑하라.\"", "그리고 떠났다."), r)
    }

    @Test fun ellipsisAndRepeatedMarks() {
        val r = SentenceSplitter.split("기다리자… 괜찮을 거야?! 그럼.")
        assertEquals(listOf("기다리자…", "괜찮을 거야?!", "그럼."), r)
    }

    @Test fun textWithoutTerminatorIsOneSentence() {
        assertEquals(listOf("마침표 없는 글"), SentenceSplitter.split("  마침표   없는 글  "))
    }

    @Test fun emptyAndSymbolOnlyInput() {
        assertTrue(SentenceSplitter.split("").isEmpty())
        assertTrue(SentenceSplitter.split(" \n \n ").isEmpty())
        assertTrue(SentenceSplitter.split("...").isEmpty() || SentenceSplitter.split("...").all { it.isNotBlank() })
    }

    @Test fun numberedListHeadsJoinNextSentence() {
        val r = SentenceSplitter.split("1. 감사합니다.\n2. 사랑합니다.")
        assertEquals(listOf("1. 감사합니다.", "2. 사랑합니다."), r)
    }

    @Test fun longSentenceIsBrokenButTextPreserved() {
        val long = (1..40).joinToString(" ") { "단어$it" } + "."
        val r = SentenceSplitter.split(long, maxChars = 60)
        assertTrue(r.size >= 2)
        assertTrue(r.all { it.length <= 60 })
        assertEquals(long.replace(" ", ""), r.joinToString("").replace(" ", ""))
    }

    @Test fun neverInventsText() {
        val src = "하나. 둘! 셋? 넷…\n다섯"
        val r = SentenceSplitter.split(src)
        assertEquals(src.replace(Regex("\\s"), ""), r.joinToString("").replace(Regex("\\s"), ""))
    }
}

class SubtitleWrapperTest {
    private val m: (String) -> Float = { it.length.toFloat() } // 글자 1개 = 폭 1

    @Test fun shortTextStaysOneLine() {
        assertEquals(listOf("안녕하세요"), SubtitleWrapper.wrap("안녕하세요", 10f, measure = m))
    }

    @Test fun wrapsAtSpacesAndRespectsWidth() {
        val lines = SubtitleWrapper.wrap("주님은 나의 목자시니 부족함이 없으리로다", 10f, balance = false, measure = m)
        assertTrue(lines.size >= 2)
        assertTrue(lines.all { it.length <= 10 })
        assertEquals("주님은 나의 목자시니 부족함이 없으리로다", lines.joinToString(" "))
    }

    @Test fun balanceMakesLinesEvener() {
        val text = "가나다 라마바 사아자 차카타 파하"
        val greedy = SubtitleWrapper.wrap(text, 14f, balance = false, measure = m)
        val bal = SubtitleWrapper.wrap(text, 14f, balance = true, measure = m)
        assertEquals(greedy.size, bal.size)
        val spreadG = greedy.maxOf { it.length } - greedy.minOf { it.length }
        val spreadB = bal.maxOf { it.length } - bal.minOf { it.length }
        assertTrue(spreadB <= spreadG)
        assertTrue(bal.all { it.length <= 14 })
    }

    @Test fun overlongWordIsBrokenByCharacter() {
        val lines = SubtitleWrapper.wrap("가나다라마바사아자차카타파하", 5f, balance = false, measure = m)
        assertTrue(lines.all { it.length <= 5 })
        assertEquals("가나다라마바사아자차카타파하", lines.joinToString(""))
    }

    @Test fun punctuationDoesNotStartALine() {
        val lines = SubtitleWrapper.wrap("가나다라마 바사아자차.", 5f, balance = false, measure = m)
        assertTrue(lines.drop(1).none { it.first() == '.' })
        assertEquals("가나다라마바사아자차.", lines.joinToString("").replace(" ", ""))
    }

    @Test fun neverLosesCharacters() {
        val text = "하나님이 세상을 이처럼 사랑하사 독생자를 주셨으니, 이는 그를 믿는 자마다 멸망하지 않고 영생을 얻게 하려 하심이라."
        for (w in listOf(6f, 9f, 13f, 20f)) {
            val lines = SubtitleWrapper.wrap(text, w, measure = m)
            assertEquals(text.replace(" ", ""), lines.joinToString("").replace(" ", ""))
        }
    }

    @Test fun emptyInput() {
        assertTrue(SubtitleWrapper.wrap("   ", 10f, measure = m).isEmpty())
    }

    @Test fun paginateChunksAndAvoidsLonelyLastLine() {
        val lines = listOf("a", "b", "c", "d", "e", "f", "g")
        val pages = SubtitleWrapper.paginate(lines, 3)
        assertEquals(lines, pages.flatten())
        assertTrue(pages.all { it.size <= 3 })
        val p2 = SubtitleWrapper.paginate(listOf("a", "b", "c", "d"), 3)
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), p2)
    }
}

class DuckingCurveTest {
    private val spans = listOf(SpeechSpan(1_000_000, 2_000_000), SpeechSpan(5_000_000, 6_000_000))
    private val c = DuckingCurve(spans, duckGain = 0.2f, attackUs = 200_000, releaseUs = 400_000)

    @Test fun fullVolumeOutsideSpeech() {
        assertEquals(1f, c.gainAt(0), 1e-6f)
        assertEquals(1f, c.gainAt(3_000_000), 1e-6f)
        assertEquals(1f, c.gainAt(9_000_000), 1e-6f)
    }

    @Test fun duckedDuringSpeech() {
        assertEquals(0.2f, c.gainAt(1_000_000), 1e-6f)
        assertEquals(0.2f, c.gainAt(1_500_000), 1e-6f)
        assertEquals(0.2f, c.gainAt(2_000_000), 1e-6f)
        assertEquals(0.2f, c.gainAt(5_500_000), 1e-6f)
    }

    @Test fun attackRampsDownLinearly() {
        assertEquals(1f, c.gainAt(800_000), 1e-6f)
        assertEquals(0.6f, c.gainAt(900_000), 1e-5f)   // 램프 중간
        assertEquals(0.2f, c.gainAt(1_000_000), 1e-6f)
    }

    @Test fun releaseRampsUpLinearly() {
        assertEquals(0.6f, c.gainAt(2_200_000), 1e-5f) // 400ms 중 200ms 경과 → 중간
        assertEquals(1f, c.gainAt(2_400_000), 1e-6f)
        assertEquals(1f, c.gainAt(2_400_001), 1e-6f)
    }

    @Test fun monotonicWithinRamps() {
        var prev = c.gainAt(800_000)
        for (t in 800_000L..1_000_000L step 10_000) { val g = c.gainAt(t); assertTrue(g <= prev + 1e-6f); prev = g }
        prev = c.gainAt(2_000_000)
        for (t in 2_000_000L..2_400_000L step 10_000) { val g = c.gainAt(t); assertTrue(g >= prev - 1e-6f); prev = g }
    }

    @Test fun overlappingTailsTakeTheMinimum() {
        val c2 = DuckingCurve(listOf(SpeechSpan(1_000_000, 2_000_000), SpeechSpan(2_100_000, 3_000_000)), 0.2f, 200_000, 400_000)
        // 두 번째 낭독 직전 구간: 첫 낭독의 되돌림(올라가는 중)과 두 번째의 내려가는 중 → 더 낮은 쪽
        val g = c2.gainAt(2_050_000)
        assertTrue(g < 0.6f)
        assertEquals(0.2f, c2.gainAt(2_500_000), 1e-6f)
    }

    @Test fun noSpansMeansUnity() {
        assertEquals(1f, DuckingCurve(emptyList()).gainAt(123), 0f)
    }

    @Test fun manySpansBinarySearchStillCorrect() {
        val many = (0 until 200).map { SpeechSpan(it * 3_000_000L + 1_000_000, it * 3_000_000L + 2_000_000) }
        val cc = DuckingCurve(many, 0.2f, 200_000, 400_000)
        for (k in listOf(0, 57, 199)) {
            assertEquals(0.2f, cc.gainAt(k * 3_000_000L + 1_500_000), 1e-6f)
            assertEquals(1f, cc.gainAt(k * 3_000_000L + 2_900_000), 1e-6f)
        }
    }
}

class TimingTest {
    @Test fun sceneDurationsAndOffsets() {
        val s = Timing.scenes(listOf(3_000_000L, 500_000L, 2_000_000L))
        assertEquals(3, s.size)
        assertEquals(0L, s[0].startUs)
        assertEquals(Timing.LEAD_US + 3_000_000 + Timing.GAP_US, s[0].durationUs)
        assertEquals(s[0].endUs, s[1].startUs)
        assertEquals(Timing.MIN_SCENE_US, s[1].durationUs) // 짧은 낭독은 최소 길이 보장
        assertEquals(s[1].endUs, s[2].startUs)
        assertEquals(Timing.LEAD_US + 2_000_000 + Timing.GAP_US + Timing.END_PAD_US, s[2].durationUs) // 마지막 여운
        assertEquals(s.last().endUs, Timing.totalUs(s))
    }

    @Test fun speechSpansInsideScenes() {
        val s = Timing.scenes(listOf(3_000_000L, 1_000_000L))
        for (sc in s) {
            assertTrue(sc.speechStartUs >= sc.startUs)
            assertTrue(sc.speechEndUs <= sc.endUs)
        }
        assertEquals(Timing.LEAD_US, s[0].speechStartUs)
        assertEquals(s[0].speechStartUs + 3_000_000, s[0].speechEndUs)
        assertEquals(2, Timing.speechSpans(s).size)
    }

    @Test fun cuesCoverSceneWithoutGapsAndSplitProportionally() {
        val s = Timing.scenes(listOf(4_000_000L))
        val cues = Timing.cues(s, listOf(listOf(listOf("가나다라"), listOf("마바"))))
        assertEquals(2, cues.size)
        assertEquals(s[0].startUs, cues[0].startUs)
        assertEquals(cues[0].endUs, cues[1].startUs)
        assertEquals(s[0].endUs, cues[1].endUs)
        val d0 = cues[0].endUs - cues[0].startUs
        val d1 = cues[1].endUs - cues[1].startUs
        assertEquals(2.0, d0.toDouble() / d1, 0.01) // 글자 4:2
    }

    @Test fun cuesAcrossScenesAreContiguous() {
        val s = Timing.scenes(listOf(1_000_000L, 2_000_000L, 1_500_000L))
        val cues = Timing.cues(s, s.map { listOf(listOf("문장")) })
        assertEquals(3, cues.size)
        for (i in 1 until cues.size) assertEquals(cues[i - 1].endUs, cues[i].startUs)
        assertEquals(Timing.totalUs(s), cues.last().endUs)
    }

    @Test fun musicSegmentsLoopToCoverTotal() {
        val seg = Timing.musicSegments(10_000_000, 4_000_000)
        assertEquals(listOf(MusicSegment(0, 4_000_000), MusicSegment(4_000_000, 4_000_000), MusicSegment(8_000_000, 2_000_000)), seg)
        assertEquals(10_000_000L, seg.sumOf { it.lengthUs })
        assertEquals(listOf(MusicSegment(0, 3_000_000)), Timing.musicSegments(3_000_000, 9_000_000))
        assertTrue(Timing.musicSegments(0, 5).isEmpty())
        assertTrue(Timing.musicSegments(5, 0).isEmpty())
    }
}

class WavInfoTest {
    private fun wav(rate: Int, ch: Int, bits: Int, dataLen: Int, declaredLen: Long = dataLen.toLong(), extraChunk: Boolean = false): ByteArray {
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        val b = java.io.ByteArrayOutputStream()
        b.write("RIFF".toByteArray()); b.write(le32(36L + dataLen)); b.write("WAVE".toByteArray())
        b.write("fmt ".toByteArray()); b.write(le32(16)); b.write(le16(1)); b.write(le16(ch)); b.write(le32(rate.toLong()))
        b.write(le32(rate.toLong() * ch * bits / 8)); b.write(le16(ch * bits / 8)); b.write(le16(bits))
        if (extraChunk) { b.write("LIST".toByteArray()); b.write(le32(3)); b.write(byteArrayOf(1, 2, 3, 0)) }
        b.write("data".toByteArray()); b.write(le32(declaredLen)); b.write(ByteArray(dataLen))
        return b.toByteArray()
    }

    @Test fun durationOfOneSecondMono16() {
        val bytes = wav(22050, 1, 16, 44100)
        val info = WavInfo.parse(bytes, bytes.size.toLong())!!
        assertEquals(1_000_000L, info.durationUs)
        assertEquals(22050, info.sampleRate)
    }

    @Test fun stereoAndOddChunk() {
        val bytes = wav(44100, 2, 16, 44100 * 4 * 2, extraChunk = true)
        assertEquals(2_000_000L, WavInfo.parse(bytes, bytes.size.toLong())!!.durationUs)
    }

    @Test fun streamingHeaderWithUnknownSizeUsesFileLength() {
        val bytes = wav(16000, 1, 16, 32000, declaredLen = 0xFFFFFFFFL)
        assertEquals(1_000_000L, WavInfo.parse(bytes, bytes.size.toLong())!!.durationUs)
        val zero = wav(16000, 1, 16, 32000, declaredLen = 0)
        assertEquals(1_000_000L, WavInfo.parse(zero, zero.size.toLong())!!.durationUs)
    }

    @Test fun brokenInputIsRejected() {
        assertNull(WavInfo.parse(ByteArray(0), 0))
        assertNull(WavInfo.parse("NOTAWAVEFILE_____".toByteArray(), 17))
        val truncated = wav(22050, 1, 16, 100).copyOf(20)
        assertNull(WavInfo.parse(truncated, 20))
    }
}
