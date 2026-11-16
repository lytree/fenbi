package com.lytree.qbank.ui.recite

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.lytree.qbank.R
import com.lytree.qbank.data.MindMapRepository
import com.lytree.qbank.data.ReciteRepository
import com.lytree.qbank.databinding.DialogMindmapGraphBinding
import com.lytree.qbank.ui.FbDialog

/**
 * 一页书一张导图，全屏看。
 *
 * 列表页只负责挑主题（板块筛选 + 主题卡）；真正看结构进这里：
 * 画布铺满、双指缩放、拖动平移、点节点折叠，底部还有「适应屏幕 / 展开全部 / 只看主干」，
 * 这一页带「内涵连线」的主题会多一颗配对按钮。
 *
 * 还有一颗「夜读」：背诵模块本身没有夜间模式，但导图是全屏看图，
 * 晚上盯白底太刺眼，所以这一页自带开关，标题栏 / 画布 / 底部动作一起换深色，选择存库。
 *
 * 用对话框而不是换 Fragment：关掉就回到原来的列表，滚动位置和板块筛选都还在。
 */
class MindMapGraphDialog : DialogFragment() {

    private var _binding: DialogMindmapGraphBinding? = null
    private val binding get() = _binding!!

    /** 夜读开关的当前状态（与画布保持一致） */
    private var night = false

    /** 标题栏右上角的夜读动作，文案随状态在「夜读 / 日读」之间切 */
    private var nightAction: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.FbFullScreenDialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext(), R.style.FbFullScreenDialog)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogMindmapGraphBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // 铺满整屏（默认对话框会按内容收窄），并自己吃系统栏 inset：
        // 标题栏按状态栏高度下移，底部操作条让开导航栏
        dialog?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        // 系统栏颜色要等 window 有了再刷一次（onViewCreated 时还没挂上）
        if (binding.root.isAttachedToWindow) paintSystemBars()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val topicId = arguments?.getString(ARG_TOPIC)
        val topic = MindMapRepository.catalog(requireContext()).topics
            .firstOrNull { it.id == topicId }
        if (topic == null) {
            dismiss()
            return
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, bottom)
            insets
        }

        binding.titleBar.setTitle(topic.name)
        binding.titleBar.showBack(true)
        binding.titleBar.onBack = { dismiss() }
        binding.tvHint.text = getString(
            R.string.mindmap_graph_meta_fmt, topic.label, topic.branchCount, topic.nodeCount
        )

        // 夜读：先把上次的选择套上再灌数据——画布换肤要清布局缓存，
        // 放在 setNodes 之后会多排一次（而且首次分栏会被这次重排按住不重算）
        night = MindMapRepository.nightMode(requireContext())
        nightAction = binding.titleBar.addTextAction(getString(R.string.mindmap_graph_night)) {
            applyNight(!night, persist = true)
        }
        applyNight(night, persist = false)

        binding.graph.setFontLevel(ReciteRepository.fontLevel(requireContext()))
        // 画布里的节点是 Canvas 画的，读屏读不到逐条文字；至少给整图一句能听的概述
        binding.graph.contentDescription = getString(
            R.string.mindmap_graph_desc_fmt, topic.name, topic.branchCount, topic.nodeCount
        )
        binding.graph.setNodes(topic.nodes)
        // 画布上一条要点只有一小块，缩小时字是看不清的：长按把整句摊开，不用先放大再找
        binding.graph.onNodeLongPress = { text ->
            FbDialog.text(requireContext(), text)
        }
        binding.btnFit.setOnClickListener { binding.graph.fitToView() }
        // 一根手指也能缩放：双指是主路径，这两个按钮给躺着 / 单手看的场景兜底
        binding.btnZoomIn.setOnClickListener { binding.graph.zoomBy(ZOOM_STEP) }
        binding.btnZoomOut.setOnClickListener { binding.graph.zoomBy(1f / ZOOM_STEP) }
        binding.btnExpand.setOnClickListener {
            binding.graph.expandAll()
            binding.graph.fitToView()
        }
        binding.btnCollapse.setOnClickListener {
            binding.graph.collapseBranches()
            binding.graph.fitToView()
        }

        // 连线题是书页下半部分的「提法 ↔ 内涵」，跟导图树分开测
        val pairs = topic.pairs
        binding.btnPairs.isVisible = pairs.isNotEmpty()
        if (pairs.isNotEmpty()) {
            binding.btnPairs.setOnClickListener {
                MindMapPairs.show(requireContext(), pairs)
            }
        }
    }

    /**
     * 换肤：整页一起换，别留半张白底。
     *
     * 顺序上先定 `night` 再刷控件——[paintSystemBars] 也要读它。
     * 画布那边自己换色并清布局缓存（文字颜色是烤进 StaticLayout 的）。
     */
    private fun applyNight(on: Boolean, persist: Boolean) {
        night = on
        if (persist) MindMapRepository.setNightMode(requireContext(), on)

        fun c(dayRes: Int, nightRes: Int): Int =
            ContextCompat.getColor(requireContext(), if (on) nightRes else dayRes)

        binding.root.setBackgroundColor(c(R.color.fb_page_bg, R.color.fb_night_sheet_bg))
        binding.titleBar.applyNightMode(on)
        binding.tvHint.setTextColor(c(R.color.fb_text_meta, R.color.fb_night_text_meta))
        binding.divider.setBackgroundColor(c(R.color.fb_divider, R.color.fb_night_divider))
        listOf(
            binding.btnFit, binding.btnZoomOut, binding.btnZoomIn,
            binding.btnExpand, binding.btnCollapse,
        ).forEach { it.setTextColor(c(R.color.fb_text_secondary, R.color.fb_night_segment_text)) }
        binding.btnPairs.setTextColor(c(R.color.fb_primary, R.color.fb_night_tag_text))

        nightAction?.apply {
            text = getString(if (on) R.string.mindmap_graph_day else R.string.mindmap_graph_night)
            setTextColor(c(R.color.fb_primary, R.color.fb_night_tag_text))
        }
        binding.graph.setNightMode(on)
        paintSystemBars()
    }

    /** 系统栏跟着换：深色底 + 浅色图标，否则夜间模式下状态栏还是亮底黑字，顶端一道白 */
    @Suppress("DEPRECATION")
    private fun paintSystemBars() {
        val window = dialog?.window ?: return
        val bar = ContextCompat.getColor(
            requireContext(),
            if (night) R.color.fb_night_bar else R.color.fb_bar_bg
        )
        window.statusBarColor = bar
        window.navigationBarColor = bar
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_TOPIC = "topic_id"

        /** 点一下放大 25%：连点几次能到 3 倍上限，不会一步跳到底 */
        private const val ZOOM_STEP = 1.25f

        fun show(manager: FragmentManager, topicId: String) {
            MindMapGraphDialog().apply {
                arguments = Bundle().apply { putString(ARG_TOPIC, topicId) }
            }.show(manager, "mindmap-graph")
        }
    }
}
