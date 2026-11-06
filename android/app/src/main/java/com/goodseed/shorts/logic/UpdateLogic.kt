package com.goodseed.shorts.logic

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** 세 자리 버전(X.Y.Z). 태그는 `vX.Y.Z` 또는 `X.Y.Z` 만 인정한다(그 밖의 모양은 무시). */
data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {
    override fun compareTo(other: Semver): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val RE = Regex("^[vV]?(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})$")
        fun parse(s: String?): Semver? {
            val m = RE.matchEntire(s?.trim().orEmpty()) ?: return null
            return Semver(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
    }
}

/** 새 버전 한 개의 정보. sha256 은 64자리 소문자 16진수. */
data class UpdateInfo(
    val version: Semver,
    val tag: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
    val pageUrl: String,
    val notes: String,
)

object UpdateLogic {
    const val REPO_RELEASES_API = "https://api.github.com/repos/soultech-oh-ux/goodseed-shorts/releases"
    const val RELEASES_PAGE = "https://github.com/soultech-oh-ux/goodseed-shorts/releases"
    private val HEX64 = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])")

    /** 파일 내용(`해시  파일이름` 형식 등)에서 첫 64자리 16진수를 꺼낸다. 없으면 null. */
    fun parseSha256(text: String?): String? = HEX64.find(text.orEmpty())?.value?.lowercase()

    /** 내려받을 곳은 https 이고 GitHub 도메인이어야 한다. */
    fun isTrustedUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = url.removePrefix("https://").substringBefore('/').substringBefore(':').lowercase()
        return host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com")
    }

    /**
     * GitHub 릴리스 목록(JSON 배열)에서 새 버전을 고른다.
     *  - 초안(draft)은 건너뛰고, 시험판(prerelease)은 포함한다.
     *  - 태그가 vX.Y.Z 모양인 것 중 버전이 가장 큰 릴리스 하나를 본다.
     *  - 그 릴리스에 `goodseed-shorts-X.Y.Z.apk` 가 없거나, 해시(.sha256 파일 내용 또는 API 의 digest)를 알 수 없거나,
     *    주소가 GitHub 가 아니면 null(조용히 무시).
     *  - 현재 버전보다 크지 않으면 null.
     * shaTextOf: `.sha256` 자산 주소 → 그 파일 내용(받지 못하면 null). 네트워크를 쓰는 부분을 밖에서 넣는다.
     */
    fun pick(releasesJson: String, current: Semver, shaTextOf: (String) -> String?): UpdateInfo? {
        val arr = runCatching { JSONArray(releasesJson) }.getOrNull() ?: return null
        var best: Pair<Semver, JSONObject>? = null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.optBoolean("draft", false)) continue
            val v = Semver.parse(r.optString("tag_name")) ?: continue
            if (best == null || v > best.first) best = v to r
        }
        val (ver, rel) = best ?: return null
        if (ver <= current) return null
        val apkName = "goodseed-shorts-$ver.apk"
        val assets = rel.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var sha: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            when (a.optString("name")) { apkName -> apk = a; "$apkName.sha256" -> sha = a }
        }
        val apkUrl = apk?.optString("browser_download_url").orEmpty()
        if (apk == null || apkUrl.isBlank() || !isTrustedUrl(apkUrl)) return null
        val shaUrl = sha?.optString("browser_download_url").orEmpty()
        val hash = (if (shaUrl.isNotBlank() && isTrustedUrl(shaUrl)) parseSha256(shaTextOf(shaUrl)) else null)
            ?: parseSha256(apk.optString("digest").removePrefix("sha256:"))
            ?: return null
        val page = rel.optString("html_url").takeIf { isTrustedUrl(it) } ?: RELEASES_PAGE
        return UpdateInfo(ver, rel.optString("tag_name"), apkName, apkUrl, apk.optLong("size", -1L), hash, page, rel.optString("body"))
    }

    /** 하루에 한 번만 자동 확인한다(날짜가 바뀌면 다시). 기록이 없으면 확인한다. */
    fun shouldAutoCheck(lastDay: String?, today: String): Boolean = lastDay.isNullOrBlank() || lastDay != today

    // ---------- 해시 검증 ----------
    fun sha256Of(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Of(file: File): String = file.inputStream().use { sha256Of(it) }

    /**
     * 받은 파일의 해시가 기대값과 같은지 본다. 다르면 파일을 지우고 false.
     * (해시가 다른 APK 는 절대 설치 화면으로 넘기지 않는다.)
     */
    fun verifyOrDelete(file: File, expectedSha256: String): Boolean {
        val ok = file.isFile && file.length() > 0 && runCatching { sha256Of(file) }.getOrNull() == expectedSha256.lowercase()
        if (!ok) runCatching { file.delete() }
        return ok
    }
}

/** 업데이트 후 첫 실행 안내 판단과 '바뀐 점'. */
object WhatsNew {
    /**
     * lastRunVersion: 마지막으로 실행했던 버전(없으면 null/빈칸). wasUpdated: 새로 설치가 아니라 업데이트로 들어온 앱인가
     * (PackageInfo.lastUpdateTime 이 firstInstallTime 보다 뒤). 같은 버전을 다시 열면 안내하지 않는다.
     */
    fun shouldShow(lastRunVersion: String?, current: String, wasUpdated: Boolean): Boolean {
        if (lastRunVersion.isNullOrBlank()) return wasUpdated
        return lastRunVersion != current
    }

    fun notesFor(version: String): List<String> = when (version) {
        "0.6.0" -> listOf(
            "홈의 최근 작품 카드에 [수정] 버튼이 생겼습니다. 누르면 만들 때의 글·장면별 자막·내 목소리 녹음·배경·음악·글꼴·스타일이 그대로 열려 고쳐서 다시 만들 수 있습니다(0.6.0부터 만든 작품만).",
            "다시 만들면 새 영상이 최근 작품에 하나 더 생기고, 처음 영상은 그대로 남습니다. 필요 없는 영상은 [삭제]로 정리하세요.",
            "폰 갤러리에서 고른 사진·영상이 앱을 다시 켠 뒤 열리지 않으면 그 자료만 빼고 열고 \"다시 골라 주세요\" 하고 알려 줍니다.",
        )
        "0.5.0" -> listOf(
            "홈의 최근 작품 카드에 [삭제] 버튼이 생겼습니다. 누르면 \"이 작품을 지울까요?\" 하고 묻고, 갤러리에 저장된 영상까지 지울지는 체크로 고릅니다(기본은 지우지 않음).",
            "무료 사진·영상 찾기에 출처 탭이 생겼습니다: 전체 · 위키미디어 · Openverse · NASA(사진·영상) · 명화(메트로폴리탄 미술관). 명화는 목자·십자가·기도·천사·성경·풍경 같은 주제로 찾습니다.",
            "[배경과 음악]에 [무료 음악 찾기]가 생겼습니다. 잔잔한 피아노·기타·현악·자연 소리·찬양 분위기·명상으로 찾고, 미리 듣고 받아서 배경 음악으로 씁니다.",
            "새로 쓴 자료(NASA·명화·음악)의 출처도 영상 끝 글자와 [출처 문구 복사]에 들어갑니다. NASA 로고·휘장이 든 자료는 보여 주지 않습니다.",
        )
        "0.4.0" -> listOf(
            "API 키 없이 무료 사진·영상을 찾을 수 있습니다. [배경과 음악] 단계의 [무료 사진·영상 찾기]를 눌러 보세요.",
            "일출·바다·하늘·십자가 같은 주제 버튼을 누르거나 한글로 직접 입력해 찾습니다. 누구나 쓰도록 공개된 자료(CC0·공개 도메인·CC BY)만 보여 줍니다.",
            "고른 자료의 작가와 이용 조건이 자동으로 기록됩니다. 영상 끝에 출처 글자를 넣을 수 있고, 저장 화면에서 [출처 문구 복사]로 유튜브 설명란에 붙여 넣을 수 있습니다.",
            "Pexels 영상 찾기는 내 API 키가 있을 때만 쓰는 선택 기능으로 아래쪽에 있습니다.",
        )
        "0.3.0" -> listOf(
            "내 목소리로 영상을 만들 수 있습니다. [목소리] 단계에서 [내 목소리]를 고르세요.",
            "문장마다 직접 읽어 녹음하면 자막이 목소리에 정확히 맞습니다. 앞뒤의 조용한 부분은 저절로 잘라 주고, 소리 크기도 맞춰 줍니다.",
            "이미 녹음해 둔 파일(m4a, mp3, wav 등)을 올리면 문장별로 자동으로 나누고, 어긋난 곳은 화면에서 고칠 수 있습니다.",
            "녹음하지 않은 문장은 AI 목소리로 채울 수 있습니다. 녹음은 폰 안에만 저장되고 밖으로 보내지 않습니다.",
        )
        "0.2.0" -> listOf(
            "화면이 밝고 산뜻하게 바뀌었습니다.",
            "영상 템플릿 8가지를 한 번에 고를 수 있습니다(새벽 묵상·말씀 카드·감성 필름·미니멀 화이트·밤하늘·따뜻한 노을·성경 구절 강조·인용 명언).",
            "영상에 제목과 출처(예: 시편 23:1)를 넣을 수 있고, 끝 화면이 붙는 템플릿도 있습니다.",
            "사진이 천천히 확대·이동하는 효과가 들어갑니다.",
            "새 버전이 나오면 홈에 알림이 뜨고 앱 안에서 바로 업데이트할 수 있습니다.",
        )
        else -> listOf("기능이 개선되었습니다.")
    }
}
