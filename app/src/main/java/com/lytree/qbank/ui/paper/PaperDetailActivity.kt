package com.lytree.qbank.ui.paper

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivityPaperDetailBinding
import com.lytree.qbank.databinding.ItemQuestionBinding
import com.lytree.qbank.ui.ExamActivity
import com.lytree.qbank.ui.MaterialRenderer
import com.lytree.qbank.ui.applyNavBarBottomPadding

/**
 * 试卷详情：查看这份卷的题目清单，并直接开练。
 *
 * 原本这页挂在「题库」下（题库详情），题库这一层取消后并到试卷上——
 * 题目清单本来就是这份卷的内容，没有第二个所有者。
 */
class PaperDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAPER_ID = "paper_id"
        const val EXTRA_PAPER_NAME = "paper_name"
    }

    private lateinit var binding: ActivityPaperDetailBinding
    private val adapter = QuestionAdapter()
    private var paperId = 0L
    private var paperName = "试卷"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaperDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        paperId = intent.getLongExtra(EXTRA_PAPER_ID, 0L)
        paperName = intent.getStringExtra(EXTRA_PAPER_NAME) ?: "试卷"
        binding.titleBar.setTitle(paperName)
        binding.bottomBar.applyNavBarBottomPadding()

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnStart.setOnClickListener { startExam() }
        reload()
    }

    private fun startExam() {
        val paper = QBankDb.get(this).getPaper(paperId)
        if (paper == null || paper.questions.isEmpty()) {
            Toast.makeText(this, "这份试卷没有题目", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, ExamActivity::class.java).apply {
                putExtra(ExamActivity.EXTRA_PAPER_ID, paper.id)
                putExtra(ExamActivity.EXTRA_PAPER_NAME, paper.name)
            }
        )
    }

    private fun reload() {
        val list = QBankDb.get(this).listQuestions(paperId)
        adapter.submit(list)
        binding.empty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }
}

class QuestionAdapter : RecyclerView.Adapter<QuestionAdapter.VH>() {

    private val items = ArrayList<Question>()

    fun submit(list: List<Question>) { items.clear(); items.addAll(list); notifyDataSetChanged() }

    class VH(val binding: ItemQuestionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemQuestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val q = items[position]
        holder.binding.tvType.text = q.type.label
        holder.binding.tvMeta.text = buildString {
            if (q.source.isNotBlank()) append(q.source)
            if (q.knowledge.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(q.knowledge)
            }
        }
        holder.binding.tvStem.text = q.stem
        holder.binding.tvOptions.text = q.options.joinToString("\n") {
            "${it.key}. ${MaterialRenderer.optionSummary(it.text)}"
        }
        holder.binding.tvAnswer.text = if (q.answer.isBlank()) {
            "答案：待补答案"
        } else {
            "答案：${q.displayAnswer()}"
        }
    }
}
