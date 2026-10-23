package com.lytree.qbank.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.databinding.ActivityMaterialPopupBinding

/**
 * 「弹出材料」只读页。
 *
 * 一则材料只带一道题时，从材料卡右上角「弹出材料」进入：整页只显示材料，
 * A- / A+ 调字号。材料带多道题时不再进这里——答题页会**就地左右分屏**
 * （左材料 / 右题目，见 [ExamActivity.openSplit]），对齐参考应用平板端的交互。
 */
class MaterialPopupActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MATERIAL = "material"
        const val EXTRA_NIGHT = "night"
        const val EXTRA_FONT_SCALE = "font_scale"
    }

    private lateinit var binding: ActivityMaterialPopupBinding

    private var night = false
    private var fontScale = 1f
    private var material = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMaterialPopupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        night = intent.getBooleanExtra(EXTRA_NIGHT, false)
        fontScale = intent.getFloatExtra(EXTRA_FONT_SCALE, 1f)
        material = intent.getStringExtra(EXTRA_MATERIAL).orEmpty()
        if (material.isBlank()) { finish(); return }

        setupTitleBar()
        applyNightMode()
        renderMaterial()
    }

    private fun setupTitleBar() {
        binding.titleBar.setTitle(getString(R.string.split_material_title))
        binding.titleBar.addTextAction("A-") {
            fontScale = (fontScale - 0.15f).coerceAtLeast(0.85f)
            renderMaterial()
        }
        binding.titleBar.addTextAction("A+") {
            fontScale = (fontScale + 0.15f).coerceAtMost(1.6f)
            renderMaterial()
        }
    }

    private fun renderMaterial() {
        // 页面空间比题目卡里大，图表按 1.4 倍高画，刻度与折线更好读
        MaterialRenderer.render(this, binding.materialContent, material, night, fontScale, chartScale = 1.4f)
        binding.materialContent.applyNavBarBottomPadding()
        binding.materialScroll.scrollTo(0, 0)
    }

    private fun applyNightMode() {
        binding.root.setBackgroundColor(color(R.color.fb_night_bg, R.color.fb_page_bg))
        binding.titleBar.setBackgroundColor(color(R.color.fb_night_bar, R.color.fb_bar_bg))
        binding.titleBar.findViewById<TextView>(R.id.tvTitle)?.setTextColor(
            color(R.color.fb_night_text, R.color.fb_text_title)
        )
        binding.topDivider.setBackgroundColor(color(R.color.fb_night_divider, R.color.divider))
        binding.leftPane.setBackgroundColor(color(R.color.fb_night_material_bg, R.color.fb_split_bg))
    }

    private fun color(nightRes: Int, dayRes: Int): Int =
        ContextCompat.getColor(this, if (night) nightRes else dayRes)
}
