package com.goodseed.shorts

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Transformer
import com.goodseed.shorts.data.*
import com.goodseed.shorts.logic.*
import com.goodseed.shorts.render.*
import com.goodseed.shorts.tts.VoiceSynth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class Screen { HOME, PASTE, VOICE, MEDIA, PREVIEW, EXPORT }

enum class Phase { IDLE, NARRATION, RENDER, SAVING, DONE, ERROR }

data class ExportUi(
    val phase: Phase = Phase.IDLE,
    val percent: Int = 0,
    val message: String = "",
    val resultUri: String? = null,
    val resultTitle: String = "",
)

data class PexelsUi(val busy: Boolean = false, val message: String = "", val results: List<PexelsVideoItem> = emptyList())
typealias PexelsVideoItem = PexelsVideo

@androidx.annotation.OptIn(UnstableApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val recentStore = RecentStore(ctx)
    val synth = VoiceSynth(ctx)

    var screen by mutableStateOf(Screen.HOME)
    var project by mutableStateOf(Project())
    var recents by mutableStateOf(recentStore.load())
    var voices by mutableStateOf<List<VoiceChoice>>(emptyList())
    var voicesState by mutableStateOf("loading") // loading | ready | none
    var export by mutableStateOf(ExportUi())
    var pexelsKey by mutableStateOf(prefs.getString("pexelsKey", "").orEmpty())
    var pexels by mutableStateOf(PexelsUi())
    var notice by mutableStateOf<String?>(null)

    private var exportJob: Job? = null
    private var transformer: Transformer? = null

    // ---------- 화면 이동 ----------
    fun go(s: Screen) { synth.stopPreview(); screen = s }
    fun back() {
        synth.stopPreview()
        screen = when (screen) {
            Screen.PASTE -> Screen.HOME
            Screen.VOICE -> Screen.PASTE
            Screen.MEDIA -> Screen.VOICE
            Screen.PREVIEW -> Screen.MEDIA
            Screen.EXPORT -> if (export.phase == Phase.RENDER || export.phase == Phase.NARRATION || export.phase == Phase.SAVING) Screen.EXPORT else Screen.PREVIEW
            Screen.HOME -> Screen.HOME
        }
    }

    fun startNew() {
        project = Project(); export = ExportUi(); pexels = PexelsUi(); go(Screen.PASTE)
    }

    // ---------- ② 글 ----------
    fun sentencesOf(raw: String): List<String> = SentenceSplitter.split(raw)

    fun acceptText(raw: String) {
        val ss = SentenceSplitter.split(raw)
        project = project.copy(rawText = raw, scenes = ss.mapIndexed { i, t -> Scene(i.toLong() + 1, t) })
    }

    // ---------- ③ 목소리 ----------
    fun loadVoices() {
        if (voicesState == "ready" && voices.isNotEmpty()) return
        viewModelScope.launch {
            voicesState = "loading"
            val v = synth.koreanVoices()
            voices = v
            voicesState = if (v.isEmpty()) "none" else "ready"
            if (project.voice == null && v.isNotEmpty()) chooseVoice(v.first())
        }
    }

    fun chooseVoice(v: VoiceChoice) { project = project.copy(voice = v); synth.select(v, project.speed.rate) }
    fun chooseSpeed(s: Speed) { project = project.copy(speed = s); synth.select(project.voice, s.rate) }
    fun previewVoice() {
        val sample = project.scenes.firstOrNull()?.text?.take(60) ?: "안녕하세요. 오늘의 묵상을 읽어 드립니다."
        synth.select(project.voice, project.speed.rate)
        synth.speakPreview(sample)
    }

    // ---------- ④ 배경·음악 ----------
    fun addMedia(uris: List<Uri>, origin: MediaOrigin = MediaOrigin.PHONE) {
        viewModelScope.launch {
            val refs = withContext(Dispatchers.IO) { uris.mapNotNull { MediaUtil.probe(ctx, it, origin) } }
            if (refs.size < uris.size) notice = "읽을 수 없는 파일 ${uris.size - refs.size}개는 건너뛰었습니다."
            project = project.copy(media = project.media + refs)
        }
    }
    fun removeMedia(id: String) {
        project = project.copy(media = project.media.filterNot { it.id == id }, scenes = project.scenes.map { if (it.mediaId == id) it.copy(mediaId = null) else it })
    }
    fun clearMedia() { project = project.copy(media = emptyList(), scenes = project.scenes.map { it.copy(mediaId = null) }) }

    fun chooseMusic(uri: Uri) {
        viewModelScope.launch {
            val m = withContext(Dispatchers.IO) { MediaUtil.probeAudio(ctx, uri) }
            if (m == null) notice = "이 파일은 음악으로 쓸 수 없습니다. 다른 음원을 골라 주세요." else project = project.copy(music = m)
        }
    }
    fun clearMusic() { project = project.copy(music = null) }
    fun setMusicVolume(v: Float) { project = project.copy(musicVolume = v) }

    fun savePexelsKey(k: String) { pexelsKey = k.trim(); prefs.edit().putString("pexelsKey", pexelsKey).apply() }

    fun searchPexels(q: String) {
        if (pexelsKey.isBlank() || q.isBlank()) return
        pexels = pexels.copy(busy = true, message = "찾는 중…")
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { PexelsClient(pexelsKey).search(q) } }
            pexels = r.fold(
                { PexelsUi(false, if (it.isEmpty()) "결과가 없습니다. 다른 낱말로 찾아 보세요." else "", it) },
                { PexelsUi(false, it.message ?: "검색에 실패했습니다.", emptyList()) },
            )
        }
    }

    fun addPexels(v: PexelsVideo) {
        pexels = pexels.copy(busy = true, message = "내려받는 중…")
        viewModelScope.launch {
            val dest = File(ctx.filesDir, "pexels/${v.id}.mp4")
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (!dest.exists() || dest.length() == 0L) PexelsClient(pexelsKey).download(v, dest)
                    MediaUtil.probe(ctx, dest.toUri(), MediaOrigin.PEXELS, v.photographer)
                }
            }
            val ref = r.getOrNull()
            if (ref != null) {
                project = project.copy(media = project.media + ref.copy(label = "Pexels · ${v.photographer}"))
                pexels = pexels.copy(busy = false, message = "추가했습니다.")
            } else pexels = pexels.copy(busy = false, message = r.exceptionOrNull()?.message ?: "추가하지 못했습니다.")
        }
    }

    // ---------- ⑤ 수정 ----------
    fun updateScene(id: Long, f: (Scene) -> Scene) { project = project.copy(scenes = project.scenes.map { if (it.id == id) f(it) else it }) }
    fun deleteScene(id: Long) { project = project.copy(scenes = project.scenes.filterNot { it.id == id }) }
    fun setFont(f: SubFont) { project = project.copy(font = f) }

    // ---------- ⑥ 저장 ----------
    fun cancelExport() {
        transformer?.cancel(); exportJob?.cancel()
        export = ExportUi(Phase.IDLE, 0, "취소했습니다.")
    }

    fun startExport() {
        if (export.phase == Phase.NARRATION || export.phase == Phase.RENDER || export.phase == Phase.SAVING) return
        val p = project
        if (p.scenes.isEmpty()) { export = ExportUi(Phase.ERROR, 0, "글이 비어 있습니다. 처음으로 돌아가 글을 넣어 주세요."); return }
        exportJob = viewModelScope.launch {
            try { runExport(p) } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) { export = ExportUi(Phase.ERROR, 0, "만들지 못했습니다: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private suspend fun runExport(p: Project) {
        val narrDir = File(ctx.cacheDir, "narration").also { it.mkdirs() }
        val outDir = File(ctx.cacheDir, "export").also { it.mkdirs() }
        // 1) 낭독 만들기(문장마다 wav — 같은 글·같은 음성이면 이전 것을 재사용)
        synth.select(p.voice, p.speed.rate)
        val raws = ArrayList<File>()
        for ((i, s) in p.scenes.withIndex()) {
            export = ExportUi(Phase.NARRATION, i * 100 / p.scenes.size, "낭독을 만드는 중… ${i + 1}/${p.scenes.size}")
            val f = File(narrDir, sha1("${p.voice?.name}|${p.speed.rate}|${s.text}") + ".wav")
            if (!f.exists() || (WavInfo.parse(f)?.durationUs ?: 0L) <= 0L) {
                val ok = synth.synthToFile(s.text, f)
                if (!ok) { export = ExportUi(Phase.ERROR, 0, "${i + 1}번째 문장의 낭독을 만들지 못했습니다. 폰의 '텍스트 음성 변환' 설정에서 한국어 음성이 설치되어 있는지 확인해 주세요."); return }
            }
            raws += f
        }
        val durs = raws.map { WavInfo.parse(it)?.durationUs ?: 0L }
        if (durs.any { it <= 0L }) { export = ExportUi(Phase.ERROR, 0, "낭독 파일 형식을 읽지 못했습니다. 다른 음성을 골라 다시 해 주세요."); return }
        val times = Timing.scenes(durs)
        // 2) 장면 길이에 맞춘 낭독 파일(앞뒤 무음)
        val padded = ArrayList<File>()
        withContext(Dispatchers.IO) {
            for ((i, f) in raws.withIndex()) {
                val dst = File(outDir, "scene_$i.wav")
                val r = WavPad.pad(f, dst, Timing.LEAD_US, times[i].durationUs)
                if (r != null) padded += dst
            }
        }
        if (padded.size != raws.size) { export = ExportUi(Phase.ERROR, 0, "이 음성의 낭독 형식(16비트 PCM 아님)을 지원하지 않습니다. 다른 음성을 골라 주세요."); return }
        // 3) 합성 입력
        val scenes = p.scenes.mapIndexed { i, s ->
            val m = p.mediaFor(s, i)
            RenderScene(s.text, s.size, m?.uri, m?.isVideo == true, m?.durationMs ?: 0L, padded[i])
        }
        val input = RenderInput(scenes, times, p.font, p.music?.uri, p.music?.durationMs ?: 0L, p.musicVolume)
        val composition = ShortsRenderer.build(ctx, input, ctx.cacheDir)
        val out = File(outDir, "result.mp4")
        export = ExportUi(Phase.RENDER, 0, "영상을 만드는 중입니다. 화면을 켜 둔 채 기다려 주세요.")
        val done = kotlinx.coroutines.CompletableDeferred<Result<File>>()
        transformer = ShortsRenderer.start(ctx, composition, out, object : ShortsRenderer.Listener {
            override fun onDone(output: File) { done.complete(Result.success(output)) }
            override fun onError(message: String, e: ExportException) { done.complete(Result.failure(Exception("${e.errorCodeName}: $message", e))) }
        })
        val poller = viewModelScope.launch {
            while (true) {
                val pct = transformer?.let { ShortsRenderer.progress(it) } ?: -1
                if (pct >= 0) export = export.copy(phase = Phase.RENDER, percent = pct)
                delay(300)
            }
        }
        val res = done.await(); poller.cancel(); transformer = null
        val file = res.getOrElse { export = ExportUi(Phase.ERROR, 0, "영상 합성에 실패했습니다: ${it.message}"); return }
        // 4) 결과 확인 후 갤러리 저장
        export = ExportUi(Phase.SAVING, 100, "갤러리에 저장하는 중…")
        val facts = withContext(Dispatchers.IO) { MediaUtil.videoFacts(file) }
        if (facts == null || facts.width != ShortsRenderer.OUT_W || facts.height != ShortsRenderer.OUT_H) {
            export = ExportUi(Phase.ERROR, 0, "영상 크기가 1080x1920 이 아닙니다(${facts?.width}x${facts?.height}). 이 폰의 영상 인코더가 지원하지 않는 것으로 보입니다.")
            return
        }
        val name = "굿씨드묵상숏츠_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.KOREA).format(java.util.Date()) + ".mp4"
        val uri = withContext(Dispatchers.IO) { MediaUtil.saveToGallery(ctx, file, name) }
        if (uri == null) { export = ExportUi(Phase.ERROR, 0, "갤러리에 저장하지 못했습니다. 폰의 저장 공간을 확인해 주세요."); return }
        withContext(Dispatchers.IO) {
            val thumb = File(recentStore.thumbDir, UUID.randomUUID().toString() + ".jpg")
            val ref = MediaRef("r", uri.toString(), true, facts.durationMs, name)
            MediaUtil.thumbnail(ctx, ref, 360)?.let { b -> thumb.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }
            recentStore.add(RecentWork(p.title, uri.toString(), System.currentTimeMillis(), thumb.absolutePath))
        }
        recents = recentStore.load()
        export = ExportUi(Phase.DONE, 100, "저장했습니다.", uri.toString(), p.title)
        outDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    override fun onCleared() { synth.shutdown(); transformer?.cancel() }
}
