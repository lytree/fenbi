package com.lytree.qbank.ui

/**
 * 「选项打叉」状态：长按某个选项把它划掉，再长按恢复。
 *
 * 这是做题时的**排除草稿**，不是作答内容——不进数据库、不参与判分，
 * 交卷后也不清（复习时还能看见当初排除了哪几项），进程退出自然归零。
 *
 * 之所以要单独存一份而不是塞在 [QuestionCardBinder] 的局部变量里：
 * 选项每次重绑（翻页、滚动复用、切单题 / 多题、即时判分）都是重新 inflate 的，
 * 状态若跟着 View 走，一滑就丢。键是**题目 id + 选项字母**，与卡片实例无关，
 * 所以主答题页、材料组页、分屏页看到的是同一份。
 */
object OptionCross {

    private val store = HashMap<Long, MutableSet<String>>()

    /** 该题已划掉的选项（大写字母），没有时返回空集合 */
    fun keys(questionId: Long): Set<String> = store[questionId].orEmpty()

    fun isCrossed(questionId: Long, key: String): Boolean =
        store[questionId]?.contains(key.uppercase()) == true

    /** 切换打叉，返回切换后是否处于「已划掉」状态 */
    fun toggle(questionId: Long, key: String): Boolean {
        val k = key.uppercase()
        val set = store.getOrPut(questionId) { mutableSetOf() }
        val crossed = if (set.remove(k)) false else set.add(k)
        // 一题全恢复成不划时把空集合摘掉，长卷做下来不留一堆空壳
        if (set.isEmpty()) store.remove(questionId)
        return crossed
    }
}
