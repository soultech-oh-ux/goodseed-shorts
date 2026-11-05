package com.goodseed.shorts.render

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.goodseed.shorts.logic.DuckingCurve
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 배경 음악에 거는 AudioProcessor — 낭독 구간에서 볼륨을 자동으로 낮춘다.
 * offsetUs: 이 음악 조각이 합성 시간 어디에서 시작하는지(조각마다 새 인스턴스).
 * baseVolume: 사용자가 정한 음악 기본 크기(0..1).
 * 16비트 PCM 만 다룬다(Transformer 의 오디오 파이프라인은 디코드된 PCM 16비트를 넘겨 준다).
 */
@OptIn(UnstableApi::class)
class DuckingAudioProcessor(
    private val curve: DuckingCurve,
    private val offsetUs: Long,
    private val baseVolume: Float,
) : BaseAudioProcessor() {

    private var framesDone = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush() { framesDone = 0 }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val channels = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate
        var gain = baseVolume * curve.gainAt(offsetUs + framesDone * 1_000_000L / rate)
        var untilUpdate = 0
        while (inputBuffer.remaining() >= 2 * channels) {
            if (untilUpdate == 0) { gain = baseVolume * curve.gainAt(offsetUs + framesDone * 1_000_000L / rate); untilUpdate = 32 }
            untilUpdate--
            for (c in 0 until channels) {
                val v = (inputBuffer.getShort() * gain).roundToInt().coerceIn(-32768, 32767)
                out.putShort(v.toShort())
            }
            framesDone++
        }
        out.flip()
    }
}
