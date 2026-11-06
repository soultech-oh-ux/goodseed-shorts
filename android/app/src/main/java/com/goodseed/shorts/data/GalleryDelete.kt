package com.goodseed.shorts.data

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** 갤러리(MediaStore)에 저장된 영상 한 개를 지운 결과. */
sealed class GalleryResult {
    object Deleted : GalleryResult()                          // 지움
    object Missing : GalleryResult()                          // 이미 없음(사용자가 갤러리에서 먼저 지움)
    class NeedUser(val sender: IntentSender) : GalleryResult() // 사용자 확인이 필요함(안드로이드 10·11 이상)
    class Failed(val message: String) : GalleryResult()
}

object GalleryDelete {
    /**
     * 이 앱이 만든 영상은 바로 지워진다. 다른 앱이 만들었거나(앱을 다시 설치한 경우 포함) 권한이 없으면
     * 안드로이드 11 이상은 createDeleteRequest, 10 은 RecoverableSecurityException 의 사용자 확인 창을 돌려준다.
     */
    fun delete(ctx: Context, uri: Uri): GalleryResult {
        val cr = ctx.contentResolver
        return try {
            if (cr.delete(uri, null, null) > 0) GalleryResult.Deleted else GalleryResult.Missing
        } catch (e: SecurityException) {
            when {
                Build.VERSION.SDK_INT >= 30 -> runCatching { GalleryResult.NeedUser(MediaStore.createDeleteRequest(cr, listOf(uri)).intentSender) as GalleryResult }
                    .getOrElse { GalleryResult.Failed("갤러리 영상을 지울 수 없습니다.") }
                Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException -> GalleryResult.NeedUser(e.userAction.actionIntent.intentSender)
                else -> GalleryResult.Failed("갤러리 영상을 지울 수 없습니다.")
            }
        } catch (e: Exception) { GalleryResult.Failed("갤러리 영상을 지우지 못했습니다.") }
    }
}
