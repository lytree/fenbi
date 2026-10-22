package com.lytree.qbank.ui

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * 材料配图加载器。
 *
 * 资料分析里的「图谱类」材料（折线图 / 柱状图 / 饼图）在原始题库里本来就是一张图，
 * 所以材料可以直接引用图片，而不是把数据翻译成图表。支持的写法见 [MaterialRenderer]：
 *
 * - 本地绝对路径：`/sdcard/Download/题目/图1.png`
 * - `file://`、`content://`（相册 / 文件选择器给的 URI）
 * - `asset://samples/xxx.png`（应用内置图）
 * - `data:image/png;base64,...`（自包含题库，不用带附件）
 *
 * 只做了本地解码：没有网络权限，也避免刷题时因为图挂了看不到材料。
 * 解码按目标宽度降采样，并用 LruCache 缓存，翻题来回不会重复读盘。
 */
object ImageLoader {

    /** 单边最大解码宽度：再大对手机屏也没有意义，只会吃内存 */
    private const val MAX_WIDTH = 1600

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun cached(src: String): Bitmap? = cache.get(src)

    /** 异步加载；[onReady] 一定在主线程回调，失败给 null */
    fun load(context: Context, src: String, reqWidth: Int, onReady: (Bitmap?) -> Unit) {
        cache.get(src)?.let {
            onReady(it)
            return
        }
        val app = context.applicationContext
        val width = reqWidth.coerceIn(320, MAX_WIDTH)
        executor.execute {
            val bmp = try {
                decode(app, src, width)
            } catch (_: Throwable) {
                null
            }
            if (bmp != null) cache.put(src, bmp)
            main.post { onReady(bmp) }
        }
    }

    private fun decode(context: Context, src: String, reqWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, src)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openStream(context, src)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun openStream(context: Context, raw: String): InputStream? {
        val src = raw.trim()
        return when {
            src.startsWith("data:", ignoreCase = true) -> {
                val comma = src.indexOf(',')
                if (comma < 0) null
                else ByteArrayInputStream(Base64.decode(src.substring(comma + 1), Base64.DEFAULT))
            }

            src.startsWith("asset://", ignoreCase = true) ->
                context.assets.open(src.removePrefix("asset://").trimStart('/'))

            src.startsWith("content://", ignoreCase = true) ->
                context.contentResolver.openInputStream(Uri.parse(src))

            src.startsWith("file://", ignoreCase = true) -> {
                val path = Uri.parse(src).path
                if (path.isNullOrBlank()) null else File(path).inputStream()
            }

            else -> File(src).inputStream()
        }
    }
}

/** 从任意 Context 找到宿主 Activity（弹图片预览要用） */
internal fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
