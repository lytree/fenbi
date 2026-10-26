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
        binding.switchReplace.onCheckedChange = { replaceAll = it }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = previewAdapter
        binding.listIssues.layoutManager = LinearLayoutManager(this)
        binding.listIssues.adapter = issueAdapter
    }

    private fun pickFile() {
        val types = arrayOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "text/comma-separated-values",
            "text/plain",
            "application/json",
            "application/octet-stream"
        )
        try {
            picker.launch(types)
        } catch (_: Exception) {
            picker.launch(arrayOf("*/*"))
        }
    }

    private fun loadSample() {
        val samples = arrayOf(
            "词库示例.csv" to "CSV 词表（成语 + 实词，带分类列）",
            "词库示例.docx" to "Word 文档（表格词表，含一条没填分类）"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("载入示例词表")
            .setItems(samples.map { it.second }.toTypedArray()) { _, which ->
                loadSampleFile(samples[which].first)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun loadSampleFile(name: String) {
        try {
            val bytes = assets.open("samples/$name").use { it.readBytes() }
            fileName = name
            applyParsed(WordImporter.build(this, WordFormat.detect(name, bytes), bytes))
        } catch (e: Exception) {
            toast("示例词表读取失败：${e.message}")
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
                toast("不支持老版 .doc，请在 Word 里另存为 .docx 再导入")
                return
            }
            val format = WordFormat.detect(fileName, bytes)
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

        binding.tvFile.text = fileName
        binding.tvStat.text = getString(
            R.string.word_stat_fmt,
            result.format, result.total, result.words.size, result.idiomCount, result.contentCount
        )

        if (result.guessed > 0) {
            binding.tvGuessed.visibility = View.VISIBLE
            binding.tvGuessed.text = getString(R.string.word_guessed_hint, result.guessed)
        } else {
            binding.tvGuessed.visibility = View.GONE
        }

        previewAdapter.submit(result.words)
        issueAdapter.submit(result.issues)

        val hasIssues = result.issues.isNotEmpty()
        binding.tvIssuesTitle.visibility = if (hasIssues) View.VISIBLE else View.GONE
        binding.listIssues.visibility = if (hasIssues) View.VISIBLE else View.GONE

        if (result.words.isEmpty()) toast("没有解析出词条，看看下面的解析提示")
    }

    private fun doImport() {
        val selected = previewAdapter.selectedItems()
        if (selected.isEmpty()) {
            toast("请至少勾选一条词条")
            return
        }
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

/** 词条预览列表（可勾选） */
class WordPreviewAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<WordPreviewAdapter.VH>() {

    private val items = ArrayList<Word>()
    private val selected = HashSet<Int>()

    fun submit(list: List<Word>) {
        items.clear(); items.addAll(list)
        selected.clear()
        items.indices.forEach { selected.add(it) }
        notifyDataSetChanged()
    }

    fun selectedItems(): List<Word> = items.filterIndexed { i, _ -> i in selected }

    fun toggleAll() {
        if (selected.size == items.size) selected.clear() else items.indices.forEach { selected.add(it) }
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
        b.cb.setOnCheckedChangeListener(null)
        b.cb.isChecked = position in selected
        b.cb.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(position) else selected.remove(position)
        }
        b.tvWord.text = w.text
        b.tvMeta.text = w.metaLine()
        b.tvMeaning.text = w.meaning
        b.tvMeaning.visibility = if (w.meaning.isBlank()) View.GONE else View.VISIBLE
    }
}
