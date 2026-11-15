import { defineComponent } from 'vue'
import { api } from '../api'
import {
  issueCounts, markDirty, markReciteDirty, publish, recite, resetRecite, saveRecite,
  showErrors, state, switchMode, toast, ui,
} from '../store'
import { theme, toggleTheme } from '../theme'
import { copyText, formatTime } from '../util'

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

/** 顶栏：模式切换（题库 / 背诵）+ 标题 + 动作 */
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

    /**
     * 复制 MCP 配置（含写入口令）。
     *
     * 口令是固定的（设了密码就是密码，没设就是手机首次生成后保存的那串），
     * 复制一次可以长期用；剪贴板被拦时把配置弹出来让人手动抄。
     * key 不回显在按钮旁边：口令只落在剪贴板和一次性弹层里，不常驻页面。
     */
    const copyMcp = async () => {
      try {
        const r = await api.mcpInfo()
        if (await copyText(r.config)) {
          toast('MCP 配置已复制（含写入口令），粘到客户端的 mcpServers 里', 'ok')
          return
        }
        await showErrors(
          '复制失败，请手动复制',
          `${r.config}\n\n（写入口令 QBANK_KEY 就在上面 JSON 里。）`,
        )
      } catch (e) {
        const msg = e instanceof Error ? e.message : '拿不到 MCP 配置'
        await showErrors('拿不到 MCP 配置', msg)
      }
    }

    return () => {
      const reciteMode = state.mode === 'recite'
      const isWords = recite.subject === 'words'
      const catalog = recite.catalog

      return (
        <header class="topbar">
          <div class="brand">
            <span class="logo">题</span>
            <div class="brand-text">
              <b>题库编辑器</b>
              <span class="ws">手机里的题库 · 局域网直连</span>
            </div>
          </div>

          <div class={['mode-seg', 'seg']} title="切换：题库编辑 / 背诵词库编辑">
            <button
              class={['seg-btn', !reciteMode && 'on']}
              onClick={() => void switchMode('paper')}
            >
              题库
            </button>
            <button
              class={['seg-btn', reciteMode && 'on']}
              onClick={() => void switchMode('recite')}
            >
              背诵
            </button>
          </div>

          {reciteMode
            ? (
              <div class="title-wrap">
                {isWords
                  ? (
                    <input class="paper-name" value="词条词库（App：背诵 → 词库）" readonly />
                  )
                  : (
                    <input
                      class="paper-name"
                      value={catalog?.title ?? ''}
                      readonly={!catalog}
                      spellcheck={false}
                      placeholder="这块背诵内容的标题（App 弹层顶部显示）"
                      onInput={(e) => {
                        if (!catalog) return
                        catalog.title = (e.target as HTMLInputElement).value
                        markReciteDirty()
                      }}
                    />
                  )}
                {recite.dirty ? <span class="dirty">未保存</span> : null}
                {catalog?.hasEdits && !recite.dirty ? <span class="draft-saved">手机上有覆盖版</span> : null}
              </div>
            )
            : (
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
            )}

          <div class="top-actions">
            <button
              class="ghost sm"
              title="复制 AI（MCP）配置 JSON，含固定不变的写入口令，配一次可长期使用"
              onClick={() => void copyMcp()}
            >
              复制 MCP
            </button>
            <button
              class="theme-btn"
              title={theme.mode === 'dark' ? '切到白天' : '切到夜间'}
              aria-label={theme.mode === 'dark' ? '切到白天' : '切到夜间'}
              onClick={toggleTheme}
            >
              <ThemeIcon />
            </button>
            {reciteMode
              ? (
                <>
                  {!isWords && catalog
                    ? (
                      <>
                        <button class="ghost" disabled={ui.busy || !!ui.waitingId} onClick={() => void resetRecite()}>
                          重置为内置
                        </button>
                        <button
                          class="primary"
                          disabled={ui.busy}
                          title="整份覆盖手机上的这块内容，点完还要在手机上点「确认保存」"
                          onClick={() => void saveRecite()}
                        >
                          {ui.busy ? '保存中…' : '保存到手机'}
                          <kbd>Ctrl S</kbd>
                        </button>
                      </>
                    )
                    : null}
                </>
              )
              : (
                <>
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
                </>
              )}
          </div>
        </header>
      )
    }
  },
})
