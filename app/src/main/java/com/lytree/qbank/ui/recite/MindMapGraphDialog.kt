package com.lytree.qbank.ui.recite

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.lytree.qbank.R
import com.lytree.qbank.data.MindMapRepository
import com.lytree.qbank.data.ReciteRepository
import com.lytree.qbank.databinding.DialogMindmapGraphBinding

/**
 * 一页书一张导图，全屏看。
 *
 * 列表页只负责挑主题（板块筛选 + 主题卡）；真正看结构进这里：
 * 画布铺满、双指缩放、拖动平移、点节点折叠，底部还有「适应屏幕 / 展开全部 / 只看主干」，
 * 这一页带「内涵连线」的主题会多一颗配对按钮。
 *
 * 用对话框而不是换 Fragment：关掉就回到原来的列表，滚动位置和板块筛选都还在。
 */
class MindMapGraphDialog : DialogFragment() {

    private var _binding: DialogMindmapGraphBinding? = null
    private val binding get() = _binding!!

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

        binding.graph.setFontLevel(ReciteRepository.fontLevel(requireContext()))
        // 画布里的节点是 Canvas 画的，读屏读不到逐条文字；至少给整图一句能听的概述
        binding.graph.contentDescription = getString(
            R.string.mindmap_graph_desc_fmt, topic.name, topic.branchCount, topic.nodeCount
        )
        binding.graph.setNodes(topic.nodes)
        binding.btnFit.setOnClickListener { binding.graph.fitToView() }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_TOPIC = "topic_id"

        fun show(manager: FragmentManager, topicId: String) {
            MindMapGraphDialog().apply {
                arguments = Bundle().apply { putString(ARG_TOPIC, topicId) }
            }.show(manager, "mindmap-graph")
        }
    }
}
