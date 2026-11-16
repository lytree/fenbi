package com.lytree.qbank.ui.recite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Parcelable
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.data.model.MindMapNode
import kotlin.math.max
import kotlin.math.min

/**
 * 图形化导图：中心主题在左，分支向右展开，节点之间用曲线连起来。
 *
 * 原来的导图页是把层级拍成「缩进的大纲行」，读起来像目录，但看不出结构。
 * 这里按真正的导图画：每个节点是一个圆角块，父子之间连贝塞尔曲线，
 * 左边一列是根（一页书常有 2~3 个中心主题），右边逐级铺开。
 *
 * · 布局：同层同列（列宽取该层最宽节点），纵向按子树高度排，父节点对齐子节点块的垂直中心
 * · 多根分栏：一页书常有十几个中心主题，全竖排会把图拉成十几倍高、fit 后字极小，
 *   所以按根的顺序切到几栏并排，栏数按画布比例挑（[MindMapLanePacker]），同一栏里的根仍共用一套列宽
 * · 交互：双指缩放、单指拖动、点节点折叠 / 展开、双击在「适应屏幕」和 1:1 之间切
 * · 折叠的分支不参与布局，所以一页 60 多条要点也不会一次全画出来
 * · 缩得很小时分级画（LOD）：先省折叠徽标，再只留首行文字，最后只剩色块——
 *   一页 1194 个节点全展开时也不至于每帧画一千多个多行布局
 * · 长按节点把整句交给宿主（弹窗看全文）；画布不自己弹窗，免得和缩放 / 拖动抢手势
 * · 夜读：日 / 夜两套令牌整体换色，开关由宿主持久化（[setNightMode]）
 * · 转屏、弹窗重建后，折叠状态、栏数与缩放位置按原样回来（[onSaveInstanceState]）
 * · 坐标只在内存里算，收回用 [MindMapNode] 树本身，不改资产
 */
class MindMapGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ---------------------------------------------------------------- 尺寸

    private val density = resources.displayMetrics.density

    /** 节点文字最多铺多宽，超过就折行 */
    private val textMaxWidth = dp(190f)
    private val padH = dp(11f)
    private val padV = dp(6f)
    private val minNodeWidth = dp(46f)
    private val columnGap = dp(28f)
    private val rowGap = dp(9f)

    /** 同一栏里两个「中心主题」之间的距离，比普通行距大一点，一眼能分开 */
    private val rootGap = dp(18f)

    /** 栏与栏之间的距离 */
    private val laneGap = dp(24f)
    private val cornerRadius = dp(8f)
    private val badgeRadius = dp(7.5f)

    /** 折叠徽标在节点右侧多占的宽度（画在 rect 外面，排内容宽度时要留出来） */
    private val badgeSlot = badgeRadius * 2 + dp(2f)
    private val contentPad = dp(10f)

    // ---------------------------------------------------------------- 画笔

    private val normalText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }
    private val boldText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /**
     * 夜读开关。
     *
     * 本 App 的夜间不是系统 DayNight（见 themes.xml 的说明），只在需要的地方局部换色；
     * 背诵模块其余页面没有夜间，但导图是全屏看图的页面，晚上盯着白底很刺眼，
     * 所以这一页自带一个开关（宿主放在标题栏右上角，选择存库，下次进来沿用）。
     */
    private var night = false

    // 配色：日间 / 夜间两套令牌，换肤时由 [applyPalette] 整体改写
    private var cCanvasBg = 0
    private var cRootBg = 0
    private var cBranchBg = 0
    private var cLeafBg = 0
    private var cBranchLine = 0
    private var cLink = 0
    private var cRootText = 0
    private var cBranchText = 0
    private var cLeafText = 0
    private var cAccent = 0
    private var cOnAccent = 0

    // ---------------------------------------------------------------- 数据

    /** 原始树（页面传进来的一页书） */
    private var roots: List<MindMapNode> = emptyList()

    /** 折叠起来的节点路径（"0", "0.1" 这种），默认折到第 3 层往里 */
    private val collapsed = HashSet<String>()

    private var layoutRoots: List<LNode> = emptyList()

    /**
     * 根节点分成几栏。
     *
     * OCR 还原出来的书页常有 19~22 个中心主题（一条「分配制度」被切成三块很常见），
     * 全竖排成一列时 fit 之后高度是宽度的十几倍，字缩到看不清。
     * 按根拆成几栏并排，整幅图更接近竖屏比例，同样的屏幕能显示大得多的字。
     */
    private var laneCount = 1

    /** 这一轮分栏是按兜底比例猜的（画布还没有尺寸），拿到尺寸后要按真实比例重分一次 */
    private var lanesNeedSize = false

    private var contentWidth = 0f
    private var contentHeight = 0f

    private var scale = 1f
    private var translateX = 0f
    private var translateY = 0f

    /** 第一次拿到尺寸 / 换主题后需要重新适应屏幕 */
    private var fitPending = true

    /** 甩动后的惯性滚动（点的坐标就是 translate 值，直接滚它） */
    private val overScroller = OverScroller(context)

    /** 字号档位（与背诵页共用 recite_font_level） */
    private var fontLevel = 0

    /** 长按一个节点：把整句交给宿主（弹窗看全文）；为空时只震一下，不做事 */
    var onNodeLongPress: ((String) -> Unit)? = null

    /**
     * 量过的文字布局按「层级 + 字号 + 正文」缓存。
     * 展开全部 / 改字号 / 折叠都会整棵重排，同一条文字再量一遍纯属浪费——
     * 一页 1194 个节点，重排一次就是一千多次 StaticLayout 构建。
     */
    private val layoutCache = LruCache<String, StaticLayout>(LAYOUT_CACHE_SIZE)

    /** 从 [onRestoreInstanceState] 恢复过视图：首帧不要再 fit，否则转屏回来位置被重置 */
    private var restoredState = false

    private val linkPath = Path()

    // ---------------------------------------------------------------- 手势

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomTo(scale * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // 手指按下就刹住惯性，否则拖拽开始时画布还在自己滚
                overScroller.forceFinished(true)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // 双指缩放时不要同时平移，否则会跟着抖
                if (scaleDetector.isInProgress) return true
                translateX -= distanceX
                translateY -= distanceY
                clampTranslation()
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // 用 TapUp 而不是 TapConfirmed：确认要等双击超时，点一下折叠要慢半拍
                nodeAt(e.x, e.y)?.let { toggle(it) }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // 长按是「读全文」：缩到看不清字的时候，点开看整句比放大再找快
                val node = nodeAt(e.x, e.y) ?: return
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onNodeLongPress?.invoke(node.text)
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (e1 == null || scaleDetector.isInProgress) return false
                val w = contentWidth * scale
                val h = contentHeight * scale
                if (w <= width && h <= height) return false
                val minX = if (w <= width) ((width - w) / 2f).toInt()
                else (width - w - contentPad).toInt()
                val maxX = if (w <= width) ((width - w) / 2f).toInt() else contentPad.toInt()
                val minY = if (h <= height) ((height - h) / 2f).toInt()
                else (height - h - contentPad).toInt()
                val maxY = if (h <= height) ((height - h) / 2f).toInt() else contentPad.toInt()
                overScroller.fling(
                    translateX.toInt(), translateY.toInt(),
                    -velocityX.toInt(), -velocityY.toInt(),
                    minX, maxX, minY, maxY,
                )
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 点在节点上时别缩放：那两下已经被 TapUp 当成折叠 / 展开了
                if (nodeAt(e.x, e.y) != null) return true
                if (scale < 0.95f) zoomTo(1f, e.x, e.y) else fitToView()
                return true
            }
        }
    )

    init {
        isClickable = true
        badgeText.textSize = sp(9f)
        applyPalette()
    }

    // ---------------------------------------------------------------- 对外

    fun setNodes(nodes: List<MindMapNode>) {
        roots = nodes
        restoredState = false
        collapsed.clear()
        // 默认摊到第 3 层：中心主题 → 分支 → 分支的要点，再往里折起来
        forEachSource(roots) { node, path, depth ->
            if (depth >= DEFAULT_OPEN_DEPTH && node.hasChildren) collapsed.add(path)
        }
        relayout()
        fitPending = true
        // 已经有尺寸时直接适应；否则等 onSizeChanged 里那次（对话框首帧就走这条）
        // 恢复过状态的这帧不 fit：转屏回来要停在用户上次看的位置
        if (width > 0 && height > 0) post { if (!restoredState) fitToView() }
        invalidate()
    }

    /** 全部展开（一页最多 60 多条，放得下但会很密，靠缩放看） */
    fun expandAll() {
        collapsed.clear()
        relayout()
        invalidate()
    }

    /** 接背诵页的 A小 / A中 / A大：节点跟着重新量宽，字大了也不会溢出 */
    fun setFontLevel(level: Int) {
        if (fontLevel == level) return
        fontLevel = level
        relayout()
        if (width > 0 && height > 0) post { fitToView() }
        invalidate()
    }

    /**
     * 夜读：整块换深色（画布底、节点块、连线、徽标一起换），只换颜色不动排版。
     *
     * 文字颜色是烤进 [StaticLayout] 里的（它拷了一份 TextPaint），所以换肤必须把
     * 布局缓存整批清掉重建，否则会拿回白底配色的旧布局。栏数沿用当前的，
     * 换肤不该让图重排一遍。
     */
    fun setNightMode(on: Boolean) {
        if (night == on) return
        night = on
        applyPalette()
        layoutCache.evictAll()
        relayout(recomputeLanes = false)
        invalidate()
    }

    private fun applyPalette() {
        cCanvasBg = color(if (night) R.color.fb_night_sheet_bg else R.color.fb_page_bg)
        cRootBg = color(if (night) R.color.fb_night_tag_bg else R.color.fb_primary_soft)
        cBranchBg = color(if (night) R.color.fb_night_card else R.color.fb_card_bg)
        // 叶子与分支同一底色：一眼看上去「一块一块」比两种浅色更清楚
        cLeafBg = cBranchBg
        // 描边在夜间收敛到「看得出边界」为止：divider 太淡（1.15:1），用 circle_stroke 那一档
        cBranchLine = color(if (night) R.color.fb_night_circle_stroke else R.color.fb_tree_rail)
        // 连线比描边亮一档：夜间描边要收敛（不然每个块都像被框住），但连线得看得见
        cLink = color(if (night) R.color.fb_night_tree_rail else R.color.fb_tree_rail)
        cRootText = color(if (night) R.color.fb_night_tag_text else R.color.fb_primary)
        cBranchText = color(if (night) R.color.fb_night_text else R.color.fb_text_title)
        cLeafText = color(if (night) R.color.fb_night_text_body else R.color.fb_text_secondary)
        cAccent = color(if (night) R.color.fb_night_tag_text else R.color.fb_primary)
        // 徽标是「实心强调色 + 反白数字」，夜间反白要换成深底，否则深底浅字看不清
        cOnAccent = color(if (night) R.color.fb_night_sheet_bg else R.color.fb_card_bg)
        linkPaint.color = cLink
        badgeText.color = cOnAccent
    }

    /** 只看主干：所有带子节点的都折起来，只剩中心主题 */
    fun collapseBranches() {
        collapsed.clear()
        forEachSource(roots) { node, path, _ ->
            if (node.hasChildren) collapsed.add(path)
        }
        relayout(recomputeLanes = false)
        invalidate()
    }

    fun fitToView() {
        if (width <= 0 || height <= 0 || contentWidth <= 0f || contentHeight <= 0f) {
            fitPending = true
            return
        }
        val availW = (width - contentPad * 2).coerceAtLeast(1f)
        val availH = (height - contentPad * 2).coerceAtLeast(1f)
        scale = min(availW / contentWidth, availH / contentHeight)
            .coerceIn(MIN_SCALE, MAX_FIT_SCALE)
        translateX = (width - contentWidth * scale) / 2f
        translateY = (height - contentHeight * scale) / 2f
        fitPending = false
        invalidate()
    }

    /**
     * 以画布中心为锚放大 / 缩小一档。
     *
     * 双指缩放是主路径，但躺着单手看的时候腾不出两根手指；底部给两个按钮兜底。
     * 缩放范围与手势一致（[MIN_SCALE] ~ [MAX_SCALE]），到边界就停住不弹回。
     */
    fun zoomBy(factor: Float) {
        if (contentWidth <= 0f || width <= 0 || height <= 0) return
        zoomTo(scale * factor, width / 2f, height / 2f)
    }

    // ---------------------------------------------------------------- 布局

    /** 布局用的一层包装：记住深度、路径与最终位置 */
    private class LNode(
        val text: String,
        val depth: Int,
        val path: String,
    ) {
        val children = ArrayList<LNode>()
        lateinit var layout: StaticLayout
        lateinit var paint: TextPaint
        var width = 0f
        var height = 0f

        /** 折叠起来没画出来的后代数量（0 = 没有隐藏） */
        var hiddenCount = 0
        var x = 0f
        var y = 0f
        var subtreeHeight = 0f
        val rect = RectF()

        /** 量过才有 [layout]；绘制前用它挡一下，别让没量的节点把整页画崩 */
        val isMeasured: Boolean get() = ::layout.isInitialized
    }

    /**
     * 重排。
     *
     * @param recomputeLanes 是否重新决定分几栏。折叠 / 展开时传 false：栏数跟着变的话，
     *        每点开一个分支整页都会重新分栏，视野大跳；换内容、换字号时才重算。
     */
    private fun relayout(recomputeLanes: Boolean = true) {
        val built = ArrayList<LNode>(roots.size)
        roots.forEachIndexed { index, root ->
            built.add(build(root, 0, index.toString()))
        }
        layoutRoots = built
        if (built.isEmpty()) {
            contentWidth = 0f
            contentHeight = 0f
            return
        }

        // 整棵树都要量：列宽和行高都靠 measure 的结果，只量子节点以外的部分会让
        // 没量到的节点在绘制时拿不到 StaticLayout（lateinit 没初始化 → 直接崩）
        forEachLayout { measure(it) }
        built.forEach { measureSubtree(it) }
        if (recomputeLanes) {
            laneCount = chooseLaneCount(built)
            lanesNeedSize = width <= 0 || height <= 0
        }

        var laneX = 0f
        var maxLaneHeight = 0f
        splitIntoChunks(built, laneCount).forEachIndexed { laneIndex, chunk ->
            if (laneIndex > 0) laneX += laneGap
            // 同一栏里的根共用一套列宽（同深度对齐），不同栏互不影响
            val widths = laneColumnWidths(chunk)
            val xs = FloatArray(widths.size)
            var x = 0f
            widths.forEachIndexed { depth, w ->
                xs[depth] = x
                x += w + columnGap
            }
            var laneWidth = x - columnGap

            var top = 0f
            chunk.forEach { root ->
                position(root, top)
                top += root.subtreeHeight + rootGap
            }

            chunk.forEach { root ->
                walk(root) { node ->
                    node.x = laneX + if (node.depth < xs.size) xs[node.depth] else 0f
                    node.rect.set(node.x, node.y, node.x + node.width, node.y + node.height)
                }
            }

            // 折叠徽标画在节点右侧、不在 rect 里：最右一列有徽标时要把这块留进内容宽度，
            // 否则 fit 之后「6」「7」这种小圆点正好被屏幕边缘切掉（实测第一版就是这样）
            var right = laneX + laneWidth
            chunk.forEach { root ->
                walk(root) {
                    if (it.hiddenCount > 0) right = max(right, it.rect.right + badgeSlot)
                }
            }
            laneWidth = max(laneWidth, right - laneX)

            laneX += laneWidth
            maxLaneHeight = max(maxLaneHeight, top - rootGap)
        }
        contentWidth = laneX
        contentHeight = maxLaneHeight
    }

    /** 按顺序把根切到几栏里（等高优先，每栏至少一个根） */
    private fun splitIntoChunks(built: List<LNode>, lanes: Int): List<List<LNode>> {
        val heights = FloatArray(built.size) { built[it].subtreeHeight }
        return MindMapLanePacker.split(heights, lanes, rootGap).map { range ->
            built.subList(range.first, range.last + 1)
        }
    }

    /** 挑栏数：让整幅图接近画布的比例，fit 之后字最大 */
    private fun chooseLaneCount(built: List<LNode>): Int {
        if (built.size <= 1) return 1
        val heights = FloatArray(built.size) { built[it].subtreeHeight }
        // 目标比例跟着画布走：竖屏约 2:1，横屏 / 平板接近 1:1。
        // 首帧还没有尺寸（对话框刚建）时按竖屏兜底，反正这时候的 fit 也会等尺寸。
        val ratio = if (width > 0 && height > 0) {
            (height.toFloat() / width).coerceIn(MIN_TARGET_RATIO, MAX_TARGET_RATIO)
        } else {
            TARGET_HEIGHT_RATIO
        }
        return MindMapLanePacker.pickLaneCount(
            heights = heights,
            maxLanes = MAX_LANES,
            gap = rootGap,
            laneGap = laneGap,
            targetRatio = ratio,
            laneWidth = { range ->
                val widths = laneColumnWidths(built.subList(range.first, range.last + 1))
                widths.fold(0f) { acc, w -> acc + w + columnGap } - columnGap
            },
        )
    }

    /** 一栏里每个深度最宽的节点（深度从该根的 0 起算） */
    private fun laneColumnWidths(chunk: List<LNode>): List<Float> {
        val widths = ArrayList<Float>()
        chunk.forEach { root ->
            walk(root) { node ->
                while (widths.size <= node.depth) widths.add(0f)
                widths[node.depth] = max(widths[node.depth], node.width)
            }
        }
        return widths
    }

    private fun build(node: MindMapNode, depth: Int, path: String): LNode {
        val out = LNode(node.text, depth, path)
        if (path !in collapsed) {
            node.children.forEachIndexed { index, child ->
                out.children.add(build(child, depth + 1, "$path.$index"))
            }
        } else {
            out.hiddenCount = countDescendants(node)
        }
        return out
    }

    private fun measure(node: LNode) {
        node.layout = layoutFor(node.text, node.depth)
        // 颜色必须在这里定：StaticLayout 会拷一份 TextPaint，画的时候改原对象不生效
        node.paint = node.layout.paint
        node.width = max(min(textMaxWidth, node.layout.width.toFloat()) + padH * 2, minNodeWidth)
        node.height = node.layout.height + padV * 2
    }

    /** 按「层级 + 字号 + 正文」取布局；同一句在展开 / 折叠之间来回时不重复构建 */
    private fun layoutFor(text: String, depth: Int): StaticLayout {
        val bucket = if (depth <= 1) depth else 2
        val sizePx = sp(if (depth <= 1) 13f + fontLevel else 12f + fontLevel)
        val key = "$bucket|${sizePx.toInt()}|$text"
        layoutCache.get(key)?.let { return it }

        val paint = TextPaint(if (depth <= 1) boldText else normalText).apply {
            textSize = sizePx
            color = when (bucket) {
                0 -> cRootText
                1 -> cBranchText
                else -> cLeafText
            }
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, textMaxWidth.toInt())
            .setIncludePad(false)
            .setLineSpacing(dp(2f), 1f)
            .build()
        layoutCache.put(key, layout)
        return layout
    }

    private fun measureSubtree(node: LNode): Float {
        if (node.children.isEmpty()) {
            node.subtreeHeight = node.height
            return node.subtreeHeight
        }
        var sum = 0f
        node.children.forEach { sum += measureSubtree(it) }
        sum += rowGap * (node.children.size - 1)
        node.subtreeHeight = max(node.height, sum)
        return node.subtreeHeight
    }

    private fun position(node: LNode, top: Float) {
        if (node.children.isEmpty()) {
            node.y = top + (node.subtreeHeight - node.height) / 2f
            return
        }
        var block = rowGap * (node.children.size - 1)
        node.children.forEach { block += it.subtreeHeight }
        var y = top + (node.subtreeHeight - block) / 2f
        node.children.forEach { child ->
            position(child, y)
            y += child.subtreeHeight + rowGap
        }
        val first = node.children.first()
        val last = node.children.last()
        val centerY = (first.y + last.y + last.height) / 2f
        node.y = (centerY - node.height / 2f)
            .coerceIn(top, (top + node.subtreeHeight - node.height).coerceAtLeast(top))
    }

    private fun walk(node: LNode, block: (LNode) -> Unit) {
        block(node)
        node.children.forEach { walk(it, block) }
    }

    private fun forEachLayout(block: (LNode) -> Unit) {
        layoutRoots.forEach { walk(it, block) }
    }

    private fun forEachSource(
        nodes: List<MindMapNode>,
        path: String = "",
        depth: Int = 0,
        block: (MindMapNode, String, Int) -> Unit,
    ) {
        nodes.forEachIndexed { index, node ->
            val p = if (path.isEmpty()) index.toString() else "$path.$index"
            block(node, p, depth)
            forEachSource(node.children, p, depth + 1, block)
        }
    }

    private fun countDescendants(node: MindMapNode): Int =
        node.children.size + node.children.sumOf { countDescendants(it) }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        // 画布底自己铺：夜间是深底，靠页面透出来的白底会露馅
        canvas.drawColor(cCanvasBg)
        if (layoutRoots.isEmpty() || contentWidth <= 0f) return
        // 缩得越小画得越省：一页 1194 个节点全展开时，逐条多行布局是主要开销
        val lod = when {
            scale >= TEXT_FULL_SCALE -> LOD_FULL
            scale >= TEXT_LINE_SCALE -> LOD_LINE
            else -> LOD_BLOCK
        }
        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scale, scale)

        forEachLayout { node ->
            node.children.forEach { child -> drawLink(canvas, node, child) }
        }
        forEachLayout { drawNode(canvas, it, lod) }

        canvas.restore()
    }

    private fun drawLink(canvas: Canvas, parent: LNode, child: LNode) {
        val sx = parent.x + parent.width
        val sy = parent.y + parent.height / 2f
        val ex = child.x
        val ey = child.y + child.height / 2f
        val mx = (sx + ex) / 2f
        linkPath.reset()
        linkPath.moveTo(sx, sy)
        linkPath.cubicTo(mx, sy, mx, ey, ex, ey)
        canvas.drawPath(linkPath, linkPaint)
    }

    private fun drawNode(canvas: Canvas, node: LNode, lod: Int) {
        // 兜底：万一有节点没走 measure（以后改布局容易漏），宁可这一块不画也别崩
        if (!node.isMeasured) return
        val bg = when (node.depth) {
            0 -> cRootBg
            1 -> cBranchBg
            else -> cLeafBg
        }
        fillPaint.color = bg
        canvas.drawRoundRect(node.rect, cornerRadius, cornerRadius, fillPaint)
        if (node.depth == 1) {
            strokePaint.color = cBranchLine
            canvas.drawRoundRect(node.rect, cornerRadius, cornerRadius, strokePaint)
        }

        when (lod) {
            LOD_FULL -> {
                canvas.save()
                canvas.translate(node.rect.left + padH, node.rect.top + padV)
                node.layout.draw(canvas)
                canvas.restore()
            }
            LOD_LINE -> {
                // 只看首行：多行布局整块 draw 在极小缩放下是白画，取第一行直接描
                val end = node.layout.getLineEnd(0)
                if (end > 0) {
                    val baseline = node.rect.top + padV + node.layout.getLineBaseline(0)
                    canvas.drawText(
                        node.layout.text, 0, end,
                        node.rect.left + padH, baseline, node.paint,
                    )
                }
            }
            // LOD_BLOCK：只剩色块，连折叠徽标也省了——几像素的圆点在这个比例下只是噪点
        }

        if (lod == LOD_FULL && node.hiddenCount > 0) {
            val cx = node.rect.right + badgeRadius + dp(1f)
            val cy = node.rect.centerY()
            badgePaint.color = cAccent
            canvas.drawCircle(cx, cy, badgeRadius, badgePaint)
            val label = if (node.hiddenCount > 99) "99+" else node.hiddenCount.toString()
            badgeText.textSize = sp(if (label.length > 2) 7.5f else 9f)
            canvas.drawText(label, cx, cy + badgeText.textSize * 0.36f, badgeText)
        }
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 画布里自己处理平移，别让外层（列表）抢走滑动手势
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 恢复过状态的：按新尺寸夹一下位置就行。重新 fit 会把用户上次看的区域冲掉
        if (restoredState) {
            if (restorePending) {
                applyPendingViewport()
            } else {
                clampTranslation()
                invalidate()
            }
            return
        }
        // 首帧分栏时还没有画布尺寸，比例只能按竖屏兜底；这一帧按真实比例再分一次，
        // 平板横屏下才不会被排成一条竖线
        if (lanesNeedSize) {
            lanesNeedSize = false
            relayout()
        }
        if (fitPending || oldw == 0 || oldh == 0) fitToView()
    }

    // ---------------------------------------------------------------- 视图状态

    override fun onSaveInstanceState(): Parcelable {
        val bundle = Bundle()
        bundle.putParcelable(KEY_SUPER, super.onSaveInstanceState())
        bundle.putStringArrayList(KEY_COLLAPSED, ArrayList(collapsed))
        bundle.putFloatArray(KEY_VIEW, floatArrayOf(scale, translateX, translateY))
        bundle.putInt(KEY_FONT, fontLevel)
        bundle.putInt(KEY_ROOTS, roots.size)
        bundle.putInt(KEY_LANES, laneCount)
        return bundle
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) {
            super.onRestoreInstanceState(state)
            return
        }
        @Suppress("DEPRECATION")
        super.onRestoreInstanceState(state.getParcelable(KEY_SUPER))
        // 换了一页书（根数对不上）就别把上一页的折叠状态套过来
        if (roots.isEmpty() || state.getInt(KEY_ROOTS) != roots.size) return

        fontLevel = state.getInt(KEY_FONT, fontLevel)
        collapsed.clear()
        state.getStringArrayList(KEY_COLLAPSED)?.let { collapsed.addAll(it) }
        // 栏数沿用转屏前的：换方向时整体重排已经很跳了，别再重新分一次栏
        laneCount = state.getInt(KEY_LANES, 0).coerceAtLeast(1)
        relayout(recomputeLanes = false)

        val view = state.getFloatArray(KEY_VIEW)
        if (view != null && view.size == 3 && contentWidth > 0f) {
            // 此刻还没量到 View 尺寸，先存着，等 onSizeChanged 再夹位置
            pendingScale = view[0]
            pendingX = view[1]
            pendingY = view[2]
            restorePending = true
        }
        // 没存到视角（比如旧版本存的状态）就照常适应屏幕
        restoredState = restorePending
        if (restorePending) fitPending = false
        if (width > 0 && height > 0) applyPendingViewport()
        invalidate()
    }

    /** [onRestoreInstanceState] 里拿到的缩放 / 位移，等画布有尺寸了再套上去 */
    private var pendingScale = Float.NaN
    private var pendingX = 0f
    private var pendingY = 0f
    private var restorePending = false

    private fun applyPendingViewport() {
        if (!restorePending) return
        restorePending = false
        scale = pendingScale.coerceIn(MIN_SCALE, MAX_SCALE)
        translateX = pendingX
        translateY = pendingY
        clampTranslation()
        invalidate()
    }

    override fun computeScroll() {
        if (overScroller.computeScrollOffset()) {
            translateX = overScroller.currX.toFloat()
            translateY = overScroller.currY.toFloat()
            invalidate()
        }
    }

    private fun nodeAt(px: Float, py: Float): LNode? {
        if (scale <= 0f) return null
        val x = (px - translateX) / scale
        val y = (py - translateY) / scale
        // 节点本身可能只有 20~30dp 高：在内容坐标里把命中区撑到 48dp（屏幕口径），
        // 缩小看时按比例放大，手指不用瞄准
        val minSize = dp(48f) / scale
        var best: LNode? = null
        var bestDist = Float.MAX_VALUE
        forEachLayout { node ->
            val rect = node.rect
            val dx = ((minSize - rect.width()) / 2f).coerceAtLeast(0f)
            val dy = ((minSize - rect.height()) / 2f).coerceAtLeast(0f)
            if (x >= rect.left - dx && x <= rect.right + dx &&
                y >= rect.top - dy && y <= rect.bottom + dy
            ) {
                // 命中区重叠时取离中心最近的那个，避免总是选到上层的大节点
                val cx = x - rect.centerX()
                val cy = y - rect.centerY()
                val dist = cx * cx + cy * cy
                if (dist < bestDist) {
                    bestDist = dist
                    best = node
                }
            }
        }
        return best
    }

    private fun toggle(node: LNode) {
        // 叶子节点没什么可折的；有隐藏子节点的点一下展开
        val hasKids = node.hiddenCount > 0 || node.children.isNotEmpty()
        if (!hasKids) return
        // 以被点的节点为锚：重排后让它留在原来的屏幕位置，视野不会跳走
        val anchorX = translateX + node.rect.left * scale
        val anchorY = translateY + node.rect.top * scale
        val path = node.path
        if (!collapsed.remove(path)) collapsed.add(path)
        // 栏数不重算：展开一个分支就让整页重新分栏的话，用户刚记住的位置全乱了
        relayout(recomputeLanes = false)
        findLayoutByPath(path)?.let { moved ->
            translateX = anchorX - moved.rect.left * scale
            translateY = anchorY - moved.rect.top * scale
            clampTranslation()
        }
        invalidate()
    }

    private fun findLayoutByPath(path: String): LNode? {
        var found: LNode? = null
        forEachLayout { if (found == null && it.path == path) found = it }
        return found
    }

    private fun zoomTo(target: Float, focusX: Float, focusY: Float) {
        val next = target.coerceIn(MIN_SCALE, MAX_SCALE)
        if (next == scale) return
        val k = next / scale
        translateX = focusX - (focusX - translateX) * k
        translateY = focusY - (focusY - translateY) * k
        scale = next
        clampTranslation()
        invalidate()
    }

    private fun clampTranslation() {
        val w = contentWidth * scale
        val h = contentHeight * scale
        translateX = if (w <= width) {
            (width - w) / 2f
        } else {
            translateX.coerceIn(width - w - contentPad, contentPad)
        }
        translateY = if (h <= height) {
            (height - h) / 2f
        } else {
            translateY.coerceIn(height - h - contentPad, contentPad)
        }
    }

    // ---------------------------------------------------------------- 小工具

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun color(res: Int): Int = ContextCompat.getColor(context, res)

    private companion object {
        /** 默认展开到第 3 层（0 中心主题 / 1 分支 / 2 要点） */
        const val DEFAULT_OPEN_DEPTH = 2
        const val MIN_SCALE = 0.2f
        const val MAX_SCALE = 3f

        /** 适应屏幕时最多放大到 1.2 倍，节点少的时候也不至于占满整屏 */
        const val MAX_FIT_SCALE = 1.2f

        /** 最多并排几栏：再多每栏就窄得放不下一句话了 */
        const val MAX_LANES = 6

        /**
         * 分栏的目标长宽比（高 / 宽）。
         *
         * 手机竖屏可视区约 1:2，图按 2:1 排时 fit 的缩放最小、
         * 浪费在另一维上的空白最少，也就等于字最大。
         */
        const val TARGET_HEIGHT_RATIO = 2f

        /** 目标比例的上下限：太扁 / 太窄的画布就按边界算，别把图排成一条 */
        const val MIN_TARGET_RATIO = 0.8f
        const val MAX_TARGET_RATIO = 2.6f

        /** 缩到这个比例以下就不再画整块文字，只描首行；再往下只剩色块（0.22 只有极限缩小才触发） */
        const val TEXT_FULL_SCALE = 0.6f
        const val TEXT_LINE_SCALE = 0.22f

        const val LOD_FULL = 0
        const val LOD_LINE = 1
        const val LOD_BLOCK = 2

        /** 文字布局缓存条数：一页 1194 个节点，512 条够覆盖常见来回展开的范围 */
        const val LAYOUT_CACHE_SIZE = 512

        const val KEY_SUPER = "super"
        const val KEY_COLLAPSED = "collapsed"
        const val KEY_VIEW = "view"
        const val KEY_FONT = "font"
        const val KEY_ROOTS = "roots"
        const val KEY_LANES = "lanes"
    }
}
