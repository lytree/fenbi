package com.lytree.qbank.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.lytree.qbank.data.db.QBankDb
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手写标注层。
 *
 * 需求来自刷题场景：做题时想用触控笔在题干/选项上划重点、算式。
 * 实现要点：
 *
 * 1. **与横向滑动共存**——只有当落笔工具是触控笔（[MotionEvent.TOOL_TYPE_STYLUS]）
 *    或用户显式开启了「手指绘制」时，才拦截事件；否则放行给父容器的手势，
 *    避免把翻页手势吃掉。手写笔有压感时用压感调节笔迹粗细。
 * 2. **笔迹按题目保存**——切题时把当前笔迹写入 [QBankDb]，回到该题时恢复，
 *    这样刷一遍题再回来，标注还在。
 * 3. **撤销/清空**——[undo] 撤销最后一笔，[clear] 清空当前题全部笔迹。
 */
class InkOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一笔：若干采样点 + 每点的压感 */
    private class Stroke {
        val points = ArrayList<FloatArray>()   // [x, y, pressure]
    }

    private val strokes = ArrayList<Stroke>()
    private var current: Stroke? = null
    private var activeQuestionId = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F04C4C")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4f
    }

    /** 允许手指绘制（默认关闭，避免和翻页手势打架） */
    var allowFingerDrawing: Boolean = false

    /** 撤销按钮回调 */
    var onStrokeCountChanged: ((Int) -> Unit)? = null

    fun bindQuestion(questionId: Long) {
        if (activeQuestionId == questionId) return
        activeQuestionId = questionId
        strokes.clear()
        current = null
        if (questionId != 0L) {
            strokes.addAll(loadStrokes(questionId))
        }
        invalidate()
        onStrokeCountChanged?.invoke(strokes.size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (s in strokes) drawStroke(canvas, s)
        current?.let { drawStroke(canvas, it) }
    }

    private fun drawStroke(canvas: Canvas, s: Stroke) {
        if (s.points.isEmpty()) return
        val p = s.points
        canvas.drawPoint(p[0][0], p[0][1], paint.apply { style = Paint.Style.FILL })
        if (p.size == 1) return
        for (i in 1 until p.size) {
            val a = p[i - 1]
            val b = p[i]
            paint.strokeWidth = baseWidth * (0.4f + 0.6f * b[2])
            canvas.drawLine(a[0], a[1], b[0], b[1], paint)
        }
    }

    private val baseWidth get() = 4f * resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        val pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // 非手写笔且未开手指绘制 → 放行，让 ViewPager2 处理翻页
                if (!isStylus && !allowFingerDrawing) return false
                if (pointerCount > 1) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                current = Stroke().apply {
                    points.add(floatArrayOf(event.x, event.y, pressureOf(event)))
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val s = current ?: return false
                for (h in 0 until event.historySize) {
                    s.points.add(
                        floatArrayOf(
                            event.getHistoricalX(h),
                            event.getHistoricalY(h),
                            pressureOf(event, h)
                        )
                    )
                }
                s.points.add(floatArrayOf(event.x, event.y, pressureOf(event)))
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val s = current ?: return false
                if (s.points.isNotEmpty()) {
                    strokes.add(s)
                    persist()
                    onStrokeCountChanged?.invoke(strokes.size)
                }
                current = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return false
    }

    private fun pressureOf(e: MotionEvent, historyIndex: Int = -1): Float {
        val p = if (historyIndex >= 0) e.getHistoricalPressure(historyIndex) else e.pressure
        return if (p <= 0f) 1f else p.coerceIn(0.1f, 1.5f)
    }

    // ---------------- 撤销 / 清空 ----------------

    fun undo() {
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.size - 1)
        persist()
        invalidate()
        onStrokeCountChanged?.invoke(strokes.size)
    }

    fun clear() {
        strokes.clear()
        persist()
        invalidate()
        onStrokeCountChanged?.invoke(0)
    }

    val strokeCount: Int get() = strokes.size

    // ---------------- 持久化 ----------------

    private fun persist() {
        if (activeQuestionId == 0L) return
        QBankDb.get(context).setInk(activeQuestionId, serialize())
    }

    private fun serialize(): String {
        val arr = JSONArray()
        for (s in strokes) {
            val o = JSONObject()
            val pts = JSONArray()
            for (p in s.points) {
                pts.put(JSONArray().put(p[0]).put(p[1]).put(p[2]))
            }
            o.put("p", pts)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun loadStrokes(questionId: Long): List<Stroke> {
        val raw = QBankDb.get(context).getInk(questionId) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val pts = o.optJSONArray("p") ?: JSONArray()
                Stroke().apply {
                    for (j in 0 until pts.length()) {
                        val pa = pts.getJSONArray(j)
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
        } catch (_: Exception) {
            emptyList()
        }
    }
}
