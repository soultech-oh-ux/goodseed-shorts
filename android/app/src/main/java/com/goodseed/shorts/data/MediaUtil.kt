package com.goodseed.shorts.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

object MediaUtil {
    /** 고른 사진·영상·음원 한 개의 종류·이름·길이를 읽는다. 읽을 수 없으면 null. */
    fun probe(ctx: Context, uri: Uri, origin: MediaOrigin = MediaOrigin.PHONE, credit: String = ""): MediaRef? {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri).orEmpty()
        val isVideo = mime.startsWith("video/")
        val isImage = mime.startsWith("image/")
        if (!isVideo && !isImage) return null
        var dur = 0L
        if (isVideo) dur = durationMs(ctx, uri) ?: return null
        return MediaRef(UUID.randomUUID().toString(), uri.toString(), isVideo, dur, displayName(cr, uri) ?: (if (isVideo) "영상" else "사진"), origin, credit)
    }

    fun probeAudio(ctx: Context, uri: Uri): MusicChoice? {
        val mime = ctx.contentResolver.getType(uri).orEmpty()
        if (!mime.startsWith("audio/") && !mime.startsWith("video/")) return null
        val d = durationMs(ctx, uri) ?: return null
        if (d <= 0) return null
        return MusicChoice(uri.toString(), displayName(ctx.contentResolver, uri) ?: "음악", d)
    }

    fun displayName(cr: ContentResolver, uri: Uri): String? = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    fun durationMs(ctx: Context, uri: Uri): Long? {
        val r = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) { null } finally { runCatching { r.release() } }
    }

    /** 카드·미리보기용 그림. 사진은 줄여서 읽고, 영상은 첫 장면 한 컷. */
    fun thumbnail(ctx: Context, ref: MediaRef, maxSide: Int = 720): Bitmap? = try {
        val uri = Uri.parse(ref.uri)
        if (ref.isVideo) {
            val r = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(ctx, uri)
                r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it, maxSide) }
            } finally { runCatching { r.release() } }
        } else {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val big = maxOf(info.size.width, info.size.height)
                if (big > maxSide) { val f = maxSide.toFloat() / big; dec.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1)) }
            }
        }
    } catch (e: Throwable) { null }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val big = maxOf(b.width, b.height)
        if (big <= maxSide) return b
        val f = maxSide.toFloat() / big
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    /** 합성된 mp4 를 갤러리(Movies/굿씨드묵상숏츠)에 저장하고 content Uri 를 돌려준다. */
    fun saveToGallery(ctx: Context, src: File, displayName: String): Uri? {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/굿씨드묵상숏츠")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            cr.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("저장 위치를 열지 못했습니다")
            if (Build.VERSION.SDK_INT >= 29) {
                cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (e: Exception) { runCatching { cr.delete(uri, null, null) }; null }
    }

    /** 완성된 영상의 크기·길이(합성 결과 확인용). */
    data class VideoFacts(val width: Int, val height: Int, val durationMs: Long)
    fun videoFacts(file: File): VideoFacts? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            VideoFacts(w, h, r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
        } catch (e: Exception) { null } finally { runCatching { r.release() } }
    }
}
