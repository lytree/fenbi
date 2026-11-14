package com.lytree.qbank.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.lytree.qbank.R

/**
 * 卷面树脊（题块左侧那一条竖线 + 节点圆点）。
 *
 * 卷面是「大类 → 材料 → 小题」的三级结构，但每一级在列表里都是独立的题块，
 * 光靠缩进看不出「这几道题属于上面那份材料」。这条脊线就是补这个信息：
 *
 * ```
 * │  ●  材料 2 · 3 题            ← 实心节点 = 材料（父节点）
 * │  ┌──────────────────────┐
 * │  │ 材料正文 / 表格 / 配图 │
 * │  └──────────────────────┘
 * │  ○  106. [单选题]  ☆ ✎ ★   ← 空心节点 = 子题
 * │  ○  107. [单选题]
 * └─ ○  108. [单选题]            ← 组尾：线在最后一个节点处收住
 * ```
 *
 * **节点位置由锚点 View 决定，不写死 y 值**：锚点可以是材料头，也可以是题号行，
 * 高度随材料长短 / 字号 / 配图加载而变，`onDraw` 每次都按锚点当前的位置重算，
 * 图片晚一点加载完、字号换一档、横竖屏切换都不会让圆点飘到别处。
 *
 * 组件只负责画，不知道题号也不知道分组——「组首 / 组中 / 组尾」由调用方
 * （[QuestionCardBinder]）翻译成 [bind] 的三个参数。
 */
class TreeRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * 一个节点：锚在哪个 View 上、是不是「父节点」。
     *
     * [primary] 为真时画成**实心主蓝**（材料），否则画成**空心圈**（小题）——
     * 这是整棵树唯一的层级线索，颜色只有一种，层次靠「实心 / 空心」区分。
     */
    class Node(val anchor: View, val primary: Boolean)

    private var nodes: List<Node> = emptyList()
    /** 脊线要不要从本块顶边接进来（组内非首题：接上一块） */
    private var connectAbove = false
    /** 脊线要不要连到本块底边（组内非末题：连下一块） */
    private var connectBelow = false
    private var night = false

    private val density = resources.displayMetrics.density
    private val railX = resources.getDimension(R.dimen.fb_tree_rail_x)
    private val railWidth = resources.getDimension(R.dimen.fb_tree_rail_line)
    private val nodeRadius = resources.getDimension(R.dimen.fb_tree_node_size) / 2f
    /** 节点外圈留白：把脊线在圆点处截断，节点才「立」在线上 */
    private val nodeHalo = 1.5f * density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = railWidth
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = railWidth
    }

    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        applyColors()
    }

    private fun applyColors() {
        val ctx = context
        linePaint.color = ContextCompat.getColor(
            ctx, if (night) R.color.fb_night_tree_rail else R.color.fb_tree_rail
        )
        ringPaint.color = linePaint.color
        fillPaint.color = ContextCompat.getColor(
            ctx, if (night) R.color.fb_night_tree_node_fill else R.color.fb_tree_node_fill
        )
        primaryPaint.color = ContextCompat.getColor(ctx, R.color.fb_primary)
    }

    /**
     * 绑定本块在树里的位置。
     *
     * @param nodes 纵向排列的节点（材料头在前、题号行在后），顺序即绘制顺序
     * @param above 顶边接不接线
     * @param below 底边连不连线
     */
    fun bind(nodes: List<Node>, above: Boolean, below: Boolean, night: Boolean) {
        this.nodes = nodes
        connectAbove = above
        connectBelow = below
        this.night = night
        applyColors()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty()) return

        // 只认「真的在屏上」的锚点：材料收起 / 换版式时锚点会 GONE，别拿高度 0 去算
        val spots = ArrayList<Pair<Float, Boolean>>(nodes.size)
        for (node in nodes) {
            if (node.anchor.visibility != VISIBLE || node.anchor.height <= 0) continue
            spots += centerYOf(node.anchor) to node.primary
        }
        if (spots.isEmpty()) return

        val top = if (connectAbove) 0f else spots.first().first
        val bottom = if (connectBelow) height.toFloat() else spots.last().first
        if (bottom > top) canvas.drawLine(railX, top, railX, bottom, linePaint)

        spots.forEach { (y, primary) ->
            // 先铺一圈卷面同色，把脊线在圆点处截断
            canvas.drawCircle(railX, y, nodeRadius + nodeHalo, fillPaint)
            if (primary) {
                canvas.drawCircle(railX, y, nodeRadius, primaryPaint)
            } else {
                canvas.drawCircle(railX, y, nodeRadius - railWidth / 2f, ringPaint)
            }
        }
    }

    /**
     * 锚点圆心在本视图坐标系里的 y。
     *
     * 锚点与树脊是**兄弟**（脊在左栏、内容在右栏，两栏顶边对齐），所以把锚点
     * 一路往上累加 `top`，走到本视图的父容器为止就够了；不依赖具体是哪一层布局。
     */
    private fun centerYOf(anchor: View): Float {
        var y = anchor.height / 2f
        var v: View = anchor
        while (true) {
            y += v.top
            val p = v.parent as? View ?: break
            if (p === parent) break
            v = p
        }
        return y
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 锚点的高度 / 位置跟着材料与字号变，重排一次就重画一次
        if (changed) invalidate()
    }
}
