package com.lytree.qbank.ui.word

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.widget.doAfterTextChanged
import com.lytree.qbank.R
import com.lytree.qbank.data.model.WordTagCount
import com.lytree.qbank.databinding.DialogWordTagsBinding
import com.lytree.qbank.databinding.ItemWordTagBinding

/**
 * 标签筛选弹窗：一个分类下的所有细分标签，多选，标签之间取并集。
 *
 * 词库的细分标签是自由文本（名词 / 动词 / 褒义 / 对比辨析·第十组 …），
 * 个数随词表来源浮动，所以不做固定筛选项，走「弹窗 + 搜索 + 多选」：
 * 打开时带上当前已选，点确定才回传，中途取消不影响列表。
 */
object WordTagDialog {

    private class Row(val vh: ItemWordTagBinding, val tag: WordTagCount, val label: String)

    fun show(
        context: Context,
        scopeLabel: CharSequence,
        tags: List<WordTagCount>,
        selected: Collection<String>,
        onApply: (Set<String>) -> Unit
    ) {
        val b = DialogWordTagsBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context)
        dialog.setContentView(b.root)
        dialog.setCanceledOnTouchOutside(true)

        val chosen = LinkedHashSet(selected)
        val rows = ArrayList<Row>()
        val inflater = LayoutInflater.from(context)
        val untagged = context.getString(R.string.word_tag_untagged)

        fun updateSubtitle() {
            b.tvSubtitle.text = if (chosen.isEmpty()) {
                context.getString(R.string.word_tag_subtitle, scopeLabel, tags.size)
            } else {
                context.getString(R.string.word_tag_subtitle_picked, scopeLabel, tags.size, chosen.size)
            }
        }

        tags.forEach { tag ->
            val vh = ItemWordTagBinding.inflate(inflater, b.tagList, false)
            val label = if (tag.name.isEmpty()) untagged else tag.name
            vh.tvName.text = label
            vh.tvCount.text = tag.count.toString()
            vh.cb.isChecked = chosen.contains(tag.name)

            vh.root.setOnClickListener {
                if (chosen.contains(tag.name)) {
                    chosen.remove(tag.name)
                    vh.cb.isChecked = false
                } else {
                    chosen.add(tag.name)
                    vh.cb.isChecked = true
                }
                updateSubtitle()
            }
            // 勾选框本身不接事件（clickable/focusable 都是 false），点击统一走整行
            rows.add(Row(vh, tag, label))
            b.tagList.addView(vh.root)
        }

        updateSubtitle()
        b.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        b.etSearch.doAfterTextChanged { text ->
            val q = text?.toString()?.trim().orEmpty()
            var visible = 0
            rows.forEach { row ->
                val hit = q.isEmpty() || row.label.contains(q, ignoreCase = true)
                row.vh.root.visibility = if (hit) View.VISIBLE else View.GONE
                if (hit) visible++
            }
            b.tvEmpty.visibility = if (visible == 0) View.VISIBLE else View.GONE
        }

        b.btnClear.setOnClickListener {
            chosen.clear()
            rows.forEach { it.vh.cb.isChecked = false }
            updateSubtitle()
        }
        b.btnCancel.setOnClickListener { dialog.dismiss() }
        b.btnOk.setOnClickListener {
            dialog.dismiss()
            onApply(chosen)
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
        // setLayout 必须在 show 之后：show 之前设置会被 Dialog 自己的窗口尺寸覆盖，
        // 结果就是列表被压成半屏、标签看不到几行
        dialog.window?.apply {
            val dm = context.resources.displayMetrics
            val w = minOf((420 * dm.density).toInt(), dm.widthPixels - (48 * dm.density).toInt())
            val h = (dm.heightPixels * 0.78f).toInt()
            setLayout(w, h)
            setGravity(Gravity.CENTER)
        }
    }
}
