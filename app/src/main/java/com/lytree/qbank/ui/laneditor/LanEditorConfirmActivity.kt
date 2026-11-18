package com.lytree.qbank.ui.laneditor

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.lytree.qbank.ui.FbDialog

/**
 * 确认弹层：**任何界面**上都能盖出来的「电脑请求写库」确认框。
 *
 * 以前确认框只住在 [LanEditorActivity]——人在答题 / 看解析 / 备份页时，
 * 电脑点发布只能去通知栏点一下、再跳回开关页处理，打断得厉害。
 * 现在请求一到（只要 App 在前台），这里就以透明弹层的形式直接盖在当前画面上，
 * 点完「确认 / 拒绝」自动消失，底下的界面原样接着用。
 *
 * 本身不带任何内容：透明主题 + 一枚 [FbDialog]，pending 清空就自动 finish。
 * 通知栏那一路也指向这里，锁屏外从别的 App 点进来同样是这层框。
 *
 * `taskAffinity=""` + `excludeFromRecents`：它是弹层不是页面，不该在多任务里
 * 占一格，也不该把主任务从别的 App 手里抢回来。
 */
class LanEditorConfirmActivity : AppCompatActivity() {

    /** 同一个请求只弹一次（与 LanEditorActivity 各自维护，请求状态变掉就不会再弹） */
    private val shown = mutableSetOf<String>()
    private var dialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不 setContentView：这页只是一层「托着确认框的玻璃」
        LanEditorConfirm.pending.observe(this) { list -> render(list) }
    }

    override fun onResume() {
        super.onResume()
        LanEditorConfirm.gateVisible = true
        // 从通知点进来时 LiveData 可能已经发过一轮，兜一次
        render(LanEditorConfirm.pending.value.orEmpty())
    }

    override fun onPause() {
        LanEditorConfirm.gateVisible = false
        super.onPause()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun render(list: List<LanEditorConfirm.Request>) {
        if (isFinishing || isDestroyed) return
        val waiting = list.filter { it.status == LanEditorConfirm.STATUS_PENDING }
        if (waiting.isEmpty()) {
            finish()
            return
        }
        // 上一条还没点：让 LiveData 在它落定后的那次刷新里再弹下一条，别叠罗汉
        if (dialog?.isShowing == true) return
        val req = waiting.firstOrNull { it.id !in shown } ?: return
        shown += req.id

        val action = LanEditorConfirm.actionName(req.kind)
        val okText = if (req.kind == LanEditorConfirm.KIND_MCP_KEY) "确认提供" else "确认$action"
        val message = if (req.kind == LanEditorConfirm.KIND_MCP_KEY) {
            "电脑上的题库编辑器请求获取 MCP 写入口令。\n\n${req.detail}"
        } else {
            "电脑上的题库编辑器请求${action}「${req.title}」。\n\n${req.detail}"
        }
        dialog = FbDialog.confirm(
            this,
            message = message,
            okText = okText,
            cancelText = LanEditorConfirm.cancelName(req.kind),
            cancelable = false,
            onOk = {
                LanEditorConfirm.approve(applicationContext, req.id)
                Toast.makeText(this, "已确认，正在$action…", Toast.LENGTH_SHORT).show()
            },
            onCancel = {
                LanEditorConfirm.reject(applicationContext, req.id)
                Toast.makeText(
                    this,
                    if (req.kind == LanEditorConfirm.KIND_MCP_KEY) "已拒绝，没有提供口令" else "已拒绝，题库没有动",
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }

    companion object {
        /** 通知 / 服务拉起弹层用；已在最上面就复用，不重开一层 */
        fun intent(context: android.content.Context): Intent =
            Intent(context, LanEditorConfirmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
