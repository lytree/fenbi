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
 * 「导入答案」的公共流程：读文件 → 解析 → 与本卷题目匹配 → 确认 → 写库。
 *
 * 答题页（交卷前补充）与成绩报告页（做完后补答案重判）共用同一套逻辑，
 * 避免两边各写一份匹配/提示文案而出现口径差异。
 */
object AnswerImportUi {

    /** 系统文件选择器的 MIME 集合，与题库导入保持一致 */
    val MIME = arrayOf(
        "application/json",
        "text/csv",
        "text/comma-separated-values",
        "text/plain",
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
                ?: "文件里没有可用的答案"
            error(activity, reason)
            return
        }

        val matched = AnswerMatcher.match(questions, sheet)
        if (matched.hits.isEmpty()) {
            error(
                activity,
                "识别到 ${sheet.entries.size} 条答案，但对不上本卷任何题目。\n\n" +
                        "答案文件格式：Excel / CSV 用「题号, 答案」两列，或带「题干」的答案表；" +
                        "TXT 用「1.A、2.B」逐行。"
            )
            return
        }

        val existing = questions.count { it.answer.isNotBlank() }
        val missingAfter = questions.size - matched.hits.size
        val message = buildString {
            append("文件：").append(fileName).append('\n')
            append("识别答案：").append(sheet.entries.size).append(" 条\n")
            append("匹配本题：").append(matched.hits.size).append('/').append(questions.size).append(" 题")
            if (existing > 0) append("（其中 ").append(existing).append(" 题原有答案将被覆盖）")
            if (missingAfter > 0) append("\n未匹配：").append(missingAfter).append(" 题，保持原样")
            matched.notes.take(3).forEach { append("\n· ").append(it) }
        }

        FbDialog.confirm(
            activity, message,
            okText = "写入答案",
            onOk = {
                val n = db.updateAnswers(
                    matched.hits.map { AnswerUpdate(it.question.id, it.answer, it.analysis) }
                )
                Toast.makeText(activity, "已补充 $n 题答案", Toast.LENGTH_SHORT).show()
                onDone(n)
            }
        )
    }

    private fun error(activity: AppCompatActivity, message: String) {
        com.lytree.qbank.ui.FbDialog.alert(
            context = activity,
            title = "答案导入失败",
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
