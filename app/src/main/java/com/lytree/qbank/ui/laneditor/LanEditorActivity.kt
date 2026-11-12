package com.lytree.qbank.ui.laneditor

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lytree.qbank.R
import com.lytree.qbank.databinding.ActivityLanEditorBinding
import com.lytree.qbank.ui.FbDialog
import com.lytree.qbank.ui.ScreenAdapt

/**
 * 局域网编辑器的开关页：起服务、看地址、复制、停止。
 *
 * 服务本身在 [LanEditorService]（前台服务），这个页面只是遥控器——
 * 退出页面服务照跑，网页还能继续加题；点「停止服务」或通知栏的「停止」才真的关掉。
 *
 * 另一个职责是**删除的最后一道闸**：电脑上点删除只会发一条请求过来，
 * 这里弹确认框，人点了「确认删除」才真正动库（见 [LanEditorConfirm]）。
 */
class LanEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLanEditorBinding

    /** 同一个请求只弹一次——LiveData 会因为别的请求进来而重发列表 */
    private val shownConfirms = mutableSetOf<String>()
    private val ticker = object : Runnable {
        override fun run() {
            render()
            binding.root.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenAdapt.applyOrientation(this)
        binding = ActivityLanEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.titleBar.usePageTitleStyle()
        binding.titleBar.setTitle(getString(R.string.page_lan_editor))
        binding.titleBar.showBack(true)
        binding.titleBar.onBack = { finish() }

        binding.etPort.setText(LanEditorService.savedPort(this).toString())
        binding.btnToggle.setOnClickListener { toggle() }
        binding.btnCopy.setOnClickListener { copyUrl() }
        binding.btnCopyMcp.setOnClickListener { copyMcp() }

        LanEditorStatus.running.observe(this) { render() }
        LanEditorStatus.url.observe(this) { render() }
        LanEditorConfirm.pending.observe(this) { list -> showPendingConfirm(list) }

        askNotificationPermission()
        render()
    }

    override fun onResume() {
        super.onResume()
        LanEditorConfirm.activityVisible = true
        binding.root.post(ticker)
        // 从通知点进来时 LiveData 可能已经发过一轮，这里兜一次
        showPendingConfirm(LanEditorConfirm.pending.value.orEmpty())
    }

    override fun onPause() {
        LanEditorConfirm.activityVisible = false
        binding.root.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        LanEditorConfirm.activityVisible = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 删除确认

    /**
     * 电脑发来的删除请求：在这里问一句。
     *
     * 不引 Material AlertDialog——全站删除确认都用 [FbDialog]，样式才是一致的；
     * `cancelable = false` 是故意的：删除这种事不能点外面糊里糊涂关掉。
     */
    private fun showPendingConfirm(list: List<LanEditorConfirm.Request>) {
        if (isFinishing || isDestroyed) return
        val req = list.firstOrNull {
            it.status == LanEditorConfirm.STATUS_PENDING && it.id !in shownConfirms
        } ?: return
        shownConfirms += req.id
        FbDialog.confirm(
            this,
            message = "电脑上的题库编辑器请求删除「${req.title}」。\n\n${req.detail}",
            okText = "确认删除",
            cancelText = "不删",
            cancelable = false,
            onOk = {
                LanEditorConfirm.approve(applicationContext, req.id)
                Toast.makeText(this, "已确认，正在删除…", Toast.LENGTH_SHORT).show()
            },
            onCancel = {
                LanEditorConfirm.reject(applicationContext, req.id)
                Toast.makeText(this, "已拒绝，题库没有动", Toast.LENGTH_SHORT).show()
            },
        )
    }

    // ---------------------------------------------------------------- 操作

    private fun toggle() {
        if (LanEditorService.isRunning()) {
            LanEditorService.stop(this)
            binding.root.postDelayed({ render() }, 300)
            Toast.makeText(this, "已停止服务", Toast.LENGTH_SHORT).show()
            return
        }
        val port = binding.etPort.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(this, "端口要在 1024–65535 之间", Toast.LENGTH_SHORT).show()
            return
        }
        LanEditorService.savePort(this, port)
        LanEditorService.start(this, port)
        Toast.makeText(this, "正在启动…", Toast.LENGTH_SHORT).show()
        binding.root.postDelayed({ render() }, 600)
    }

    private fun copyUrl() {
        val url = LanEditorStatus.url.value.orEmpty().ifEmpty { LanEditorService.pageUrl() }
        if (url.isEmpty()) {
            Toast.makeText(this, "先启动服务", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("局域网编辑器", url))
        Toast.makeText(this, "地址已复制，粘到电脑浏览器打开", Toast.LENGTH_SHORT).show()
    }

    /**
     * 复制 AI 客户端（MCP）的配置。
     *
     * 口令每次启动都变，手拼最容易漏 —— 这里直接把当前地址拼好的整段 JSON 放进剪贴板，
     * 用户只要把 args 里的路径换成自己电脑上的位置。
     */
    private fun copyMcp() {
        if (!LanEditorService.isRunning()) {
            Toast.makeText(this, "先启动服务，配置里才会带上当前口令", Toast.LENGTH_SHORT).show()
            return
        }
        val json = LanEditorMcpConfig.json(LanEditorService.pageUrl())
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MCP 配置", json))
        Toast.makeText(this, "配置已复制，粘到客户端的 mcpServers 里", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        val running = LanEditorService.isRunning()
        val url = if (running) LanEditorService.pageUrl() else ""
        val last = if (running) LanEditorService.lastRequest() else ""

        binding.btnToggle.setText(if (running) "停止服务" else "启动服务")
        binding.etPort.isEnabled = !running
        binding.btnCopy.isEnabled = running || url.isNotEmpty()
        binding.tvUrl.text = url.ifEmpty { "—" }

        // AI 接入卡片：未启动时给一份带占位地址的样例，启动后换成真实地址 + 当前口令
        val mcp = LanEditorMcpConfig.json(url)
        if (binding.tvMcp.text.toString() != mcp) binding.tvMcp.text = mcp
        binding.btnCopyMcp.isEnabled = running

        if (running) {
            binding.tvBadge.setText("运行中")
            binding.tvBadge.setTextColor(ContextCompat.getColor(this, R.color.fb_primary))
            binding.tvState.text = if (last.isEmpty()) {
                "服务已就绪，等电脑打开网页…"
            } else {
                "最近一次请求：$last"
            }
            binding.tvHint.text = "手机锁屏不影响服务；离开这个页面服务仍在跑。要关掉，点下面的「停止服务」或通知栏的「停止」。"
        } else {
            binding.tvBadge.setText("已停止")
            binding.tvBadge.setTextColor(ContextCompat.getColor(this, R.color.fb_text_tertiary))
            binding.tvState.text = LanEditorStatus.message.value?.takeIf { it.isNotBlank() && it != "已停止" }
                ?: "未启动"
            binding.tvHint.text = "启动后手机会在当前 Wi-Fi 上开一个网页服务，只有带口令的链接能进；用完记得停止。"
        }
    }

    // ---------------------------------------------------------------- 通知权限

    /** Android 13+ 没有通知权限，前台服务的常驻通知就不显示——地址也就看不到了，所以先问一声 */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71)
    }
}
