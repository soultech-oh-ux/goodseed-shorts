package com.goodseed.shorts.data

import com.goodseed.shorts.logic.Attribution
import com.goodseed.shorts.render.SubFont
import com.goodseed.shorts.render.SubSize
import com.goodseed.shorts.render.Templates
import com.goodseed.shorts.render.VideoTemplate

enum class MediaOrigin { PHONE, IMPORTED, PEXELS, FREE }

/** 배경으로 쓸 사진·영상 한 개. uri 는 content:// 또는 file:// 문자열. */
data class MediaRef(
    val id: String,
    val uri: String,
    val isVideo: Boolean,
    val durationMs: Long,          // 영상 길이(사진은 0)
    val label: String,
    val origin: MediaOrigin = MediaOrigin.PHONE,
    val credit: String = "",       // 예: Pexels 촬영자
    val attribution: Attribution? = null,   // 무료 자료(위키미디어·Openverse·Pexels)의 표기 정보
)

/** 한 장면 = 한 문장 = 자막 한 장 = 낭독 한 구간. */
data class Scene(
    val id: Long,
    val text: String,
    val mediaId: String? = null,   // null 이면 기본 배경
    val size: SubSize = SubSize.NORMAL,
    val myVoice: String? = null,   // 내 목소리 녹음 파일(앱 내부 wav, 앞뒤 무음 다듬고 크기 맞춤). null 이면 녹음 없음
    val myVoiceMs: Long = 0,
)

data class VoiceChoice(val name: String, val label: String, val needsNetwork: Boolean)

enum class Speed(val label: String, val rate: Float) { SLOW("느리게", 0.8f), NORMAL("보통", 1.0f) }

data class MusicChoice(val uri: String, val label: String, val durationMs: Long, val attribution: Attribution? = null)   // 무료 음악 찾기로 받은 곡은 출처 정보가 붙는다

enum class VoiceMode { AI, MINE }

/** 만들고 있는 작품 한 편(앱을 켜 두는 동안만 유지). */
data class Project(
    val id: String = java.util.UUID.randomUUID().toString(),   // 녹음 폴더 이름
    val voiceMode: VoiceMode = VoiceMode.AI,
    val rawText: String = "",
    val scenes: List<Scene> = emptyList(),
    val voice: VoiceChoice? = null,
    val speed: Speed = Speed.SLOW,
    val media: List<MediaRef> = emptyList(),      // 선택 순서가 곧 순번
    val music: MusicChoice? = null,
    val musicVolume: Float = 0.5f,
    val font: SubFont = SubFont.PRETENDARD,
    val templateId: String = Templates.DEFAULT.id,
    val headerTitle: String = "",      // 영상에 넣는 제목(선택)
    val headerSource: String = "",     // 출처 줄(선택, 예: 시편 23:1)
    val showCredits: Boolean = true,   // 영상 끝에 사진·영상 출처 글자를 넣을지
) {
    val template: VideoTemplate get() = Templates.byId(templateId)

    val recordedCount: Int get() = scenes.count { it.myVoice != null }
    val missingCount: Int get() = scenes.size - recordedCount

    /** 실제로 장면에 쓰이는 자료의 표기 정보(같은 자료는 한 번만). 고르기만 하고 장면에 안 들어간 자료는 뺀다. */
    fun usedAttributions(): List<Attribution> =
        (scenes.mapIndexedNotNull { i, s -> mediaFor(s, i)?.attribution } + listOfNotNull(music?.attribution)).distinctBy { it.sourceUrl.ifBlank { it.title + "|" + it.author } }

    val title: String get() = scenes.firstOrNull()?.text?.take(24) ?: "묵상 영상"

    /** 순번대로 돌아가며 장면에 배경을 배정한다(사용자가 장면별로 바꾼 것은 그대로 둔다). */
    fun mediaFor(scene: Scene, indexInScenes: Int): MediaRef? {
        if (media.isEmpty()) return null
        scene.mediaId?.let { id -> media.firstOrNull { it.id == id }?.let { return it } }
        return media[indexInScenes % media.size]
    }
}

/** 최근 작품(갤러리에 저장된 결과). */
data class RecentWork(
    val title: String, val uri: String, val savedAtMillis: Long, val thumbPath: String,
    val projectId: String = "",                 // 이 작품을 만들 때 쓴 녹음 폴더 이름(지울 때 함께 정리)
    val files: List<String> = emptyList(),       // 이 작품이 쓴 앱 내부 받은 파일(무료 자료·음악)
    val snapshot: String = "",                   // [수정]용 작품 상태 파일 이름(files/projects/ 안). 0.5.0 이전 작품은 빈칸
) {
    val editable: Boolean get() = snapshot.isNotBlank()
}
