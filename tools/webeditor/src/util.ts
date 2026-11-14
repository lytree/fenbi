import type { Question } from './types'

export const esc = (s: unknown): string =>
  String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')

export function debounce<T extends (...args: any[]) => void>(fn: T, ms: number): T {
  let timer: number | undefined
  return ((...args: any[]) => {
    window.clearTimeout(timer)
    timer = window.setTimeout(() => fn(...args), ms)
  }) as T
}

/**
 * 让 textarea 长到内容那么高（最高 520px，再高就内部滚动）。
 *
 * 必须等元素真的进了文档再量：Vue 的函数 ref 在插入 DOM 之前就会回调一次，
 * 那时 `scrollHeight` 是 0，量出来只有 2px —— 材料正文会被裁成一条缝。
 * 所以没连上文档就挪到下一帧再量。
 */
export function grow(el?: HTMLTextAreaElement | null) {
  if (!el) return
  const apply = () => {
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight + 2, 520)}px`
  }
  if (!el.isConnected) {
    requestAnimationFrame(apply)
    return
  }
  apply()
}

export function growAll(root: HTMLElement | null | undefined) {
  if (!root) return
  root.querySelectorAll('textarea').forEach((el) => grow(el as HTMLTextAreaElement))
}

/** 在光标处插入文本（图片 markdown 用），插完把光标放到插入内容之后 */
export function insertAtCursor(el: HTMLTextAreaElement, text: string) {
  const start = el.selectionStart ?? el.value.length
  const end = el.selectionEnd ?? start
  el.value = el.value.slice(0, start) + text + el.value.slice(end)
  el.selectionStart = el.selectionEnd = start + text.length
  el.dispatchEvent(new Event('input', { bubbles: true }))
  grow(el)
}

export const clampText = (s: string, n: number) => (s.length > n ? `${s.slice(0, n)}…` : s)

let uidSeq = 0
/** 题目在前端的临时身份，用来记住「展开的是哪道题」，发布时会被剥掉 */
export const nextUid = () => ++uidSeq

export const sleep = (ms: number) => new Promise<void>((r) => window.setTimeout(r, ms))

/** 从题干 / 材料 / 选项里取第一张图，给分组板的缩略图用 */
export function firstImageSrc(q: Question): string {
  const text = `${q.material}\n${q.stem}\n${q.options.map((o) => o.text).join('\n')}`
  const md = text.match(/!\[[^\]]*\]\(\s*([^)\s]+)/)
  if (md) return md[1]
  const bare = text.match(/(?:^|\s)((?:[A-Za-z]:[\\/]|\/|\.{1,2}\/|https?:\/\/|asset:\/\/)[^\s)]+\.(?:png|jpe?g|webp|gif|bmp))/i)
  return bare ? bare[1] : ''
}

/** 版本列表用的时间：今年的省略年份，看着短一点 */
export function formatTime(ts: number): string {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  const year = d.getFullYear() === new Date().getFullYear() ? '' : `${d.getFullYear()}/`
  return `${year}${d.getMonth() + 1}/${d.getDate()} ${p(d.getHours())}:${p(d.getMinutes())}`
}
