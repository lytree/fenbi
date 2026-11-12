package com.lytree.qbank.ui.paper

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.AnswerResult
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivityPaperDetailBinding
import com.lytree.qbank.databinding.ItemQuestionBinding
import com.lytree.qbank.ui.ExamActivity
import com.lytree.qbank.ui.MaterialRenderer
import com.lytree.qbank.ui.applyNavBarBottomPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 试卷详情：查看这份卷的题目清单，并直接开练。
 *
 * 也接「整组作答」的虚拟卷（id 为负 = 导入组，见 [com.lytree.qbank.data.model.PaperRef]）：
 * 组记录的「看原题」跳进来时，列的是整组合起来的题目。
 *
 * 原本这页挂在「题库」下（题库详情），题库这一层取消后并到试卷上——
 * 题目清单本来就是这份卷的内容，没有第二个所有者。
 *
 * 另一个入口是成绩报告：点某道题直接跳到这里，滚动定位到那道题并默认展开
 * （材料 + 答案 + 解析），见 [EXTRA_FOCUS_QUESTION_ID] / [EXTRA_RECORD_ID]。
 */
class PaperDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAPER_ID = "paper_id"
        const val EXTRA_PAPER_NAME = "paper_name"
        /** 定位到哪道题（题目 id，0 = 不定位）——成绩报告点题跳转用 */
        const val EXTRA_FOCUS_QUESTION_ID = "focus_question_id"
        /** 带上这次作答的记录 id：定位题会多显示「你的答案 / 对错」 */
        const val EXTRA_RECORD_ID = "record_id"
    }

    private lateinit var binding: ActivityPaperDetailBinding
    private val adapter = QuestionAdapter()
    private var paperId = 0L
    private var paperName = "试卷"
    private var focusQuestionId = 0L
    private var recordId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaperDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        paperId = intent.getLongExtra(EXTRA_PAPER_ID, 0L)
        paperName = intent.getStringExtra(EXTRA_PAPER_NAME) ?: "试卷"
        focusQuestionId = intent.getLongExtra(EXTRA_FOCUS_QUESTION_ID, 0L)
        recordId = intent.getLongExtra(EXTRA_RECORD_ID, 0L)
        binding.titleBar.setTitle(paperName)
        binding.bottomBar.applyNavBarBottomPadding()

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnStart.setOnClickListener { startExam() }
        reload()
    }

    private fun startExam() {
        // 读卷放后台；回来在主线程校验后再开答题页，页面销毁后结果丢弃
        lifecycleScope.launch {
            val paper = withContext(Dispatchers.IO) { QBankDb.get(this@PaperDetailActivity).getPaperOrGroup(paperId) }
            if (isFinishing || isDestroyed) return@launch
            if (paper == null || paper.questions.isEmpty()) {
                Toast.makeText(this@PaperDetailActivity, "这份试卷没有题目", Toast.LENGTH_SHORT).show()
                return@launch
            }
            startActivity(
                Intent(this@PaperDetailActivity, ExamActivity::class.java).apply {
                    putExtra(ExamActivity.EXTRA_PAPER_ID, paper.id)
                    putExtra(ExamActivity.EXTRA_PAPER_NAME, paper.name)
                }
            )
        }
    }

    private fun reload() {
        // 题目清单可能是整卷几百题：查询放后台，回主线程再填列表
        lifecycleScope.launch {
            val db = QBankDb.get(this@PaperDetailActivity)
            val loaded = withContext(Dispatchers.IO) {
                val questions = db.getPaperOrGroup(paperId)?.questions ?: emptyList()
                // 从报告页跳进来才有记录：按题目 id 取出这次的作答与判定
                val mine = if (recordId > 0L) {
                    db.getRecord(recordId)?.results?.associateBy { it.question.id }.orEmpty()
                } else {
                    emptyMap()
                }
                questions to mine
            }
            if (isFinishing || isDestroyed) return@launch
            val questions = loaded.first
            adapter.submit(questions, loaded.second, focusQuestionId.takeIf { it > 0L })
            binding.empty.visibility = if (questions.isEmpty()) View.VISIBLE else View.GONE
            scrollToFocus(questions)
        }
    }

    /** 定位到成绩报告点开的那道题：等列表跑完一趟布局再滚，否则还没测量就滚不准 */
    private fun scrollToFocus(questions: List<Question>) {
        if (focusQuestionId <= 0L) return
        val index = questions.indexOfFirst { it.id == focusQuestionId }
        if (index < 0) return
        binding.list.post {
            (binding.list.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(index, 0)
        }
    }
}

/**
 * 题目清单适配器。
 *
 * 卡片可点：点一下展开这道题的完整资料（材料 + 解析），再点收起。
 * [focusId] 是从报告页跳过来时定位的那道题，首次绑定时默认展开；
 * [mine] 是这次记录的逐题结果，有值时多显示一行「你的答案 / 对错」。
 */
class QuestionAdapter : RecyclerView.Adapter<QuestionAdapter.VH>() {

    private val items = ArrayList<Question>()
    private var mine: Map<Long, AnswerResult> = emptyMap()

    /** 当前展开的题 id（同一时刻只展开一道，避免整页被材料撑满） */
    private var expandedId: Long? = null

    fun submit(list: List<Question>, mine: Map<Long, AnswerResult> = emptyMap(), focusId: Long? = null) {
        items.clear()
        items.addAll(list)
        this.mine = mine
        // 定位题默认展开：跳过来就是为了看这道题的资料，不该再让用户点一下
        expandedId = focusId?.takeIf { id -> list.any { it.id == id } }
        notifyDataSetChanged()
    }

    class VH(val binding: ItemQuestionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemQuestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val q = items[position]
        val b = holder.binding
        val ctx = holder.itemView.context
        val expanded = q.id == expandedId

        b.tvType.text = q.type.label
        b.tvMeta.text = buildString {
            if (q.source.isNotBlank()) append(q.source)
            if (q.knowledge.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(q.knowledge)
            }
        }
        b.tvStem.text = q.stem
        b.tvOptions.text = q.options.joinToString("\n") {
            "${it.key}. ${MaterialRenderer.optionSummary(it.text)}"
        }
        b.tvAnswer.text = if (q.answer.isBlank()) {
            "答案：待补答案"
        } else {
            "答案：${q.displayAnswer()}"
        }
        b.tvAnswer.setTextColor(
            ContextCompat.getColor(ctx, if (q.answer.isBlank()) R.color.partial else R.color.fb_success)
        )

        // 本次作答（只有从报告页跳过来才有记录）
        val result = mine[q.id]
        if (result == null) {
            b.tvMine.visibility = View.GONE
        } else {
            b.tvMine.visibility = View.VISIBLE
            b.tvMine.text = buildString {
                append("你的答案：${result.userAnswer.ifBlank { "未作答" }}")
                when {
                    result.pending -> Unit
                    result.correct -> append("　正确")
                    result.partial -> append("　部分得分")
                    result.userAnswer.isBlank() -> Unit
                    else -> append("　错误")
                }
            }
            b.tvMine.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    when {
                        result.pending || result.userAnswer.isBlank() -> R.color.fb_text_meta
                        result.correct -> R.color.right
                        result.partial -> R.color.partial
                        else -> R.color.wrong
                    }
                )
            )
        }

        // 材料：只在展开时渲染，收起时清掉（图片占内存，列表里几十道材料题一起画会卡）
        if (expanded && q.material.isNotBlank()) {
            b.materialBox.visibility = View.VISIBLE
            val tag = q.material.hashCode()
            if (b.materialContent.tag != tag) {
                b.materialContent.removeAllViews()
                MaterialRenderer.render(ctx, b.materialContent, q.material, night = false)
                b.materialContent.tag = tag
            }
        } else {
            b.materialBox.visibility = View.GONE
            if (b.materialContent.childCount > 0) {
                b.materialContent.removeAllViews()
                b.materialContent.tag = null
            }
        }

        // 解析
        if (expanded && q.analysis.isNotBlank()) {
            b.tvAnalysis.visibility = View.VISIBLE
            b.tvAnalysis.text = "解析：${q.analysis}"
        } else {
            b.tvAnalysis.visibility = View.GONE
        }

        b.tvToggle.text = ctx.getString(
            if (expanded) R.string.question_show_less else R.string.question_show_more
        )
        // chevron 默认朝右：收起 = 朝右，展开 = 朝下
        b.icToggle.rotation = if (expanded) 90f else 0f

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val oldId = expandedId
            expandedId = if (oldId == items[pos].id) null else items[pos].id
            oldId?.let { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                ?.let { notifyItemChanged(it) }
            notifyItemChanged(pos)
        }
    }
}
