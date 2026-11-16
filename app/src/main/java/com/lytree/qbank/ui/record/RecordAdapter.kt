package com.lytree.qbank.ui.record

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lytree.qbank.R
import com.lytree.qbank.data.model.ExamRecord
import com.lytree.qbank.databinding.ItemListFooterBinding
import com.lytree.qbank.databinding.ItemRecordBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 练习记录列表。
 *
 * 一行 = 一场练习（进答题页那一刻就落库，不等交卷）：
 * - **已完成**（交了卷）：右上角是得分，副标题给出用时、答对率、标记数，点开是成绩报告；
 * - **未完成**（中途退出 / 重新开始）：右上角是「继续」，副标题给出已答几题、已经做了多久，
 *   点开回到答题页接着做。
 *
 * 行尾「⋯」是这一条的菜单（查看 / 继续 / 删除），长按等价。
 *
 * 记录只增不减（每进一次卷就留一条），列表按 [PAGE_SIZE] 一页往下翻：尾部多一行状态，
 * 还在取就转圈、取完了说一句「全部在这了」。记录少于一页时这一行不出现，别为三条记录加一句废话。
 */
class RecordAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<ExamRecord>()
    /** 库里还有没有下一页 */
    private var hasMore = false
    /** 正在取下一页：尾部转圈，同时挡住滚动回调重复请求 */
    private var loading = false

    var onClick: ((ExamRecord) -> Unit)? = null
    /** 行内「⋯」与长按都走这里：查看 / 继续 / 删除由页面决定 */
    var onActions: ((ExamRecord) -> Unit)? = null

    /** 整表替换（首屏 / 刷新 / 删除后重载） */
    fun submit(list: List<ExamRecord>, hasMore: Boolean) {
        items.clear()
        items.addAll(list)
        this.hasMore = hasMore
        loading = false
        notifyDataSetChanged()
    }

    /** 追加下一页；[more] 为空（并发删除把尾巴抽走了）也算到底，避免一直转圈 */
    fun append(more: List<ExamRecord>, hasMore: Boolean) {
        this.loading = false
        this.hasMore = hasMore
        if (more.isEmpty()) {
            if (showFooter) notifyItemChanged(items.size)
            return
        }
        val from = items.size
        items.addAll(more)
        notifyItemRangeInserted(from, more.size)
        // 尾部状态行被顶到新位置，旧位置已经是新记录：只需要重写新位置那一行
        if (showFooter) notifyItemChanged(items.size)
    }

    /** 尾部切到 / 退出「正在加载」 */
    fun setLoading(value: Boolean) {
        if (loading == value) return
        loading = value
        if (showFooter) notifyItemChanged(items.size)
    }

    /** 尾部状态行在最后一行（items.size）；一页以内且已到底时干脆不显示 */
    private val showFooter: Boolean
        get() = items.isNotEmpty() && (hasMore || items.size > PAGE_SIZE)

    private fun isFooter(position: Int) = showFooter && position == items.size

    class VH(val binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root)
    class FooterVH(val binding: ItemListFooterBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = if (isFooter(position)) TYPE_FOOTER else TYPE_RECORD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOOTER) FooterVH(ItemListFooterBinding.inflate(inflater, parent, false))
        else VH(ItemRecordBinding.inflate(inflater, parent, false))
    }

    override fun getItemCount() = items.size + if (showFooter) 1 else 0

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is FooterVH) {
            val ctx = holder.itemView.context
            holder.binding.footerProgress.visibility = if (loading) View.VISIBLE else View.GONE
            holder.binding.footerText.text = when {
                loading -> ctx.getString(R.string.record_footer_loading)
                hasMore -> ctx.getString(R.string.record_footer_more)
                else -> ctx.getString(R.string.record_footer_end, items.size)
            }
            return
        }
        bindRecord(holder as VH, items[position])
    }

    private fun bindRecord(holder: VH, r: ExamRecord) {
        val ctx = holder.itemView.context
        holder.binding.title.text = r.displayTitle

        // 完成 / 未完成标签：已交卷用主色，没做完用灰——一眼能分出哪些能看成绩
        holder.binding.tvStatus.text =
            ctx.getString(if (r.isDone) R.string.record_status_done else R.string.record_status_ongoing)
        holder.binding.tvStatus.setTextColor(
            ContextCompat.getColor(ctx, if (r.isDone) R.color.right else R.color.fb_text_meta)
        )

        holder.binding.subtitle.text = buildString {
            append(fmtDate(r.startedAt))
            append(" · 用时 ${fmtDuration(r.durationSec)}")
            append(" · 答 ${r.answeredCount}/${r.totalCount}")
            if (r.isDone) {
                val rate = if (r.answeredCount > 0) r.correctCount * 100 / r.answeredCount else 0
                append(" · 正确率 $rate%")
                // 本次作答标记过几题：标记已随记录落库，这里顺手给个总数
                if (r.marked.isNotEmpty()) append(" · 标记 ${r.marked.size} 题")
            } else {
                append(" · ")
                append(
                    ctx.getString(
                        if (r.isOngoing) R.string.record_tap_continue else R.string.record_interrupted
                    )
                )
            }
        }

        // 已完成给分数，未完成给「继续」——同一列位置，扫一眼就知道这条能不能看成绩
        holder.binding.score.text =
            if (r.isDone) String.format(Locale.CHINA, "%.1f", r.score)
            else ctx.getString(R.string.record_continue)

        holder.binding.root.setOnClickListener { onClick?.invoke(r) }
        // 「⋯」是删除等操作的入口（长按仍然等价，只是没人猜得到）
        holder.binding.btnMore.visibility = if (onActions == null) View.GONE else View.VISIBLE
        holder.binding.btnMore.setOnClickListener { onActions?.invoke(r) }
        holder.binding.root.isLongClickable = onActions != null
        holder.binding.root.setOnLongClickListener(
            if (onActions == null) null else View.OnLongClickListener { onActions?.invoke(r); true }
        )
    }

    companion object {
        /** 一页取多少条：一屏七八条，一页给两屏多，滚到底之前下一页通常已经回来了 */
        const val PAGE_SIZE = 20

        private const val TYPE_RECORD = 0
        private const val TYPE_FOOTER = 1

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
