package com.lytree.qbank.ui.record

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.databinding.ItemPaperBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordAdapter : RecyclerView.Adapter<RecordAdapter.VH>() {

    private val items = ArrayList<ExamRecord>()
    var onClick: ((ExamRecord) -> Unit)? = null

    fun submit(list: List<ExamRecord>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(val binding: ItemPaperBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPaperBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.binding.title.text = r.name.ifBlank { "练习记录" }
        val rate = if (r.answeredCount > 0) r.correctCount * 100 / r.answeredCount else 0
        holder.binding.subtitle.text = buildString {
            append(fmtDate(r.startedAt))
            append(" · 用时 ${fmtDuration(r.durationSec)}")
            append(" · 答 ${r.answeredCount}/${r.totalCount}")
            append(" · 正确率 $rate%")
        }
        holder.binding.score.text = String.format(Locale.CHINA, "%.1f", r.score)
        holder.binding.root.setOnClickListener { onClick?.invoke(r) }
    }

    companion object {
        private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        fun fmtDate(ts: Long): String = if (ts <= 0) "-" else sdf.format(Date(ts))
        fun fmtDuration(sec: Int): String {
            if (sec <= 0) return "0秒"
            val h = sec / 3600
            val m = (sec % 3600) / 60
            val s = sec % 60
            return when {
                h > 0 -> "${h}小时${m}分"
                m > 0 -> "${m}分${s}秒"
                else -> "${s}秒"
            }
        }
    }
}
