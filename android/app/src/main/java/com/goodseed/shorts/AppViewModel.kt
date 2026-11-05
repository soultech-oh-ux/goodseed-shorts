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
import com.goodseed.shorts.data.UpdateManager
import com.goodseed.shorts.tts.VoiceSynth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class Screen { HOME, PASTE, TEMPLATE, VOICE, RECORD, ALIGN, MEDIA, PREVIEW, EXPORT }

enum class Phase { IDLE, NARRATION, RENDER, SAVING, DONE, ERROR }

data class ExportUi(
    val phase: Phase = Phase.IDLE,
    val percent: Int = 0,
    val message: String = "",
    val resultUri: String? = null,
    val resultTitle: String = "",
)

/** 문장별 녹음 화면 상태. 소리 크기·경과 시간은 녹음 스레드에서 갱신하므로 따로 둔다(recLevel/recMillis). */
data class RecUi(
    val index: Int = 0,
    val recording: Boolean = false,
    val saving: Boolean = false,
    val playing: Boolean = false,
    val message: String = "",
    val warn: Boolean = false,
    val micDenied: Boolean = false,    // 마이크 권한을 거부했을 때 안내 창
)

/** 올린 녹음 파일의 문장 경계 맞추기 화면 상태. */
class AlignUi(
    val pcm: MonoPcm,
    val starts: List<Long>,
    val confident: Boolean,
    val truncated: Boolean = false,
    val playing: Int = -1,
    val saving: Boolean = false,
    val message: String = "",
) {
    fun copy(starts: List<Long> = this.starts, playing: Int = this.playing, saving: Boolean = this.saving, message: String = this.message) =
        AlignUi(pcm, starts, confident, truncated, playing, saving, message)
}

data class ImportUi(val busy: Boolean = false, val message: String = "", val warn: Boolean = false)

enum class UpdPhase { IDLE, AVAILABLE, DOWNLOADING, READY, ERROR, NEED_PERMISSION }

data class UpdateUi(
    val info: UpdateInfo? = null,
    val phase: UpdPhase = UpdPhase.IDLE,
    val percent: Int = 0,
    val message: String = "",          // 진행·오류·수동 확인 결과 문구
    val bannerHidden: Boolean = false, // [나중에]
    val showHowTo: Boolean = false,
    val checking: Boolean = false,
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
    var update by mutableStateOf(UpdateUi())
    var whatsNew by mutableStateOf<List<String>?>(null)    // 업데이트 후 첫 실행 안내(없으면 null)
    var rec by mutableStateOf(RecUi())
    var recLevel by mutableStateOf(0f)                     // 0..1, 녹음 중 소리 크기
    var recMillis by mutableStateOf(0L)                    // 녹음 경과 시간
    var align by mutableStateOf<AlignUi?>(null)
    var voiceImport by mutableStateOf(ImportUi())
    var voiceFillAsk by mutableStateOf(false)              // 녹음 안 한 문장을 AI 목소리로 채울지 묻는 창

    internal var recorder: VoiceRecorder = AudioRecordRecorder()   // 시험에서 바꿔 끼운다
    private val player = PcmPlayer()
    private var recStartNs = 0L
    private val recRoot: File get() = File(ctx.filesDir, "recordings")

    private val updater = UpdateManager(ctx)
    private val currentVersion: Semver = Semver.parse(BuildConfig.VERSION_NAME) ?: Semver(0, 0, 0)
    private val cancelDownload = java.util.concurrent.atomic.AtomicBoolean(false)
    private var downloadedApk: File? = null

    init { if (startupChecks) afterStart() }

    companion object {
        /** 시험에서 끈다(시작할 때의 네트워크 확인·첫 실행 안내를 하지 않게). */
        @Volatile var startupChecks = true
    }

    private var exportJob: Job? = null
    private var transformer: Transformer? = null

    // ---------- 새 버전 안내·업데이트 ----------
    private fun today(): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    /** 앱 시작: ①업데이트 후 첫 실행 안내 ②하루 한 번 새 버전 확인(소리 없이). */
    private fun afterStart() {
        val cur = BuildConfig.VERSION_NAME
        val wasUpdated = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pi.lastUpdateTime - pi.firstInstallTime > 60_000L
        }.getOrDefault(false)
        if (WhatsNew.shouldShow(prefs.getString("lastRunVersion", null), cur, wasUpdated)) whatsNew = WhatsNew.notesFor(cur)
        prefs.edit().putString("lastRunVersion", cur).apply()
        val pending = prefs.getBoolean("updPending", false)
        if (UpdateLogic.shouldAutoCheck(prefs.getString("updLastDay", null), today()) || pending) checkUpdate(manual = false)
    }

    fun dismissWhatsNew() { whatsNew = null }

    /** manual=false: 결과가 없거나 실패해도 아무 표시도 하지 않는다. manual=true([업데이트 확인] 버튼): 결과를 문구로 알린다. */
    fun checkUpdate(manual: Boolean) {
        if (update.checking || update.phase == UpdPhase.DOWNLOADING) return
        update = update.copy(checking = true, message = if (manual) "새 버전을 확인하는 중…" else update.message)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { updater.check(currentVersion) }
            if (r.reached) prefs.edit().putString("updLastDay", today()).apply()
            val info = r.info
            if (info != null) {
                val dismissedToday = prefs.getString("updDismissed", null) == "${info.version}@${today()}"
                prefs.edit().putBoolean("updPending", !dismissedToday).apply()
                update = update.copy(info = info, phase = if (update.phase == UpdPhase.IDLE || update.phase == UpdPhase.ERROR) UpdPhase.AVAILABLE else update.phase,
                    checking = false, bannerHidden = if (manual) false else dismissedToday, message = "")
            } else {
                prefs.edit().putBoolean("updPending", false).apply()
                update = update.copy(checking = false, message = if (!manual) "" else if (r.reached) "지금 쓰는 버전(${currentVersion})이 가장 새 버전입니다." else "새 버전을 확인하지 못했습니다. 인터넷 연결을 확인해 주세요.")
            }
        }
    }

    fun laterUpdate() {
        update.info?.let { prefs.edit().putString("updDismissed", "${it.version}@${today()}").putBoolean("updPending", false).apply() }
        update = update.copy(bannerHidden = true)
    }

    fun showUpdateHowTo(show: Boolean) { update = update.copy(showHowTo = show) }
    fun closePermissionGuide() { update = update.copy(phase = UpdPhase.AVAILABLE, message = "") }
    fun openInstallSettings() { updater.openInstallPermissionSettings(); closePermissionGuide() }
    fun openReleasePage() { updater.openUrl(update.info?.pageUrl ?: UpdateLogic.RELEASES_PAGE) }

    /** [업데이트]: 설치 허용 확인 → 받기(진행률) → sha256 검증 → 시스템 설치 화면. 사용자의 누름 없이는 시작하지 않는다. */
    fun startUpdate() {
        val info = update.info ?: return
        if (update.phase == UpdPhase.DOWNLOADING) return
        if (update.phase == UpdPhase.READY) { downloadedApk?.takeIf { it.isFile }?.let { if (updater.openInstaller(it)) return } }
        if (!updater.canInstall()) { update = update.copy(phase = UpdPhase.NEED_PERMISSION, message = ""); return }
        cancelDownload.set(false)
        update = update.copy(phase = UpdPhase.DOWNLOADING, percent = 0, message = "새 버전을 받는 중입니다…")
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { updater.download(info, { cancelDownload.get() }, { pct -> update = update.copy(percent = pct) }) }
            when (r) {
                is UpdateManager.Download.Ready -> {
                    downloadedApk = r.file
                    val opened = updater.openInstaller(r.file)
                    update = update.copy(phase = if (opened) UpdPhase.READY else UpdPhase.ERROR, percent = 100,
                        message = if (opened) "설치 화면이 열립니다. 화면의 [업데이트] 또는 [설치]를 눌러 주세요." else "설치 화면을 열지 못했습니다. [업데이트 방법]의 직접 받기를 이용해 주세요.")
                }
                UpdateManager.Download.HashMismatch -> update = update.copy(phase = UpdPhase.ERROR, message = "받은 파일이 올바르지 않아 설치하지 않았습니다. 잠시 뒤 다시 시도하거나 직접 받아 주세요.")
                UpdateManager.Download.Cancelled -> update = update.copy(phase = UpdPhase.AVAILABLE, message = "")
                is UpdateManager.Download.Failed -> update = update.copy(phase = UpdPhase.ERROR, message = "받지 못했습니다. 인터넷 연결을 확인하고 다시 눌러 주세요.")
            }
        }
    }

    fun cancelUpdateDownload() { cancelDownload.set(true) }

    // ---------- 화면 이동 ----------
    fun go(s: Screen) { stopAllSound(); screen = s }
    private fun stopAllSound() {
        synth.stopPreview(); player.stop()
        if (rec.recording) { recorder.stop(); recLevel = 0f; rec = rec.copy(recording = false) }   // 녹음 중 화면을 떠나면 그 녹음은 버린다
        if (rec.playing) rec = rec.copy(playing = false)
        align?.let { if (it.playing >= 0) align = it.copy(playing = -1) }
    }
    fun back() {
        stopAllSound()
        screen = when (screen) {
            Screen.RECORD -> Screen.VOICE
            Screen.ALIGN -> { align = null; Screen.VOICE }
            Screen.PASTE -> Screen.HOME
            Screen.TEMPLATE -> Screen.PASTE
            Screen.VOICE -> Screen.TEMPLATE
            Screen.MEDIA -> Screen.VOICE
            Screen.PREVIEW -> Screen.MEDIA
            Screen.EXPORT -> if (export.phase == Phase.RENDER || export.phase == Phase.NARRATION || export.phase == Phase.SAVING) Screen.EXPORT else Screen.PREVIEW
            Screen.HOME -> Screen.HOME
        }
    }

    /** 홈에서 템플릿을 먼저 골랐을 때(또는 마지막으로 쓴 템플릿)부터 시작한다. */
    fun startNew(templateId: String? = null) {
        val t = Templates.byId(templateId ?: lastTemplateId)
        project = Project(templateId = t.id, font = t.font); export = ExportUi(); pexels = PexelsUi(); rec = RecUi(); align = null; voiceImport = ImportUi()
        val keep = project.id
        viewModelScope.launch(Dispatchers.IO) { runCatching { RecordingStore.cleanOld(recRoot, keep, 7L * 86_400_000L) } }   // 7일 넘게 안 쓴 예전 녹음 정리
        go(Screen.PASTE)
    }

    // ---------- ② 글 ----------
    fun sentencesOf(raw: String): List<String> = SentenceSplitter.split(raw)

    fun acceptText(raw: String) {
        val ss = SentenceSplitter.split(raw)
        // 글을 다시 나눠도 같은 문장의 내 목소리 녹음은 이어서 쓴다
        val pool = project.scenes.filter { it.myVoice != null }.toMutableList()
        val scenes = ss.mapIndexed { i, t ->
            val old = pool.firstOrNull { it.text == t }?.also { pool.remove(it) }
            Scene(i.toLong() + 1, t, myVoice = old?.myVoice, myVoiceMs = old?.myVoiceMs ?: 0)
        }
        pool.forEach { it.myVoice?.let { f -> runCatching { File(f).delete() } } }
        project = project.copy(rawText = raw, scenes = scenes)
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

    // ---------- 내 목소리 ----------
    fun setVoiceMode(m: VoiceMode) { project = project.copy(voiceMode = m); loadVoices() }   // 내 목소리에서도 빈 문장을 AI 목소리로 채울 수 있게 음성 목록은 불러 둔다

    fun openRecord() {
        val first = project.scenes.indexOfFirst { it.myVoice == null }.let { if (it < 0) 0 else it }
        rec = RecUi(index = first); recLevel = 0f; recMillis = 0
        go(Screen.RECORD)
    }

    fun recSelect(i: Int) { stopAllSound(); rec = rec.copy(index = i.coerceIn(0, (project.scenes.size - 1).coerceAtLeast(0)), message = "", warn = false) }
    fun recNext() { if (rec.recording || rec.saving) return; if (rec.index < project.scenes.lastIndex) recSelect(rec.index + 1) else go(Screen.VOICE) }
    fun recPrev() { if (rec.recording || rec.saving) return; recSelect(rec.index - 1) }
    fun micDenied(denied: Boolean) { rec = rec.copy(micDenied = denied) }

    fun startRecording() {
        if (rec.recording || rec.saving) return
        stopAllSound()
        recStartNs = System.nanoTime(); recMillis = 0; recLevel = 0f
        val ok = recorder.start { lv -> recLevel = lv; recMillis = (System.nanoTime() - recStartNs) / 1_000_000L }
        rec = if (ok) rec.copy(recording = true, playing = false, message = "", warn = false, micDenied = false)
        else rec.copy(message = "마이크를 열 수 없습니다. 다른 앱이 마이크를 쓰고 있지 않은지 확인하고 다시 눌러 주세요.", warn = true)
    }

    fun stopRecording() {
        if (!rec.recording) return
        val raw = recorder.stop(); recLevel = 0f
        val scene = project.scenes.getOrNull(rec.index)
        if (raw == null || scene == null) { rec = rec.copy(recording = false); return }
        rec = rec.copy(recording = false, saving = true, message = "녹음을 다듬는 중…", warn = false)
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) { VoiceClip.process(raw) }
            when (res) {
                ClipResult.Silent -> rec = rec.copy(saving = false, warn = true, message = "소리가 들리지 않았습니다. 폰 마이크를 가리지 않았는지 확인하고 다시 녹음해 주세요.")
                ClipResult.TooShort -> rec = rec.copy(saving = false, warn = true, message = "너무 짧게 녹음되었습니다. [● 녹음]을 누른 뒤 문장을 끝까지 읽고 [■ 멈춤]을 눌러 주세요.")
                is ClipResult.Ok -> {
                    val file = withContext(Dispatchers.IO) { RecordingStore.sceneFile(RecordingStore.dirFor(recRoot, project.id), scene.id).also { VoiceAudio.writeWav(it, res.pcm) } }
                    val prev = project.scenes.firstOrNull { it.id == scene.id }?.myVoice
                    updateScene(scene.id) { it.copy(myVoice = file.absolutePath, myVoiceMs = res.pcm.durationMs) }
                    if (prev != null && prev != file.absolutePath) runCatching { File(prev).delete() }
                    val hint = when {
                        res.clipped -> "소리가 너무 커서 일부가 찌그러졌을 수 있습니다. 폰을 조금 멀리 두고 다시 녹음해 보세요."
                        res.tooQuiet -> "목소리가 작아 소리를 키웠습니다. 폰을 입 가까이에 두고 다시 녹음하면 더 깨끗합니다."
                        else -> ""
                    }
                    rec = rec.copy(saving = false, warn = hint.isNotEmpty(), message = hint)
                }
            }
        }
    }

    fun playRecorded(index: Int = rec.index) {
        val f = project.scenes.getOrNull(index)?.myVoice?.let { File(it) } ?: return
        if (rec.recording) return
        viewModelScope.launch {
            val pcm = withContext(Dispatchers.IO) { VoiceAudio.readWav(f) } ?: run { rec = rec.copy(warn = true, message = "녹음 파일을 읽지 못했습니다. 다시 녹음해 주세요."); return@launch }
            rec = rec.copy(playing = true)
            player.play(pcm.samples, pcm.rate) { rec = rec.copy(playing = false) }
        }
    }
    fun stopPlayback() { player.stop(); rec = rec.copy(playing = false) }

    /** [내 목소리] 단계의 [다음]: 녹음이 없으면 막고, 일부만 있으면 나머지를 AI 목소리로 채울지 묻는다. */
    fun voiceNext() {
        val p = project
        if (p.voiceMode == VoiceMode.AI) { go(Screen.MEDIA); return }
        when {
            p.recordedCount == 0 -> {}
            p.missingCount > 0 -> voiceFillAsk = true
            else -> go(Screen.MEDIA)
        }
    }
    fun fillMissingWithAi() { voiceFillAsk = false; go(Screen.MEDIA) }
    fun closeFillAsk() { voiceFillAsk = false }

    // 녹음 파일 올리기
    fun importVoiceFile(uri: Uri) {
        if (voiceImport.busy || project.scenes.isEmpty()) return
        voiceImport = ImportUi(true, "녹음 파일을 읽는 중입니다. 길면 조금 걸립니다…")
        val sentences = project.scenes.map { it.text }
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { AudioDecoder.decode(ctx, uri) }) {
                is DecodeResult.Fail -> voiceImport = ImportUi(false, r.reason + " m4a, mp3, wav, aac 같은 녹음 파일을 골라 주세요.", true)
                is DecodeResult.Ok -> {
                    val prepared = withContext(Dispatchers.Default) {
                        if (Energy.isSilent(Energy.frameDb(r.pcm))) null
                        else Loudness.normalize(r.pcm).let { it to SentenceAligner.estimate(it, sentences) }
                    }
                    if (prepared == null) voiceImport = ImportUi(false, "이 파일에서 말소리를 찾지 못했습니다. 다른 파일을 골라 주세요.", true)
                    else {
                        voiceImport = ImportUi()
                        align = AlignUi(prepared.first, prepared.second.startsMs, prepared.second.confident, r.truncated)
                        go(Screen.ALIGN)
                    }
                }
            }
        }
    }

    fun alignShift(i: Int, deltaMs: Long) {
        val a = align ?: return
        player.stop()
        align = a.copy(starts = BoundaryEdit.shift(a.starts, i, deltaMs, a.pcm.durationMs), playing = -1, message = "")
    }

    fun alignPlay(i: Int) {
        val a = align ?: return
        if (a.playing == i) { player.stop(); align = a.copy(playing = -1); return }
        val (s, e) = BoundaryEdit.span(a.starts, i, a.pcm.durationMs)
        val seg = a.pcm.slice(s, e)
        align = a.copy(playing = i)
        player.play(seg.samples, seg.rate) { align?.let { cur -> if (cur.playing == i) align = cur.copy(playing = -1) } }
    }

    /** [확정]: 구간마다 잘라 앞뒤 무음을 다듬고 문장별 녹음으로 저장한다. 소리가 없는 구간이 있으면 어느 문장인지 알려 주고 멈춘다. */
    fun alignConfirm() {
        val a = align ?: return
        if (a.saving) return
        player.stop()
        align = a.copy(saving = true, playing = -1, message = "문장별로 저장하는 중…")
        val scenes = project.scenes
        val projectId = project.id
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val clips = scenes.indices.map { i -> val (s, e) = BoundaryEdit.span(a.starts, i, a.pcm.durationMs); SilenceTrim.trim(a.pcm.slice(s, e)) }
                val bad = clips.indexOfFirst { it == null || it.durationMs - 2 * SilenceTrim.PAD_MS < VoiceClip.MIN_SPEECH_MS }
                if (bad >= 0) return@withContext bad to null
                val dir = RecordingStore.dirFor(recRoot, projectId)
                val stamp = System.currentTimeMillis()
                val files = scenes.mapIndexed { i, sc -> RecordingStore.sceneFile(dir, sc.id, stamp).also { VoiceAudio.writeWav(it, clips[i]!!) } to clips[i]!!.durationMs }
                -1 to files
            }
            val files = saved.second
            if (files == null) {
                align = a.copy(saving = false, message = "${saved.first + 1}번째 문장 구간에 소리가 거의 없습니다. 그 문장의 시작 시간을 고쳐 주세요.")
                return@launch
            }
            val olds = project.scenes.mapNotNull { it.myVoice }
            project = project.copy(scenes = project.scenes.mapIndexed { i, sc -> if (i < files.size) sc.copy(myVoice = files[i].first.absolutePath, myVoiceMs = files[i].second) else sc })
            olds.forEach { o -> if (files.none { it.first.absolutePath == o }) runCatching { File(o).delete() } }
            align = null
            go(Screen.VOICE)
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
    /** 글을 고치면 그 문장의 내 목소리 녹음은 더 이상 맞지 않으므로 지운다. */
    fun updateScene(id: Long, f: (Scene) -> Scene) {
        project = project.copy(scenes = project.scenes.map {
            if (it.id != id) it else {
                val n = f(it)
                if (n.text != it.text && n.myVoice != null) { runCatching { File(n.myVoice).delete() }; n.copy(myVoice = null, myVoiceMs = 0) } else n
            }
        })
    }
    fun deleteScene(id: Long) {
        project.scenes.firstOrNull { it.id == id }?.myVoice?.let { runCatching { File(it).delete() } }
        project = project.copy(scenes = project.scenes.filterNot { it.id == id })
    }
    fun setFont(f: SubFont) { project = project.copy(font = f) }

    // ---------- 템플릿 ----------
    private val lastTemplateId: String get() = prefs.getString("templateId", Templates.DEFAULT.id).orEmpty()

    /** 템플릿을 고르면 그 템플릿의 기본 글꼴도 따라간다(이후 글꼴만 따로 바꿀 수 있다). */
    fun chooseTemplate(id: String) {
        val t = Templates.byId(id)
        project = project.copy(templateId = t.id, font = t.font)
        prefs.edit().putString("templateId", t.id).apply()
    }
    fun setHeader(title: String, source: String) { project = project.copy(headerTitle = title, headerSource = source) }

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
            // 내 목소리: 녹음해 둔 파일을 그대로 낭독 줄로 쓴다(녹음이 없는 문장만 아래에서 AI 목소리로 만든다)
            val mine = if (p.voiceMode == VoiceMode.MINE) s.myVoice?.let { File(it) }?.takeIf { it.isFile && (WavInfo.parse(it)?.durationUs ?: 0L) > 0L } else null
            if (mine != null) { raws += mine; continue }
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
        val tpl = p.template
        val endTexts = if (tpl.endCard != null) FrameComposer.endCardTexts(p.headerTitle, p.headerSource) else null
        val endUs = if (endTexts != null) tpl.endCard!!.durationMs * 1000L else 0L
        val padded = ArrayList<File>()
        withContext(Dispatchers.IO) {
            for ((i, f) in raws.withIndex()) {
                val dst = File(outDir, "scene_$i.wav")
                // 끝 화면이 붙으면 낭독 줄도 그만큼 무음으로 이어 영상과 길이가 같게 한다
                val r = WavPad.pad(f, dst, Timing.LEAD_US, times[i].durationUs + if (i == raws.lastIndex) endUs else 0L)
                if (r != null) padded += dst
            }
        }
        if (padded.size != raws.size) { export = ExportUi(Phase.ERROR, 0, "이 음성의 낭독 형식(16비트 PCM 아님)을 지원하지 않습니다. 다른 음성을 골라 주세요."); return }
        // 3) 합성 입력
        val scenes = p.scenes.mapIndexed { i, s ->
            val m = p.mediaFor(s, i)
            RenderScene(s.text, s.size, m?.uri, m?.isVideo == true, m?.durationMs ?: 0L, padded[i])
        }
        val endFile = if (endTexts != null) withContext(Dispatchers.IO) { FrameComposer.saveEndCard(ctx, tpl, p.font, endTexts.first, endTexts.second, outDir) } else null
        val input = RenderInput(scenes, times, p.font, p.music?.uri, p.music?.durationMs ?: 0L, p.musicVolume,
            tpl, p.headerTitle, p.headerSource, endFile, endUs)
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

    override fun onCleared() { synth.shutdown(); transformer?.cancel(); player.stop(); if (rec.recording) recorder.stop() }
}
