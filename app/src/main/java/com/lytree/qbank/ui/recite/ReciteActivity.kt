package com.lytree.qbank.ui.recite

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lytree.qbank.R
import com.lytree.qbank.data.ReciteRepository
import com.lytree.qbank.data.model.ReciteCard
import com.lytree.qbank.data.model.ReciteCatalog
import com.lytree.qbank.data.model.ReciteCategory
import com.lytree.qbank.databinding.ActivityReciteBinding
import com.lytree.qbank.databinding.DialogReciteDetailBinding
import com.lytree.qbank.ui.ScreenAdapt
import com.lytree.qbank.ui.applyNavBarBottomPadding
import kotlin.random.Random

/**
 * 考点背诵（对齐超格考点背诵）：
 *
 * · 未掌握 / 已掌握两个标签页，数量实时更新
 * · 填空式背诵卡：轻点卡片显示 / 收起答案，右上角星标重点
 * · 换一个（顺序下一个）/ 随机一个 / 记住了（未掌握→已掌握，已掌握→取消掌握）
 * · 顶栏：考点详情、考点范围（分类 / 只看重点）、字号 A小/A中/A大
 *
 * 掌握与重点状态按考点 id 存库，退出重进仍在；分词正文与答案来自 assets。
 */
class ReciteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReciteBinding
    private lateinit var fontAction: TextView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenAdapt.applyOrientation(this)
        binding = ActivityReciteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.bottomBar.applyNavBarBottomPadding()

        catalog = ReciteRepository.catalog(this)
        mastered += ReciteRepository.masteredIds(this)
        favorites += ReciteRepository.favoriteIds(this)
        fontLevel = ReciteRepository.fontLevel(this)
        loadGroups()
        categoryFilter = ReciteRepository.rememberedCategory(this)
            ?.takeIf { id -> isValidScope(id) }
        restoreState(savedInstanceState)

        binding.titleBar.showBack(true)
        binding.titleBar.setTitle(getString(R.string.title_recite))
        binding.titleBar.addIconAction(
            R.drawable.ic_recite_detail,
            getString(R.string.recite_detail_entry)
        ) { showDetail() }
        binding.titleBar.addIconAction(
            R.drawable.ic_recite_catalog,
            getString(R.string.recite_catalog_title)
        ) { showCatalog() }
        fontAction = binding.titleBar.addTextAction("") { cycleFont() }
        fontAction.setTextColor(ContextCompat.getColor(this, R.color.fb_text_secondary))

        binding.tabUnmastered.setOnClickListener { switchTab(TAB_UNMASTERED) }
        binding.tabMastered.setOnClickListener { switchTab(TAB_MASTERED) }
        binding.card.setOnClickListener {
            val card = currentCard() ?: return@setOnClickListener
            revealed = !revealed
            bindCard(card)
        }
        binding.btnStar.setOnClickListener { toggleFavorite() }
        binding.btnNext.setOnClickListener { nextCard(random = false) }
        binding.btnShuffle.setOnClickListener { nextCard(random = true) }
        binding.btnMaster.setOnClickListener { toggleMastered() }

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
        if (state == null) return
        tab = state.getInt(STATE_TAB, TAB_UNMASTERED)
        favoriteOnly = state.getBoolean(STATE_FAVORITE_ONLY, false)
        if (state.containsKey(STATE_CATEGORY)) {
            categoryFilter = state.getString(STATE_CATEGORY)
                ?.takeIf { id -> isValidScope(id) }
        }
        currentId = state.getString(STATE_CARD)
        revealed = state.getBoolean(STATE_REVEALED, false)
    }

    // ---------------------------------------------------------------- 渲染

    private fun render(animate: Boolean) {
        if (isFinishing) return
        renderTabs()
        renderFilterChip()
        binding.btnMaster.setText(
            if (tab == TAB_UNMASTERED) R.string.recite_remember else R.string.recite_forget
        )

        val list = cardsFor(tab)
        if (list.isEmpty()) {
            currentId = null
            binding.card.animate().cancel()
            binding.card.alpha = 1f
            binding.card.translationX = 0f
            binding.card.visibility = View.GONE
            binding.emptyText.isVisible = true
            binding.emptyText.setText(emptyMessage())
            setActionsEnabled(false)
            return
        }

        setActionsEnabled(true)
        binding.emptyText.isVisible = false
        val target = list.firstOrNull { it.id == currentId } ?: list.first()
        if (target.id != currentId) {
            currentId = target.id
            revealed = false
        }
        binding.card.visibility = View.VISIBLE
        if (animate) animateTo(target) else bindCard(target)
    }

    private fun animateTo(card: ReciteCard) {
        binding.card.animate().cancel()
        val hidden = binding.card.visibility != View.VISIBLE
        if (hidden || binding.card.alpha < 1f) {
            binding.card.visibility = View.VISIBLE
            bindCard(card)
            binding.card.alpha = 0f
            binding.card.translationX = dp(16).toFloat()
            binding.card.animate().alpha(1f).translationX(0f).setDuration(180).start()
            return
        }
        binding.card.animate().alpha(0f).translationX(-dp(16).toFloat()).setDuration(110)
            .withEndAction {
                bindCard(card)
                binding.card.alpha = 0f
                binding.card.translationX = dp(16).toFloat()
                binding.card.animate().alpha(1f).translationX(0f).setDuration(180).start()
            }.start()
    }

    private fun bindCard(card: ReciteCard) {
        binding.cardText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f + fontLevel * 2)
        binding.cardText.setLineSpacing(dp(7 + fontLevel).toFloat(), 1f)
        binding.cardText.text = cardSpanned(card, reveal = revealed)
        binding.cardHint.setText(
            if (revealed) R.string.recite_hint_hide else R.string.recite_hint_reveal
        )
        val favorite = card.id in favorites
        binding.btnStar.setImageResource(
            if (favorite) R.drawable.ic_fb_star_filled else R.drawable.ic_fb_star
        )
        binding.btnStar.imageTintList = if (favorite) {
            null
        } else {
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.fb_text_meta))
        }
        binding.btnStar.contentDescription = getString(R.string.recite_favorite)
    }

    private fun renderTabs() {
        val scope = cardsInScope()
        val unmastered = scope.count { it.id !in mastered }
        val masteredCount = scope.count { it.id in mastered }
        binding.tvUnmastered.text = getString(R.string.recite_tab_unmastered, unmastered)
        binding.tvMastered.text = getString(R.string.recite_tab_mastered, masteredCount)

        val selectedColor = ContextCompat.getColor(this, R.color.fb_primary)
        val normalColor = ContextCompat.getColor(this, R.color.fb_text_meta)
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
            binding.filterChip.setOnClickListener { applyFilter(null, false) }
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

    private fun setActionsEnabled(enabled: Boolean) {
        listOf(binding.btnNext, binding.btnShuffle, binding.btnMaster).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.38f
        }
    }

    /** 把正文里的 ____ 换成答案（或下划线），答案用主蓝高亮 */
    private fun cardSpanned(card: ReciteCard, reveal: Boolean, withSeq: Boolean = true): CharSequence {
        val accent = ContextCompat.getColor(this, R.color.fb_primary)
        val soft = ContextCompat.getColor(this, R.color.fb_primary_soft)
        val hint = ContextCompat.getColor(this, R.color.fb_text_hint)
        val sb = SpannableStringBuilder()
        if (withSeq) sb.append("${card.seq}. ")

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

    private fun nextCard(random: Boolean) {
        val card = currentCard() ?: return
        val list = cardsFor(tab)
        if (list.size <= 1) {
            toast(R.string.recite_only_one)
            return
        }
        val index = list.indexOfFirst { it.id == card.id }.coerceAtLeast(0)
        val next = if (random) {
            list.filter { it.id != card.id }.let { it[Random.nextInt(it.size)] }
        } else {
            list[(index + 1) % list.size]
        }
        currentId = next.id
        revealed = false
        render(animate = true)
    }

    private fun toggleMastered() {
        val card = currentCard() ?: return
        val mastering = tab == TAB_UNMASTERED
        val before = cardsFor(tab)
        val index = before.indexOfFirst { it.id == card.id }.coerceAtLeast(0)

        ReciteRepository.setMastered(this, card.id, mastering)
        if (mastering) mastered.add(card.id) else mastered.remove(card.id)
        toast(if (mastering) R.string.recite_added else R.string.recite_removed)

        val after = cardsFor(tab)
        currentId = after.getOrNull(index)?.id ?: after.lastOrNull()?.id
        revealed = false
        if (after.isEmpty()) {
            binding.card.animate().alpha(0f).translationX(-dp(16).toFloat()).setDuration(120)
                .withEndAction {
                    binding.card.alpha = 1f
                    binding.card.translationX = 0f
                    render(animate = false)
                }.start()
        } else {
            render(animate = true)
        }
    }

    private fun toggleFavorite() {
        val card = currentCard() ?: return
        val add = card.id !in favorites
        ReciteRepository.setFavorite(this, card.id, add)
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
        ReciteRepository.rememberCategory(this, categoryId)
        currentId = null
        revealed = false
        render(animate = true)
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
        ReciteRepository.setFontLevel(this, fontLevel)
        updateFontAction()
        currentCard()?.let { bindCard(it) }
    }

    // ---------------------------------------------------------------- 考点范围弹层

    private fun showCatalog() {
        val sheet = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.sheet_recite_catalog, null)
        val rows = view.findViewById<LinearLayout>(R.id.catalogRows)
        view.findViewById<TextView>(R.id.catalogMeta).text =
            getString(R.string.recite_updated, catalog.updated, catalog.cards.size) +
                " · " + getString(R.string.recite_group_hint)

        addSheetRow(
            rows,
            getString(R.string.recite_catalog_all),
            catalogCount(null),
            categoryFilter == null && !favoriteOnly,
        ) {
            applyFilter(null, false)
            sheet.dismiss()
        }
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
            val members = catalog.categories.filter { category ->
                (catGroup[category.id] ?: UNGROUPED) == group &&
                    catalog.cards.any { it.categoryId == category.id }
            }
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
        val accent = ContextCompat.getColor(this, R.color.fb_primary)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(8), 0)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(ContextCompat.getColor(context, R.color.fb_ripple)), null, null)
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(this).apply {
                text = group
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.fb_text_body_sm)
                )
                includeFontPadding = false
                setTextColor(
                    ContextCompat.getColor(
                        this@ReciteActivity,
                        if (selected) R.color.fb_primary else R.color.fb_text_secondary
                    )
                )
                setTypeface(null, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            TextView(this).apply {
                text = meta
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.fb_text_tag)
                )
                includeFontPadding = false
                setTextColor(
                    if (selected) accent
                    else ContextCompat.getColor(this@ReciteActivity, R.color.fb_text_meta)
                )
            }
        )
        if (selected) {
            row.addView(
                TextView(this).apply {
                    text = "✓"
                    setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX,
                        resources.getDimension(R.dimen.fb_text_body_size)
                    )
                    includeFontPadding = false
                    setTextColor(accent)
                    setPadding(dp(8), 0, 0, 0)
                }
            )
        }
        val more = TextView(this).apply {
            text = "⋯"
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(R.dimen.fb_text_item_title)
            )
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@ReciteActivity, R.color.fb_text_meta))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(
                    ContextCompat.getColor(this@ReciteActivity, R.color.fb_ripple)
                ), null, null
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(indent), 0, dp(20), 0)
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(
                    ContextCompat.getColor(this@ReciteActivity, R.color.fb_ripple)
                ), null, null
            )
            setOnClickListener { onClick() }
            onLongClick?.let { cb ->
                isLongClickable = true
                setOnLongClickListener { cb(); true }
            }
        }
        row.addView(
            TextView(this).apply {
                text = title
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.fb_text_item_title)
                )
                includeFontPadding = false
                setTextColor(
                    ContextCompat.getColor(
                        this@ReciteActivity,
                        if (selected) R.color.fb_primary else R.color.fb_text_primary
                    )
                )
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            TextView(this).apply {
                text = meta
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.fb_text_tag)
                )
                includeFontPadding = false
                setTextColor(ContextCompat.getColor(this@ReciteActivity, R.color.fb_text_meta))
            }
        )
        if (selected) {
            row.addView(
                TextView(this).apply {
                    text = "✓"
                    setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX,
                        resources.getDimension(R.dimen.fb_text_body_size)
                    )
                    includeFontPadding = false
                    setTextColor(
                        ContextCompat.getColor(this@ReciteActivity, R.color.fb_primary)
                    )
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
        container.addView(
            View(this).apply { setBackgroundColor(ContextCompat.getColor(this@ReciteActivity, R.color.fb_divider)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(20)
                marginEnd = dp(20)
            }
        )
    }

    private fun addSheetFooter(container: LinearLayout, text: String, onClick: () -> Unit) {
        container.addView(
            TextView(this).apply {
                this.text = text
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    resources.getDimension(R.dimen.fb_text_body_size)
                )
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                setTextColor(ContextCompat.getColor(this@ReciteActivity, R.color.fb_primary))
                isClickable = true
                isFocusable = true
                background = RippleDrawable(
                    ColorStateList.valueOf(
                        ContextCompat.getColor(this@ReciteActivity, R.color.fb_ripple)
                    ), null, null
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
        PopupMenu(this, anchor).apply {
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
        val input = EditText(this).apply {
            hint = getString(R.string.recite_group_name_hint)
            setText(group.orEmpty())
            setSelection(text.length)
            setSingleLine()
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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
        binding.root.post { showCatalog() }
    }

    private fun loadGroups() {
        val layout = ReciteRepository.groupLayout(this)
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
        ReciteRepository.saveGroupLayout(this, customGroups, groupOverrides)
        rebuildGroups()
    }

    /** 分组顺序：自定义分组在前（按创建顺序），其余按课程顺序 */
    private fun groupNames(): List<String> {
        val names = LinkedHashSet<String>()
        customGroups.forEach(names::add)
        catalog.categories.forEach { names.add(catGroup[it.id] ?: UNGROUPED) }
        return names.toList()
    }

    private fun displayName(card: ReciteCard): String {
        val category = catalog.categories.firstOrNull { it.id == card.categoryId }
            ?: return card.categoryName
        val group = catGroup[card.categoryId] ?: UNGROUPED
        return if (group == UNGROUPED) "$UNGROUPED · ${category.name}" else "$group · ${category.name}"
    }

    // ---------------------------------------------------------------- 考点详情

    private fun showDetail() {
        val card = currentCard() ?: return
        val b = DialogReciteDetailBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
        dialog.setContentView(b.root)
        dialog.setCanceledOnTouchOutside(true)

        b.tvDetailTitle.text =
            getString(R.string.recite_detail_title_fmt, card.seq, displayName(card))
        b.tvDetailText.text = cardSpanned(card, reveal = true, withSeq = false)
        if (card.note.isBlank()) {
            b.tvDetailNoteLabel.visibility = View.GONE
            b.tvDetailNote.visibility = View.GONE
        } else {
            b.tvDetailNote.text = card.note
        }
        b.tvDetailSource.text = getString(
            R.string.recite_detail_source,
            card.source.ifBlank { getString(R.string.recite_catalog_all) }
        )
        b.btnDetailClose.setOnClickListener { dialog.dismiss() }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = resources.displayMetrics
            val maxWidth = (340 * dm.density).toInt()
            val width = minOf(maxWidth, dm.widthPixels - (56 * dm.density).toInt())
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- 小工具

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
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
