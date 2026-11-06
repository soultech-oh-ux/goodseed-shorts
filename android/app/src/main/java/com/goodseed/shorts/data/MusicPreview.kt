package com.goodseed.shorts.data

import android.media.AudioAttributes
import android.media.MediaPlayer

/** 인터넷 주소의 음악을 받지 않고 바로 들어 보는 플레이어(시험에서는 가짜로 바꿔 끼운다). */
interface UrlPreviewPlayer {
    enum class State { LOADING, PLAYING, ENDED, ERROR }
    fun play(url: String, onState: (State) -> Unit)
    fun stop()
}

class MediaPlayerPreview : UrlPreviewPlayer {
    private var mp: MediaPlayer? = null

    override fun play(url: String, onState: (UrlPreviewPlayer.State) -> Unit) {
        stop()
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build())
            p.setDataSource(url)
            p.setOnPreparedListener { if (mp === p) { it.start(); onState(UrlPreviewPlayer.State.PLAYING) } }
            p.setOnCompletionListener { if (mp === p) onState(UrlPreviewPlayer.State.ENDED) }
            p.setOnErrorListener { _, _, _ -> if (mp === p) onState(UrlPreviewPlayer.State.ERROR); true }
            onState(UrlPreviewPlayer.State.LOADING)
            p.prepareAsync()
        } catch (e: Exception) { runCatching { p.release() }; if (mp === p) mp = null; onState(UrlPreviewPlayer.State.ERROR) }
    }

    override fun stop() { mp?.let { runCatching { it.stop() }; runCatching { it.release() } }; mp = null }
}
