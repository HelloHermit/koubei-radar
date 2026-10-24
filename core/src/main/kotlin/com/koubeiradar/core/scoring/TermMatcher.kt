package com.koubeiradar.core.scoring

import java.util.Arrays

internal enum class TermKind { POSITIVE, NEGATIVE, RED_FLAG, NEUTRAL }

internal class TermMatch(val term: String, val kind: TermKind, val start: Int, val end: Int)

/**
 * Multi-pattern substring matcher over a character trie. A scan is linear in the text length times the length of
 * the longest term, independent of the number of terms.
 */
internal class TermTrie(terms: List<Pair<String, TermKind>>) {

    private class Node {
        var keys = CharArray(0)
        var children = arrayOfNulls<Node>(0)
        var kind: TermKind? = null

        fun child(key: Char): Node? {
            val index = Arrays.binarySearch(keys, key)
            return if (index >= 0) children[index] else null
        }

        fun childOrCreate(key: Char): Node {
            val index = Arrays.binarySearch(keys, key)
            if (index >= 0) return children[index] ?: Node().also { children[index] = it }
            val insertAt = -index - 1
            val created = Node()
            keys = keys.copyOf(keys.size + 1).also {
                System.arraycopy(keys, insertAt, it, insertAt + 1, keys.size - insertAt)
                it[insertAt] = key
            }
            children = children.copyOf(children.size + 1).also {
                System.arraycopy(children, insertAt, it, insertAt + 1, children.size - insertAt)
                it[insertAt] = created
            }
            return created
        }
    }

    private val root = Node()

    init {
        for ((term, kind) in terms) {
            require(term.isNotEmpty()) { "empty lexicon term" }
            var node = root
            for (char in term) node = node.childOrCreate(char)
            node.kind = kind
        }
    }

    /** Leftmost-longest, non-overlapping matches in text order. */
    fun scan(text: String): List<TermMatch> {
        val matches = ArrayList<TermMatch>()
        var start = 0
        while (start < text.length) {
            var node = root
            var position = start
            var matchEnd = -1
            var matchKind: TermKind? = null
            while (position < text.length) {
                node = node.child(text[position]) ?: break
                position++
                val kind = node.kind
                if (kind != null) {
                    matchEnd = position
                    matchKind = kind
                }
            }
            if (matchKind != null) {
                matches += TermMatch(text.substring(start, matchEnd), matchKind, start, matchEnd)
                start = matchEnd
            } else {
                start++
            }
        }
        return matches
    }
}

/**
 * Simple negation handling: a negator at most [MAX_FILLER] characters before a term, inside the same clause,
 * cancels that term ("不难吃", "没有异物", "没吃到头发"). A negator only reaches the first term after it, so the
 * negator inside "不错" does not cancel the "分量足" in "不错分量足".
 */
internal object Negation {
    private const val MAX_FILLER = 2

    private val negatorWords: List<String> = buildList {
        addAll(listOf("不", "没", "没有", "无", "未", "别", "并不", "不会", "从未"))
        for (negator in listOf("没", "没有", "未")) {
            for (verb in listOf("发现", "吃到", "吃出", "看到", "见到", "闻到", "尝到")) add(negator + verb)
        }
    }

    /** Words that merely contain a negator character ("特别好吃", "不过太咸", "无比美味"). */
    private val notNegations: List<String> = listOf(
        "不过", "不仅", "不但", "不光", "不只", "不止", "不管", "不论", "不得不", "不愧", "不然", "不禁", "不免",
        "没想到", "没错",
        "特别", "区别", "分别", "个别", "差别", "类别", "性别", "辨别", "告别", "识别", "鉴别", "级别", "派别",
        "别的", "别家", "别人", "别处", "别样",
        "无比", "无敌", "无限", "无疑", "无穷", "无论",
        "未来",
    )

    private class Negator(val word: String) {
        /** Each pair: a non-negating word containing [word], and the offset of [word] inside it. */
        private val lookalikes: List<Pair<String, Int>> = notNegations.flatMap { other ->
            other.indices.filter { other.startsWith(word, it) }.map { other to it }
        }

        fun endsAt(text: String, end: Int, notBefore: Int): Boolean {
            val start = end - word.length
            if (start < notBefore || !text.startsWith(word, start)) return false
            return lookalikes.none { (other, offset) -> text.startsWith(other, start - offset) }
        }
    }

    private val negators: List<Negator> = negatorWords.map(::Negator)

    /** [notBefore] is the end of the previous term: a negator may not start inside it. */
    fun isNegated(text: String, termStart: Int, notBefore: Int): Boolean {
        for (filler in 0..MAX_FILLER) {
            if (filler > 0) {
                val index = termStart - filler
                if (index < notBefore || !text[index].isLetterOrDigit()) return false
            }
            val negatorEnd = termStart - filler
            if (negators.any { it.endsAt(text, negatorEnd, notBefore) }) return true
        }
        return false
    }
}
