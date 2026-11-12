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
import com.lytree.qbank.data.model.ExamScope
import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.databinding.ActivityPaperOutlineBinding
import com.lytree.qbank.databinding.ItemPaperOutlineBinding
import com.lytree.qbank.ui.ExamActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 试卷目录：把一份卷按三级结构摊开，**点哪一层就只做哪一层**。
 *
 * ```
 * 整卷作答                135题
 * 资料分析                 20题     ← 点这里 = 只做这一个模块
 *   材料1                   5题     ← 点这里 = 只做这一则材料
 *   材料2                   5题
 * 言语理解                 30题
 * ...
 * ```
 *
 * 答题范围以 [ExamScope] 的短串带给答题页（[ExamActivity.EXTRA_SCOPE_KEY]），
 * 题号、答题卡、判分、记录、未交卷进度都只算这一截——所以「资料分析」和整卷
 * 可以各自留进度、各自出成绩，互不覆盖。
 *
 * 入口：首页试卷长按菜单的「试卷目录」，以及试卷详情页底部的「分模块」。
 */
class PaperOutlineActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAPER_ID = "paper_id"
        const val EXTRA_PAPER_NAME = "paper_name"
    }

    private lateinit var binding: ActivityPaperOutlineBinding
    private val adapter = OutlineAdapter()
    private var paperId = 0L
    private var paperName = "试卷"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPaperOutlineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        paperId = intent.getLongExtra(EXTRA_PAPER_ID, 0L)
        paperName = intent.getStringExtra(EXTRA_PAPER_NAME)?.takeIf { it.isNotBlank() } ?: "试卷"
        binding.titleBar.setTitle(paperName)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        adapter.onPick = { row -> start(row) }

        load()
    }

    /** 读卷 + 建结构放后台；回来时页面已销毁就丢弃 */
    private fun load() {
        lifecycleScope.launch {
            val db = QBankDb.get(this@PaperOutlineActivity)
            val data = withContext(Dispatchers.IO) {
                val paper = db.getPaper(paperId) ?: return@withContext null
                Triple(paper.name, PaperStructure(paper.questions), paper.durationMin)
            }
            if (isFinishing || isDestroyed) return@launch
            if (data == null) {
                Toast.makeText(this@PaperOutlineActivity, "试卷不存在", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            paperName = data.first
            binding.titleBar.setTitle(paperName)
            val structure = data.second
            binding.empty.visibility = if (structure.size == 0) View.VISIBLE else View.GONE
            adapter.submit(buildRows(structure, data.third))
        }
    }

    /**
     * 结构 → 行。
     *
     * 材料只列**真的带材料**的组：一题一组的题本来就是一题，再单列一行只会把目录撑成题目清单。
     * 材料序号在同一大类内从 1 数，与 [ExamScope.label] 的「材料N」口径一致。
     */
    private fun buildRows(structure: PaperStructure, durationMin: Int): List<OutlineAdapter.Row> {
        val rows = ArrayList<OutlineAdapter.Row>()
        rows.add(
            OutlineAdapter.Row(
                title = getString(R.string.outline_whole_paper),
                subtitle = if (durationMin > 0) {
                    getString(R.string.outline_whole_sub, structure.size, durationMin)
                } else {
                    getString(R.string.outline_whole_sub_untimed, structure.size)
                },
                count = getString(R.string.outline_count, structure.size),
                scopeKey = ExamScope.FULL.key,
                level = 0
            )
        )
        structure.sections.forEach { section ->
            rows.add(
                OutlineAdapter.Row(
                    title = section.name ?: getString(R.string.outline_uncategorized),
                    subtitle = "",
                    count = getString(R.string.outline_count, section.count),
                    scopeKey = ExamScope.ofSection(section).key,
                    level = 1
                )
            )
            section.groups.filter { it.isMaterialGroup }.forEachIndexed { index, group ->
                rows.add(
                    OutlineAdapter.Row(
                        title = getString(R.string.outline_material, index + 1),
                        subtitle = "",
                        count = getString(R.string.outline_count, group.count),
                        scopeKey = ExamScope.ofMaterial(group).key,
                        level = 2
                    )
                )
            }
        }
        return rows
    }

    private fun start(row: OutlineAdapter.Row) {
        startActivity(
            Intent(this, ExamActivity::class.java).apply {
                putExtra(ExamActivity.EXTRA_PAPER_ID, paperId)
                // 卷名不带范围，范围单独带：答题页自己拼「卷名 · 资料分析」
                putExtra(ExamActivity.EXTRA_PAPER_NAME, paperName)
                putExtra(ExamActivity.EXTRA_SCOPE_KEY, row.scopeKey)
            }
        )
    }
}

/**
 * 目录行的适配器：三级共用一个布局，靠字号 / 字色 / 缩进区分层级。
 *
 * - [Row.level] 0 = 整卷（最上面的总入口），1 = 大类，2 = 材料（多缩进 16dp、字弱一档）。
 */
class OutlineAdapter : RecyclerView.Adapter<OutlineAdapter.VH>() {

    class Row(
        val title: String,
        val subtitle: String,
        val count: String,
        val scopeKey: String,
        val level: Int
    )

    private val items = ArrayList<Row>()
    var onPick: ((Row) -> Unit)? = null

    class VH(val binding: ItemPaperOutlineBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(rows: List<Row>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPaperOutlineBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val b = holder.binding
        val ctx = holder.itemView.context
        val res = ctx.resources

        b.title.text = row.title
        b.count.text = row.count
        b.subtitle.text = row.subtitle
        b.subtitle.visibility = if (row.subtitle.isBlank()) View.GONE else View.VISIBLE

        // 层级样式：大类 = 加粗大字；材料 = 常规弱字 + 缩进
        when (row.level) {
            2 -> {
                b.title.setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    res.getDimension(R.dimen.fb_text_item_title)
                )
                b.title.setTypeface(null, android.graphics.Typeface.NORMAL)
                b.title.setTextColor(ContextCompat.getColor(ctx, R.color.fb_text_secondary))
            }
            else -> {
                b.title.setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    res.getDimension(R.dimen.fb_text_card_title)
                )
                b.title.setTypeface(null, android.graphics.Typeface.BOLD)
                b.title.setTextColor(ContextCompat.getColor(ctx, R.color.fb_text_title))
            }
        }

        // 缩进：卡片本身有 16dp 的页面边距，材料再往里让一档，层级一眼看出来
        val lp = b.root.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            val base = res.getDimensionPixelSize(R.dimen.fb_page_pad)
            val extra = if (row.level == 2) res.getDimensionPixelSize(R.dimen.fb_space_16) else 0
            lp.marginStart = base + extra
            b.root.layoutParams = lp
        }

        b.root.setOnClickListener { onPick?.invoke(row) }
    }
}
