package com.lytree.qbank.ui.recite

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lytree.qbank.R
import com.lytree.qbank.data.ReciteRepository
import com.lytree.qbank.data.model.ReciteCard
import com.lytree.qbank.data.model.ReciteCatalog
import com.lytree.qbank.data.model.ReciteCategory
import com.lytree.qbank.databinding.FragmentReciteBinding
import com.lytree.qbank.ui.LibrarySwitch
import com.lytree.qbank.ui.idiom.IdiomListFragment
import com.lytree.qbank.ui.word.WordListFragment
import java.text.Collator
import java.util.Locale
import kotlin.math.abs

/**
 * 考点背诵（对齐超格考点背诵），并进「词库」Tab，与词库 / 成语共用顶部三段切换。
 *
 * · 未掌握 / 已掌握两个标签页，数量实时更新
 * · 填空式背诵卡：**左右滑动切题**，**长按显示答案**（松手仍在，轻点收起）
 * · **背诵模式**：顶栏的睁眼图标即开关，打开后答案直接显示、图标染主蓝，适合通读 / 朗读
 * · 卡片右上角星标：重点收藏，配合「只看重点」筛选
 * · 顶栏：背诵模式开关、考点范围（分类 / 只看重点）、字号 A小/A中/A大
 * · 范围面板不再有「全部考点」：分组与组内课程都按拼音重排、「未分组」垫底，
 *   首次进来默认落在第一个有考点的分组
 * · 页面无底部按钮，切题全靠左右滑动，卡面留给正文
 *
 * 掌握、重点、背诵模式、字号、上次停在哪张卡都按考点 id / 设置项存库，退出重进仍在；
 * 分词正文与答案来自 assets。
 */
class ReciteFragment : Fragment() {

    private var _binding: FragmentReciteBinding? = null
    private val binding get() = _binding!!

    private lateinit var fontAction: TextView

    /** 顶栏「背诵模式」开关：图标 + 底色随开关变化 */
    private lateinit var modeAction: ImageView

    private var catalog: ReciteCatalog = ReciteCatalog.EMPTY
    private val mastered = HashSet<String>()
    private val favorites = HashSet<String>()

    /** 自定义分组（按创建顺序）与「分类 -> 分组」覆盖，存 settings 表 */
    private val customGroups = ArrayList<String>()
    private val groupOverrides = HashMap<String, String>()
    private val catGroup = HashMap<String, String>()

    private var tab = TAB_UNMASTERED
    private var categoryFilter: String? = null
    private var favoriteOnly = false
    private var currentId: String? = null
    private var revealed = false
    private var fontLevel = 0

    /** 背诵模式：答案直接显示，不再需要长按 */
    private var reciteMode = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReciteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val context = requireContext()
        catalog = ReciteRepository.catalog(context)
        mastered += ReciteRepository.masteredIds(context)
        favorites += ReciteRepository.favoriteIds(context)
        fontLevel = ReciteRepository.fontLevel(context)
        reciteMode = ReciteRepository.reciteMode(context)
        loadGroups()
        categoryFilter = ReciteRepository.rememberedCategory(context)
            ?.takeIf { id -> isValidScope(id) }
            ?: defaultScope()
        restoreState(savedInstanceState)

        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle(getString(R.string.title_recite))
        // 顶栏第一颗图标就是「背诵模式」开关：睁眼=答案常显，一眼看得出当前是不是在背诵模式
        modeAction = binding.titleBar.addIconAction(
            R.drawable.ic_recite_mode,
            getString(R.string.recite_mode)
        ) { setReciteMode(!reciteMode) }
        binding.titleBar.addIconAction(
            R.drawable.ic_recite_catalog,
            getString(R.string.recite_catalog_title)
        ) { showCatalog() }
        fontAction = binding.titleBar.addTextAction("") { cycleFont() }
        fontAction.setTextColor(ContextCompat.getColor(context, R.color.fb_text_secondary))

        // 词库 / 成语 / 考点背诵 三段切换：当前已在背诵，点另外两段把本页换掉
        LibrarySwitch.bind(
            binding.librarySwitch,
            LibrarySwitch.PAGE_RECITE,
            onWords = { openWords() },
            onIdioms = { openIdioms() },
            onRecite = {}
        )

        binding.tabUnmastered.setOnClickListener { switchTab(TAB_UNMASTERED) }
        binding.tabMastered.setOnClickListener { switchTab(TAB_MASTERED) }
        binding.btnStar.setOnClickListener { toggleFavorite() }

        updateModeAction()
        setupCardGestures()
        updateFontAction()
        render(animate = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
        outState.putString(STATE_CATEGORY, categoryFilter)
        outState.putBoolean(STATE_FAVORITE_ONLY, favoriteOnly)
        outState.putString(STATE_CARD, currentId)
        outState.putBoolean(STATE_REVEALED, revealed)
    }

    private fun restoreState(state: Bundle?) {
        if (state == null) {
            // 切到别的段再回来：接着上次那张卡背，不用从第一条重来
            currentId = ReciteRepository.lastCard(requireContext())
            return
        }
        tab = state.getInt(STATE_TAB, TAB_UNMASTERED)
        favoriteOnly = state.getBoolean(STATE_FAVORITE_ONLY, false)
        if (state.containsKey(STATE_CATEGORY)) {
            categoryFilter = state.getString(STATE_CATEGORY)
                ?.takeIf { id -> isValidScope(id) }
        }
        currentId = state.getString(STATE_CARD)
        revealed = state.getBoolean(STATE_REVEALED, false)
    }

    override fun onPause() {
        super.onPause()
        // 记住停在哪张卡：切段 / 退出重进都接着背
        currentId?.let { ReciteRepository.rememberCard(requireContext(), it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------------------------------------------------------------- 页面切换

    /** 换回词库页：仍在「词库」这个底部 Tab 里，只是页面内容换了 */
    private fun openWords() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, WordListFragment())
            .commit()
    }

    /** 换到成语汇总页：同上，仍留在词库 Tab 里 */
    private fun openIdioms() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, IdiomListFragment())
            .commit()
    }

    // ---------------------------------------------------------------- 渲染

    private fun render(animate: Boolean, forward: Boolean = true) {
        if (!isAdded) return
        renderTabs()
        renderFilterChip()

        val list = cardsFor(tab)
        if (list.isEmpty()) {
            currentId = null
            binding.card.animate().cancel()
            binding.card.alpha = 1f
            binding.card.translationX = 0f
            binding.card.visibility = View.GONE
            binding.emptyText.isVisible = true
            binding.emptyText.setText(emptyMessage())
            binding.tvProgress.text = ""
            return
        }

        binding.emptyText.isVisible = false
        val target = list.firstOrNull { it.id == currentId } ?: list.first()
        if (target.id != currentId) {
            currentId = target.id
            revealed = false
        }
        binding.card.visibility = View.VISIBLE
        if (animate) animateTo(target, forward) else bindCard(target)
    }

    private fun animateTo(card: ReciteCard, forward: Boolean) {
        val out = if (forward) -dp(16).toFloat() else dp(16).toFloat()
        val from = -out
        binding.card.animate().cancel()
        val hidden = binding.card.visibility != View.VISIBLE
        if (hidden || binding.card.alpha < 1f) {
            binding.card.visibility = View.VISIBLE
            bindCard(card)
            binding.card.alpha = 0f
            binding.card.translationX = from
            binding.card.animate().alpha(1f).translationX(0f).setDuration(180).start()
            return
        }
        binding.card.animate().alpha(0f).translationX(out).setDuration(110)
            .withEndAction {
                if (_binding == null) return@withEndAction
                bindCard(card)
                binding.card.alpha = 0f
                binding.card.translationX = from
                binding.card.animate().alpha(1f).translationX(0f).setDuration(180).start()
            }.start()
    }

    private fun bindCard(card: ReciteCard) {
        val showAnswer = reciteMode || revealed
        binding.cardText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f + fontLevel * 2)
        binding.cardText.setLineSpacing(dp(7 + fontLevel).toFloat(), 1f)
        binding.cardText.text = cardSpanned(card, reveal = showAnswer)
        binding.cardHint.setText(
            when {
                reciteMode -> R.string.recite_hint_mode
                revealed -> R.string.recite_hint_hide
                else -> R.string.recite_hint_reveal
            }
        )
        val favorite = card.id in favorites
        binding.btnStar.setImageResource(
            if (favorite) R.drawable.ic_fb_star_filled else R.drawable.ic_fb_star
        )
        binding.btnStar.imageTintList = if (favorite) {
            null
        } else {
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.fb_text_meta))
        }
        binding.btnStar.contentDescription = getString(R.string.recite_favorite)
        renderProgress()
    }

    /** 当前第几张 / 本范围共几张 */
    private fun renderProgress() {
        val list = cardsFor(tab)
        val index = list.indexOfFirst { it.id == currentId }
        binding.tvProgress.text = if (index < 0) {
            ""
        } else {
            getString(R.string.recite_progress_fmt, index + 1, list.size)
        }
    }

    private fun renderTabs() {
        val scope = cardsInScope()
        val unmastered = scope.count { it.id !in mastered }
        val masteredCount = scope.count { it.id in mastered }
        binding.tvUnmastered.text = getString(R.string.recite_tab_unmastered, unmastered)
        binding.tvMastered.text = getString(R.string.recite_tab_mastered, masteredCount)

        val selectedColor = ContextCompat.getColor(requireContext(), R.color.fb_primary)
        val normalColor = ContextCompat.getColor(requireContext(), R.color.fb_text_meta)
        val unselected = tab == TAB_UNMASTERED
        binding.tvUnmastered.setTextColor(if (unselected) selectedColor else normalColor)
        binding.tvMastered.setTextColor(if (unselected) normalColor else selectedColor)
        binding.tvUnmastered.setTypeface(null, if (unselected) Typeface.BOLD else Typeface.NORMAL)
        binding.tvMastered.setTypeface(null, if (unselected) Typeface.NORMAL else Typeface.BOLD)
        binding.indUnmastered.isVisible = unselected
        binding.indMastered.isVisible = !unselected
    }

    private fun renderFilterChip() {
        val label = when {
            categoryFilter != null && favoriteOnly ->
                "${categoryName(categoryFilter)} · ${getString(R.string.recite_filter_favorite)}"
            categoryFilter != null -> categoryName(categoryFilter)
            favoriteOnly -> getString(R.string.recite_filter_favorite)
            else -> null
        }
        binding.filterChip.isVisible = label != null
        if (label != null) {
            binding.filterChip.text = getString(R.string.recite_filter_fmt, label)
            // 面板里已经没有「全部考点」，清掉筛选就回到默认的第一组，不留「全部」这个看不见的状态
            binding.filterChip.setOnClickListener { applyFilter(defaultScope(), false) }
        }
    }

    private fun emptyMessage(): CharSequence {
        if (favoriteOnly && favorites.isEmpty()) return getString(R.string.recite_empty_favorite)
        if (categoryFilter != null && catalog.cards.none { inScope(it, categoryFilter) }) {
            return getString(R.string.recite_empty_scope)
        }
        return getString(
            if (tab == TAB_UNMASTERED) R.string.recite_empty_unmastered
            else R.string.recite_empty_mastered
        )
    }

    /** 当前范围内的序号：跟顶部「第几张 / 共几张」同一口径，切到某个组就从 1 重数 */
    private fun seqInScope(card: ReciteCard): Int {
        val index = cardsFor(tab).indexOfFirst { it.id == card.id }
        return if (index >= 0) index + 1 else card.seq
    }

    /** 把正文里的 ____ 换成答案（或下划线），答案用主蓝高亮 */
    private fun cardSpanned(card: ReciteCard, reveal: Boolean, withSeq: Boolean = true): CharSequence {
        val context = requireContext()
        val accent = ContextCompat.getColor(context, R.color.fb_primary)
        val soft = ContextCompat.getColor(context, R.color.fb_primary_soft)
        val hint = ContextCompat.getColor(context, R.color.fb_text_hint)
        val sb = SpannableStringBuilder()
        if (withSeq) sb.append("${seqInScope(card)}. ")

        val parts = card.text.split("____")
        for (i in parts.indices) {
            sb.append(parts[i])
            if (i >= card.answers.size) continue
            val start = sb.length
            if (reveal) {
                sb.append(card.answers[i])
                sb.setSpan(
                    ForegroundColorSpan(accent), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    BackgroundColorSpan(soft), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                sb.append("______")
                sb.setSpan(
                    ForegroundColorSpan(hint), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    // ---------------------------------------------------------------- 手势：左右切题 / 长按看答案

    /**
     * 卡片上的三件事：
     * - 左右滑：上一张 / 下一张（滑得够远或甩得够快都算）；
     * - 长按：显示答案（松手不收回，轻点才收起——低头背完再收）；
     * - 轻点：显示 / 收起答案。
     * 背诵模式下答案本来就一直显示，长按 / 轻点都不再改状态。
     */
    private fun setupCardGestures() {
        val slop = dp(44)
        val detector = android.view.GestureDetector(
            requireContext(),
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (!reciteMode) toggleReveal()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (reciteMode || revealed) return
                    binding.card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    revealed = true
                    currentCard()?.let { bindCard(it) }
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    // 竖向为主的滑动不算切题（长题干可能在页内滚）
                    if (abs(dx) < slop || abs(dx) < abs(dy) * 1.6f) return false
                    step(if (dx < 0) 1 else -1)
                    return true
                }
            }
        )
        // 只观察、不吞事件：卡片正文在 ScrollView 里，长答案要靠它纵向滚；
        // 点击 / 长按 / 左右滑由 GestureDetector 顺带识别，不影响滚动。
        val observer = View.OnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
        binding.cardScroll.setOnTouchListener(observer)
        binding.card.setOnTouchListener(observer)
    }

    private fun toggleReveal() {
        val card = currentCard() ?: return
        revealed = !revealed
        bindCard(card)
    }

    // ---------------------------------------------------------------- 筛选 / 取数

    /** scope 为 null=全部；"g|分组名"=整组；其余=单个分类 id */
    private fun inScope(card: ReciteCard, scope: String?): Boolean = when {
        scope == null -> true
        scope.startsWith(GROUP_PREFIX) ->
            (catGroup[card.categoryId] ?: UNGROUPED) == scope.removePrefix(GROUP_PREFIX)
        else -> card.categoryId == scope
    }

    private fun isValidScope(id: String?): Boolean = when {
        id == null -> true
        id.startsWith(GROUP_PREFIX) -> groupNames().contains(id.removePrefix(GROUP_PREFIX))
        else -> catalog.categories.any { it.id == id }
    }

    private fun cardsInScope(): List<ReciteCard> = catalog.cards.filter { card ->
        inScope(card, categoryFilter) && (!favoriteOnly || card.id in favorites)
    }

    private fun cardsFor(which: Int): List<ReciteCard> =
        cardsInScope().filter { (it.id in mastered) == (which == TAB_MASTERED) }

    private fun currentCard(): ReciteCard? =
        currentId?.let { id -> catalog.cards.firstOrNull { it.id == id } }

    private fun categoryName(id: String?): String = when {
        id == null -> ""
        id.startsWith(GROUP_PREFIX) -> id.removePrefix(GROUP_PREFIX)
        else -> catalog.categories.firstOrNull { it.id == id }?.name ?: ""
    }

    private fun switchTab(target: Int) {
        if (tab == target) return
        tab = target
        revealed = false
        render(animate = true)
    }

    /** 左右滑动切题：循环取下一张（到头回到第一张） */
    private fun step(delta: Int) {
        val list = cardsFor(tab)
        if (list.size <= 1) return
        val current = currentId
        val index = list.indexOfFirst { it.id == current }.let { if (it < 0) 0 else it }
        val next = list[((index + delta) % list.size + list.size) % list.size]
        currentId = next.id
        revealed = false
        render(animate = true, forward = delta > 0)
    }

    private fun toggleFavorite() {
        val card = currentCard() ?: return
        val add = card.id !in favorites
        ReciteRepository.setFavorite(requireContext(), card.id, add)
        if (add) favorites.add(card.id) else favorites.remove(card.id)

        if (!add && favoriteOnly) {
            val after = cardsFor(tab)
            currentId = after.firstOrNull()?.id
            revealed = false
            render(animate = true)
        } else {
            bindCard(card)
        }
    }

    private fun applyFilter(categoryId: String?, onlyFavorite: Boolean) {
        categoryFilter = categoryId
        favoriteOnly = onlyFavorite
        ReciteRepository.rememberCategory(requireContext(), categoryId)
        currentId = null
        revealed = false
        render(animate = true)
    }

    // ---------------------------------------------------------------- 背诵模式开关

    /**
     * 顶栏那颗睁眼图标就是开关：开 = 答案常显（图标染主蓝 + 浅蓝底），
     * 关 = 回到长按 / 轻点看答案。状态存库，下次进来还是上次的。
     */
    private fun setReciteMode(on: Boolean) {
        if (reciteMode == on) return
        reciteMode = on
        ReciteRepository.setReciteMode(requireContext(), on)
        if (!on) revealed = false
        updateModeAction()
        toast(if (on) R.string.recite_mode_on else R.string.recite_mode_off)
        currentCard()?.let { bindCard(it) }
    }

    private fun updateModeAction() {
        val context = requireContext()
        modeAction.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (reciteMode) R.color.fb_primary else R.color.fb_icon)
        )
        modeAction.background = if (reciteMode) {
            ContextCompat.getDrawable(context, R.drawable.bg_recite_mode_on)
        } else {
            null
        }
        modeAction.isSelected = reciteMode
        modeAction.contentDescription =
            getString(if (reciteMode) R.string.recite_mode_on else R.string.recite_mode)
    }

    // ---------------------------------------------------------------- 字号

    private fun updateFontAction() {
        val labels = arrayOf(
            R.string.recite_font_small,
            R.string.recite_font_medium,
            R.string.recite_font_large,
        )
        fontAction.text = getString(R.string.recite_font_fmt, getString(labels[fontLevel]))
    }

    private fun cycleFont() {
        fontLevel = (fontLevel + 1) % 3
        ReciteRepository.setFontLevel(requireContext(), fontLevel)
        updateFontAction()
        currentCard()?.let { bindCard(it) }
    }

    // ---------------------------------------------------------------- 考点范围弹层

    private fun showCatalog() {
        val context = requireContext()
        val sheet = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_recite_catalog, null)
        val rows = view.findViewById<LinearLayout>(R.id.catalogRows)
        view.findViewById<TextView>(R.id.catalogMeta).text =
            getString(R.string.recite_updated, catalog.updated, catalog.cards.size) +
                " · " + getString(R.string.recite_group_hint)

        addSheetRow(
            rows,
            getString(R.string.recite_catalog_favorite),
            getString(R.string.recite_catalog_favorite_count, favorites.size),
            favoriteOnly,
        ) {
            applyFilter(null, true)
            sheet.dismiss()
        }

        addSheetDivider(rows)
        groupNames().forEach { group ->
            // 组内课程也按拼音重排，和分组顺序同一口径
            val members = catalog.categories
                .filter { category ->
                    (catGroup[category.id] ?: UNGROUPED) == group &&
                        catalog.cards.any { it.categoryId == category.id }
                }
                .sortedWith(compareBy(CHINESE_ORDER) { it.name })
            addSheetGroupHeader(
                rows,
                group,
                if (members.isEmpty()) getString(R.string.recite_group_empty) else groupMeta(group),
                categoryFilter == GROUP_PREFIX + group,
                onMore = { anchor -> showGroupMenu(anchor, group) { reloadCatalog(sheet) } },
            ) {
                if (members.isEmpty()) return@addSheetGroupHeader
                applyFilter(GROUP_PREFIX + group, false)
                sheet.dismiss()
            }
            members.forEach { category ->
                addSheetRow(
                    rows,
                    category.name,
                    catalogCount(category.id),
                    categoryFilter == category.id && !favoriteOnly,
                    indent = 32,
                    onLongClick = { showMoveDialog(category) { reloadCatalog(sheet) } },
                ) {
                    applyFilter(category.id, false)
                    sheet.dismiss()
                }
            }
        }

        addSheetFooter(rows, getString(R.string.recite_group_new_action)) {
            showGroupNameDialog(null) { _, new ->
                createGroup(new)
                reloadCatalog(sheet)
            }
        }

        sheet.setContentView(view)
        sheet.show()
    }

    private fun catalogCount(categoryId: String?): String {
        val cards = catalog.cards.filter { categoryId == null || it.categoryId == categoryId }
        val unmastered = cards.count { it.id !in mastered }
        return getString(R.string.recite_catalog_count, unmastered, cards.size)
    }

    /** 分组标题：整组可点，点中即按「整组」背诵；右侧「⋯」可重命名 / 删除 / 新建分组 */
    private fun addSheetGroupHeader(
        container: LinearLayout,
        group: String,
        meta: String,
        selected: Boolean,
        onMore: (View) -> Unit,
        onClick: () -> Unit,
    ) {
        val context = requireContext()
        val accent = ContextCompat.getColor(context, R.color.fb_primary)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(8), 0)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.fb_ripple)), null, null
            )
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(context).apply {
                text = group
                setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.fb_text_body_sm)
                )
                includeFontPadding = false
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.fb_primary else R.color.fb_text_secondary
                    )
                )
                setTypeface(null, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            TextView(context).apply {
                text = meta
                setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.fb_text_tag)
                )
                includeFontPadding = false
                setTextColor(
                    if (selected) accent
                    else ContextCompat.getColor(context, R.color.fb_text_meta)
                )
            }
        )
        if (selected) {
            row.addView(
                TextView(context).apply {
                    text = "✓"
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        resources.getDimension(R.dimen.fb_text_body_size)
                    )
                    includeFontPadding = false
                    setTextColor(accent)
                    setPadding(dp(8), 0, 0, 0)
                }
            )
        }
        val more = TextView(context).apply {
            text = "⋯"
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.fb_text_item_title)
            )
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.fb_text_meta))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.fb_ripple)),
                null,
                null
            )
            setOnClickListener { onMore(this) }
        }
        row.addView(more, LinearLayout.LayoutParams(dp(40), dp(40)))

        container.addView(
            row,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
        )
    }

    private fun groupMeta(group: String): String {
        val sections = catalog.categories.count { (catGroup[it.id] ?: UNGROUPED) == group }
        val cards = catalog.cards.filter { (catGroup[it.categoryId] ?: UNGROUPED) == group }
        return getString(
            R.string.recite_catalog_group_meta,
            sections,
            cards.count { it.id !in mastered },
            cards.size,
        )
    }

    private fun addSheetRow(
        container: LinearLayout,
        title: String,
        meta: String,
        selected: Boolean,
        indent: Int = 20,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(indent), 0, dp(20), 0)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(ContextCompat.getColor(context, R.color.fb_ripple)),
                null,
                null
            )
            setOnClickListener { onClick() }
            onLongClick?.let { cb ->
                isLongClickable = true
                setOnLongClickListener { cb(); true }
            }
        }
        row.addView(
            TextView(context).apply {
                text = title
                setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.fb_text_item_title)
                )
                includeFontPadding = false
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (selected) R.color.fb_primary else R.color.fb_text_primary
                    )
                )
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            TextView(context).apply {
                text = meta
                setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.fb_text_tag)
                )
                includeFontPadding = false
                setTextColor(ContextCompat.getColor(context, R.color.fb_text_meta))
            }
        )
        if (selected) {
            row.addView(
                TextView(context).apply {
                    text = "✓"
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_PX,
                        resources.getDimension(R.dimen.fb_text_body_size)
                    )
                    includeFontPadding = false
                    setTextColor(ContextCompat.getColor(context, R.color.fb_primary))
                    setPadding(dp(8), 0, 0, 0)
                }
            )
        }
        container.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.fb_row_h)
            )
        )
    }

    private fun addSheetDivider(container: LinearLayout) {
        val context = requireContext()
        container.addView(
            View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.fb_divider))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(20)
                marginEnd = dp(20)
            }
        )
    }

    private fun addSheetFooter(container: LinearLayout, text: String, onClick: () -> Unit) {
        val context = requireContext()
        container.addView(
            TextView(context).apply {
                this.text = text
                setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.fb_text_body_size)
                )
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                setTextColor(ContextCompat.getColor(context, R.color.fb_primary))
                isClickable = true
                isFocusable = true
                background = RippleDrawable(
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.fb_ripple)),
                    null,
                    null
                )
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.fb_row_h)
            ).apply { topMargin = dp(4) }
        )
    }

    // ---------------------------------------------------------------- 分组增删改

    /** 分组「⋯」菜单：重命名 / 删除 / 新建分组 */
    private fun showGroupMenu(anchor: View, group: String, onChanged: () -> Unit) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_RENAME, 0, R.string.recite_group_rename)
            menu.add(0, MENU_DELETE, 1, R.string.recite_group_delete)
            menu.add(0, MENU_NEW, 2, R.string.recite_group_new)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RENAME -> showGroupNameDialog(group) { old, new ->
                        renameGroup(old, new)
                        onChanged()
                    }
                    MENU_DELETE -> confirmDeleteGroup(group, onChanged)
                    MENU_NEW -> showGroupNameDialog(null) { _, new ->
                        createGroup(new)
                        onChanged()
                    }
                }
                true
            }
            show()
        }
    }

    /** 新建 / 重命名分组：传 group=null 为新建 */
    private fun showGroupNameDialog(group: String?, onDone: (String, String) -> Unit) {
        val context = requireContext()
        val input = EditText(context).apply {
            hint = getString(R.string.recite_group_name_hint)
            setText(group.orEmpty())
            setSelection(text.length)
            setSingleLine()
        }
        val wrap = FrameLayout(context).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(context)
            .setTitle(if (group == null) R.string.recite_group_new else R.string.recite_group_rename)
            .setView(wrap)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (name == UNGROUPED) {
                    toast(R.string.recite_group_name_reserved)
                    return@setPositiveButton
                }
                onDone(group.orEmpty(), name)
            }
            .show()
    }

    private fun createGroup(name: String) {
        if (groupNames().contains(name)) return
        customGroups.add(name)
        persistGroups()
    }

    private fun renameGroup(old: String, new: String) {
        if (old == new || new.isBlank()) return
        if (groupNames().contains(new)) {
            toast(R.string.recite_group_exists)
            return
        }
        catalog.categories.forEach { category ->
            if ((catGroup[category.id] ?: UNGROUPED) == old) groupOverrides[category.id] = new
        }
        val idx = customGroups.indexOf(old)
        if (idx >= 0) customGroups[idx] = new
        if (categoryFilter == GROUP_PREFIX + old) categoryFilter = GROUP_PREFIX + new
        persistGroups()
    }

    private fun confirmDeleteGroup(group: String, onChanged: () -> Unit) {
        if (group == UNGROUPED) {
            toast(R.string.recite_group_ungrouped_locked)
            return
        }
        val count = catalog.categories.count { (catGroup[it.id] ?: UNGROUPED) == group }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.recite_group_delete_title, group))
            .setMessage(getString(R.string.recite_group_delete_msg, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                catalog.categories.forEach { category ->
                    if ((catGroup[category.id] ?: UNGROUPED) == group) {
                        groupOverrides[category.id] = UNGROUPED
                    }
                }
                customGroups.remove(group)
                if (categoryFilter == GROUP_PREFIX + group) categoryFilter = null
                persistGroups()
                onChanged()
            }
            .show()
    }

    /** 课程（分类）移动到其他分组，长按课程行触发 */
    private fun showMoveDialog(category: ReciteCategory, onChanged: () -> Unit) {
        val names = groupNames()
        val current = catGroup[category.id] ?: UNGROUPED
        val options = names.toMutableList().apply {
            if (!contains(UNGROUPED)) add(0, UNGROUPED)
        }
        val checked = options.indexOf(current)
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.recite_group_move_title, category.name))
            .setSingleChoiceItems(options.toTypedArray(), checked) { dialog, which ->
                val target = options[which]
                if (target != current) {
                    groupOverrides[category.id] = target
                    if (categoryFilter == category.id) categoryFilter = null
                    persistGroups()
                    onChanged()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 分组操作后重开弹层，让列表按最新分组重排 */
    private fun reloadCatalog(sheet: BottomSheetDialog) {
        sheet.dismiss()
        binding.root.post { if (isAdded) showCatalog() }
    }

    private fun loadGroups() {
        val layout = ReciteRepository.groupLayout(requireContext())
        customGroups.clear()
        customGroups.addAll(layout.first)
        groupOverrides.clear()
        groupOverrides.putAll(layout.second)
        rebuildGroups()
    }

    /** 把「分类 -> 分组」算进缓存，分组筛选 / 标题都用它 */
    private fun rebuildGroups() {
        catGroup.clear()
        catalog.categories.forEach { category ->
            catGroup[category.id] =
                (groupOverrides[category.id] ?: category.group).ifBlank { UNGROUPED }
        }
    }

    private fun persistGroups() {
        ReciteRepository.saveGroupLayout(requireContext(), customGroups, groupOverrides)
        rebuildGroups()
    }

    /** 分组顺序：自定义分组在前（按创建顺序），其余按课程顺序 */
    private fun groupNames(): List<String> {
        val names = LinkedHashSet<String>()
        customGroups.forEach(names::add)
        catalog.categories.forEach { names.add(catGroup[it.id] ?: UNGROUPED) }
        // 面板顺序按拼音重排，「未分组」永远垫底：它是兜底组，夹在课程组中间会打断阅读
        return names.sortedWith(CHINESE_ORDER).sortedBy { it == UNGROUPED }
    }

    /** 面板里已没有「全部考点」这一项，进来默认落到第一个有考点的分组 */
    private fun defaultScope(): String? =
        groupNames()
            .firstOrNull { group ->
                catalog.cards.any { (catGroup[it.categoryId] ?: UNGROUPED) == group }
            }
            ?.let { GROUP_PREFIX + it }

    // ---------------------------------------------------------------- 小工具

    private fun toast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        /** 中文按拼音排：分组 / 分类都用它，面板顺序才稳定可预期 */
        val CHINESE_ORDER = Comparator<String> { a, b ->
            Collator.getInstance(Locale.CHINA).compare(a, b)
        }

        const val TAB_UNMASTERED = 0
        const val TAB_MASTERED = 1

        /** 分组筛选的前缀：categoryFilter 为 "g|超格 · 新思想" 时代表按整组筛选 */
        const val GROUP_PREFIX = "g|"

        /** 未分组：删除分组后课程落到这里，也作为可编辑的普通分组出现 */
        const val UNGROUPED = "未分组"

        const val MENU_RENAME = 1
        const val MENU_DELETE = 2
        const val MENU_NEW = 3

        const val STATE_TAB = "recite_tab"
        const val STATE_CATEGORY = "recite_category"
        const val STATE_FAVORITE_ONLY = "recite_favorite_only"
        const val STATE_CARD = "recite_card"
        const val STATE_REVEALED = "recite_revealed"
    }
}
