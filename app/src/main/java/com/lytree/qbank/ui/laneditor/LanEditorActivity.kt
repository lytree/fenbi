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
 * 另一个职责是**写库的最后一道闸**：电脑上点发布 / 回滚 / 删除只会发一条请求过来，
 * 这里弹确认框，人点了「确认」才真正动库（见 [LanEditorConfirm]）。
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
        binding.btnSavePassword.setOnClickListener { savePassword() }
        binding.btnClearPassword.setOnClickListener { clearPassword() }

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

    // ---------------------------------------------------------------- 写库确认

    /**
     * 电脑发来的发布 / 回滚 / 删除请求：在这里问一句。
     *
     * 不引 Material AlertDialog——全站确认都用 [FbDialog]，样式才是一致的；
     * `cancelable = false` 是故意的：会改题库的事不能点外面糊里糊涂关掉。
     */
    private fun showPendingConfirm(list: List<LanEditorConfirm.Request>) {
        if (isFinishing || isDestroyed) return
        val req = list.firstOrNull {
            it.status == LanEditorConfirm.STATUS_PENDING && it.id !in shownConfirms
        } ?: return
        shownConfirms += req.id
        val action = when (req.kind) {
            LanEditorConfirm.KIND_PUBLISH -> "发布"
            LanEditorConfirm.KIND_RESTORE -> "回滚"
            else -> "删除"
        }
        val okText = "确认$action"
        val cancelText = when (req.kind) {
            LanEditorConfirm.KIND_PUBLISH -> "不发布"
            LanEditorConfirm.KIND_RESTORE -> "不回滚"
            else -> "不删"
        }
        FbDialog.confirm(
            this,
            message = "电脑上的题库编辑器请求${action}「${req.title}」。\n\n${req.detail}",
            okText = okText,
            cancelText = cancelText,
            cancelable = false,
            onOk = {
                LanEditorConfirm.approve(applicationContext, req.id)
                Toast.makeText(this, "已确认，正在$action…", Toast.LENGTH_SHORT).show()
            },
            onCancel = {
                LanEditorConfirm.reject(applicationContext, req.id)
                Toast.makeText(this, "已拒绝，题库没有动", Toast.LENGTH_SHORT).show()
            },
        )
    }

    // ---------------------------------------------------------------- 访问密码

    /**
     * 设 / 改密码：服务在跑就立刻生效（所有浏览器会话失效，要重新登录），
     * 没在跑就只落盘，下次启动服务时按新密码拦人。
     */
    private fun savePassword() {
        val pwd = binding.etPassword.text.toString().trim()
        if (pwd.length < LanEditorPrefs.MIN_PASSWORD_LENGTH) {
            Toast.makeText(this, "密码至少 ${LanEditorPrefs.MIN_PASSWORD_LENGTH} 位", Toast.LENGTH_SHORT).show()
            return
        }
        LanEditorService.updatePassword(this, pwd)
        binding.etPassword.setText("")
        render()
        Toast.makeText(
            this,
            if (LanEditorService.isRunning()) "已生效，网页需要重新登录" else "已保存，下次启动服务时生效",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * 清除密码 = 回到「谁都能打开」。这一步等于把刚建起来的门再拆掉，
     * 所以要有一次确认，别在手滑的位置直接生效。
     */
    private fun clearPassword() {
        if (LanEditorPrefs.password(this) == null) {
            Toast.makeText(this, "现在没有设密码", Toast.LENGTH_SHORT).show()
            return
        }
        FbDialog.confirm(
            this,
            message = "清除后，同一 Wi-Fi 下任何人打开地址就能看到题库（含作答记录）。确定吗？",
            okText = "清除密码",
            cancelText = "不清",
            cancelable = true,
            onOk = {
                LanEditorService.updatePassword(this, null)
                render()
                Toast.makeText(this, "已清除，网页不用再登录", Toast.LENGTH_SHORT).show()
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
     * 写入口令每次启动都变，手拼最容易漏 —— 这里直接把当前地址 + 口令拼好的整段 JSON
     * 放进剪贴板，用户只要把 args 里的路径换成自己电脑上的位置。
     */
    private fun copyMcp() {
        if (!LanEditorService.isRunning()) {
            Toast.makeText(this, "先启动服务，配置里才会带上当前口令", Toast.LENGTH_SHORT).show()
            return
        }
        val json = LanEditorMcpConfig.json(LanEditorService.pageUrl(), LanEditorService.accessKey())
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

        val pwd = LanEditorPrefs.password(this)
        binding.tvPasswordState.text = if (pwd != null) {
            "已设置 · 网页需要登录，AI 也用同一串"
        } else {
            "未设置——同一 Wi-Fi 打开地址就能用"
        }
        binding.btnClearPassword.isEnabled = pwd != null

        // AI 接入卡片：未启动时给一份带占位地址的样例，启动后换成真实地址 + 当前口令
        // 设了密码就用密码（重启不变），没设才用每次启动生成的随机码
        val mcp = LanEditorMcpConfig.json(url, if (running) LanEditorService.aiKey(this) else "")
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
            binding.tvHint.text = "手机锁屏不影响服务；离开这个页面服务仍在跑。网页上要先登录（如果设了密码），发布、回滚、删除还会再在这里弹一次确认框。"
        } else {
            binding.tvBadge.setText("已停止")
            binding.tvBadge.setTextColor(ContextCompat.getColor(this, R.color.fb_text_tertiary))
            binding.tvState.text = LanEditorStatus.message.value?.takeIf { it.isNotBlank() && it != "已停止" }
                ?: "未启动"
            binding.tvHint.text = "启动后手机会在当前 Wi-Fi 上开一个网页服务，同一 Wi-Fi 的电脑用浏览器打开就能加题；没设密码谁都能进，设了要先登录。发布和删除仍要在这台手机上确认。用完记得停止。"
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
