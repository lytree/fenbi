/**
 * 卷面预览 / 答案解析 / 记录详情三页共用的「题型渲染」。
 *
 * 三页的骨架是同一套（`.pv-q` / `.pv-opt` / `.ov-grid` / `.sol-box`），题型口径
 * 也必须同源：同一道多选题在预览里是圆徽标、在解析里变方徽标，一眼就看得出串了。
 * 所以单选 / 多选 / 不定项 / 判断 / 填空 / 主观六种题的摆法都收在这里，
 * 各页只决定「给不给答案、给不给你的作答」。
 */
import type { OptionItem } from './types'
import { esc } from './util'
import { renderInline } from './markdown'
import { level1Of } from './xingce'

export interface QuestionLike {
  /** 题型：Question 是 QuestionType，记录接口回来的是裸字符串，两边都收 */
  type: string
  /** 选项：填空 / 主观题没有选项，接口给的可能是空数组，也可能干脆不带 */
  options?: OptionItem[]
  optionLayout?: string
  answer?: string
}

/** 解析态的两组字母：标准答案 / 用户作答 */
export interface OptionMark {
  keys: string[]
  userKeys: string[]
}

const isTF = (t: string): boolean => t === 'truefalse'
/** 多选与不定项在粉笔里都是方角选矿，单选是圆的 */
const isMulti = (t: string): boolean => t === 'multi' || t === 'uncertain'
export const isBlank = (t: string): boolean => t === 'fill' || t === 'subjective'

const TF_TRUE = /^(正确|对|√|t|true|y|yes|1|a)$/i
const TF_FALSE = /^(错误|错|×|x|f|false|n|no|0|b)$/i

/**
 * 答案串 → 选项字母。
 *
 * 选择题直接取字母；判断题在库里可能是 `正确 / 错误`、`对 / 错`、`T / F`、`A / B`，
 * 甚至混着来（App 的答案后补也吃这几种写法），统一折成 A / B 再比对选项。
 */
export function normKeys(type: string, text: string): string[] {
  const raw = (text ?? '').trim()
  if (!raw) return []
  if (isTF(type)) {
    const out: string[] = []
    if (TF_TRUE.test(raw)) out.push('A')
    if (TF_FALSE.test(raw)) out.push('B')
    return out
  }
  return raw
    .toUpperCase()
    .replace(/[^A-H]/g, '')
    .split('')
    .filter(Boolean)
}

/** 展示用的答案：判断题一律翻成「正确 / 错误」，别把 A / B 甩给用户看 */
export function answerText(type: string, answer: string): string {
  const raw = (answer ?? '').trim()
  if (!isTF(type) || !raw) return raw
  const keys = normKeys(type, raw)
  if (keys.length === 1) return keys[0] === 'A' ? '正确' : '错误'
  return raw
}

/** 判断题的徽标换勾叉，选择题用字母 */
const glyphOf = (type: string, key: string): string => {
  if (!isTF(type)) return esc(key)
  const k = key.toUpperCase()
  return k === 'A' ? '✓' : k === 'B' ? '✗' : esc(key)
}

/** 题号行右端那支笔：粉笔解析页卡片右上角的笔记入口，有笔记时染蓝 */
const PEN_SVG =
  '<svg viewBox="0 0 16 16" width="13" height="13" aria-hidden="true">' +
  '<path d="M11.4 2.1a1.4 1.4 0 0 1 2 0l.5.5a1.4 1.4 0 0 1 0 2L5.6 12.9l-3.1.8.8-3.1 8.1-8.5Z" ' +
  'fill="none" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>'

export function noteActionHtml(hasNote: boolean): string {
  const title = hasNote ? '编辑笔记' : '写笔记'
  return `<button class="pv-act${hasNote ? ' on' : ''}" data-act="note" title="${title}" aria-label="${title}">${PEN_SVG}</button>`
}

/**
 * 选项列表。
 *
 * - 预览（不传 mark）：只有形状差异——多选方角、判断勾叉；
 * - 解析 / 记录（传 mark）：粉笔三态——选对的绿实心、错选红实心、
 *   没选到的正确答案绿描边，其余素色。
 */
export function optionListHtml(q: QuestionLike, mark?: OptionMark): string {
  const opts = (q.options ?? []).filter((o) => o.text.trim())
  if (!opts.length) return ''

  const tf = isTF(q.type)
  const cls = [
    isMulti(q.type) ? 'multi' : '',
    tf ? 'tf' : '',
    q.optionLayout === '2x2' && !tf ? 'two' : '',
  ].filter(Boolean)

  const keys = (mark?.keys ?? []).map((k) => k.toUpperCase())
  const picks = (mark?.userKeys ?? []).map((k) => k.toUpperCase())

  const rows = opts.map((o) => {
    const k = o.key.toUpperCase()
    const inAnswer = keys.includes(k)
    const picked = picks.includes(k)
    // 三态优先级：自己选对了 > 答案本身 > 自己错选。没标记时一律素色
    const state = !mark ? '' : picked && inAnswer ? 'right' : inAnswer ? 'ans-only' : picked ? 'wrong' : ''
    return (
      `<div class="pv-opt${state ? ` ${state}` : ''}">` +
      `<span class="pv-key">${glyphOf(q.type, o.key)}</span>` +
      `<span class="pv-text">${renderInline(o.text)}</span>` +
      '</div>'
    )
  })

  return `<div class="pv-opts${cls.length ? ` ${cls.join(' ')}` : ''}">${rows.join('')}</div>`
}

export interface GridCell {
  /** 格子下方的小字：正确答案 / 你的答案 / 分值 */
  k: string
  /** 格子里的大字（已转义或已是 HTML） */
  v: string
  /** 值的着色：g 绿 / r 红 / b 蓝 / o 橙 / pend 待批 / none 灰 */
  cls?: string
  /** 长文本（填空、主观参考答案）：整格左对齐、字号收小 */
  long?: boolean
}

/** 灰底宫格条（粉笔四宫格/两宫格的同款）：1～4 格自适应列数 */
export function gridHtml(cells: GridCell[]): string {
  if (!cells.length) return ''
  const long = cells.some((c) => c.long)
  const n = Math.min(cells.length, 4)
  const body = cells
    .map(
      (c) =>
        `<div class="ov${c.long ? ' long' : ''}">` +
        `<div class="v${c.cls ? ` ${c.cls}` : ''}">${c.v || '—'}</div>` +
        `<div class="k">${esc(c.k)}</div>` +
        '</div>',
    )
    .join('')
  return `<div class="ov-grid c${n}${long ? ' long' : ''}">${body}</div>`
}

/**
 * 填空 / 主观题的「作答区」：预览里给一块虚框，说明这题是往里写的，
 * 不是从选项里挑。记录详情不铺它——那边直接给「你的答案 / 参考答案」两格。
 */
export function writeZoneHtml(type: string): string {
  if (!isBlank(type)) return ''
  const label = type === 'fill' ? '作答区 · 填空' : '作答区 · 主观题'
  return `<div class="pv-write ${esc(type)}"><span>${label}</span></div>`
}

/**
 * 卷面分章：连续同一科目（一级题型）的题算一章，和粉笔作答页的大标题同口径。
 * 卷面预览的章头、底部答题卡的分组都按它走，两处的科目与题数才是同一个数。
 */
export interface PaperChapter {
  name: string
  /** 这一章在卷面上的起止下标（0 基，含） */
  start: number
  end: number
}

export function chaptersOf(qs: { subject?: string }[]): PaperChapter[] {
  const out: PaperChapter[] = []
  qs.forEach((q, i) => {
    const name = level1Of({ subject: q.subject ?? '' }) || '未分章'
    const last = out[out.length - 1]
    if (last && last.name === name) last.end = i
    else out.push({ name, start: i, end: i })
  })
  return out
}

/**
 * 一则资料 + 它带的小题：三页统一成一个 `.pv-group`。
 *
 * 粉笔的卷面里，材料题不是「材料卡 + 一堆互不相干的小题」——小题要看得出一家人。
 * 这里把材料卡和小题收进同一个组，左轨把范围画出来，头部标明「第 6–7 题 · 2 小题」，
 * 资料可以单独收起（小题不跟着藏，方便对着材料做题）。
 */
export interface MaterialGroup {
  /** 第一道小题在卷面上的题号（1 基） */
  start: number
  /** 这道资料带几道小题 */
  count: number
  /** 已经渲染好的材料 HTML */
  materialHtml: string
  /** 已经渲染好的小题 HTML（一组 `.pv-q`） */
  questionsHtml: string
}

/** 材料卡头部：资料 / 题号范围 / 小题数 / 收起 */
export function materialHeadHtml(start: number, count: number): string {
  const end = start + count - 1
  const range = count > 1 ? `第 ${start}–${end} 题` : `第 ${start} 题`
  return (
    '<div class="mh">' +
    '<span class="mtag">资料</span>' +
    `<span class="mrange">${range}</span>` +
    `<span class="mcount">${count} 小题共用</span>` +
    '<button class="mfold" data-fold="mat" title="收起 / 展开资料">' +
    '<span class="mf-t">收起</span><span class="mf-t2">展开</span>' +
    '</button>' +
    '</div>'
  )
}

/** 资料卡（头 + 正文），不含小题；记录页用 JSX 拼小题时也复用它 */
export function materialCardHtml(start: number, count: number, materialHtml: string): string {
  return (
    '<div class="pv-mat">' +
    materialHeadHtml(start, count) +
    `<div class="mbody">${materialHtml}</div>` +
    '</div>'
  )
}

/** 整组：材料卡 + 小题，两组之间留出一段距离（整页版的资料分屏另走 `.pp-split`，见 splitpane.ts） */
export function materialGroupHtml(p: MaterialGroup): string {
  return (
    '<div class="pv-group">' +
    materialCardHtml(p.start, p.count, p.materialHtml) +
    `<div class="pv-subs">${p.questionsHtml}</div>` +
    '</div>'
  )
}
