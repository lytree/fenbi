package com.lytree.qbank

import com.lytree.qbank.data.db.QBankDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作答进度串的编解码：旧格式 `id,value;` 会把含 `;` 的主观题答案截断，
 * 新格式改用不可见字符 US 分隔条目。这里钉住两件事：
 *
 * 1. 任意用户输入（`;`、换行、逗号、空值、超长文本、US 本身、反斜杠）都能无损往返；
 * 2. 升级前落库的旧串仍按旧规则读出来，不会把历史作答读成错乱。
 */
class ProgressCodecTest {

    private val sep = QBankDb.PROGRESS_UNIT_SEP

    @Test
    fun `分号换行逗号都能原样往返`() {
        val answers = mapOf(
            1L to "第一点;第二点;第三点",
            2L to "第一行\n第二行\r\n第三行",
            3L to "A,B,C",
            4L to ""
        )
        val roundtrip = QBankDb.decodeProgressAnswers(QBankDb.encodeProgressAnswers(answers))
        assertEquals(answers, roundtrip)
    }

    @Test
    fun `只有一条作答时分号也不会被截断`() {
        // 关键回归：新串只有一条记录时内部没有 US，靠串首的版本标记才能和旧格式区分
        val answers = mapOf(7L to "甲;乙")
        val raw = QBankDb.encodeProgressAnswers(answers)
        assertTrue("新格式串首要有 US 版本标记", raw.startsWith(sep))
        assertEquals(answers, QBankDb.decodeProgressAnswers(raw))
    }

    @Test
    fun `US 与反斜杠也能无损往返`() {
        val answers = mapOf(
            11L to "含${sep}分隔符的答案",
            12L to "反斜杠\\与\\u001F字样",
            13L to "\\${sep}\\"
        )
        assertEquals(answers, QBankDb.decodeProgressAnswers(QBankDb.encodeProgressAnswers(answers)))
    }

    @Test
    fun `空值与超长文本往返`() {
        val long = buildString { repeat(20_000) { append('x') } } + ";结尾"
        val answers = mapOf(21L to "", 22L to long)
        assertEquals(answers, QBankDb.decodeProgressAnswers(QBankDb.encodeProgressAnswers(answers)))
    }

    @Test
    fun `空作答编码为空串并解出空表`() {
        val raw = QBankDb.encodeProgressAnswers(emptyMap())
        assertEquals("", raw)
        assertTrue(QBankDb.decodeProgressAnswers(raw).isEmpty())
        assertTrue(QBankDb.decodeProgressAnswers("").isEmpty())
    }

    @Test
    fun `旧格式按旧规则读出`() {
        // 旧串：分号分隔条目，键与值用第一个逗号切开
        val legacy = "1,A;2,B,C;3,第一行\n第二行"
        val decoded = QBankDb.decodeProgressAnswers(legacy)
        assertEquals("A", decoded[1L])
        assertEquals("值里的逗号在旧格式里也是原样保留的", "B,C", decoded[2L])
        assertEquals("第一行\n第二行", decoded[3L])

        // 旧格式里含 `;` 的值本来就会被截断：升级后保持同样的读法，不做二次改写
        val truncated = QBankDb.decodeProgressAnswers("5,甲;乙")
        assertEquals("甲", truncated[5L])
        assertEquals("多出来的半句不产生新的键", 1, truncated.size)
    }

    @Test
    fun `旧数据解出后再按新格式落库不丢内容`() {
        val legacy = "1,A;2,B,C;3,含换行\n的值"
        val decoded = QBankDb.decodeProgressAnswers(legacy)
        val reencoded = QBankDb.encodeProgressAnswers(decoded)
        assertEquals(decoded, QBankDb.decodeProgressAnswers(reencoded))
    }

    @Test
    fun `空串片段的旧格式不产生空键`() {
        val decoded = QBankDb.decodeProgressAnswers(";1,A;;2,B;")
        assertEquals(2, decoded.size)
        assertEquals("A", decoded[1L])
        assertEquals("B", decoded[2L])
    }
}
