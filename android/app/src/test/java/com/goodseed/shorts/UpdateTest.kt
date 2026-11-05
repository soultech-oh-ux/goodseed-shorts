package com.goodseed.shorts

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.goodseed.shorts.data.UpdateManager
import com.goodseed.shorts.logic.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest

class SemverTest {
    @Test fun parsesVPrefixAndPlain() {
        assertEquals(Semver(0, 2, 0), Semver.parse("v0.2.0"))
        assertEquals(Semver(1, 10, 3), Semver.parse("1.10.3"))
        assertEquals(Semver(1, 0, 0), Semver.parse(" V1.0.0 "))
    }

    @Test fun rejectsOtherShapes() {
        for (s in listOf(null, "", "v1", "v1.2", "v1.2.3.4", "v1.2.3-beta", "latest", "v1.x.0", "v-1.0.0")) assertNull("'$s'", Semver.parse(s))
    }

    @Test fun comparesNumericallyNotAsText() {
        assertTrue(Semver(0, 10, 0) > Semver(0, 9, 9))
        assertTrue(Semver(1, 0, 0) > Semver(0, 99, 99))
        assertTrue(Semver(0, 2, 1) > Semver(0, 2, 0))
        assertEquals(0, Semver(0, 2, 0).compareTo(Semver(0, 2, 0)))
        assertEquals("0.2.0", Semver(0, 2, 0).toString())
    }
}

class UpdatePickTest {
    private val hash = "a".repeat(64)
    private fun rel(tag: String, draft: Boolean = false, pre: Boolean = false, apk: Boolean = true, sha: Boolean = true, digest: String? = null,
                    apkUrl: String? = null, body: String = "바뀐 점") : String {
        val v = tag.removePrefix("v")
        val assets = ArrayList<String>()
        if (apk) assets += """{"name":"goodseed-shorts-$v.apk","size":1234,"browser_download_url":"${apkUrl ?: "https://github.com/soultech-oh-ux/goodseed-shorts/releases/download/$tag/goodseed-shorts-$v.apk"}"${if (digest != null) ""","digest":"$digest"""" else ""}}"""
        if (sha) assets += """{"name":"goodseed-shorts-$v.apk.sha256","size":90,"browser_download_url":"https://github.com/soultech-oh-ux/goodseed-shorts/releases/download/$tag/goodseed-shorts-$v.apk.sha256"}"""
        return """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"html_url":"https://github.com/soultech-oh-ux/goodseed-shorts/releases/tag/$tag","body":"$body","assets":[${assets.joinToString(",")}]}"""
    }
    private fun list(vararg r: String) = "[" + r.joinToString(",") + "]"
    private val shaText: (String) -> String? = { "$hash  goodseed-shorts.apk\n" }
    private val cur = Semver(0, 1, 0)

    @Test fun picksHighestIncludingPrerelease() {
        val info = UpdatePick.pick(list(rel("v0.1.0"), rel("v0.2.0", pre = true), rel("v0.1.5")), cur)!!
        assertEquals(Semver(0, 2, 0), info.version)
        assertEquals("goodseed-shorts-0.2.0.apk", info.apkName)
        assertEquals(hash, info.sha256)
        assertTrue(info.apkUrl.endsWith("goodseed-shorts-0.2.0.apk"))
        assertEquals("바뀐 점", info.notes)
        assertTrue(info.pageUrl.contains("/releases/tag/v0.2.0"))
    }

    @Test fun numericOrderNotAlphabetical() {
        val info = UpdatePick.pick(list(rel("v0.9.0"), rel("v0.10.0")), cur)!!
        assertEquals(Semver(0, 10, 0), info.version)
    }

    @Test fun nothingNewerGivesNull() {
        assertNull(UpdatePick.pick(list(rel("v0.1.0")), cur))
        assertNull(UpdatePick.pick(list(rel("v0.0.9")), cur))
        assertNull(UpdatePick.pick("[]", cur))
    }

    @Test fun draftsAndOddTagsAreSkipped() {
        assertNull(UpdatePick.pick(list(rel("v0.3.0", draft = true)), cur))
        assertNull(UpdatePick.pick(list(rel("nightly")), cur))
        assertEquals(Semver(0, 2, 0), UpdatePick.pick(list(rel("v0.3.0", draft = true), rel("v0.2.0"), rel("v1.0.0-beta")), cur)!!.version)
    }

    @Test fun missingApkAssetIsSilentlyIgnored() {
        assertNull(UpdatePick.pick(list(rel("v0.2.0", apk = false)), cur))
        // 가장 높은 릴리스에 자산이 없으면 낮은 쪽을 대신 권하지 않는다(올리는 중일 수 있다)
        assertNull(UpdatePick.pick(list(rel("v0.3.0", apk = false), rel("v0.2.0")), cur))
    }

    @Test fun missingShaFileFallsBackToDigestOrIsIgnored() {
        assertNull(UpdatePick.pick(list(rel("v0.2.0", sha = false)), cur))
        val withDigest = UpdatePick.pick(list(rel("v0.2.0", sha = false, digest = "sha256:" + "b".repeat(64))), cur)!!
        assertEquals("b".repeat(64), withDigest.sha256)
    }

    @Test fun unreadableShaTextIsIgnored() {
        assertNull(UpdatePick.pick(list(rel("v0.2.0")), cur) { null })
        assertNull(UpdatePick.pick(list(rel("v0.2.0")), cur) { "not a hash" })
    }

    @Test fun untrustedDownloadUrlIsIgnored() {
        assertNull(UpdatePick.pick(list(rel("v0.2.0", apkUrl = "http://github.com/x/goodseed-shorts-0.2.0.apk")), cur))
        assertNull(UpdatePick.pick(list(rel("v0.2.0", apkUrl = "https://evil.example.com/goodseed-shorts-0.2.0.apk")), cur))
        assertNull(UpdatePick.pick(list(rel("v0.2.0", apkUrl = "https://github.com.evil.com/a.apk")), cur))
    }

    @Test fun brokenJsonIsIgnored() {
        assertNull(UpdatePick.pick("not json", cur))
        assertNull(UpdatePick.pick("{}", cur))
        assertNull(UpdatePick.pick("[1,2,\"x\"]", cur))
    }

    // 0.3.0: 0.2.0 앱에서 새 버전 안내가 실제로 뜨는 조건(태그 vX.Y.Z + apk/sha256 자산 이름 규칙)
    @Test fun version030IsOfferedTo020ButNotTo030() {
        val lst = list(rel("v0.2.0"), rel("v0.3.0"))
        val info = UpdatePick.pick(lst, Semver(0, 2, 0))!!
        assertEquals(Semver(0, 3, 0), info.version)
        assertEquals("goodseed-shorts-0.3.0.apk", info.apkName)
        assertNull(UpdatePick.pick(lst, Semver(0, 3, 0)))
        assertNull(UpdatePick.pick(lst, Semver(0, 3, 1)))
    }

    @Test fun builtAppVersionMatchesTheReleaseRule() {
        assertEquals("0.3.0", BuildConfig.VERSION_NAME)
        assertEquals(3, BuildConfig.VERSION_CODE)
        val cur = Semver.parse(BuildConfig.VERSION_NAME)!!
        assertTrue(cur > Semver(0, 2, 0))
        assertEquals("goodseed-shorts-${cur}.apk", "goodseed-shorts-0.3.0.apk")
    }

    @Test fun whatsNewFor030MentionsMyVoice() {
        val n = WhatsNew.notesFor("0.3.0")
        assertTrue(n.size >= 4)
        assertTrue(n.any { it.contains("내 목소리") })
        assertTrue(WhatsNew.shouldShow("0.2.0", "0.3.0", true))
        assertFalse(WhatsNew.shouldShow("0.3.0", "0.3.0", true))
    }

    private object UpdatePick {
        fun pick(json: String, cur: Semver, shaText: (String) -> String? = { "${"a".repeat(64)}  x.apk" }) = UpdateLogic.pick(json, cur, shaText)
    }
}

class UpdateHelpersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun parseSha256TakesFirstHexToken() {
        val h = "0123456789abcdef".repeat(4)
        assertEquals(h, UpdateLogic.parseSha256("$h  goodseed-shorts-0.2.0.apk\n"))
        assertEquals(h, UpdateLogic.parseSha256("SHA256: ${h.uppercase()}"))
        assertNull(UpdateLogic.parseSha256("abc"))
        assertNull(UpdateLogic.parseSha256(null))
        assertNull(UpdateLogic.parseSha256("a".repeat(65)))   // 65자리는 해시가 아니다
    }

    @Test fun trustedUrlRules() {
        assertTrue(UpdateLogic.isTrustedUrl("https://github.com/a/b"))
        assertTrue(UpdateLogic.isTrustedUrl("https://objects.githubusercontent.com/x"))
        assertFalse(UpdateLogic.isTrustedUrl("http://github.com/a"))
        assertFalse(UpdateLogic.isTrustedUrl("https://notgithub.com/a"))
        assertFalse(UpdateLogic.isTrustedUrl("file:///sdcard/a.apk"))
    }

    @Test fun autoCheckOncePerDay() {
        assertTrue(UpdateLogic.shouldAutoCheck(null, "20261105"))
        assertTrue(UpdateLogic.shouldAutoCheck("", "20261105"))
        assertFalse(UpdateLogic.shouldAutoCheck("20261105", "20261105"))
        assertTrue(UpdateLogic.shouldAutoCheck("20261104", "20261105"))
    }

    @Test fun verifyOrDeleteKeepsGoodFileAndDeletesBadOrMissing() {
        val f = tmp.newFile("a.apk"); f.writeBytes(ByteArray(1000) { it.toByte() })
        val good = UpdateLogic.sha256Of(f)
        assertTrue(UpdateLogic.verifyOrDelete(f, good.uppercase()))
        assertTrue(f.exists())
        assertFalse(UpdateLogic.verifyOrDelete(f, "0".repeat(64)))   // 해시가 다르면 지운다
        assertFalse(f.exists())
        assertFalse(UpdateLogic.verifyOrDelete(File(tmp.root, "none.apk"), good))
        val empty = tmp.newFile("empty.apk")
        assertFalse(UpdateLogic.verifyOrDelete(empty, UpdateLogic.sha256Of(empty)))   // 빈 파일은 해시가 맞아도 거부
        assertFalse(empty.exists())
    }

    @Test fun whatsNewShowsOncePerUpdateOnly() {
        assertFalse(WhatsNew.shouldShow(null, "0.2.0", wasUpdated = false))   // 새로 설치
        assertTrue(WhatsNew.shouldShow(null, "0.2.0", wasUpdated = true))     // 0.1.0 은 기록을 안 남겼다 → 업데이트로 본다
        assertTrue(WhatsNew.shouldShow("0.1.0", "0.2.0", wasUpdated = true))
        assertFalse(WhatsNew.shouldShow("0.2.0", "0.2.0", wasUpdated = true)) // 같은 버전을 다시 열면 안내 없음
        assertTrue(WhatsNew.notesFor("0.2.0").size >= 3)
        assertTrue(WhatsNew.notesFor("9.9.9").isNotEmpty())
    }
}

/** 내려받기(로컬 HTTP 서버로): 해시가 맞으면 Ready, 다르면 파일을 남기지 않고 HashMismatch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateDownloadTest {
    private lateinit var server: ServerSocket
    @Volatile private var running = true
    private val payload = ByteArray(300_000) { (it * 7).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun info(url: String, hash: String) = UpdateInfo(Semver(0, 2, 0), "v0.2.0", "goodseed-shorts-0.2.0.apk", url, payload.size.toLong(), hash, UpdateLogic.RELEASES_PAGE, "")
    private val mgr by lazy { UpdateManager(ApplicationProvider.getApplicationContext<Application>()) }
    private val any: (String) -> Boolean = { true }
    private val CRLF = String(charArrayOf(13.toChar(), 10.toChar()))

    /** 아주 작은 HTTP 서버(경로 /ok 는 payload, 그 밖은 404). */
    @Before fun up() {
        running = true
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (running) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    val reader = s.getInputStream().bufferedReader()
                    val first = reader.readLine().orEmpty()
                    while (reader.readLine().orEmpty().isNotEmpty()) { /* 머리글 건너뛰기 */ }
                    val out = s.getOutputStream()
                    if (first.contains(" /ok ")) {
                        out.write(("HTTP/1.1 200 OK" + CRLF + "Content-Length: ${payload.size}" + CRLF + "Connection: close" + CRLF + CRLF).toByteArray()); out.write(payload)
                    } else out.write(("HTTP/1.1 404 Not Found" + CRLF + "Content-Length: 0" + CRLF + "Connection: close" + CRLF + CRLF).toByteArray())
                    out.flush()
                }
                runCatching { s.close() }
            }
        }.apply { isDaemon = true }.start()
    }
    @After fun down() { running = false; runCatching { server.close() } }
    private fun url(p: String) = "http://127.0.0.1:${server.localPort}$p"

    @Test fun goodHashGivesReadyFileWithProgress() {
        val seen = ArrayList<Int>()
        val r = mgr.download(info(url("/ok"), sha(payload)), { false }, { seen += it }, any)
        assertTrue(r.toString(), r is UpdateManager.Download.Ready)
        val f = (r as UpdateManager.Download.Ready).file
        assertEquals(payload.size.toLong(), f.length())
        assertEquals(100, seen.last()); assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun wrongHashIsRejectedAndNothingIsLeftBehind() {
        val r = mgr.download(info(url("/ok"), "f".repeat(64)), { false }, {}, any)
        assertEquals(UpdateManager.Download.HashMismatch, r)
        assertTrue(mgr.updateDir().listFiles().orEmpty().isEmpty())
    }

    @Test fun cancelStopsAndRemovesPartFile() {
        val r = mgr.download(info(url("/ok"), sha(payload)), { true }, {}, any)
        assertEquals(UpdateManager.Download.Cancelled, r)
        assertTrue(mgr.updateDir().listFiles().orEmpty().isEmpty())
    }

    @Test fun serverErrorAndUnreachableAreFailures() {
        assertTrue(mgr.download(info(url("/missing"), sha(payload)), { false }, {}, any) is UpdateManager.Download.Failed)
        assertTrue(mgr.download(info("http://127.0.0.1:1/none", sha(payload)), { false }, {}, any) is UpdateManager.Download.Failed)
    }

    @Test fun untrustedUrlIsRefusedByDefault() {
        assertTrue(mgr.download(info(url("/ok"), sha(payload)), { false }, {}) is UpdateManager.Download.Failed)   // http·localhost → 기본 규칙에서 거부
    }
}
