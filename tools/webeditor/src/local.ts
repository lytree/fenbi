import type { Question, QuestionType } from './types'
import { nextUid } from './util'

/**
 * 本地试卷包（`qbank_out/`、`sample/`、`templates/` 里的 JSON）与编辑器题目之间的转换。
 *
 * 本地 JSON 的字段口径与 App 导入模板一致：题型写中文（单选 / 多选 / 判断…），
 * 选项是 `{ A: "…" }` 对象。编辑器内部统一用 `single` / `multi` 等 code 和
 * `[{ key, text }]` 数组，所以两头各转一次；图片路径原样保留（`images/x.png`），
 * 由开发服务器的 `/local/img` 按试卷包目录解析。
 */

export interface LocalOptionMap {
  [key: string]: string
}

export interface LocalQuestionRaw {
  type?: string
  stem?: string
  material?: string
  images?: string
  options?: LocalOptionMap | { key: string; text: string }[]
  answer?: string
  analysis?: string
  knowledge?: string
  subject?: string
  difficulty?: string | number
  region?: string
  source?: string
  year?: string
  score?: string | number
  keywords?: string
}

export interface LocalPackageRaw {
  bank?: string
  title?: string
  description?: string
  questions?: LocalQuestionRaw[]
  [key: string]: unknown
}

export interface LocalPackageResponse extends LocalPackageRaw {
  id: string
  /** JSON 文件在磁盘上的绝对路径 */
  path: string
  /** 试卷包目录，图片相对路径按它解析 */
  dir?: string
}

/** 本地 JSON 的题型写法 → 编辑器 code */
const TYPE_FROM_LOCAL: Record<string, QuestionType> = {
  single: 'single',
  multi: 'multi',
  uncertain: 'uncertain',
  truefalse: 'truefalse',
  单选: 'single',
  单选题: 'single',
  多选: 'multi',
  多选题: 'multi',
  不定项: 'uncertain',
  不定项选择: 'uncertain',
  不定项选择题: 'uncertain',
  判断: 'truefalse',
  判断题: 'truefalse',
  填空: 'fill',
  填空题: 'fill',
  主观: 'subjective',
  主观题: 'subjective',
}

/** 编辑器 code → 本地 JSON 的题型写法 */
const TYPE_TO_LOCAL: Record<QuestionType, string> = {
  single: '单选',
  multi: '多选',
  uncertain: '不定项',
  truefalse: '判断',
  fill: '填空',
  subjective: '主观',
}

export function localTypeToEditor(raw: string | undefined): QuestionType {
  const key = (raw ?? '').trim()
  return TYPE_FROM_LOCAL[key] ?? 'single'
}

export function editorTypeToLocal(type: QuestionType): string {
  return TYPE_TO_LOCAL[type] ?? '单选'
}

/** 判断题在本地可能写 A/B、T/F、正确/错误，编辑器统一用中文 */
function trueFalseAnswer(raw: string): string {
  const v = raw.trim().toUpperCase()
  if (['A', 'T', 'TRUE', 'Y', 'YES', '正确', '对', '√', '1'].includes(v)) return '正确'
  if (['B', 'F', 'FALSE', 'N', 'NO', '错误', '错', '×', '0'].includes(v)) return '错误'
  return raw.trim()
}

function optionsToEditor(raw: LocalQuestionRaw['options']): Question['options'] {
  if (!raw) return []
  if (Array.isArray(raw)) {
    return raw
      .filter((o) => o && String(o.key ?? '').trim())
      .map((o) => ({ key: String(o.key).trim().toUpperCase(), text: String(o.text ?? '') }))
  }
  return Object.entries(raw)
    .filter(([key]) => String(key).trim())
    .map(([key, text]) => ({ key: key.trim().toUpperCase(), text: String(text ?? '') }))
    .sort((a, b) => a.key.localeCompare(b.key))
}

function str(v: unknown): string {
  return v === undefined || v === null ? '' : String(v)
}

export function localQuestionToEditor(raw: LocalQuestionRaw): Question {
  const type = localTypeToEditor(raw.type)
  const answer = str(raw.answer)
  return {
    _uid: nextUid(),
    type,
    subject: str(raw.subject),
    material: str(raw.material),
    images: str(raw.images),
    stem: str(raw.stem),
    options: optionsToEditor(raw.options),
    answer: type === 'truefalse' ? trueFalseAnswer(answer) : answer,
    analysis: str(raw.analysis),
    knowledge: str(raw.knowledge),
    difficulty: str(raw.difficulty),
    region: str(raw.region),
    source: str(raw.source),
    year: str(raw.year),
    score: str(raw.score),
    keywords: str(raw.keywords),
    _issues: null,
  }
}

export function localToQuestions(raw: LocalPackageResponse): Question[] {
  return (raw.questions ?? []).map((q) => localQuestionToEditor(q))
}

function numberOrText(v: string | number): string | number | undefined {
  const text = str(v).trim()
  if (!text) return undefined
  return /^-?\d+(\.\d+)?$/.test(text) ? Number(text) : text
}

export function editorQuestionToLocal(q: Question): LocalQuestionRaw {
  const options: LocalOptionMap = {}
  for (const o of q.options) {
    if (!o.key.trim()) continue
    options[o.key.trim().toUpperCase()] = o.text
  }
  const out: LocalQuestionRaw = {
    type: editorTypeToLocal(q.type),
    stem: q.stem,
    options,
    answer: q.answer,
    analysis: q.analysis,
    knowledge: q.knowledge,
    subject: q.subject,
    source: q.source,
    year: q.year,
    region: q.region,
    material: q.material,
    images: q.images,
    keywords: q.keywords,
  }
  const difficulty = numberOrText(q.difficulty)
  if (difficulty !== undefined) out.difficulty = difficulty
  const score = numberOrText(q.score)
  if (score !== undefined) out.score = score
  return out
}

export function questionsToLocal(qs: Question[]): LocalQuestionRaw[] {
  return qs.map(editorQuestionToLocal)
}

/** 从题干 / 材料 / 选项里取第一张图，给分组板的缩略图用 */
export function firstImageSrc(q: Question): string {
  const text = `${q.material}\n${q.stem}\n${q.options.map((o) => o.text).join('\n')}`
  const md = text.match(/!\[[^\]]*\]\(\s*([^)\s]+)/)
  if (md) return md[1]
  const bare = text.match(/(?:^|\s)((?:[A-Za-z]:[\\/]|\/|\.{1,2}\/|https?:\/\/|asset:\/\/)[^\s)]+\.(?:png|jpe?g|webp|gif|bmp))/i)
  return bare ? bare[1] : ''
}
