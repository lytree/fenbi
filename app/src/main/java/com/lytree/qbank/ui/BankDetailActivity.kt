package com.lytree.qbank.ui

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
import com.lytree.qbank.databinding.ActivityBankDetailBinding
import com.lytree.qbank.databinding.ItemQuestionBinding

/**
 * 题库详情：查看题目列表，并可一键组卷开练。
 */
class BankDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BANK_ID = "bank_id"
        const val EXTRA_BANK_NAME = "bank_name"
    }

    private lateinit var binding: ActivityBankDetailBinding
    private val adapter = QuestionAdapter()
    private var bankId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBankDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bankId = intent.getLongExtra(EXTRA_BANK_ID, 0L)
        val bankName = intent.getStringExtra(EXTRA_BANK_NAME) ?: "题库"
        binding.titleBar.setTitle(bankName)
        binding.bottomBar.applyNavBarBottomPadding()

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnCompose.setOnClickListener {
            val questions = QBankDb.get(this).listQuestions(bankId)
            if (questions.isEmpty()) {
                Toast.makeText(this, "题库为空", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            val pid = QBankDb.get(this).insertPaper(
                "$bankName 全量练习", bankId, 0, "全量练习", questions
            )
            startActivity(
                Intent(this, ExamActivity::class.java).apply {
                    putExtra(ExamActivity.EXTRA_PAPER_ID, pid)
                }
            )
        }
        reload()
    }

    private fun reload() {
        val list = QBankDb.get(this).listQuestions(bankId)
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
        holder.binding.tvOptions.text = q.options.joinToString("\n") { "${it.key}. ${it.text}" }
        holder.binding.tvAnswer.text = "答案：${q.displayAnswer()}"
    }
}
