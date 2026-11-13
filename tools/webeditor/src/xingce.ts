import type { Question } from './types'

/**
 * 行测题型的两级划分。
 *
 * 不新增字段：一级题型复用「科目」（`subject`），二级题型复用「知识点」（`knowledge`）。
 * 下面的表只给输入框做候选提示，不限制自由填写——申论、公基这些非行测内容照样能写。
 *
 * 一级沿用 App 卷面的口径（`PaperStructure.sectionName`）：`行测·资料分析` 只取
 * `资料分析` 作为大类名；二级同理，`资料分析·增长量` 取 `增长量`。
 */
export interface XingceType {
  level1: string
  level2: string[]
}

export const XINGCE_TYPES: XingceType[] = [
  { level1: '言语理解与表达', level2: ['逻辑填空', '片段阅读', '语句表达', '篇章阅读'] },
  { level1: '数量关系', level2: ['数学运算', '数字推理'] },
  { level1: '判断推理', level2: ['图形推理', '定义判断', '类比推理', '逻辑判断'] },
  {
    level1: '资料分析',
    level2: ['增长量', '增长率', '基期量', '现期量', '比重', '平均数', '倍数', '综合分析'],
  },
  { level1: '常识判断', level2: ['政治', '经济', '法律', '历史人文', '地理', '科技生活'] },
]

export const LEVEL1_NAMES: string[] = XINGCE_TYPES.map((t) => t.level1)

/** 一级题型为空时显示的兜底文案 */
export const LEVEL_NONE = '未分类'

/**
 * 题型名归一：`行测·资料分析` / `行测-资料分析` / `行测/资料分析` → `资料分析`。
 * 与 App `PaperStructure.sectionName` 保持同一口径，网页上看到的大类名和卷面一致。
 */
export function typeName(raw: string): string {
  const s = (raw || '').trim()
  if (!s) return ''
  // 取最后一个分隔符之后的部分；分隔符在结尾（如「行测·」）时退回原文
  const name = s.split(/[·\-/]/).pop()?.trim() ?? ''
  return name || s
}

export function level1Of(q: Pick<Question, 'subject'>): string {
  return typeName(q.subject)
}

export function level2Of(q: Pick<Question, 'knowledge'>): string {
  return typeName(q.knowledge)
}

/** 题目的一级 · 二级题型文案；两级都空时返回空串，交给调用方退回「单选 / 多选」 */
export function typeText(q: Pick<Question, 'subject' | 'knowledge'>): string {
  const l1 = level1Of(q)
  const l2 = level2Of(q)
  if (!l1 && !l2) return ''
  return `${l1 || LEVEL_NONE} · ${l2 || LEVEL_NONE}`
}

/** 一级题型对应的二级候选；认不出一级（自由填写）时给全部候选 */
export function level2NamesOf(level1: string): string[] {
  const key = (level1 || '').trim()
  const hit = key
    ? XINGCE_TYPES.find((t) => t.level1 === key || t.level1.includes(key) || key.includes(t.level1))
    : undefined
  return hit ? hit.level2 : XINGCE_TYPES.flatMap((t) => t.level2)
}
