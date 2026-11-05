package com.goodseed.shorts.logic

import java.io.File
import java.io.RandomAccessFile

/** RIFF/WAVE(PCM) 헤더에서 길이를 읽는다. TextToSpeech.synthesizeToFile 이 만든 wav 의 길이를 구하는 데 쓴다. */
data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataBytes: Long, val formatTag: Int = 1, val dataOffset: Int = 44) {
    /** 16비트 PCM 인가(무음 덧붙이기 등 직접 다룰 수 있는 형식) */
    val isPcm16: Boolean get() = bitsPerSample == 16 && (formatTag == 1 || formatTag == 0xFFFE)
    val frameBytes: Int get() = channels * (bitsPerSample / 8)
    val durationUs: Long
        get() {
            val bytesPerSec = sampleRate.toLong() * channels * (bitsPerSample / 8)
            return if (bytesPerSec <= 0) 0L else dataBytes * 1_000_000L / bytesPerSec
        }

    companion object {
        fun parse(file: File): WavInfo? = try {
            RandomAccessFile(file, "r").use { f -> parse(ByteArray(minOf(f.length(), 4096L).toInt()).also { f.readFully(it) }, file.length()) }
        } catch (e: Exception) { null }

        /** head 는 파일 앞부분(헤더가 들어갈 만큼), totalLength 는 파일 전체 길이. */
        fun parse(head: ByteArray, totalLength: Long): WavInfo? {
            if (head.size < 12 || String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var pos = 12
            var rate = 0; var ch = 0; var bits = 0; var tag = 1
            while (pos + 8 <= head.size) {
                val id = String(head, pos, 4, Charsets.US_ASCII)
                val size = le32(head, pos + 4)
                val body = pos + 8
                if (id == "fmt " && body + 16 <= head.size) {
                    tag = le16(head, body); ch = le16(head, body + 2); rate = le32(head, body + 4).toInt(); bits = le16(head, body + 14)
                } else if (id == "data") {
                    if (rate <= 0 || ch <= 0 || bits <= 0) return null
                    // 스트리밍으로 만든 파일은 크기가 0 또는 0xFFFFFFFF — 그땐 파일 끝까지를 데이터로 본다
                    val remain = totalLength - body
                    val n = if (size == 0L || size == 0xFFFFFFFFL || size > remain) remain else size
                    return WavInfo(rate, ch, bits, n.coerceAtLeast(0), tag, body)
                }
                pos = body + size.toInt() + (size.toInt() and 1)
                if (size < 0 || pos < 0) return null
            }
            return null
        }

        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        private fun le32(b: ByteArray, o: Int): Long = (le16(b, o).toLong()) or (le16(b, o + 2).toLong() shl 16)
    }
}
