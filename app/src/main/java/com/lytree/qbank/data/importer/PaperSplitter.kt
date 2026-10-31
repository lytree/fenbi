package com.lytree.qbank.data.importer

/**
 * 一份 Excel → 多份试卷的拆分。
 *
 * **一个工作表就是一份试卷**（sheet 名即卷名），这是主规则：
 * 老师发来的往往是「一本卷子」，几十套题按套分表塞在同一个 xlsx 里。
 *
 * 额外的「试卷」列（`试卷名` / `卷名` / `paper` 都认）只在一个工作表**内部**细分：
 * 一张表里排了几套题、又懒得拆表时，用这一列把它们分开。它不会跨表合并——
 * 两张表就是两份卷，哪怕卷名写得一样（同名会在卷名后补上表名以示区分）。
 *
 * 认不出来就当单卷：**宁可少拆一卷，也不能把两套题的答案混到一卷里**。
 */
object PaperSplitter {

    /** 一张工作表解析出来的行（尚未规范化） */
    data class SheetQuestions(val sheetName: String, val raws: List<RawQuestion>)

    /** 拆分结果：一份待建卷的原始行 */
    data class Block(
        val name: String,
        /** 卷名从哪来的，导入页用它解释拆分依据；空串表示「整个文件就是这一卷」 */
        val origin: String,
        val raws: List<RawQuestion>
    )

    /** 默认工作表名（Sheet1 / 工作表1）当卷名没有意义，得回落到文件名 */
    private val DEFAULT_SHEET = Regex("^(sheet|工作表|表)\\s*\\d*$", RegexOption.IGNORE_CASE)

    /** 卷名上限：太长的名字在列表里会撑破一行 */
    private const val MAX_NAME = 60

    fun split(sheets: List<SheetQuestions>, fallbackName: String): List<Block> {
        val usable = sheets.filter { it.raws.isNotEmpty() }
        if (usable.isEmpty()) return emptyList()

        val multiSheet = usable.size > 1
        val out = ArrayList<Block>()
        val used = HashSet<String>()

        usable.forEachIndexed { index, sheet ->
            val sheetPapers = splitSheet(sheet, index, fallbackName, multiSheet)
            sheetPapers.forEach { block ->
                // 两份卷同名会让「覆盖导入」互相吃掉对方，这里先改出唯一的名字
                val unique = uniqueName(block.name, sheet.sheetName, used)
                used.add(unique)
                out.add(block.copy(name = unique))
            }
        }
        return out
    }

    /**
     * 拆一张工作表：表里有「试卷」列且填了值就按列分组，否则整张表一卷。
     *
     * 多表时卷名取工作表名；单表时取文件名——「文件叫 2023国考，卷子还叫 Sheet1」很难认。
     */
    private fun splitSheet(
        sheet: SheetQuestions,
        index: Int,
        fallbackName: String,
        multiSheet: Boolean
    ): List<Block> {
        val sheetName = sheetNameOf(sheet.sheetName, fallbackName, index)
        val singleName = if (multiSheet) sheetName else fallbackName.ifBlank { "我的试卷" }
        val singleOrigin = if (multiSheet) "工作表「${sheet.sheetName}」" else ""

        if (sheet.raws.none { it.get(Field.PAPER).isNotEmpty() }) {
            return listOf(Block(singleName, singleOrigin, sheet.raws))
        }

        // 「试卷」列：空白行沿用上一份卷名——合并单元格只在首行留值，用户也很少逐行填
        val groups = LinkedHashMap<String, MutableList<RawQuestion>>()
        var current = ""
        sheet.raws.forEach { raw ->
            val v = raw.get(Field.PAPER)
            if (v.isNotEmpty()) current = v
            val name = current.ifEmpty { singleName }
            groups.getOrPut(name) { ArrayList() } += raw
        }
        if (groups.size == 1) {
            // 列只写了一个值：跟整表一卷没区别，卷名仍按单卷口径取
            val only = groups.values.first()
            return listOf(Block(singleName, singleOrigin, only))
        }
        val origin = if (multiSheet) "工作表「${sheet.sheetName}」的「试卷」列" else "「试卷」列"
        return groups.map { (name, raws) -> Block(name, origin, raws) }
    }

    private fun sheetNameOf(sheetName: String, fallbackName: String, index: Int): String {
        val n = sheetName.trim().take(MAX_NAME)
        if (n.isNotEmpty() && !DEFAULT_SHEET.matches(n)) return n
        return if (fallbackName.isNotBlank()) "$fallbackName-${index + 1}" else "试卷${index + 1}"
    }

    /** 重名时先用工作表名区分，还重就加序号 */
    private fun uniqueName(name: String, sheetName: String, used: Set<String>): String {
        val base = name.trim().ifEmpty { "未命名试卷" }.take(MAX_NAME)
        if (base !in used) return base
        val withSheet = "$base（${sheetName.trim().take(20)}）"
        if (withSheet !in used) return withSheet
        var n = 2
        while ("$withSheet-$n" in used) n++
        return "$withSheet-$n"
    }
}
