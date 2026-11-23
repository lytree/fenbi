import type { Ref } from 'vue'
import { esc } from './util'

/**
 * 资料题的「左材料 + 右题号页签 + 小题连着排」——照粉笔（作答页 / 解析页 / 记录页同一个样）做的
 * 一块分屏，卷面预览、答案解析、练习记录三页共用同一份结构、同一套交互。
 *
 * 结构（`.pp-split`）：
 * ```
 * ┌───────────────┬─┬──────────────────────────┐
 * │ 材料           │ │ 56题 57题 58题 59题 60题  │  ← 页签：点它滚到那道小题
 * │ 正文（自己滚）  │ │ 59. 单选题 …（连着排）     │  ← 右栏：一组小题一条流，不是一题一页
 * │               │ │ 60. 单选题 …              │
 * └───────────────┴─┴──────────────────────────┘
 * ```
 *
 * 交互统一成三条：
 * - **点页签**：右栏滚到那道小题（当前题下面一条主色短杠跟着走）；
 * - **右栏滚动**：哪道小题贴在栏顶，哪枚页签就亮（滚到 60 题，页签自己换过去）；
 * - **拖中间的把手**：改左栏宽度（45%–72%），重绘后还按上次的宽度摆。
 *
 * 认「哪道小题」只靠卡片上的 `data-uid` 和页签上的 `data-tab`：三页的 uid 各不相同
 * （卷面/解析用题目的 `_uid`，记录页用题号），这里一律按字符串比。
 */

/** 左栏宽度的上下限：再窄材料读不了，再宽右栏放不下选项 */
const MAT_MIN = 45
const MAT_MAX = 72
/** 默认左栏占比（与粉笔 1180 里 704 : 468 差不多） */
const MAT_DEFAULT = 60

/** 页签与它对应的小题卡 */
export interface SplitTab {
  /** 与小题卡 `data-uid` 一致 */
  id: string | number
  /** 页签上的字：`59题` */
  label: string
}

/** 一组分屏的全部积木（材料与小题都已经渲染成 HTML 了） */
export interface SplitGroupParts {
  /** 组的稳定标识，写进 `data-g`：重绘后靠它找回滚动位置与左栏宽度 */
  key: string | number
  /** 左栏宽度百分比；不给就用默认 60 */
  width?: number
  tabs: SplitTab[]
  /** 已经渲染好的材料 HTML */
  materialHtml: string
  /** 已经渲染好的小题 HTML（一组 `.pv-q`，页签按 `data-uid` 找它们） */
  bodyHtml: string
  /** 进来时哪枚页签是亮的；不给就是第一题（滚动时会自己跟） */
  activeId?: string | number
}

/** 页面顶上那块「材料」小签的文字 */
const MAT_TAG = '材料'

/** 一排题号页签：`56题 57题 …`，`data-tab` 写小题卡的 uid */
export function splitTabsHtml(tabs: SplitTab[], activeId?: string | number): string {
  const active = activeId === undefined || activeId === null ? '' : String(activeId)
  return tabs
    .map((t) => {
      const on = active && String(t.id) === active ? ' active' : ''
      return `<button class="pv-tab${on}" data-tab="${esc(t.id)}">${esc(t.label)}</button>`
    })
    .join('')
}

/** 一整块分屏：左材料白卡 + 拖柄 + 右（页签 + 小题流） */
export function splitGroupHtml(p: SplitGroupParts): string {
  const w = Math.min(MAT_MAX, Math.max(MAT_MIN, p.width ?? MAT_DEFAULT))
  return (
    `<div class="pp-split" data-g="${esc(p.key)}" style="--mat-w:${w}%">` +
    '<div class="pp-mat">' +
    `<span class="pv-mat-tag">${MAT_TAG}</span>` +
    `<div class="pp-mat-body">${p.materialHtml}</div>` +
    '</div>' +
    '<div class="pp-grip" title="拖动调整材料宽度"></div>' +
    '<div class="pp-qs">' +
    `<div class="pv-tabs">${splitTabsHtml(p.tabs, p.activeId)}</div>` +
    `<div class="pp-qs-body">${p.bodyHtml}</div>` +
    '</div>' +
    '</div>'
  )
}

/**
 * 分屏的交互（挂在页面根元素上，事件委托，不逐个绑）。
 *
 * 三页各自的事件收口都先问它一句：`onClick` 返回 true 表示「这下面是页签，我处理了」，
 * 页面就别再当成「点了题目」去跳当前题。
 */
export interface SplitControl {
  /** 点击：只认题号页签，其余交回页面 */
  onClick(e: MouseEvent): boolean
  /** 按住两栏之间的把手拖动改宽度 */
  onPointerDown(e: PointerEvent): void
  /** 滚动（捕获阶段）：右栏滚到哪儿，页签跟着亮 */
  onScrollCapture(e: Event): void
  /** 重绘前记下右栏滚动位置 */
  remember(): void
  /** 重绘后把滚动位置、左栏宽度、页签亮态都还回去 */
  restore(): void
  /** 页签亮态按当前滚动位置重算（刚进页面 / 重绘后调一次） */
  syncAll(): void
  /** 拖过的宽度贴回 DOM（重绘会把 style 上的变量抹掉） */
  applyWidths(): void
  /** 把某道小题带到眼前：分屏里的滚两层（整块 → 右栏），独立题滚到顶 */
  scrollToCard(id: string | number, smooth?: boolean): void
}

const scrollBox = (el: HTMLElement, top: number, smooth: boolean) => {
  const y = Math.max(0, top)
  // jsdom（单测）里没有 scrollTo：退回直接改 scrollTop，测试照样能验行为
  if (typeof el.scrollTo === 'function') {
    el.scrollTo({ top: y, behavior: smooth ? 'smooth' : 'auto' })
  } else {
    el.scrollTop = y
  }
}

/** 按 `data-uid` 找小题卡：只比字符串，三页的 uid 口径互不影响 */
const cardOf = (scope: ParentNode, id: string | number): HTMLElement | null => {
  const want = String(id)
  const all = [...scope.querySelectorAll<HTMLElement>('.pv-q')]
  return all.find((c) => c.dataset.uid === want) ?? null
}

/** 右栏里贴顶的那道小题 = 亮着的那枚页签 */
const syncTabs = (body: HTMLElement) => {
  const split = body.closest('.pp-split') as HTMLElement | null
  if (!split) return
  const cards = [...body.querySelectorAll<HTMLElement>('.pv-q')]
  if (!cards.length) return
  // 用 rect 差值算「相对栏顶的位置」：offsetTop 的参照物是最近的定位祖先，在这里并不可靠
  const rect = body.getBoundingClientRect()
  // 还没排版（刚插进 DOM / 单测环境）：谁都不比，页签就停在这一版渲染出来的那枚上
  if (!rect.height) return
  const top = rect.top
  let active = cards[0]
  for (const c of cards) {
    if (c.getBoundingClientRect().top - top <= 24) active = c
  }
  const id = active.dataset.uid
  split.querySelectorAll<HTMLElement>('.pv-tab').forEach((t) => {
    t.classList.toggle('active', t.dataset.tab === id)
  })
}

export function useSplitGroup(host: Ref<HTMLElement | null>): SplitControl {
  /** 拖过的左栏宽度（键 = 组的 data-g）：重绘后还按这个摆，不用回默认 */
  const widths: Record<string, number> = {}
  /** 重绘前记下的两栏滚动位置（键同上）：小题滚到一半、材料翻到中间，都不该白滚 */
  let scrollMemo: Record<string, { qs: number; mat: number }> = {}

  const splits = (): HTMLElement[] =>
    host.value ? [...host.value.querySelectorAll<HTMLElement>('.pp-split')] : []

  const bodyOf = (split: HTMLElement): HTMLElement | null =>
    split.querySelector<HTMLElement>('.pp-qs-body')

  const matOf = (split: HTMLElement): HTMLElement | null =>
    split.querySelector<HTMLElement>('.pp-mat-body')

  const syncAll = () => {
    host.value?.querySelectorAll<HTMLElement>('.pp-qs-body').forEach(syncTabs)
  }

  const applyWidths = () => {
    for (const s of splits()) {
      const w = s.dataset.g === undefined ? undefined : widths[s.dataset.g]
      if (typeof w === 'number') s.style.setProperty('--mat-w', `${w}%`)
    }
  }

  const onClick = (e: MouseEvent): boolean => {
    const tab = (e.target as HTMLElement).closest('.pv-tab[data-tab]') as HTMLElement | null
    if (!tab) return false
    const split = tab.closest('.pp-split') as HTMLElement | null
    const body = split ? bodyOf(split) : null
    const id = tab.dataset.tab
    if (!body || !id) return false
    const hit = cardOf(body, id)
    if (hit) {
      const delta = hit.getBoundingClientRect().top - body.getBoundingClientRect().top
      scrollBox(body, body.scrollTop + delta - 8, true)
    }
    // 点完立刻亮这一枚：滚动事件要等下一帧，先给个手感
    split?.querySelectorAll<HTMLElement>('.pv-tab').forEach((t) => {
      t.classList.toggle('active', t === tab)
    })
    return true
  }

  const onScrollCapture = (e: Event) => {
    const el = e.target as HTMLElement | null
    if (el?.classList?.contains('pp-qs-body')) syncTabs(el)
  }

  const onPointerDown = (e: PointerEvent) => {
    const grip = (e.target as HTMLElement).closest('.pp-grip') as HTMLElement | null
    if (!grip || e.button !== 0) return
    const split = grip.closest('.pp-split') as HTMLElement | null
    if (!split) return
    e.preventDefault()
    const key = split.dataset.g ?? ''
    const rect = split.getBoundingClientRect()
    const move = (ev: PointerEvent) => {
      if (!rect.width) return
      const pct = Math.min(MAT_MAX, Math.max(MAT_MIN, ((ev.clientX - rect.left) / rect.width) * 100))
      widths[key] = Math.round(pct)
      split.style.setProperty('--mat-w', `${widths[key]}%`)
    }
    const up = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', up)
      window.removeEventListener('pointercancel', up)
      document.body.classList.remove('split-dragging')
    }
    document.body.classList.add('split-dragging')
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
    window.addEventListener('pointercancel', up)
  }

  const remember = () => {
    scrollMemo = {}
    for (const s of splits()) {
      const body = bodyOf(s)
      const mat = matOf(s)
      if (s.dataset.g === undefined) continue
      scrollMemo[s.dataset.g] = { qs: body?.scrollTop ?? 0, mat: mat?.scrollTop ?? 0 }
    }
  }

  const restore = () => {
    for (const s of splits()) {
      const memo = s.dataset.g === undefined ? undefined : scrollMemo[s.dataset.g]
      if (!memo) continue
      const body = bodyOf(s)
      const mat = matOf(s)
      if (body) body.scrollTop = memo.qs
      if (mat) mat.scrollTop = memo.mat
    }
    applyWidths()
    syncAll()
  }

  /**
   * 滚到某道小题。分屏里要滚两层：先把整块分屏顶到视口上方（`block: 'start'`，
   * 配合 CSS 的 `scroll-margin` 让过顶栏），再把右栏自己滚到那道小题的顶部——
   * 两层都用 `scrollIntoView` 的话，内层会被「居中」到栏中间，页签跟着跳错。
   */
  const scrollToCard = (id: string | number, smooth = false) => {
    const root = host.value
    if (!root) return
    const hit = cardOf(root, id)
    if (!hit) return
    const body = hit.closest('.pp-qs-body') as HTMLElement | null
    const outer = hit.closest('.pp-split') as HTMLElement | null
    const box = outer ?? hit
    // jsdom（单测）里没有 scrollIntoView，跳过这一步
    if (typeof box.scrollIntoView === 'function') {
      box.scrollIntoView({ block: 'start', behavior: smooth ? 'smooth' : 'auto' })
    }
    if (body) {
      const delta = hit.getBoundingClientRect().top - body.getBoundingClientRect().top
      scrollBox(body, body.scrollTop + delta - 8, smooth)
      syncTabs(body)
    }
  }

  return {
    onClick,
    onPointerDown,
    onScrollCapture,
    remember,
    restore,
    syncAll,
    applyWidths,
    scrollToCard,
  }
}
