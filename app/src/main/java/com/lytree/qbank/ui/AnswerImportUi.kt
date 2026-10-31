package com.lytree.qbank.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lytree.qbank.data.db.AnswerUpdate
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.importer.AnswerMatcher
import com.lytree.qbank.data.importer.AnswerSheetImporter
import com.lytree.qbank.data.importer.ImportIssue
import com.lytree.qbank.data.model.Question
import java.io.ByteArrayOutputStream

/**
 * 「导入答案 / 解析」的公共流程：读文件 → 解析 → 与本卷题目匹配 → 确认 → 写库。
 *
 * 答题页（交卷前补充）与成绩报告页（做完后补答案重判）共用同一套逻辑，
 * 避免两边各写一份匹配/提示文案而出现口径差异。
 *
 * 只有一种方式：**选文件，剩下的 App 自动判断**——
 * - 文件里有答案列（`题号,答案[,解析]`）→ 按答案导入，带了解析的顺带写入；
 * - 文件只有解析没有答案（`题号,解析`）→ 按解析导入，**只更新解析、答案一个字不动**。
 * 用户不用先想清楚手里的文件是「答案」还是「解析」，确认弹窗里会写明这次导入什么。
 */
object AnswerImportUi {

    /** 系统文件选择器的 MIME 集合：只收 Excel */
    val MIME = arrayOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/octet-stream"
    )

    /**
     * @param onDone 写入成功后回调，参数为实际写入的题数
     */
    fun handle(
        activity: AppCompatActivity,
        db: QBankDb,
        questions: List<Question>,
        uri: Uri,
        onDone: (Int) -> Unit
    ) {
        val bytes = try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                val bos = ByteArrayOutputStream()
                input.copyTo(bos)
                bos.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
        if (bytes == null) {
            error(activity, "无法读取所选文件")
            return
        }

        val fileName = displayName(activity, uri)
        val sheet = AnswerSheetImporter.parse(fileName, bytes)
        if (sheet.entries.isEmpty()) {
            val reason = sheet.issues.firstOrNull { it.level == ImportIssue.Level.ERROR }?.message
                ?: "文件里没有可用的答案或解析"
            error(activity, reason)
            return
        }

        // 答案列整列缺失（所有条目都没有答案）→ 按解析文件导入
        val analysisOnly = sheet.analysisOnly
        val what = if (analysisOnly) "解析" else "答案"
        // 解析文件只留带解析的条目（理论上不会有答案，过滤只是兜底）
        val entries = if (analysisOnly) {
            sheet.entries.filter { it.analysis.isNotBlank() }
        } else {
            sheet.entries
        }
        if (entries.isEmpty()) {
            error(
                activity,
                "文件里没有可用的$what。\n\n" +
                        if (analysisOnly) "解析文件请用 Excel（.xlsx）：「题号, 解析」两列。"
                        else "答案文件请用 Excel（.xlsx）：「题号, 答案」两列。",
                title = "${what}导入失败"
            )
            return
        }
        val trimmed = sheet.copy(entries = entries)

        val matched = AnswerMatcher.match(questions, trimmed, what)
        if (matched.hits.isEmpty()) {
            val fmtHint = if (analysisOnly) {
                "解析文件请用 Excel（.xlsx）：「题号, 解析」两列。"
            } else {
                "答案文件请用 Excel（.xlsx）：「题号, 答案」两列，或带「题干」的完整答案表。"
            }
            error(
                activity,
                "识别到 ${entries.size} 条$what，但对不上本卷任何题目。\n\n$fmtHint",
                title = "${what}导入失败"
            )
            return
        }

        val existing = if (analysisOnly) {
            questions.count { it.analysis.isNotBlank() }
        } else {
            questions.count { it.answer.isNotBlank() }
        }
        val missingAfter = questions.size - matched.hits.size
        val message = buildString {
            append("文件：").append(fileName).append('\n')
            if (analysisOnly) {
                append("本次只导入解析，答案保持不变\n")
            }
            append("识别$what：").append(entries.size).append(" 条\n")
            append("匹配本题：").append(matched.hits.size).append('/').append(questions.size).append(" 题")
            if (existing > 0) append("（其中 ").append(existing).append(" 题原有${what}将被覆盖）")
            if (missingAfter > 0) append("\n未匹配：").append(missingAfter).append(" 题，保持原样")
            matched.notes.take(3).forEach { append("\n· ").append(it) }
        }

        FbDialog.confirm(
            activity, message,
            okText = "写入$what",
            onOk = {
                val updates = matched.hits.map { hit ->
                    if (analysisOnly) {
                        AnswerUpdate(hit.question.id, "", hit.analysis)
                    } else {
                        AnswerUpdate(hit.question.id, hit.answer, hit.analysis)
                    }
                }
                val n = db.updateAnswers(updates)
                Toast.makeText(activity, "已补充 $n 题$what", Toast.LENGTH_SHORT).show()
                onDone(n)
            }
        )
    }

    private fun error(activity: AppCompatActivity, message: String, title: String = "答案导入失败") {
        com.lytree.qbank.ui.FbDialog.alert(
            context = activity,
            title = title,
            message = message
        )
    }

    private fun displayName(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment
        } ?: (uri.lastPathSegment ?: "答案文件")
    } catch (_: Exception) {
        uri.lastPathSegment ?: "答案文件"
    }
}
