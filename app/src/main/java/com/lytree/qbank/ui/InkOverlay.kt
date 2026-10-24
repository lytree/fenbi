package com.lytree.qbank.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.lytree.qbank.data.db.QBankDb
import org.json.JSONArray
import org.json.JSONObject

/**
 * 题目遮盖层（可写）。
 *
 * 需求来自刷题场景：做题时想在题干/选项上圈画、写算式 —— 手边没有触控笔时也得能写，
 * 而且写着写着不能把下面的选项点掉、不能把题翻过去。所以这一层不是「透明的浮层」，
 * 而是**盖在题目上的一张可写薄纸**：
 *
 * 1. **遮盖**——[shade] 决定纸的厚度（关 / 浅 / 中 / 深四档，夜间自动换成深色纸）。
 *    盖上去题目文字仍在、但明显淡了一层，一眼能看出「现在是写画模式」。
 * 2. **全拦截 + 右侧滚动通道**——[writeMode] 打开时，这一层吞掉所有触摸（手指、笔都算），
 *    事件不会再穿透到选项和 ViewPager2：写字不会误选、不会翻页。
 *    唯一的例外是屏幕右边缘那条细轨道：落在那里不画线，而是滚动下面的题目容器
 *    （见 [scrollTargetResolver]）——长题干折行多，写画时也得能翻下去。
 * 3. **手指直接写**——不再需要「允许手指绘制」开关，没笔也能写；多指（掌托）不会打断笔迹。
 * 4. **弃写仍可见**——退出写画模式后笔迹留在原处（[writeMode] = false 只是不再拦截），
 *    标注不会一「完成」就消失。
 * 5. **按题持久化**——笔迹存 [QBankDb]，切题往返、退出重进、隔天再来都还在，可以回头接着标。
 * 6. **内容锚定**——笔迹不是「钉在屏幕上」，而是钉在题目卡上（[anchorSource] 给出每张卡
 *    在本层里的位置）。滚动卷面、切浏览模式、答题页重新排版，笔迹都跟着那道题走；
 *    这正是「临时标记」和「真标注」的区别。
 * 7. **按笔换色**——每一笔自己记颜色（[PenColors]），当前笔色可在工具条上循环切换，
 *    也可在设置页统一指定。
 */
class InkOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 遮盖档位：关 / 浅 / 中 / 深（日间铺白、夜间铺黑，值越大题目越淡） */
    enum class Shade(val label: String, val alpha: Int) {
        NONE("关", 0),
        LIGHT("浅", 24),
        MEDIUM("中", 58),
        DEEP("深", 110);

        fun next(): Shade = entries[(ordinal + 1) % entries.size]
    }

    /**
     * 一张题目卡在本层坐标系里的位置。
     *
     * 笔迹存的是「相对这张卡左上角」的内容坐标，画的时候再加上 [x]/[y]：
     * 卷面一滚，卡的位置变了，笔迹自然跟着走，不需要像旧实现那样手工平移每一笔。
     */
    class Anchor(val questionId: Long, val x: Float, val y: Float)

    /** 一笔：若干采样点 + 每点的压感 + 这一笔的颜色（内容坐标） */
    private class Stroke {
        val points = ArrayList<FloatArray>()   // [x, y, pressure]
        var color: Int = 0                     // 0 = 旧数据，按默认红笔渲染
    }

    /** 一道题的笔迹集合；从库里懒加载，读过的题（哪怕是空）都缓存着，滚动时不重复查库 */
    private class Doc(val questionId: Long) {
        val strokes = ArrayList<Stroke>()
    }

    private val docs = HashMap<Long, Doc>()
    private var current: Stroke? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    /** 撤销 / 清空 / 落库的目标题（当前正在写的那道） */
    private var activeQuestionId = 0L
    /** 当前这一笔落下时，它所属的题目卡在本层里的位置（内容坐标 → 本层坐标的平移量） */
    private var strokeOriginX = 0f
    private var strokeOriginY = 0f

    private val prefs = context.getSharedPreferences(PenColors.PREFS, Context.MODE_PRIVATE)

    /** 遮盖纸的档位，跨题目 / 跨页面记住上次选择 */
    var shade: Shade = Shade.entries.getOrElse(prefs.getInt(KEY_SHADE, Shade.LIGHT.ordinal)) { Shade.LIGHT }
        set(value) {
            if (field == value) return
            field = value
            prefs.edit().putInt(KEY_SHADE, value.ordinal).apply()
            invalidate()
            onShadeChanged?.invoke(value)
        }

    /** 当前笔色：新写的每一笔用它。设置页与工具条读写的是同一份偏好 */
    var penColor: Int = PenColors.load(context)
        set(value) {
            if (field == value) return
            field = value
            PenColors.save(context, value)
            invalidate()
            onColorChanged?.invoke(value)
        }

    /** 是否处于写画模式：true = 铺遮盖纸 + 拦截全部触摸 */
    var writeMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            activePointerId = MotionEvent.INVALID_POINTER_ID
            current = null
            barDragging = false
            barTarget = null
            barLeftEdge = false
            isEnabled = value
            invalidate()
        }

    /** 夜间模式：遮盖纸与笔迹换深色 */
    var night: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 撤销按钮回调 */
    var onStrokeCountChanged: ((Int) -> Unit)? = null
    /** 遮盖档位切换回调（工具条按钮文案跟着变） */
    var onShadeChanged: ((Shade) -> Unit)? = null
    /** 笔色切换回调（工具条上的色点跟着变） */
    var onColorChanged: ((Int) -> Unit)? = null

    /**
     * 内容锚点来源：返回当前屏幕上每一张题目卡（题目 id + 卡片左上角在本层里的坐标）。
     *
     * 接了它 = 笔迹跟着题目走；不接（返回 null / 空表）= 退回旧行为，按屏幕坐标画当前题。
     */
    var anchorSource: (() -> List<Anchor>)? = null

    // ---------------- 滚动通道（右边缘 / 分屏页左边缘） ----------------

    /**
     * 写画模式下手按住右边缘这条轨道，就能滚动题目。
     *
     * 这一层本来吞掉所有触摸，长题干折行多的时候根本翻不下去，只能先「完成」再滚、再进写画。
     * 轨道是唯一的裂缝：落点在轨道上就当滚动条使，其余位置一律画线。
     */
    var scrollTarget: View? = null

    /**
     * 按落点决定滚哪个容器（分屏页左材料 / 右题目各一个），优先于 [scrollTarget]。
     * 参数是触摸点在本层坐标系里的 x、y。
     */
    var scrollTargetResolver: ((Float, Float) -> View?)? = null

    /** 轨道底部让位（px）：给悬浮工具条 / 底部按钮留出空间，避免轨道画到它们背后 */
    var scrollBarBottomInset: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 左边缘是否也放一条滚动手柄。分屏页左右两栏各滚各的，需要两条；
     * 普通页面左边缘是宝贵的书写区，保持默认关闭。
     */
    var leftRailEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 是否把边缘手柄画出来。
     *
     * 竖屏答题页把它关掉：屏幕窄，一条轨道贴着题目文字太抢眼，看着像系统滚动条漏出来了。
     * 关掉只是「不画」，[barTouchWidth] 那条热区仍然有效，贴着屏幕最右边拖还是能滚。
     */
    var railVisible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 右侧可滚边缘的宽度（px）。横屏用 34dp 够手指按；竖屏收窄到 16dp，
     * 只占屏幕最外沿一条，正文里写字不会被当成滚动。
     */
    var barTouchWidth: Float = 34f * resources.displayMetrics.density

    private var barTarget: View? = null
    /** 滚动过程中的位移参照物（滚动容器的第一个子视图） */
    private var barAnchor: View? = null
    private var barPointerId = MotionEvent.INVALID_POINTER_ID
    private var barLastY = 0f
    private var barAccum = 0f
    private var barDragging = false
    /** 正在拖的是左边那条手柄（分屏页材料栏） */
    private var barLeftEdge = false

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PenColors.DEFAULT
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4f
    }

    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 右侧滚动轨道 / 滑块的画笔 */
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barRect = RectF()

    init {
        isEnabled = false   // 默认不拦截，笔迹照常显示
    }

    /** 换绑到某一题：只影响撤销 / 清空 / 落库的目标，画面上所有可见题的笔迹照画 */
    fun bindQuestion(questionId: Long) {
        if (activeQuestionId == questionId) return
        activeQuestionId = questionId
        current = null
        invalidate()
        onStrokeCountChanged?.invoke(strokeCount)
    }

    /**
     * 丢掉笔迹缓存，下次绘制重新读库。
     *
     * 同一道题可以在两个页面里标（答题页 ↔ 资料分析分屏页），回来时不重读就会看到旧笔迹。
     * 已落库的不会丢——每一笔在抬手时就写库了。
     */
    fun reload() {
        docs.clear()
        current = null
        invalidate()
        onStrokeCountChanged?.invoke(strokeCount)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawShade(canvas)

        val anchors = anchorSource?.invoke().orEmpty()
        if (anchors.isEmpty()) {
            // 没接内容锚点（或有页面暂不支持）：退回屏幕坐标，和旧版本行为一致
            activeDoc()?.let { doc ->
                for (s in doc.strokes) drawStroke(canvas, s, 0f, 0f)
            }
        } else {
            for (a in anchors) {
                val doc = docOf(a.questionId)
                if (doc.strokes.isEmpty()) continue
                for (s in doc.strokes) drawStroke(canvas, s, a.x, a.y)
            }
        }
        // 正在写的那一笔：点已经换算成内容坐标，画的时候抬回屏幕
        current?.let { drawStroke(canvas, it, strokeOriginX, strokeOriginY) }
        // 轨道最后画：始终压在纸和笔迹之上，写画时才看得见
        if (writeMode) drawScrollBar(canvas)
    }

    /** 遮盖纸：日间铺一层白、夜间铺一层黑，厚度由 [shade] 决定 */
    private fun drawShade(canvas: Canvas) {
        val alpha = if (writeMode) shade.alpha else 0
        if (alpha <= 0) return
        shadePaint.color = if (night) {
            Color.argb(alpha, 0, 0, 0)
        } else {
            Color.argb(alpha, 255, 255, 255)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
    }

    private fun drawStroke(canvas: Canvas, s: Stroke, dx: Float, dy: Float) {
        if (s.points.isEmpty()) return
        val p = s.points
        strokePaint.color = visibleColor(if (s.color == 0) PenColors.DEFAULT else s.color)

        // 起点画一个圆点：单点也要看得见（点一下就是一个句点）
        strokePaint.style = Paint.Style.FILL
        strokePaint.strokeWidth = baseWidth * (0.4f + 0.6f * p[0][2])
        canvas.drawPoint(p[0][0] + dx, p[0][1] + dy, strokePaint)
        if (p.size == 1) return

        // 之后逐段连线，粗细随压感
        strokePaint.style = Paint.Style.STROKE
        for (i in 1 until p.size) {
            val a = p[i - 1]
            val b = p[i]
            strokePaint.strokeWidth = baseWidth * (0.4f + 0.6f * b[2])
            canvas.drawLine(a[0] + dx, a[1] + dy, b[0] + dx, b[1] + dy, strokePaint)
        }
    }

    private val baseWidth get() = 4f * resources.displayMetrics.density

    /**
     * 夜间卷面是深色的，黑笔 / 深色笔写上去等于没写。
     * 亮度太低时按比例往白抬，抬到刚好看得清，颜色的色相仍保留。
     */
    private fun visibleColor(color: Int): Int {
        if (!night) return color
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        if (lum >= 0.5f) return color
        val f = (0.5f - lum) / 0.5f * 0.85f
        return Color.rgb(
            (r + (255 - r) * f).toInt().coerceIn(0, 255),
            (g + (255 - g) * f).toInt().coerceIn(0, 255),
            (b + (255 - b) * f).toInt().coerceIn(0, 255)
        )
    }

    // ---------------- 题目卡锚点 ----------------

    /**
     * 当前这一笔所属题目的卡片位置。取不到就退回 (0,0)——旧页面 / 极端布局下
     * 至少还能写，只是不跟着内容滚。
     */
    private fun originOfActive(): FloatArray {
        val id = activeQuestionId
        if (id != 0L) {
            anchorSource?.invoke()?.firstOrNull { it.questionId == id }?.let {
                return floatArrayOf(it.x, it.y)
            }
        }
        return floatArrayOf(0f, 0f)
    }

    // ---------------- 滚动手柄（右边缘 / 分屏页左边缘）：绘制 ----------------

    /** 轨道上下边界（本层坐标）；空间不够时返回 null */
    private fun barRange(): Pair<Float, Float>? {
        val top = 10f * resources.displayMetrics.density
        val bottom = height - scrollBarBottomInset.toFloat()
        return if (bottom - top > 80f * resources.displayMetrics.density) top to bottom else null
    }

    /** 某一侧（左/右边缘）该反映哪个容器的滚动进度 */
    private fun barTargetAt(x: Float): View? =
        scrollTargetResolver?.invoke(x, height / 2f) ?: scrollTarget

    private fun drawScrollBar(canvas: Canvas) {
        if (!railVisible) return
        val range = barRange() ?: return
        drawRail(canvas, width - 12f * resources.displayMetrics.density, barTargetAt(width - 1f), false, range)
        // 分屏页左边缘也有一条，滚左边的材料；普通页面左边缘仍然是画布
        if (leftRailEnabled) {
            drawRail(canvas, 12f * resources.displayMetrics.density, barTargetAt(0f), true, range)
        }
    }

    private fun drawRail(
        canvas: Canvas,
        cx: Float,
        t: View?,
        leftEdge: Boolean,
        range: Pair<Float, Float>
    ) {
        if (t == null) return
        if (!t.canScrollVertically(1) && !t.canScrollVertically(-1)) return

        val d = resources.displayMetrics.density
        val (top, bottom) = range
        val railH = bottom - top
        val w = 3.5f * d

        // 轨道底
        barPaint.color = if (night) 0x1FFFFFFF else 0x14000000
        barRect.set(cx - w / 2f, top, cx + w / 2f, bottom)
        canvas.drawRoundRect(barRect, w / 2f, w / 2f, barPaint)

        // 滑块：位置 / 长度按实际滚动进度算，滚不动时退化成一根短柄提示「这里能拖」
        val offset = scrollOffsetOf(t)
        val extent = scrollExtentOf(t)
        val total = scrollRangeOf(t)
        var thumbH = railH * 0.18f
        var fraction = 0f
        if (offset != null && extent != null && total != null && total > 0 && extent > 0) {
            thumbH = (railH * extent / total).coerceIn(railH * 0.10f, railH * 0.55f)
            val span = (total - extent).coerceAtLeast(1)
            fraction = (offset.toFloat() / span).coerceIn(0f, 1f)
        }
        val thumbTop = top + (railH - thumbH) * fraction

        barPaint.color = when {
            barDragging && barLeftEdge == leftEdge ->
                if (night) 0xCCFFFFFF.toInt() else 0xCCF04C4C.toInt()
            night -> 0x88FFFFFF.toInt()
            else -> 0x66000000
        }
        barRect.set(cx - w / 2f, thumbTop, cx + w / 2f, thumbTop + thumbH)
        canvas.drawRoundRect(barRect, w / 2f, w / 2f, barPaint)
    }

    // ---------------- 滚动手柄：滚动 ----------------

    /** 落点是否在左/右边缘的滚动手柄上（工具条背后不算） */
    private fun isOnBar(x: Float, y: Float): Boolean {
        val onRight = x >= width - barTouchWidth
        val onLeft = leftRailEnabled && x <= barTouchWidth
        if (!onRight && !onLeft) return false
        val (top, bottom) = barRange() ?: return false
        return y >= top - 12f * resources.displayMetrics.density &&
            y <= bottom + 12f * resources.displayMetrics.density
    }

    private fun beginBarDrag(e: MotionEvent): Boolean {
        barLeftEdge = e.x < barTouchWidth
        val target = scrollTargetResolver?.invoke(if (barLeftEdge) 0f else width - 1f, e.y)
            ?: scrollTarget
        barTarget = target
        barAnchor = (target as? android.view.ViewGroup)?.getChildAt(0)
        barPointerId = e.getPointerId(0)
        barLastY = e.y
        barAccum = 0f
        barDragging = target != null &&
            (target.canScrollVertically(1) || target.canScrollVertically(-1))
        current = null
        invalidate()
        return true
    }

    /**
     * 手指位移换算成滚动量。
     *
     * 接了内容锚点之后不用再挪笔迹——笔迹是钉在题目卡上的，内容一滚就跟着走；
     * 只有在没接锚点的老页面上，才继续用「手工平移」那套兜底。
     */
    private fun dragBar(e: MotionEvent) {
        val t = barTarget ?: return
        val idx = e.findPointerIndex(barPointerId)
        if (idx < 0) return
        val y = e.getY(idx)
        barAccum -= y - barLastY          // 手指下滑 = 内容上移 = scrollBy 负值
        barLastY = y
        val step = barAccum.toInt()
        if (step == 0) return
        barAccum -= step

        val anchored = anchorSource != null
        // 用容器自己的滚动偏移量算真实位移：滚到顶 / 底时 scrollBy 会被夹住，
        // 按请求量平移笔迹会越描越偏；computeVerticalScrollOffset 是给滚动条用的估算值，也不能用。
        val before = if (anchored) null else contentOffset(t)
        t.scrollBy(0, step)
        val after = if (anchored) null else contentOffset(t)
        if (!anchored) {
            val moved = if (before != null && after != null) after - before else step
            if (before == null || after == null) {
                // 锚点子视图被回收（整卡滚出屏幕），换下一个继续跟
                barAnchor = (t as? android.view.ViewGroup)?.getChildAt(0)
            }
            if (moved != 0) shiftInk(-moved.toFloat())
        }
        invalidate()
    }

    /**
     * 内容已经滚出去多少像素（向下滚为正）。
     *
     * ScrollView / NestedScrollView 滚动时移动的是画布，子视图的 top 不变，只能看 [View.getScrollY]；
     * RecyclerView 则是真的挪动子视图，scrollY 恒为 0，只能看首个可见项的 top。
     */
    private fun contentOffset(t: View): Int? {
        if (t is android.widget.ScrollView) return t.scrollY
        if (t is androidx.core.widget.NestedScrollView) return t.scrollY
        val a = barAnchor ?: return null
        return if (a.parent === t && a.isAttachedToWindow) -a.top else null
    }

    /** 只有「没接锚点」的老页面才需要：把已有笔迹整体平移，让它跟着内容走 */
    private fun shiftInk(dy: Float) {
        if (dy == 0f) return
        val doc = activeDoc() ?: return
        for (s in doc.strokes) for (p in s.points) p[1] += dy
        current?.let { for (p in it.points) p[1] += dy }
    }

    private fun endBarDrag() {
        barDragging = false
        barTarget = null
        barAnchor = null
        barPointerId = MotionEvent.INVALID_POINTER_ID
        if (anchorSource == null) persist()   // 老页面上平移过的笔迹要落库，否则重进又回原位
        invalidate()
    }

    // ---------------- 滚动进度读取 ----------------
    // computeVerticalScroll* 是 View 的 protected 方法，RecyclerView / NestedScrollView 各自重写过，
    // 反射调用会正常走虚分派，拿到的是它们算好的像素值（没有公开 API 可用）。

    private fun scrollOffsetOf(v: View?): Int? = invokeScrollInt(v, OFFSET_M)
    private fun scrollExtentOf(v: View?): Int? = invokeScrollInt(v, EXTENT_M)
    private fun scrollRangeOf(v: View?): Int? = invokeScrollInt(v, RANGE_M)

    private fun invokeScrollInt(v: View?, m: java.lang.reflect.Method?): Int? {
        if (v == null || m == null) return null
        return try {
            m.invoke(v) as? Int
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 写画模式下吞掉一切触摸：既不穿透到选项，也不让父容器翻页 / 滚动
        if (!isEnabled || !writeMode) return false

        // 正在拖右侧轨道：这一手势全程只滚内容，不落笔
        if (barDragging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragBar(event)
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == barPointerId) endBarDrag()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endBarDrag()
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount > 1) return true
                // 右边缘轨道：不画线，交给滚动
                if (isOnBar(event.x, event.y)) return beginBarDrag(event)
                parent?.requestDisallowInterceptTouchEvent(true)
                activePointerId = event.getPointerId(0)
                // 记下这一笔所属卡片的位置，之后所有点都换算成「卡片内容坐标」
                val o = originOfActive()
                strokeOriginX = o[0]
                strokeOriginY = o[1]
                current = Stroke().apply {
                    color = penColor
                    points.add(
                        floatArrayOf(
                            event.x - strokeOriginX,
                            event.y - strokeOriginY,
                            pressureOf(event, 0)
                        )
                    )
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 掌托 / 第二根手指落下：忽略，不打断正在写的那一笔
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val s = current ?: return true
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                // 只取当前笔那一根手指的轨迹，其余触点（掌托）不参与绘制
                for (h in 0 until event.historySize) {
                    s.points.add(
                        floatArrayOf(
                            event.getHistoricalX(idx, h) - strokeOriginX,
                            event.getHistoricalY(idx, h) - strokeOriginY,
                            pressureOf(event, idx, h)
                        )
                    )
                }
                s.points.add(
                    floatArrayOf(
                        event.getX(idx) - strokeOriginX,
                        event.getY(idx) - strokeOriginY,
                        pressureOf(event, idx)
                    )
                )
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起的是别人的手指，不影响这一笔
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val s = current
                val doc = activeDoc()
                if (s != null && doc != null &&
                    event.actionMasked != MotionEvent.ACTION_CANCEL && s.points.isNotEmpty()
                ) {
                    doc.strokes.add(s)
                    persist()
                    onStrokeCountChanged?.invoke(doc.strokes.size)
                }
                current = null
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun pressureOf(e: MotionEvent, pointerIndex: Int, historyIndex: Int = -1): Float {
        val p = if (historyIndex >= 0) {
            e.getHistoricalPressure(pointerIndex, historyIndex)
        } else {
            e.getPressure(pointerIndex)
        }
        return if (p <= 0f) 1f else p.coerceIn(0.1f, 1.5f)
    }

    // ---------------- 撤销 / 清空 / 换档 / 换色 ----------------

    fun undo() {
        val doc = activeDoc() ?: return
        if (doc.strokes.isEmpty()) return
        doc.strokes.removeAt(doc.strokes.size - 1)
        persist()
        invalidate()
        onStrokeCountChanged?.invoke(doc.strokes.size)
    }

    fun clear() {
        val doc = activeDoc() ?: return
        doc.strokes.clear()
        current = null
        persist()
        invalidate()
        onStrokeCountChanged?.invoke(0)
    }

    /** 遮盖档位循环切换：关 → 浅 → 中 → 深 → 关 */
    fun cycleShade() {
        shade = shade.next()
    }

    /** 笔色循环切换：红 → 蓝 → 绿 → 橙 → 黑 → 红 */
    fun cycleColor() {
        penColor = PenColors.next(penColor)
    }

    val strokeCount: Int get() = activeDoc()?.strokes?.size ?: 0

    /** 本题有没有写过东西（用于「清空」按钮置灰之类） */
    val hasInk: Boolean get() = strokeCount > 0

    // ---------------- 持久化 ----------------

    private fun activeDoc(): Doc? =
        if (activeQuestionId == 0L) null else docOf(activeQuestionId)

    /** 每题一份，读过的（包括空）都缓存：滚动时不会反复查库 */
    private fun docOf(questionId: Long): Doc {
        if (questionId == 0L) return EMPTY_DOC
        docs[questionId]?.let { return it }
        val doc = Doc(questionId)
        doc.strokes.addAll(loadStrokes(questionId))
        docs[questionId] = doc
        return doc
    }

    private fun persist() {
        val id = activeQuestionId
        if (id == 0L) return
        QBankDb.get(context).setInk(id, serialize(docOf(id)))
    }

    private fun serialize(doc: Doc): String {
        val root = JSONObject().apply { put("v", 2) }
        val arr = JSONArray()
        for (s in doc.strokes) {
            val o = JSONObject()
            o.put("c", s.color)
            val pts = JSONArray()
            for (p in s.points) {
                pts.put(JSONArray().put(p[0]).put(p[1]).put(p[2]))
            }
            o.put("p", pts)
            arr.put(o)
        }
        root.put("s", arr)
        return root.toString()
    }

    private fun loadStrokes(questionId: Long): List<Stroke> {
        val raw = QBankDb.get(context).getInk(questionId)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return try {
            if (raw.startsWith("{")) parseV2(raw) else parseV1(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseV2(raw: String): List<Stroke> {
        val arr = JSONObject(raw).optJSONArray("s") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            strokeOf(o.optJSONArray("p") ?: JSONArray(), o.optInt("c", 0))
        }
    }

    /**
     * 旧格式：顶层就是一个笔迹数组，点存的是**屏幕坐标**。
     * 那条老实现里进写画前会把题目卡顶到内容区最上面，所以屏幕坐标 ≈ 卡片内容坐标，
     * 直接当内容坐标读进来即可（个别写得靠下的笔迹会有一点点偏移，可以擦掉重画）。
     */
    private fun parseV1(raw: String): List<Stroke> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            strokeOf(o.optJSONArray("p") ?: JSONArray(), 0)
        }
    }

    private fun strokeOf(pts: JSONArray, color: Int): Stroke? {
        if (pts.length() == 0) return null
        return Stroke().apply {
            this.color = color
            for (j in 0 until pts.length()) {
                val pa = pts.optJSONArray(j) ?: continue
                points.add(
                    floatArrayOf(
                        pa.optDouble(0, 0.0).toFloat(),
                        pa.optDouble(1, 0.0).toFloat(),
                        pa.optDouble(2, 1.0).toFloat()
                    )
                )
            }
        }
    }

    private companion object {
        const val KEY_SHADE = "shade_level"

        /** 空题（模块说明页等）共用一份空文档，不会被写入 */
        val EMPTY_DOC = Doc(0L)

        /** 反射缓存：View 上没有公开的滚动进度 API，只能借 protected 的实现 */
        val OFFSET_M: java.lang.reflect.Method? = scrollMethod("computeVerticalScrollOffset")
        val EXTENT_M: java.lang.reflect.Method? = scrollMethod("computeVerticalScrollExtent")
        val RANGE_M: java.lang.reflect.Method? = scrollMethod("computeVerticalScrollRange")

        fun scrollMethod(name: String): java.lang.reflect.Method? = try {
            View::class.java.getDeclaredMethod(name).apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }
}
