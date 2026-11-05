package com.goodseed.shorts.data

import com.goodseed.shorts.render.SubFont
import com.goodseed.shorts.render.SubSize
import com.goodseed.shorts.render.Templates
import com.goodseed.shorts.render.VideoTemplate

enum class MediaOrigin { PHONE, IMPORTED, PEXELS }

/** 배경으로 쓸 사진·영상 한 개. uri 는 content:// 또는 file:// 문자열. */
data class MediaRef(
    val id: String,
    val uri: String,
    val isVideo: Boolean,
    val durationMs: Long,          // 영상 길이(사진은 0)
    val label: String,
    val origin: MediaOrigin = MediaOrigin.PHONE,
    val credit: String = "",       // 예: Pexels 촬영자
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

data class MusicChoice(val uri: String, val label: String, val durationMs: Long)

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
) {
    val template: VideoTemplate get() = Templates.byId(templateId)

    val recordedCount: Int get() = scenes.count { it.myVoice != null }
    val missingCount: Int get() = scenes.size - recordedCount

    val title: String get() = scenes.firstOrNull()?.text?.take(24) ?: "묵상 영상"

    /** 순번대로 돌아가며 장면에 배경을 배정한다(사용자가 장면별로 바꾼 것은 그대로 둔다). */
    fun mediaFor(scene: Scene, indexInScenes: Int): MediaRef? {
        if (media.isEmpty()) return null
        scene.mediaId?.let { id -> media.firstOrNull { it.id == id }?.let { return it } }
        return media[indexInScenes % media.size]
    }
}

/** 최근 작품(갤러리에 저장된 결과). */
data class RecentWork(val title: String, val uri: String, val savedAtMillis: Long, val thumbPath: String)
