package com.lytree.qbank.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.AnswerResult
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.data.model.PaperRef
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivityQuestionReviewBinding
import com.lytree.qbank.databinding.ItemQuestionPageBinding
import com.lytree.qbank.databinding.ItemQuestionPagerPageBinding
import com.lytree.qbank.engine.Policy
import com.lytree.qbank.ui.paper.PaperDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 解析页（成绩报告 → 点题号 / 「错题解析」）。
 *
 * 版式就是答题页本身：同一张题目卡 [QuestionCardBinder]、同一套题号 / 题型标签 / 题干字号 /
 * 选项圆标，连滑动翻题的手势都一样。唯一的区别是**只读**——
 * 选项点不动、也不挂打叉，题目行右侧的标记 / 笔 / 收藏不显示；
 * 而解析块（正确答案 / 你的答案 / 结果 / 解析）正是答题页交卷后的那一块。
 *
 * [EXTRA_NUMBERS] 决定这次翻的是哪些题：错题解析只传错题在原卷里的下标，
 * 卡上的题号、顶栏的「第 N/M 题」都按原卷算，跟答卷对得上。
 */
class QuestionReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RECORD_ID = "record_id"
        /** 要翻的题：原卷题号下标（0 起）；不传 = 整卷 */
        const val EXTRA_NUMBERS = "numbers"
        const val EXTRA_START = "start"

        private const val PREFS = "exam_prefs"
        private const val KEY_FONT_SCALE = "font_scale"
        /** 与答题页同一组字号档位，复习时字号不该和做题时不一样 */
        private val FONT_SCALES = floatArrayOf(0.9f, 1.0f, 1.15f)
    }

    private lateinit var binding: ActivityQuestionReviewBinding
    private lateinit var db: QBankDb
    private var record: ExamRecord? = null
    private var numbers: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机锁竖屏 / 平板跟随旋转（方向锁是 Activity 级的，答题页锁了这里也要跟着锁）
        ScreenAdapt.applyOrientation(this)
        binding = ActivityQuestionReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)

        val recordId = intent.getLongExtra(EXTRA_RECORD_ID, 0L)
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val r = db.getRecord(recordId) ?: return@withContext null
                r to db.getPolicy()
            }
            if (isFinishing || isDestroyed) return@launch
            if (loaded == null) {
                finish(); return@launch
            }
            render(loaded.first, loaded.second)
        }
    }

    private fun render(record: ExamRecord, policy: Policy) {
        this.record = record
        if (record.results.isEmpty()) {
            finish(); return
        }

        val asked = intent.getIntArrayExtra(EXTRA_NUMBERS)?.toList().orEmpty()
            .filter { it in record.results.indices }
        numbers = asked.ifEmpty { record.results.indices.toList() }
        val items = numbers.map { record.results[it] }

        // 作答按题 id 带进卡片：解析块里的「你的答案 / 结果」直接由 binder 判
        val answers = HashMap<Long, String>()
        record.results.forEach { answers[it.question.id] = it.userAnswer }
        // 一则材料带多道小题时才给「弹出材料」入口（与答题页同一口径）
        val materialCounts = record.results
            .filter { it.question.material.isNotBlank() }
            .groupingBy { it.question.material }
            .eachCount()

        val adapter = ReviewPagerAdapter(
            items = items,
            numbers = numbers,
            answers = answers,
            marked = record.marked,
            policy = policy,
            materialCounts = materialCounts,
            fontScale = fontScale(),
            onMaterialExpand = { q -> openMaterial(q) }
        )
        binding.pager.offscreenPageLimit = 1
        binding.pager.adapter = adapter

        val start = intent.getIntExtra(EXTRA_START, 0).coerceIn(0, items.lastIndex)
        binding.pager.setCurrentItem(start, false)
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = syncChrome(position)
        })
        syncChrome(start)

        binding.btnPrev.setOnClickListener { goTo(binding.pager.currentItem - 1) }
        binding.btnNext.setOnClickListener { goTo(binding.pager.currentItem + 1) }
        // 原题里的材料表格 / 配图 / 图片选项，只有试卷详情渲染得全
        binding.titleBar.addTextAction(getString(R.string.report_action_open_paper)) {
            openInPaper(binding.pager.currentItem)
        }
    }

    /** 顶栏标题与两个翻页按钮跟着当前页走（第一题没有上一题、最后一题没有下一题） */
    private fun syncChrome(position: Int) {
        val total = record?.results?.size ?: numbers.size
        binding.titleBar.setTitle(
            getString(R.string.report_analysis_page_title, numbers[position] + 1, total)
        )
        val hasPrev = position > 0
        binding.btnPrev.isEnabled = hasPrev
        binding.btnPrev.alpha = if (hasPrev) 1f else 0.4f
        val hasNext = position < numbers.lastIndex
        binding.btnNext.isEnabled = hasNext
        binding.btnNext.alpha = if (hasNext) 1f else 0.4f
    }

    private fun goTo(position: Int) {
        if (position in numbers.indices) binding.pager.setCurrentItem(position, true)
    }

    private fun openMaterial(q: Question) {
        if (q.material.isBlank()) return
        startActivity(
            Intent(this, MaterialPopupActivity::class.java).apply {
                putExtra(MaterialPopupActivity.EXTRA_MATERIAL, q.material)
                putExtra(MaterialPopupActivity.EXTRA_FONT_SCALE, fontScale())
            }
        )
    }

    private fun openInPaper(position: Int) {
        val rec = record ?: return
        val qid = rec.results.getOrNull(numbers.getOrNull(position) ?: -1)?.question?.id ?: return
        if (!PaperRef.isValid(rec.paperId)) {
            Toast.makeText(this, R.string.report_paper_missing, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, PaperDetailActivity::class.java).apply {
                putExtra(PaperDetailActivity.EXTRA_PAPER_ID, rec.paperId)
                putExtra(PaperDetailActivity.EXTRA_PAPER_NAME, rec.displayTitle)
                putExtra(PaperDetailActivity.EXTRA_FOCUS_QUESTION_ID, qid)
                putExtra(PaperDetailActivity.EXTRA_RECORD_ID, rec.id)
            }
        )
    }

    /** 与答题页共用同一份字号偏好：复习时字号跟着用户在答题页调过的档位 */
    private fun fontScale(): Float {
        val idx = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_FONT_SCALE, 1)
        return FONT_SCALES.getOrElse(idx) { 1f }
    }
}

/**
 * 解析页的一页 = 答题页同款单题容器（`item_question_pager_page.xml` + `item_question_page.xml`），
 * 绑定同样走 [QuestionCardBinder.bind]，只是 [QuestionCardBinder.bind] 的 `readOnly` 打开、
 * 并且 `revealAnswers` 恒为 true。
 */
private class ReviewPagerAdapter(
    private val items: List<AnswerResult>,
    private val numbers: List<Int>,
    private val answers: MutableMap<Long, String>,
    private val marked: Set<Long>,
    private val policy: Policy,
    private val materialCounts: Map<String, Int>,
    private val fontScale: Float,
    private val onMaterialExpand: (Question) -> Unit
) : RecyclerView.Adapter<ReviewPagerAdapter.VH>() {

    class VH(val page: ItemQuestionPagerPageBinding, val card: ItemQuestionPageBinding) :
        RecyclerView.ViewHolder(page.root)

    /** 只读页面：标记 / 笔 / 收藏 / 作答都没有承接方，回调留空即可（图标本身也不显示） */
    private val cb = QuestionCardBinder.Callbacks(
        onMarkToggle = {},
        onFavoriteToggle = {},
        onInkClicked = {},
        onNoteClicked = {},
        onAnswerChanged = {},
        onMaterialExpand = { q -> onMaterialExpand(q) }
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        val page = ItemQuestionPagerPageBinding.inflate(inflater, parent, false)
        val card = ItemQuestionPageBinding.inflate(inflater, page.cardContainer, false)
        page.cardContainer.addView(card.root)
        return VH(page, card)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        QuestionCardBinder.bind(
            b = holder.card,
            // 题号按原卷编号：错题解析只翻错题，但卡上还得写「第 8 题」而不是「第 3 题」
            position = numbers[position],
            q = r.question,
            answers = answers,
            marked = marked,
            favorites = emptySet(),
            revealAnswers = true,
            policy = policy,
            fontScale = fontScale,
            cb = cb,
            hideMaterial = false,
            materialGroupSize = materialCounts[r.question.material] ?: 1,
            moduleHeader = null,
            showDivider = false,
            readOnly = true
        )
        // 复用的页可能停在上一题滚到一半的位置
        holder.page.pageScroll.scrollTo(0, 0)
    }
}
