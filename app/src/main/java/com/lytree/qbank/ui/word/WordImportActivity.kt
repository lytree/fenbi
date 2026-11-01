package com.lytree.qbank.ui.word

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.R
import com.lytree.qbank.data.BuiltinWords
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.importer.WordFormat
import com.lytree.qbank.data.importer.WordImporter
import com.lytree.qbank.data.importer.WordOutcome
import com.lytree.qbank.data.model.Word
import com.lytree.qbank.databinding.ActivityWordImportBinding
import com.lytree.qbank.databinding.ItemWordImportBinding
import com.lytree.qbank.ui.IssueAdapter
import com.lytree.qbank.ui.applyNavBarBottomPadding
import java.io.ByteArrayOutputStream

/**
 * 词库导入：选文档 → 解析预览 → 勾选 → 入库。
 *
 * 和试卷导入的差别：文档里的词条**全部收**（成语 + 实词），
 * 分类能读就读「分类」列，读不到按字数推测并在页面上标出来，不偷偷丢词。
 */
class WordImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWordImportBinding
    private lateinit var db: QBankDb

    private val previewAdapter = WordPreviewAdapter()
    private val issueAdapter = IssueAdapter()

    private var fileName: String = ""
    private var outcome: WordOutcome? = null

    /** 打开后先清空词库再导入 */
    private var replaceAll = false

    /** 本次解析出来的词条里，有多少是库里已经有的 */
    private var duplicateCount = 0

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWordImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = QBankDb.get(this)

        binding.titleBar.setTitle(getString(R.string.title_word_import))
        binding.titleBar.showBack(true)
        binding.bottomBar.applyNavBarBottomPadding()

        binding.btnPick.setOnClickListener { pickFile() }
        binding.btnSample.setOnClickListener { loadSample() }
        binding.btnAll.setOnClickListener { previewAdapter.toggleAll() }
        binding.btnImport.setOnClickListener { doImport() }

        binding.switchReplace.setChecked(false)
        binding.switchReplace.onCheckedChange = { checked ->
            replaceAll = checked
            // 覆盖模式会先清空词库：只勾一部分导入 = 其余全被删掉，所以开覆盖就全选
            if (checked) previewAdapter.selectAll()
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = previewAdapter
        binding.listIssues.layoutManager = LinearLayoutManager(this)
        binding.listIssues.adapter = issueAdapter
    }

    private fun pickFile() {
        // 词表导入统一走 Excel：只挑 .xlsx，别的格式进来会明确提示
        val types = arrayOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/octet-stream"
        )
        try {
            picker.launch(types)
        } catch (_: Exception) {
            picker.launch(arrayOf("*/*"))
        }
    }

    private fun loadSample() {
        // 内置词库是 App 自带的数据；示例是一份 .xlsx 模板，照它的表头填就能导
        val samples = listOf(
            getString(R.string.word_sample_builtin) to BuiltinWords.ASSET,
            getString(R.string.word_sample_xlsx) to "samples/词库示例.xlsx"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.word_sample_title)
            .setItems(samples.map { it.first }.toTypedArray()) { _, which ->
                loadSampleFile(samples[which].second)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun loadSampleFile(assetPath: String) {
        try {
            val bytes = assets.open(assetPath).use { it.readBytes() }
            fileName = assetPath.substringAfterLast('/')
            applyParsed(WordImporter.build(this, WordFormat.detect(fileName, bytes), bytes))
        } catch (e: Exception) {
            toast("词表读取失败：${e.message}")
        }
    }

    private fun loadFile(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val bos = ByteArrayOutputStream()
                input.copyTo(bos)
                bos.toByteArray()
            } ?: return toast("无法读取所选文件")

            fileName = displayName(uri)
            if (WordFormat.isLegacyDoc(fileName)) {
                toast(getString(R.string.word_import_excel_only))
                return
            }
            val format = WordFormat.detect(fileName, bytes)
            // 统一 Excel：docx / csv / txt / json 一律挡在门外，别让用户白等解析
            if (format != WordFormat.XLSX) {
                toast(getString(R.string.word_import_excel_only))
                return
            }
            applyParsed(WordImporter.build(this, format, bytes))
        } catch (e: Exception) {
            toast("解析失败：${e.message}")
        }
    }

    private fun applyParsed(result: WordOutcome) {
        outcome = result

        binding.pickPanel.visibility = View.GONE
        binding.resultPanel.visibility = View.VISIBLE
        binding.bottomDivider.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE

        // 和库里已有的词条对一遍：重复的不再默认勾选，避免「导入两遍变成两批」
        val existing = db.existingWordTexts(result.words.map { it.text })
        val dupCount = result.words.count { it.text in existing }
        val freshCount = result.words.size - dupCount
        duplicateCount = dupCount

        binding.tvFile.text = fileName
        val stat = getString(
            R.string.word_stat_fmt,
            result.format, result.total, result.words.size, result.idiomCount, result.contentCount
        )
        binding.tvStat.text = if (dupCount == 0) stat else buildString {
            append(stat)
            append('\n')
            append(getString(R.string.word_import_dup_fmt, dupCount, freshCount))
            append('\n')
            append(getString(R.string.word_import_dup_hint))
        }

        if (result.guessed > 0) {
            binding.tvGuessed.visibility = View.VISIBLE
            binding.tvGuessed.text = getString(R.string.word_guessed_hint, result.guessed)
        } else {
            binding.tvGuessed.visibility = View.GONE
        }

        previewAdapter.submit(result.words, existing)
        issueAdapter.submit(result.issues)
        fitPreviewList(result.words.size)

        val hasIssues = result.issues.isNotEmpty()
        binding.tvIssuesTitle.visibility = if (hasIssues) View.VISIBLE else View.GONE
        binding.listIssues.visibility = if (hasIssues) View.VISIBLE else View.GONE

        if (result.words.isEmpty()) toast("没有解析出词条，看看下面的解析提示")
    }

    /**
     * 预览列表在 NestedScrollView 里用 wrap_content 时会把**所有**行一次性测量，
     * 两三千条的词表直接 OOM（实测 2445 条必挂）。行数多就改成定高 + 自己滚动，
     * 少则保持自适应高度，短词表看起来也不会空一大块。
     */
    private fun fitPreviewList(count: Int) {
        val lp = binding.list.layoutParams
        val tall = count > 60
        binding.list.isNestedScrollingEnabled = tall
        lp.height = if (tall) (360 * resources.displayMetrics.density).toInt()
        else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        binding.list.layoutParams = lp
    }

    private fun doImport() {
        val selected = previewAdapter.selectedItems()
        if (selected.isEmpty()) {
            toast(
                if (duplicateCount > 0 && duplicateCount == outcome?.words?.size)
                    getString(R.string.word_import_all_dup)
                else
                    getString(R.string.word_import_pick_none)
            )
            return
        }
        if (replaceAll && outcome != null && selected.size < (outcome?.words?.size ?: 0)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.word_import_replace_confirm_title)
                .setMessage(getString(R.string.word_import_replace_confirm_msg, selected.size))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm) { _, _ -> commitImport(selected) }
                .show()
            return
        }
        commitImport(selected)
    }

    private fun commitImport(selected: List<Word>) {
        val count = db.upsertWords(selected, replaceAll)
        val idiom = selected.count { it.categoryEnum == com.lytree.qbank.data.model.WordCategory.IDIOM }
        val content = selected.count { it.categoryEnum == com.lytree.qbank.data.model.WordCategory.WORD }
        toast(getString(R.string.word_import_done, count, idiom, content))
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment ?: "词表"
        } ?: (uri.lastPathSegment ?: "词表")
    } catch (_: Exception) {
        uri.lastPathSegment ?: "词表"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

/** 词条预览列表（可勾选；库内已有的词条标出来，默认不勾） */
class WordPreviewAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<WordPreviewAdapter.VH>() {

    private val items = ArrayList<Word>()
    private val selected = HashSet<Int>()

    /** 库里已经存在的词条文本，用来在行上标「已在词库」 */
    private val duplicates = HashSet<String>()

    fun submit(list: List<Word>, existing: Set<String> = emptySet()) {
        items.clear(); items.addAll(list)
        duplicates.clear(); duplicates.addAll(existing)
        selected.clear()
        // 默认只勾「库里没有的」；重复项勾上就是覆盖更新，交给用户自己决定
        items.forEachIndexed { i, w -> if (w.text !in duplicates) selected.add(i) }
        notifyDataSetChanged()
    }

    fun selectedItems(): List<Word> = items.filterIndexed { i, _ -> i in selected }

    fun toggleAll() {
        if (selected.size == items.size) selected.clear() else items.indices.forEach { selected.add(it) }
        notifyDataSetChanged()
    }

    /** 覆盖模式下强制全选：漏勾一条就少一条，等于顺手删词 */
    fun selectAll() {
        selected.clear()
        items.indices.forEach { selected.add(it) }
        notifyDataSetChanged()
    }

    class VH(val binding: ItemWordImportBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemWordImportBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val w = items[position]
        val b = holder.binding
        val dup = w.text in duplicates
        val ctx = b.root.context

        b.cb.setOnCheckedChangeListener(null)
        b.cb.isChecked = position in selected
        b.cb.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(position) else selected.remove(position)
        }
        b.tvWord.text = w.text
        b.tvMeta.text = if (dup) {
            ctx.getString(R.string.word_import_dup_badge) + " · " + w.metaLine()
        } else {
            w.metaLine()
        }
        b.tvWord.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                ctx,
                if (dup) R.color.fb_text_meta else R.color.fb_text_title
            )
        )
        b.tvMeaning.text = w.meaning
        b.tvMeaning.visibility = if (w.meaning.isBlank()) View.GONE else View.VISIBLE
    }
}
