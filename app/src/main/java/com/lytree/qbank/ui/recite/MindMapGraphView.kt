package com.lytree.qbank.ui.recite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
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
 * · 交互：双指缩放、单指拖动、点节点折叠 / 展开、双击在「适应屏幕」和 1:1 之间切
 * · 折叠的分支不参与布局，所以一页 60 多条要点也不会一次全画出来
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
    private val cornerRadius = dp(8f)
    private val badgeRadius = dp(7.5f)
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
        color = color(R.color.fb_tree_rail)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.fb_card_bg)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // 配色（导图页跟着 App 的日间配色走，背诵这块不区分夜间）
    private val cRootBg = color(R.color.fb_primary_soft)
    private val cBranchBg = color(R.color.fb_card_bg)
    private val cBranchLine = color(R.color.fb_tree_rail)
    private val cLeafBg = color(R.color.fb_card_bg)
    private val cRootText = color(R.color.fb_primary)
    private val cBranchText = color(R.color.fb_text_title)
    private val cLeafText = color(R.color.fb_text_secondary)
    private val cPrimary = color(R.color.fb_primary)

    // ---------------------------------------------------------------- 数据

    /** 原始树（页面传进来的一页书） */
    private var roots: List<MindMapNode> = emptyList()

    /** 折叠起来的节点路径（"0", "0.1" 这种），默认折到第 3 层往里 */
    private val collapsed = HashSet<String>()

    private var layoutRoots: List<LNode> = emptyList()

    private val columnWidths = ArrayList<Float>()
    private val columnX = ArrayList<Float>()
    private var contentWidth = 0f
    private var contentHeight = 0f

    private var scale = 1f
    private var translateX = 0f
    private var translateY = 0f

    /** 第一次拿到尺寸 / 换主题后需要重新适应屏幕 */
    private var fitPending = true

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
            override fun onDown(e: MotionEvent): Boolean = true

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

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                nodeAt(e.x, e.y)?.let { toggle(it) }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale < 0.95f) zoomTo(1f, e.x, e.y) else fitToView()
                return true
            }
        }
    )

    init {
        isClickable = true
        badgeText.textSize = sp(9f)
    }

    // ---------------------------------------------------------------- 对外

    fun setNodes(nodes: List<MindMapNode>) {
        roots = nodes
        collapsed.clear()
        // 默认摊到第 3 层：中心主题 → 分支 → 分支的要点，再往里折起来
        forEachSource(roots) { node, path, depth ->
            if (depth >= DEFAULT_OPEN_DEPTH && node.hasChildren) collapsed.add(path)
        }
        relayout()
        fitPending = true
        // 已经有尺寸时直接适应；否则等 onSizeChanged 里那次（对话框首帧就走这条）
        if (width > 0 && height > 0) post { fitToView() }
        invalidate()
    }

    /** 全部展开（一页最多 60 多条，放得下但会很密，靠缩放看） */
    fun expandAll() {
        collapsed.clear()
        relayout()
        invalidate()
    }

    /** 只看主干：所有带子节点的都折起来，只剩中心主题 */
    fun collapseBranches() {
        collapsed.clear()
        forEachSource(roots) { node, path, _ ->
            if (node.hasChildren) collapsed.add(path)
        }
        relayout()
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

    private fun relayout() {
        columnWidths.clear()
        columnX.clear()

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

        forEachLayout { node ->
            while (columnWidths.size <= node.depth) columnWidths.add(0f)
            columnWidths[node.depth] = max(columnWidths[node.depth], node.width)
        }
        var x = 0f
        columnWidths.forEach { w ->
            columnX.add(x)
            x += w + columnGap
        }
        contentWidth = x - columnGap

        var top = 0f
        built.forEach { root ->
            measureSubtree(root)
            position(root, top)
            top += root.subtreeHeight + rowGap
        }
        contentHeight = top - rowGap

        forEachLayout { node ->
            node.x = columnX[node.depth]
            node.rect.set(node.x, node.y, node.x + node.width, node.y + node.height)
        }
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
        // 颜色必须在这里定：StaticLayout 会拷一份 TextPaint，画的时候改原对象不生效
        val paint = TextPaint(if (node.depth <= 1) boldText else normalText).apply {
            textSize = sp(if (node.depth <= 1) 13f else 12f)
            color = when (node.depth) {
                0 -> cRootText
                1 -> cBranchText
                else -> cLeafText
            }
        }
        node.paint = paint
        node.layout = StaticLayout.Builder
            .obtain(node.text, 0, node.text.length, paint, textMaxWidth.toInt())
            .setIncludePad(false)
            .setLineSpacing(dp(2f), 1f)
            .build()
        node.width = max(min(textMaxWidth, node.layout.width.toFloat()) + padH * 2, minNodeWidth)
        node.height = node.layout.height + padV * 2
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

    private fun forEachLayout(block: (LNode) -> Unit) {
        fun walk(node: LNode) {
            block(node)
            node.children.forEach { walk(it) }
        }
        layoutRoots.forEach { walk(it) }
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
        if (layoutRoots.isEmpty() || contentWidth <= 0f) return
        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scale, scale)

        forEachLayout { node ->
            node.children.forEach { child -> drawLink(canvas, node, child) }
        }
        forEachLayout { drawNode(canvas, it) }

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

    private fun drawNode(canvas: Canvas, node: LNode) {
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

        canvas.save()
        canvas.translate(node.rect.left + padH, node.rect.top + padV)
        node.layout.draw(canvas)
        canvas.restore()

        if (node.hiddenCount > 0) {
            val cx = node.rect.right + badgeRadius + dp(1f)
            val cy = node.rect.centerY()
            badgePaint.color = cPrimary
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
        if (fitPending || oldw == 0 || oldh == 0) fitToView()
    }

    private fun nodeAt(px: Float, py: Float): LNode? {
        if (scale <= 0f) return null
        val x = (px - translateX) / scale
        val y = (py - translateY) / scale
        var hit: LNode? = null
        forEachLayout { node ->
            if (hit == null && node.rect.contains(x, y)) hit = node
        }
        return hit
    }

    private fun toggle(node: LNode) {
        // 叶子节点没什么可折的；有隐藏子节点的点一下展开
        val hasKids = node.hiddenCount > 0 || node.children.isNotEmpty()
        if (!hasKids) return
        if (node.path in collapsed) collapsed.remove(node.path) else collapsed.add(node.path)
        relayout()
        invalidate()
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
    }
}
