package com.goodseed.shorts.logic

/**
 * 자막 자동 줄바꿈(직접 구현). 글자 폭 측정은 measure 로 주입한다(앱에서는 Paint.measureText, 시험에서는 글자 수).
 *  - 띄어쓰기 단위로 채우고, 한 단어가 한 줄보다 길면 글자 단위로 끊는다
 *  - 줄 첫머리에 마침표·쉼표·닫는 따옴표 등이 오지 않도록 앞 줄 끝으로 올린다(금칙 처리)
 *  - balance=true 면 같은 줄 수를 유지하는 가장 좁은 폭으로 다시 채워 줄 길이를 고르게 한다
 */
object SubtitleWrapper {
    private const val NO_LINE_START = ".,!?:;…·)]}\"'”’」』）》>、。，！？"

    fun wrap(text: String, maxWidth: Float, balance: Boolean = true, measure: (String) -> Float): List<String> {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return emptyList()
        if (maxWidth <= 0f) return listOf(clean)
        val first = greedy(clean, maxWidth, measure)
        if (!balance || first.size <= 1) return first
        var lo = maxWidth * 0.4f
        var hi = maxWidth
        var best = first
        repeat(14) {
            val mid = (lo + hi) / 2f
            val r = greedy(clean, mid, measure)
            if (r.size <= first.size && r.maxOf { measure(it) } <= maxWidth * 1.0001f) { best = r; hi = mid } else lo = mid
        }
        return best
    }

    /** 줄 목록을 한 화면(자막 한 장)에 보일 최대 줄 수씩 나눈다. 글자 수 비율로 시간을 나눌 때 쓴다. */
    fun paginate(lines: List<String>, maxLines: Int): List<List<String>> {
        if (lines.isEmpty()) return emptyList()
        val n = maxLines.coerceAtLeast(1)
        val pages = lines.chunked(n)
        // 마지막 쪽이 한 줄만 남으면 앞 쪽과 고르게 나눈다
        if (pages.size >= 2 && pages.last().size == 1 && n >= 3) {
            val all = lines
            val count = pages.size
            val per = (all.size + count - 1) / count
            return all.chunked(per)
        }
        return pages
    }

    private fun greedy(clean: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { lines.add(cur.toString().trimEnd()); cur = StringBuilder() } }
        for (word in clean.split(' ')) {
            if (word.isEmpty()) continue
            val joined = if (cur.isEmpty()) word else "$cur $word"
            if (measure(joined) <= maxWidth) { cur = StringBuilder(joined); continue }
            if (cur.isNotEmpty()) flush()
            if (measure(word) <= maxWidth) { cur = StringBuilder(word); continue }
            // 한 단어가 한 줄보다 길다 → 글자 단위로 끊는다
            for (ch in word) {
                val t = cur.toString() + ch
                if (cur.isNotEmpty() && measure(t) > maxWidth) flush()
                cur.append(ch)
            }
        }
        flush()
        return fixLineStarts(lines)
    }

    /** 줄 첫 글자가 금칙 문자면 앞 줄 끝으로 올린다. */
    private fun fixLineStarts(lines: List<String>): List<String> {
        val l = lines.toMutableList()
        for (i in 1 until l.size) {
            var cur = l[i]
            while (cur.isNotEmpty() && cur[0] in NO_LINE_START) {
                l[i - 1] = l[i - 1] + cur[0]
                cur = cur.substring(1).trimStart()
            }
            l[i] = cur
        }
        return l.filter { it.isNotEmpty() }
    }
}
