/**
 * 「单独查看页面」的地址约定。
 *
 * 右栏（卷面预览 / 答案解析 / 记录）挤在 280–560px 的窄栏里，整卷过一遍要一直滚，
 * 排版也伸不开。所以给这三页各配一个整页版本：地址上加 `?view=`，同一个 bundle
 * 换个壳渲染（`main.tsx` 按 query 决定挂 App 还是 FullView），不用改手机端的路由。
 *
 *   ?view=preview|answer|records   看哪一页（必填，缺了就当普通编辑器）
 *   &setId=<组编号>                看哪一组（缺省 = 列表里第一组未归档的）
 *   &sheet=<卷 id>                 看哪份卷（缺省 = 第一份）
 *   &recordId=<记录 id>            记录页直接落到这条记录的详情
 *
 * 用 query 而不是 hash：手机端把 `/` 映射到 index.html，query 不参与路径匹配，
 * 刷新、分享、从编辑器 window.open 都直接可用。
 */

export type FullPane = 'preview' | 'answer' | 'records'

export const FULL_PANES: FullPane[] = ['preview', 'answer', 'records']

export const FULL_PANE_LABEL: Record<FullPane, string> = {
  preview: '卷面预览',
  answer: '答案解析',
  records: '练习记录',
}

/** 副标题里那句「这页是只读的」 */
export const FULL_PANE_HINT: Record<FullPane, string> = {
  preview: '与 App 答题页同版式 · 只读',
  answer: '题目 / 答案 / 解析 / 知识点 · 只读',
  records: '全库作答记录 · 只读',
}

export const isFullPane = (v: unknown): v is FullPane =>
  typeof v === 'string' && (FULL_PANES as readonly string[]).includes(v)

export interface FullQuery {
  pane: FullPane
  /** 0 = 没指定：打开列表里第一组未归档的 */
  setId: number
  /** 卷（工作表）id，0 = 这一组的第一份卷 */
  sheetId: number
  /** 记录页直接落到这条记录的详情，0 = 只到列表 */
  recordId: number
}

const positiveInt = (v: string | null): number => {
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? Math.trunc(n) : 0
}

/** 解析地址；不是单独查看页就返回 null（当普通编辑器处理） */
export function readFullQuery(search?: string): FullQuery | null {
  const raw = search ?? (typeof window === 'undefined' ? '' : window.location.search)
  const q = new URLSearchParams(raw.startsWith('?') ? raw.slice(1) : raw)
  const view = q.get('view')
  if (!isFullPane(view)) return null
  return {
    pane: view,
    setId: positiveInt(q.get('setId')),
    sheetId: positiveInt(q.get('sheet')),
    recordId: positiveInt(q.get('recordId')),
  }
}

/** 编辑器本身（不带 view）的地址 */
export function editorUrl(): string {
  const path = typeof window === 'undefined' ? '/' : window.location.pathname.replace(/index\.html$/, '')
  return path || '/'
}

/** 拼单独查看页的地址（相对当前站点根，手机端是 `/`，静态托管带 index.html 也能用） */
export function fullUrl(
  pane: FullPane,
  opts: { setId?: number; sheetId?: number; recordId?: number } = {},
): string {
  const q = new URLSearchParams({ view: pane })
  if (opts.setId && opts.setId > 0) q.set('setId', String(opts.setId))
  if (opts.sheetId && opts.sheetId > 0) q.set('sheet', String(opts.sheetId))
  if (opts.recordId && opts.recordId > 0) q.set('recordId', String(opts.recordId))
  return `${editorUrl()}?${q.toString()}`
}

/**
 * 新标签页打开。`noopener` 是必须的：那边的页面能读本题库，
 * 别让新窗口通过 window.opener 反手拿住编辑器。
 */
export function openFullPage(
  pane: FullPane,
  opts: { setId?: number; sheetId?: number; recordId?: number } = {},
) {
  window.open(fullUrl(pane, opts), '_blank', 'noopener')
}
