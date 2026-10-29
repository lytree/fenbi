package com.lytree.qbank.ui.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.databinding.FragmentSettingsBinding
import com.lytree.qbank.ui.FbSwitchView
import com.lytree.qbank.ui.PenColors
import com.lytree.qbank.ui.Refreshable
import com.lytree.qbank.ui.record.RecordListFragment

/**
 * 判题规则 + 手写标注设置。不同机构判题口径不同，这里让用户自己定。
 * 开关用与「更多」面板同一个自绘组件 [FbSwitchView]，保证全站视觉一致。
 *
 * 「笔迹颜色」跟答题页工具条上的那枚色点共用 [PenColors] 里的同一份偏好：
 * 这里选好之后，答题页写画直接用；在答题页里随手换色，回来这里也是新的。
 *
 * 本页是「记录」页的二级页（右上角齿轮进入），不占底部导航。
 */
class SettingsFragment : Fragment(), Refreshable {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: QBankDb

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = QBankDb.get(requireContext())
        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle(getString(R.string.page_settings))

        // 二级页：从「记录」页右上角齿轮进来，标题栏与系统返回都回记录列表
        binding.titleBar.showBack(true)
        binding.titleBar.onBack = { backToRecords() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) { backToRecords() }

        val p = db.getPolicy()
        binding.swMulti.setChecked(p.multiPartial)
        binding.swUncertain.setChecked(p.uncertainPartial)
        binding.swFill.setChecked(p.fillPartial)
        binding.swSubjective.setChecked(p.subjectivePartial)
        binding.swJudgeLenient.setChecked(p.judgeLenient)

        val save = {
            db.setPolicy(
                com.lytree.qbank.engine.Policy(
                    multiPartial = binding.swMulti.isChecked,
                    uncertainPartial = binding.swUncertain.isChecked,
                    uncertainPartialRatio = 0.5,
                    fillPartial = binding.swFill.isChecked,
                    subjectivePartial = binding.swSubjective.isChecked,
                    judgeLenient = binding.swJudgeLenient.isChecked
                )
            )
        }
        listOf(
            binding.swMulti, binding.swUncertain, binding.swFill,
            binding.swSubjective, binding.swJudgeLenient
        ).forEach { it.onCheckedChange = { _ -> save() } }

        renderPenColors()
    }

    // ---------------- 笔迹颜色 ----------------

    /** 五色圆点：选中的那枚外面套一圈主色描边，一眼看出当前用哪支笔 */
    private fun renderPenColors() {
        val row = binding.colorRow
        row.removeAllViews()
        val current = PenColors.load(requireContext())

        PenColors.items.forEach { item ->
            val dot = View(requireContext())
            val size = resources.getDimensionPixelSize(R.dimen.fb_pen_dot)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = resources.getDimensionPixelSize(R.dimen.fb_pen_dot_gap)
            dot.layoutParams = lp
            dot.background = swatch(item.color, item.color == current)
            dot.contentDescription = "${item.label}笔"
            dot.setOnClickListener {
                PenColors.save(requireContext(), item.color)
                renderPenColors()
            }
            row.addView(dot)
        }

        binding.tvColorHint.text = getString(
            R.string.settings_pen_color_hint,
            PenColors.labelOf(current)
        )
    }

    /** 未选中：纯色圆；选中：白色底 + 主色描边，中间留 4dp 空隙，看着像一枚被圈住的色块 */
    private fun swatch(color: Int, selected: Boolean): android.graphics.drawable.Drawable {
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        if (!selected) {
            fill.setStroke(
                dp(1),
                ContextCompat.getColor(requireContext(), R.color.fb_dot_stroke)
            )
            return fill
        }
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(
                dp(2),
                ContextCompat.getColor(requireContext(), R.color.fb_primary)
            )
        }
        return LayerDrawable(arrayOf(ring, fill)).apply {
            val inset = dp(4)
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    /** 回记录页：本页不占底部导航，返回即把内容换回记录列表，底部仍高亮「记录」 */
    private fun backToRecords() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, RecordListFragment())
            .commit()
    }

    override fun refresh() = Unit

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
