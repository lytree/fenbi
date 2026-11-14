import { defineComponent } from 'vue'
import { issueCounts, markDirty, publish, state, toast, ui } from '../store'
import { theme, toggleTheme } from '../theme'
import { formatTime } from '../util'

/** 明暗切换：白天显示月亮（切到夜间），夜里显示太阳 */
const ThemeIcon = () => {
  const dark = theme.mode === 'dark'
  return (
    <svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" aria-hidden="true">
      {dark
        ? (
          <>
            <circle cx="8" cy="8" r="3.1" />
            <path d="M8 1.4v1.3M8 13.3v1.3M1.4 8h1.3M13.3 8h1.3M3.3 3.3l.9.9M11.8 11.8l.9.9M12.7 3.3l-.9.9M4.2 11.8l-.9.9" />
          </>
        )
        : <path d="M13.4 9.8A5.8 5.8 0 0 1 6.2 2.6a5.9 5.9 0 1 0 7.2 7.2Z" />}
    </svg>
  )
}

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
          <button
            class="theme-btn"
            title={theme.mode === 'dark' ? '切到白天' : '切到夜间'}
            aria-label={theme.mode === 'dark' ? '切到白天' : '切到夜间'}
            onClick={toggleTheme}
          >
            <ThemeIcon />
          </button>
          <button class="ghost" disabled={!state.paper} onClick={check}>校验</button>
          <button
            class="primary"
            disabled={!state.paper || ui.busy}
            title="点完还要在手机上点「确认发布」才会写进题库"
            onClick={runPublish}
          >
            {ui.busy ? '发布中…' : '发布到 App'}
            <kbd>Ctrl S</kbd>
          </button>
        </div>
      </header>
    )
  },
})
