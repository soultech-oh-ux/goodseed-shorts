package com.goodseed.shorts.logic

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 16비트 모노 PCM. 내 목소리(녹음·올린 파일)는 모두 이 모양으로 다룬다. */
class MonoPcm(val samples: ShortArray, val rate: Int) {
    val durationMs: Long get() = if (rate <= 0) 0L else samples.size * 1000L / rate
    val durationUs: Long get() = if (rate <= 0) 0L else samples.size * 1_000_000L / rate
    fun slice(fromMs: Long, toMs: Long): MonoPcm {
        val a = msToIndex(fromMs).coerceIn(0, samples.size)
        val b = msToIndex(toMs).coerceIn(a, samples.size)
        return MonoPcm(samples.copyOfRange(a, b), rate)
    }
    fun msToIndex(ms: Long): Int = (ms * rate / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

object Pcm {
    /** 여러 채널이 섞인 16비트 샘플을 모노로(채널 평균). */
    fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved.copyOf()
        val n = interleaved.size / channels
        val out = ShortArray(n)
        for (i in 0 until n) {
            var s = 0
            for (c in 0 until channels) s += interleaved[i * channels + c]
            out[i] = (s / channels).toShort()
        }
        return out
    }

    /** 단순 선형 보간 리샘플(말소리용). from==to 면 그대로. */
    fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from <= 0 || to <= 0 || from == to || src.isEmpty()) return src.copyOf()
        val n = (src.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val f = pos - i0
            out[i] = (src[i0] * (1 - f) + src[i1] * f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    fun toMono16(interleaved: ShortArray, channels: Int, rate: Int, targetRate: Int = VoiceAudio.RATE): MonoPcm =
        MonoPcm(resample(downmix(interleaved, channels), rate, targetRate), targetRate)

    fun floatToShort(f: Float): Short = (f * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
}

/** 구간(20ms 조각) 단위의 소리 크기와 말소리 판별. */
object Energy {
    const val FRAME_MS = 20

    /** 조각마다 dBFS(꽉 찬 소리 = 0, 완전 무음 = -100). */
    fun frameDb(pcm: MonoPcm): DoubleArray {
        val fl = max(1, pcm.rate * FRAME_MS / 1000)
        val n = pcm.samples.size / fl
        val out = DoubleArray(n)
        for (f in 0 until n) {
            var acc = 0.0
            val base = f * fl
            for (i in 0 until fl) { val v = pcm.samples[base + i].toDouble(); acc += v * v }
            val rms = sqrt(acc / fl) / 32768.0
            out[f] = if (rms <= 1e-5) -100.0 else 20.0 * log10(rms)
        }
        return out
    }

    /** 말소리가 없다고 볼 때의 문턱 — 어떤 조각도 넘지 못한다. */
    const val NO_SPEECH = 1000.0
    private const val QUIET_ABS_DB = -42.0       // 이보다 작은 소리뿐이면 말소리가 없다고 본다
    private const val MIN_SPREAD_DB = 12.0       // 잡음 바닥과 큰 소리의 차이가 이보다 작으면 '말소리와 쉼의 구분'이 없다

    /**
     * 말소리 문턱(dB): 잡음 바닥(하위 5%)보다 10dB 높게(단, 잡음과 큰 소리의 한가운데를 넘지 않게), 아주 조용한 방에서도 -55dB 아래로는 내리지 않는다.
     * 잡음과 큰 소리의 차이가 12dB 미만이면 구분이 없는 것: 전체가 -42dB 보다 작으면 '말소리 없음'(NO_SPEECH),
     * 크면 처음부터 끝까지 이어진 말소리로 보아 전부 통과시킨다.
     */
    fun threshold(db: DoubleArray): Double {
        // 디지털 무음(-80dB 미만, 마이크가 0 만 보낸 조각)은 잡음 바닥 계산에서 뺀다
        val s = db.filter { it > -80.0 }.sorted().toDoubleArray()
        if (s.isEmpty()) return NO_SPEECH
        val noise = s[(s.size * 0.05).toInt().coerceIn(0, s.size - 1)]
        val loud = s[(s.size * 0.95).toInt().coerceIn(0, s.size - 1)]
        if (loud - noise < MIN_SPREAD_DB) return if (loud < QUIET_ABS_DB) NO_SPEECH else s[0] - 1.0
        return min(max(noise + 10.0, -55.0), (noise + loud) / 2.0)
    }

    /** 문턱 이상인 조각이 minFrames 개 이상 이어진 덩어리(프레임 번호 [start, end)). 짧은 딸깍 소리는 말소리로 보지 않는다. */
    fun speechRuns(db: DoubleArray, th: Double, minFrames: Int = 3): List<IntRange> {
        val runs = ArrayList<IntRange>()
        var i = 0
        while (i < db.size) {
            if (db[i] >= th) {
                var j = i
                while (j < db.size && db[j] >= th) j++
                if (j - i >= minFrames) runs += i until j
                i = j
            } else i++
        }
        return runs
    }

    /** 말소리가 전혀 없는가(전부 무음에 가까움). */
    fun isSilent(db: DoubleArray): Boolean = db.isEmpty() || db.max() < -60.0 || speechRuns(db, threshold(db)).isEmpty()
}

/** 앞뒤 무음 자동 다듬기. */
object SilenceTrim {
    const val PAD_MS = 100L

    /** 말소리가 있는 부분만 남기고(앞뒤 PAD_MS 여유) 자른다. 말소리가 없으면 null. */
    fun trim(pcm: MonoPcm, padMs: Long = PAD_MS): MonoPcm? {
        val db = Energy.frameDb(pcm)
        if (db.isEmpty()) return null
        val runs = Energy.speechRuns(db, Energy.threshold(db))
        if (runs.isEmpty() || db.max() < -60.0) return null
        val startMs = runs.first().first.toLong() * Energy.FRAME_MS - padMs
        val endMs = (runs.last().last + 1).toLong() * Energy.FRAME_MS + padMs
        return pcm.slice(max(0L, startMs), min(pcm.durationMs, endMs))
    }
}

/** 소리 크기(라우드니스 근사) 맞추기: 말소리 구간의 RMS 를 목표값으로, 피크는 넘치지 않게. ITU BS.1770 라우드니스가 아니라 근사다. */
object Loudness {
    const val TARGET_RMS_DB = -20.0
    const val MAX_GAIN_DB = 20.0
    const val MIN_GAIN_DB = -12.0
    const val PEAK_CEIL = 0.95

    /** 말소리 조각들의 RMS(dBFS). 말소리가 없으면 null. */
    fun speechRmsDb(pcm: MonoPcm): Double? {
        val db = Energy.frameDb(pcm)
        val runs = Energy.speechRuns(db, Energy.threshold(db))
        if (runs.isEmpty() || db.max() < -60.0) return null
        val fl = max(1, pcm.rate * Energy.FRAME_MS / 1000)
        var acc = 0.0; var count = 0L
        for (r in runs) for (f in r) {
            val base = f * fl
            for (i in 0 until fl) { val v = pcm.samples[base + i].toDouble(); acc += v * v; count++ }
        }
        if (count == 0L) return null
        return 20.0 * log10(sqrt(acc / count) / 32768.0)
    }

    fun peak(pcm: MonoPcm): Int { var p = 0; for (s in pcm.samples) p = max(p, abs(s.toInt())); return p }

    /** 곱할 배율(선형). 목표에 맞추되 [-12, +20]dB 로 제한하고 피크가 PEAK_CEIL 을 넘지 않게. 말소리가 없으면 1.0. */
    fun gainFor(pcm: MonoPcm): Double {
        val rms = speechRmsDb(pcm) ?: return 1.0
        val gDb = (TARGET_RMS_DB - rms).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        var g = 10.0.pow(gDb / 20.0)
        val p = peak(pcm)
        if (p > 0) g = min(g, PEAK_CEIL * 32767.0 / p)
        return g
    }

    fun apply(pcm: MonoPcm, gain: Double): MonoPcm {
        if (abs(gain - 1.0) < 1e-6) return pcm
        val out = ShortArray(pcm.samples.size) { (pcm.samples[it] * gain).roundToInt().coerceIn(-32768, 32767).toShort() }
        return MonoPcm(out, pcm.rate)
    }

    fun normalize(pcm: MonoPcm): MonoPcm = apply(pcm, gainFor(pcm))
}

/** 무음 구간(말소리 사이의 쉼). 가운데 지점이 문장 경계 후보다. */
data class SilenceGap(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
    val midMs: Long get() = (startMs + endMs) / 2
}

object SilenceGaps {
    const val MIN_GAP_MS = 300L

    fun find(pcm: MonoPcm, minGapMs: Long = MIN_GAP_MS): List<SilenceGap> {
        val db = Energy.frameDb(pcm)
        val runs = Energy.speechRuns(db, Energy.threshold(db))
        val out = ArrayList<SilenceGap>()
        for (k in 0 until runs.size - 1) {
            val a = (runs[k].last + 1).toLong() * Energy.FRAME_MS
            val b = runs[k + 1].first.toLong() * Energy.FRAME_MS
            if (b - a >= minGapMs) out += SilenceGap(a, b)
        }
        return out
    }
}

/** 문장 시작점 목록(밀리초). 문장 k 는 [starts[k], starts[k+1]) — 마지막 문장은 파일 끝까지. */
class AlignEstimate(val startsMs: List<Long>, val confident: Boolean, val gapCount: Int)

object SentenceAligner {
    const val MIN_SEG_MS = 300L
    private const val PAUSE_WEIGHT = 3          // 문장 사이 쉼을 글자 3개 길이로 본다
    private const val LEAD_KEEP_MS = 150L

    /** 문장의 '읽는 길이' 비중: 글자·숫자 수(공백·부호 제외) + 쉼. */
    fun weight(sentence: String, last: Boolean): Int = max(1, sentence.count { it.isLetterOrDigit() }) + if (last) 0 else PAUSE_WEIGHT

    /**
     * 올린 녹음에서 문장 경계를 추정한다. ①무음 구간(≥0.3초)의 가운데를 후보로 ②문장 글자 수 비례 위치에
     * 가장 가깝게(그리고 쉼이 긴 곳을 조금 더 선호) 앞에서부터 차례로 고른다. 후보가 모자라면 비례 위치 근처에서
     * 가장 조용한 곳으로 맞춘다. 추정일 뿐이라 화면에서 고칠 수 있다.
     */
    fun estimate(pcm: MonoPcm, sentences: List<String>): AlignEstimate {
        val n = sentences.size
        val total = pcm.durationMs
        if (n == 0) return AlignEstimate(emptyList(), true, 0)
        val db = Energy.frameDb(pcm)
        val runs = Energy.speechRuns(db, Energy.threshold(db))
        if (runs.isEmpty() || db.max() < -60.0) return AlignEstimate(evenStarts(n, total), false, 0)
        val speechStart = runs.first().first.toLong() * Energy.FRAME_MS
        val speechEnd = (runs.last().last + 1).toLong() * Energy.FRAME_MS
        val first = max(0L, speechStart - LEAD_KEEP_MS)
        if (n == 1) return AlignEstimate(listOf(first), true, 0)
        val gaps = SilenceGaps.find(pcm)
        val w = sentences.mapIndexed { i, s -> weight(s, i == n - 1) }
        val sumW = w.sum().toDouble()
        val span = max(1L, speechEnd - speechStart).toDouble()
        val expected = LongArray(n - 1)                    // expected[k-1] = 문장 k 의 시작
        var cum = 0
        for (k in 1 until n) { cum += w[k - 1]; expected[k - 1] = speechStart + (span * cum / sumW).toLong() }

        val picked: LongArray
        val confident: Boolean
        if (gaps.size >= n - 1) {
            picked = pickByDp(gaps, expected, span); confident = true
        } else {
            picked = snapToQuiet(db, gaps, expected, span); confident = false
        }
        val starts = ArrayList<Long>(n)
        starts += first
        for (b in picked) starts += b
        return AlignEstimate(enforce(starts, total), confident, gaps.size)
    }

    /** 후보 중 n-1 개를 앞에서부터 차례로 골라 (비례 위치와의 거리² − 쉼 길이 보너스)의 합이 최소가 되게(동적계획). */
    private fun pickByDp(gaps: List<SilenceGap>, expected: LongArray, span: Double): LongArray {
        val m = gaps.size; val k = expected.size
        val inf = Double.MAX_VALUE / 4
        val cost = Array(k) { DoubleArray(m) { inf } }
        val from = Array(k) { IntArray(m) { -1 } }
        fun c(kk: Int, j: Int): Double {
            val d = (gaps[j].midMs - expected[kk]) / span
            return d * d * 100.0 - 0.15 * min(gaps[j].lengthMs, 1000L) / 1000.0
        }
        for (j in 0 until m) cost[0][j] = c(0, j)
        for (kk in 1 until k) {
            var best = inf; var bestJ = -1
            for (j in 0 until m) {
                if (j > 0 && cost[kk - 1][j - 1] < best) { best = cost[kk - 1][j - 1]; bestJ = j - 1 }
                if (bestJ >= 0) { cost[kk][j] = best + c(kk, j); from[kk][j] = bestJ }
            }
        }
        var endJ = -1; var endC = inf
        for (j in 0 until m) if (cost[k - 1][j] < endC) { endC = cost[k - 1][j]; endJ = j }
        val out = LongArray(k)
        var j = endJ
        for (kk in k - 1 downTo 0) { out[kk] = gaps[j].midMs; j = from[kk][j] }
        return out
    }

    /** 후보가 모자랄 때: 비례 위치 가까이의 후보(±15% 구간)를 쓰고, 없으면 ±400ms 안에서 가장 조용한 조각. */
    private fun snapToQuiet(db: DoubleArray, gaps: List<SilenceGap>, expected: LongArray, span: Double): LongArray {
        val tol = max(600.0, span / (expected.size + 1) * 0.5).toLong()
        val used = HashSet<Int>()
        return LongArray(expected.size) { kk ->
            val e = expected[kk]
            val gi = gaps.indices.filter { it !in used }.minByOrNull { abs(gaps[it].midMs - e) }
            if (gi != null && abs(gaps[gi].midMs - e) <= tol) { used += gi; gaps[gi].midMs }
            else {
                val lo = max(0, ((e - 400) / Energy.FRAME_MS).toInt()); val hi = min(db.size - 1, ((e + 400) / Energy.FRAME_MS).toInt())
                if (lo > hi) e else {
                    var bf = lo
                    for (f in lo..hi) if (db[f] < db[bf]) bf = f
                    bf.toLong() * Energy.FRAME_MS + Energy.FRAME_MS / 2
                }
            }
        }
    }

    private fun evenStarts(n: Int, total: Long): List<Long> = List(n) { it * total / n }

    /** 순서·최소 길이(MIN_SEG_MS)·파일 길이를 지키게 정리한다. */
    internal fun enforce(starts: List<Long>, total: Long): List<Long> {
        val out = starts.toMutableList()
        val n = out.size
        out[0] = out[0].coerceIn(0L, max(0L, total - MIN_SEG_MS * n))
        for (i in 1 until n) out[i] = max(out[i], out[i - 1] + MIN_SEG_MS)
        // 뒤에서부터 파일 끝 안에 들어오게 당겨 넣는다
        for (i in n - 1 downTo 0) {
            val limit = total - MIN_SEG_MS * (n - i)
            if (out[i] > limit) out[i] = max(0L, limit)
        }
        for (i in 1 until n) out[i] = max(out[i], out[i - 1])
        return out
    }
}

/** 자막 맞추기 화면의 수동 이동. */
object BoundaryEdit {
    /** 문장 index 의 시작점을 deltaMs 만큼 옮긴다. 앞 문장·뒤 문장(마지막은 파일 끝)과 MIN_SEG_MS 이상 떨어지게 제한. 옮길 수 없으면 그대로. */
    fun shift(starts: List<Long>, index: Int, deltaMs: Long, totalMs: Long): List<Long> {
        if (index !in starts.indices) return starts
        val lo = if (index == 0) 0L else starts[index - 1] + SentenceAligner.MIN_SEG_MS
        val hi = (starts.getOrNull(index + 1) ?: totalMs) - SentenceAligner.MIN_SEG_MS
        if (lo > hi) return starts
        val v = (starts[index] + deltaMs).coerceIn(lo, hi)
        return starts.toMutableList().also { it[index] = v }
    }

    /** 문장 index 의 구간 [시작, 끝) (밀리초). */
    fun span(starts: List<Long>, index: Int, totalMs: Long): Pair<Long, Long> = starts[index] to (starts.getOrNull(index + 1) ?: totalMs)
}

object VoiceAudio {
    const val RATE = 44100
    const val MAX_UPLOAD_MS = 10 * 60 * 1000L

    /** 모노 16비트 wav 로 쓴다(헤더 44바이트). */
    fun writeWav(file: File, pcm: MonoPcm) {
        val bytes = pcm.samples.size * 2L
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            f.write(WavPad.header(WavInfo(pcm.rate, 1, 16, bytes), bytes))
            val buf = java.nio.ByteBuffer.allocate(64 * 1024).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < pcm.samples.size) {
                buf.clear()
                val n = min(buf.capacity() / 2, pcm.samples.size - i)
                for (k in 0 until n) buf.putShort(pcm.samples[i + k])
                f.write(buf.array(), 0, n * 2)
                i += n
            }
        }
    }

    /** 16비트 PCM wav 를 모노로 읽는다. 다른 형식이면 null(그땐 MediaCodec 경로로). */
    fun readWav(file: File): MonoPcm? {
        val info = WavInfo.parse(file) ?: return null
        if (!info.isPcm16 || info.channels < 1 || info.sampleRate <= 0) return null
        val frames = info.dataBytes / info.frameBytes
        if (frames <= 0 || frames * info.channels > Int.MAX_VALUE / 2) return null
        val shorts = ShortArray((frames * info.channels).toInt())
        RandomAccessFile(file, "r").use { f ->
            f.seek(info.dataOffset.toLong())
            val bytes = ByteArray(shorts.size * 2)
            f.readFully(bytes)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        }
        return Pcm.toMono16(shorts, info.channels, info.sampleRate)
    }

    fun fmtMs(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        return "%d:%02d.%d".format(t / 60000, t / 1000 % 60, t / 100 % 10)
    }
}

/** 프로젝트별 녹음 폴더(앱 내부 저장소). */
object RecordingStore {
    fun dirFor(root: File, projectId: String): File = File(root, projectId).also { it.mkdirs() }
    /** 장면 번호는 글을 다시 나누면 바뀔 수 있으므로 파일 이름에 만든 시각을 넣어 겹치지 않게 한다. */
    fun sceneFile(dir: File, sceneId: Long, stamp: Long = System.currentTimeMillis()): File = File(dir, "s${sceneId}_$stamp.wav")

    /** keepId 가 아닌 프로젝트 폴더 중 마지막 수정이 maxAgeMs 보다 오래된 것을 지운다. 지운 폴더 수. */
    fun cleanOld(root: File, keepId: String?, maxAgeMs: Long, now: Long = System.currentTimeMillis()): Int {
        var n = 0
        root.listFiles()?.forEach { d ->
            if (d.isDirectory && d.name != keepId && now - d.lastModified() > maxAgeMs) { if (d.deleteRecursively()) n++ }
        }
        return n
    }
}

/** 문장 하나의 녹음 처리: 앞뒤 무음 다듬기 → 소리 크기 맞추기. */
sealed class ClipResult {
    class Ok(val pcm: MonoPcm, val boostDb: Double, val clipped: Boolean) : ClipResult() {
        /** 안내가 필요한가(너무 작아 많이 키웠거나, 너무 커서 찌그러졌을 수 있음). */
        val tooQuiet: Boolean get() = boostDb > 14.0
    }
    object Silent : ClipResult()      // 말소리가 없다
    object TooShort : ClipResult()    // 0.3초도 안 되는 소리
}

object VoiceClip {
    const val MIN_SPEECH_MS = 300L
    private const val CLIP_PEAK = 32000

    fun process(raw: MonoPcm): ClipResult {
        val peakBefore = Loudness.peak(raw)
        val trimmed = SilenceTrim.trim(raw) ?: return ClipResult.Silent
        if (trimmed.durationMs - 2 * SilenceTrim.PAD_MS < MIN_SPEECH_MS) return ClipResult.TooShort
        val g = Loudness.gainFor(trimmed)
        val boost = 20.0 * log10(g)
        return ClipResult.Ok(Loudness.apply(trimmed, g), boost, peakBefore >= CLIP_PEAK)
    }
}
