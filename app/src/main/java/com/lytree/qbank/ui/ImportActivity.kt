package com.lytree.qbank.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.R
import com.lytree.qbank.data.db.PaperImport
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.importer.ImportFormat
import com.lytree.qbank.data.importer.ImportIssue
import com.lytree.qbank.data.importer.Importer
import com.lytree.qbank.data.importer.MultiPaperOutcome
import com.lytree.qbank.data.importer.PaperDraft
import com.lytree.qbank.data.importer.UrlDownloader
import com.lytree.qbank.data.model.Question
import com.lytree.qbank.databinding.ActivityImportBinding
import com.lytree.qbank.databinding.DialogImportUrlBinding
import com.lytree.qbank.databinding.ItemImportIssueBinding
import com.lytree.qbank.databinding.ItemImportQuestionBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 试卷导入：选文件 → 解析 → 校验预览 → 勾选 → 建卷。
 *
 * 一个文件可以是**一份卷子，也可以是几份**（一个工作表 = 一份试卷，「试卷」列可在表内再分，
 * 规则见 [com.lytree.qbank.data.importer.PaperSplitter]）。拆出多卷时顶部多一排卷名胶囊，
 * 预览与勾选按卷切换，最后一次性把勾到题的卷全部建出来。
 *
 * 同时也是全 App 唯一的导入入口：导入得到的直接是试卷（没有单独的题库层），
 * 词表不进这一页，由「导入词表」卡片分发到 [com.lytree.qbank.ui.word.WordImportActivity]。
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding
    private lateinit var db: QBankDb

    private val qAdapter = PreviewAdapter()
    private val issueAdapter = IssueAdapter()

    private var fileName: String = ""
    private var format: ImportFormat = ImportFormat.XLSX
    private var bytes: ByteArray = ByteArray(0)

    /** 题库包（zip）的解压根目录：图片落到应用私有目录，清缓存也不影响已有题目 */
    private lateinit var pkgDir: File

    /** 文件拆出来的各卷（带答案的原始样貌） */
    private var papers: List<PaperDraft> = emptyList()
    /** 文件级问题：解析失败、多工作表说明、包内择优等，不属于某一卷 */
    private var fileIssues: List<ImportIssue> = emptyList()

    /** 当前预览的是第几卷（单卷文件恒为 0） */
    private var activePaper = 0
    /** 每卷各自的勾选：来回切卷不丢，导入时按卷取 */
    private val selections = HashMap<Int, Set<Int>>()
    /** 卷名胶囊（与 [papers] 一一对应）：勾选变了只改文案，不重建视图 */
    private val chips = ArrayList<TextView>()

    private var outcomeFormat: String = ""
    private var outcomeTotal: Int = 0

    /** 是否把答案一起导入（关掉 = 只导题目，做完在成绩报告里补录） */
    private var importAnswers = true

    /** 链接下载任务：弹窗关掉时一起取消，避免下载完还闯进预览页 */
    private var downloadJob: Job? = null

    /** 解析任务：换文件 / 退出页面时取消，避免旧结果覆盖新预览 */
    private var parseJob: Job? = null

    /** 上一次自动填的试卷名：用户没改过才跟着新文件变，改过就尊重用户的输入 */
    private var lastAutoName: String = ""

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
        binding.btnLink.setOnClickListener { showLinkDialog() }
        binding.btnSample.setOnClickListener { loadSample() }
        // 词表导入是同一入口下的另一条分支：这里只做跳转，不在本页混排词条预览
        binding.btnWordImport.setOnClickListener {
            startActivity(Intent(this, com.lytree.qbank.ui.word.WordImportActivity::class.java))
        }
        binding.btnAll.setOnClickListener {
            qAdapter.toggleAll()
            updateChipLabels()
        }
        // 逐题勾选时卷名胶囊上的题数跟着变，用户一眼能看出哪卷被勾空了
        qAdapter.onSelectionChanged = { updateChipLabels() }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = qAdapter
        binding.listIssues.layoutManager = LinearLayoutManager(this)
        binding.listIssues.adapter = issueAdapter

        binding.btnImport.setOnClickListener { doImport() }

        // 默认把答案一起导入；关掉就只导题目（卷子上不带答案，做完再补录）
        binding.switchImportAnswers.setChecked(true)
        binding.switchImportAnswers.onCheckedChange = { on ->
            importAnswers = on
            refreshPreview(keepSelection = true)
        }

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

    /**
     * 「从链接导入」：输入下载链接 → 后台下载 → 复用本地文件的格式探测与预览流程。
     *
     * 下载期间弹窗内显示进度；关掉弹窗会取消任务，不会下载完再跳预览。
     */
    private fun showLinkDialog() {
        val b = DialogImportUrlBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
        dialog.setContentView(b.root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = resources.displayMetrics
            val maxW = (360 * dm.density).toInt()
            val w = minOf(maxW, dm.widthPixels - (48 * dm.density).toInt())
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.setOnDismissListener { downloadJob?.cancel() }

        b.btnCancel.setOnClickListener { dialog.dismiss() }
        b.btnOk.setOnClickListener {
            val raw = b.inputUrl.text?.toString()?.trim().orEmpty()
            if (UrlDownloader.normalize(raw) == null) {
                b.inputLayoutUrl.error = getString(R.string.import_link_invalid)
                return@setOnClickListener
            }
            b.inputLayoutUrl.error = null
            startDownload(raw, b, dialog)
        }

        dialog.show()
        b.inputUrl.requestFocus()
    }

    private fun startDownload(url: String, b: DialogImportUrlBinding, dialog: Dialog) {
        b.btnOk.isEnabled = false
        b.progress.visibility = View.VISIBLE
        b.tvStatus.visibility = View.VISIBLE
        b.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.fb_text_meta))
        b.tvStatus.text = getString(R.string.import_link_downloading)

        downloadJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { UrlDownloader.download(url) }
                downloadJob = null
                dialog.dismiss()
                applyDownloaded(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                b.progress.visibility = View.GONE
                b.tvStatus.setTextColor(ContextCompat.getColor(this@ImportActivity, R.color.wrong))
                b.tvStatus.text = getString(R.string.import_link_failed, e.message ?: "未知错误")
                b.btnOk.isEnabled = true
            }
        }
    }

    /** 下载完成的字节按本地文件同样处理：探测格式 → 补扩展名 → 解析预览 */
    private fun applyDownloaded(result: UrlDownloader.Result) {
        format = Importer.detect(result.fileName, result.bytes)
        fileName = ensureExtension(result.fileName, format)
        parseAsync("正在解析「$fileName」…") { fileName to result.bytes }
    }

    /** 链接常以 download?id=1 结尾、拿不到扩展名，按内容探测结果补一个，卷名才好取 */
    private fun ensureExtension(name: String, format: ImportFormat): String {
        if (ImportFormat.of(name) != null) return name
        val base = name.substringBeforeLast('.', name).ifBlank { "导入文件" }
        return "$base.${format.ext}"
    }

    /** 「载入示例」：内置样例，普通题库 / 带图材料 / zip 题库包 */
    private fun loadSample() {
        val samples = arrayOf(
            "行测基础示例.xlsx" to "行测基础示例（11 题 · 覆盖全部选择题型）",
            "多套卷示例.xlsx" to "多套卷示例（2 个工作表 · 一次导入 2 份试卷）",
            "资料分析示例.xlsx" to "资料分析示例（含图表材料）",
            "资料分析题库包.zip" to "资料分析题库包（Excel + 图片 · 图片随包）"
        )
        FbDialog.list(
            context = this,
            title = "载入示例题库",
            items = samples.map { it.second }.toTypedArray(),
            onPick = { which -> loadSample(samples[which].first) }
        )
    }

    private fun loadSample(name: String) {
        val data = try {
            assets.open("samples/$name").use { it.readBytes() }
        } catch (e: Exception) {
            toast("示例文件读取失败：${e.message}")
            return
        }
        parseAsync("正在解析「$name」…") { name to data }
    }

    private fun applyParsed(outcome: MultiPaperOutcome) {
        papers = outcome.papers
        fileIssues = outcome.issues
        outcomeFormat = outcome.format
        outcomeTotal = outcome.totalRows
        activePaper = 0
        selections.clear()

        if (papers.isEmpty()) {
            toast("没有解析出任何题目，请检查文件格式")
        }

        binding.pickPanel.visibility = View.GONE
        binding.resultPanel.visibility = View.VISIBLE
        binding.btnImport.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        binding.bottomDivider.visibility = View.VISIBLE
        binding.tvFile.text = fileName

        // 卷名跟着文件走，但用户手动改过就不再覆盖；多卷时每卷自带卷名，输入框收起
        val autoName = papers.firstOrNull()?.name ?: fileName.substringBeforeLast('.')
        val current = binding.inputName.text?.toString()?.trim().orEmpty()
        if (current.isEmpty() || current == lastAutoName) {
            binding.inputName.setText(autoName)
            lastAutoName = autoName
        }

        val multi = papers.size > 1
        binding.multiPanel.visibility = if (multi) View.VISIBLE else View.GONE
        binding.inputLayoutName.visibility = if (multi) View.GONE else View.VISIBLE
        binding.btnAll.visibility = if (papers.isEmpty()) View.GONE else View.VISIBLE
        binding.btnImport.isEnabled = papers.isNotEmpty()
        binding.btnImport.text = if (multi) {
            getString(R.string.import_confirm_papers, papers.size)
        } else {
            getString(R.string.import_confirm_paper)
        }
        if (multi) {
            binding.tvMultiTitle.text =
                getString(R.string.import_multi_title, papers.size, papers.sumOf { it.questions.size })
        }

        renderChips()
        refreshPreview(keepSelection = false)
    }

    /** 卷名胶囊：选中态换实心背景，题数跟着勾选实时变 */
    private fun renderChips() {
        chips.clear()
        binding.chipRow.removeAllViews()
        if (papers.size <= 1) return
        val margin = (6 * resources.displayMetrics.density).toInt()
        papers.forEachIndexed { i, paper ->
            val chip = LayoutInflater.from(this)
                .inflate(R.layout.item_import_paper_chip, binding.chipRow, false) as TextView
            val sel = selections[i]
            val count = if (sel == null) questionsOf(paper).size else sel.size
            val on = i == activePaper
            chip.text = getString(R.string.import_paper_chip, paper.name, count)
            chip.setBackgroundResource(
                if (on) R.drawable.bg_fb_chip_selected else R.drawable.bg_fb_chip
            )
            chip.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (on) R.color.fb_text_on_primary else R.color.fb_text_secondary
                )
            )
            // 整卷都没勾的卷子淡一点：导入时不会建卷，提前透个底
            chip.alpha = if (!on && count == 0) 0.55f else 1f
            chip.setOnClickListener { switchPaper(i) }
            binding.chipRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = margin }
            )
            chips.add(chip)
        }
    }

    /** 只改胶囊上的题数（勾选在逐个点，重建视图会跳滚动位置） */
    private fun updateChipLabels() {
        if (papers.size <= 1 || chips.size != papers.size) return
        papers.forEachIndexed { i, paper ->
            val sel = if (i == activePaper) qAdapter.selection() else selections[i]
            val count = if (sel == null) questionsOf(paper).size else sel.size
            chips[i].text = getString(R.string.import_paper_chip, paper.name, count)
            chips[i].alpha = if (i == activePaper || count > 0) 1f else 0.55f
        }
    }

    /** 切卷：先把当前卷的勾选存下来，再按目标卷的勾选重画预览 */
    private fun switchPaper(index: Int) {
        if (index == activePaper || index !in papers.indices) return
        saveSelection()
        activePaper = index
        renderChips()
        refreshPreview(keepSelection = false, restore = selections[index])
    }

    private fun saveSelection() {
        if (papers.isNotEmpty()) selections[activePaper] = qAdapter.selection()
    }

    /** 当前卷真正会入库的题：关掉「同时导入答案」时把答案（主观题连关键词一起）清空 */
    private fun questionsOf(draft: PaperDraft): List<Question> =
        if (importAnswers) draft.questions
        else draft.questions.map { it.copy(answer = "", keywords = "") }

    /**
     * 按「是否导入答案」重算当前卷的入卷题目，并同步预览列表与统计行。
     *
     * 关掉开关时只清答案字段，题目的其他字段一律不动——做完在成绩报告里补录答案后还能正常判分。
     *
     * @param restore 指定这卷的勾选（切卷时用）；null 表示按 [keepSelection] 走默认规则
     */
    private fun refreshPreview(keepSelection: Boolean, restore: Set<Int>? = null) {
        val draft = papers.getOrNull(activePaper)
        val questions = draft?.let { questionsOf(it) } ?: emptyList()
        val issues = fileIssues + (draft?.issues ?: emptyList())

        binding.tvStat.text = buildString {
            append("格式：$outcomeFormat")
            append("　总行数：$outcomeTotal")
            if (papers.size > 1) {
                append("　共 ${papers.size} 份 / ${papers.sumOf { questionsOf(it).size }} 题")
            }
            append("　本卷可导入：${questions.size}")
            if (draft != null && draft.duplicateSkipped > 0) {
                append("　重复跳过：${draft.duplicateSkipped}")
            }
            if (importAnswers) {
                val missing = questions.count { it.answer.isBlank() }
                if (missing > 0) append("　缺答案：$missing")
            } else {
                append("　").append(getString(R.string.import_answer_skipped, questions.size))
            }
            append("　警告：${issues.count { it.level == ImportIssue.Level.WARN }}")
            append("　错误：${issues.count { it.level == ImportIssue.Level.ERROR }}")
        }

        qAdapter.submit(questions, keepSelection, restore)

        issueAdapter.submit(issues)
        val hasIssue = issues.isNotEmpty()
        binding.tvIssuesTitle.visibility = if (hasIssue) View.VISIBLE else View.GONE
        binding.listIssues.visibility = if (hasIssue) View.VISIBLE else View.GONE
    }

    /**
     * 读文件 + 解析全在后台线程做。
     *
     * 题库包要解压几百张图、再把主数据逐份试解析，主线程做完必然卡白屏（甚至 ANR）；
     * 普通文件也可能几 MB。这里统一走 [parseAsync]，期间用遮罩挡住交互。
     */
    private fun loadFile(uri: Uri) {
        val name = displayName(uri)
        parseAsync("正在读取「$name」…") { readBytes(uri)?.let { name to it } }
    }

    private fun readBytes(uri: Uri): ByteArray? =
        contentResolver.openInputStream(uri)?.use { input ->
            val bos = ByteArrayOutputStream()
            input.copyTo(bos)
            bos.toByteArray()
        }

    /**
     * 后台读字节 → 探测格式 → 解析（拆卷 + 规范化）→ 刷新预览。
     *
     * 同一个 `parseJob` 统一管：新任务取消旧任务，避免用户连点选择时旧结果盖掉新预览。
     *
     * @param load 在 IO 线程执行，返回「文件名 + 字节」；返回 null 表示读取失败
     */
    private fun parseAsync(loadingText: String, load: () -> Pair<String, ByteArray>?) {
        parseJob?.cancel()
        showLoading(loadingText)
        parseJob = lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { load() }
                if (loaded == null) {
                    hideLoading()
                    toast("无法读取所选文件")
                    return@launch
                }
                val (name, data) = loaded
                val fmt = withContext(Dispatchers.Default) { Importer.detect(name, data) }
                binding.tvLoading.text = "正在解析「$name」…"
                val outcome = withContext(Dispatchers.IO) {
                    Importer.buildPapers(fmt, data, name, pkgDir)
                }
                fileName = name
                bytes = data
                format = fmt
                hideLoading()
                applyParsed(outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hideLoading()
                toast("解析失败：${e.message}")
            }
        }
    }

    private fun showLoading(text: String) {
        if (isFinishing || isDestroyed) return
        binding.tvLoading.text = text
        binding.loadingPanel.visibility = View.VISIBLE
        // 遮罩只盖内容区，底部主按钮露在外面：解析期间禁掉，避免把上一份的预览导进去
        binding.btnImport.isEnabled = false
    }

    private fun hideLoading() {
        if (isFinishing || isDestroyed) return
        binding.loadingPanel.visibility = View.GONE
        binding.btnImport.isEnabled = true
    }

    /**
     * 导入即建卷：每卷（勾到的题）按文件里的顺序整卷入卷，不随机抽题。
     *
     * 「一个文件 = 一份试卷」的老口径保留在单卷文件上——那边的名字仍以输入框为准；
     * 多卷时不在这里统一改名，每卷用它自己解析出来的卷名，避免三份卷子叫同一个名字。
     */
    private fun doImport() {
        if (papers.isEmpty()) {
            toast(getString(R.string.import_pick_none))
            return
        }
        saveSelection()

        val single = papers.size == 1
        val typedName = binding.inputName.text?.toString()?.trim().orEmpty()
        val durationMin = binding.inputDuration.text?.toString()?.trim()
            ?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val items = ArrayList<PaperImport>(papers.size)
        papers.forEachIndexed { i, paper ->
            val all = questionsOf(paper)
            val sel = if (single) null else selections[i]
            val picked = if (sel == null) all else all.filterIndexed { k, _ -> k in sel }
            // 一卷都没勾的卷子不建（拆卷拆出空卷也走这条路）
            if (picked.isEmpty()) return@forEachIndexed
            val name = if (single) {
                typedName.ifBlank { paper.name }
                    .ifBlank { fileName.substringBeforeLast('.').trim() }
                    .ifBlank { "我的试卷" }
            } else {
                paper.name
            }
            items.add(PaperImport(name, "导入自 $fileName", durationMin, picked))
        }

        if (items.isEmpty()) {
            toast(getString(R.string.import_pick_none))
            return
        }

        val conflicts = items.map { it.name }.filter { db.findPaperByName(it) != null }
        if (conflicts.isNotEmpty()) {
            FbDialog.confirm(
                this,
                overwriteMessage(conflicts),
                okText = "覆盖导入",
                onOk = { commitImport(items, papers.size - items.size) }
            )
        } else {
            commitImport(items, papers.size - items.size)
        }
    }

    private fun overwriteMessage(conflicts: List<String>): String =
        if (conflicts.size == 1) {
            "已存在同名试卷「${conflicts[0]}」，继续导入会覆盖原有的题目和作答记录。"
        } else {
            getString(
                R.string.import_overwrite_multi,
                conflicts.take(3).joinToString("、"),
                conflicts.size
            )
        }

    /**
     * 一次事务写完所有卷：中途失败不会留下「前几卷建好了」的残局。
     *
     * @param skipped 因为一题都没勾而没建卷的份数（多卷时提示一下，别让用户以为丢卷了）
     */
    private fun commitImport(items: List<PaperImport>, skipped: Int) {
        showLoading("正在写入试卷…")
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { db.importAsPapers(items, replace = true) }
                hideLoading()
                if (results.isEmpty()) {
                    toast("导入失败：没有可写入的题目")
                    return@launch
                }
                val total = results.sumOf { it.questionCount }
                val msg = if (results.size == 1) {
                    getString(R.string.import_done_paper, results[0].paperName, results[0].questionCount)
                } else {
                    getString(R.string.import_done_papers, results.size, total) +
                        if (skipped > 0) getString(R.string.import_done_skipped, skipped) else ""
                }
                toast(msg)
                setResult(Activity.RESULT_OK)
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hideLoading()
                toast("导入失败：${e.message}")
            }
        }
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

    /** 勾选变化回调：多卷时卷名胶囊上的题数要跟着变 */
    var onSelectionChanged: (() -> Unit)? = null

    /**
     * @param keepSelection 切换「是否导入答案」时保留用户已取消勾选的题，不要一路全选回去
     * @param restore 指定勾选（切卷时用）：这一卷上次勾了哪些就还勾哪些
     */
    fun submit(list: List<Question>, keepSelection: Boolean = false, restore: Set<Int>? = null) {
        val prev = when {
            restore != null -> restore.filter { it >= 0 && it < list.size }.toSet()
            keepSelection -> selected.filter { it >= 0 && it < list.size }.toSet()
            else -> null
        }
        items.clear(); items.addAll(list)
        selected.clear()
        if (prev != null) {
            selected.addAll(prev)
        } else {
            items.indices.forEach { selected.add(it) }
        }
        notifyDataSetChanged()
    }

    fun selectedItems(): List<Question> = items.filterIndexed { i, _ -> i in selected }

    /** 当前勾选的下标：切卷前存起来 */
    fun selection(): Set<Int> = HashSet(selected)

    fun toggleAll() {
        if (selected.size == items.size) selected.clear() else items.indices.forEach { selected.add(it) }
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
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
            onSelectionChanged?.invoke()
        }
        holder.binding.tvType.text = q.type.label
        holder.binding.tvStem.text = q.stem
        val opts = q.options.joinToString("\n") { "${it.key}. ${it.text}" }
        holder.binding.tvOptions.text = if (opts.isBlank()) "" else opts
        holder.binding.tvAnswer.text = if (q.answer.isBlank()) {
            "答案：" + holder.itemView.context.getString(com.lytree.qbank.R.string.import_answer_pending_tip)
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
        // rowNo = 0 是整卷级提示（文件级错误、卷面结构体检），没有具体行号可标
        holder.binding.tvText.text =
            if (it.rowNo > 0) "第 ${it.rowNo} 行 · ${it.message}" else it.message
        holder.binding.tvText.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                holder.itemView.context,
                if (it.level == ImportIssue.Level.ERROR) com.lytree.qbank.R.color.wrong
                else com.lytree.qbank.R.color.partial
            )
        )
    }
}
