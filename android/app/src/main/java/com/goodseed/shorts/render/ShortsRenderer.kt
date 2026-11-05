package com.goodseed.shorts.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.goodseed.shorts.logic.DuckingCurve
import com.goodseed.shorts.logic.SceneTime
import com.goodseed.shorts.logic.Timing
import java.io.File

/** 합성에 필요한 모든 입력(장면별 배경·자막 크기·낭독 파일). */
class RenderScene(
    val text: String,
    val size: SubSize,
    val bgUri: String?,          // null 이면 기본 배경
    val bgIsVideo: Boolean,
    val bgDurationMs: Long,
    val voiceWav: File,          // 장면 길이에 맞춰 무음을 붙인 낭독 파일
)

class RenderInput(
    val scenes: List<RenderScene>,
    val times: List<SceneTime>,
    val font: SubFont,
    val musicUri: String?,
    val musicDurationMs: Long,
    val musicVolume: Float,
)

/**
 * 1080x1920 MP4 합성(AndroidX Media3 Transformer, Composition).
 *  - 영상 줄: 장면 길이만큼의 배경(사진 그대로 / 영상은 잘라 쓰거나 반복) — 소리는 제거, 9:16 으로 채워 자름
 *  - 낭독 줄: 장면 하나당 파일 하나(앞뒤 무음 포함 = 장면 길이와 같음)
 *  - 음악 줄: 합성 길이만큼 이어 붙이고 조각마다 DuckingAudioProcessor(낭독 구간 자동 볼륨 낮춤)
 *  - 합성 전체에 SubtitleOverlay(시간별 TextOverlay) 하나
 */
@OptIn(UnstableApi::class)
object ShortsRenderer {
    const val OUT_W = SubtitleLayout.FRAME_W
    const val OUT_H = SubtitleLayout.FRAME_H
    private const val FPS = 30
    private const val VIDEO_BITRATE = 8_000_000

    fun build(ctx: Context, input: RenderInput, cacheDir: File): Composition {
        val tf = Fonts.typeface(ctx, input.font)
        val total = Timing.totalUs(input.times)

        // 자막: 장면마다 줄바꿈 → 쪽 나누기 → 시간표
        val pages = input.scenes.map { s -> SubtitleLayout.pages(SubtitleLayout.lines(s.text, tf, s.size), s.size) }
        val cues = Timing.cues(input.times, pages)
        val timedTexts = cues.map { c -> com.goodseed.shorts.render.TimedText(c.startUs, c.endUs, SubtitleLayout.spannable(c.lines, tf, input.scenes[c.sceneIndex].size)) }
        val overlay = SubtitleOverlay(timedTexts)

        // 영상 줄
        val fit = Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val fallbackBg = defaultBackground(cacheDir)
        val videoItems = ArrayList<EditedMediaItem>()
        for ((i, s) in input.scenes.withIndex()) {
            val durUs = input.times[i].durationUs
            val bg = s.bgUri
            if (bg == null) {
                videoItems += imageItem(fallbackBg.toUri(), durUs, fit)
            } else if (!s.bgIsVideo) {
                videoItems += imageItem(bg.toUri(), durUs, fit)
            } else {
                videoItems += videoItems(bg.toUri(), s.bgDurationMs, durUs, fit)
            }
        }
        val videoSeq = sequence(C.TRACK_TYPE_VIDEO, videoItems)

        // 낭독 줄
        val voiceItems = input.scenes.map { s -> EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(s.voiceWav))).build() }
        val voiceSeq = sequence(C.TRACK_TYPE_AUDIO, voiceItems)

        val seqs = arrayListOf(videoSeq, voiceSeq)

        // 음악 줄
        if (input.musicUri != null && input.musicDurationMs > 0) {
            val curve = DuckingCurve(Timing.speechSpans(input.times))
            val segs = Timing.musicSegments(total, input.musicDurationMs * 1000L)
            val items = segs.map { seg ->
                val item = MediaItem.Builder().setUri(input.musicUri)
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(seg.lengthUs / 1000L).build())
                    .build()
                val proc: AudioProcessor = DuckingAudioProcessor(curve, seg.startUs, input.musicVolume)
                EditedMediaItem.Builder(item).setRemoveVideo(true).setEffects(Effects(listOf(proc), emptyList())).build()
            }
            if (items.isNotEmpty()) seqs += sequence(C.TRACK_TYPE_AUDIO, items)
        }

        val globalEffects = Effects(emptyList<AudioProcessor>(), listOf<Effect>(OverlayEffect(listOf(overlay))))
        return Composition.Builder(seqs).setEffects(globalEffects).build()
    }

    /** 줄(시퀀스) 하나 — 영상 줄은 영상 트랙만, 낭독·음악 줄은 오디오 트랙만 만든다. */
    private fun sequence(trackType: Int, items: List<EditedMediaItem>): EditedMediaItemSequence =
        EditedMediaItemSequence.Builder(setOf(trackType)).addItems(items).build()

    private fun imageItem(uri: Uri, durUs: Long, fit: Presentation): EditedMediaItem {
        val mi = MediaItem.Builder().setUri(uri).setImageDurationMs(durUs / 1000L).build()
        return EditedMediaItem.Builder(mi).setDurationUs(durUs).setFrameRate(FPS)
            .setEffects(Effects(emptyList(), listOf<Effect>(fit))).build()
    }

    /** 영상이 장면보다 짧으면 처음부터 반복해 장면 길이를 채운다. */
    private fun videoItems(uri: Uri, clipMs: Long, durUs: Long, fit: Presentation): List<EditedMediaItem> {
        val out = ArrayList<EditedMediaItem>()
        var leftMs = durUs / 1000L
        val full = if (clipMs > 0) clipMs else leftMs
        while (leftMs > 0) {
            val take = minOf(full, leftMs)
            val mi = MediaItem.Builder().setUri(uri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(take).build()).build()
            out += EditedMediaItem.Builder(mi).setRemoveAudio(true)
                .setEffects(Effects(emptyList(), listOf<Effect>(fit))).build()
            leftMs -= take
        }
        return out
    }

    /** 배경을 고르지 않았을 때 쓰는 은은한 초록 그라데이션 한 장. */
    fun defaultBackground(cacheDir: File): File {
        val f = File(cacheDir, "default-bg-v1.png")
        if (f.exists() && f.length() > 0) return f
        cacheDir.mkdirs()
        val bmp = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 0f, OUT_H.toFloat(), 0xFF1F6B44.toInt(), 0xFF0E2E1E.toInt(), Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRect(0f, 0f, OUT_W.toFloat(), OUT_H.toFloat(), p)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    interface Listener {
        fun onDone(output: File)
        fun onError(message: String, e: ExportException)
    }

    /** 메인(루퍼) 스레드에서 호출한다. 진행률은 transformer.getProgress 로 읽는다. */
    fun start(ctx: Context, composition: Composition, output: File, listener: Listener): Transformer {
        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build())
            .build()
        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { listener.onDone(output) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    listener.onError(exportException.message ?: "합성 중 오류", exportException)
                }
            })
            .build()
        output.parentFile?.mkdirs(); if (output.exists()) output.delete()
        t.start(composition, output.absolutePath)
        return t
    }

    fun progress(t: Transformer): Int {
        val h = ProgressHolder()
        return if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) h.progress else -1
    }
}
