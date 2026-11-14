/**
 * 明暗两套皮：白天是冷灰纸面的校样台，夜里是同一张桌子关灯后的样子。
 *
 * 首屏由 index.html 里的内联脚本先把 `data-theme` 落到 <html> 上（读到 localStorage，
 * 没有就按系统偏好），这里只负责之后的状态同步和切换，避免「先白一下再变黑」。
 */
import { reactive } from 'vue'

export type ThemeMode = 'light' | 'dark'

const KEY = 'fenbi.lan-editor.theme'

function readStored(): ThemeMode | null {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'dark' || v === 'light' ? v : null
  } catch {
    // 隐私模式读不到 localStorage：当作用户没选过
    return null
  }
}

function systemMode(): ThemeMode {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export const theme = reactive({
  /** 当前生效的主题；初值取 <html data-theme>，和首屏脚本保持一致 */
  mode: (document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light') as ThemeMode,
})

function apply(mode: ThemeMode, persist: boolean) {
  theme.mode = mode
  document.documentElement.dataset.theme = mode
  if (!persist) return
  try {
    localStorage.setItem(KEY, mode)
  } catch {
    // 存不下就只在这次会话里生效
  }
}

/** 手动切换：选了就记住，之后不再跟系统走 */
export function toggleTheme() {
  apply(theme.mode === 'dark' ? 'light' : 'dark', true)
}

/** 启动时对齐一次；用户没手动选过时，系统切明暗页面跟着换 */
export function initTheme() {
  const mode = readStored() ?? (document.documentElement.dataset.theme === 'dark' ? 'dark' : systemMode())
  apply(mode, false)
  window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
    if (readStored()) return
    apply(e.matches ? 'dark' : 'light', false)
  })
}
