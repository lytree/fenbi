package com.lytree.qbank.ui.word

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
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
 *
 * **长按任意标签 → 进入删除模式**：勾选框变成「要删的标签」，可多选，
 * 底部按钮变成「删除选中」，确认后把标签从所有词条上摘掉（词条保留）。
 */
object WordTagDialog {

    private class Row(val vh: ItemWordTagBinding, val tag: WordTagCount, val label: String)

    fun show(
        context: Context,
        scopeLabel: CharSequence,
        tags: List<WordTagCount>,
        selected: Collection<String>,
        onApply: (Set<String>) -> Unit,
        onDelete: ((Set<String>) -> Unit)? = null
    ) {
        val b = DialogWordTagsBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context)
        dialog.setContentView(b.root)
        dialog.setCanceledOnTouchOutside(true)

        /** 筛选用的已选标签 */
        val chosen = LinkedHashSet(selected)
        /** 删除模式下待删的标签 */
        val doomed = LinkedHashSet<String>()
        var deleteMode = false

        val rows = ArrayList<Row>()
        val inflater = LayoutInflater.from(context)
        val untagged = context.getString(R.string.word_tag_untagged)

        fun renderRow(row: Row) {
            val checked = if (deleteMode) row.tag.name.isNotEmpty() && row.tag.name in doomed
            else row.tag.name in chosen
            row.vh.cb.isChecked = checked
        }

        fun updateSubtitle() {
            b.tvSubtitle.text = when {
                deleteMode -> context.getString(R.string.word_tag_delete_mode)
                chosen.isEmpty() -> context.getString(R.string.word_tag_subtitle, scopeLabel, tags.size)
                else -> context.getString(
                    R.string.word_tag_subtitle_picked, scopeLabel, tags.size, chosen.size
                )
            }
        }

        fun updateButtons() {
            if (deleteMode) {
                b.btnClear.visibility = View.GONE
                b.btnCancel.setText(R.string.action_cancel)
                b.btnOk.setText(R.string.word_tag_delete_action)
            } else {
                b.btnClear.visibility = View.VISIBLE
                b.btnCancel.setText(R.string.action_cancel)
                b.btnOk.setText(R.string.action_confirm)
            }
        }

        fun enterDeleteMode(first: String) {
            if (onDelete == null) return
            deleteMode = true
            doomed.clear()
            if (first.isNotEmpty()) doomed.add(first)
            rows.forEach { renderRow(it) }
            updateSubtitle()
            updateButtons()
        }

        fun exitDeleteMode() {
            deleteMode = false
            doomed.clear()
            rows.forEach { renderRow(it) }
            updateSubtitle()
            updateButtons()
        }

        tags.forEach { tag ->
            val vh = ItemWordTagBinding.inflate(inflater, b.tagList, false)
            val label = if (tag.name.isEmpty()) untagged else tag.name
            vh.tvName.text = label
            vh.tvCount.text = tag.count.toString()
            vh.cb.isChecked = chosen.contains(tag.name)

            val row = Row(vh, tag, label)
            rows.add(row)

            vh.root.setOnClickListener {
                if (deleteMode) {
                    // 「未标注标签」不是真标签，删除模式里点它不选
                    if (tag.name.isEmpty()) return@setOnClickListener
                    if (!doomed.remove(tag.name)) doomed.add(tag.name)
                    vh.cb.isChecked = tag.name in doomed
                } else {
                    if (chosen.contains(tag.name)) {
                        chosen.remove(tag.name)
                        vh.cb.isChecked = false
                    } else {
                        chosen.add(tag.name)
                        vh.cb.isChecked = true
                    }
                    updateSubtitle()
                }
            }
            // 长按进删除模式（「未标注标签」没有可删的实体标签，不响应）
            vh.root.setOnLongClickListener {
                if (tag.name.isEmpty()) return@setOnLongClickListener false
                if (!deleteMode) enterDeleteMode(tag.name) else {
                    if (!doomed.remove(tag.name)) doomed.add(tag.name)
                    vh.cb.isChecked = tag.name in doomed
                }
                true
            }
            b.tagList.addView(vh.root)
        }

        updateSubtitle()
        updateButtons()
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
        b.btnCancel.setOnClickListener {
            if (deleteMode) exitDeleteMode() else dialog.dismiss()
        }
        b.btnOk.setOnClickListener {
            if (!deleteMode) {
                dialog.dismiss()
                onApply(chosen)
                return@setOnClickListener
            }
            if (doomed.isEmpty()) {
                exitDeleteMode()
                return@setOnClickListener
            }
            val toDelete = doomed.toSet()
            AlertDialog.Builder(context)
                .setTitle(R.string.word_tag_delete_confirm_title)
                .setMessage(context.getString(R.string.word_tag_delete_confirm_msg, toDelete.size))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.word_tag_delete_action) { _, _ ->
                    dialog.dismiss()
                    onDelete?.invoke(toDelete)
                }
                .show()
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
