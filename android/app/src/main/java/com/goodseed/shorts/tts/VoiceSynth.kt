package com.goodseed.shorts.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.goodseed.shorts.data.VoiceChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 안드로이드 기본 TextToSpeech 래퍼. 한국어 음성 목록·미리듣기·문장별 synthesizeToFile.
 * 앱 밖 서비스(유료 API)는 쓰지 않는다 — 폰에 설치된 음성 엔진만 쓴다.
 */
class VoiceSynth(context: Context) {
    private val ctx = context.applicationContext
    private val ready = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null
    private val waiting = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private var voiceMap: Map<String, Voice> = emptyMap()
    @Volatile private var currentVoice: String? = null
    @Volatile private var currentRate: Float = 1f

    init {
        tts = TextToSpeech(ctx) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { utteranceId?.let { waiting.remove(it)?.invoke(true) } }
                    @Deprecated("deprecated in API") override fun onError(utteranceId: String?) { utteranceId?.let { waiting.remove(it)?.invoke(false) } }
                    override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { waiting.remove(it)?.invoke(false) } }
                })
                voiceMap = runCatching { t.voices.orEmpty().filter { it.locale.language == "ko" }.associateBy { it.name } }.getOrDefault(emptyMap())
                ready.complete(true)
            } else ready.complete(false)
        }
    }

    suspend fun isReady(): Boolean = withTimeoutOrNull(8000) { ready.await() } ?: false

    /** 한국어 음성 목록 — 폰 안에서 만드는 음성을 앞에, 인터넷이 필요한 음성을 뒤에. */
    suspend fun koreanVoices(): List<VoiceChoice> {
        if (!isReady()) return emptyList()
        val sorted = voiceMap.values.sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenByDescending { it.quality }.thenBy { it.name })
        return sorted.mapIndexed { i, v ->
            val tag = buildString {
                append(if (v.isNetworkConnectionRequired) "인터넷 필요" else "폰 안에서 만듦")
                if (v.quality >= Voice.QUALITY_HIGH) append(" · 고품질")
            }
            VoiceChoice(v.name, "한국어 음성 ${i + 1}  ($tag)", v.isNetworkConnectionRequired)
        }
    }

    fun select(voice: VoiceChoice?, rate: Float) {
        currentVoice = voice?.name; currentRate = rate
        apply()
    }

    private fun apply() {
        val t = tts ?: return
        t.language = Locale.KOREAN
        currentVoice?.let { n -> voiceMap[n]?.let { t.voice = it } }
        t.setSpeechRate(currentRate)
    }

    /** 미리듣기(소리로 바로 재생). */
    fun speakPreview(text: String) {
        val t = tts ?: return
        apply()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview-" + System.nanoTime())
    }

    fun stopPreview() { tts?.stop() }

    /** 한 문장을 wav 파일로 만든다. 성공 여부를 돌려준다(최대 60초 기다림). */
    suspend fun synthToFile(text: String, out: File): Boolean {
        if (!isReady()) return false
        val t = tts ?: return false
        apply()
        out.parentFile?.mkdirs(); if (out.exists()) out.delete()
        val id = "syn-" + System.nanoTime()
        val r = withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                waiting[id] = { ok -> if (cont.isActive) cont.resume(ok) }
                cont.invokeOnCancellation { waiting.remove(id) }
                val rc = t.synthesizeToFile(text, Bundle(), out, id)
                if (rc != TextToSpeech.SUCCESS) { waiting.remove(id); if (cont.isActive) cont.resume(false) }
            }
        }
        waiting.remove(id)
        return r == true && out.exists() && out.length() > 44
    }

    fun shutdown() { runCatching { tts?.stop(); tts?.shutdown() }; tts = null }
}
