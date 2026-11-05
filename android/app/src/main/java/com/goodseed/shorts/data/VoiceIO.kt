package com.goodseed.shorts.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import com.goodseed.shorts.logic.MonoPcm
import com.goodseed.shorts.logic.Pcm
import com.goodseed.shorts.logic.VoiceAudio
import java.io.File
import java.nio.ByteOrder

/** 올린 녹음 파일을 PCM 으로 읽은 결과. */
sealed class DecodeResult {
    class Ok(val pcm: MonoPcm, val truncated: Boolean) : DecodeResult()
    class Fail(val reason: String) : DecodeResult()
}

/**
 * 녹음 파일(m4a/mp3/wav/aac/ogg 등) → 모노 16비트 44.1kHz PCM.
 * wav(16비트 PCM)는 직접 읽고, 그 밖의 형식은 MediaExtractor + MediaCodec 으로 디코딩한다.
 * 파일은 먼저 앱 캐시로 복사한 뒤 읽는다(다른 앱의 파일 권한 문제를 피하고 중간에 바뀌지 않게). 소리는 앱 밖으로 보내지 않는다.
 */
object AudioDecoder {
    fun decode(ctx: Context, uri: Uri, maxMs: Long = VoiceAudio.MAX_UPLOAD_MS): DecodeResult {
        val tmp = File(ctx.cacheDir, "import/source.bin")
        return try {
            tmp.parentFile?.mkdirs()
            val ins = ctx.contentResolver.openInputStream(uri) ?: return DecodeResult.Fail("파일을 열 수 없습니다.")
            ins.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            if (tmp.length() <= 0) DecodeResult.Fail("빈 파일입니다.") else decodeFile(tmp, maxMs)
        } catch (e: Throwable) {
            DecodeResult.Fail("파일을 읽지 못했습니다.")
        } finally { runCatching { tmp.delete() } }
    }

    fun decodeFile(file: File, maxMs: Long = VoiceAudio.MAX_UPLOAD_MS): DecodeResult {
        runCatching { VoiceAudio.readWav(file) }.getOrNull()?.let { return finish(it, maxMs) }
        return decodeWithCodec(file, maxMs)
    }

    private fun finish(pcm: MonoPcm, maxMs: Long): DecodeResult {
        if (pcm.samples.isEmpty()) return DecodeResult.Fail("소리가 들어 있지 않은 파일입니다.")
        val maxSamples = (maxMs * pcm.rate / 1000L).toInt()
        return if (pcm.samples.size > maxSamples) DecodeResult.Ok(MonoPcm(pcm.samples.copyOf(maxSamples), pcm.rate), true) else DecodeResult.Ok(pcm, false)
    }

    private fun decodeWithCodec(file: File, maxMs: Long): DecodeResult {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            var track = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return DecodeResult.Fail("소리가 들어 있는 파일이 아닙니다.")
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var rate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            var float = false
            val acc = ShortAccumulator()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false; var truncated = false
            var idleLoops = 0
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        idleLoops = 0
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(oi)!!
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            val bb = ob.slice().order(ByteOrder.nativeOrder())
                            if (float) {
                                val fb = bb.asFloatBuffer(); val tmp = ShortArray(fb.remaining()); var k = 0
                                while (fb.hasRemaining()) tmp[k++] = Pcm.floatToShort(fb.get())
                                acc.add(Pcm.downmix(tmp, ch))
                            } else {
                                val sb = bb.asShortBuffer(); val tmp = ShortArray(sb.remaining()); sb.get(tmp)
                                acc.add(Pcm.downmix(tmp, ch))
                            }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (acc.size.toLong() * 1000L / rate > maxMs) { truncated = true; outputDone = true }
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    oi == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (inputDone && ++idleLoops > 300) outputDone = true }
                }
            }
            val mono = acc.toArray()
            if (mono.isEmpty()) return DecodeResult.Fail("소리를 읽지 못했습니다.")
            val pcm = MonoPcm(Pcm.resample(mono, rate, VoiceAudio.RATE), VoiceAudio.RATE)
            return DecodeResult.Ok(pcm, truncated)
        } catch (e: Throwable) {
            return DecodeResult.Fail("이 파일 형식은 읽을 수 없습니다.")
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { ex.release() }
        }
    }
}

/** 늘어나는 ShortArray. */
class ShortAccumulator {
    private var a = ShortArray(1 shl 16)
    var size = 0; private set
    fun add(s: ShortArray, len: Int = s.size) {
        if (size + len > a.size) a = a.copyOf(maxOf(a.size * 2, size + len))
        System.arraycopy(s, 0, a, size, len); size += len
    }
    fun toArray(): ShortArray = a.copyOf(size)
    fun clear() { size = 0 }
}

/** 마이크 녹음기(시험에서 바꿔 끼울 수 있게 인터페이스). */
interface VoiceRecorder {
    /** 녹음을 시작한다. 마이크를 열 수 없으면 false. onLevel: 0..1 소리 크기, 약 20회/초, 녹음 스레드에서 불린다. */
    fun start(onLevel: (Float) -> Unit): Boolean
    /** 멈추고 지금까지의 소리를 돌려준다(시작 안 했으면 null). */
    fun stop(): MonoPcm?
    val isRecording: Boolean
}

/** AudioRecord 로 44.1kHz 모노 16비트를 받는다. 한 문장 최대 3분. */
class AudioRecordRecorder : VoiceRecorder {
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val acc = ShortAccumulator()
    private val maxSamples = VoiceAudio.RATE * 180

    override val isRecording: Boolean get() = running

    @SuppressWarnings("MissingPermission")
    override fun start(onLevel: (Float) -> Unit): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(VoiceAudio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, VoiceAudio.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, VoiceAudio.RATE / 5 * 2) * 2)
        } catch (e: Throwable) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        try { r.startRecording() } catch (e: Throwable) { r.release(); return false }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return false }
        synchronized(acc) { acc.clear() }
        rec = r; running = true
        thread = Thread({
            val buf = ShortArray(VoiceAudio.RATE / 20)   // 50ms
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) break else continue }
                var peak = 0
                for (i in 0 until n) peak = maxOf(peak, Math.abs(buf[i].toInt()))
                synchronized(acc) { if (acc.size + n <= maxSamples) acc.add(buf, n) }
                onLevel((peak / 32768f).coerceIn(0f, 1f))
            }
        }, "voice-record").also { it.start() }
        return true
    }

    override fun stop(): MonoPcm? {
        val r = rec ?: return null
        running = false
        runCatching { thread?.join(1000) }
        runCatching { r.stop() }; runCatching { r.release() }
        rec = null; thread = null
        val arr = synchronized(acc) { acc.toArray() }
        return MonoPcm(arr, VoiceAudio.RATE)
    }
}

/** 녹음·구간을 소리로 들려 준다(AudioTrack). 한 번에 하나만 재생. */
class PcmPlayer {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var token = 0
    val playing: Boolean get() = track != null

    fun play(samples: ShortArray, rate: Int, onDone: () -> Unit) {
        stop()
        if (samples.isEmpty()) { onDone(); return }
        val my = ++token
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (e: Throwable) { onDone(); return }
        track = t
        Thread({
            try {
                t.play()
                var i = 0
                while (i < samples.size && token == my) {
                    val n = t.write(samples, i, minOf(8192, samples.size - i))
                    if (n <= 0) break
                    i += n
                }
                if (token == my) {   // 끝까지 틀었으면 버퍼가 비워질 때까지 기다린다
                    var guard = 0
                    while (token == my && t.playbackHeadPosition < samples.size && guard++ < 2000) Thread.sleep(10)
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { t.stop() }; runCatching { t.release() }
                val mine = (token == my)
                if (track === t) track = null
                if (mine) onDone()
            }
        }, "pcm-play").start()
    }

    fun stop() { token++; track = null }
}
