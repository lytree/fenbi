package com.lytree.qbank.ui.paper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.db.PaperSet
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.ExamScope
import com.lytree.qbank.data.model.Paper
import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.databinding.ItemPaperBinding
import com.lytree.qbank.databinding.ItemPaperLevelBinding
import com.lytree.qbank.databinding.ItemPaperSetBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首页试卷树：**导入组 → 试卷 → 大类 → 材料** 四级，一层层摊开。
 *
 * 每一层都能直接当一张卷来答（[ExamScope] 负责把范围带进答题页）：
 *
 * ```
 * 2024国考行测           6 份 · 240 题   [整组作答]   ← 整组合成一张卷
 *   资料分析             30 题  ›                     ← 整卷作答
 *     资料分析           20 题  ›                     ← 只做这个大类
 *       材料1             5 题                        ← 只做这一则材料
 * ```
 *
 * 层级是**懒加载**的：试卷行展开时才读题、建 [PaperStructure]，首页不用一次性把几十份卷的
 * 题目全读进内存。展开状态由 Fragment 持有（跨刷新 / 切 Tab 保持）。
 */
class PaperTreeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Node {
        data class Header(val set: PaperSet, val collapsed: Boolean) : Node

        /** 一份卷：点整行 = 整卷作答；点行尾箭头 = 展开层级 */
        data class Row(val paper: Paper, val expanded: Boolean, val moduleCount: Int) : Node

        /** 一个大类：点整行 = 只做这个大类；有材料时箭头可展开 */
        data class SectionRow(
            val paperId: Long,
            val title: String,
            val count: Int,
            val scopeKey: String,
            val sectionKey: String,
            val expanded: Boolean,
            val expandable: Boolean
        ) : Node

        /** 一则材料：点整行 = 只做这一则材料 */
        data class MaterialRow(
            val paperId: Long,
            val title: String,
            val count: Int,
            val scopeKey: String
        ) : Node
    }

    private val nodes = ArrayList<Node>()

    var onPaperClick: ((Paper) -> Unit)? = null
    var onPaperLongClick: ((Paper) -> Unit)? = null

    /** 点组标题上的「整组作答」：把整组的卷子合成一张卷 */
    var onGroupAnswer: ((PaperSet) -> Unit)? = null

    /** 点了组标题：参数是这一组和「点完之后是否收起」 */
    var onToggleSet: ((PaperSet, Boolean) -> Unit)? = null
    var onSetLongClick: ((PaperSet) -> Unit)? = null

    /** 点了试卷行尾的箭头：展开 / 收起这份卷的大类 */
    var onTogglePaper: ((Paper, Boolean) -> Unit)? = null

    /** 点了某个大类行尾的箭头：展开 / 收起这一类的材料（参数是 sectionKey） */
    var onToggleSection: ((String) -> Unit)? = null

    /** 点了大类 / 材料行：按范围开练（题目页自己算标题） */
    var onAnswerScope: ((paperId: Long, scopeKey: String) -> Unit)? = null

    /**
     * @param collapsed 已收起的组 id；组 id 为 0（未分组）时用 [UNGROUPED_KEY] 表示
     * @param expandedPapers 已展开大类的试卷 id
     * @param expandedSections 已展开材料的 sectionKey 集合（见 [sectionKey]）
     * @param structures 已加载的试卷结构（只有展开过的卷才在里面）
     */
    fun submit(
        sets: List<PaperSet>,
        collapsed: Set<Long>,
        expandedPapers: Set<Long>,
        expandedSections: Set<String>,
        structures: Map<Long, PaperStructure>
    ) {
        nodes.clear()
        sets.forEach { set ->
            val folded = keyOf(set) in collapsed
            nodes.add(Node.Header(set, folded))
            if (folded) return@forEach
            set.papers.forEach { paper ->
                val structure = structures[paper.id]
                val expanded = paper.id in expandedPapers && structure != null
                nodes.add(
                    Node.Row(
                        paper = paper,
                        expanded = expanded,
                        moduleCount = structure?.sections?.size ?: 0
                    )
                )
                if (!expanded) return@forEach
                structure!!.sections.forEachIndexed { sectionIndex, section ->
                    val key = sectionKey(paper.id, section.startIndex)
                    val expandable = section.groups.any { it.isMaterialGroup }
                    val sectionExpanded = expandable && key in expandedSections
                    nodes.add(
                        Node.SectionRow(
                            paperId = paper.id,
                            title = section.name ?: UNCATEGORIZED,
                            count = section.count,
                            scopeKey = ExamScope.ofSection(section).key,
                            sectionKey = key,
                            expanded = sectionExpanded,
                            expandable = expandable
                        )
                    )
                    if (!sectionExpanded) return@forEachIndexed
                    // 材料序号在同一大类内从 1 数，与 ExamScope.label() 的「材料N」一致
                    section.groups.filter { it.isMaterialGroup }.forEachIndexed { index, group ->
                        nodes.add(
                            Node.MaterialRow(
                                paperId = paper.id,
                                title = MATERIAL_FMT.format(index + 1),
                                count = group.count,
                                scopeKey = ExamScope.ofMaterial(group).key
                            )
                        )
                    }
                }
            }
        }
        notifyDataSetChanged()
    }

    /** 折叠状态用的键：未分组（id = 0）用 -1，避免和真实 id 撞上 */
    fun keyOf(set: PaperSet): Long = if (set.id > 0) set.id else UNGROUPED_KEY

    /** 大类展开状态的键：一份卷里同一个大类只会有一段（连续相同 subject 才成段），起题号足够区分 */
    fun sectionKey(paperId: Long, startIndex: Int): String = "$paperId:$startIndex"

    override fun getItemViewType(position: Int): Int = when (nodes[position]) {
        is Node.Header -> TYPE_SET
        is Node.Row -> TYPE_PAPER
        else -> TYPE_LEVEL
    }

    class SetVH(val binding: ItemPaperSetBinding) : RecyclerView.ViewHolder(binding.root)
    class PaperVH(val binding: ItemPaperBinding) : RecyclerView.ViewHolder(binding.root)
    class LevelVH(val binding: ItemPaperLevelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SET -> SetVH(ItemPaperSetBinding.inflate(inflater, parent, false))
            TYPE_PAPER -> PaperVH(ItemPaperBinding.inflate(inflater, parent, false))
            else -> LevelVH(ItemPaperLevelBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount() = nodes.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val node = nodes[position]) {
            is Node.Header -> bindHeader(holder as SetVH, node)
            is Node.Row -> bindPaper(holder as PaperVH, node)
            is Node.SectionRow -> bindSection(holder as LevelVH, node)
            is Node.MaterialRow -> bindMaterial(holder as LevelVH, node)
        }
    }

    private fun bindHeader(holder: SetVH, node: Node.Header) {
        val set = node.set
        // chevron 往右指 = 展开，往「下」指 = 收起，靠旋转同一个图标，不用备两张图
        holder.binding.arrow.rotation = if (node.collapsed) -90f else 0f
        holder.binding.title.text = set.title
        holder.binding.subtitle.text = buildString {
            append("${set.paperCount} 份 · ${set.questionCount} 题")
            dateText(set.createdAt)?.let { append(" · ").append(it) }
        }
        // 缺答案从灰字里提出来做成橙色胶囊：扫列表时先看到「要补答案」的组
        holder.binding.missing.text = "缺 ${set.missingAnswers} 答案"
        holder.binding.missing.visibility = if (set.missingAnswers > 0) View.VISIBLE else View.GONE
        // 整组作答：「未分组」是兜底伪组（没有组行可查），隐藏；
        // 组里只有一份卷时照样给入口，点的就是那份卷本身（见 Fragment.answerGroup）
        val canAnswerGroup = set.id > 0L && set.papers.isNotEmpty()
        holder.binding.groupAnswer.visibility = if (canAnswerGroup) View.VISIBLE else View.GONE
        holder.binding.groupAnswer.setOnClickListener { onGroupAnswer?.invoke(set) }
        holder.binding.root.setOnClickListener {
            onToggleSet?.invoke(set, !node.collapsed)
        }
        holder.binding.root.setOnLongClickListener {
            onSetLongClick?.invoke(set)
            true
        }
    }

    private fun bindPaper(holder: PaperVH, node: Node.Row) {
        val p = node.paper
        holder.binding.title.text = p.name
        holder.binding.subtitle.text = buildString {
            val count = if (p.questionCount > 0) p.questionCount else p.questions.size
            append("$count 题")
            if (p.durationMin > 0) append(" · 限时 ${p.durationMin} 分钟")
            if (node.moduleCount > 1) append(" · ${node.moduleCount} 个模块")
            // 导入生成的卷子占绝大多数，不必每题都挂个「导入」；
            // 只标注历史遗留的组卷来源，方便区分。
            if (p.mode.isNotBlank() && p.mode != QBankDb.MODE_IMPORT) append(" · ${p.mode}")
        }
        // 还缺标准答案的卷子标出来（长按试卷可以补答案）
        holder.binding.missing.text = "缺 ${p.missingAnswers} 答案"
        holder.binding.missing.visibility = if (p.missingAnswers > 0) View.VISIBLE else View.GONE
        indent(holder.binding.root, LEVEL_PAPER)
        holder.binding.root.setOnClickListener { onPaperClick?.invoke(p) }
        holder.binding.root.setOnLongClickListener { onPaperLongClick?.invoke(p); true }
        // 行尾箭头只管「展开层级」，点整行才是整卷开练
        holder.binding.expand.rotation = if (node.expanded) 90f else 0f
        holder.binding.expand.setOnClickListener { onTogglePaper?.invoke(p, !node.expanded) }
    }

    private fun bindSection(holder: LevelVH, node: Node.SectionRow) {
        val b = holder.binding
        val ctx = holder.itemView.context
        b.title.text = node.title
        b.title.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            ctx.resources.getDimension(R.dimen.fb_text_item_title)
        )
        b.title.setTypeface(null, android.graphics.Typeface.BOLD)
        b.title.setTextColor(ContextCompat.getColor(ctx, R.color.fb_text_title))
        b.count.text = ctx.getString(R.string.tree_count, node.count)
        b.chevron.visibility = if (node.expandable) View.VISIBLE else View.INVISIBLE
        b.chevron.rotation = if (node.expanded) 90f else 0f
        b.chevron.setOnClickListener { onToggleSection?.invoke(node.sectionKey) }
        b.root.setOnClickListener { onAnswerScope?.invoke(node.paperId, node.scopeKey) }
        indent(b.root, LEVEL_SECTION)
    }

    private fun bindMaterial(holder: LevelVH, node: Node.MaterialRow) {
        val b = holder.binding
        val ctx = holder.itemView.context
        b.title.text = node.title
        b.title.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            ctx.resources.getDimension(R.dimen.fb_text_item_sub)
        )
        b.title.setTypeface(null, android.graphics.Typeface.NORMAL)
        b.title.setTextColor(ContextCompat.getColor(ctx, R.color.fb_text_secondary))
        b.count.text = ctx.getString(R.string.tree_count, node.count)
        b.chevron.visibility = View.INVISIBLE
        b.chevron.setOnClickListener(null)
        b.root.setOnClickListener { onAnswerScope?.invoke(node.paperId, node.scopeKey) }
        indent(b.root, LEVEL_MATERIAL)
    }

    /**
     * 组内卷子往里缩一格：靠缩进读出层级，不额外画连接线。
     * 卡片本身有 16dp 的页面边距，每深一层再加 14dp。
     */
    private fun indent(view: View, level: Int) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val base = view.resources.getDimensionPixelSize(R.dimen.fb_page_pad)
        val step = (view.resources.displayMetrics.density * INDENT_STEP_DP).toInt()
        lp.marginStart = base + step * (level - 1)
        view.layoutParams = lp
    }

    private fun dateText(millis: Long): String? {
        if (millis <= 0L) return null
        return try {
            SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(millis))
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TYPE_SET = 0
        private const val TYPE_PAPER = 1
        private const val TYPE_LEVEL = 2

        /** 折叠状态里「未分组」这一伪组用的键（不可能是真实 id） */
        const val UNGROUPED_KEY = -1L

        /** 层级：1 = 试卷，2 = 大类，3 = 材料（只影响缩进） */
        private const val LEVEL_PAPER = 1
        private const val LEVEL_SECTION = 2
        private const val LEVEL_MATERIAL = 3
        private const val INDENT_STEP_DP = 14f

        private const val UNCATEGORIZED = "未分类"
        private val MATERIAL_FMT = "材料%d"
    }
}
