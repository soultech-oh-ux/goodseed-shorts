package com.goodseed.shorts.render

import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation

/** 확대 배율과 이동량(정규화 좌표, 프레임 가로·세로가 −1~1). */
data class KbState(val scale: Float, val tx: Float, val ty: Float)

/** Ken Burns 수식(순수 계산 — JVM 에서 시험). progress 는 0~1. 가장자리가 비지 않도록 이동량은 (배율−1) 이내. */
object KenBurnsMath {
    fun state(motion: Motion, amount: Float, progress: Float): KbState {
        val p = progress.coerceIn(0f, 1f)
        val a = amount.coerceIn(0f, 0.30f)
        return when (motion) {
            Motion.NONE -> KbState(1f, 0f, 0f)
            Motion.ZOOM_IN -> KbState(1f + a * p, 0f, 0f)
            Motion.ZOOM_OUT -> KbState(1f + a * (1f - p), 0f, 0f)
            Motion.PAN_LEFT -> KbState(1f + a, a * (1f - 2f * p), 0f)     // 내용이 왼쪽으로 흐른다(+ → −)
            Motion.PAN_RIGHT -> KbState(1f + a, -a * (1f - 2f * p), 0f)
            Motion.PAN_UP -> KbState(1f + a, 0f, -a * (1f - 2f * p))      // 내용이 위로 올라간다(NDC 위쪽이 +)
        }
    }
}

/**
 * 사진 한 장(한 장면)에 거는 시간 변화 효과. 장면(항목)마다 새 인스턴스를 쓴다.
 * 프레임 시각이 항목 기준인지 합성 전체 기준인지 어느 쪽이어도 되도록, 처음 들어온 0 초과 시각을 기준점으로 삼는다
 * (첫 프레임 한 장 정도의 오차만 생긴다).
 */
@OptIn(UnstableApi::class)
class KenBurnsEffect(private val motion: Motion, private val amount: Float, private val durationUs: Long) : MatrixTransformation {
    private var t0 = -1L

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        if (t0 < 0 && presentationTimeUs > 0) t0 = presentationTimeUs
        val local = if (t0 < 0) 0L else presentationTimeUs - t0
        val p = if (durationUs <= 0) 0f else (local.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
        val s = KenBurnsMath.state(motion, amount, p)
        return Matrix().apply { setScale(s.scale, s.scale); postTranslate(s.tx, s.ty) }
    }
}
