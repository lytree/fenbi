import { z } from 'zod'

/**
 * 与手机端 `LanEditorStore.toQuestion()` 一一对应的题目结构。
 *
 * 只有题干和选项是硬要求（服务端发布时会再校验一次：题干非空、选择题至少两个有内容的选项），
 * 其余都给默认值——AI 加题时不该被迫填「地区 / 年份 / 分值」这些它不知道的字段。
 */
export const optionSchema = z.object({
  key: z.string().trim().min(1).max(2).describe('选项字母，如 A'),
  text: z.string().describe('选项正文（可以是纯文本，也可以带 Markdown 图片）'),
})

export const questionSchema = z.object({
  type: z
    .enum(['single', 'multi', 'uncertain', 'truefalse', 'fill', 'subjective'])
    .default('single')
    .describe('题型：单选 / 多选 / 不定项 / 判断 / 填空 / 主观'),
  stem: z.string().min(1).describe('题干（必填，不能为空）'),
  options: z.array(optionSchema).default([]).describe('选项；判断题固定用「正确 / 错误」两项'),
  answer: z
    .string()
    .default('')
    .describe('答案：选择题写字母（多选用逗号，如 A,C）；判断题写「正确」或「错误」'),
  material: z.string().default('').describe('材料（资料分析 / 阅读理解那段共用文字，连续几题相同即为一组）'),
  subject: z.string().default('').describe('科目，如 言语理解 / 资料分析 / 判断推理'),
  analysis: z.string().default('').describe('解析'),
  knowledge: z.string().default('').describe('知识点'),
  difficulty: z.union([z.string(), z.number()]).default('').describe('难度 1–3，或空'),
  region: z.string().default('').describe('地区'),
  source: z.string().default('').describe('来源'),
  year: z.union([z.string(), z.number()]).default('').describe('年份'),
  score: z.union([z.string(), z.number()]).default('').describe('分值；不填按题型默认（单选 1、多选 2、主观 10）'),
  keywords: z.string().default('').describe('关键词'),
})

export const sheetSchema = z.object({
  name: z.string().trim().min(1).describe('卷名 = App 里的一张工作表'),
  questions: z.array(questionSchema).min(1).describe('这份卷的题目，按卷面顺序'),
})

/**
 * 回传给手机端的题目结构。
 *
 * 比输入 schema 多两个可选字段：
 * - `id`：手机用它判断「这道题原来就在库里」，命中就原地 UPDATE，
 *   收藏 / 笔记 / 历史作答才不会因为一次改题被清空；
 * - `disabled`：这一题是否已禁用（题还在库里，只是不参与卷面与统计）。
 * 输入侧（新建题目）不接受这两个字段，所以挂在类型上而不是 zod schema 上。
 */
export type WireQuestion = z.infer<typeof questionSchema> & { id?: number; disabled?: boolean }
/** 回传给手机端的整组内容：卷 id 可选，题目用带 id / disabled 的版本 */
export type WireSheet = Omit<z.infer<typeof sheetSchema>, 'questions'> & {
  id?: number
  questions: WireQuestion[]
}

/** 手机端返回的题目：比输入多了 id / disabled，可能带旧字段 */
export interface StoredQuestion extends Partial<WireQuestion> {
  id?: number
  disabled?: boolean
  images?: string
  _uid?: number
  _issues?: unknown
}

export interface SetSummary {
  id: number
  title: string
  source: string
  paperCount: number
  questionCount: number
  missingAnswers: number
  /** 组里被禁用的题数（题还在，只是不参与卷面与统计） */
  disabledQuestions?: number
  editable: boolean
  papers: { id: number; name: string; questionCount: number; missingAnswers: number }[]
}

export interface SetsResponse {
  sets: SetSummary[]
  questionTotal: number
  paperTotal: number
}

export interface SetDetail {
  id: number
  title: string
  sheets: { id?: number; name: string; questions: StoredQuestion[] }[]
  questionCount: number
  missingAnswers: number
  disabledQuestions?: number
}

export interface VersionInfo {
  id: string
  title: string
  /** publish = 发布前的旧版；restore = 回滚前的旧版 */
  reason: string
  savedAt: number
  paperCount: number
  questionCount: number
}

export interface PublishResult {
  ok: boolean
  setId: number
  title: string
  questionCount: number
  /** 组里被禁用的题数（题还在，只是不参与卷面与统计） */
  disabledQuestions?: number
  replaced: boolean
  version: VersionInfo | null
  papers: { name: string; questionCount: number }[]
}

/** 一次作答的摘要（批改入口） */
export interface RecordSummary {
  id: number
  paperId: number
  name: string
  paperName: string
  mode: string
  status: string
  startedAt: number
  submittedAt: number
  durationSec: number
  total: number
  answered: number
  correct: number
  partial: number
  score: number
  fullScore: number
}

/** 记录里的逐题明细：比题库里的题多了「用户怎么答的、对没对」 */
export interface RecordAnswer extends StoredQuestion {
  no: number
  userAnswer: string
  correct: boolean
  partial: boolean
  pending: boolean
  earned: number
  full: number
}

export interface RecordDetail extends RecordSummary {
  answers: RecordAnswer[]
}

/* ------------------------------------------------------------------ 背诵词库（政治 / 常识）+ 词条 */

/** 背诵内容的一块：政治 / 常识（App「背诵」Tab 下的两块背诵卡内容） */
export interface ReciteSubjectInfo {
  id: string
  /**
   * `library` = 政治理论统一库（App 政治页那份，标签 + 条目，只有网页编辑器能改）；
   * `cards` = 还活着的卡片库（现在只剩常识）。缺省按 cards 处理。
   */
  kind?: 'library' | 'cards'
  title: string
  intro: string
  updated: string
  /** 仅 cards */
  categoryCount?: number
  cardCount?: number
  /** 仅 library */
  tagCount?: number
  itemCount?: number
  /** 是否有网页 / AI 存过的覆盖版（false = 还是 App 内置那份） */
  hasEdits: boolean
}

export interface ReciteCategory {
  id: string
  name: string
  /** 二级分组标题，空串 = 未分组 */
  group: string
  count?: number
}

/** 一张背诵卡：正文用 ____ 标填空位，answers 按出现顺序一一对应 */
export interface ReciteCard {
  id: string
  cat: string
  text: string
  answers: string[]
  note: string
  source: string
}

export interface ReciteCatalog {
  subject: string
  title: string
  intro: string
  updated: string
  hasEdits: boolean
  categories: ReciteCategory[]
  cards: ReciteCard[]
}

export interface ReciteSaveResult {
  ok: boolean
  subject: string
  title?: string
  categoryCount?: number
  cardCount?: number
  updated?: string
  /** 仅重置：是否真的删过覆盖文件（false = 本来就没有） */
  reset?: boolean
}

/** 词库（词条）。与 App words 表一一对应 */
export interface WordItem {
  id: number
  text: string
  pinyin: string
  initial: string
  category: string
  tag: string
  meaning: string
  example: string
  source: string
}

export interface WordsResponse {
  words: WordItem[]
  total: number
  counts: Record<string, number>
  tags: { name: string; count: number }[]
}
