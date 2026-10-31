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
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.R
import com.lytree.qbank.data.db.PaperImport
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.db.SetImport
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
 * 结构是**三层**的，与试卷列表一一对应：
 * 1. 一次导入可以选**多个文件**，每个文件 = 列表上的一个**标题**（导入组）；
 * 2. 一个文件里可以是**一份卷，也可以是几份**（一个工作表 = 一份试卷，「试卷」列可在表内再分，
 *    规则见 [com.lytree.qbank.data.importer.PaperSplitter]），拆出多卷时顶部多一排卷名胶囊；
 * 3. 每份卷里才是题目，逐题可勾选。
 *
 * 预览始终只显示「当前文件 · 当前卷」的题，勾选按（文件, 卷）各存一份，
 * 来回切不会互相覆盖；最后一次性把勾到题的组全部建出来。
 *
 * 同时也是全 App 唯一的导入入口：导入得到的直接是试卷（只有「标题 → 试卷」两层，没有单独的题库层），
 * 词表不进这一页，由「导入词表」卡片分发到 [com.lytree.qbank.ui.word.WordImportActivity]。
 */
class ImportActivity : AppCompatActivity() {

    /**
     * 一次导入里的一个文件 = 列表上的一个标题。
     *
     * [title] 可变：用户可以在页面上改这一组的名字，默认取文件名。
     */
    private data class FileBatch(
        var title: String,
        val source: String,
        val format: String,
        val totalRows: Int,
        val papers: List<PaperDraft>,
        val issues: List<ImportIssue>
    ) {
        val questionCount: Int get() = papers.sumOf { it.questions.size }
    }

    private lateinit var binding: ActivityImportBinding
    private lateinit var db: QBankDb

    private val qAdapter = PreviewAdapter()
    private val issueAdapter = IssueAdapter()

    private var fileName: String = ""

    /** 试卷包（zip）的解压根目录：图片落到应用私有目录，清缓存也不影响已有题目 */
    private lateinit var pkgDir: File

    /** 本次导入拆出来的各文件（每个文件一个标题） */
    private var batches: List<FileBatch> = emptyList()

    /** 当前预览的是第几个文件 / 文件里的第几卷（单文件单卷时恒为 0） */
    private var activeBatch = 0
    private var activePaper = 0

    /** 勾选按「文件#卷」分开存，来回切不丢 */
    private val selections = HashMap<String, Set<Int>>()

    /** 卷名 / 文件名胶囊（与当前层一一对应）：勾选变了只改文案，不重建视图 */
    private val chips = ArrayList<TextView>()
    private val batchChips = ArrayList<TextView>()

    /** 是否把答案一起导入（关掉 = 只导题目，做完在成绩报告里补录） */
    private var importAnswers = true

    /** 链接下载任务：弹窗关掉时一起取消，避免下载完还闯进预览页 */
    private var downloadJob: Job? = null

    /** 解析任务：换文件 / 退出页面时取消，避免旧结果覆盖新预览 */
    private var parseJob: Job? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) loadFiles(uris)
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

        // 改标题时文件胶囊跟着变：一眼能看出改的是哪一组
        binding.inputName.doAfterTextChanged { editable ->
            val b = batches.getOrNull(activeBatch) ?: return@doAfterTextChanged
            val text = editable?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@doAfterTextChanged
            b.title = text
            batchChips.getOrNull(activeBatch)?.text =
                getString(R.string.import_file_chip, text, b.papers.size)
        }

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

    /** 支持从外部（文件管理器 / 分享）直接打开试卷文件 */
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
        loadFiles(listOf(uri))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** 多选：一次挑几个文件，就建几个标题 */
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
        val fmt = Importer.detect(result.fileName, result.bytes)
        fileName = ensureExtension(result.fileName, fmt)
        parseAsync("正在解析「$fileName」…") { listOf(fileName to result.bytes) }
    }

    /** 链接常以 download?id=1 结尾、拿不到扩展名，按内容探测结果补一个，标题才好取 */
    private fun ensureExtension(name: String, format: ImportFormat): String {
        if (ImportFormat.of(name) != null) return name
        val base = name.substringBeforeLast('.', name).ifBlank { "导入文件" }
        return "$base.${format.ext}"
    }

    /** 「载入示例」：内置样例，普通单表 / 带图材料 / zip 试卷包 */
    private fun loadSample() {
        val samples = arrayOf(
            "行测基础示例.xlsx" to "行测基础示例（11 题 · 覆盖全部选择题型）",
            "多套卷示例.xlsx" to "多套卷示例（2 个工作表 · 一次导入 2 份试卷）",
            "资料分析示例.xlsx" to "资料分析示例（含图表材料）",
            "资料分析试卷包.zip" to "资料分析试卷包（Excel + 图片 · 图片随包）"
        )
        FbDialog.list(
            context = this,
            title = "载入示例",
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
        parseAsync("正在解析「$name」…") { listOf(name to data) }
    }

    /** 解析完成：按文件成组，组内按卷排好，默认预览第一组的第一卷 */
    private fun applyParsed(list: List<FileBatch>) {
        batches = list
        activeBatch = 0
        activePaper = 0
        selections.clear()
        chips.clear()
        batchChips.clear()

        // 同一个文件导过就沿用上次的标题：默认标题只是文件名，用户改过的名字不该被冲掉，
        // 也不要因为重导一次就多出一个同名分组（标题一致时接下来会走「覆盖导入」确认）
        val existing = db.listPaperSets(withPapers = false)
        batches.forEach { b ->
            val source = "导入自 ${b.source}"
            existing.firstOrNull { it.source == source }?.let { b.title = it.title }
        }

        val questionTotal = batches.sumOf { it.questionCount }
        if (questionTotal == 0) {
            toast("没有解析出任何题目，请检查文件格式")
        }

        binding.pickPanel.visibility = View.GONE
        binding.resultPanel.visibility = View.VISIBLE
        binding.btnImport.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        binding.bottomDivider.visibility = View.VISIBLE

        val paperCount = batches.sumOf { it.papers.size }
        binding.tvFile.text = when {
            batches.size > 1 -> getString(R.string.import_file_count, batches.size)
            batches.size == 1 -> batches.first().source
            else -> fileName
        }

        // 标题默认取文件名，改过就尊重用户输入；多文件时输入框跟着当前文件走
        binding.inputName.setText(batches.getOrNull(activeBatch)?.title.orEmpty())
        binding.tvTitleHint.visibility = if (batches.size > 1) View.VISIBLE else View.GONE

        binding.btnAll.visibility = if (questionTotal == 0) View.GONE else View.VISIBLE
        binding.btnImport.isEnabled = questionTotal > 0
        binding.btnImport.text = when {
            questionTotal == 0 -> getString(R.string.import_confirm_paper)
            batches.size > 1 -> getString(R.string.import_confirm_sets, batches.size, paperCount)
            paperCount > 1 -> getString(R.string.import_confirm_papers, paperCount)
            else -> getString(R.string.import_confirm_paper)
        }

        renderBatchChips()
        renderChips()
        refreshPreview(keepSelection = false)
    }

    /** 文件胶囊：一次选了多个文件才出现，点哪个就预览哪个文件拆出来的卷 */
    private fun renderBatchChips() {
        batchChips.clear()
        binding.fileChipRow.removeAllViews()
        if (batches.size <= 1) {
            binding.fileChipPanel.visibility = View.GONE
            return
        }
        binding.fileChipPanel.visibility = View.VISIBLE
        val margin = (6 * resources.displayMetrics.density).toInt()
        batches.forEachIndexed { i, b ->
            val chip = chipView()
            val on = i == activeBatch
            chip.text = getString(R.string.import_file_chip, b.title, b.papers.size)
            styleChip(chip, on, dim = !on && b.questionCount == 0)
            chip.setOnClickListener { switchBatch(i) }
            binding.fileChipRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = margin }
            )
            batchChips.add(chip)
        }
    }

    /** 卷名胶囊：选中态换实心背景，题数跟着勾选实时变 */
    private fun renderChips() {
        chips.clear()
        binding.chipRow.removeAllViews()
        val papers = batches.getOrNull(activeBatch)?.papers.orEmpty()
        if (papers.size <= 1) {
            binding.multiPanel.visibility = View.GONE
            return
        }
        binding.multiPanel.visibility = View.VISIBLE
        binding.tvMultiTitle.text =
            getString(R.string.import_multi_title, papers.size, papers.sumOf { it.questions.size })
        val margin = (6 * resources.displayMetrics.density).toInt()
        papers.forEachIndexed { i, paper ->
            val chip = chipView()
            val sel = selections[key(activeBatch, i)]
            val count = if (sel == null) questionsOf(paper).size else sel.size
            val on = i == activePaper
            chip.text = getString(R.string.import_paper_chip, paper.name, count)
            styleChip(chip, on, dim = !on && count == 0)
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

    private fun chipView(): TextView =
        LayoutInflater.from(this)
            .inflate(R.layout.item_import_paper_chip, binding.chipRow, false) as TextView

    private fun styleChip(chip: TextView, on: Boolean, dim: Boolean) {
        chip.setBackgroundResource(if (on) R.drawable.bg_fb_chip_selected else R.drawable.bg_fb_chip)
        chip.setTextColor(
            ContextCompat.getColor(
                this,
                if (on) R.color.fb_text_on_primary else R.color.fb_text_secondary
            )
        )
        // 整卷都没勾的卷子淡一点：导入时不会建卷，提前透个底
        chip.alpha = if (dim) 0.55f else 1f
    }

    /** 只改胶囊上的题数（逐题点勾选时重建视图会跳滚动位置） */
    private fun updateChipLabels() {
        val papers = batches.getOrNull(activeBatch)?.papers.orEmpty()
        if (chips.size == papers.size) {
            papers.forEachIndexed { i, paper ->
                val sel = if (i == activePaper) qAdapter.selection() else selections[key(activeBatch, i)]
                val count = if (sel == null) questionsOf(paper).size else sel.size
                chips[i].text = getString(R.string.import_paper_chip, paper.name, count)
                chips[i].alpha = if (i == activePaper || count > 0) 1f else 0.55f
            }
        }
        batches.forEachIndexed { i, b ->
            batchChips.getOrNull(i)?.text =
                getString(R.string.import_file_chip, b.title, b.papers.size)
        }
    }

    /** 切文件：先存下这组的标题与勾选，再按新组重画 */
    private fun switchBatch(index: Int) {
        if (index == activeBatch || index !in batches.indices) return
        saveTitle()
        saveSelection()
        activeBatch = index
        activePaper = 0
        binding.inputName.setText(batches[index].title)
        renderBatchChips()
        renderChips()
        refreshPreview(keepSelection = false, restore = selections[key(index, 0)])
    }

    /** 切卷：先把当前卷的勾选存下来，再按目标卷的勾选重画预览 */
    private fun switchPaper(index: Int) {
        val papers = batches.getOrNull(activeBatch)?.papers.orEmpty()
        if (index == activePaper || index !in papers.indices) return
        saveSelection()
        activePaper = index
        renderChips()
        refreshPreview(keepSelection = false, restore = selections[key(activeBatch, index)])
    }

    private fun key(batch: Int, paper: Int) = "$batch#$paper"

    private fun saveSelection() {
        if (batches.isEmpty()) return
        selections[key(activeBatch, activePaper)] = qAdapter.selection()
    }

    /** 把输入框里的标题写回当前文件（空着就保留默认的文件名） */
    private fun saveTitle() {
        val b = batches.getOrNull(activeBatch) ?: return
        val typed = binding.inputName.text?.toString()?.trim().orEmpty()
        b.title = typed.ifBlank { defaultTitle(b.source) }
    }

    private fun defaultTitle(source: String): String =
        source.substringBeforeLast('.').trim().ifBlank { "我的试卷" }

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
        val batch = batches.getOrNull(activeBatch)
        val draft = batch?.papers?.getOrNull(activePaper)
        val questions = draft?.let { questionsOf(it) } ?: emptyList()
        val issues = (batch?.issues ?: emptyList()) + (draft?.issues ?: emptyList())

        binding.tvStat.text = buildString {
            append("格式：").append(batch?.format ?: "-")
            append("　总行数：").append(batch?.totalRows ?: 0)
            if (batches.size > 1) {
                append("　共 ").append(batches.size).append(" 个文件 / ")
                append(batches.sumOf { it.papers.size }).append(" 份试卷")
            } else if ((batch?.papers?.size ?: 0) > 1) {
                append("　本文件 ").append(batch?.papers?.size).append(" 份试卷")
            }
            append("　本卷可导入：").append(questions.size)
            if (draft != null && draft.duplicateSkipped > 0) {
                append("　重复跳过：").append(draft.duplicateSkipped)
            }
            if (importAnswers) {
                val missing = questions.count { it.answer.isBlank() }
                if (missing > 0) append("　缺答案：").append(missing)
            } else {
                append("　").append(getString(R.string.import_answer_skipped, questions.size))
            }
            append("　警告：").append(issues.count { it.level == ImportIssue.Level.WARN })
            append("　错误：").append(issues.count { it.level == ImportIssue.Level.ERROR })
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
     * 试卷包要解压几百张图、再把主数据逐份试解析，主线程做完必然卡白屏（甚至 ANR）；
     * 普通文件也可能几 MB。这里统一走 [parseAsync]，期间用遮罩挡住交互。
     */
    private fun loadFiles(uris: List<Uri>) {
        val names = uris.map { displayName(it) }
        parseAsync(if (names.size == 1) "正在读取「${names[0]}」…" else "正在读取 ${names.size} 个文件…") {
            uris.mapIndexed { i, uri -> names[i] to (readBytes(uri) ?: ByteArray(0)) }
                .filter { it.second.isNotEmpty() }
        }
    }

    private fun readBytes(uri: Uri): ByteArray? =
        contentResolver.openInputStream(uri)?.use { input ->
            val bos = ByteArrayOutputStream()
            input.copyTo(bos)
            bos.toByteArray()
        }

    /**
     * 后台读字节 → 探测格式 → 解析（拆卷 + 规范化）→ 组装成「每个文件一组」→ 刷新预览。
     *
     * 同一个 `parseJob` 统一管：新任务取消旧任务，避免用户连点选择时旧结果盖掉新预览。
     *
     * @param load 在 IO 线程执行，返回「文件名 + 字节」列表；为空表示读取失败
     */
    private fun parseAsync(loadingText: String, load: () -> List<Pair<String, ByteArray>>) {
        parseJob?.cancel()
        showLoading(loadingText)
        parseJob = lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { load() }
                if (loaded.isEmpty()) {
                    hideLoading()
                    toast("无法读取所选文件")
                    return@launch
                }
                val built = withContext(Dispatchers.IO) {
                    loaded.mapIndexed { i, (name, data) ->
                        if (loaded.size > 1) {
                            runOnUiThread { binding.tvLoading.text = "正在解析 ${i + 1}/${loaded.size}：$name" }
                        }
                        val fmt = Importer.detect(name, data)
                        val outcome: MultiPaperOutcome = Importer.buildPapers(fmt, data, name, pkgDir)
                        FileBatch(
                            title = defaultTitle(name),
                            source = name,
                            format = outcome.format,
                            totalRows = outcome.totalRows,
                            papers = outcome.papers,
                            issues = outcome.issues
                        )
                    }
                }
                val first = loaded.first()
                fileName = if (loaded.size == 1) first.first else ""
                hideLoading()
                applyParsed(built)
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
     * 导入即建组：每个文件一个标题，标题下的卷按文件里的顺序整卷入卷，不随机抽题。
     *
     * 单卷文件里，卷名就跟标题走（用户改的那个名字就是他在列表上看到的名字）；
     * 多卷时每卷用它自己解析出来的卷名，避免三份卷子叫同一个名字。
     */
    private fun doImport() {
        if (batches.isEmpty()) {
            toast(getString(R.string.import_pick_none))
            return
        }
        saveTitle()
        saveSelection()

        val durationMin = binding.inputDuration.text?.toString()?.trim()
            ?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val sets = ArrayList<SetImport>(batches.size)
        var skipped = 0
        batches.forEachIndexed { bi, batch ->
            val title = batch.title.trim().ifBlank { defaultTitle(batch.source) }
            val single = batch.papers.size == 1
            val items = ArrayList<PaperImport>(batch.papers.size)
            batch.papers.forEachIndexed { pi, paper ->
                val all = questionsOf(paper)
                val sel = if (single) null else selections[key(bi, pi)]
                val picked = if (sel == null) all else all.filterIndexed { k, _ -> k in sel }
                // 一卷都没勾的卷子不建（拆卷拆出空卷也走这条路）
                if (picked.isEmpty()) return@forEachIndexed
                val name = if (single) title else paper.name.ifBlank { title }
                items.add(PaperImport(name, "导入自 ${batch.source}", durationMin, picked))
            }
            if (items.isEmpty()) skipped++ else {
                sets.add(SetImport(title, "导入自 ${batch.source}", items))
            }
        }

        if (sets.isEmpty()) {
            toast(getString(R.string.import_pick_none))
            return
        }

        val conflicts = sets.map { it.title }.filter { db.findPaperSetByTitle(it) != null }
        if (conflicts.isNotEmpty()) {
            FbDialog.confirm(
                this,
                overwriteMessage(conflicts),
                okText = "覆盖导入",
                onOk = { commitImport(sets, skipped) }
            )
        } else {
            commitImport(sets, skipped)
        }
    }

    private fun overwriteMessage(conflicts: List<String>): String = if (conflicts.size == 1) {
        "列表里已经有同名标题「${conflicts[0]}」，继续导入会覆盖这一组原有的试卷和作答记录。"
    } else {
        "列表里已经有 ${conflicts.size} 个同名标题（${conflicts.take(3).joinToString("、")}），" +
            "继续导入会覆盖这些组原有的试卷和作答记录。"
    }

    /**
     * 一次事务写完所有组：中途失败不会留下「前几组建好了」的残局。
     *
     * @param skipped 因为一题都没勾而没建组的文件数（多文件时提示一下，别让用户以为丢文件了）
     */
    private fun commitImport(sets: List<SetImport>, skipped: Int) {
        showLoading("正在写入试卷…")
        lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { db.importSets(sets, replace = true) }
                hideLoading()
                if (results.isEmpty()) {
                    toast("导入失败：没有可写入的题目")
                    return@launch
                }
                val paperCount = results.sumOf { it.papers.size }
                val questionCount = results.sumOf { it.questionCount }
                val msg = when {
                    results.size == 1 -> getString(
                        R.string.import_done_set,
                        results[0].title, paperCount, questionCount
                    )
                    else -> getString(
                        R.string.import_done_sets,
                        results.size, paperCount, questionCount
                    )
                } + if (skipped > 0) getString(R.string.import_done_skipped, skipped) else ""
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
