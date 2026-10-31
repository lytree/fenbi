package com.lytree.qbank

import com.lytree.qbank.data.db.QBankDb
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 导入组（标题）的取名规则：**一次导入 = 一个标题**。
 *
 * 组名来自卷子的来源说明（`导入自 xxx.xlsx`），旧库回填时全靠这一条把卷子归回各自的批次，
 * 取歪了就会把几份不同的卷混成一组，所以单拎出来钉住。
 */
class PaperSetTitleTest {

    @Test
    fun `去掉导入自前缀与扩展名`() {
        assertEquals("多套卷示例", QBankDb.setTitleOf("导入自 多套卷示例.xlsx"))
        assertEquals("2024真题", QBankDb.setTitleOf("2024真题.zip"))
        assertEquals("资料分析试卷包", QBankDb.setTitleOf("导入自 资料分析试卷包"))
    }

    @Test
    fun `没有来源的落到历史试卷`() {
        assertEquals("历史试卷", QBankDb.setTitleOf(""))
        assertEquals("历史试卷", QBankDb.setTitleOf("   "))
        assertEquals("历史试卷", QBankDb.setTitleOf("导入自 .xlsx"))
    }

    @Test
    fun `文件名里的点只去最后一段扩展名`() {
        assertEquals("2024.10.21 模考", QBankDb.setTitleOf("导入自 2024.10.21 模考.xlsx"))
    }
}
