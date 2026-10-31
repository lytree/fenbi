package com.lytree.qbank.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.db.AnswerUpdate
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.importer.AnswerSheetImporter
import com.lytree.qbank.data.importer.Normalizer
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivityAnswerEntryBinding
import com.lytree.qbank.databinding.ItemAnswerEntryBinding

/**
 * 补录答案：**不需要答案文件**，逐题把标准答案填进来，保存后立刻按当前记录的作答重判。
 *
 * 对齐真实场景：卷子先做完，答案才拿到手里（甚至是老师在黑板上念的），
 * 这时最省事的是在 App 里直接填，而不是先整理一份 Excel 再导入。
 *
 * 两种填法：
 * - 逐题输入框：适合只补几道题 / 边对边填；
 * - 右上角「批量粘贴」：把 `1.A 2.B`、一行一题、或纯一串 `A B C D` 贴进来，按题号 / 题序自动填。
 */
class AnswerEntryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAPER_ID = "paper_id"
        const val EXTRA_RECORD_ID = "record_id"
    }

    private lateinit var binding: ActivityAnswerEntryBinding
    private lateinit var db: QBankDb

    private val questions = ArrayList<Question>()
    /** 题干 id → 正在填的标准答案（含用户改动） */
    private val answers = HashMap<Long, String>()
    /** 题干 id → 这条记录里用户自己的作答，用于对照着填 */
    private val mine = HashMap<Long, String>()

    private var recordId = 0L
    private var adapter: AnswerEntryAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenAdapt.applyOrientation(this)
        binding = ActivityAnswerEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)

        val paperId = intent.getLongExtra(EXTRA_PAPER_ID, 0L)
        recordId = intent.getLongExtra(EXTRA_RECORD_ID, 0L)

        // 题目优先取试卷当前版本；试卷被删了才退回记录里的快照
        val paper = db.getPaper(paperId)
        val record = if (recordId > 0) db.getRecord(recordId) else null
        val source = paper?.questions?.takeIf { it.isNotEmpty() }
            ?: record?.results?.map { it.question }
            ?: emptyList()
        if (source.isEmpty()) {
            toast("没有可补录的题目")
            finish()
            return
        }
        questions.addAll(source)
        questions.forEach { q ->
            answers[q.id] = q.answer
            record?.results?.firstOrNull { it.question.id == q.id }?.let { mine[q.id] = it.userAnswer }
        }

        binding.titleBar.setTitle(getString(R.string.fill_answer_title))
        binding.titleBar.addTextAction(getString(R.string.fill_answer_paste)) { showPasteDialog() }
        binding.bottomBar.applyNavBarBottomPadding()

        adapter = AnswerEntryAdapter(questions, answers, mine) { refreshHint() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.btnSave.setOnClickListener { save() }

        refreshHint()
    }

    /** 顶部说明条：兼作「已填 N 题」的进度显示 */
    private fun refreshHint() {
        val filled = questions.count { answers[it.id].orEmpty().isNotBlank() }
        binding.tvHint.text = getString(R.string.fill_answer_hint, questions.size, filled)
    }

    // ---------------- 批量粘贴 ----------------

    private fun showPasteDialog() {
        FbDialog.input(
            context = this,
            title = getString(R.string.fill_answer_paste_title),
            hint = getString(R.string.fill_answer_paste_hint),
            multiline = true,
            okText = getString(R.string.fill_answer_paste_apply),
            onOk = { applyPasted(it) }
        )
    }

    /**
     * 把粘贴的一段答案填进列表：
     * 先按题号落位（`1.A` / `第1题 A` / `1.A 2.B`），没题号的按题序补到空位上。
     */
    private fun applyPasted(raw: String) {
        val entries = AnswerSheetImporter.parsePasted(raw)
        if (entries.isEmpty()) {
            toast(getString(R.string.fill_answer_paste_failed))
            return
        }
        var outOfRange = 0
        var filled = 0
        val used = HashSet<Int>()
        val rest = ArrayList<String>()

        for (e in entries) {
            val no = e.no
            val idx = if (no != null && no in 1..questions.size) no - 1 else -1
            if (idx >= 0 && idx !in used) {
                answers[questions[idx].id] = e.answer
                used.add(idx)
                filled++
            } else {
                if (no != null && idx < 0) outOfRange++
                rest.add(e.answer)
            }
        }
        if (rest.isNotEmpty()) {
            var k = 0
            for (i in questions.indices) {
                if (k >= rest.size) break
                if (i in used) continue
                answers[questions[i].id] = rest[k++]
                filled++
            }
        }

        adapter?.notifyDataSetChanged()
        refreshHint()
        toast(getString(R.string.fill_answer_paste_result, filled))
        if (outOfRange > 0) {
            toast(getString(R.string.fill_answer_out_of_range, outOfRange))
        }
    }

    // ---------------- 保存 ----------------

    private fun save() {
        val updates = ArrayList<AnswerUpdate>()
        for (q in questions) {
            val raw = answers[q.id].orEmpty().trim()
            val next = if (raw.isBlank()) "" else Normalizer.normalizeAnswer(raw, q.type, q.options)
            val old = if (q.answer.isBlank()) "" else Normalizer.normalizeAnswer(q.answer, q.type, q.options)
            if (next != old) updates.add(AnswerUpdate(q.id, next, ""))
        }
        if (updates.isEmpty()) {
            toast(getString(R.string.fill_answer_unchanged))
            return
        }
        val n = db.setAnswersManually(updates)
        // 已交卷的记录：按记录里的原作答重判，不需要重做
        if (recordId > 0 && db.rejudgeRecord(recordId) != null) {
            toast(getString(R.string.fill_answer_saved_graded, n))
        } else {
            toast(getString(R.string.fill_answer_saved, n))
        }
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

/**
 * 补录答案列表。输入框在 [onCreateViewHolder] 里只挂一次 TextWatcher，
 * 通过 bindingAdapterPosition 回写 map，避免复用行时监听器串题。
 */
class AnswerEntryAdapter(
    private val questions: List<Question>,
    private val answers: MutableMap<Long, String>,
    private val mine: Map<Long, String>,
    private val onEdited: () -> Unit
) : RecyclerView.Adapter<AnswerEntryAdapter.VH>() {

    class VH(val binding: ItemAnswerEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        /** 正在回填数据时为 true，避免把程序设置的值当成用户输入 */
        var bindingData = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val holder = VH(ItemAnswerEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        holder.binding.etAnswer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (holder.bindingData) return
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                answers[questions[pos].id] = s?.toString().orEmpty()
                onEdited()
            }
        })
        return holder
    }

    override fun getItemCount() = questions.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val q = questions[position]
        val ctx = holder.itemView.context
        holder.binding.tvIndex.text = "第 ${position + 1} 题"
        holder.binding.tvType.text = q.type.label
        holder.binding.tvStem.text = q.stem
        val my = mine[q.id].orEmpty()
        holder.binding.tvMine.text = if (my.isBlank()) {
            ctx.getString(R.string.fill_answer_mine_blank)
        } else {
            ctx.getString(R.string.fill_answer_mine, my)
        }

        holder.bindingData = true
        holder.binding.etAnswer.setText(answers[q.id].orEmpty())
        holder.binding.etAnswer.setSelection(holder.binding.etAnswer.text?.length ?: 0)
        holder.bindingData = false
    }
}
