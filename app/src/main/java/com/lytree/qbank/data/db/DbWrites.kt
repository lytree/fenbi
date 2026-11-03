package com.lytree.qbank.data.db

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 数据库写队列（进程级单线程）。
 *
 * 作答进度、每题用时、草稿记录状态这些「写」都排到同一条线程上：
 * - 对 UI 而言不再有主线程 SQL；
 * - 对数据而言先后顺序严格 FIFO，不会出现「旧的 saveProgress 晚于交卷事务落库」这种覆盖；
 * - 生命周期边界（onPause / 退出 / 交卷事务之前）用 [flush] 等队列落完，保证不丢。
 *
 * 读不进这里：页面启动时的一次性读取统一在 IO 上批量做，回主线程装配。
 */
object DbWrites {

    private const val TAG = "DbWrites"
    private const val FLUSH_TIMEOUT_MS = 3_000L

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "db-write") }

    /** 投递一个写任务；异常只记日志，不打断后续任务（否则一次写失败会让队列停摆） */
    fun submit(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "后台写库失败", e)
            }
        }
    }

    /**
     * 等队列里已投递的写全部落完（最多 [timeoutMs]）。
     *
     * 只做「屏障 + 等待」，不产生新的业务写；超时只记日志，不抛异常——
     * 交卷流程不应因为一次写超时而中断。
     */
    fun flush(timeoutMs: Long = FLUSH_TIMEOUT_MS) {
        try {
            executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "等待写队列超时", e)
        }
    }
}
