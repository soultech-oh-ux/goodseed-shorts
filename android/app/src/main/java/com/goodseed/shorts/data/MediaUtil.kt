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

    /** 앱 안에 받아 둔 사진·영상 파일(file://)을 읽는다. file:// 주소는 ContentResolver 가 종류를 알려 주지 않으므로 종류는 호출한 쪽이 안다.
     *  못 읽으면 null, 영상을 이 폰이 처리하지 못하면 unsupported 에 안내 문구를 담는다. */
    class FileProbe(val ref: MediaRef?, val unsupported: String? = null)

    fun probeFile(ctx: Context, file: File, isVideo: Boolean, origin: MediaOrigin, label: String, credit: String = "", attribution: com.goodseed.shorts.logic.Attribution? = null): FileProbe {
        if (!file.isFile || file.length() <= 0) return FileProbe(null)
        var dur = 0L
        if (isVideo) {
            VideoSupport.problem(file)?.let { return FileProbe(null, it) }
            dur = durationMs(ctx, Uri.fromFile(file)) ?: return FileProbe(null)
            if (dur <= 0) return FileProbe(null)
        } else {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return FileProbe(null)
        }
        return FileProbe(MediaRef(UUID.randomUUID().toString(), Uri.fromFile(file).toString(), isVideo, dur, label, origin, credit, attribution))
    }

    /** 앱 안에 받아 둔 음악 파일(file://). file:// 은 ContentResolver 가 종류를 알려 주지 않으므로 길이만으로 읽을 수 있는지 확인한다. */
    fun probeAudioFile(ctx: Context, file: File, label: String, attribution: com.goodseed.shorts.logic.Attribution?): MusicChoice? {
        if (!file.isFile || file.length() <= 0) return null
        val d = durationMs(ctx, Uri.fromFile(file)) ?: return null
        if (d <= 0) return null
        return MusicChoice(Uri.fromFile(file).toString(), label, d, attribution)
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

/** 받은 영상을 이 폰이 읽고 풀 수 있는지(webm 의 VP8·VP9 등) 미리 확인한다. */
object VideoSupport {
    /** 쓸 수 없으면 한국어 안내 문구, 쓸 수 있으면 null. */
    fun problem(file: File): String? {
        val ex = android.media.MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            var mime: String? = null
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (m.startsWith("video/")) { mime = m; break }
            }
            if (mime == null) return "이 파일에서 영상을 찾지 못했습니다. 다른 영상을 골라 주세요."
            val hasDecoder = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            if (!hasDecoder) "이 폰은 이 영상의 형식(${shortName(mime)})을 읽지 못합니다. 다른 영상이나 사진을 골라 주세요." else null
        } catch (e: Exception) { "이 영상 파일을 열지 못했습니다. 다른 영상을 골라 주세요." } finally { runCatching { ex.release() } }
    }

    fun shortName(mime: String): String = when (mime.lowercase()) {
        "video/x-vnd.on2.vp9" -> "VP9"; "video/x-vnd.on2.vp8" -> "VP8"; "video/avc" -> "H.264"; "video/hevc" -> "H.265"; "video/av01" -> "AV1"
        else -> mime.removePrefix("video/")
    }
}
