package com.goodseed.shorts.logic

import java.io.File
import java.io.RandomAccessFile

/**
 * 낭독 wav 앞뒤에 무음을 붙여 "한 장면 = 한 파일" 로 만든다.
 * 장면 길이(Timing)와 오디오 길이가 정확히 같아지므로 합성에서 별도 간격(gap) 처리가 필요 없다.
 * 16비트 PCM wav 만 다룬다(안드로이드 TextToSpeech.synthesizeToFile 의 기본 출력).
 */
object WavPad {
    /** 성공하면 만든 파일의 길이(마이크로초), 다룰 수 없는 형식이면 null. */
    fun pad(src: File, dst: File, leadUs: Long, totalUs: Long): Long? {
        val info = WavInfo.parse(src) ?: return null
        if (!info.isPcm16 || info.frameBytes <= 0) return null
        val frame = info.frameBytes
        val leadBytes = alignedBytes(leadUs, info)
        val totalBytes = alignedBytes(totalUs, info)
        val dataBytes = (info.dataBytes / frame) * frame
        val tailBytes = (totalBytes - leadBytes - dataBytes).coerceAtLeast(0)
        val outData = leadBytes + dataBytes + tailBytes
        if (outData > Int.MAX_VALUE - 64) return null
        dst.parentFile?.mkdirs()
        RandomAccessFile(dst, "rw").use { out ->
            out.setLength(0)
            out.write(header(info, outData))
            writeZeros(out, leadBytes)
            RandomAccessFile(src, "r").use { inp ->
                inp.seek(info.dataOffset.toLong())
                val buf = ByteArray(64 * 1024)
                var left = dataBytes
                while (left > 0) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n); left -= n
                }
            }
            writeZeros(out, tailBytes)
        }
        return outData * 1_000_000L / (info.sampleRate.toLong() * frame)
    }

    private fun alignedBytes(us: Long, info: WavInfo): Long =
        (us * info.sampleRate / 1_000_000L) * info.frameBytes

    private fun writeZeros(out: RandomAccessFile, n: Long) {
        val z = ByteArray(64 * 1024)
        var left = n
        while (left > 0) { val k = minOf(z.size.toLong(), left).toInt(); out.write(z, 0, k); left -= k }
    }

    internal fun header(info: WavInfo, dataBytes: Long): ByteArray {
        val b = java.io.ByteArrayOutputStream(44)
        fun le32(v: Long) = b.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = b.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        b.write("RIFF".toByteArray()); le32(36 + dataBytes); b.write("WAVE".toByteArray())
        b.write("fmt ".toByteArray()); le32(16); le16(1); le16(info.channels); le32(info.sampleRate.toLong())
        le32(info.sampleRate.toLong() * info.frameBytes); le16(info.frameBytes); le16(16)
        b.write("data".toByteArray()); le32(dataBytes)
        return b.toByteArray()
    }
}
