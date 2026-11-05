package com.goodseed.shorts.logic

/**
 * 붙여넣은 글을 문장 단위로 나눈다. 한 문장 = 자막 한 장면 = 낭독 한 구간.
 *
 * 원칙: 글자를 만들어 내거나 고치지 않는다(공백 정리만). 사용자가 넣은 글에서만 자막이 나온다.
 *  - 줄바꿈은 항상 문장 경계(시·묵상 글은 줄이 곧 호흡이므로)
 *  - 마침표(.)·물음표(?)·느낌표(!)·말줄임표(…, ...)·「。」 뒤에서 끊되,
 *    숫자 사이의 점(3.5)·절 번호(요 3:16)처럼 뒤에 공백이 없는 점은 끊지 않는다
 *  - 문장 끝 기호 뒤에 붙은 닫는 따옴표·괄호(" ' ” ’ ) ] 」 』)는 같은 문장에 둔다
 *  - 너무 긴 문장(maxChars 초과)은 쉼표·공백 근처에서 나눈다(낭독·자막이 한 번에 너무 길어지지 않게)
 */
object SentenceSplitter {
    const val DEFAULT_MAX_CHARS = 90

    private val terminators = setOf('.', '!', '?', '…', '。', '！', '？')
    private val closers = setOf('"', '\'', '”', '’', ')', ']', '」', '』', '）', '》', '>')

    fun split(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        val out = ArrayList<String>()
        var pendingNumber = ""
        for (rawLine in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            for (s in splitLine(line)) {
                val t = s.trim().replace(Regex("[ \\t\\u00A0]+"), " ")
                if (t.isEmpty()) continue
                if (pendingNumber.isNotEmpty()) { out.add(pendingNumber + " " + t); pendingNumber = ""; continue }
                if (Regex("^[0-9]{1,3}[.)]$").matches(t)) { pendingNumber = t; continue }
                if (!t.any { it.isLetterOrDigit() }) {
                    // 기호만 있는 조각(예: 홀로 남은 따옴표)은 앞 문장에 붙인다
                    if (out.isNotEmpty()) out[out.lastIndex] = out.last() + t
                    continue
                }
                out.addAll(breakLong(t, maxChars))
            }
        }
        if (pendingNumber.isNotEmpty()) out.add(pendingNumber)
        return out
    }

    private fun splitLine(line: String): List<String> {
        val res = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            sb.append(c)
            if (c in terminators) {
                // 연속된 종결 기호(?!, ..., …)를 한 덩어리로
                var j = i + 1
                while (j < line.length && (line[j] in terminators)) { sb.append(line[j]); j++ }
                // 닫는 따옴표·괄호
                while (j < line.length && (line[j] in closers)) { sb.append(line[j]); j++ }
                val atEnd = j >= line.length
                val nextIsSpace = !atEnd && line[j].isWhitespace()
                val onlyDots = sb.takeLastWhile { it in terminators || it in closers }.all { it == '.' || it in closers }
                // 점(.)만으로 끝난 경우는 뒤가 공백이거나 줄 끝일 때만 문장 끝(3.5, 요3.16, www.a.com 보호)
                if (atEnd || nextIsSpace || !onlyDots) { res.add(sb.toString()); sb.setLength(0) }
                i = j
                continue
            }
            i++
        }
        if (sb.isNotBlank()) res.add(sb.toString())
        return res
    }

    /** maxChars 를 넘는 문장을 쉼표·공백 근처(가운데에 가까운 곳)에서 나눈다. 글자는 그대로 보존한다. */
    fun breakLong(s: String, maxChars: Int): List<String> {
        if (s.length <= maxChars) return listOf(s)
        val mid = s.length / 2
        // 1순위: 가운데에 가까운 쉼표 뒤, 2순위: 가운데에 가까운 공백
        var cut = -1
        var best = Int.MAX_VALUE
        for (k in 1 until s.length - 1) {
            if (s[k] == ',' || s[k] == '，' || s[k] == '、') {
                val d = kotlin.math.abs(k + 1 - mid)
                if (d < best) { best = d; cut = k + 1 }
            }
        }
        if (cut < 0 || best > s.length / 3) {
            best = Int.MAX_VALUE; cut = -1
            for (k in 1 until s.length - 1) {
                if (s[k] == ' ') {
                    val d = kotlin.math.abs(k - mid)
                    if (d < best) { best = d; cut = k }
                }
            }
        }
        if (cut < 0) cut = mid // 공백이 전혀 없으면 가운데에서 강제 분할
        val a = s.substring(0, cut).trim()
        val b = s.substring(cut).trim()
        if (a.isEmpty() || b.isEmpty()) return listOf(s)
        return breakLong(a, maxChars) + breakLong(b, maxChars)
    }
}
