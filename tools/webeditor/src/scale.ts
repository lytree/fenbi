/**
 * 界面缩放：顶栏「A− / 百分比 / A+」一组钮，把整页字号连着间距、控件一起放大缩小。
 *
 * 全站字号是写死的 px（令牌口径对齐 antd），rem 那套撼不动它们；
 * 所以这里用 CSS zoom 直接缩 <html>，效果等同于浏览器的页面缩放，
 * 但档位记在 localStorage 里、进页面自动恢复，手机浏览器里也有按钮可点。
 */
import { reactive } from 'vue'

const KEY = 'fenbi.lan-editor.ui-scale'

/** 缩放档位：默认 100%，往两边各两档；到端点再点不动 */
export const SCALE_LEVELS = [0.85, 1, 1.15, 1.3, 1.5]

/** 当前生效的缩放倍率（1 = 原始大小） */
export const uiScale = reactive({ level: 1 })

function readStored(): number | null {
  try {
    const v = localStorage.getItem(KEY)
    if (v === null) return null
    const n = Number(v)
    // 合法区间内的任意值都收（老版本存过不在档位表里的数也照用）
    return Number.isFinite(n) && n >= 0.5 && n <= 2 ? n : null
  } catch {
    // 隐私模式读不到 localStorage：当作用户没调过
    return null
  }
}

function apply(level: number, persist: boolean) {
  uiScale.level = level
  // 100% 时把属性摘掉，别留一个 zoom:1 干扰浏览器自己的缩放
  document.documentElement.style.setProperty('zoom', level === 1 ? '' : String(level))
  if (!persist) return
  try {
    localStorage.setItem(KEY, String(level))
  } catch {
    // 存不下就只在这次会话里生效
  }
}

/** 启动时对齐一次：index.html 的首屏脚本已经先设过了，这里兜底把状态对上 */
export function initScale() {
  const saved = readStored()
  if (saved !== null) apply(saved, false)
}

/** 放大一档；已在最大档就不动 */
export function zoomIn() {
  const next = SCALE_LEVELS.find((l) => l > uiScale.level + 1e-6)
  if (next !== undefined) apply(next, true)
}

/** 缩小一档；已在最小档就不动 */
export function zoomOut() {
  const next = [...SCALE_LEVELS].reverse().find((l) => l < uiScale.level - 1e-6)
  if (next !== undefined) apply(next, true)
}

/** 回到 100% */
export function resetScale() {
  apply(1, true)
}

/** 当前档位的百分数文案（顶栏按钮和提示用） */
export function scalePercent(): string {
  return `${Math.round(uiScale.level * 100)}%`
}
