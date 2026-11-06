package com.goodseed.shorts

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.goodseed.shorts.data.*
import com.goodseed.shorts.logic.*
import com.goodseed.shorts.render.SubFont
import com.goodseed.shorts.render.SubSize
import com.goodseed.shorts.render.Templates
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** 0.6.0: 최근 작품 [수정] — 스냅샷 왕복, 복원 검증, 같은 녹음 폴더(pid) 공유 삭제 보호, 스냅샷 없는 작품. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Edit060Test {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var vm: AppViewModel
    private val pid = "11111111-2222-3333-4444-555555555555"

    private val nasa = Attribution("Apollo 11 \"Earthrise\"", "NASA/Bill Anders", "공개 도메인", "https://www.nasa.gov/nasa-brand-center/images-and-media/", "https://images.nasa.gov/details/as08-14-2383", FreeProvider.NASA, false)
    private val wiki = Attribution("일출 \n(sunrise) 🌅", "홍길동 <작가>", "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/", "https://commons.wikimedia.org/wiki/File:X.jpg", FreeProvider.WIKIMEDIA, true)
    private val song = Attribution("Calm Piano", "Some Artist", "CC0 1.0", "https://creativecommons.org/publicdomain/zero/1.0/", "https://freesound.org/s/1/", FreeProvider.OPENVERSE, false)

    private fun fullProject(dir: File = File(app.filesDir, "recordings/$pid")): Project {
        val tpl = Templates.ALL.first { it.id != Templates.DEFAULT.id }
        return Project(
            id = pid, voiceMode = VoiceMode.MINE, rawText = "주님은 나의 목자시니.\n\"부족함이\" 없으리로다.",
            scenes = listOf(
                Scene(1, "주님은 나의 목자시니.", "m1", SubSize.LARGE, File(dir, "s1_1.wav").absolutePath, 2500),
                Scene(2, "\"부족함이\" 없으리로다.", null, SubSize.XLARGE, null, 0),
                Scene(3, "탭\t줄바꿈\n이모지 🙏", "m2", SubSize.SMALL, File(dir, "s3_1.wav").absolutePath, 1800),
            ),
            voice = VoiceChoice("ko-kr-x-abc-local", "한국어 여성 1", true), speed = Speed.NORMAL,
            media = listOf(
                MediaRef("m1", "content://media/external/images/media/77", false, 0, "IMG_77.jpg", MediaOrigin.PHONE),
                MediaRef("m2", "file:///data/user/0/x/files/free/wm_1.webm", true, 9000, "위키미디어 · 홍길동", MediaOrigin.FREE, "홍길동", wiki),
                MediaRef("m3", "file:///data/user/0/x/files/free/nasa_2.mp4", true, 12000, "NASA", MediaOrigin.FREE, "NASA", nasa),
            ),
            music = MusicChoice("file:///data/user/0/x/files/free/song.mp3", "Calm Piano", 61_000, song), musicVolume = 0.35f,
            font = SubFont.GOWUN, templateId = tpl.id, headerTitle = "시편 23편", headerSource = "시편 23:1", showCredits = false,
        )
    }

    @Before fun setUp() {
        AppViewModel.startupChecks = false
        listOf("recordings", "free", "projects", "thumbs").forEach { File(app.filesDir, it).deleteRecursively() }
        File(app.filesDir, "recent.json").delete()
        vm = AppViewModel(app)
        vm.uriReadable = { true }
    }

    private fun idle(ms: Long = 20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(ms); shadowOf(Looper.getMainLooper()).idle() }
    private fun await(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { shadowOf(Looper.getMainLooper()).idle(); if (System.currentTimeMillis() > end) fail("시간 초과: $what"); Thread.sleep(15) }
    }

    private fun work(name: String, snapshotOf: Project?, projectId: String = pid, vararg files: File): RecentWork {
        val snap = snapshotOf?.let { vm.recentStore.saveSnapshot(it) } ?: ""
        val w = RecentWork(name, "content://media/external/video/media/$name", 1_000L, "", projectId, files.map { it.absolutePath }, snap)
        vm.recentStore.add(w); vm.recents = vm.recentStore.load()
        return w
    }

    // ---------- 스냅샷 왕복 ----------
    @Test fun snapshotRoundTripKeepsEveryField() {
        val p = fullProject()
        val back = ProjectSnapshot.decode(ProjectSnapshot.encode(p))
        assertEquals(p, back)
        // data class 같음에 더해 눈에 띄는 값을 직접 확인
        assertEquals(SubFont.GOWUN, back!!.font); assertEquals(0.35f, back.musicVolume); assertFalse(back.showCredits)
        assertEquals(wiki, back.media[1].attribution); assertEquals(song, back.music!!.attribution); assertEquals(nasa, back.media[2].attribution)
        assertEquals(listOf(SubSize.LARGE, SubSize.XLARGE, SubSize.SMALL), back.scenes.map { it.size })
        assertNull(back.scenes[1].mediaId); assertNull(back.scenes[1].myVoice); assertEquals(2500L, back.scenes[0].myVoiceMs)
        assertEquals(p.usedAttributions(), back.usedAttributions())
    }

    @Test fun snapshotOfMinimalProjectRoundTrips() {
        val p = Project(id = pid, scenes = listOf(Scene(1, "한 문장.")))
        assertEquals(p, ProjectSnapshot.decode(ProjectSnapshot.encode(p)))
    }

    @Test fun brokenSnapshotsGiveNullNotACrash() {
        val good = ProjectSnapshot.encode(fullProject())
        listOf("", "   ", "not json", "[]", "{}", """{"v":1}""", """{"v":99,"id":"$pid","scenes":[{"id":1,"text":"a"}]}""",
            """{"v":1,"id":"$pid","scenes":[]}""", """{"v":1,"id":"","scenes":[{"id":1,"text":"a"}]}""", """{"v":1,"id":"$pid","scenes":[{"text":"id 없음"}]}""",
            good.take(good.length / 2), good.replace("\"scenes\":[", "\"scenes\":{")
        ).forEach { assertNull("깨진 입력은 null: " + it.take(40), ProjectSnapshot.decode(it)) }
    }

    @Test fun unknownOrMissingValuesFallBackToDefaults() {
        val t = """{"v":1,"id":"$pid","scenes":[{"id":1,"text":"가","size":"HUGE","mediaId":null}],"font":"COMIC","speed":"FAST","voiceMode":"X","templateId":"없는템플릿",
            "media":[{"id":"a","uri":"","label":"빈 주소"},{"id":"b","uri":"file:///x.jpg","origin":"???","attr":{"provider":"MARS"}}],"music":{"uri":""},"musicVolume":7}"""
        val p = ProjectSnapshot.decode(t)!!
        assertEquals(SubSize.NORMAL, p.scenes[0].size); assertEquals(SubFont.PRETENDARD, p.font); assertEquals(Speed.SLOW, p.speed); assertEquals(VoiceMode.AI, p.voiceMode)
        assertEquals(Templates.DEFAULT.id, p.templateId); assertEquals(1f, p.musicVolume); assertNull(p.music)
        assertEquals("주소가 빈 자료는 버린다", listOf("b"), p.media.map { it.id }); assertEquals(MediaOrigin.PHONE, p.media[0].origin); assertNull("알 수 없는 출처 정보는 없는 것으로", p.media[0].attribution)
        assertTrue(p.showCredits); assertNull(p.scenes[0].mediaId)
    }

    // ---------- 복원 검증 ----------
    @Test fun restoreDropsUnreadableMediaMusicAndVoicesAndResetsTheirScenes() {
        val p = fullProject()
        val r = SnapshotRestore.apply(p, { it.uri.startsWith("file") }, { false }, { it.endsWith("s1_1.wav") })
        assertEquals(1, r.droppedMedia); assertTrue(r.droppedMusic); assertEquals(1, r.lostVoices)
        assertEquals(listOf("m2", "m3"), r.project.media.map { it.id }); assertNull(r.project.music)
        assertNull("뺀 자료를 쓰던 장면은 기본 배경으로", r.project.scenes[0].mediaId); assertEquals("m2", r.project.scenes[2].mediaId)
        assertNotNull(r.project.scenes[0].myVoice); assertNull(r.project.scenes[2].myVoice); assertEquals(0L, r.project.scenes[2].myVoiceMs)
        val msg = r.notice()!!
        assertTrue(msg, msg.contains("사진·영상 1개") && msg.contains("배경 음악") && msg.contains("다시 골라 주세요") && msg.contains("1개는 [목소리]에서 다시 녹음"))
        assertEquals(p.id, r.project.id); assertEquals(p.headerTitle, r.project.headerTitle)
    }

    @Test fun restoreOfIntactProjectChangesNothingAndHasNoNotice() {
        val p = fullProject(); val r = SnapshotRestore.apply(p, { true }, { true }, { true })
        assertEquals(p, r.project); assertNull(r.notice()); assertFalse(r.anyDropped)
    }

    @Test fun missingRecordingsOfAiVoiceWorkAreNotMentioned() {
        val p = fullProject().copy(voiceMode = VoiceMode.AI)
        val r = SnapshotRestore.apply(p, { true }, { true }, { false })
        assertEquals(0, r.lostVoices); assertNull(r.notice())
    }

    // ---------- 스냅샷 저장소 ----------
    @Test fun storeSavesReadsAndDeletesSnapshotsAndRejectsOddNames() {
        val p = fullProject()
        val name = vm.recentStore.saveSnapshot(p)
        assertTrue(name.endsWith(".json")); assertEquals(p, vm.recentStore.readSnapshot(name))
        assertNull(vm.recentStore.readSnapshot("../recent.json")); assertNull(vm.recentStore.readSnapshot("")); assertNull(vm.recentStore.readSnapshot("nope.json"))
        File(app.filesDir, "keep-me.json").writeText("{}"); vm.recentStore.deleteSnapshot("../keep-me.json"); assertTrue("projects/ 밖은 안 지운다", File(app.filesDir, "keep-me.json").exists())
        vm.recentStore.deleteSnapshot(name); assertNull(vm.recentStore.readSnapshot(name))
        assertTrue(File(app.filesDir, "projects").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun oldRecentJsonWithoutSnapshotFieldLoadsAsNotEditable() {
        File(app.filesDir, "recent.json").writeText("""[{"title":"옛 작품","uri":"content://v/1","at":5,"thumb":"","pid":"$pid","files":[]}]""")
        val w = vm.recentStore.load().single(); assertEquals("", w.snapshot); assertFalse(w.editable)
    }

    @Test fun listDroppingOldestAlsoDeletesItsSnapshotFile() {
        val first = work("w0", fullProject(), "00000000-0000-0000-0000-000000000000")
        repeat(12) { i -> work("w${i + 1}", fullProject(), "00000000-0000-0000-0000-%012d".format(i + 1)) }
        assertEquals(12, vm.recentStore.load().size); assertFalse(vm.recentStore.load().any { it.title == "w0" })
        assertNull("밀려난 작품의 스냅샷 파일은 지운다", vm.recentStore.readSnapshot(first.snapshot))
        assertTrue(vm.recentStore.load().all { vm.recentStore.readSnapshot(it.snapshot) != null })
    }

    // ---------- 영상 저장 → 최근 작품 기록 ----------
    @Test fun recordRecentWritesSnapshotThatRestoresTheSameProject() {
        val p = fullProject()
        vm.recordRecent(p, Uri.parse("content://media/external/video/media/900"), 30_000, "굿씨드묵상숏츠_x.mp4")
        val w = vm.recentStore.load().single()
        assertEquals(pid, w.projectId); assertTrue(w.editable); assertEquals(p, vm.recentStore.readSnapshot(w.snapshot))
    }

    // ---------- [수정] 흐름 ----------
    @Test fun editOpensSavedStateInPreviewAndKeepsPidAndWorkList() {
        recFile(pid, "s1_1.wav"); recFile(pid, "s3_1.wav")   // 녹음 파일이 남아 있으면 상태가 그대로 열린다
        val p = fullProject(); val w = work("a", p)
        vm.askEdit(w); await("복원") { vm.screen == Screen.PREVIEW }
        assertEquals(p, vm.project); assertNull(vm.editAsk); assertNull(vm.editNotice); assertEquals(pid, vm.project.id)
        assertEquals("목록은 그대로", 1, vm.recents.size)
    }

    @Test fun workWithoutSnapshotShowsGuidanceAndChangesNothing() {
        val w = work("old", null); val before = vm.project
        assertFalse(w.editable)
        vm.askEdit(w); idle()
        assertEquals("0.6.0부터 만든 작품만 고칠 수 있어요.", vm.homeNotice); assertEquals(Screen.HOME, vm.screen); assertSame(before, vm.project); assertNull(vm.editAsk)
    }

    @Test fun unfinishedProjectMustBeConfirmedFirst() {
        val w = work("a", fullProject())
        vm.project = Project(scenes = listOf(Scene(1, "만들던 글")), rawText = "만들던 글")
        val mine = vm.project
        vm.askEdit(w); idle()
        assertEquals(w, vm.editAsk); assertSame("확인 전에는 아무것도 바뀌지 않는다", mine, vm.project); assertEquals(Screen.HOME, vm.screen)
        vm.cancelEdit(); idle(); assertNull(vm.editAsk); assertSame(mine, vm.project)
        vm.askEdit(w); vm.confirmEdit(); await("복원") { vm.screen == Screen.PREVIEW }
        assertEquals(pid, vm.project.id); assertNull(vm.editAsk)
    }

    @Test fun nothingToLoseWhenProjectIsEmptyOrUnchangedSinceSave() {
        val w = work("a", fullProject())
        vm.askEdit(w); await("빈 상태에서 바로 열림") { vm.screen == Screen.PREVIEW }
        assertNull(vm.editAsk)
        vm.go(Screen.HOME)
        val other = work("b", fullProject().copy(rawText = "다른 글"), "99999999-9999-9999-9999-999999999999")
        vm.askEdit(other); await("고치지 않았으면 바로 다른 작품") { vm.project.rawText == "다른 글" }
        assertNull("방금 연 작품을 안 고쳤으면 묻지 않는다", vm.editAsk)
        vm.go(Screen.HOME); vm.setFont(SubFont.PRETENDARD); vm.chooseSpeed(Speed.SLOW)
        vm.updateScene(vm.project.scenes[0].id) { it.copy(size = SubSize.SMALL) }
        vm.askEdit(w); idle(); assertEquals("고친 뒤에는 묻는다", w, vm.editAsk)
    }

    @Test fun unreadableMediaIsLeftOutAndTheNoticeSaysSo() {
        val w = work("a", fullProject())
        vm.uriReadable = { u -> !u.startsWith("content://") && !u.endsWith(".mp3") }
        vm.askEdit(w); await("복원") { vm.screen == Screen.PREVIEW }
        assertEquals(listOf("m2", "m3"), vm.project.media.map { it.id }); assertNull(vm.project.music); assertNull(vm.project.scenes[0].mediaId)
        val n = vm.editNotice!!; assertTrue(n, n.contains("사진·영상 1개") && n.contains("배경 음악") && n.contains("다시 골라 주세요"))
    }

    @Test fun missingRecordingFileIsReportedAndSceneBecomesUnrecorded() {
        File(app.filesDir, "recordings/$pid").mkdirs()
        File(app.filesDir, "recordings/$pid/s1_1.wav").writeBytes(ByteArray(200))   // 첫 문장 녹음만 남아 있음(셋째는 없음)
        val w = work("a", fullProject())
        vm.askEdit(w); await("복원") { vm.screen == Screen.PREVIEW }
        assertNotNull(vm.project.scenes[0].myVoice); assertNull(vm.project.scenes[2].myVoice)
        assertTrue(vm.editNotice!!.contains("1개는 [목소리]에서 다시 녹음"))
    }

    @Test fun corruptOrMissingSnapshotFileIsHandledWithoutCrash() {
        val w = work("a", fullProject())
        File(app.filesDir, "projects/${w.snapshot}").writeText("{깨진 파일")
        val before = vm.project
        vm.askEdit(w); await("안내") { vm.homeNotice.isNotEmpty() }
        assertTrue(vm.homeNotice.contains("읽지 못해")); assertSame(before, vm.project); assertEquals(Screen.HOME, vm.screen)
        File(app.filesDir, "projects/${w.snapshot}").delete(); vm.homeNotice = ""
        vm.askEdit(w); await("안내2") { vm.homeNotice.isNotEmpty() }; assertEquals(Screen.HOME, vm.screen)
    }

    @Test fun editIsRefusedWhileAVideoIsBeingMade() {
        val w = work("a", fullProject()); vm.export = ExportUi(Phase.RENDER, 40, "만드는 중")
        vm.askEdit(w); idle()
        assertTrue(vm.homeNotice.contains("만드는 중")); assertEquals(Screen.HOME, vm.screen); assertNull(vm.editAsk)
    }

    @Test fun startNewClearsRestoreNoticeAndResetsEverything() {
        val w = work("a", fullProject()); vm.uriReadable = { false }
        vm.askEdit(w); await("복원") { vm.screen == Screen.PREVIEW }; assertNotNull(vm.editNotice)
        vm.go(Screen.HOME); vm.startNew(); assertNull(vm.editNotice); assertTrue(vm.project.scenes.isEmpty()); assertNotEquals(pid, vm.project.id)
    }

    // ---------- 같은 녹음 폴더(pid)·파일 공유 삭제 보호 ----------
    private fun recFile(p: String, n: String) = File(File(app.filesDir, "recordings/$p").also { it.mkdirs() }, n).also { it.writeBytes(ByteArray(300)) }
    private fun del(w: RecentWork) { vm.askDelete(w); vm.confirmDelete(); await("지움") { vm.homeNotice.isNotEmpty() }; vm.homeNotice = "" }

    @Test fun deletingOneOfTwoWorksThatShareThePidKeepsTheRecordings() {
        val rec = recFile(pid, "s1_1.wav")
        val a = work("a", fullProject()); val b = work("b", fullProject().copy(headerTitle = "다시 만든 것"))
        del(b); assertTrue("A 가 아직 쓰는 녹음 폴더는 남는다", rec.exists()); assertEquals(listOf("a"), vm.recents.map { it.title })
        del(a); assertFalse("마지막 작품을 지우면 폴더도 지운다", File(app.filesDir, "recordings/$pid").exists())
    }

    @Test fun deletingAWorkKeepsTheRecordingsWhenTheCurrentProjectUsesThePid() {
        val rec = recFile(pid, "s1_1.wav"); val a = work("a", fullProject())
        vm.askEdit(a); await("복원") { vm.screen == Screen.PREVIEW }; vm.go(Screen.HOME)
        del(a); assertTrue("[수정]으로 열어 둔 작품의 녹음은 남는다", rec.exists()); assertTrue(vm.recents.isEmpty())
    }

    @Test fun deletingAWorkRemovesItsSnapshotButNotTheOthers() {
        val a = work("a", fullProject()); val b = work("b", fullProject(), "22222222-2222-2222-2222-222222222222")
        assertNotEquals(a.snapshot, b.snapshot)
        del(a); assertNull(vm.recentStore.readSnapshot(a.snapshot)); assertNotNull(vm.recentStore.readSnapshot(b.snapshot))
        assertFalse(File(app.filesDir, "projects/${a.snapshot}").exists())
    }

    @Test fun downloadedFilesUsedByAnotherWorksSnapshotListStayWhenOneIsDeleted() {
        val shared = File(File(app.filesDir, "free").also { it.mkdirs() }, "shared.jpg").also { it.writeBytes(ByteArray(8)) }
        val a = work("a", fullProject(), "33333333-3333-3333-3333-333333333333", shared)
        work("b", fullProject(), "44444444-4444-4444-4444-444444444444", shared)
        del(a); assertTrue(shared.exists())
    }

    // ---------- 7일 정리가 최근 작품 자료를 지우지 않는다 ----------
    @Test fun weeklyCleanupKeepsRecordingsAndFilesOfRecentWorksButRemovesStrangers() {
        val old = System.currentTimeMillis() - 10L * 86_400_000L
        val mineRec = recFile(pid, "s1_1.wav"); val strangerRec = recFile("aaaaaaaa-0000-0000-0000-000000000000", "s1_1.wav")
        val used = File(File(app.filesDir, "free").also { it.mkdirs() }, "used.jpg").also { it.writeBytes(ByteArray(8)) }
        val stray = File(app.filesDir, "free/stray.jpg").also { it.writeBytes(ByteArray(8)) }
        listOf(mineRec.parentFile!!, strangerRec.parentFile!!, used, stray).forEach { it.setLastModified(old) }
        work("a", fullProject(), pid, used)
        vm.startNew()
        await("정리") { !stray.exists() && !strangerRec.parentFile!!.exists() }
        assertTrue("최근 작품의 녹음 폴더는 남는다", mineRec.exists()); assertTrue("최근 작품이 쓴 받은 파일은 남는다", used.exists())
    }

    @Test fun weeklyCleanupAfterEditKeepsTheOpenedProjectsOwnFiles() {
        val old = System.currentTimeMillis() - 10L * 86_400_000L
        val f = File(File(app.filesDir, "free").also { it.mkdirs() }, "wm_1.webm").also { it.writeBytes(ByteArray(8)); it.setLastModified(old) }
        val p = fullProject().copy(media = listOf(MediaRef("m", Uri.fromFile(f).toString(), true, 1000, "x", MediaOrigin.FREE)), scenes = listOf(Scene(1, "가", "m")), music = null)
        val stray = File(app.filesDir, "free/stray.jpg").also { it.writeBytes(ByteArray(8)); it.setLastModified(old) }
        val w = work("a", p)   // files 목록은 일부러 비워 둠(스냅샷만 있는 작품)
        vm.askEdit(w); await("복원") { vm.screen == Screen.PREVIEW }
        await("정리가 실제로 돌았음(관계없는 낡은 파일은 지워짐)") { !stray.exists() }
        assertTrue("[수정]으로 연 작품이 쓰는 받은 파일은 정리되지 않는다", f.exists())
    }
}
