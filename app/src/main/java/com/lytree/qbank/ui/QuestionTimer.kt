package com.lytree.qbank.ui

import com.lytree.qbank.data.db.QBankDb

/**
 * 每题独立计时。
 *
 * 答题页 / 分屏页共用一个计时器实例：**同一时刻只给一道题计时**，切到下一题时
 * 把上一题这一段秒数累加进库（`record_question_times`，一题一行），于是：
 * - 交卷后成绩报告能逐题看「这道题磨了几秒」，而不是只有一个总用时；
 * - 中途退出也不丢——切题、退到后台、交卷都会 [flush] 一次。
 *
 * 只记整秒，不满 1 秒的零头丢掉：这类零头对分析做题节奏没有意义，
 * 反而会让每题都凭空多出 1 秒。
 */
class QuestionTimer(private val db: QBankDb) {

    /** 秒数挂在哪条记录上（草稿记录一进卷就有，交卷后转正仍是同一个 id） */
    private var recordId = 0L

    /** 正在计时的题；0 表示没在计时（单题浏览停在模块说明页、页面已不可见等） */
    private var currentId = 0L

    /** 本题这一段的起点（墙上时间） */
    private var sinceAt = 0L

    /** 已累计、还没落库的秒数（切题时一次性写库） */
    private val pending = HashMap<Long, Int>()

    fun bindRecord(id: Long) {
        recordId = id
    }

    /**
     * 把计时切到第 [questionId] 题（0 / null = 暂停计时）。
     *
     * 同一道题重复调用没有副作用：不会清零，也不会重复计秒。
     */
    fun switchTo(questionId: Long?) {
        val target = questionId ?: 0L
        if (target == currentId) return
        accumulate()
        currentId = target
        sinceAt = if (target > 0L) System.currentTimeMillis() else 0L
        flush()
    }

    /**
     * 结算当前这一段的秒数并入待写队列。
     *
     * 单独抽出来是因为 [flush] 要在「不停表」的情况下写库（例如退到后台、
     * 弹窗里补答案回来），此时仍然在给同一道题计时。
     */
    fun accumulate() {
        if (currentId <= 0L || sinceAt <= 0L) return
        val now = System.currentTimeMillis()
        val sec = ((now - sinceAt) / 1000).toInt()
        if (sec > 0) {
            pending[currentId] = (pending[currentId] ?: 0) + sec
            sinceAt += sec * 1000L
        }
    }

    /** 把待写的秒数落库（不改变当前计时目标） */
    fun flush() {
        if (pending.isEmpty()) return
        if (recordId > 0L) {
            pending.forEach { (qid, sec) -> db.addQuestionTime(recordId, qid, sec) }
        }
        pending.clear()
    }

    /** 结算并落库，然后停表（退出页面 / 交卷时用） */
    fun stop() {
        accumulate()
        flush()
        currentId = 0L
        sinceAt = 0L
    }

    /** 当前题已计时的秒数（含正在走的一段，界面上要实时显示时用） */
    fun currentSeconds(): Int {
        val base = pending[currentId] ?: 0
        if (currentId <= 0L || sinceAt <= 0L) return base
        return base + ((System.currentTimeMillis() - sinceAt) / 1000).toInt()
    }
}
