package com.lytree.qbank.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivitySplitExamBinding
import com.lytree.qbank.databinding.ItemOptionBinding
import com.lytree.qbank.databinding.ItemSplitQuestionBinding
import com.lytree.qbank.engine.Judge
import java.util.Locale

/**
 * 资料分析分屏页。
 *
 * 对应参考应用的「资料分析左右分屏」：左侧是材料（表格渲染成真表格），右侧是这一组材料
 * 对应的若干道题。资料分析的特点是「一道材料带 3~5 问」，逐题翻页时材料会反复出现，
 * 分屏能让材料始终停在左边，做题时可以直接对着数据看。
 *
 * 进入方式：答题页「更多 → 开启左右分屏」，或资料分析题组的入口。
 */
class SplitExamActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAPER_ID = "paper_id"
        const val EXTRA_PAPER_NAME = "paper_name"
        /** 只显示这组题（同一份材料）；为空则显示整卷 */
        const val EXTRA_GROUP_KEY = "group_key"
        private const val REQ_CARD = 1001
    }

    private lateinit var binding: ActivitySplitExamBinding
    private lateinit var db: QBankDb

    private var questions: List<Question> = emptyList()
    private val answers = HashMap<Long, String>()
    private val marked = HashSet<Long>()
    private var startedAt = 0L
    private var submitted = false
    private var revealAnswers = false
    private var inkMode = false
    private var currentIndex = 0
    private lateinit var inkToolbar: InkToolbarController
    private lateinit var tvIndex: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplitExamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)

        val paperId = intent.getLongExtra(EXTRA_PAPER_ID, 0L)
        val paper = db.getPaper(paperId)
        if (paper == null || paper.questions.isEmpty()) {
            android.widget.Toast.makeText(this, "试卷不存在或为空", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 按材料分组：同一份材料的题放一起
        val groupKey = intent.getStringExtra(EXTRA_GROUP_KEY)
        questions = if (groupKey.isNullOrBlank()) {
            paper.questions
        } else {
            paper.questions.filter { it.material.trim() == groupKey.trim() }
                .ifEmpty { paper.questions }
        }

        startedAt = System.currentTimeMillis()
        binding.titleBar.showDivider(false)
        binding.titleBar.setTitle(intent.getStringExtra(EXTRA_PAPER_NAME) ?: paper.name)
        binding.titleBar.onBack = { confirmExit() }
        tvIndex = binding.titleBar.addTextAction("") { }
        binding.bottomBar.applyNavBarBottomPadding()

        binding.btnCard.setOnClickListener { openAnswerCard() }
        binding.btnSubmit.setOnClickListener { confirmSubmit() }
        binding.btnInk.setOnClickListener { toggleInk() }
        binding.btnInkUndo.setOnClickListener { binding.inkOverlay.undo() }
        binding.btnInkClear.setOnClickListener { binding.inkOverlay.clear() }

        inkToolbar = InkToolbarController(
            inkOverlay = binding.inkOverlay,
            inkBar = binding.inkBar,
            btnInkFinger = binding.btnInkFinger,
            btnInkDone = binding.btnInkDone,
            styleInkButton = { on ->
                binding.btnInk.setBackgroundResource(
                    if (on) R.drawable.bg_fb_mark_on else R.drawable.bg_fb_action
                )
            }
        )
        inkToolbar.onModeChanged = { on ->
            inkMode = on
            if (on) binding.inkOverlay.bindQuestion(questions.getOrNull(currentIndex)?.id ?: 0L)
        }

        renderMaterial()
        renderQuestions()
        updateHeader()
    }

    // ---------------- 左栏：材料 ----------------

    private fun renderMaterial() {
        val material = questions.firstOrNull { it.material.isNotBlank() }?.material ?: ""
        if (material.isBlank()) {
            binding.materialContent.removeAllViews()
            val tip = TextView(this)
            tip.text = "本组题目无材料"
            tip.textSize = 13f
            tip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.materialContent.addView(tip)
            return
        }
        MaterialRenderer.render(this, binding.materialContent, material)
    }

    // ---------------- 右栏：题目 ----------------

    private fun renderQuestions() {
        binding.questionList.removeAllViews()
        val inflater = LayoutInflater.from(this)

        questions.forEachIndexed { i, q ->
            val card = ItemSplitQuestionBinding.inflate(inflater, binding.questionList, false)
            card.tvNum.text = "${i + 1}."
            card.tvType.text = q.type.label
            card.tvStem.text = q.stem

            val multi = q.type == com.lytree.qbank.data.model.QuestionType.MULTI ||
                q.type == com.lytree.qbank.data.model.QuestionType.UNCERTAIN
            val selected = (answers[q.id] ?: "").split(',', '，', ' ')
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .toMutableSet()

            q.options.forEach { opt ->
                val item = ItemOptionBinding.inflate(inflater, card.optionsBox, false)
                item.tvKey.text = opt.key
                item.tvText.text = opt.text
                val isSel = selected.contains(opt.key.uppercase())
                QuestionListAdapter.applyCircleState(item, isSel)
                item.root.setOnClickListener {
                    if (multi) {
                        if (selected.contains(opt.key.uppercase())) selected.remove(opt.key.uppercase())
                        else selected.add(opt.key.uppercase())
                    } else {
                        selected.clear()
                        selected.add(opt.key.uppercase())
                    }
                    answers[q.id] = selected.sorted().joinToString(",")
                    currentIndex = i
                    updateHeader()
                    renderQuestions()
                    binding.inkOverlay.bindQuestion(q.id)
                }
                card.optionsBox.addView(item.root)
            }

            if (revealAnswers) {
                card.tvAnalysis.visibility = android.view.View.VISIBLE
                val userAns = answers[q.id] ?: ""
                val r = Judge.judge(q, userAns, db.getPolicy())
                card.tvAnalysis.text = buildString {
                    append("正确答案：${q.displayAnswer()}\n")
                    append("你的答案：${userAns.ifBlank { "未作答" }}\n")
                    append("结果：${when { r.correct -> "正确"; r.partial -> "部分得分"; else -> "错误" }}\n")
                    if (q.analysis.isNotBlank()) append("解析：${q.analysis}")
                }
            }

            binding.questionList.addView(card.root)
        }
    }

    private fun updateHeader() {
        tvIndex.text = getString(R.string.question_index, currentIndex + 1, questions.size)
    }

    // ---------------- 手写 ----------------

    private fun toggleInk() {
        inkToolbar.toggle()
    }

    // ---------------- 答题卡 ----------------

    private fun openAnswerCard() {
        val answeredPos = questions.indices.filter { (answers[questions[it].id] ?: "").isNotBlank() }
        val markedPos = questions.indices.filter { questions[it].id in marked }
        startActivityForResult(
            Intent(this, AnswerCardActivity::class.java).apply {
                putExtra(AnswerCardActivity.EXTRA_COUNT, questions.size)
                putExtra(AnswerCardActivity.EXTRA_ANSWERED, answeredPos.toIntArray())
                putExtra(AnswerCardActivity.EXTRA_MARKED, markedPos.toIntArray())
            },
            REQ_CARD
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CARD && resultCode == RESULT_OK && data != null) {
            val jump = data.getIntExtra(AnswerCardActivity.EXTRA_JUMP, -1)
            if (jump in questions.indices) {
                currentIndex = jump
                updateHeader()
                binding.inkOverlay.bindQuestion(questions[jump].id)
                val card = binding.questionList.getChildAt(jump)
                card?.let { binding.rightPane.smoothScrollTo(0, it.top) }
            }
        }
    }

    // ---------------- 交卷 ----------------

    private fun confirmExit() {
        if (submitted) { finish(); return }
        AlertDialog.Builder(this)
            .setMessage("你还有题目未做完，确定退出吗？")
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("继续答题", null)
            .show()
    }

    private fun confirmSubmit() {
        val unanswered = questions.count { (answers[it.id] ?: "").isBlank() }
        val msg = if (unanswered > 0) {
            "你还有 $unanswered 道题目未作答，确定交卷吗？"
        } else {
            "确定交卷并查看结果吗？"
        }
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ -> submit() }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun submit() {
        if (submitted) return
        submitted = true
        revealAnswers = true

        val policy = db.getPolicy()
        val results = questions.map { Judge.judge(it, answers[it.id] ?: "", policy) }
        val summary = Judge.summarize(results)
        val now = System.currentTimeMillis()

        val record = ExamRecord(
            paperId = intent.getLongExtra(EXTRA_PAPER_ID, 0L),
            name = "分屏练习记录",
            mode = "资料分析分屏",
            startedAt = startedAt,
            submittedAt = now,
            durationSec = ((now - startedAt) / 1000).toInt(),
            totalCount = summary.total,
            answeredCount = summary.answered,
            correctCount = summary.correct,
            partialCount = summary.partial,
            score = summary.score,
            fullScore = summary.fullScore
        )
        val rid = db.insertRecord(record, results)
        startActivity(
            Intent(this, ReportActivity::class.java).apply {
                putExtra(ReportActivity.EXTRA_RECORD_ID, rid)
            }
        )
        finish()
    }

    override fun onBackPressed() {
        if (submitted) { super.onBackPressed(); return }
        confirmExit()
    }
}
