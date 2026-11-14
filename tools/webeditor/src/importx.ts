import * as XLSX from 'xlsx'
import type { Question, QuestionType } from './types'
import { nextUid } from './util'

/**
 * Excel 导入解析层（网页端「导入文件」）。
 *
 * 口径对齐 App 端的导入器（docs/02 试卷格式规范、docs/04 答案后补）：
 * - 先归一化表头，再按别名表映射列；一列只认一个字段，同一字段出现两次取靠前的；
 * - 认得出「题干 + 选项 / 答案」→ 题目导入（可多工作表 / 「试卷」列拆卷）；
 * - 只有「题号 / 答案 / 解析」没有选项列 → 答案后补文件，按「题干 > 题号 > 顺序」
 *   匹配当前卷的题，只动答案和解析，不改题目内容；
 * - 没有表头时按内容形状推断（答案列全是单字母、题干取最长均值），推断不出就报错。
 *
 * 本文件保持纯函数：不碰 Vue 的响应式，DOM / 文件读取都在 store 侧。
 */

/* ------------------------------------------------------------------ 结果类型 */

export interface ImportNote {
  level: 'error' | 'warn'
  message: string
}

/** 题目导入：一个 ParsedSheet 对应网页上的一张工作表（= App 里的一份卷） */
export interface ParsedSheet {
  name: string
  questions: Question[]
  /** 缺答案题数（App 里按「待补答案」处理） */
  missing: number
  /** 题干重复被跳过的行数 */
  dupSkipped: number
}

/** 答案后补文件里的一条：题号可有可无，题干可用来精确匹配 */
export interface BackfillEntry {
  /** 1 起的题号；没有题号列或该行为空时为 null */
  no: number | null
  stem: string
  answer: string
  analysis: string
  /** 文件里的行号（1 起，含表头行），报问题用 */
  line: number
}

export type ImportResult =
  | { kind: 'questions'; sheets: ParsedSheet[]; notes: ImportNote[] }
  | { kind: 'backfill'; entries: BackfillEntry[]; notes: ImportNote[] }
  | { kind: 'empty'; error: string }

/** 后补匹配的单条结果 */
export interface BackfillMatch {
  entry: BackfillEntry
  /** 目标题目的 _uid */
  uid: number
  how: 'stem' | 'no' | 'order'
  answer: string
  analysis: string
  /** 目标题原本就有答案 / 解析，写入会覆盖 */
  overwriteAnswer: boolean
  overwriteAnalysis: boolean
}

export interface BackfillPlan {
  matches: BackfillMatch[]
  /** 没匹配上的条目（多余的部分会被忽略） */
  unmatched: BackfillEntry[]
}

/* ------------------------------------------------------------------ 表头归一与别名 */

/** 全角转半角、去空白 / 下划线 / 连字符 / 括号 / 冒号，再转小写（对齐 docs/02 的归一口径） */
export function normHeader(s: unknown): string {
  const t = String(s ?? '').normalize('NFKC').toLowerCase()
  return t.replace(/[\s_\-–—()（）:：、，,.\/\\'"「」【】\[\]]+/g, '')
}

const FIELD_ALIASES: Record<string, string[]> = {
  type: ['题型', '类型', '题目类型', 'type', 'questionType', 'qtype', 'category'],
  no: ['题号', '题序', '序号', '顺序', '编号', '题目编号', '第几题', 'no', 'number', 'num', 'index', 'idx', 'seq', 'questionNo', 'qNo'],
  stem: ['题干', '题目', '试题', '问题', '题目内容', 'stem', 'question', 'title', 'content', 'questionContent', 'problem', 'q'],
  material: ['材料', '材料内容', '背景', '背景材料', '资料', 'material', 'passage', 'context', 'stem2'],
  images: ['图片', '图片地址', '配图', '插图', '题图', 'image', 'images', 'img', 'pic', 'picture', 'figure', 'photo'],
  options: ['选项', '选项列表', 'options', 'choices', 'optionList'],
  answer: ['答案', '正确答案', '标准答案', '参考答案', 'answer', 'correctAnswer', 'rightAnswer', 'key', 'ans'],
  analysis: ['解析', '答案解析', '试题解析', '说明', '备注', 'analysis', 'explanation', 'explain', 'remark', 'note', 'solution'],
  knowledge: ['知识点', '考点', '知识点标签', '标签', 'knowledge', 'point', 'tag', 'knowledgePoint'],
  difficulty: ['难度', '难度等级', 'difficulty', 'level', 'hard', 'stars'],
  region: ['地区', '省份', '城市', '地域', 'region', 'area', 'province'],
  subject: ['科目', '学科', '分类', '模块', 'subject', 'category', 'module', 'section'],
  source: ['来源', '出处', '试卷来源', 'source', 'from', 'origin'],
  year: ['年份', '年度', 'year', 'date', 'yr'],
  score: ['分值', '分', '分数', '每题分值', 'score', 'points', 'value', 'mark'],
  keywords: ['得分点', '关键词', '采分点', 'keywords', 'keyPoints', 'scoringPoints'],
  paper: ['试卷', '试卷名', '试卷名称', '卷名', '卷子', '套卷', '题卷', '卷号', 'paper', 'examName', 'testName'],
}

/** 选项 A–H 列的别名：A / 选项A / A选项 / optionA / 选A，也认 选项1…选项8、1…8 */
const OPTION_LETTERS = 'ABCDEFGH'.split('')
for (let i = 0; i < OPTION_LETTERS.length; i++) {
  const l = OPTION_LETTERS[i].toLowerCase()
  FIELD_ALIASES[`option_${l}`] = [l, `选项${l}`, `${l}选项`, `option${l}`, `选${l}`, `选项${i + 1}`, `${i + 1}`]
}

/**
 * 字段的认领顺序：题干最关键放最前；同一归一结果撞名时先到先得
 * （如 `category` 在 type 和 subject 里都有别名，type 先认领）。
 */
const FIELD_CLAIM_ORDER = [
  'stem', 'type', 'no', 'material', 'images', 'options',
  ...OPTION_LETTERS.map((l) => `option_${l.toLowerCase()}`),
  'answer', 'analysis', 'knowledge', 'difficulty', 'region',
  'subject', 'source', 'year', 'score', 'keywords', 'paper',
]

const ALIAS_LOOKUP: Map<string, string> = (() => {
  const m = new Map<string, string>()
  for (const field of FIELD_CLAIM_ORDER) {
    for (const alias of FIELD_ALIASES[field] ?? []) {
      const key = normHeader(alias)
      if (key && !m.has(key)) m.set(key, field)
    }
  }
  return m
})()

/** 一行表头 → 字段 → 列下标。认不出任何字段时返回空表。 */
function mapHeaderRow(header: unknown[]): Map<string, number> {
  const map = new Map<string, number>()
  header.forEach((cell, col) => {
    const field = ALIAS_LOOKUP.get(normHeader(cell))
    if (field && !map.has(field)) map.set(field, col)
  })
  return map
}

/* ------------------------------------------------------------------ 题型 / 答案归一 */

const TYPE_BY_NAME: Record<string, QuestionType> = {
  单选: 'single', 单选题: 'single', 选择: 'single', 选择题: 'single',
  single: 'single', singlechoice: 'single', 1: 'single',
  多选: 'multi', 多选题: 'multi', 多项选择: 'multi',
  multi: 'multi', multiple: 'multi', multiplechoice: 'multi', 2: 'multi',
  不定项: 'uncertain', 不定项选择: 'uncertain', 不定项选择题: 'uncertain',
  uncertain: 'uncertain', partial: 'uncertain', 3: 'uncertain',
  判断: 'truefalse', 判断题: 'truefalse', judge: 'truefalse', truefalse: 'truefalse', tf: 'truefalse', bool: 'truefalse', 4: 'truefalse',
  填空: 'fill', 填空题: 'fill', fill: 'fill', blank: 'fill', cloze: 'fill', 5: 'fill',
  主观: 'subjective', 主观题: 'subjective', 简答: 'subjective', 申论: 'subjective',
  材料分析: 'subjective', 论述: 'subjective', essay: 'subjective', subjective: 'subjective', 6: 'subjective',
}

/** 题型列的取值 → 题型；认不出返回 null（调用方按单选处理并告警） */
export function parseType(raw: unknown): QuestionType | null {
  const key = normHeader(raw)
  return key ? TYPE_BY_NAME[key] ?? null : null
}

/** 与手机端 LanEditorStore 同一张表：判断题答案统一成「正确 / 错误」 */
const TF_TRUE = new Set(['A', 'T', 'TRUE', 'Y', 'YES', '正确', '对', '√', '1'])
const TF_FALSE = new Set(['B', 'F', 'FALSE', 'N', 'NO', '错误', '错', '×', '0'])

export function normalizeTrueFalse(raw: unknown): string {
  const s = String(raw ?? '').trim().toUpperCase()
  if (TF_TRUE.has(s)) return '正确'
  if (TF_FALSE.has(s)) return '错误'
  return String(raw ?? '').trim()
}

/** 选择题答案：抽字母去重排序（`A. 选项原文` / `答案：A` 这类写法一并吃掉） */
function normalizeChoiceAnswer(raw: unknown): string {
  const letters = Array.from(new Set(String(raw ?? '').toUpperCase().match(/[A-H]/g) ?? [])).sort()
  return letters.join(',')
}

/** 按目标题的题型把答案归一成库里的写法 */
export function normalizeAnswerFor(type: QuestionType, raw: unknown): string {
  const s = String(raw ?? '').trim()
  if (!s) return ''
  if (type === 'truefalse') return normalizeTrueFalse(s)
  if (type === 'single' || type === 'multi' || type === 'uncertain') return normalizeChoiceAnswer(s)
  return s
}

const DIFFICULTY_OK = new Set(['1', '2', '3', '易', '中', '难', '简单', '中等', '困难'])

/* ------------------------------------------------------------------ 题目导入 */

interface SheetParsed {
  questions: Question[]
  notes: ImportNote[]
  skipped: number
  dupSkipped: number
  missing: number
  papers: Map<string, Question[]>
  hasPaperCol: boolean
}

function blankFrom(row: Record<string, string>): Question {
  return {
    _uid: nextUid(),
    type: 'single',
    subject: row.subject ?? '',
    material: row.material ?? '',
    images: '',
    stem: row.stem ?? '',
    options: [],
    answer: '',
    analysis: row.analysis ?? '',
    knowledge: row.knowledge ?? '',
    difficulty: row.difficulty ?? '',
    region: row.region ?? '',
    source: row.source ?? '',
    year: row.year ?? '',
    score: row.score ?? '',
    keywords: row.keywords ?? '',
    _issues: null,
  }
}

/** 「图片」列 → markdown 图，前置到材料顶部（对齐 App：题图在题干上方、材料文本跟在图后） */
function imagesToMaterial(images: string, material: string): string {
  const paths = images.split(/\n|\|/).map((s) => s.trim()).filter(Boolean)
  if (!paths.length) return material
  const lines = paths.map((p) => (/^!\[.*\]\(.*\)$/.test(p) ? p : `![图](${p})`))
  return material ? `${lines.join('\n')}\n${material}` : lines.join('\n')
}

/** 选项列（单个「选项」格，`A. xxx` 换行写）→ 选项数组 */
function parseOptionsCell(cell: string): { key: string; text: string }[] {
  const out: { key: string; text: string }[] = []
  for (const line of cell.split(/\r?\n/)) {
    const t = line.trim()
    if (!t) continue
    const m = t.match(/^(?:[（(]?\s*([A-Ha-h])\s*[.、．)）])\s*(.*)$/)
    if (m) out.push({ key: m[1].toUpperCase(), text: m[2].trim() })
    else out.push({ key: OPTION_LETTERS[out.length] ?? `?${out.length + 1}`, text: t })
  }
  return out
}

/** 解析一张工作表（二维表：首行表头，其余数据行） */
/** 一个表头行够不够格当表头：至少要认出题干 / 答案 / 解析之一。
 *  不认单个「选项A」这种列——无表头的卷子里答案列顶格就是字母 A/B，会误判。 */
const isHeaderish = (map: Map<string, number>): boolean =>
  map.has('stem') || map.has('answer') || map.has('analysis')

export function parseSheetRows(rows: unknown[][], fallbackName: string): SheetParsed {
  const notes: ImportNote[] = []
  const out: SheetParsed = {
    questions: [], notes, skipped: 0, dupSkipped: 0, missing: 0,
    papers: new Map(), hasPaperCol: false,
  }

  const firstRow = rows.find((r) => (r ?? []).some((c) => String(c ?? '').trim())) ?? []
  let map = mapHeaderRow(firstRow)
  let hasHeader = isHeaderish(map)
  if (!hasHeader) {
    const inferred = inferColumns(rows)
    if (!inferred) {
      notes.push({ level: 'error', message: `未找到「题干/题目/stem」列，无法识别表头${fallbackName ? `（${fallbackName}）` : ''}` })
      return out
    }
    map = inferred.map
    notes.push({ level: 'warn', message: `没有表头，已按内容推断列含义：${inferred.desc}` })
  }

  const get = (row: unknown[], field: string): string => {
    const col = map.get(field)
    return col === undefined ? '' : String(row?.[col] ?? '').trim()
  }

  const dataRows = (hasHeader ? rowsAfterHeader(rows, firstRow) : nonEmptyRows(rows))
  const seenStems = new Set<string>()
  let currentPaper = ''

  for (let i = 0; i < dataRows.length; i++) {
    const row = dataRows[i]
    const lineNo = i + 2
    const stem = get(row, 'stem')
    if (!stem) {
      // 整行全空是分节留白，不提示；有其他内容但没题干才算「题干为空」
      if ((row ?? []).some((c) => String(c ?? '').trim())) {
        out.skipped++
        notes.push({ level: 'error', message: `第 ${lineNo} 行 · 题干为空，已跳过` })
      }
      continue
    }

    const typeRaw = get(row, 'type')
    const hinted = parseType(typeRaw)
    if (typeRaw && !hinted) {
      notes.push({ level: 'warn', message: `第 ${lineNo} 行 · 无法识别的题型「${typeRaw}」，按单选题处理` })
    }
    let type: QuestionType = hinted ?? 'single'

    // 选项：A–H 各列优先，其次单个「选项」格
    let options: { key: string; text: string }[] = []
    const optFromLetters = OPTION_LETTERS
      .map((l) => ({ key: l, text: get(row, `option_${l.toLowerCase()}`) }))
      .filter((o) => o.text)
    if (optFromLetters.length >= 2) options = optFromLetters
    else if (optFromLetters.length === 1) options = optFromLetters
    else if (map.has('options')) options = parseOptionsCell(get(row, 'options'))

    const answerRaw = get(row, 'answer')
    const analysis = get(row, 'analysis')

    // 判断题没有选项列时沿用 A/B（正确 / 错误）——App 判题认中文
    if (type === 'truefalse' && !options.length) {
      options = [{ key: 'A', text: '正确' }, { key: 'B', text: '错误' }]
      notes.push({ level: 'warn', message: `第 ${lineNo} 行 · 判断题选项沿用 A/B（正确 / 错误），实际按中文判题` })
    }

    // App 口径：当前只答选择题。填空 / 主观带选项降级单选，不带选项整题跳过
    if ((type === 'fill' || type === 'subjective') && !options.length) {
      out.skipped++
      notes.push({ level: 'error', message: `第 ${lineNo} 行 · 当前版本仅支持选择题，该题已跳过` })
      continue
    }
    if ((type === 'fill' || type === 'subjective') && options.length) {
      notes.push({ level: 'warn', message: `第 ${lineNo} 行 · 填空/主观题带选项，已降级为单选题` })
      type = 'single'
    }
    if (!options.length && type !== 'fill' && type !== 'subjective') {
      const letters = answerRaw.toUpperCase().match(/[A-H]/g) ?? []
      if (letters.length > 1) {
        notes.push({ level: 'warn', message: `第 ${lineNo} 行 · 未识别到选项列，已按多选题处理（发布前记得补选项）` })
        type = 'multi'
      } else {
        out.skipped++
        notes.push({ level: 'error', message: `第 ${lineNo} 行 · 未识别到选项列，无法作答，已跳过` })
        continue
      }
    }

    const q = blankFrom({
      stem, analysis,
      material: imagesToMaterial(get(row, 'images'), get(row, 'material')),
      subject: get(row, 'subject'), knowledge: get(row, 'knowledge'),
      difficulty: get(row, 'difficulty'), region: get(row, 'region'),
      source: get(row, 'source'), year: get(row, 'year'),
      score: get(row, 'score'), keywords: get(row, 'keywords'),
    })
    q.type = type
    q.options = options
    q.answer = normalizeAnswerFor(type, answerRaw)
    if (q.answer && type === 'truefalse' && !TF_TRUE.has(q.answer) && !TF_FALSE.has(q.answer)) {
      notes.push({ level: 'warn', message: `第 ${lineNo} 行 · 判断题答案「${q.answer}」认不出，按待补答案处理` })
      q.answer = ''
    }
    if (get(row, 'difficulty') && !DIFFICULTY_OK.has(get(row, 'difficulty'))) {
      notes.push({ level: 'warn', message: `第 ${lineNo} 行 · 难度「${get(row, 'difficulty')}」不在 易/中/难 里，原样保留` })
    }

    if (!q.answer) out.missing++

    // 题干去重（卷内）：内容完全重复的只保留第一道
    if (seenStems.has(stem)) {
      out.dupSkipped++
      continue
    }
    seenStems.add(stem)

    // 「试卷」列拆卷：留空沿用上一行（合并单元格只写首行是常态）
    const paperRaw = get(row, 'paper')
    if (map.has('paper')) {
      out.hasPaperCol = true
      if (paperRaw) {
        if (!out.papers.has(paperRaw)) out.papers.set(paperRaw, [])
        currentPaper = paperRaw
      } else if (!out.papers.size) {
        out.papers.set(fallbackName, [])
        currentPaper = fallbackName
      }
      out.papers.get(currentPaper)!.push(q)
    } else {
      out.questions.push(q)
    }
  }

  return out
}

/** 数据行 = 表头行之后的非空行（表头行按引用匹配定位） */
function rowsAfterHeader(rows: unknown[][], headerRow: unknown[]): unknown[][] {
  const at = rows.indexOf(headerRow)
  return (at >= 0 ? rows.slice(at + 1) : rows).filter((r) => (r ?? []).some((c) => String(c ?? '').trim()))
}

function nonEmptyRows(rows: unknown[][]): unknown[][] {
  return rows.filter((r) => (r ?? []).some((c) => String(c ?? '').trim()))
}

/** 没有表头时按内容形状推断列：答案列全是单字母；题干取非空均值最长的一列 */
function inferColumns(rows: unknown[][]): { map: Map<string, number>; desc: string } | null {
  const rowsData = rows.filter((r) => (r ?? []).some((c) => String(c ?? '').trim()))
  if (!rowsData.length) return null
  const width = Math.max(...rowsData.map((r) => r.length))
  const cells = (col: number) => rowsData.map((r) => String(r[col] ?? '').trim()).filter(Boolean)

  let answerCol = -1
  for (let c = 0; c < width; c++) {
    const vals = cells(c)
    if (vals.length >= 2 && vals.every((v) => /^[A-Ha-h]$/.test(v))) { answerCol = c; break }
  }

  let stemCol = -1
  let best = 0
  for (let c = 0; c < width; c++) {
    if (c === answerCol) continue
    const vals = cells(c)
    if (vals.length < 2) continue
    const avg = vals.reduce((s, v) => s + v.length, 0) / vals.length
    if (avg > best) { best = avg; stemCol = c }
  }
  if (stemCol < 0 || best < 4) return null

  // 选项：题干右侧、答案列之前的连续列（取值互不相同才算像选项）
  const opts: number[] = []
  const right = answerCol > stemCol ? answerCol : width
  for (let c = stemCol + 1; c < right && opts.length < 8; c++) {
    const vals = cells(c)
    if (!vals.length) break
    if (new Set(vals).size < Math.min(2, vals.length)) break
    if (c === answerCol) break
    opts.push(c)
  }

  const selfProof = answerCol >= 0 || opts.length >= 2
  if (!selfProof) return null

  const map = new Map<string, number>([['stem', stemCol]])
  if (answerCol >= 0) map.set('answer', answerCol)
  opts.forEach((c, i) => map.set(`option_${OPTION_LETTERS[i].toLowerCase()}`, c))
  const parts = [`第 ${stemCol + 1} 列 → 题干`]
  if (answerCol >= 0) parts.push(`第 ${answerCol + 1} 列 → 答案`)
  if (opts.length) parts.push(`第 ${opts.map((c) => c + 1).join('/')} 列 → 选项`)
  return { map, desc: parts.join('，') }
}

/** 默认表名（Sheet1 / 工作表1）没意义，回落到文件名 */
const MEANINGLESS_SHEET = /^(sheet1|工作表1|worksheet1)$/i

/* ------------------------------------------------------------------ 入口：解析一个文件 */

export function parseWorkbook(data: ArrayBuffer, fileName: string): ImportResult {
  let wb: XLSX.WorkBook
  try {
    wb = XLSX.read(data, { type: 'array' })
  } catch {
    return { kind: 'empty', error: '文件打不开：只支持 Excel（.xlsx）格式' }
  }
  const notes: ImportNote[] = []
  const sheets: ParsedSheet[] = []
  const entries: BackfillEntry[] = []
  let sawData = false

  for (const name of wb.SheetNames) {
    const ws = wb.Sheets[name]
    if (!ws) continue
    const rows = XLSX.utils.sheet_to_json<unknown[]>(ws, { header: 1, raw: false, defval: '' })
    const nonEmpty = rows.filter((r) => (r ?? []).some((c) => String(c ?? '').trim()))
    if (!nonEmpty.length) continue

    const header = nonEmpty[0] ?? []
    const map = mapHeaderRow(header)
    // 答案后补文件：认得出表头，有答案/解析列，但没有任何选项列
    if (isHeaderish(map) && !map.has('options')
      && !OPTION_LETTERS.some((l) => map.has(`option_${l.toLowerCase()}`))
      && (map.has('answer') || map.has('analysis'))) {
      sawData = true
      const get = (row: unknown[], field: string): string => {
        const col = map.get(field)
        return col === undefined ? '' : String(row?.[col] ?? '').trim()
      }
      const noCol = map.get('no')
      nonEmpty.slice(1).forEach((row, i) => {
        const answer = get(row, 'answer')
        const analysis = get(row, 'analysis')
        if (!answer && !analysis) return
        const noRaw = noCol === undefined ? '' : String(row?.[noCol] ?? '').trim()
        const no = noRaw && /^\d{1,4}$/.test(noRaw) ? parseInt(noRaw, 10) : null
        entries.push({ no, stem: get(row, 'stem'), answer, analysis, line: i + 2 })
      })
      continue
    }

    const base = MEANINGLESS_SHEET.test(name) ? fileName : name
    const parsed = parseSheetRows(rows, base)
    if (parsed.notes.some((n) => n.level === 'error') && !parsed.questions.length && !parsed.papers.size) {
      notes.push(...parsed.notes)
      continue
    }
    sawData = true
    notes.push(...parsed.notes)
    if (parsed.papers.size) {
      for (const [paperName, qs] of parsed.papers) {
        sheets.push(finishSheet(paperName, qs, parsed))
      }
    } else if (parsed.questions.length) {
      sheets.push(finishSheet(base, parsed.questions, parsed))
    }
  }

  if (!sawData) {
    return { kind: 'empty', error: notes.length ? notes.map((n) => n.message).join('\n') : '文件内容为空，或没有可读数据' }
  }
  if (entries.length) return { kind: 'backfill', entries, notes }
  if (sheets.length) return { kind: 'questions', sheets, notes }
  return { kind: 'empty', error: notes.length ? notes.map((n) => n.message).join('\n') : '没解析出题目' }
}

function finishSheet(name: string, questions: Question[], parsed: SheetParsed): ParsedSheet {
  return {
    name,
    questions,
    missing: questions.filter((q) => !q.answer).length,
    dupSkipped: parsed.dupSkipped,
  }
}

/* ------------------------------------------------------------------ 答案后补匹配（题干 > 题号 > 顺序，对齐 docs/04） */

/** 归一化题干：去空白 / 标点 / 全半角差异，全等才算匹配上 */
export function normStem(s: string): string {
  return String(s ?? '').normalize('NFKC').toLowerCase().replace(/[\s\p{P}\p{S}]+/gu, '')
}

export function matchBackfill(entries: BackfillEntry[], questions: Question[]): BackfillPlan {
  const matches: BackfillMatch[] = []
  const unmatched: BackfillEntry[] = []
  const used = new Set<number>()

  const byStem = new Map<string, number>()
  questions.forEach((q) => {
    const key = normStem(q.stem)
    // App 口径：题干长度 ≥ 8 个字才参与题干匹配，防止短题干误撞
    if (key.length >= 8 && !byStem.has(key)) byStem.set(key, q._uid)
  })

  const emit = (entry: BackfillEntry, q: Question, how: BackfillMatch['how']) => {
    used.add(q._uid)
    matches.push({
      entry,
      uid: q._uid,
      how,
      answer: normalizeAnswerFor(q.type, entry.answer),
      analysis: entry.analysis,
      overwriteAnswer: !!entry.answer && !!q.answer.trim(),
      overwriteAnalysis: !!entry.analysis && !!q.analysis.trim(),
    })
  }

  // 1. 题干优先：答案文件题序被打乱也能对上
  for (const entry of entries) {
    const key = normStem(entry.stem)
    const uid = key.length >= 8 ? byStem.get(key) : undefined
    if (uid !== undefined) {
      const q = questions.find((x) => x._uid === uid)
      if (q && !used.has(q._uid)) { emit(entry, q, 'stem'); continue }
    }
    unmatched.push(entry)
  }

  // 2. 按题号（1 起，对应卷内顺序）
  const stillUnmatched: BackfillEntry[] = []
  for (const entry of unmatched) {
    const at = entry.no !== null ? entry.no - 1 : -1
    const q = at >= 0 && at < questions.length ? questions[at] : undefined
    if (q && !used.has(q._uid)) emit(entry, q, 'no')
    else stillUnmatched.push(entry)
  }

  // 3. 按顺序填给尚未匹配的题（纯答案列表的情况）
  let cursor = 0
  for (const entry of stillUnmatched) {
    while (cursor < questions.length && used.has(questions[cursor]._uid)) cursor++
    if (cursor >= questions.length) break
    emit(entry, questions[cursor], 'order')
    cursor++
  }

  // 最终没匹配上的：从头数一遍，谁都没被认领谁就算多余（多余的部分会被忽略）
  const left = entries.filter((e) => !matches.some((m) => m.entry === e))
  // 按文件行序排，弹层里的清单和文件对得上
  matches.sort((a, b) => a.entry.line - b.entry.line)
  return { matches, unmatched: left }
}
