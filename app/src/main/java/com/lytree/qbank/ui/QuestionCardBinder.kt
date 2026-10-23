package com.lytree.qbank.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.data.model.QuestionType
import com.lytree.qbank.databinding.ItemOptionBinding
import com.lytree.qbank.databinding.ItemQuestionPageBinding
import com.lytree.qbank.engine.Judge
import com.lytree.qbank.engine.Policy
import java.util.Locale

/**
 * 题目卡渲染器。
 *
 * 「单题浏览」（[QuestionPagerAdapter] + ViewPager2）与「多题浏览」
 * （[QuestionListAdapter] + RecyclerView）承载的是同一张卡片 [ItemQuestionPageBinding]，
 * 所以绑定逻辑只能有一份——否则两份实现很快就会出现「单题模式没有字号缩放 / 收藏点了没反应 /
 * 交卷后不出解析」这类只在一侧发生的差异。
 *
 * 这里集中处理：
 * - 题号、题型标签、标记 / 手写 / 收藏 三图标状态与点击；
 * - 题干与选项的字号缩放、选项徽标两态、多选与单选的作答口径；
 * - 交卷后的解析区（正确答案 / 你的答案 / 结果 / 解析）。
 *
 * 选完选项只就地刷新徽标、不触发整卡重绑——单题模式下重绑会把页内滚动位置顶回去。
 */
object QuestionCardBinder {

    /** 卡片上的四个交互回调，由各适配器在构造时注入 */
    class Callbacks(
        val onMarkToggle: (Question) -> Unit,
        val onFavoriteToggle: (Question) -> Unit,
        /** 点卡片上的「笔」：进手写标注（遮盖层），直接在题目上圈画 */
        val onInkClicked: (Question) -> Unit,
        /** 长按「笔」：编辑本题文字笔记 */
        val onNoteClicked: (Question) -> Unit,
        val onAnswerChanged: (Question) -> Unit,
        /** 材料右上角「弹出材料」：把材料单独放大展示 */
        val onMaterialExpand: ((Question) -> Unit)? = null
    )

    fun bind(
        b: ItemQuestionPageBinding,
        position: Int,
        q: Question,
        answers: MutableMap<Long, String>,
        marked: Set<Long>,
        favorites: Set<Long>,
        revealAnswers: Boolean,
        policy: Policy,
        fontScale: Float,
        cb: Callbacks,
        /** 夜间模式：只换颜色与背景资源，版式、字号、间距一律不动 */
        night: Boolean = false,
        /** 分屏右栏：材料已在左栏整栏展示，卡片不再重复渲染材料 */
        hideMaterial: Boolean = false,
        /**
         * 练习模式（参考应用的「练习」作答口径）：本题作答后立即判色出解析，
         * 不必等整卷交卷。集合里是已触发即时判分的题 id。
         */
        revealed: Set<Long> = emptySet()
    ) {
        val ctx = b.root.context
        val res = ctx.resources
        val density = res.displayMetrics.density
        val revealNow = revealAnswers || q.id in revealed

        // ---- 卡片底色 ----
        b.root.setBackgroundResource(
            if (night) R.drawable.bg_fb_card_night else R.drawable.bg_fb_card
        )

        // ---- 题目行：题号 + 题型 + 三图标 ----
        b.tvIndex.text = "${position + 1}."
        b.tvIndex.setTextColor(
            ContextCompat.getColor(ctx, if (night) R.color.fb_night_text else R.color.fb_text_title)
        )
        b.tvType.text = q.type.label
        b.tvType.setBackgroundResource(
            if (night) R.drawable.bg_fb_tag_night else R.drawable.bg_fb_tag
        )
        b.tvType.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_tag_text else R.color.fb_type_tag_text
            )
        )

        // 本题没有标准答案（试卷先导入、答案后补）→ 挂一个「待补答案」小标签
        val noStandard = !Judge.hasStandard(q)
        b.tvPending.visibility = if (noStandard) View.VISIBLE else View.GONE
        if (noStandard) {
            b.tvPending.setBackgroundResource(
                if (night) R.drawable.bg_fb_tag_night else R.drawable.bg_fb_tag
            )
            b.tvPending.setTextColor(ContextCompat.getColor(ctx, R.color.partial))
        }

        val iconColor = ContextCompat.getColor(
            ctx, if (night) R.color.fb_night_icon else R.color.fb_icon
        )
        val isMarked = q.id in marked
        b.btnMark.setImageResource(
            if (isMarked) R.drawable.ic_fb_bookmark_filled else R.drawable.ic_fb_bookmark
        )
        b.btnMark.setColorFilter(
            if (isMarked) ContextCompat.getColor(ctx, R.color.fb_primary) else iconColor
        )
        val isFavorite = q.id in favorites
        b.btnFavorite.setImageResource(
            if (isFavorite) R.drawable.ic_fb_star_filled else R.drawable.ic_fb_star
        )
        b.btnFavorite.setColorFilter(
            if (isFavorite) ContextCompat.getColor(ctx, R.color.fb_card_num_favorite) else iconColor
        )
        b.btnScratch.setColorFilter(iconColor)

        b.btnMark.setOnClickListener { cb.onMarkToggle(q) }
        b.btnFavorite.setOnClickListener { cb.onFavoriteToggle(q) }
        // 「笔」= 手写标注入口：点了就盖一层可写薄纸，圈画当前题；
        // 文字笔记改成**长按**这支笔（以前点一下弹输入框，跟画布完全没关系，等于点了没反应）
        b.btnScratch.setOnClickListener { cb.onInkClicked(q) }
        b.btnScratch.setOnLongClickListener {
            cb.onNoteClicked(q)
            true
        }

        // ---- 题干（字号随「更多」面板缩放；基准 17sp / 额外行距 6dp）----
        b.tvStem.text = q.stem
        b.tvStem.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_text_body else R.color.fb_text_body
            )
        )
        b.tvStem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * fontScale)
        b.tvStem.setLineSpacing(6f * density * fontScale, 1f)

        // ---- 材料 ----
        if (q.material.isNotBlank() && !hideMaterial) {
            b.materialBox.visibility = View.VISIBLE
            b.materialBox.setBackgroundResource(
                if (night) R.drawable.bg_fb_material_night else R.drawable.bg_fb_material
            )
            b.tvMaterialTag.setBackgroundResource(
                if (night) R.drawable.bg_fb_material_tag_night else R.drawable.bg_fb_material_tag
            )
            b.tvMaterialTag.setTextColor(
                ContextCompat.getColor(
                    ctx, if (night) R.color.fb_night_tag_text else R.color.fb_type_tag_text
                )
            )
            // 「弹出材料」：窄屏里材料被卡宽压着，表格 / 图表都看不清，单独弹出来看
            b.tvMaterialExpand.setTextColor(
                ContextCompat.getColor(
                    ctx, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
                )
            )
            b.icMaterialExpand.setColorFilter(
                ContextCompat.getColor(
                    ctx, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
                )
            )
            b.btnMaterialExpand.setOnClickListener { cb.onMaterialExpand?.invoke(q) }
            // 上下分块：材料区独立滚动，高度 = 屏高 × 记忆比例，拖动手柄实时调节
            applyMaterialSplit(b)
            MaterialRenderer.render(ctx, b.materialContent, q.material, night)
            // 短材料不必占满记忆高度：渲染完成后收缩到内容实际高度，避免卡片里留大片空白
            b.materialScroll.post {
                val contentH = b.materialContent.height
                val cur = b.materialScroll.layoutParams.height
                if (contentH > 0 && contentH < cur) {
                    b.materialScroll.layoutParams.height = contentH
                    b.materialScroll.requestLayout()
                }
            }
        } else {
            b.materialBox.visibility = View.GONE
            b.materialHandle.visibility = View.GONE
            b.materialContent.removeAllViews()
        }

        // ---- 选项 ----
        b.optionsBox.removeAllViews()
        buildOptions(b, q, answers, fontScale, cb, night, revealNow)

        // ---- 解析（交卷后 / 练习模式即时判分后）----
        b.tvAnalysis.setBackgroundResource(
            if (night) R.drawable.bg_fb_note_night else R.drawable.bg_fb_note
        )
        b.tvAnalysis.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_text_body else R.color.fb_text_primary
            )
        )
        if (revealNow) {
            val userAns = answers[q.id] ?: ""
            val r = Judge.judge(q, userAns, policy)
            b.tvAnalysis.visibility = View.VISIBLE
            b.tvAnalysis.text = if (r.pending) {
                buildString {
                    append(res.getString(R.string.pending_answer_empty)).append('\n')
                    append("你的答案：${userAns.ifBlank { "未作答" }}\n")
                    append(res.getString(R.string.pending_answer_hint))
                }
            } else {
                buildString {
                    append("正确答案：${q.displayAnswer()}\n")
                    append("你的答案：${userAns.ifBlank { "未作答" }}\n")
                    append(
                        "结果：${when {
                            r.correct -> "正确"
                            r.partial -> "部分得分 ${fmtScore(r.earned)}/${fmtScore(r.full)}"
                            else -> "错误"
                        }}\n"
                    )
                    if (q.analysis.isNotBlank()) append("解析：${q.analysis}")
                }
            }
        } else {
            b.tvAnalysis.visibility = View.GONE
        }
    }

    /** 短选项两列的判定：选项 ≥ 4 个且文本都很短（参考应用对「判断/常识短选项」的两列排布） */
    private const val TWO_COL_MAX_OPTIONS = 4
    private const val TWO_COL_MAX_TEXT_LEN = 6

    // ---------------- 材料/题目上下分块（独立滑动 + 占比可调） ----------------

    private const val MATERIAL_SPLIT_PREFS = "exam_prefs"
    private const val KEY_MATERIAL_RATIO = "material_split_ratio"
    /** 材料区高度占屏高的比例上下限与默认值 */
    private const val MATERIAL_RATIO_MIN = 0.12f
    private const val MATERIAL_RATIO_MAX = 0.72f
    private const val MATERIAL_RATIO_DEFAULT = 0.38f

    private fun readMaterialRatio(ctx: android.content.Context): Float =
        ctx.getSharedPreferences(MATERIAL_SPLIT_PREFS, android.content.Context.MODE_PRIVATE)
            .getFloat(KEY_MATERIAL_RATIO, MATERIAL_RATIO_DEFAULT)

    private fun saveMaterialRatio(ctx: android.content.Context, ratio: Float) {
        ctx.getSharedPreferences(MATERIAL_SPLIT_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putFloat(KEY_MATERIAL_RATIO, ratio).apply()
    }

    /** 按记忆比例设定材料区高度，并给手柄挂上拖动逻辑（多张卡共享同一份比例） */
    private fun applyMaterialSplit(b: ItemQuestionPageBinding) {
        val ctx = b.root.context
        val screenH = ctx.resources.displayMetrics.heightPixels
        val maxH = (screenH * MATERIAL_RATIO_MAX).toInt()
        val ratio = readMaterialRatio(ctx)
        val lp = b.materialScroll.layoutParams
        lp.height = (screenH * ratio.coerceIn(MATERIAL_RATIO_MIN, MATERIAL_RATIO_MAX)).toInt()
            .coerceAtMost(maxH)
        b.materialScroll.layoutParams = lp
        b.materialHandle.visibility = View.VISIBLE

        var startY = 0f
        var startH = 0
        b.materialHandle.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = ev.rawY
                    startH = b.materialScroll.layoutParams.height
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val h = (startH + (ev.rawY - startY)).toInt()
                        .coerceIn((screenH * MATERIAL_RATIO_MIN).toInt(), maxH)
                    if (h != startH) {
                        b.materialScroll.layoutParams.height = h
                        b.materialScroll.requestLayout()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    saveMaterialRatio(ctx, b.materialScroll.layoutParams.height.toFloat() / screenH)
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun buildOptions(
        b: ItemQuestionPageBinding,
        q: Question,
        answers: MutableMap<Long, String>,
        fontScale: Float,
        cb: Callbacks,
        night: Boolean,
        revealNow: Boolean
    ) {
        val ctx = b.root.context
        val inflater = LayoutInflater.from(ctx)
        val multi = q.type == QuestionType.MULTI || q.type == QuestionType.UNCERTAIN
        val selected = parseKeys(answers[q.id])
        val optionTextColor = ContextCompat.getColor(
            ctx, if (night) R.color.fb_night_text_body else R.color.fb_option_text
        )
        // 即时判分的着色基准：正确项 = 绿，选错的项 = 红
        val correctKeys = if (revealNow) revealKeys(q) else emptySet()
        val wrongSelected = if (revealNow) selected.filter { it !in correctKeys }.toSet() else emptySet()

        /** 绑定单个选项行 */
        fun bindOption(opt: com.lytree.qbank.data.model.Option): ItemOptionBinding {
            val item = ItemOptionBinding.inflate(inflater, b.optionsBox, false)
            item.tvKey.text = opt.key
            item.tvKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * fontScale)
            item.tvText.text = opt.text
            item.tvText.setTextColor(optionTextColor)
            item.tvText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
            item.tvText.setLineSpacing(
                5f * ctx.resources.displayMetrics.density * fontScale, 1f
            )
            val key = opt.key.uppercase()
            applyCircleState(
                item,
                selected.contains(key),
                night,
                when {
                    revealNow && correctKeys.isNotEmpty() && key in correctKeys -> Reveal.RIGHT
                    revealNow && key in wrongSelected -> Reveal.WRONG
                    else -> Reveal.NONE
                }
            )

            // 把 item 绑定挂到子 view 上，作答后就地刷新徽标时不必重新解析下标
            item.root.tag = item
            item.root.setOnClickListener {
                val k = opt.key.uppercase()
                if (multi) {
                    if (!selected.remove(k)) selected.add(k)
                } else {
                    selected.clear()
                    selected.add(k)
                }
                answers[q.id] = selected.sorted().joinToString(",")
                refreshSelection(b, q, answers, night)
                cb.onAnswerChanged(q)
            }
            return item
        }

        val twoColumn = q.options.size >= TWO_COL_MAX_OPTIONS &&
                q.options.all { it.text.length <= TWO_COL_MAX_TEXT_LEN }
        if (twoColumn) {
            // 短选项两列：两行一组水平排布，各占一半宽度
            q.options.chunked(2).forEach { rowOpts ->
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                rowOpts.forEach { opt ->
                    val v = bindOption(opt).root
                    row.addView(v, android.widget.LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ))
                }
                // 单数行补一个占位，让最后一项仍靠左半宽，与整列对齐
                if (rowOpts.size == 1) {
                    row.addView(View(ctx), android.widget.LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ))
                }
                b.optionsBox.addView(row)
            }
        } else {
            q.options.forEach { opt -> b.optionsBox.addView(bindOption(opt).root) }
        }
    }

    /** 作答后就地刷新所有选项徽标（不重绑整张卡） */
    private fun refreshSelection(
        b: ItemQuestionPageBinding,
        q: Question,
        answers: MutableMap<Long, String>,
        night: Boolean
    ) {
        val selected = parseKeys(answers[q.id])
        for (i in 0 until b.optionsBox.childCount) {
            val child = b.optionsBox.getChildAt(i)
            if (child.tag is ItemOptionBinding) {
                // 单列：child 本身就是选项行
                applyState(child.tag as ItemOptionBinding, selected, night)
            } else if (child is ViewGroup) {
                // 两列：选项行包在水平容器里，逐个刷新（单数行末尾是占位 View，tag 为空会跳过）
                for (j in 0 until child.childCount) {
                    val inner = child.getChildAt(j)
                    (inner.tag as? ItemOptionBinding)?.let { applyState(it, selected, night) }
                }
            }
        }
    }

    private fun applyState(item: ItemOptionBinding, selected: Set<String>, night: Boolean) {
        applyCircleState(item, selected.contains(item.tvKey.text.toString().uppercase()), night)
    }

    /** `"A,C"` / `"a，c"` / `"A C"` 统一解成大写键集合 */
    fun parseKeys(raw: String?): MutableSet<String> =
        (raw ?: "").split(',', '，', ' ')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .toMutableSet()

    /** 圆形徽标三态：未选（灰描边灰字）/ 已选（蓝实心白字）/ 判分后对错（绿·红实心白字） */
    enum class Reveal { NONE, RIGHT, WRONG }

    fun applyCircleState(
        item: ItemOptionBinding,
        selected: Boolean,
        night: Boolean = false,
        reveal: Reveal = Reveal.NONE
    ) {
        val ctx = item.root.context
        when {
            reveal == Reveal.RIGHT -> {
                item.tvKey.setBackgroundResource(R.drawable.bg_fb_circle_right)
                item.tvKey.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            }
            reveal == Reveal.WRONG -> {
                item.tvKey.setBackgroundResource(R.drawable.bg_fb_circle_wrong)
                item.tvKey.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            }
            selected -> {
                item.tvKey.setBackgroundResource(R.drawable.bg_fb_circle_selected)
                item.tvKey.setTextColor(ContextCompat.getColor(ctx, R.color.fb_circle_on))
            }
            else -> {
                item.tvKey.setBackgroundResource(
                    if (night) R.drawable.bg_fb_circle_night else R.drawable.bg_fb_circle
                )
                item.tvKey.setTextColor(
                    ContextCompat.getColor(
                        ctx, if (night) R.color.fb_night_circle_text else R.color.fb_circle_text
                    )
                )
            }
        }
    }

    /**
     * 即时判分用的「正确项集合」：标准答案键与选项 key 的交集优先；
     * 判断题答案常以「正确/错误/T/F」入库而不与选项 key（A/B）对应，此时按选项文本兜底匹配。
     */
    private fun revealKeys(q: Question): Set<String> {
        if (!Judge.hasStandard(q)) return emptySet()
        val optionKeys = q.options.map { it.key.uppercase() }.toSet()
        val direct = q.answerKeys().filter { it in optionKeys }.toSet()
        if (direct.isNotEmpty()) return direct
        if (q.type == QuestionType.TRUE_FALSE) {
            val raw = Judge.normalizeText(q.answer)
            val isTrue = raw in setOf("t", "true", "y", "yes", "对", "正确", "√")
            return q.options.filter { o ->
                val t = Judge.normalizeText(o.text)
                if (isTrue) t in setOf("正确", "对", "t", "true", "yes", "y", "√")
                else t in setOf("错误", "错", "f", "false", "no", "n", "×")
            }.map { it.key.uppercase() }.toSet()
        }
        return emptySet()
    }

    fun fmtScore(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.CHINA, "%.1f", v)
}
