package com.lytree.qbank.data.importer

import java.io.InputStream

/**
 * 纯文本题库解析。常见格式：
 *
 * ```
 * 1. 下列关于法的表述正确的是？
 * A. 法由国家制定
 * B. 法由社会公认
 * 答案：A
 * 解析：法由国家制定或认可。
 * 知识点：法的渊源
 * 难度：2
 *
 * 2. 第二题题干……
 * A. xxx
 * B. xxx
 * 答案：B
 * ```
 *
 * 空行分隔题目；`答案:`/`解析:`/`知识点:`/`难度:`/`分值:`/`材料:` 前缀识别。
 */
object TxtImporter {

    private val PREFIXES = mapOf(
        "答案" to Field.ANSWER,
        "正确答案" to Field.ANSWER,
        "参考答案" to Field.ANSWER,
        "标准答案" to Field.ANSWER,
        "解析" to Field.ANALYSIS,
        "答案解析" to Field.ANALYSIS,
        "说明" to Field.ANALYSIS,
        "知识点" to Field.KNOWLEDGE,
        "考点" to Field.KNOWLEDGE,
        "难度" to Field.DIFFICULTY,
        "分值" to Field.SCORE,
        "材料" to Field.MATERIAL,
        "题型" to Field.TYPE,
        "地区" to Field.REGION,
        "科目" to Field.SUBJECT,
        "来源" to Field.SOURCE,
        "年份" to Field.YEAR,
        "得分点" to Field.KEYWORDS
    )

    private val OPTION_RE = Regex("^\\s*([A-Ha-h])\\s*[.、．:：)）]\\s*(.*)$")

    /** 题号行：`12.` / `12、` / `12．` / `第12题` */
    private val QUESTION_HEAD = Regex("^(?:\\(?\\d+\\s*[.、．)）]|第\\s*\\d+\\s*题\\s*[.、．:：]?)\\s*.+$")

    fun parse(input: InputStream, issues: MutableList<ImportIssue>): ImportResult {
        val text = CsvImporter.decode(input.readBytes())
        // 先按「题号开头」切块，再把块内空行合并——
        // 否则材料段落里的空行会把一道题拆成两半
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = splitByQuestionNumber(normalized)

        if (blocks.isEmpty()) {
            issues.add(ImportIssue(0, ImportIssue.Level.ERROR, "TXT 内容为空"))
            return ImportResult(emptyList(), issues, "TXT", 0)
        }

        val raws = ArrayList<RawQuestion>()
        blocks.forEachIndexed { idx, block ->
            val raw = RawQuestion(idx + 1)
            val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            var stemDone = false
            // 材料是多行的，遇到选项行或带前缀的标记行才结束
            var inMaterial = false
            val materialBuf = StringBuilder()

            fun flushMaterial() {
                if (inMaterial) {
                    val text = materialBuf.toString().trim()
                    if (text.isNotEmpty()) raw.put(Field.MATERIAL, text)
                    materialBuf.clear()
                    inMaterial = false
                }
            }

            for (line in lines) {
                // 去掉题号前缀 "1. " "1、" "(1)"
                var content = line
                content = content.replace(Regex("^\\(?\\d+\\s*[.、．)）]\\s*"), "")
                    .replace(Regex("^第\\s*\\d+\\s*题\\s*[.、．:：]?\\s*"), "")

                val opt = OPTION_RE.find(content)
                if (opt != null && opt.groupValues[1].length == 1) {
                    flushMaterial()
                    raw.options[opt.groupValues[1].uppercase()] = opt.groupValues[2].trim()
                    continue
                }

                val prefixMatch = Regex("^([\\u4e00-\u9fa5A-Za-z]{1,6})\\s*[:：]\\s*(.*)$").find(content)
                if (prefixMatch != null) {
                    val key = prefixMatch.groupValues[1]
                    val field = PREFIXES[key]
                    if (field != null) {
                        flushMaterial()
                        if (field == Field.MATERIAL) {
                            // 「材料：」后面可能还有多行，进入材料模式直到遇到选项/标记行
                            inMaterial = true
                            val first = prefixMatch.groupValues[2]
                            if (first.isNotEmpty()) materialBuf.append(first).append('\n')
                            continue
                        }
                        raw.put(field, prefixMatch.groupValues[2])
                        continue
                    }
                }

                // 材料模式中，续行并入材料
                if (inMaterial) {
                    materialBuf.append(content).append('\n')
                    continue
                }

                if (!stemDone) {
                    raw.put(Field.STEM, content)
                    stemDone = true
                } else {
                    // 续行并入题干
                    raw.put(Field.STEM, raw.get(Field.STEM) + "\n" + content)
                }
            }
            flushMaterial()
            raws.add(raw)
        }
        return ImportResult(raws, issues, "TXT", blocks.size)
    }

    /**
     * 按题号切块。
     *
     * 行首形如 `12. ` / `12、` / `第12题 ` 的行视为新题开头，
     * 其余行（含空行）都并入当前题。这样材料段落里的空行不会把题目截断。
     */
    private fun splitByQuestionNumber(text: String): List<String> {
        val lines = text.split('\n')
        val blocks = ArrayList<StringBuilder>()
        var current: StringBuilder? = null

        for (line in lines) {
            val t = line.trim()
            val startsNew = t.isNotEmpty() && QUESTION_HEAD.matchEntire(t) != null
            if (startsNew) {
                current = StringBuilder()
                blocks.add(current)
            }
            if (current == null) {
                // 首个题号之前的内容，视为独立块（可能是无题号的题）
                current = StringBuilder()
                blocks.add(current)
            }
            current!!.append(line).append('\n')
        }

        return blocks
            .map { it.toString().trim() }
            .filter { it.isNotEmpty() }
    }
}
