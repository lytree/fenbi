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
import kotlin.math.hypot

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
 * 5. **跟随作答记录持久化**——笔迹存 [QBankDb] 的 `record_inks` 表，按「本次作答记录 + 目标」
 *    维度存取：换一轮练习是另一套圈画，同一记录里每个目标只保留最新一份批注；
 *    撤销 / 清空即时落库，切题往返、退出重进都还在。
 * 6. **内容锚定、材料与题目分开**——笔迹不是「钉在屏幕上」，而是钉在题目卡或材料块上
 *    （[anchorSource] 给出每块内容在本层里的位置与命中区）。滚动卷面、切浏览模式、
 *    答题页重新排版，笔迹都跟着那道题 / 那份材料走；材料块上的圈画不再按题干定位而偏移。
 *    题目用 [TargetKind.QUESTION]（target = 题目 id），材料用 [TargetKind.MATERIAL]
 *    （target = 材料组首题 id），存储与锚点都分开。
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
     * 当前工具：笔 / 橡皮。
     *
     * 橡皮按「整笔擦除」实现——手指划过的笔迹整条清掉，不做像素级局部擦。
     * 好处是数据模型不变（还是一条条笔迹），撤销 / 换色 / 落库都不用特殊照顾；
     * 对圈画做题这种「一笔就是一道线」的场景，整笔擦反而更顺手。
     */
    enum class Tool { PEN, ERASER }

    /**
     * 笔迹归属的两类目标：题目 / 材料。
     *
     * 材料题的圈画如果也按题干锚定，材料块一换版式就会整体偏移，甚至串到题目上；
     * 所以材料单独一类，存储与锚点都跟题目分开。
     */
    enum class TargetKind(val dbValue: String) {
        QUESTION("question"),
        MATERIAL("material")
    }

    /**
     * 一份笔迹目标在本层坐标系里的位置。
     *
     * 笔迹存的是「相对这块内容左上角」的内容坐标，画的时候再加上 [x]/[y]：
     * 卷面一滚，内容的位置变了，笔迹自然跟着走，不需要像旧实现那样手工平移每一笔。
     *
     * [width]/[height] 是命中区域（[contains]），用来判断这一笔落在题目上还是材料上；
     * 同一落点命中多个锚点时取面积最小的那个，材料块盖在题目卡里也能写到材料上。
     */
    class Anchor(
        val kind: TargetKind,
        val targetId: Long,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    ) {
        /** 题目锚点的便捷写法（材料用完整构造函数 + [TargetKind.MATERIAL]） */
        constructor(questionId: Long, x: Float, y: Float, width: Float, height: Float) :
            this(TargetKind.QUESTION, questionId, x, y, width, height)

        /**
         * 命中区（本层坐标）：默认为内容矩形。
         *
         * 材料正文在独立的滚动容器里时，内容可能比可视区高（甚至滚到屏幕外），
         * 命中区要裁到容器可视范围，否则下方题目的落点会被算成材料。
         * 绘制仍用 [x]/[y]（内容原点），所以滚动后笔迹照样跟着材料走。
         */
        private var hitLeft = x
        private var hitTop = y
        private var hitRight = x + width
        private var hitBottom = y + height

        val area: Float get() =
            (hitRight - hitLeft).coerceAtLeast(0f) * (hitBottom - hitTop).coerceAtLeast(0f)

        /** 把命中区裁到 [left]/[top]/[right]/[bottom]（通常是滚动容器的可视范围） */
        fun hitRegion(left: Float, top: Float, right: Float, bottom: Float): Anchor {
            hitLeft = left
            hitTop = top
            hitRight = right
            hitBottom = bottom
            return this
        }

        fun contains(px: Float, py: Float): Boolean =
            hitRight > hitLeft && hitBottom > hitTop &&
                px >= hitLeft && px <= hitRight && py >= hitTop && py <= hitBottom
    }

    /** 一笔：若干采样点 + 每点的压感 + 这一笔的颜色（内容坐标） */
    private class Stroke {
        val points = ArrayList<FloatArray>()   // [x, y, width]  width = 压感 × 速度合成的粗细系数
        var color: Int = 0                     // 0 = 旧数据，按默认红笔渲染

        /** 笔锋用：上一个采样点的时间与粗细系数（只活在本次书写期间，不落库） */
        var lastTime: Long = 0L
        var lastWidth: Float = 1f
    }

    /** 一份目标（题目或材料）的笔迹集合；从库里懒加载，读过（哪怕是空）都缓存着，滚动时不重复查库 */
    private class Doc(val kind: TargetKind, val targetId: Long) {
        val strokes = ArrayList<Stroke>()
    }

    private val docs = HashMap<String, Doc>()
    private var current: Stroke? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    /** 撤销下来的笔迹：重做按逆序放回；一落新笔、一换目标就作废（标准撤销/重做语义） */
    private val redoStack = ArrayList<Stroke>()

    /** 橡皮正在擦：落点画一枚淡淡的圈，让人知道「现在是在擦，不是笔没水」 */
    private var erasing = false
    private var eraserX = -1f
    private var eraserY = -1f
    /** 撤销 / 清空 / 落库的目标（最近一次落笔的题目或材料） */
    private var activeKind = TargetKind.QUESTION
    private var activeTargetId = 0L

    /**
     * 当前作答记录 id：笔迹按「记录 + 目标」存，换一轮练习就是另一套圈画。
     *
     * 答题页一进来就会建/取草稿记录并赋给这里；0 表示还没记录（笔迹只在内存里，不落库）。
     */
    var recordId: Long = 0L
        set(value) {
            if (field == value) return
            field = value
            docs.clear()
            current = null
            redoStack.clear()
            invalidate()
            onStrokeCountChanged?.invoke(strokeCount)
        }
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
        }

    /** 当前工具：笔 / 橡皮。工具条上两个图标互斥高亮 */
    var tool: Tool = Tool.PEN
        set(value) {
            if (field == value) return
            field = value
            current = null
            erasing = false
            invalidate()
            onToolChanged?.invoke(value)
        }

    /** 是否处于写画模式：true = 铺遮盖纸 + 拦截全部触摸 */
    var writeMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            activePointerId = MotionEvent.INVALID_POINTER_ID
            current = null
            erasing = false
            barDragging = false
            barTarget = null
            barLeftEdge = false
            isEnabled = value
            // 笔色只在设置页改：每次进写画都重读一次，设置里刚换的颜色马上生效
            if (value) penColor = PenColors.load(context)
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
    /** 工具切换回调（工具条换高亮） */
    var onToolChanged: ((Tool) -> Unit)? = null

    /**
     * 内容锚点来源：返回当前屏幕上每一块可写内容（题目 / 材料）的归属、原点与命中区。
     *
     * 接了它 = 笔迹跟着内容走，且落笔时按命中区自动决定写的是题目还是材料；
     * 不接（返回 null / 空表）= 退回旧行为，按屏幕坐标画当前目标。
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

    /** 橡皮落点圈 */
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** 橡皮的作用半径：手指粗细，划过就把整笔带走 */
    private val eraserRadius get() = 12f * resources.displayMetrics.density

    /** 右侧滚动轨道 / 滑块的画笔 */
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barRect = RectF()

    init {
        isEnabled = false   // 默认不拦截，笔迹照常显示
    }

    /** 换绑到某一题：只影响撤销 / 清空 / 落库的默认目标，画面上所有可见目标的笔迹照画 */
    fun bindQuestion(questionId: Long) = bindTarget(TargetKind.QUESTION, questionId)

    /** 换绑到某份材料（[materialId] 是材料组首题 id） */
    fun bindMaterial(materialId: Long) = bindTarget(TargetKind.MATERIAL, materialId)

    private fun bindTarget(kind: TargetKind, targetId: Long) {
        if (activeKind == kind && activeTargetId == targetId) return
        activeKind = kind
        activeTargetId = targetId
        current = null
        redoStack.clear()
        invalidate()
        onStrokeCountChanged?.invoke(strokeCount)
    }

    /**
     * 丢掉笔迹缓存，下次绘制重新读库。
     *
     * 同一份内容可能在本页不可见时被别的页面改过（资料分析独立分屏页、页面重建），
     * 回来时不重读就会看到旧笔迹。已落库的不会丢——每一笔在抬手 / 撤销时就写库了。
     */
    fun reload() {
        docs.clear()
        current = null
        redoStack.clear()
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
                val doc = docOf(a.kind, a.targetId)
                if (doc.strokes.isEmpty()) continue
                for (s in doc.strokes) drawStroke(canvas, s, a.x, a.y)
            }
        }
        // 正在写的那一笔：点已经换算成内容坐标，画的时候抬回屏幕
        current?.let { drawStroke(canvas, it, strokeOriginX, strokeOriginY) }
        // 橡皮落点：一枚空心圈跟着走，擦没擦到一眼看得出来
        if (erasing) drawEraserRing(canvas)
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
            // 取两端系数的均值：相邻两段不会突然一粗一细，笔锋是「渐变」出来的
            strokePaint.strokeWidth = baseWidth * (0.4f + 0.6f * (a[2] + b[2]) * 0.5f)
            canvas.drawLine(a[0] + dx, a[1] + dy, b[0] + dx, b[1] + dy, strokePaint)
        }
    }

    /** 橡皮落点圈：只画轮廓，不挡下面的字 */
    private fun drawEraserRing(canvas: Canvas) {
        eraserPaint.strokeWidth = 1.5f * resources.displayMetrics.density
        eraserPaint.color = if (night) 0x99FFFFFF.toInt() else 0x59000000
        canvas.drawCircle(eraserX, eraserY, eraserRadius, eraserPaint)
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

    // ---------------- 内容锚点 ----------------

    /**
     * 当前目标的位置。取不到就退回 (0,0)——旧页面 / 极端布局下
     * 至少还能写，只是不跟着内容滚。
     */
    private fun originOfActive(): FloatArray {
        val id = activeTargetId
        if (id != 0L) {
            anchorSource?.invoke()
                ?.firstOrNull { it.kind == activeKind && it.targetId == id }
                ?.let { return floatArrayOf(it.x, it.y) }
        }
        return floatArrayOf(0f, 0f)
    }

    /**
     * 落点属于哪份内容：命中多个锚点时取面积最小的（材料块在题目卡里，取更精确的材料）；
     * 没命中任何矩形时取最近的一块，写在题卡边缘也不会丢到屏幕坐标上。
     */
    private fun pickAnchor(x: Float, y: Float): Anchor? {
        val anchors = anchorSource?.invoke().orEmpty().filter { it.targetId != 0L }
        if (anchors.isEmpty()) return null
        var best: Anchor? = null
        for (a in anchors) {
            if (!a.contains(x, y)) continue
            if (best == null || a.area < best.area) best = a
        }
        if (best != null) return best
        return anchors.minByOrNull { distanceTo(it, x, y) }
    }

    /** 点到矩形的最短距离平方（只比大小，不用开方） */
    private fun distanceTo(a: Anchor, x: Float, y: Float): Float {
        val dx = when {
            x < a.x -> a.x - x
            x > a.x + a.width -> x - (a.x + a.width)
            else -> 0f
        }
        val dy = when {
            y < a.y -> a.y - y
            y > a.y + a.height -> y - (a.y + a.height)
            else -> 0f
        }
        return dx * dx + dy * dy
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
                // 橡皮：整笔擦掉划过的笔迹，不产生新线
                if (tool == Tool.ERASER) {
                    beginErase(event)
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                activePointerId = event.getPointerId(0)
                // 落点决定这一笔归属于题目还是材料：材料区写材料、题目区写题目，
                // 之后所有点都换算成「那块内容」的相对坐标，滚动 / 换版式都不会串位
                val hit = pickAnchor(event.x, event.y)
                val origin = if (hit != null) {
                    bindTarget(hit.kind, hit.targetId)
                    floatArrayOf(hit.x, hit.y)
                } else {
                    originOfActive()
                }
                strokeOriginX = origin[0]
                strokeOriginY = origin[1]
                redoStack.clear()          // 落了新笔，撤销掉的那些就不能再重做了
                current = Stroke().apply {
                    color = penColor
                    addSample(
                        this,
                        event.x - strokeOriginX,
                        event.y - strokeOriginY,
                        pressureOf(event, 0),
                        event.eventTime
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
                if (erasing) {
                    dragErase(event)
                    return true
                }
                val s = current ?: return true
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                // 只取当前笔那一根手指的轨迹，其余触点（掌托）不参与绘制
                for (h in 0 until event.historySize) {
                    addSample(
                        s,
                        event.getHistoricalX(idx, h) - strokeOriginX,
                        event.getHistoricalY(idx, h) - strokeOriginY,
                        pressureOf(event, idx, h),
                        event.getHistoricalEventTime(h)
                    )
                }
                addSample(
                    s,
                    event.getX(idx) - strokeOriginX,
                    event.getY(idx) - strokeOriginY,
                    pressureOf(event, idx),
                    event.eventTime
                )
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起的是别人的手指，不影响这一笔
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (erasing) {
                    endErase()
                    return true
                }
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

    // ---------------- 笔锋 ----------------

    /**
     * 采样一个点，顺手算出这一点该多粗。
     *
     * 粗细 = 压感 × 速度：落笔重、走得慢就粗（顿笔），甩得快就细（出锋）。
     * 手指没有压感（pressure 恒为 1）时靠速度也一样能写出笔锋 —— 这就是「像手写」的关键，
     * 固定线宽写出来的字会像记号笔，一眼看不出是手写的。
     *
     * 系数做了一阶低通（新值占 45%），避免采样抖动让线条忽粗忽细。
     */
    private fun addSample(s: Stroke, x: Float, y: Float, pressure: Float, time: Long) {
        val last = if (s.points.isEmpty()) null else s.points[s.points.size - 1]
        val raw = if (last == null) {
            // 起笔：重一点，像笔尖按下纸面
            0.75f + 0.45f * pressure
        } else {
            val d = resources.displayMetrics.density
            val distDp = hypot(x - last[0], y - last[1]) / d
            val dt = (time - s.lastTime).coerceAtLeast(1L)
            val speedDp = distDp / dt                       // dp/ms
            val speed = (1.08f - speedDp * 0.42f).coerceIn(0.45f, 1.08f)
            val target = speed * (0.72f + 0.42f * pressure)
            s.lastWidth * 0.55f + target * 0.45f
        }
        val w = raw.coerceIn(0.40f, 1.35f)

        s.lastTime = time
        s.lastWidth = w
        s.points.add(floatArrayOf(x, y, w))
    }

    // ---------------- 橡皮 ----------------

    private fun beginErase(e: MotionEvent) {
        parent?.requestDisallowInterceptTouchEvent(true)
        erasing = true
        eraserX = e.x
        eraserY = e.y
        eraseAt(e.x, e.y)
        invalidate()
    }

    private fun dragErase(e: MotionEvent) {
        eraserX = e.x
        eraserY = e.y
        // 补上历史点，快速划过去也不会漏掉中间的笔迹
        for (h in 0 until e.historySize) {
            eraseAt(e.getHistoricalX(0, h), e.getHistoricalY(0, h))
        }
        eraseAt(e.x, e.y)
        invalidate()
    }

    private fun endErase() {
        erasing = false
        eraserX = -1f
        eraserY = -1f
        parent?.requestDisallowInterceptTouchEvent(false)
        persist()          // 擦完立刻落库，切题 / 退出重进都还是擦干净的样子
        invalidate()
    }

    /**
     * 擦掉落点半径内碰到的笔迹（整笔带走）。
     *
     * 笔迹点存的是「相对命中内容原点」的坐标，落点要先减掉同一个原点才能比距离；
     * 落点若不在任何内容上，就按当前绑定的目标擦。
     */
    private fun eraseAt(x: Float, y: Float) {
        val hit = pickAnchor(x, y)
        val kind = hit?.kind ?: activeKind
        val targetId = if (hit != null) hit.targetId else activeTargetId
        if (targetId == 0L) return
        val doc = docOf(kind, targetId)
        if (doc.strokes.isEmpty()) return

        val origin = if (hit != null) floatArrayOf(hit.x, hit.y) else originOfActive()
        val px = x - origin[0]
        val py = y - origin[1]
        val r2 = eraserRadius * eraserRadius

        val before = doc.strokes.size
        val it = doc.strokes.iterator()
        while (it.hasNext()) {
            if (strokeHits(it.next(), px, py, r2)) it.remove()
        }
        if (doc.strokes.size == before) return
        redoStack.clear()   // 擦除也是一次编辑，旧的「重做」不再成立
        onStrokeCountChanged?.invoke(doc.strokes.size)
    }

    private fun strokeHits(s: Stroke, px: Float, py: Float, r2: Float): Boolean {
        for (p in s.points) {
            val dx = p[0] - px
            val dy = p[1] - py
            if (dx * dx + dy * dy <= r2) return true
        }
        return false
    }

    // ---------------- 撤销 / 清空 / 换档 / 换色 ----------------

    fun undo() {
        val doc = activeDoc() ?: return
        if (doc.strokes.isEmpty()) return
        redoStack.add(doc.strokes.removeAt(doc.strokes.size - 1))
        persist()
        invalidate()
        onStrokeCountChanged?.invoke(doc.strokes.size)
    }

    /** 重做：把刚撤销的那一笔按原顺序放回去 */
    fun redo() {
        val doc = activeDoc() ?: return
        val s = redoStack.removeLastOrNull() ?: return
        doc.strokes.add(s)
        persist()
        invalidate()
        onStrokeCountChanged?.invoke(doc.strokes.size)
    }

    /** 清空当前目标的全部笔迹（垃圾桶语义：清掉就是清掉，不进重做栈） */
    fun clear() {
        val doc = activeDoc() ?: return
        if (doc.strokes.isEmpty()) return
        doc.strokes.clear()
        redoStack.clear()
        current = null
        persist()
        invalidate()
        onStrokeCountChanged?.invoke(0)
    }

    /** 遮盖档位循环切换：关 → 浅 → 中 → 深 → 关 */
    fun cycleShade() {
        shade = shade.next()
    }

    val strokeCount: Int get() = activeDoc()?.strokes?.size ?: 0

    /** 本题有没有写过东西（用于「清空」按钮置灰之类） */
    val hasInk: Boolean get() = strokeCount > 0

    /** 能不能撤销 / 重做（工具条据此决定图标亮度） */
    val canUndo: Boolean get() = hasInk
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // ---------------- 持久化 ----------------

    private fun activeDoc(): Doc? =
        if (activeTargetId == 0L) null else docOf(activeKind, activeTargetId)

    /** 每个目标（题目 / 材料）一份，读过的（包括空）都缓存：滚动时不会反复查库 */
    private fun docOf(kind: TargetKind, targetId: Long): Doc {
        if (targetId == 0L) return EMPTY_DOC
        val key = keyOf(kind, targetId)
        docs[key]?.let { return it }
        val doc = Doc(kind, targetId)
        doc.strokes.addAll(loadStrokes(kind, targetId))
        docs[key] = doc
        return doc
    }

    /**
     * 当前目标的笔迹落库。撤销到空时直接删行——「每题只保留最新一份」，
     * 清空后不该留一条空记录，也不保留历史版本。
     */
    private fun persist() {
        val doc = activeDoc() ?: return
        val rid = recordId
        if (rid <= 0L) return
        val db = QBankDb.get(context)
        if (doc.strokes.isEmpty()) {
            db.deleteInk(rid, doc.kind.dbValue, doc.targetId)
        } else {
            db.setInk(rid, doc.kind.dbValue, doc.targetId, serialize(doc))
        }
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

    private fun loadStrokes(kind: TargetKind, targetId: Long): List<Stroke> {
        val rid = recordId
        if (rid <= 0L) return emptyList()
        val raw = QBankDb.get(context).getInk(rid, kind.dbValue, targetId)?.trim().orEmpty()
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

        fun keyOf(kind: TargetKind, targetId: Long): String = "${kind.dbValue}:$targetId"

        /** 空目标（模块说明页等）共用一份空文档，不会被写入 */
        val EMPTY_DOC = Doc(TargetKind.QUESTION, 0L)

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
