package com.lytree.qbank.ui.recite

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.lytree.qbank.R
import com.lytree.qbank.data.ReciteLibraryRepository
import com.lytree.qbank.data.ReciteRepository
import com.lytree.qbank.data.model.LibraryItem
import com.lytree.qbank.data.model.ReciteLibrary
import com.lytree.qbank.databinding.FragmentReciteDrillBinding
import kotlin.math.abs

/**
 * 聚焦背诵页——大纲页里点某一条进来，**一条一屏**。
 *
 * 大纲负责「看结构、找位置」，这一页只负责「把这条背下来」，所以它没有任何导航噪音：
 * 上面一行面包屑告诉你这条在哪一组，中间一张卡，下面两个决定。
 *
 * ```
 * ← 新思想                              12 / 42
 * ▬▬▬▬▬▬▬░░░░░░░░░░░░░░░░░░░░░░
 * ┌────────────────────────────────┐
 * │ 习近平新时代…同______相结合的   │  ← 轻点揭示 / 长按偷看 / 左右滑切条
 * │ 重大成果。                      │
 * │        轻点揭示 · 长按偷看       │
 * └────────────────────────────────┘
 * [ 还不熟 ]              [ 背会了 ]
 * ```
 *
 * 背诵范围按「这条内容所属的那一块」自动定，不用调用方传一堆参数：
 * * 卡片 → **同一讲**（同一份文件 / 同一节课，实测 31 组、中位数 33 条、最多 93 条）；
 * * 导图行 → **同一个导图主题**（36 个主题，中位数 27 条）。
 *
 * 标题是这一组的名字（讲名 / 主题名），面包屑是当前这条归在哪个标签——标签是横切分类，
 * 同一讲里跨几个标签很正常，切条时它会跟着变，正好说明「标签不是文件夹」。
 *
 * 会话结束（背到最后一条再往后）不另开页面，卡片原地换成小结，可以再背一遍或回大纲。
 */
class ReciteDrillFragment : Fragment() {

    private var _binding: FragmentReciteDrillBinding? = null
    private val binding get() = _binding!!

    private lateinit var library: ReciteLibrary
    private lateinit var modeAction: ImageView
    private lateinit var fontAction: TextView

    /** 本次背诵的序列（同标签同来源 / 同主题），按资产原始顺序 */
    private var scope: List<LibraryItem> = emptyList()
    private var index = 0

    private var done: Set<String> = emptySet()
    private var fontLevel = 0
    private var reciteMode = false

    /** 轻点揭示：本条的答案当前是否展开 */
    private var revealed = false

    /** 长按偷看：只在手指按住期间显示 */
    private var peeking = false

    /** 本轮点了「还不熟」的条目：小结里可以只再背这几条 */
    private val hardIds = LinkedHashSet<String>()

    /** 边缘提示是否正在显示：避免连滑几次叠一串延时任务 */
    private var edgeHintPosted = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReciteDrillBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        library = ReciteLibraryRepository.library(context)
        done = ReciteLibraryRepository.doneIds(context)
        fontLevel = ReciteRepository.fontLevel(context)
        reciteMode = ReciteRepository.reciteMode(context)

        binding.titleBar.showBack(true)
        binding.titleBar.onBack = { openOutline() }
        // 系统返回键同样回大纲：本页是从大纲顶上开出来的一层，一按就退出 App 太粗暴
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { openOutline() }

        // 背诵页也能切「答案常显」：常识页开着它就带着进来，这里没开关的话没法改回去
        modeAction = binding.titleBar.addIconAction(
            R.drawable.ic_recite_mode,
            getString(R.string.recite_mode)
        ) { setReciteMode(!reciteMode) }
        updateModeAction()

        fontAction = binding.titleBar.addTextAction("") { chooseFont() }
        fontAction.setTextColor(ContextCompat.getColor(context, R.color.fb_text_secondary))
        updateFontAction()

        binding.btnHard.setOnClickListener {
            // 点「还不熟」就是明确表态这条没记住：记下来，小结里可以只过这几条
            scope.getOrNull(index)?.let { hardIds += it.id }
            step(1)
        }
        binding.btnKnown.setOnClickListener { markAndNext() }
        binding.btnAgain.setOnClickListener { restart() }
        binding.btnHardOnly.setOnClickListener { drillHardOnly() }
        binding.btnBackOutline.setOnClickListener { openOutline() }

        val anchor = library.items.firstOrNull { it.id == requireArguments().getString(ARG_ANCHOR) }
        if (anchor == null) {
            showUnavailable()
            return
        }

        scope = buildScope(anchor)
        index = scope.indexOfFirst { it.id == anchor.id }.coerceAtLeast(0)
        binding.titleBar.setTitle(groupTitle(anchor))
        setupGestures()
        render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------------------------------------------------------- 范围

    /**
     * 一次背多大一块：
     * * 卡片按**同一讲**（`source`）——同一份文件 / 同一节课的内容本来就该连着背，
     *   按标签切反而会把一讲拆成三五条（实测按「标签 + 来源」有 44% 的组不足 3 条）；
     * * 导图行按**同一主题**。
     *
     * 组内只有一两条时也不再放宽到整个标签：宁可背两条就结束，也别让人点进来发现还有 500 条。
     */
    private fun buildScope(anchor: LibraryItem): List<LibraryItem> {
        val group = if (anchor.isCard) {
            library.items.filter { it.isCard && it.source == anchor.source }
        } else {
            library.items.filter { it.topic.isNotBlank() && it.topic == anchor.topic }
        }
        if (group.isNotEmpty()) return group
        return library.items.filter { it.mainTag == anchor.mainTag }.ifEmpty { listOf(anchor) }
    }

    /** 标题 = 这一组叫什么：卡片给讲名，导图给主题名 */
    private fun groupTitle(anchor: LibraryItem): String {
        if (!anchor.isCard) return anchor.topicName.ifBlank { getString(R.string.drill_title) }
        return anchor.source.substringAfterLast(" · ").ifBlank { anchor.source }
            .ifBlank { getString(R.string.drill_title) }
    }

    /** 面包屑 = 当前这条归在哪个标签（标签是横切分类，切条时会跟着变） */
    private fun tagLabel(item: LibraryItem): String =
        if (item.mainTag.isBlank()) getString(R.string.lib_untagged) else library.tagName(item.mainTag)

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        val item = scope.getOrNull(index) ?: return
        val context = requireContext()

        binding.tvCrumb.text = tagLabel(item)
        binding.tvCounter.text = getString(R.string.drill_counter_fmt, index + 1, scope.size)
        // 进度条是「第几条」，不是「背会了多少」：读屏也要听到同一个口径
        binding.progressBar.contentDescription =
            getString(R.string.drill_progress_desc_fmt, index + 1, scope.size)

        // 进度条用权重分摊，省得自己算像素宽度
        val fill = ((index + 1f) / scope.size * WEIGHT_TOTAL).toInt().coerceIn(0, WEIGHT_TOTAL)
        (binding.progressFill.layoutParams as LinearLayout.LayoutParams).weight = fill.toFloat()
        (binding.progressRest.layoutParams as LinearLayout.LayoutParams).weight =
            (WEIGHT_TOTAL - fill).toFloat()
        binding.progressFill.requestLayout()
        binding.progressRest.requestLayout()

        binding.tvText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f + fontLevel * 2)
        binding.tvText.setLineSpacing(dp(8 + fontLevel * 2).toFloat(), 1f)
        renderText()

        // 已经是已背状态时，主按钮变成「下一条」，避免让人以为要再标一次
        binding.btnKnown.setText(
            if (item.id in done) R.string.drill_next else R.string.drill_known
        )
        binding.card.isVisible = true
        binding.card.animate().cancel()
        binding.card.translationX = 0f
        binding.doneBox.isVisible = false
        binding.bottomBar.isVisible = true
        // 新条目从顶部读起；换上文字后布局才更新，滚动得等下一帧
        binding.cardScroll.post { binding.cardScroll.scrollTo(0, 0) }

        // 每切一条就记一次：被杀掉进程也能从这条续上
        ReciteLibraryRepository.rememberDrill(context, item.id)
    }

    /** 只重刷正文与提示（揭示 / 偷看时用，不动序号和进度） */
    private fun renderText() {
        val item = scope.getOrNull(index) ?: return
        val show = reciteMode || revealed || peeking
        binding.tvText.text = if (item.isCard) {
            ReciteCloze.card(requireContext(), item.text, item.answers, show)
        } else {
            ReciteCloze.line(requireContext(), item.text, item.cloze, show)
        }
        // 没有可遮的东西（单句短语、短标题）时，别提示「轻点揭示」——点了没反应更让人犯嘀咕
        val canReveal = item.isCard || item.cloze.isNotEmpty()
        binding.tvHint.setText(
            when {
                !canReveal -> R.string.drill_hint_plain
                reciteMode -> R.string.drill_hint_mode
                peeking -> R.string.drill_hint_peeking
                revealed -> R.string.drill_hint_revealed
                else -> R.string.drill_hint_reveal
            }
        )
    }

    // ---------------------------------------------------------------- 切条

    private fun step(delta: Int) {
        val next = index + delta
        if (next < 0) {
            // 第一条再往右滑：没有上一条可去，给一下回弹 + 一句话，别让人以为滑动失灵
            nudgeEdge(forward = false)
            return
        }
        if (next >= scope.size) {
            showSummary()
            return
        }
        index = next
        revealed = false
        peeking = false
        render()
    }

    /** 「背会了」：标已背，再进下一条；已背的不会再写一次库 */
    private fun markAndNext() {
        val item = scope.getOrNull(index) ?: return
        if (item.id !in done) {
            done = ReciteLibraryRepository.setDone(requireContext(), item.id, true)
            binding.card.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        step(1)
    }

    private fun restart() {
        index = 0
        revealed = false
        peeking = false
        hardIds.clear()
        render()
    }

    /** 只背本轮标了「还不熟」的几条：背完一次小结还在，可以继续收窄 */
    private fun drillHardOnly() {
        val hard = scope.filter { it.id in hardIds }
        if (hard.isEmpty()) return
        scope = hard
        index = 0
        revealed = false
        peeking = false
        hardIds.clear()
        render()
    }

    /**
     * 边缘回弹：卡片朝该滑的方向顶一下再弹回，同时把底下那行提示临时换成
     * 「已经是第一条 / 最后一条了」。提示只挂一次，连滑不会叠任务。
     */
    private fun nudgeEdge(forward: Boolean) {
        if (_binding == null) return
        binding.card.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val shift = dp(14).toFloat() * if (forward) -1 else 1
        binding.card.animate().cancel()
        binding.card.translationX = 0f
        binding.card.animate()
            .translationX(shift)
            .setDuration(90)
            .withEndAction {
                _binding?.card?.animate()?.translationX(0f)?.setDuration(140)?.start()
            }
            .start()

        if (edgeHintPosted) return
        edgeHintPosted = true
        binding.tvHint.setText(
            if (forward) R.string.drill_edge_last else R.string.drill_edge_first
        )
        binding.tvHint.postDelayed({
            edgeHintPosted = false
            if (_binding != null) renderText()
        }, 1200)
    }

    private fun showSummary() {
        val hardCount = scope.count { it.id in hardIds }
        binding.btnHardOnly.isVisible = hardCount > 0
        if (hardCount > 0) {
            binding.btnHardOnly.text = getString(R.string.drill_hard_only_fmt, hardCount)
        }
        binding.tvDoneMsg.text = getString(
            R.string.drill_done_msg,
            scope.size,
            scope.count { it.id in done },
        )
        binding.card.isVisible = false
        binding.doneBox.isVisible = true
        binding.bottomBar.isVisible = false
    }

    private fun showUnavailable() {
        binding.titleBar.setTitle(getString(R.string.drill_title))
        binding.tvText.setText(R.string.drill_empty)
        binding.tvHint.isVisible = false
        binding.bottomBar.isVisible = false
        binding.progressFill.isVisible = false
        binding.progressRest.isVisible = false
        binding.tvCounter.isVisible = false
    }

    // ---------------------------------------------------------------- 手势

    /**
     * 三个动作和大纲页 / 背诵页保持同一套肌肉记忆：
     * * 轻点 → 揭示 / 收起答案（背诵模式下答案本来就常显，不动）；
     * * 长按 → 按住偷看，松手即藏（政治的老手感）；
     * * 左右滑 → 上 / 下一条，滑到最后一条再往后就是小结。
     *
     * 只观察不吞事件：正文在 ScrollView 里，长条目还要靠它纵向滚。
     */
    private fun setupGestures() {
        val slop = dp(44)
        // 手势内累计的位移：只认「甩」的话，慢慢拖过去毫无反应，和提示里的「左右滑」对不上
        var swipeDx = 0f
        var swipeDy = 0f
        var swipeConsumed = false
        val detector = GestureDetector(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    swipeDx = 0f
                    swipeDy = 0f
                    swipeConsumed = false
                    return true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    onCardTapped()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (reciteMode || revealed) return
                    binding.card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    peeking = true
                    renderText()
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (swipeConsumed) return false
                    swipeDx -= distanceX
                    swipeDy -= distanceY
                    // 不够远、或竖向占主导（长条目还要靠 ScrollView 滚），都不算切条
                    if (abs(swipeDx) < slop || abs(swipeDx) < abs(swipeDy) * 1.6f) return false
                    swipeConsumed = true
                    step(if (swipeDx < 0) 1 else -1)
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    // 慢拖已经在 onScroll 里切过了，别甩手再来一次
                    if (swipeConsumed) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    // 竖向为主的滑动算滚动，不算切条（长条目在页内要能滚）
                    if (abs(dx) < slop || abs(dx) < abs(dy) * 1.6f) return false
                    step(if (dx < 0) 1 else -1)
                    return true
                }
            }
        )
        val observer = View.OnTouchListener { _, event ->
            detector.onTouchEvent(event)
            // 按住偷看的收尾：手指抬起或被 ScrollView 抢走事件，立刻藏回去
            if (peeking &&
                (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)
            ) {
                peeking = false
                renderText()
            }
            false
        }
        binding.cardScroll.setOnTouchListener(observer)
        binding.card.setOnTouchListener(observer)
        // 卡片补 OnClickListener：读屏才认得出这是可操作区域；手势与点击同时到达时按时间戳去重
        binding.card.setOnClickListener { onCardTapped() }
    }

    private var lastTapAt = 0L

    /** 轻点卡片 = 揭示 / 收起答案（背诵模式下答案常显，不动） */
    private fun onCardTapped() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt < 150) return
        lastTapAt = now
        if (reciteMode) return
        revealed = !revealed
        renderText()
    }

    // ---------------------------------------------------------------- 背诵模式

    /** 顶栏睁眼 = 答案常显，与常识页同一套语义：随时能开能关，不用退回列表 */
    private fun setReciteMode(on: Boolean) {
        if (reciteMode == on) return
        reciteMode = on
        ReciteRepository.setReciteMode(requireContext(), on)
        updateModeAction()
        renderText()
    }

    private fun updateModeAction() {
        val context = requireContext()
        modeAction.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (reciteMode) R.color.fb_primary else R.color.fb_icon)
        )
        modeAction.background = if (reciteMode) {
            ContextCompat.getDrawable(context, R.drawable.bg_recite_mode_on)
        } else {
            null
        }
        modeAction.isSelected = reciteMode
        modeAction.contentDescription =
            getString(if (reciteMode) R.string.recite_mode_on else R.string.recite_mode)
    }

    // ---------------------------------------------------------------- 字号 / 导航

    private fun updateFontAction() {
        val labels = arrayOf(
            R.string.recite_font_small,
            R.string.recite_font_medium,
            R.string.recite_font_large,
        )
        fontAction.text = getString(R.string.recite_font_fmt, getString(labels[fontLevel]))
    }

    private fun chooseFont() {
        ReciteFontPicker.show(requireContext(), fontLevel) { level ->
            fontLevel = level
            ReciteRepository.setFontLevel(requireContext(), level)
            updateFontAction()
            binding.tvText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f + level * 2)
            binding.tvText.setLineSpacing(dp(8 + level * 2).toFloat(), 1f)
        }
    }

    private fun openOutline() {
        if (!isAdded) return
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, ReciteOutlineFragment())
            .commit()
    }

    // ---------------------------------------------------------------- 小工具

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {

        private const val ARG_ANCHOR = "drill_anchor"

        /** 进度条按权重分摊，等于把 100% 分成 1000 份 */
        private const val WEIGHT_TOTAL = 1000

        fun newInstance(anchorId: String): ReciteDrillFragment =
            ReciteDrillFragment().apply { arguments = bundleOf(ARG_ANCHOR to anchorId) }
    }
}
