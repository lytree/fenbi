package com.lytree.qbank

import com.lytree.qbank.data.importer.Normalizer
import com.lytree.qbank.data.model.Option
import com.lytree.qbank.data.model.QuestionType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 答案归一化口径（[Normalizer.normalizeAnswer]）。
 *
 * 重点守住「多选连写 ABC」：这是最常见的多选答案写法，历史上没有分隔符时
 * 整个 token 对不上任何选项键、被整段丢掉，导入后变成「待补答案」。
 */
class NormalizerAnswerTest {

    private val options = listOf(
        Option("A", "甲"), Option("B", "乙"), Option("C", "丙"), Option("D", "丁")
    )

    @Test
    fun multi_answer_letters_are_split_without_separators() {
        assertEquals("A,B,C", Normalizer.normalizeAnswer("ABC", QuestionType.MULTI, options))
        assertEquals("A,B,D", Normalizer.normalizeAnswer("ABD", QuestionType.MULTI, options))
        assertEquals("B,D", Normalizer.normalizeAnswer("bd", QuestionType.MULTI, options))
    }

    @Test
    fun multi_answer_with_separators_still_works() {
        assertEquals("A,C", Normalizer.normalizeAnswer("A,C", QuestionType.MULTI, options))
        assertEquals("A,B,C", Normalizer.normalizeAnswer("A、B、C", QuestionType.MULTI, options))
        assertEquals("A,C", Normalizer.normalizeAnswer("A C", QuestionType.MULTI, options))
    }

    @Test
    fun multi_answer_prefix_and_letter_text_forms() {
        assertEquals("B", Normalizer.normalizeAnswer("答案：B", QuestionType.SINGLE, options))
        assertEquals("A,C", Normalizer.normalizeAnswer("A.甲,C.丙", QuestionType.MULTI, options))
    }

    @Test
    fun single_answer_takes_first_key() {
        assertEquals("C", Normalizer.normalizeAnswer("C", QuestionType.SINGLE, options))
        // 连写 "AB" 对单选无意义：拆开后取第一个键，比存一个不存在的 "AB" 好
        assertEquals("A", Normalizer.normalizeAnswer("AB", QuestionType.SINGLE, options))
    }

    @Test
    fun unknown_tokens_pass_through_for_multi() {
        // 没有选项键可对时保持原文（ known.isEmpty() 分支），不静默丢答案
        assertEquals("对", Normalizer.normalizeAnswer("对", QuestionType.MULTI, emptyList()))
    }
}
