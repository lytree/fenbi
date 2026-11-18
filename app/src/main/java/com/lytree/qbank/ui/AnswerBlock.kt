package com.lytree.qbank.ui

import android.content.res.ColorStateList
import android.content.res.Resources
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.lytree.qbank.R
import com.lytree.qbank.data.model.AnswerResult
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ViewFbAnalysisCardBinding
import com.lytree.qbank.databinding.ViewFbAnswerBlockBinding
import com.lytree.qbank.databinding.ViewFbAnswerSummaryBinding
import com.lytree.qbank.engine.Judge
import com.lytree.qbank.engine.Policy
import com.lytree.qbank.ui.record.RecordAdapter

/**
 * 解析页统计三项的数据（对齐参考应用「答题时间 / 正确率 / 易错项」——
 * 那边是全站口径，这边用本机数据聚合：用时来自 `record_question_times`，
 * 正确率 / 易错项来自全库 `record_answers` 按题聚合）。
 *
 * 没有数据的项显示「--」；整项没数据时统计行整行不出现。
 */
data class QuestionStats(
    /** 本次记录里这道题的累计秒数（没计过时为 null） */
    val seconds: Int? = null,
    /** 历史作答数 / 答对数（全库记录聚合；没作答过为 0） */
    val answered: Int = 0,
    val right: Int = 0,
    /** 历史上答错最多的选项（如 "C"；没答错过为 null） */
    val commonWrong: String? = null
)

/**
 * 作答小结 + 解析的编排层：判一次分，然后把结果分给两张卡。
 *
 * 三块内容各有一份布局和一份绑定：
 * - [AnswerSummary] → `view_fb_answer_summary.xml`（浅灰卡：正确答案 / 你的答案）
 * - 统计三项 → `statsRow`（答题时间 / 历史正确率 / 易错项，有数据才出现）
 * - [AnalysisCard]  → `view_fb_analysis_card.xml`（白底描边卡：可收起的解析）
 *
 * 之所以拆成几张卡：答案和解析本来就不同命——答案永远有（含「未作答」），解析常常没有；
 * 挤在一张卡里，没解析的题会剩一截空卡底，「解析」这个标题也没了着落。
 *
 * 答题页、解析页（[QuestionReviewActivity]）、弹出材料的左右分屏右栏都调这里，
 * 三处共用同一套口径（以前是三处各拼一段 buildString）。
 */
object AnswerBlock {

    fun bind(
        b: ViewFbAnswerBlockBinding,
        q: Question,
        userAnswer: String,
        policy: Policy = Policy.DEFAULT,
        /** 夜间：只换底色与字色，版式一律不动 */
        night: Boolean = false,
        /** 统计三项（解析页传，答题页不传）；传了但全空时统计行也自动收起 */
        stats: QuestionStats? = null
    ) {
        val r = Judge.judge(q, userAnswer, policy)
        AnswerSummary.bind(b.summary, q, userAnswer, r, night)
        bindStats(b, stats)
        AnalysisCard.bind(b.analysis, analysisText(b.root.resources, q, r), night)
        b.root.visibility = View.VISIBLE
    }

    /** 统计三项：三格各管各的数据源，缺哪项补「--」；三项全缺就整行收起 */
    private fun bindStats(b: ViewFbAnswerBlockBinding, stats: QuestionStats?) {
        val row = b.statsRow
        if (stats == null || (stats.seconds == null && stats.answered <= 0 && stats.commonWrong == null)) {
            row.visibility = View.GONE
            return
        }
        val ctx = row.context

        // 答题时间（蓝）：本次记录里的累计秒数；没计过时给「--」
        b.tvStatTime.text = stats.seconds?.let { RecordAdapter.fmtDuration(it) } ?: "--"
        // 历史正确率（绿）：全库这道题的作答聚合；没作答过给「--」
        b.tvStatRate.text = if (stats.answered > 0) {
            "${stats.right * 100 / stats.answered}%"
        } else {
            "--"
        }
        // 易错项（红）：历史上答错最多的选项；没答错过给「--」
        b.tvStatTrap.text = stats.commonWrong ?: "--"
        b.tvStatTime.setTextColor(ContextCompat.getColor(ctx, R.color.fb_primary))
        b.tvStatRate.setTextColor(ContextCompat.getColor(ctx, R.color.fb_success))
        b.tvStatTrap.setTextColor(ContextCompat.getColor(ctx, R.color.fb_danger))
        row.visibility = View.VISIBLE
    }

    /** 未交卷 / 未到判分时机：整块收起（答题页交卷前就是这个状态） */
    fun hide(b: ViewFbAnswerBlockBinding) {
        b.root.visibility = View.GONE
    }

    /** 解析正文：待补答案时换成补录说明——题没有标准答案，也就无解析可看 */
    private fun analysisText(res: Resources, q: Question, r: AnswerResult): String =
        if (r.pending) {
            res.getString(R.string.pending_answer_empty) + "\n" +
                res.getString(R.string.pending_answer_hint)
        } else {
            q.analysis.trim()
        }
}

/**
 * 作答小结卡：左「正确答案」右「你的答案」，答案值 18sp 加粗、按对错着色
 * （绿 = 对 / 橙 = 部分得分 / 红 = 错 / 灰 = 未作答）。
 *
 * 待补答案时左栏整栏收起——不留一个空的绿色答案；部分得分才在卡底补一行具体分数。
 */
object AnswerSummary {

    fun bind(
        b: ViewFbAnswerSummaryBinding,
        q: Question,
        userAnswer: String,
        r: AnswerResult,
        night: Boolean = false
    ) {
        val ctx = b.root.context
        // 标签色与「未作答 / 待补答案」的答案色共用一个色号：下面分色时只能传**资源 id**，
        // 传已解析的颜色值会走进 Resources.getColor(int) 抛 NotFoundException
        val metaRes = if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
        val metaColor = ContextCompat.getColor(ctx, metaRes)
        b.root.setBackgroundResource(
            if (night) R.drawable.bg_fb_note_night else R.drawable.bg_fb_note
        )
        b.tvCorrectLabel.setTextColor(metaColor)
        b.tvMineLabel.setTextColor(metaColor)

        // 左栏：正确答案
        b.correctGroup.visibility = if (r.pending) View.GONE else View.VISIBLE
        b.tvCorrectValue.text = q.displayAnswer()
        b.tvCorrectValue.setTextColor(ContextCompat.getColor(ctx, R.color.fb_success))

        // 右栏：你的答案（颜色直接说明对错）
        b.tvMineValue.text = userAnswer.ifBlank { ctx.getString(R.string.answer_unanswered) }
        b.tvMineValue.setTextColor(
            ContextCompat.getColor(
                ctx,
                when {
                    userAnswer.isBlank() -> metaRes
                    r.pending -> metaRes
                    r.correct -> R.color.fb_success
                    r.partial -> R.color.fb_warning
                    else -> R.color.fb_danger
                }
            )
        )

        // 正确 / 错误由颜色说清楚了，只有部分得分需要把分数写出来
        if (r.partial) {
            b.tvResult.visibility = View.VISIBLE
            b.tvResult.text = "部分得分 " +
                "${QuestionCardBinder.fmtScore(r.earned)}/${QuestionCardBinder.fmtScore(r.full)}"
        } else {
            b.tvResult.visibility = View.GONE
        }

        b.root.visibility = View.VISIBLE
    }
}

/**
 * 解析卡：双圆图标 + 「解析」标题 + 右侧收起箭头，正文 15sp、行距放宽。
 *
 * 点标题行收起 / 展开（箭头跟着翻转）；没有解析内容时整张卡不出现，
 * 免得只挂一个空标题。每次重绑都回到展开态——卡片会被复用，收起状态不必跨题记住。
 */
object AnalysisCard {

    fun bind(b: ViewFbAnalysisCardBinding, text: String, night: Boolean = false) {
        if (text.isBlank()) {
            hide(b)
            return
        }
        val ctx = b.root.context
        b.root.setBackgroundResource(
            if (night) R.drawable.bg_fb_note_night else R.drawable.bg_fb_card_line
        )
        b.tvAnalysisTitle.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_text else R.color.fb_text_title
            )
        )
        b.tvAnalysis.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_text_body else R.color.fb_text_body
            )
        )
        ImageViewCompat.setImageTintList(
            b.icAnalysisChevron,
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    ctx, if (night) R.color.fb_night_text_meta else R.color.fb_text_hint
                )
            )
        )

        b.tvAnalysis.text = text
        applyExpanded(b, expanded = true)
        b.btnAnalysisHeader.setOnClickListener {
            applyExpanded(b, expanded = b.tvAnalysis.visibility != View.VISIBLE)
        }
        b.root.visibility = View.VISIBLE
    }

    fun hide(b: ViewFbAnalysisCardBinding) {
        b.root.visibility = View.GONE
        b.btnAnalysisHeader.setOnClickListener(null)
    }

    private fun applyExpanded(b: ViewFbAnalysisCardBinding, expanded: Boolean) {
        b.tvAnalysis.visibility = if (expanded) View.VISIBLE else View.GONE
        // 箭头朝下 = 可展开，朝上 = 可收起（与参考截图一致）
        b.icAnalysisChevron.rotation = if (expanded) 180f else 0f
    }
}
