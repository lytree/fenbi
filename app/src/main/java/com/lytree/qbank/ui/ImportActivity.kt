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
import com.lytree.qbank.R
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.ImportIssue
import com.lytree.qbank.data.importer.Importer
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivityImportBinding
import com.lytree.qbank.databinding.ItemImportIssueBinding
import com.lytree.qbank.databinding.ItemImportQuestionBinding
import java.io.ByteArrayOutputStream
import java.io.File

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

    /** 题库包（zip）的解压根目录：图片落到应用私有目录，清缓存也不影响已有题目 */
    private lateinit var pkgDir: File
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
        pkgDir = File(filesDir, "qbank_pkg")

        binding.titleBar.setTitle(getString(R.string.title_import))
        binding.bottomBar.applyNavBarBottomPadding()
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
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream"
                )
            )
        } catch (_: Exception) {
            picker.launch(arrayOf("*/*"))
        }
    }

    /** 「载入示例」：内置样例，普通题库 / 带图材料 / zip 题库包 */
    private fun loadSample() {
        val samples = arrayOf(
            "行测基础示例.json" to "行测基础示例（11 题 · 覆盖全部选择题型）",
            "资料分析示例.json" to "资料分析示例（含折线图材料）",
            "资料分析题库包.zip" to "资料分析题库包（zip 标准结构 · 图片随包）"
        )
        AlertDialog.Builder(this)
            .setTitle("载入示例题库")
            .setItems(samples.map { it.second }.toTypedArray()) { _, which ->
                loadSample(samples[which].first)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun loadSample(name: String) {
        try {
            bytes = assets.open("samples/$name").use { it.readBytes() }
        } catch (e: Exception) {
            toast("示例文件读取失败：${e.message}")
            return
        }
        fileName = name
        format = Importer.detect(fileName, bytes)
        applyParsed(Importer.build(format, bytes, pkgDir = pkgDir))
        if (format != ImportFormat.ZIP) {
            binding.inputName.setText(name.substringBeforeLast('.'))
        }
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
        binding.bottomBar.visibility = View.VISIBLE
        binding.bottomDivider.visibility = View.VISIBLE
        binding.tvFile.text = fileName
        val missing = parsed.count { it.answer.isBlank() }
        binding.tvStat.text = buildString {
            append("格式：${outcome.format}")
            append("　总行数：${outcome.total}")
            append("　可导入：${parsed.size}")
            if (missing > 0) append("　缺答案：$missing")
            append("　警告：${outcome.warnCount}")
            append("　错误：${outcome.errorCount}")
        }
        binding.tvFile.text = fileName
        if (binding.inputName.text.isNullOrBlank()) {
            binding.inputName.setText(
                outcome.suggestedName ?: fileName.substringBeforeLast('.')
            )
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
            applyParsed(Importer.build(format, bytes, pkgDir = pkgDir))
        } catch (e: Exception) {
            toast("解析失败：${e.message}")
        }
    }

    /**
     * 导入即建卷：勾选的题目全部进同一份试卷，顺序沿用文件里的顺序，不再随机抽题组卷。
     */
    private fun doImport() {
        val selected = qAdapter.selectedItems()
        if (selected.isEmpty()) {
            toast("请至少勾选一道题")
            return
        }
        val name = binding.inputName.text?.toString()?.trim().orEmpty()
            .ifBlank { fileName.substringBeforeLast('.').trim() }
            .ifBlank { "我的试卷" }
        val durationMin = binding.inputDuration.text?.toString()?.trim()
            ?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val exists = db.findPaperByName(name) != null || db.findBankByName(name) != null
        if (exists) {
            FbDialog.confirm(
                this,
                "已存在同名试卷或题库「$name」，继续导入会覆盖原有的题目。",
                okText = "覆盖导入",
                onOk = { commitImport(name, selected, durationMin, replace = true) }
            )
        } else {
            commitImport(name, selected, durationMin, replace = false)
        }
    }

    private fun commitImport(
        name: String,
        questions: List<Question>,
        durationMin: Int,
        replace: Boolean
    ) {
        // 覆盖导入：把同名的旧卷子/旧题库全部清掉，保证「一个文件 = 一份试卷」
        if (replace) {
            db.listPapers().filter { it.name == name }.forEach { db.deletePaper(it.id) }
            db.listBanks().filter { it.name == name }.forEach { db.deleteBank(it.id) }
        }
        val result = db.importAsPaper(
            bankName = name,
            description = "导入自 $fileName",
            subject = "",
            paperName = name,
            durationMin = durationMin,
            questions = questions
        )
        toast("已生成试卷「${result.paperName}」，共 ${result.questionCount} 题")
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
        val opts = q.options.joinToString("\n") { "${it.key}. ${it.text}" }
        holder.binding.tvOptions.text = if (opts.isBlank()) "" else opts
        holder.binding.tvAnswer.text = if (q.answer.isBlank()) {
            "答案：待补答案（可做完后导入答案）"
        } else {
            "答案：${q.displayAnswer()}"
        }
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
