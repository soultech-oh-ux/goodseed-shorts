package com.goodseed.shorts.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.goodseed.shorts.logic.Semver
import com.goodseed.shorts.logic.UpdateInfo
import com.goodseed.shorts.logic.UpdateLogic
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 새 버전 확인·내려받기·설치 화면 열기. 네트워크·파일 입출력이므로 백그라운드 스레드에서 부른다. */
class UpdateManager(private val ctx: Context) {
    sealed class Download {
        data class Ready(val file: File) : Download()
        object HashMismatch : Download()
        data class Failed(val reason: String) : Download()
        object Cancelled : Download()
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "goodseed-shorts-updater")
        return c
    }

    private fun getText(url: String, accept: String): String? = runCatching {
        val c = open(url, accept)
        try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }.getOrNull()

    /** reached: GitHub 에서 목록을 받았는가(못 받았으면 false). info: 새 버전이 있으면 그 정보. */
    data class CheckResult(val reached: Boolean, val info: UpdateInfo?)

    /** 어떤 실패도 예외로 내보내지 않는다(호출한 쪽이 조용히 넘어갈 수 있게). */
    fun check(current: Semver): CheckResult = runCatching {
        val json = getText(UpdateLogic.REPO_RELEASES_API + "?per_page=30", "application/vnd.github+json") ?: return CheckResult(false, null)
        CheckResult(true, UpdateLogic.pick(json, current) { shaUrl -> getText(shaUrl, "text/plain") })
    }.getOrDefault(CheckResult(false, null))

    fun updateDir(): File = File(ctx.cacheDir, "updates").also { it.mkdirs() }

    /**
     * APK 를 받아 sha256 을 확인한다. 해시가 다르면 파일을 지우고 HashMismatch.
     * cancelled() 가 true 가 되면 멈추고 파일을 지운다. onProgress: 0~100.
     */
    fun download(info: UpdateInfo, cancelled: () -> Boolean, onProgress: (Int) -> Unit, trusted: (String) -> Boolean = UpdateLogic::isTrustedUrl): Download {
        if (!trusted(info.apkUrl)) return Download.Failed("받는 곳이 올바르지 않습니다.")
        val dir = updateDir()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val dest = File(dir, info.apkName)
        val tmp = File(dir, info.apkName + ".part")
        return try {
            val c = open(info.apkUrl, "application/octet-stream")
            try {
                if (c.responseCode !in 200..299) return Download.Failed("받지 못했습니다(응답 ${c.responseCode}).")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                val md = MessageDigest.getInstance("SHA-256")
                var got = 0L
                var stopped = false
                c.inputStream.use { ins ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) { stopped = true; break }   // 열린 파일은 지울 수 없으니 닫은 뒤에 지운다
                            val n = ins.read(buf); if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); got += n
                            if (total > 0) onProgress(((got * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
                if (stopped) { tmp.delete(); return Download.Cancelled }
                val hash = md.digest().joinToString("") { "%02x".format(it) }
                if (hash != info.sha256.lowercase()) { tmp.delete(); return Download.HashMismatch }
                if (!tmp.renameTo(dest)) return Download.Failed("받은 파일을 저장하지 못했습니다.")
                onProgress(100)
                Download.Ready(dest)
            } finally { c.disconnect() }
        } catch (e: Exception) {
            tmp.delete()
            Download.Failed(e.message ?: "인터넷 연결을 확인해 주세요.")
        }
    }

    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** '출처를 알 수 없는 앱' 허용 화면(이 앱 항목)을 연다. */
    fun openInstallPermissionSettings() {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    /** 시스템 설치 화면을 연다(자동 설치가 아니다 — 사용자가 설치 화면에서 [업데이트]를 눌러야 한다). */
    fun openInstaller(apk: File): Boolean {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i) }.isSuccess
    }

    fun openUrl(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
