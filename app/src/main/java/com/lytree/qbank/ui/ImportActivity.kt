package com.lytree.qbank.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.ImportIssue
import com.lytree.qbank.data.importer.Importer
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivityImportBinding
import com.lytree.qbank.databinding.ItemImportIssueBinding
import com.lytree.qbank.databinding.ItemImportQuestionBinding
import java.io.ByteArrayOutputStream

/**
 * 题库导入：选文件 → 解析 → 校验预览 → 勾选 → 入库。
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding
    private lateinit var db: QBankDb

    private val qAdapter = PreviewAdapter()
    private val issueAdapter = IssueAdapter()

    private var fileName: String = ""
    private var format: ImportFormat = ImportFormat.JSON
    private var bytes: ByteArray = ByteArray(0)
    private var parsed: List<Question> = emptyList()
    private var issues: List<ImportIssue> = emptyList()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnPick.setOnClickListener { pickFile() }
        binding.btnSample.setOnClickListener { loadSample() }
        binding.btnAll.setOnClickListener { qAdapter.toggleAll() }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = qAdapter
        binding.listIssues.layoutManager = LinearLayoutManager(this)
        binding.listIssues.adapter = issueAdapter

        binding.btnImport.setOnClickListener { doImport() }

        handleIncomingIntent(intent)
    }

    /** 支持从外部（文件管理器 / 分享）直接打开题库文件 */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val candidate: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        val uri = candidate ?: return
        loadFile(uri)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun pickFile() {
        try {
            picker.launch(
                arrayOf(
                    "application/json",
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/octet-stream"
                )
            )
        } catch (_: Exception) {
            picker.launch(arrayOf("*/*"))
        }
    }

    private fun loadSample() {
        val name = "行测基础示例.json"
        try {
            bytes = assets.open("samples/$name").use { it.readBytes() }
        } catch (e: Exception) {
            toast("示例文件读取失败：${e.message}")
            return
        }
        fileName = name
        format = Importer.detect(fileName, bytes)
        applyParsed(Importer.build(format, bytes))
        binding.inputName.setText("行测基础示例")
    }

    private fun applyParsed(outcome: com.lytree.qbank.data.importer.ImportOutcome) {
        parsed = outcome.questions
        issues = outcome.issues

        if (parsed.isEmpty()) {
            toast("没有解析出任何题目，请检查文件格式")
        }

        binding.pickPanel.visibility = View.GONE
        binding.resultPanel.visibility = View.VISIBLE
        binding.btnImport.visibility = View.VISIBLE
        binding.tvFile.text = fileName
        binding.tvStat.text = buildString {
            append("格式：${outcome.format}")
            append("　总行数：${outcome.total}")
            append("　可导入：${parsed.size}")
            append("　警告：${outcome.warnCount}")
            append("　错误：${outcome.errorCount}")
        }
        if (binding.inputName.text.isNullOrBlank()) {
            binding.inputName.setText(fileName.substringBeforeLast('.'))
        }
        qAdapter.submit(parsed)
        issueAdapter.submit(issues)
        binding.tvIssuesTitle.visibility = if (issues.isEmpty()) View.GONE else View.VISIBLE
        binding.listIssues.visibility = if (issues.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun loadFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bos = ByteArrayOutputStream()
                input.copyTo(bos)
                bytes = bos.toByteArray()
            } ?: return toast("无法读取所选文件")

            fileName = displayName(uri)
            format = Importer.detect(fileName, bytes)
            applyParsed(Importer.build(format, bytes))
        } catch (e: Exception) {
            toast("解析失败：${e.message}")
        }
    }

    private fun doImport() {
        val selected = qAdapter.selectedItems()
        if (selected.isEmpty()) {
            toast("请至少勾选一道题")
            return
        }
        val name = binding.inputName.text?.toString()?.trim().orEmpty().ifBlank { "我的题库" }
        val bankId = db.insertBank(name, "导入自 $fileName", "")
        val n = db.insertQuestions(bankId, selected)
        toast("导入成功：$n 道题 → $name")
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment ?: "导入文件"
            } ?: (uri.lastPathSegment ?: "导入文件")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "导入文件"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

/** 预览列表 */
class PreviewAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<PreviewAdapter.VH>() {
    private val items = ArrayList<Question>()
    private val selected = HashSet<Int>()

    fun submit(list: List<Question>) {
        items.clear(); items.addAll(list)
        selected.clear()
        items.indices.forEach { selected.add(it) }
        notifyDataSetChanged()
    }

    fun selectedItems(): List<Question> = items.filterIndexed { i, _ -> i in selected }

    fun toggleAll() {
        if (selected.size == items.size) selected.clear() else items.indices.forEach { selected.add(it) }
        notifyDataSetChanged()
    }

    class VH(val binding: ItemImportQuestionBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemImportQuestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val q = items[position]
        holder.binding.cb.setOnCheckedChangeListener(null)
        holder.binding.cb.isChecked = position in selected
        holder.binding.cb.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(position) else selected.remove(position)
        }
        holder.binding.tvType.text = q.type.label
        holder.binding.tvStem.text = q.stem
        val opts = q.options.joinToString("  ") { "${it.key}. ${it.text}" }
        holder.binding.tvOptions.text = if (opts.isBlank()) "" else opts
        holder.binding.tvAnswer.text = "答案：${q.displayAnswer()}"
    }
}

/** 校验问题列表 */
class IssueAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<IssueAdapter.VH>() {
    private val items = ArrayList<ImportIssue>()

    fun submit(list: List<ImportIssue>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(val binding: ItemImportIssueBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemImportIssueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        holder.binding.tvText.text = "第 ${it.rowNo} 行 · ${it.message}"
        holder.binding.tvText.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                holder.itemView.context,
                if (it.level == ImportIssue.Level.ERROR) com.lytree.qbank.R.color.wrong
                else com.lytree.qbank.R.color.partial
            )
        )
    }
}
