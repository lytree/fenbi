package com.lytree.qbank.ui.paper

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lytree.qbank.R
import com.lytree.qbank.data.db.PaperSet
import com.lytree.qbank.data.db.QBankDb
import com.lytree.qbank.data.model.Paper
import com.lytree.qbank.data.model.PaperRef
import com.lytree.qbank.data.model.PaperStructure
import com.lytree.qbank.databinding.FragmentListBinding
import com.lytree.qbank.ui.AnswerEntryActivity
import com.lytree.qbank.ui.AnswerImportUi
import com.lytree.qbank.ui.ExamActivity
import com.lytree.qbank.ui.FbDialog
import com.lytree.qbank.ui.ImportActivity
import com.lytree.qbank.ui.MainActivity
import com.lytree.qbank.ui.Refreshable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页试卷树：**导入组 → 试卷 → 大类 → 材料**四级，**每层都能直接当一张卷来答**。
 *
 * 一次导入就是一个组，组下面是那次导入拆出来的各份卷，卷里再按大类 / 材料往下摊。
 * 组标题上的「整组作答」把整组合成一张卷、直接按试卷形式答题；行尾箭头只负责展开层级
 * （懒加载，展开过的卷才读题）。组标题可折叠、可重命名、可整组删除；
 * 组内卷子右键（长按）仍是查看 / 补答案 / 删除。
 *
 * 这是 App 的主页，也是唯一的导入入口——原本挂「题库」页的导入按钮随题库层一起并到这里。
 */
class PaperListFragment : Fragment(), Refreshable {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val adapter = PaperTreeAdapter()
    /** 列表刷新任务：连续刷新时取消上一次，避免旧结果后到覆盖新结果 */
    private var refreshJob: Job? = null
    private lateinit var db: QBankDb
    private var pendingPaper: Paper? = null

    /** 收起的组 id（存本地，切 Tab / 重进 App 都保持） */
    private val collapsed = HashSet<Long>()

    /** 展开了层级的试卷 id（存本地）：层级是懒加载的，只有展开过的卷才会去读题 */
    private val expandedPapers = HashSet<Long>()

    /** 展开了材料的大类（键见 [PaperTreeAdapter.sectionKey]） */
    private val expandedSections = HashSet<String>()

    /** 已加载的试卷结构：展开过一次就留着，反复折叠不再读库 */
    private val structures = HashMap<Long, PaperStructure>()

    /** 最近一次读到的树数据：展开 / 折叠时只重排节点，不再查库 */
    private var sets: List<PaperSet> = emptyList()

    /** 试卷列表直接补答案：不必进答题页，也不用重做 */
    private val answerPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val paper = pendingPaper
        pendingPaper = null
        val act = activity as? AppCompatActivity ?: return@registerForActivityResult
        if (uri == null || paper == null) return@registerForActivityResult
        // 视图已销毁（例如宿主重建）时 lifecycleScope 不可用，直接放弃这次结果
        if (view == null) return@registerForActivityResult
        // 读卷放后台；本页视图销毁后结果直接丢弃
        viewLifecycleOwner.lifecycleScope.launch {
            val questions = withContext(Dispatchers.IO) { db.getPaper(paper.id)?.questions } ?: emptyList()
            AnswerImportUi.handle(act, db, questions, uri) { refresh() }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = QBankDb.get(requireContext())
        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle(getString(R.string.page_papers))
        binding.titleBar.addTextAction(getString(R.string.action_import)) { openImport() }

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        // 整张卡 = 整卷作答；行尾箭头才是展开层级
        adapter.onPaperClick = { paper -> startExam(paper.id, paper.name) }
        adapter.onPaperLongClick = { paper -> showPaperActions(paper) }
        adapter.onTogglePaper = { paper, expand -> togglePaper(paper, expand) }
        adapter.onToggleSection = { key -> toggleSection(key) }
        adapter.onAnswerScope = { paperId, scopeKey -> answerScope(paperId, scopeKey) }
        adapter.onGroupAnswer = { set -> answerGroup(set) }
        adapter.onToggleSet = { set, fold -> toggle(set, fold) }
        adapter.onSetLongClick = { set -> showSetActions(set) }
        binding.emptyAction.setText(R.string.action_import)
        binding.emptyAction.setOnClickListener { openImport() }
    }

    private fun openImport() {
        (activity as? MainActivity)?.openImport()
            ?: startActivity(Intent(requireContext(), ImportActivity::class.java))
    }

    // ---------------------------------------------------------------- 开练

    /** 开一场答题：整卷 / 整组都走这里，范围（大类 / 材料）按需带过去 */
    private fun startExam(paperId: Long, name: String, scopeKey: String = "") {
        startActivity(Intent(requireContext(), ExamActivity::class.java).apply {
            putExtra(ExamActivity.EXTRA_PAPER_ID, paperId)
            // 卷名不带范围，范围单独带：答题页自己拼「卷名 · 资料分析」
            putExtra(ExamActivity.EXTRA_PAPER_NAME, name)
            putExtra(ExamActivity.EXTRA_SCOPE_KEY, scopeKey)
        })
    }

    /**
     * 整组作答：组里各份卷合成一张卷（虚拟卷 id = -组 id，见 [PaperRef]）。
     *
     * 组里只剩一份卷时直接开那份卷：合成出来的卷跟它一模一样，再开一个虚拟卷只会多出一条
     * 换着名头的记录（「组名」和「组名」两套进度，看着像重复）。
     */
    private fun answerGroup(set: PaperSet) {
        val only = set.papers.singleOrNull()
        if (only != null) {
            startExam(only.id, only.name)
            return
        }
        if (set.papers.isEmpty()) {
            FbDialog.alert(requireContext(), "这一组还没有试卷。")
            return
        }
        startExam(PaperRef.groupPaperId(set.id), set.title)
    }

    /** 大类 / 材料：答题页按范围切题，记录与进度也按范围分桶 */
    private fun answerScope(paperId: Long, scopeKey: String) {
        val paper = sets.flatMap { it.papers }.firstOrNull { it.id == paperId } ?: return
        startExam(paperId, paper.name, scopeKey)
    }

    // ---------------------------------------------------------------- 层级展开

    /**
     * 展开 / 收起一份卷的大类。
     *
     * 结构是**懒加载**的：第一次展开才读这份卷的题目、建 [PaperStructure]；
     * 之后折叠再展开直接用缓存，首页不用一上来就把几十份卷的题目读进内存。
     */
    private fun togglePaper(paper: Paper, expand: Boolean) {
        if (!expand) {
            expandedPapers.remove(paper.id)
            saveExpanded()
            render()
            return
        }
        expandedPapers.add(paper.id)
        saveExpanded()
        if (structures.containsKey(paper.id)) {
            render()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val structure = withContext(Dispatchers.IO) {
                db.getPaper(paper.id)?.let { PaperStructure(it.questions) }
            }
            if (_binding == null) return@launch
            if (structure != null) structures[paper.id] = structure
            render()
        }
    }

    /** 展开 / 收起某个大类的材料 */
    private fun toggleSection(key: String) {
        if (!expandedSections.remove(key)) expandedSections.add(key)
        saveExpanded()
        render()
    }

    // ---------------------------------------------------------------- 组（标题）

    private fun toggle(set: PaperSet, fold: Boolean) {
        val key = adapter.keyOf(set)
        if (fold) collapsed.add(key) else collapsed.remove(key)
        saveCollapsed()
        // 折叠只是重排节点，不必回库再读一遍
        render()
    }

    /**
     * 长按标题：整组作答 / 重命名 / 删除整组。
     * 「未分组」是兜底伪组（没有组行），只能改名以外的删除，也没有整组作答。
     */
    private fun showSetActions(set: PaperSet) {
        val canAnswerGroup = set.id > 0 && set.papers.isNotEmpty()
        val items = ArrayList<String>()
        if (canAnswerGroup) items.add(getString(R.string.tree_answer_group))
        if (set.id > 0) items.add("重命名标题")
        items.add(
            if (set.id > 0) "删除这一组（${set.paperCount} 份试卷）"
            else "删除这些试卷（${set.paperCount} 份）"
        )
        val answerIndex = if (canAnswerGroup) 0 else -1
        val renameIndex = if (set.id > 0) items.lastIndex - 1 else -1
        FbDialog.list(
            context = requireContext(),
            title = set.title,
            items = items.toTypedArray(),
            onPick = { which ->
                when (which) {
                    answerIndex -> answerGroup(set)
                    renameIndex -> FbDialog.input(
                        context = requireContext(),
                        title = "重命名标题",
                        initial = set.title,
                        hint = "导入标题",
                        multiline = false,
                        okText = getString(R.string.action_confirm),
                        onOk = { text ->
                            val name = text.trim()
                            if (name.isNotEmpty()) {
                                if (name != set.title && db.findPaperSetByTitle(name) != null) {
                                    FbDialog.alert(requireContext(), "已经有同名标题「$name」，换一个吧。")
                                } else {
                                    db.renamePaperSet(set.id, name)
                                    refresh()
                                }
                            }
                        }
                    )
                    else -> confirmDeleteSet(set)
                }
            }
        )
    }

    private fun confirmDeleteSet(set: PaperSet) {
        FbDialog.confirm(
            requireContext(),
            "删除「${set.title}」这一组？组里 ${set.paperCount} 份试卷的题目和作答记录会一起删除，删了不能恢复。",
            okText = getString(R.string.action_delete),
            onOk = {
                if (set.id > 0) {
                    db.deletePaperSet(set.id)
                } else {
                    set.papers.forEach { db.deletePaper(it.id) }
                }
                collapsed.remove(adapter.keyOf(set))
                saveCollapsed()
                refresh()
            }
        )
    }

    // ---------------------------------------------------------------- 试卷

    /**
     * 长按：查看题目 / 补录答案 / 导入答案 / 删除。
     * 导入的试卷常常没答案，补答案是这里的日常操作；分模块答题在首页树上直接点层级。
     */
    private fun showPaperActions(paper: Paper) {
        val actions = arrayOf(
            getString(R.string.action_view_questions),
            getString(R.string.action_fill_answer),
            getString(R.string.action_import_answer),
            getString(R.string.action_delete)
        )
        FbDialog.list(
            context = requireContext(),
            title = paper.name,
            items = actions,
            onPick = { which ->
                when (which) {
                    0 -> startActivity(Intent(requireContext(), PaperDetailActivity::class.java).apply {
                        putExtra(PaperDetailActivity.EXTRA_PAPER_ID, paper.id)
                        putExtra(PaperDetailActivity.EXTRA_PAPER_NAME, paper.name)
                    })
                    1 -> startActivity(Intent(requireContext(), AnswerEntryActivity::class.java).apply {
                        putExtra(AnswerEntryActivity.EXTRA_PAPER_ID, paper.id)
                    })
                    2 -> {
                        pendingPaper = paper
                        try {
                            answerPicker.launch(AnswerImportUi.MIME)
                        } catch (_: Exception) {
                            answerPicker.launch(arrayOf("*/*"))
                        }
                    }
                    else -> confirmDelete(paper.id, paper.name)
                }
            }
        )
    }

    private fun confirmDelete(paperId: Long, name: String) {
        FbDialog.confirm(
            requireContext(),
            "删除试卷「$name」？这份卷的题目和它的作答记录会一起删除，删了不能恢复。",
            okText = getString(R.string.action_delete),
            onOk = {
                db.deletePaper(paperId)
                refresh()
            }
        )
    }

    // ---------------------------------------------------------------- 数据

    override fun refresh() {
        // 读列表放后台：首页每次 onResume 都要刷，主线程不再等这次多表查询。
        // 连续刷新时取消上一次，避免旧结果后到把新结果覆盖掉。
        if (_binding == null) return
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { db.listPaperSets() }
            if (_binding == null) return@launch
            sets = loaded
            // 卷被删 / 重组后，缓存里的结构与展开状态跟着失效，别在树上留下点不开的行
            val ids = loaded.flatMap { set -> set.papers.map { it.id } }.toHashSet()
            structures.keys.retainAll(ids)
            expandedPapers.retainAll(ids)
            // 上次退出时展开着的卷，回来要把结构补齐再排节点（结构是懒加载的缓存）
            val missing = expandedPapers.filter { it !in structures }
            if (missing.isNotEmpty()) {
                val loadedStructures = withContext(Dispatchers.IO) {
                    missing.mapNotNull { id -> db.getPaper(id)?.let { id to PaperStructure(it.questions) } }
                }
                if (_binding == null) return@launch
                loadedStructures.forEach { (id, structure) -> structures[id] = structure }
            }
            binding.emptyBox.visibility = if (isTreeEmpty()) View.VISIBLE else View.GONE
            binding.emptyAction.visibility = if (isTreeEmpty()) View.VISIBLE else View.GONE
            binding.empty.setText(if (isTreeEmpty()) getString(R.string.empty_papers) else "")
            render()
        }
    }

    /** 空组（卷都被删干净了）不占列表：db.deletePaper 已顺手清理，这里只兜个底 */
    private fun isTreeEmpty(): Boolean = sets.all { it.papers.isEmpty() }

    /** 按当前展开状态重排这棵树（不查库） */
    private fun render() {
        if (_binding == null) return
        adapter.submit(
            sets = if (isTreeEmpty()) emptyList() else sets,
            collapsed = collapsed,
            expandedPapers = expandedPapers,
            expandedSections = expandedSections,
            structures = structures
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun prefs() = requireContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun loadCollapsed() {
        collapsed.clear()
        prefs().getString(KEY_COLLAPSED, "").orEmpty()
            .split(',')
            .forEach { it.trim().toLongOrNull()?.let(collapsed::add) }
        expandedPapers.clear()
        prefs().getString(KEY_EXPANDED, "").orEmpty()
            .split(',')
            .forEach { it.trim().toLongOrNull()?.let(expandedPapers::add) }
        expandedSections.clear()
        prefs().getString(KEY_SECTIONS, "").orEmpty()
            .split(',')
            .forEach { it.trim().takeIf(String::isNotEmpty)?.let(expandedSections::add) }
    }

    private fun saveCollapsed() {
        prefs().edit().putString(KEY_COLLAPSED, collapsed.joinToString(",")).apply()
    }

    private fun saveExpanded() {
        prefs().edit()
            .putString(KEY_EXPANDED, expandedPapers.joinToString(","))
            .putString(KEY_SECTIONS, expandedSections.joinToString(","))
            .apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCollapsed()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val PREFS = "paper_tree"
        private const val KEY_COLLAPSED = "collapsed"

        /** 展开了层级的试卷 id */
        private const val KEY_EXPANDED = "expanded_papers"

        /** 展开了材料的大类键（`paperId:startIndex`） */
        private const val KEY_SECTIONS = "expanded_sections"
    }
}
