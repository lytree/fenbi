package com.lytree.qbank.ui

import android.graphics.Paint
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.data.model.QuestionType
import com.lytree.qbank.databinding.ItemOptionBinding
import com.lytree.qbank.databinding.ItemQuestionPageBinding
import com.lytree.qbank.databinding.ViewModuleHeaderBinding
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

    /**
     * 模块说明卡。对齐参考应用真机：一个模块（资料分析 / 常识判断…）在卷面上只出现一次，
     * 落在该模块首题的上方，「标题 = 模块名 (N题)」+ 该模块的官方作答说明。
     */
    data class ModuleHeader(val title: String, val desc: String)

    /**
     * 模块说明卡的统一渲染：卷面内嵌（多题浏览 / 分屏右栏）与单题浏览的「说明页」共用，
     * 保证两处的卡片样式、夜间配色一致。
     */
    fun bindModuleCard(b: ViewModuleHeaderBinding, header: ModuleHeader, night: Boolean) {
        val ctx = b.root.context
        b.root.setBackgroundResource(
            if (night) R.drawable.bg_fb_module_night else R.drawable.bg_fb_module
        )
        b.tvModuleTitle.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_text else R.color.fb_module_title
            )
        )
        b.tvModuleTitle.text = header.title
        b.tvModuleDesc.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
            )
        )
        b.tvModuleDesc.text = header.desc
        b.tvModuleDesc.visibility = if (header.desc.isBlank()) View.GONE else View.VISIBLE
    }

    /** 卡片上的交互回调，由各适配器在构造时注入 */
    class Callbacks(
        val onMarkToggle: (Question) -> Unit,
        val onFavoriteToggle: (Question) -> Unit,
        /** 点卡片上的「笔」：进手写标注（遮盖层），直接在题目上圈画 */
        val onInkClicked: (Question) -> Unit,
        /** 长按「笔」：编辑本题文字笔记 */
        val onNoteClicked: (Question) -> Unit,
        val onAnswerChanged: (Question) -> Unit,
        /** 材料右上角「弹出材料」：把材料单独放大展示 */
        val onMaterialExpand: ((Question) -> Unit)? = null,
        /** 点材料标题栏：收起 / 展开材料正文（不传 = 不给折叠入口） */
        val onMaterialToggle: ((Question) -> Unit)? = null
    )

    /**
     * 本题卡在「材料组」这条树枝上的位置（见 [TreeRailView]）。
     *
     * 三件事分开说，调用方（[QuestionListAdapter]）只按结构填，不用关心怎么画：
     * - [above]：顶边要不要把脊线接进来（组内非首题）；
     * - [below]：底边要不要继续往下连（组内非末题）；
     * - [materialNode]：本卡是不是组首、材料头算不算一个节点。
     */
    class RailMark(
        val above: Boolean,
        val below: Boolean,
        val materialNode: Boolean
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
        revealed: Set<Long> = emptySet(),
        /** 模块说明卡：只在该模块第一题传值，其余题传 null（卷面上一模块只出现一次） */
        moduleHeader: ModuleHeader? = null,
        /**
         * 本题所属材料组里的题量（默认 1 = 一道材料只带一道题）。
         *
         * 「弹出材料」只对**一材料多题**有意义：一道材料只带一道题时，材料在卡上本来
         * 就整段完整显示，再弹一层只是把同一段文字重看一遍，入口反而占地方。
         */
        materialGroupSize: Int = 1,
        /**
         * 题间分隔线。多题浏览是卷面通铺，靠这条线分题；
         * 单题浏览一屏一题、下面没有「下一题」，画了反而像被截断。
         */
        showDivider: Boolean = true,
        /**
         * 只读复习（错题解析 / 单题解析）：答案已经定了，选项不能再点选、也不能长按打叉，
         * 右上角「标记 / 笔 / 收藏」也没有承接动作，直接不显示。
         * 其余版式、字号、判分着色一律与答题页相同——复习页就是要跟答题页长得一模一样。
         */
        readOnly: Boolean = false,
        /**
         * 树脊：本卡在「材料 → 小题」这条树枝上的位置。
         * null 表示不参与树形卷面（普通单题 / 分屏右栏 / 复习页），左侧那条线不画。
         */
        rail: RailMark? = null,
        /** 材料节点标题里的序号（「材料 2」）；null 时退回光秃秃的「材料」 */
        materialLabel: String? = null,
        /** 材料正文是否已收起：标题栏照留，正文不占地方（折叠状态由适配器持有） */
        materialCollapsed: Boolean = false
    ) {
        val ctx = b.root.context
        val res = ctx.resources
        val density = res.displayMetrics.density
        val revealNow = revealAnswers || q.id in revealed

        // ---- 卷面底色 ----
        // 对齐参考应用：整页白底通铺，题目不再是独立圆角卡片，题与题之间只有一条细线。
        b.root.setBackgroundColor(
            ContextCompat.getColor(ctx, if (night) R.color.fb_night_sheet_bg else R.color.fb_sheet_bg)
        )
        b.vDivider.setBackgroundColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_question_divider else R.color.fb_question_divider
            )
        )
        b.vDivider.visibility = if (showDivider) View.VISIBLE else View.GONE

        // ---- 模块说明卡（卷面内嵌版；单题浏览里它单独占一页，见 QuestionPagerAdapter）----
        if (moduleHeader == null) {
            b.moduleHeader.root.visibility = View.GONE
        } else {
            b.moduleHeader.root.visibility = View.VISIBLE
            bindModuleCard(b.moduleHeader, moduleHeader, night)
        }

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
        // 标记用「细线星」、收藏用「星」——参考应用里这两颗星形状不同，选中后各自填实
        b.btnMark.setImageResource(
            if (isMarked) R.drawable.ic_fb_star_filled else R.drawable.ic_fb_star_outline
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
        // 复习页只读：三个动作图标没有承接方，留着点了没反应，干脆不显示
        val actionVisibility = if (readOnly) View.GONE else View.VISIBLE
        b.btnMark.visibility = actionVisibility
        b.btnScratch.visibility = actionVisibility
        b.btnFavorite.visibility = actionVisibility

        // ---- 题干（字号随「更多」面板缩放；基准 16sp / 额外行距 7dp）----
        // 题干里可能夹图（图形推理、言语的材料图）：图片摘到 stemMedia 里渲染，
        // 文字留在 TextView 上——这样字号缩放、行距、笔迹锚点（取题干顶边）全都还是原来那套。
        val stem = MaterialRenderer.splitInline(q.stem)
        b.tvStem.text = stem.text
        b.tvStem.visibility = if (stem.text.isBlank()) View.GONE else View.VISIBLE
        b.tvStem.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_text_body else R.color.fb_text_body
            )
        )
        b.tvStem.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
        b.tvStem.setLineSpacing(7f * density * fontScale, 1f)
        if (stem.hasImages) {
            b.stemMedia.visibility = View.VISIBLE
            MaterialRenderer.renderOptionImages(
                ctx, b.stemMedia, stem.images, night, maxHeightDp = STEM_IMAGE_MAX_HEIGHT_DP
            )
        } else {
            b.stemMedia.visibility = View.GONE
            b.stemMedia.removeAllViews()
        }

        // ---- 材料 ----
        // 树形卷面里「材料」是父节点：标签栏（材料 N · 几题 · 折叠箭头）+ 浅灰面板正文。
        // 同一则材料的后续题不再重复渲染（由适配器算好 hideMaterial 传进来）
        val showMaterial = q.material.isNotBlank() && !hideMaterial
        if (showMaterial) {
            b.materialBox.visibility = View.VISIBLE
            bindMaterialHead(b, q, materialLabel, materialGroupSize, materialCollapsed, night, cb)
            // 「弹出材料」：窄屏里材料被卡宽压着，表格 / 图表都看不清，单独弹出来看。
            // 但只有**一则材料带多道小题**才留这个入口——单题材料在卡上已完整展开，
            // 弹出等于把同一段材料再看一遍；分屏右栏（onMaterialExpand = null）同理不留。
            val canExpandMaterial = cb.onMaterialExpand != null && materialGroupSize > 1
            b.btnMaterialExpand.visibility = if (canExpandMaterial) View.VISIBLE else View.GONE
            if (canExpandMaterial) {
                val metaColor = ContextCompat.getColor(
                    ctx, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
                )
                b.tvMaterialExpand.setTextColor(metaColor)
                b.icMaterialExpand.setColorFilter(metaColor)
                b.btnMaterialExpand.setOnClickListener { cb.onMaterialExpand?.invoke(q) }
            }
            if (materialCollapsed) {
                // 收起时不留正文（连渲染都不做：长材料里的图表、配图很吃内存）
                b.materialContent.removeAllViews()
            } else {
                MaterialRenderer.render(ctx, b.materialContent, q.material, night)
            }
        } else {
            b.materialBox.visibility = View.GONE
            b.materialContent.removeAllViews()
        }

        // ---- 树脊 ----
        // 只有「材料组」里的题才画：材料头一个实心节点，题号行一个空心节点，
        // 组内几道题共用一条竖线（组首 / 组中 / 组尾的接线方式见 RailMark）。
        bindRail(b, showMaterial, rail, night)

        // ---- 选项 ----
        b.optionsBox.removeAllViews()
        buildOptions(b, q, answers, fontScale, cb, night, revealNow, readOnly)

        // ---- 作答小结 + 解析（交卷后 / 练习模式即时判分后）----
        // 版式与配色集中在 AnswerBlock，分屏右栏、解析页用的是同一份
        if (revealNow) {
            AnswerBlock.bind(
                b.answerBlock, q, answers[q.id] ?: "", policy, night
            )
        } else {
            AnswerBlock.hide(b.answerBlock)
        }
    }

    // ---------------- 材料节点 / 树脊 ----------------

    /** 题干配图的最高高度：比选项图（240dp）高一档，比材料图（420dp）矮一档 */
    private const val STEM_IMAGE_MAX_HEIGHT_DP = 360f

    /**
     * 材料节点标题栏：`● 材料 2 · 3 题 … ⤢ 弹出材料 ⌄`。
     *
     * 整行可点 = 收起 / 展开材料正文（[Callbacks.onMaterialToggle] 非空时才有这个入口）。
     * 箭头朝下表示「正文在下、已展开」，朝右表示「已收起」——树上的 disclosure 三角，
     * 用 chevron 是这套设计系统里唯一的折叠语言（首页试卷树也是它）。
     */
    private fun bindMaterialHead(
        b: ItemQuestionPageBinding,
        q: Question,
        label: String?,
        groupSize: Int,
        collapsed: Boolean,
        night: Boolean,
        cb: Callbacks
    ) {
        val ctx = b.root.context
        b.tvMaterialTag.text = label ?: ctx.getString(R.string.tag_material)
        b.tvMaterialTag.setBackgroundResource(
            if (night) R.drawable.bg_fb_material_tag_night else R.drawable.bg_fb_material_tag
        )
        b.tvMaterialTag.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_tag_text else R.color.fb_type_tag_text
            )
        )

        // 题量只在「一则材料带多道小题」时写出来；一材料一题重复「1 题」纯属噪音
        b.tvMaterialCount.text = ctx.getString(R.string.tree_material_count, groupSize)
        b.tvMaterialCount.visibility = if (groupSize > 1) View.VISIBLE else View.GONE
        b.tvMaterialCount.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_text_meta else R.color.fb_text_meta
            )
        )

        b.materialPanel.setBackgroundResource(
            if (night) R.drawable.bg_fb_material_panel_night else R.drawable.bg_fb_material_panel
        )
        b.materialPanel.visibility = if (collapsed) View.GONE else View.VISIBLE

        val canToggle = cb.onMaterialToggle != null
        b.btnMaterialToggle.visibility = if (canToggle) View.VISIBLE else View.GONE
        if (canToggle) {
            // 不用动画：重绑时直接落到目标角度，免得每次滑回来都「转一下」
            b.btnMaterialToggle.rotation = if (collapsed) -90f else 0f
            b.btnMaterialToggle.contentDescription = ctx.getString(
                if (collapsed) R.string.tree_material_expand else R.string.material_collapse
            )
            b.materialHead.setOnClickListener { cb.onMaterialToggle?.invoke(q) }
            b.materialHead.isClickable = true
        } else {
            b.materialHead.setOnClickListener(null)
            b.materialHead.isClickable = false
        }
    }

    /**
     * 把树脊交给 [TreeRailView] 画。
     *
     * 节点顺序就是纵向顺序：材料头在上、题号行在下；两者之间的线由组件自己连。
     * 不属于材料组（[rail] 为 null）时传空列表——组件什么都不画，但**栏位留着**，
     * 卷面里普通题与材料组题的正文左边界才不会跳来跳去。
     */
    private fun bindRail(
        b: ItemQuestionPageBinding,
        showMaterial: Boolean,
        rail: RailMark?,
        night: Boolean
    ) {
        if (rail == null) {
            b.treeRail.bind(emptyList(), false, false, night)
            return
        }
        val nodes = ArrayList<TreeRailView.Node>(2)
        if (rail.materialNode && showMaterial) nodes += TreeRailView.Node(b.materialHead, true)
        nodes += TreeRailView.Node(b.questionActionBar, false)
        b.treeRail.bind(nodes, rail.above, rail.below, night)
    }

    // ---------------- 选项 ----------------

    private fun buildOptions(
        b: ItemQuestionPageBinding,
        q: Question,
        answers: MutableMap<Long, String>,
        fontScale: Float,
        cb: Callbacks,
        night: Boolean,
        revealNow: Boolean,
        readOnly: Boolean = false
    ) {
        val ctx = b.root.context
        val inflater = LayoutInflater.from(ctx)
        val multi = q.type == QuestionType.MULTI || q.type == QuestionType.UNCERTAIN
        val selected = parseKeys(answers[q.id])
        // 即时判分的着色基准：正确项 = 绿，选错的项 = 红
        val correctKeys = if (revealNow) revealKeys(q) else emptySet()
        val wrongSelected = if (revealNow) selected.filter { it !in correctKeys }.toSet() else emptySet()

        /** 选中 / 取消某一项：单选换项、多选增删，然后就地刷新徽标 */
        fun pick(key: String) {
            val k = key.uppercase()
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

        /**
         * 打叉排除一个**已选**项：排除与「已选」互斥，答案要跟着撤
         * （单选清空、多选只删这一项），否则会出现「灰掉的项还计分」。
         */
        fun unpick(key: String) {
            if (!selected.remove(key.uppercase())) return
            answers[q.id] = selected.sorted().joinToString(",")
            refreshSelection(b, q, answers, night)
            cb.onAnswerChanged(q)
        }

        // 图形推理那种「每个选项就是一张图」的题：横排一行、图按格宽等比缩小，
        // 四个图放一起才比得出来；文字选项仍旧单列。
        if (isImageOnlyOptions(q)) {
            val revealFor: (String) -> Reveal = { key ->
                when {
                    revealNow && correctKeys.isNotEmpty() && key in correctKeys -> Reveal.RIGHT
                    revealNow && key in wrongSelected -> Reveal.WRONG
                    else -> Reveal.NONE
                }
            }
            b.optionsBox.addView(
                buildImageOptionsRow(
                    b.optionsBox, q, { selected }, night, fontScale,
                    revealFor = revealFor,
                    onPick = { pick(it) },
                    onCrossChanged = { key, crossed -> if (crossed) unpick(key) },
                    readOnly = readOnly
                )
            )
            return
        }

        /** 绑定单个选项行 */
        fun bindOption(opt: com.lytree.qbank.data.model.Option): ItemOptionBinding {
            val item = ItemOptionBinding.inflate(inflater, b.optionsBox, false)
            item.tvKey.text = opt.key
            item.tvKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * fontScale)
            val key = opt.key.uppercase()

            /** 按「当前选中 + 打叉 + 判分」重画这一行（长按 / 点叉后要就地更新，不整卡重绑） */
            fun refresh() = applyOptionState(
                item,
                selected.contains(key),
                OptionCross.isCrossed(q.id, key),
                night,
                when {
                    revealNow && correctKeys.isNotEmpty() && key in correctKeys -> Reveal.RIGHT
                    revealNow && key in wrongSelected -> Reveal.WRONG
                    else -> Reveal.NONE
                }
            )

            /** 图上长按 = 整行长按（翻转排除态 → 撤答案 → 重画），两条路径别分叉 */
            fun toggleCrossOnImage() {
                if (OptionCross.toggle(q.id, opt.key)) unpick(key)
                refresh()
            }

            bindOptionBody(
                item, opt.text, night, fontScale,
                onImageLongPress = { toggleCrossOnImage() }
            )
            refresh()

            // 把 item 绑定挂到子 view 上，作答后就地刷新徽标时不必重新解析下标
            item.root.tag = item
            // 只读复习：不挂手势，已判分的圈色停在本来的位置，点一下不会把答案「改成」别的项
            if (!readOnly) {
                bindOptionInteractions(
                    item, q.id, opt.key,
                    onPick = { pick(opt.key) },
                    onCrossChanged = { crossed ->
                        if (crossed) unpick(key)
                        refresh()
                    }
                )
            }
            return item
        }

        // 文字选项一律单列（对齐参考应用手机端：即便「2个 / 3个」这种超短选项也是竖排一列）
        q.options.forEach { opt -> b.optionsBox.addView(bindOption(opt).root) }
    }

    /**
     * 选项是不是「每项一张图、没有正文」的题型（图形推理最典型）。
     *
     * 只有 2~4 项才横排：再多一行就挤成缩略图墙了，还不如单列看大图。
     */
    fun isImageOnlyOptions(q: Question): Boolean {
        if (q.options.size !in 2..4) return false
        return q.options.all { opt ->
            val c = MaterialRenderer.splitOption(opt.text)
            c.text.isBlank() && c.images.size == 1
        }
    }

    /**
     * 图片选项横向排成一行：每格宽度均分，图按格宽等比缩放（高不过 1.5 倍格宽，也要矮一档），
     * A/B/C/D 徽标放在图上方居中——图一高，徽标在左侧会把整行撑歪。
     *
     * 手势：**点图 = 看大图，点字母圈 / 行内空白 = 选中，长按 = 打叉**。
     * 图比格子窄时按原图宽度居中放着，不为了填满格子把图拉糊。
     *
     * [selectedProvider] 传函数而不是快照：行内的「打叉 / 恢复」是就地重画的，
     * 得快照会读到打叉前的选中态（叉还在、圈还是蓝的）。
     *
     * [applyRowPadding] 为 false 时不吃行内留白：分屏右栏整页已经有左右留白了。
     */
    fun buildImageOptionsRow(
        container: ViewGroup,
        q: Question,
        selectedProvider: () -> Set<String>,
        night: Boolean,
        fontScale: Float = 1f,
        revealFor: ((String) -> Reveal)? = null,
        applyRowPadding: Boolean = true,
        onPick: (String) -> Unit,
        /** 打叉状态变了：调用方负责把已选项从答案里撤掉（重画由本行自己做） */
        onCrossChanged: ((key: String, crossed: Boolean) -> Unit)? = null,
        /** 只读复习：不挂点选 / 打叉手势，行内只保留判分着色 */
        readOnly: Boolean = false
    ): View {
        val ctx = container.context
        val res = ctx.resources
        val dm = res.displayMetrics
        val inflater = LayoutInflater.from(ctx)
        val gap = (8 * dm.density).toInt()
        val padStart = if (applyRowPadding) res.getDimensionPixelSize(R.dimen.fb_tree_option_pad_start) else 0
        val padEnd = if (applyRowPadding) res.getDimensionPixelSize(R.dimen.fb_content_padding_h) else 0
        val padV = res.getDimensionPixelSize(R.dimen.fb_option_row_padding_v)
        val count = q.options.size.takeIf { it > 0 } ?: 1
        val cellWidth = ((dm.widthPixels - padStart - padEnd - gap * (count - 1)) / count)
            .coerceAtLeast(gap * 4)
        // 高度按格宽放一档半：正方图就是格子宽，长条图不会把一行顶得过高
        val maxHeightDp = (cellWidth / dm.density * 1.5f).coerceIn(72f, 180f)
        val circle = res.getDimensionPixelSize(R.dimen.fb_option_circle)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(padStart, padV, padEnd, padV)
        }

        q.options.forEachIndexed { index, opt ->
            val item = ItemOptionBinding.inflate(inflater, row, false)
            item.root.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                if (index < count - 1) marginEnd = gap
            }
            (item.root as LinearLayout).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, (4 * dm.density).toInt(), 0, (2 * dm.density).toInt())
            }
            item.tvKey.text = opt.key
            item.tvKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * fontScale)
            item.tvKey.layoutParams = LinearLayout.LayoutParams(circle, circle).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (6 * dm.density).toInt()
            }
            // 竖排之后正文容器要自己撑满格宽（XML 里是 0dp + weight，那是横向行用的）
            item.optionBody.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            item.tvText.visibility = View.GONE

            val content = MaterialRenderer.splitOption(opt.text)
            item.optionMedia.visibility = View.VISIBLE

            val key = opt.key.uppercase()
            // 竖排之后叉号落到图下方居中（XML 里那条 8dp 起始边距是给横排用的）
            item.ivCross.layoutParams = LinearLayout.LayoutParams(
                (16 * dm.density).toInt(), (16 * dm.density).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (4 * dm.density).toInt()
            }

            fun refresh() = applyOptionState(
                item,
                selectedProvider().contains(key),
                OptionCross.isCrossed(q.id, key),
                night,
                revealFor?.invoke(key) ?: Reveal.NONE
            )

            /** 图上长按 = 整行长按（翻转排除态 → 交给调用方撤答案 → 重画） */
            fun toggleCrossOnImage() {
                val crossed = OptionCross.toggle(q.id, opt.key)
                onCrossChanged?.invoke(key, crossed)
                refresh()
            }

            // 图按格宽缩、点图看大图；字母圈照样是选中，长按（含图上的长按）是打叉
            MaterialRenderer.renderOptionImages(
                ctx, item.optionMedia, content.images, night,
                compact = true, widthPx = cellWidth, maxHeightDp = maxHeightDp,
                onLongPress = { toggleCrossOnImage() }
            )
            refresh()
            item.root.tag = item
            if (!readOnly) {
                bindOptionInteractions(
                    item, q.id, opt.key,
                    onPick = { onPick(key) },
                    onCrossChanged = { crossed ->
                        onCrossChanged?.invoke(key, crossed)
                        refresh()
                    }
                )
            }
            row.addView(item.root)
        }
        return row
    }

    /**
     * 选项正文：文字 + 配图。
     *
     * 图形推理那种「四个选项就是四张图」的题，选项单元格里放的就是图片地址
     * （写法与材料图片一致，见 [MaterialRenderer.splitOption]）。图片多大就显示多大
     * （只缩不放、保持原比例），最高 240dp；**点图看大图**，点其余部分是选这一项。
     * 配图上的长按交给 [onImageLongPress]，否则图片可点会把整行的「长按打叉」吃掉。
     *
     * 分屏页（[SplitExamActivity]）也走这里，两处口径一致，别各写一份。
     */
    fun bindOptionBody(
        item: ItemOptionBinding,
        text: String,
        night: Boolean,
        fontScale: Float = 1f,
        onImageLongPress: (() -> Unit)? = null
    ) {
        val ctx = item.root.context
        val content = MaterialRenderer.splitOption(text)
        item.tvText.text = content.text
        item.tvText.setTextColor(
            ContextCompat.getColor(
                ctx, if (night) R.color.fb_night_text_body else R.color.fb_option_text
            )
        )
        item.tvText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
        item.tvText.setLineSpacing(5f * ctx.resources.displayMetrics.density * fontScale, 1f)
        item.tvText.visibility = if (content.text.isNotEmpty()) View.VISIBLE else View.GONE

        // 徽标对齐：带图的选项顶对齐——图一高，圆标居中会飘到图中间，A/B/C/D 反而对不上号；
        // 纯文字仍整块居中（多行时圈落在文字块中间，与原来的口径一致）。
        // 只有图时图顶有 6dp 间距（见 MaterialRenderer.buildImage），徽标跟着让 6dp 才对得齐。
        val hasImage = content.hasImages
        (item.root as? LinearLayout)?.gravity =
            if (hasImage) Gravity.TOP else Gravity.CENTER_VERTICAL
        (item.tvKey.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            val top = if (hasImage && content.text.isEmpty()) {
                (6 * ctx.resources.displayMetrics.density).toInt()
            } else {
                0
            }
            if (lp.topMargin != top) {
                lp.topMargin = top
                item.tvKey.layoutParams = lp
            }
        }

        if (content.hasImages) {
            item.optionMedia.visibility = View.VISIBLE
            MaterialRenderer.renderOptionImages(
                ctx, item.optionMedia, content.images, night,
                onLongPress = onImageLongPress
            )
        } else {
            item.optionMedia.visibility = View.GONE
            item.optionMedia.removeAllViews()
        }
    }

    /**
     * 选项行的两个手势：**点 = 选这一项，长按 = 打叉排除**。
     *
     * 打叉是「排除法」：划掉的项变灰、打叉，**不能再点选**；想反悔就再点一下（或再长按），
     * 立刻回到可选状态。所以点叉不等于作答，[onPick] 只在没划掉时才走。
     *
     * 这里只管手势与状态位，**不碰答案**：划掉的是已选项时由调用方在 [onCrossChanged] 里
     * 把它从答案里撤掉（单选清空 / 多选删这一项），并按自己的口径重画这一行——
     * 主答题页、图选项一行、分屏页共用同一套手势，各家的重画逻辑才不会漂。
     */
    fun bindOptionInteractions(
        item: ItemOptionBinding,
        questionId: Long,
        key: String,
        onPick: () -> Unit,
        onCrossChanged: (crossed: Boolean) -> Unit
    ) {
        item.root.setOnClickListener {
            if (OptionCross.isCrossed(questionId, key)) {
                // 点在叉掉的项上 = 撤销排除，回到可选状态（不是作答）
                OptionCross.toggle(questionId, key)
                onCrossChanged(false)
            } else {
                onPick()
            }
        }
        item.root.setOnLongClickListener { v ->
            val crossed = OptionCross.toggle(questionId, key)
            onCrossChanged(crossed)
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            true
        }
    }

    /**
     * 一行选项的完整外观：徽标 + 正文 + 配图 + 叉号，三个状态一起算，别分两次调用。
     *
     * 划掉（排除）后的样子：徽标换成**灰色实心圆**（灰点，与「未选」的描边圈区分开），
     * 正文压删除线并置灰，配图压暗，行尾亮一个叉号。
     *
     * 优先级：判对绿 / 判错红 > 已选蓝 > 已排除灰点 > 未选描边圈——
     * 判分结果是答案本身，不能被「已排除」盖掉（选项先划掉后来又被选中的极端情况也按这个来）。
     */
    fun applyOptionState(
        item: ItemOptionBinding,
        selected: Boolean,
        crossed: Boolean,
        night: Boolean = false,
        reveal: Reveal = Reveal.NONE
    ) {
        val ctx = item.root.context
        val gray = ContextCompat.getColor(
            ctx, if (night) R.color.fb_night_text_meta else R.color.fb_text_placeholder
        )

        if (crossed && !selected && reveal == Reveal.NONE) {
            // 灰点：实心浅灰圆 + 更深一档的字母，一眼看得出「这项我排除了」
            item.tvKey.setBackgroundResource(
                if (night) R.drawable.bg_fb_circle_crossed_night else R.drawable.bg_fb_circle_crossed
            )
            item.tvKey.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (night) R.color.fb_night_circle_crossed_text else R.color.fb_circle_crossed_text
                )
            )
        } else {
            applyCircleState(item, selected, night, reveal)
        }

        // 正文：删除线 + 灰；恢复时把两样一起撤掉
        val flags = item.tvText.paintFlags
        item.tvText.paintFlags = if (crossed) {
            flags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            flags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        item.tvText.setTextColor(
            if (crossed) {
                gray
            } else {
                ContextCompat.getColor(
                    ctx, if (night) R.color.fb_night_text_body else R.color.fb_option_text
                )
            }
        )
        // 图上看不出删除线，压暗 + 叉号一起说明「这项排除了」
        item.optionMedia.alpha = if (crossed) 0.3f else 1f
        item.ivCross.visibility = if (crossed) View.VISIBLE else View.GONE
        item.ivCross.setColorFilter(gray)
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
                applyState(child.tag as ItemOptionBinding, q.id, selected, night)
            } else if (child is ViewGroup) {
                // 横排容器：两列老版式，或「图选项一行」（见 [buildImageOptionsRow]）；
                // 逐个刷新（占位 View 没 tag 会跳过）
                for (j in 0 until child.childCount) {
                    val inner = child.getChildAt(j)
                    (inner.tag as? ItemOptionBinding)?.let { applyState(it, q.id, selected, night) }
                }
            }
        }
    }

    private fun applyState(
        item: ItemOptionBinding,
        questionId: Long,
        selected: Set<String>,
        night: Boolean
    ) {
        val key = item.tvKey.text.toString().uppercase()
        applyOptionState(item, selected.contains(key), OptionCross.isCrossed(questionId, key), night)
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
