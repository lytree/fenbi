import { defineComponent } from 'vue'
import { issueCounts, markDirty, publish, state, toast, ui } from '../store'
import { formatTime } from '../util'

/** 顶栏：组名（发布后就是 App 首页那个标题）+ 校验 + 发布 */
export default defineComponent({
  name: 'TopBar',
  setup() {
    const check = () => {
      if (!state.paper) return
      const { errors, warns } = issueCounts()
      state.pane = 'issue'
      toast(
        errors ? `本地检查发现 ${errors} 个错误，发布会被拦下` : `本地检查通过${warns ? ` · ${warns} 条提醒` : ''}`,
        errors ? 'err' : 'ok',
      )
    }

    const runPublish = async () => {
      await publish()
    }

    return () => (
      <header class="topbar">
        <div class="brand">
          <span class="logo">题</span>
          <div class="brand-text">
            <b>题库编辑器</b>
            <span class="ws">手机里的题库 · 局域网直连</span>
          </div>
        </div>

        <div class="title-wrap">
          <input
            class="paper-name"
            value={state.paper?.name ?? ''}
            readonly={!state.paper}
            spellcheck={false}
            placeholder="这一组叫什么（会成为 App 首页的标题）"
            onInput={(e) => {
              if (!state.paper) return
              state.paper.name = (e.target as HTMLInputElement).value
              markDirty()
            }}
          />
          {state.dirty ? <span class="dirty">未发布</span> : null}
          {state.dirty && ui.draftSavedAt
            ? (
              <span class="draft-saved" title="改动已存到本机浏览器，关掉页面也不会丢；草稿不设过期时间">
                已自动保存 {formatTime(ui.draftSavedAt)}
              </span>
            )
            : null}
        </div>

        <div class="top-actions">
          <button class="ghost" disabled={!state.paper} onClick={check}>校验</button>
          <button class="primary" disabled={!state.paper || ui.busy} onClick={runPublish}>
            {ui.busy ? '发布中…' : '发布到 App'}
            <kbd>Ctrl S</kbd>
          </button>
        </div>
      </header>
    )
  },
})
