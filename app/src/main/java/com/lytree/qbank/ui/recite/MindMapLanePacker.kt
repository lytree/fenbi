package com.lytree.qbank.ui.recite

/**
 * 多根导图的分栏算法（纯计算，不碰 Canvas 也不碰 StaticLayout，可以单测）。
 *
 * 一页书里的「中心主题」经常是十几个（OCR 会把「分配制度」切成三块），
 * 竖排成一列时高度是宽度的十几倍，fit 之后字小到看不清。
 * 这里把根按顺序切到几栏并排，让整幅图的长宽比接近竖屏，同样的屏幕能放更大的字。
 *
 * 两条约束：
 * 1. **保持顺序**：切是按根的顺序切的，不按高度重排——同一页书里主题的前后关系是有意义的；
 * 2. **每栏至少一根**：宁可某栏矮一点，也不要出现空栏或把最后一栏塞爆。
 */
internal object MindMapLanePacker {

    /** 误差容忍（px）：栏数更多但只省下不到半像素时不值得，选栏少的那个 */
    private const val SCORE_SLACK = 0.5f

    /**
     * 把根按顺序切成若干栏，尽量等高。
     *
     * @param heights 每个根的子树高度（含根自己）
     * @param lanes   想切成几栏；≤1 时原样返回一段
     * @param gap     同一栏里两个根之间的间距
     * @return 每栏对应 [heights] 的闭区间，按顺序排列
     */
    fun split(heights: FloatArray, lanes: Int, gap: Float): List<IntRange> {
        if (heights.isEmpty()) return emptyList()
        val whole = 0..heights.lastIndex
        // 栏数多于根数时按根数算：一栏一根，不制造空栏
        val count = minOf(lanes, heights.size)
        if (count <= 1 || heights.size <= 1) return listOf(whole)

        var total = 0f
        heights.forEach { total += it + gap }
        val target = total / count

        val out = ArrayList<IntRange>(count)
        var start = 0
        var acc = 0f
        heights.forEachIndexed { index, h ->
            // 当前这根之前先看看要不要换栏：已经攒够高度，且后面还留得下每栏至少一根
            if (index > start) {
                val lanesLeft = count - out.size // 含正在填的这一栏
                val rest = heights.size - index // 含当前这个，还没分出去的根数
                if (acc >= target && rest >= lanesLeft - 1) {
                    out.add(start until index)
                    start = index
                    acc = 0f
                }
            }
            acc += h + gap
        }
        out.add(start..heights.lastIndex)
        return out
    }

    /**
     * 挑栏数：逐个试，取「fit 之后最不浪费」的那个。
     *
     * 判据是整幅图的长宽比。适配屏幕时缩放取 `min(画布宽/图宽, 画布高/图高)`，
     * 于是哪一维超出画布比例，哪一维就先被压到——
     * 记 `score = max(宽, 高 ÷ targetRatio)`（targetRatio = 画布高 / 画布宽），
     * score 越小说明图越贴合屏幕，fit 之后字越大。**注意是除不是乘**：
     * 乘会把「高」放大成主导项，越切栏越觉得「还差得远」，最后切出一堆细长栏。
     *
     * @param targetRatio 理想的长宽比（高 / 宽）：手机竖屏约 2，平板横屏给 1
     * @param laneWidth   量一栏的宽度（含栏内列间距）；宽度和文字有关，只能由调用方量
     */
    fun pickLaneCount(
        heights: FloatArray,
        maxLanes: Int,
        gap: Float,
        laneGap: Float,
        targetRatio: Float,
        laneWidth: (IntRange) -> Float,
    ): Int {
        if (heights.size <= 1) return 1
        val limit = minOf(heights.size, maxLanes).coerceAtLeast(1)
        var best = 1
        var bestScore = Float.MAX_VALUE
        for (lanes in 1..limit) {
            var width = 0f
            var height = 0f
            split(heights, lanes, gap).forEachIndexed { index, range ->
                if (index > 0) width += laneGap
                width += laneWidth(range)
                var h = 0f
                for (i in range) h += heights[i] + gap
                height = maxOf(height, h - gap)
            }
            val score = maxOf(width, height / targetRatio)
            if (score < bestScore - SCORE_SLACK) {
                bestScore = score
                best = lanes
            }
        }
        return best
    }
}
