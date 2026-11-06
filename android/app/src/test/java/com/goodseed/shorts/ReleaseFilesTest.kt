package com.goodseed.shorts

import com.goodseed.shorts.logic.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 릴리스 산출물 이름 고정(tools/make_release_files.py): dist\goodseed-shorts-X.Y.Z.apk 와 .apk.sha256('해시  파일명').
 * 앱 안 업데이터(UpdateLogic)가 찾는 이름과 읽는 형식을 그대로 쓰는지 같이 확인한다. python 이 없는 PC 에서는 건너뛴다.
 */
class ReleaseFilesTest {
    private val script = File("../../tools/make_release_files.py").absoluteFile.normalize()

    private val python: String? by lazy {
        listOf("python3", "python").firstOrNull { c -> runCatching { ProcessBuilder(c, "--version").redirectErrorStream(true).start().let { it.waitFor() == 0 } }.getOrDefault(false) }
    }

    private class Run(val code: Int, val out: String)
    private fun run(vararg args: String): Run {
        val p = ProcessBuilder(listOf(python!!, "-I", script.path) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        return Run(p.waitFor(), out)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun tmp() = Files.createTempDirectory("gsrel").toFile()

    private fun ready() { assertTrue("스크립트가 있어야 한다: $script", script.isFile); assumeTrue("python 이 없으면 건너뜀", python != null) }

    @Test fun createsFixedNamesAndHashLineFormat() {
        ready()
        val d = tmp(); val src = File(d, "app-release.apk").also { it.writeBytes(ByteArray(5000) { i -> (i * 7).toByte() }) }; val out = File(d, "dist")
        val r = run(src.path, "--out", out.path, "--version", "0.5.0")
        assertEquals(r.out, 0, r.code)
        val apk = File(out, "goodseed-shorts-0.5.0.apk"); val sf = File(out, "goodseed-shorts-0.5.0.apk.sha256")
        assertTrue(apk.isFile); assertArrayEquals(src.readBytes(), apk.readBytes())
        val raw = sf.readBytes()
        assertEquals("'해시  파일명' 한 줄(두 칸 띄움, 줄바꿈 1개, ASCII)", sha(apk.readBytes()) + "  goodseed-shorts-0.5.0.apk\n", String(raw, Charsets.US_ASCII))
        assertTrue(raw.none { it < 0 || it == '\r'.code.toByte() })
        assertEquals("고정되지 않은 이름은 만들지 않는다", setOf("goodseed-shorts-0.5.0.apk", "goodseed-shorts-0.5.0.apk.sha256"), out.list()!!.toSet())
        assertEquals(0, run("--check", "--out", out.path, "--version", "0.5.0").code)
    }

    @Test fun theHashFileIsReadableByTheAppUpdater() {
        ready()
        val d = tmp(); val src = File(d, "a.apk").also { it.writeBytes(ByteArray(777) { 3 }) }; val out = File(d, "dist")
        assertEquals(0, run(src.path, "--out", out.path, "--version", "0.5.0").code)
        val text = File(out, "goodseed-shorts-0.5.0.apk.sha256").readText()
        assertEquals(sha(src.readBytes()), UpdateLogic.parseSha256(text))
        // 업데이터가 릴리스에서 찾는 자산 이름과 같다
        val rel = """[{"tag_name":"v0.5.0","draft":false,"prerelease":false,"html_url":"https://github.com/x/y","body":"","assets":[
            {"name":"goodseed-shorts-0.5.0.apk","size":777,"browser_download_url":"https://github.com/x/y/releases/download/v0.5.0/goodseed-shorts-0.5.0.apk"},
            {"name":"${File(out, "goodseed-shorts-0.5.0.apk.sha256").name}","size":90,"browser_download_url":"https://github.com/x/y/releases/download/v0.5.0/goodseed-shorts-0.5.0.apk.sha256"}]}]"""
        val info = UpdateLogic.pick(rel, Semver(0, 4, 0)) { text }
        assertNotNull(info); assertEquals(sha(src.readBytes()), info!!.sha256); assertEquals("goodseed-shorts-0.5.0.apk", info.apkName)
        // 0.4.0 때의 잘못된 이름(.sha256 만 붙은 것)으로는 해시를 찾지 못해 안내가 뜨지 않는다 — 이름 고정이 필요한 이유
        val wrongName = rel.replace("goodseed-shorts-0.5.0.apk.sha256", "goodseed-shorts-0.5.0.sha256").replace(Regex("\"digest\"[^,]*,"), "")
        assertNull(UpdateLogic.pick(wrongName, Semver(0, 4, 0)) { null })
    }

    @Test fun removesTheWronglyNamedOldHashFile() {
        ready()
        val d = tmp(); val src = File(d, "a.apk").also { it.writeBytes(ByteArray(100) { 1 }) }; val out = File(d, "dist").also { it.mkdirs() }
        File(out, "goodseed-shorts-0.5.0.sha256").writeText("stale")
        assertEquals(0, run(src.path, "--out", out.path, "--version", "0.5.0").code)
        assertFalse(File(out, "goodseed-shorts-0.5.0.sha256").exists())
    }

    @Test fun checkFailsOnTamperedBadFormatOrMissingFiles() {
        ready()
        val d = tmp(); val src = File(d, "a.apk").also { it.writeBytes(ByteArray(400) { 9 }) }; val out = File(d, "dist")
        assertEquals(0, run(src.path, "--out", out.path, "--version", "0.5.0").code)
        val sf = File(out, "goodseed-shorts-0.5.0.apk.sha256"); val good = sf.readText()
        fun check() = run("--check", "--out", out.path, "--version", "0.5.0").code
        sf.writeText(good.replace("  ", " *")); assertNotEquals("sha256sum 의 바이너리 표시(' *')는 규칙이 아니다", 0, check())
        sf.writeText(good.trimEnd()); assertNotEquals("줄바꿈이 없으면 실패", 0, check())
        sf.writeText(good.replace("\n", "\r\n")); assertNotEquals("CRLF 는 실패", 0, check())
        sf.writeText(good.uppercase()); assertNotEquals("대문자 해시·파일명은 실패", 0, check())
        sf.writeText(good.replace("0.5.0.apk", "0.4.0.apk")); assertNotEquals("파일명이 다르면 실패", 0, check())
        sf.writeText("0".repeat(64) + "  goodseed-shorts-0.5.0.apk\n"); assertNotEquals("해시가 다르면 실패", 0, check())
        sf.writeText(good); assertEquals(0, check())
        File(out, "goodseed-shorts-0.5.0.apk").writeBytes(ByteArray(10)); assertNotEquals("apk 가 바뀌면 실패", 0, check())
        sf.delete(); assertNotEquals("해시 파일이 없으면 실패", 0, check())
    }

    @Test fun rejectsBadVersionOrMissingApk() {
        ready()
        val d = tmp(); val src = File(d, "a.apk").also { it.writeBytes(ByteArray(10)) }
        for (v in listOf("0.5", "v0.5.0", "0.5.0-beta", "")) assertEquals(v, 2, run(src.path, "--out", File(d, "o").path, "--version", v).code)
        assertEquals(2, run(File(d, "none.apk").path, "--out", File(d, "o").path, "--version", "0.5.0").code)
        assertEquals(2, run(File(d, "empty.apk").also { it.writeBytes(ByteArray(0)) }.path, "--out", File(d, "o").path, "--version", "0.5.0").code)
        assertFalse(File(d, "o").exists() && File(d, "o").list()!!.isNotEmpty())
    }

    @Test fun readsTheVersionFromGradleWhenNotGiven() {
        ready()
        val d = tmp(); val src = File(d, "a.apk").also { it.writeBytes(ByteArray(50) { 5 }) }
        val r = run(src.path, "--out", File(d, "dist").path)
        assertEquals(r.out, 0, r.code)
        assertTrue(File(d, "dist/goodseed-shorts-${BuildConfig.VERSION_NAME}.apk.sha256").isFile)
    }

    @Test fun updaterAssetNameRuleMatchesTheScript() {
        // 앱 쪽 규칙(UpdateLogic.pick 이 찾는 이름)이 스크립트가 만드는 이름과 같다는 것을 소스에서 직접 확인한다
        val src = File("src/main/java/com/goodseed/shorts/logic/UpdateLogic.kt").readText()
        assertTrue(src.contains("\"goodseed-shorts-\$ver.apk\"")); assertTrue(src.contains("\"\$apkName.sha256\""))
        val py = script.readText()
        assertTrue(py.contains("\"goodseed-shorts-%s.apk\" % version")); assertTrue(py.contains("apk + \".sha256\""))
    }
}
