import { QbankClient } from './client.js'
import type {
  RecordAnswer,
  RecordDetail,
  RecordSummary,
  SetDetail,
  SetSummary,
  StoredQuestion,
  WireQuestion,
  WireSheet,
} from './schema.js'

/* ------------------------------------------------------------------ 小工具 */

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

const clip = (value: unknown, max = 160): string => {
  const text = String(value ?? '').replace(/\s+/g, ' ').trim()
  return text.length > max ? `${text.slice(0, max)}…` : text
}

const TYPE_LABEL: Record<string, string> = {
  single: '单选',
  multi: '多选',
  uncertain: '不定项',
  truefalse: '判断',
  fill: '填空',
  subjective: '主观',
}

/** 手机端存的题目 → 可以回传的发布结构：剥掉 id 等字段，缺的补空 */
export function toWire(q: StoredQuestion): WireQuestion {
  return {
    type: (q.type ?? 'single') as WireQuestion['type'],
    stem: q.stem ?? '',
    options: (q.options ?? []).map((o) => ({ key: String(o.key ?? ''), text: String(o.text ?? '') })),
    answer: q.answer ?? '',
    material: q.material ?? '',
    subject: q.subject ?? '',
    analysis: q.analysis ?? '',
    knowledge: q.knowledge ?? '',
    difficulty: String(q.difficulty ?? ''),
    region: q.region ?? '',
    source: q.source ?? '',
    year: String(q.year ?? ''),
    score: typeof q.score === 'number' ? q.score : String(q.score ?? ''),
    keywords: q.keywords ?? '',
  }
}

const toSheets = (detail: SetDetail): WireSheet[] =>
  detail.sheets.map((s) => ({ name: s.name, questions: (s.questions ?? []).map(toWire) }))

/** 按 setId 或标题找组；找到就返回，找不到抛一句能直接给用户看的话 */
export async function findSet(
  client: QbankClient,
  by: { setId?: number; title?: string }
): Promise<SetSummary> {
  const { sets } = await client.listSets()
  if (by.setId) {
    const hit = sets.find((s) => s.id === by.setId)
    if (!hit) throw new Error(`找不到 id=${by.setId} 的组。先调 qbank_list_sets 看看现在有哪些组。`)
    if (!hit.editable) throw new Error(`「${hit.title}」是「未分组」里的卷，不支持在线编辑，先在 App 里归到某个标题下。`)
    return hit
  }
  const title = (by.title ?? '').trim()
  if (!title) throw new Error('要给 setId 或 title（组名）其中之一')
  const exact = sets.find((s) => s.title === title)
  if (exact) {
    if (!exact.editable) throw new Error(`「${exact.title}」是「未分组」里的卷，不支持在线编辑。`)
    return exact
  }
  const loose = sets.filter((s) => s.title.includes(title))
  if (loose.length === 1) return loose[0]
  if (loose.length > 1) {
    throw new Error(
      `有 ${loose.length} 个组名包含「${title}」：${loose.map((s) => s.title).join('、')}。请写完整组名。`
    )
  }
  throw new Error(`找不到组「${title}」。先调 qbank_list_sets 看现有组名。`)
}

/* ------------------------------------------------------------------ 格式化 */

export function formatSets(sets: SetSummary[], questionTotal: number): string {
  if (!sets.length) return '手机里的题库还是空的。用 qbank_publish_set 或 qbank_add_questions 建第一组。'
  const lines = [`共 ${sets.length} 组 · ${questionTotal} 题`, '']
  for (const s of sets) {
    const parts = [`${s.paperCount} 卷`, `${s.questionCount} 题`]
    if (s.missingAnswers > 0) parts.push(`缺 ${s.missingAnswers} 题答案`)
    if (!s.editable) parts.push('未归组·不可编辑')
    lines.push(`#${s.id} ${s.title} —— ${parts.join(' · ')}`)
    for (const p of s.papers) {
      const miss = p.missingAnswers > 0 ? `（缺 ${p.missingAnswers}）` : ''
      lines.push(`   · ${p.name}：${p.questionCount} 题${miss}`)
    }
  }
  return lines.join('\n')
}

export function formatQuestion(q: StoredQuestion, sheet: string, index: number): string {
  const type = TYPE_LABEL[String(q.type ?? 'single')] ?? String(q.type ?? '')
  const head = `【${sheet}】第 ${index + 1} 题 · ${type}${q.subject ? ` · ${q.subject}` : ''}`
  const lines = [head]
  if (q.material?.trim()) lines.push(`材料：${clip(q.material, 200)}`)
  lines.push(clip(q.stem, 400) || '（题干为空）')
  for (const o of q.options ?? []) {
    if (!String(o.text ?? '').trim()) continue
    lines.push(`${o.key}. ${clip(o.text, 200)}`)
  }
  const answer = String(q.answer ?? '').trim()
  lines.push(`答案：${answer || '（未填）'}`)
  if (q.analysis?.trim()) lines.push(`解析：${clip(q.analysis, 240)}`)
  return lines.join('\n')
}

export interface GetSetArgs {
  setId?: number
  title?: string
  sheet?: string
  offset?: number
  limit?: number
  /** 只看还没有解析的题——AI 批量补解析时的入口 */
  onlyMissingAnalysis?: boolean
}

/** 读一组（可分页 / 只看某卷）。返回给模型看的紧凑文本。 */
export async function readSet(client: QbankClient, args: GetSetArgs): Promise<string> {
  const set = await findSet(client, { setId: args.setId, title: args.title })
  const detail = await client.getSet(set.id)
  const sheets = args.sheet
    ? detail.sheets.filter((s) => s.name === args.sheet)
    : detail.sheets

  if (args.sheet && !sheets.length) {
    throw new Error(
      `「${detail.title}」里没有叫「${args.sheet}」的卷。现有卷：${detail.sheets.map((s) => s.name).join('、')}`
    )
  }

  const flat: { sheet: string; index: number; q: StoredQuestion }[] = []
  for (const s of sheets) {
    ;(s.questions ?? []).forEach((q, index) => flat.push({ sheet: s.name, index, q }))
  }

  if (args.onlyMissingAnalysis) {
    for (let i = flat.length - 1; i >= 0; i--) {
      if (String(flat[i].q.analysis ?? '').trim()) flat.splice(i, 1)
    }
  }

  const offset = Math.max(0, Math.trunc(args.offset ?? 0))
  const limit = Math.min(100, Math.max(1, Math.trunc(args.limit ?? 20)))
  const page = flat.slice(offset, offset + limit)

  const scope =
    (args.sheet ? `卷「${args.sheet}」` : '全部卷') + (args.onlyMissingAnalysis ? '里的缺解析题' : '')
  const range = page.length
    ? `下面是第 ${offset + 1}–${offset + page.length} 题`
    : offset >= flat.length
      ? `offset=${offset} 已经超过总数，没有更多题了`
      : '这一页没有题'
  const header =
    `「${detail.title}」${scope}共 ${flat.length} 题，${range}` +
    `（共 ${detail.sheets.length} 卷、${detail.questionCount} 题；缺答案 ${detail.missingAnswers} 题）`

  const body = page.map((it) => formatQuestion(it.q, it.sheet, it.index)).join('\n\n')
  const more: string[] = []
  if (offset + page.length < flat.length) {
    more.push(`继续读：offset=${offset + page.length}, limit=${limit}${args.sheet ? `, sheet="${args.sheet}"` : ''}`)
  }
  if (!args.sheet && detail.sheets.length > 1) {
    more.push(`只看某一卷：sheet="${detail.sheets[0].name}"`)
  }
  more.push('改某一题用 qbank_update_question（sheet + index），加题用 qbank_add_questions')

  return [header, '', body, '', ...more.map((m) => `· ${m}`)].join('\n').trim()
}

/* ------------------------------------------------------------------ 写操作 */

export interface AddQuestionsArgs {
  title: string
  sheet?: string
  questions: WireQuestion[]
}

/**
 * 加题 = 读整组 → 往指定卷末尾追加 → 整组重新发布。
 *
 * 手机端没有「只追加一题」的接口（发布就是整组替换），所以这里是读-改-写。
 * 代价是：如果这期间网页上也改了同一组，后写的会覆盖先写的——单人使用时无所谓。
 */
export async function addQuestions(client: QbankClient, args: AddQuestionsArgs): Promise<string> {
  const title = args.title.trim()
  if (!title) throw new Error('title 不能为空')
  if (!args.questions.length) throw new Error('questions 是空的，没东西可加')
  const sheetName = (args.sheet ?? '').trim()

  const sets = await client.listSets()
  const existing = sets.sets.find((s) => s.title === title)

  if (!existing) {
    const name = sheetName || '试卷'
    const r = await client.publish(title, false, [{ name, questions: args.questions }])
    return [
      `已新建组「${r.title}」并发布：${name} · ${r.questionCount} 题。`,
      '手机上的试卷列表现在就能看到；如果要退回，右栏「版本」或 qbank_list_versions 里能找到这次之前的存档。',
    ].join('\n')
  }

  const detail = await client.getSet(existing.id)
  const sheets = toSheets(detail)
  const name = sheetName || sheets[0]?.name || '试卷'
  let target = sheets.find((s) => s.name === name)
  if (!target) {
    target = { name, questions: [] }
    sheets.push(target)
  }
  target.questions.push(...args.questions)

  const r = await client.publish(title, true, sheets)
  const version = r.version ? `\n覆盖前的旧版已存档（版本 ${r.version.id}，${r.version.questionCount} 题），可回滚。` : ''
  return [
    `已给「${r.title} / ${name}」加 ${args.questions.length} 题，现在整组 ${r.questionCount} 题。`,
    `（手机端是整组重导：这次提交的是 ${r.papers.map((p) => `${p.name} ${p.questionCount} 题`).join('、')}）${version}`,
  ].join('\n')
}

export interface UpdateQuestionArgs {
  title: string
  sheet?: string
  index: number
  patch: Partial<WireQuestion>
}

export async function updateQuestion(client: QbankClient, args: UpdateQuestionArgs): Promise<string> {
  const set = await findSet(client, { title: args.title })
  const detail = await client.getSet(set.id)
  const sheets = toSheets(detail)

  const name = (args.sheet ?? '').trim() || sheets[0]?.name
  const target = sheets.find((s) => s.name === name)
  if (!target) throw new Error(`「${detail.title}」里没有叫「${name}」的卷`)

  const at = Math.trunc(args.index) - 1
  if (at < 0 || at >= target.questions.length) {
    throw new Error(`index 超范围：${name} 只有 ${target.questions.length} 题，收到 ${args.index}（从 1 开始数）`)
  }

  const before = target.questions[at]
  target.questions[at] = { ...before, ...args.patch } as WireQuestion
  const r = await client.publish(detail.title, true, sheets)
  const changed = Object.keys(args.patch).join('、')
  return `已改「${r.title} / ${name}」第 ${args.index} 题（${changed}），整组重新发布，共 ${r.questionCount} 题。`
}

export interface DeleteQuestionArgs {
  title: string
  sheet?: string
  index: number
}

export async function deleteQuestion(client: QbankClient, args: DeleteQuestionArgs): Promise<string> {
  const set = await findSet(client, { title: args.title })
  const detail = await client.getSet(set.id)
  const sheets = toSheets(detail)

  const name = (args.sheet ?? '').trim() || sheets[0]?.name
  const idx = sheets.findIndex((s) => s.name === name)
  if (idx < 0) throw new Error(`「${detail.title}」里没有叫「${name}」的卷`)
  const target = sheets[idx]

  const at = Math.trunc(args.index) - 1
  if (at < 0 || at >= target.questions.length) {
    throw new Error(`index 超范围：${name} 只有 ${target.questions.length} 题，收到 ${args.index}（从 1 开始数）`)
  }

  const [removed] = target.questions.splice(at, 1)
  if (!target.questions.length) {
    if (sheets.length === 1) {
      throw new Error('这是最后一张卷，删完整组就空了。要整组删就用 qbank_delete_set（还得在手机上确认）。')
    }
    sheets.splice(idx, 1)
  }

  const r = await client.publish(detail.title, true, sheets)
  return `已删「${name}」第 ${args.index} 题（${clip(removed?.stem, 40)}…），整组现在 ${r.questionCount} 题。`
}

export interface PublishSetArgs {
  title: string
  sheets: WireSheet[]
  replace?: boolean
}

export async function publishSet(client: QbankClient, args: PublishSetArgs): Promise<string> {
  const r = await client.publish(args.title.trim(), !!args.replace, args.sheets)
  const head = r.replaced
    ? `已覆盖「${r.title}」：${r.papers.map((p) => `${p.name} ${p.questionCount} 题`).join('、')}，共 ${r.questionCount} 题。`
    : `已新建「${r.title}」：${r.papers.map((p) => `${p.name} ${p.questionCount} 题`).join('、')}，共 ${r.questionCount} 题。`
  const lines = [head]
  if (r.version) {
    lines.push(`覆盖前的旧版已存档（版本 ${r.version.id}，${r.version.questionCount} 题），可在 qbank_list_versions 里回滚。`)
  }
  lines.push('旧卷的作答记录不会保留——这是「重新导入同一组」的口径。')
  return lines.join('\n')
}

/* ------------------------------------------------------------------ 批改（只读记录） */

const formatWhen = (ts: number): string => {
  if (!ts) return '时间未知'
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function formatRecords(records: RecordSummary[], total: number): string {
  if (!records.length) {
    return '手机里还没有作答记录。先在 App 里做一套卷（交卷后才有记录），再来让 AI 批改。'
  }
  const lines = [`共 ${total} 条作答记录（最新在前，最多显示 200 条）`, '']
  for (const r of records) {
    const when = formatWhen(r.submittedAt || r.startedAt)
    const rate = r.total > 0 ? Math.round((r.correct / r.total) * 100) : 0
    const status =
      r.status === 'done' ? '' : r.status === 'ongoing' ? ' · 未做完' : ` · ${r.status}`
    const extras: string[] = []
    if (r.partial > 0) extras.push(`部分得分 ${r.partial} 题`)
    if (r.answered < r.total) extras.push(`只答了 ${r.answered} 题`)
    lines.push(`#${r.id} ${r.paperName || r.name} · ${when}${status}`)
    lines.push(
      `   ${r.score}/${r.fullScore} 分 · 对 ${r.correct}/${r.total}（正确率 ${rate}%）` +
        (extras.length ? ` · ${extras.join(' · ')}` : '')
    )
  }
  lines.push('', '看某一次的逐题对错：qbank_get_record(recordId)；只看错题加 wrongOnly=true')
  return lines.join('\n')
}

function formatRecordAnswer(a: RecordAnswer): string {
  const type = TYPE_LABEL[String(a.type ?? 'single')] ?? String(a.type ?? '')
  const verdict = a.pending ? '待补答案' : a.correct ? '✓' : a.partial ? '△ 部分得分' : '✗'
  const head =
    `第 ${a.no} 题 · ${type} · ${verdict}` +
    `　你答 ${a.userAnswer || '（空）'} / 正确 ${a.answer || '（未填）'}（${a.earned}/${a.full} 分）`
  const lines = [head]
  if (a.material?.trim()) lines.push(`材料：${clip(a.material, 160)}`)
  lines.push(clip(a.stem, 320) || '（题干为空）')
  const opts = (a.options ?? []).filter((o) => String(o.text ?? '').trim())
  if (opts.length) lines.push(opts.map((o) => `${o.key}. ${clip(o.text, 120)}`).join('　'))
  if (a.analysis?.trim()) lines.push(`解析：${clip(a.analysis, 320)}`)
  return lines.join('\n')
}

export interface ReadRecordArgs {
  recordId: number
  wrongOnly?: boolean
  offset?: number
  limit?: number
}

/**
 * 读一次作答：给 AI 批改用。
 *
 * 只读——分数和作答记录都不改：让模型悄悄改掉已经确认过的判分，比不批改更糟。
 * AI 该做的是看错题、讲错因、指出解析缺漏；要改题目走 qbank_update_question / qbank_set_analysis。
 */
export async function readRecord(client: QbankClient, args: ReadRecordArgs): Promise<string> {
  const rec: RecordDetail = await client.getRecord(args.recordId)
  const all = rec.answers ?? []
  const wrong = all.filter((a) => !a.correct && !a.pending)
  const list = args.wrongOnly ? wrong : all

  const offset = Math.max(0, Math.trunc(args.offset ?? 0))
  const limit = Math.min(50, Math.max(1, Math.trunc(args.limit ?? 20)))
  const page = list.slice(offset, offset + limit)

  const rate = rec.total > 0 ? Math.round((rec.correct / rec.total) * 100) : 0
  const header =
    `「${rec.paperName || rec.name}」${formatWhen(rec.submittedAt || rec.startedAt)} · ` +
    `${rec.score}/${rec.fullScore} 分 · 对 ${rec.correct}/${rec.total}（正确率 ${rate}%）`
  const sub =
    `错题 ${wrong.length} 道；当前 ${args.wrongOnly ? '只看错题' : '全部题目'}，` +
    `第 ${page.length ? offset + 1 : 0}–${offset + page.length} 题，共 ${list.length} 题`

  const body = page.map(formatRecordAnswer).join('\n\n')
  const more: string[] = []
  if (offset + page.length < list.length) {
    more.push(
      `继续读：offset=${offset + page.length}, limit=${limit}` +
        (args.wrongOnly ? ', wrongOnly=true' : '')
    )
  }
  more.push('错题讲完要改题（答案不对、解析缺了）用 qbank_update_question / qbank_set_analysis')

  return [header, sub, '', body, '', ...more.map((m) => `· ${m}`)].join('\n').trim()
}

/* ------------------------------------------------------------------ 批量写解析 */

export interface AnalysisUpdate {
  sheet?: string
  index: number
  analysis: string
}

export interface SetAnalysisArgs {
  title: string
  updates: AnalysisUpdate[]
}

/**
 * 批量写解析：读整组 → 逐条按 `sheet + index` 写到 analysis → 整组发布一次。
 *
 * 为什么批量：AI 通常是「读 20 题 → 生成 20 条解析」，一条一发会把整组重导 20 次，
 * 又慢又容易和网页上的改动打架。这里攒成一次发布。
 */
export async function setAnalysis(client: QbankClient, args: SetAnalysisArgs): Promise<string> {
  if (!args.updates.length) throw new Error('updates 是空的，没东西可写')
  const set = await findSet(client, { title: args.title })
  const detail = await client.getSet(set.id)
  const sheets = toSheets(detail)

  const errors: string[] = []
  let filled = 0
  for (const u of args.updates) {
    const name = (u.sheet ?? '').trim() || sheets[0]?.name || ''
    const target = sheets.find((s) => s.name === name)
    if (!target) {
      errors.push(`没有叫「${name}」的卷`)
      continue
    }
    const at = Math.trunc(u.index) - 1
    if (at < 0 || at >= target.questions.length) {
      errors.push(`「${name}」第 ${u.index} 题不存在（这卷共 ${target.questions.length} 题）`)
      continue
    }
    if (!String(target.questions[at].analysis ?? '').trim()) filled++
    target.questions[at].analysis = u.analysis
  }

  // 有一条对不上就整批不写：宁可让 AI 重查一遍，也不要写进半套解析
  if (errors.length) throw new Error(`这些没写进去：\n${errors.join('\n')}`)

  const r = await client.publish(detail.title, true, sheets)
  return (
    `已给「${r.title}」${args.updates.length} 道题写入解析（其中 ${filled} 道之前是空白的），` +
    `整组已重新发布，共 ${r.questionCount} 题。`
  )
}

/* ------------------------------------------------------------------ 体检：找出错题 */

export interface AuditArgs {
  title?: string
  setId?: number
  /** 最多列多少条，默认 60 */
  limit?: number
}

interface Finding {
  level: 'error' | 'warn'
  sheet: string
  index: number
  stem: string
  issue: string
}

/**
 * 按 App 的导入口径 + 常见脏数据做一遍体检，给出「哪张卷第几题、什么问题」。
 *
 * 只报不改：AI 拿到清单后自己判断怎么修，再用 qbank_batch_update_questions 一次写回。
 * 之所以不自动修，是因为「解析里写的答案和字段不一致」这类问题，到底信哪一个需要人判断。
 */
export async function auditSet(client: QbankClient, args: AuditArgs): Promise<string> {
  const set = await findSet(client, { title: args.title, setId: args.setId })
  const detail = await client.getSet(set.id)
  const findings: Finding[] = []
  const seenStems = new Map<string, { sheet: string; index: number }>()

  for (const sh of detail.sheets) {
    const questions = sh.questions ?? []
    questions.forEach((q, i) => {
      const type = String(q.type ?? 'single')
      const stem = String(q.stem ?? '')
      const answer = String(q.answer ?? '').trim()
      const analysis = String(q.analysis ?? '').trim()
      const opts = (q.options ?? []).filter((o) => String(o.text ?? '').trim())
      const add = (level: Finding['level'], issue: string) =>
        findings.push({ level, sheet: sh.name, index: i + 1, stem: clip(stem, 36), issue })

      if (!stem.trim()) add('error', '题干为空')
      if (['single', 'multi', 'uncertain', 'truefalse'].includes(type) && opts.length < 2) {
        add('error', `只有 ${opts.length} 个有内容的选项（至少要 2 个）`)
      }

      if (type === 'truefalse') {
        if (!answer) add('error', '判断题没填答案')
        else if (answer !== '正确' && answer !== '错误') {
          add('warn', `判断题答案应写「正确 / 错误」，现在是「${answer}」`)
        }
      } else if (['single', 'multi', 'uncertain'].includes(type)) {
        if (!answer) {
          add('error', '没填答案')
        } else {
          const letters = answer.toUpperCase().split(/[,，\s]+/).filter(Boolean)
          const keys = opts.map((o) => String(o.key).toUpperCase())
          const bad = letters.filter((l) => !keys.includes(l))
          if (bad.length) add('warn', `答案「${answer}」里的 ${bad.join('、')} 不在选项范围内`)
          if (type === 'single' && letters.length > 1) {
            add('warn', `单选题给了 ${letters.length} 个答案`)
          }
        }
      }

      if (!analysis) add('warn', '缺解析')

      // 解析里常写「答案：B」，和字段对不上时十有八九是改题时漏了一处
      const m = analysis.match(/答案[：:]\s*([A-Ha-h]{1,8})/)
      if (m && answer) {
        const fromAnalysis = m[1].toUpperCase()
        const fromField = answer.replace(/[,，\s]+/g, '').toUpperCase()
        if (fromAnalysis !== fromField) {
          add('warn', `解析里写「答案：${m[1]}」，字段里是「${answer}」`)
        }
      }

      const blob = stem + opts.map((o) => String(o.text ?? '')).join('')
      if (/<br\s*\/?>|&nbsp;|\u00a0/i.test(blob)) add('warn', '含 HTML 残留（<br> / &nbsp;）')
      if (/[（）]\s{3,}/.test(stem)) add('warn', '题干括号里是一长串空格（App 里按「待填」展示，确认是不是手误）')

      const key = stem.trim()
      if (key) {
        const prev = seenStems.get(key)
        if (prev) add('warn', `题干与「${prev.sheet}」第 ${prev.index} 题完全相同`)
        else seenStems.set(key, { sheet: sh.name, index: i + 1 })
      }
    })
  }

  if (!findings.length) {
    return `「${detail.title}」体检通过：${detail.sheets.length} 卷 ${detail.questionCount} 题，没发现缺答案、选项不足、解析与答案冲突这类问题。`
  }

  const limit = Math.min(200, Math.max(1, Math.trunc(args.limit ?? 60)))
  const errors = findings.filter((f) => f.level === 'error')
  const ordered = [...errors, ...findings.filter((f) => f.level === 'warn')]
  const shown = ordered.slice(0, limit)

  const lines = [
    `「${detail.title}」体检：${detail.questionCount} 题，发现 ${findings.length} 处问题（${errors.length} 处会导致导入失败 / 无法判分）`,
    '',
    ...shown.map(
      (f) => `[${f.level === 'error' ? '错' : '疑'}] ${f.sheet} 第 ${f.index} 题 · ${f.issue}  「${f.stem}」`
    ),
  ]
  if (ordered.length > shown.length) lines.push(`…还有 ${ordered.length - shown.length} 处，调大 limit 继续看`)
  lines.push('', '修：qbank_batch_update_questions，按 sheet + index 一次改多题（只传要改的字段）。')
  return lines.join('\n')
}

/* ------------------------------------------------------------------ 批量改题（修错） */

export interface QuestionPatch {
  sheet?: string
  index: number
  patch: Partial<WireQuestion>
}

export interface BatchUpdateArgs {
  title: string
  updates: QuestionPatch[]
}

/**
 * 批量改题：一次发布改多道题，用于「体检发现 12 处 → 一次修完」。
 *
 * patch 只写要改的字段，没传的保持原样；改选项要传完整 options 数组。
 * 任何一条 sheet/index 对不上就整批不写——半套修改比不改更难排查。
 */
export async function batchUpdateQuestions(
  client: QbankClient,
  args: BatchUpdateArgs
): Promise<string> {
  if (!args.updates.length) throw new Error('updates 是空的，没东西可改')
  const set = await findSet(client, { title: args.title })
  const detail = await client.getSet(set.id)
  const sheets = toSheets(detail)

  const errors: string[] = []
  const touchedFields = new Map<string, number>()

  for (const u of args.updates) {
    const name = (u.sheet ?? '').trim() || sheets[0]?.name || ''
    const target = sheets.find((s) => s.name === name)
    if (!target) {
      errors.push(`没有叫「${name}」的卷`)
      continue
    }
    const at = Math.trunc(u.index) - 1
    if (at < 0 || at >= target.questions.length) {
      errors.push(`「${name}」第 ${u.index} 题不存在（这卷共 ${target.questions.length} 题）`)
      continue
    }
    const keys = Object.keys(u.patch) as (keyof WireQuestion)[]
    if (!keys.length) {
      errors.push(`「${name}」第 ${u.index} 题：patch 是空的`)
      continue
    }
    target.questions[at] = { ...target.questions[at], ...u.patch } as WireQuestion
    for (const k of keys) touchedFields.set(k, (touchedFields.get(k) ?? 0) + 1)
  }

  if (errors.length) throw new Error(`这些没改：\n${errors.join('\n')}`)

  const r = await client.publish(detail.title, true, sheets)
  const summary = [...touchedFields.entries()]
    .map(([k, n]) => `${k}×${n}`)
    .join('、')
  return (
    `已修改「${r.title}」${args.updates.length} 道题（${summary}），整组已重新发布，共 ${r.questionCount} 题。` +
    (r.version ? `\n覆盖前的旧版已存档（版本 ${r.version.id}），改坏了可以回滚。` : '')
  )
}

/* ------------------------------------------------------------------ 版本 */

export async function listVersions(client: QbankClient, title?: string): Promise<string> {
  const { versions } = await client.listVersions(title?.trim() || undefined)
  if (!versions.length) {
    return title
      ? `「${title}」还没有历史版本——第一次覆盖发布之前不会有存档。`
      : '还没有任何版本存档。'
  }
  const lines = [`${versions.length} 个版本（新的在前）`, '']
  for (const v of versions) {
    const when = new Date(v.savedAt).toLocaleString('zh-CN', { hour12: false })
    const why = v.reason === 'restore' ? '回滚前存档' : '发布前存档'
    lines.push(`${v.id}  ${when}  ${v.paperCount} 卷 ${v.questionCount} 题  · ${why}`)
  }
  lines.push('', '回滚：qbank_restore_version（会先把「现在」也存一版，滚错了还能滚回来）')
  return lines.join('\n')
}

export interface RestoreArgs {
  versionId: string
  title?: string
}

export async function restoreVersion(client: QbankClient, args: RestoreArgs): Promise<string> {
  let setId = 0
  if (args.title) {
    const sets = await client.listSets()
    setId = sets.sets.find((s) => s.title === args.title)?.id ?? 0
  }
  const r = await client.restoreVersion(args.versionId, setId)
  return [
    `已回滚「${r.title}」到版本 ${args.versionId}：${r.papers.map((p) => `${p.name} ${p.questionCount} 题`).join('、')}，共 ${r.questionCount} 题。`,
    '回滚前的当前内容也存了一版，需要的话可以再滚回来。',
  ].join('\n')
}

/* ------------------------------------------------------------------ 删除 */

export interface DeleteSetArgs {
  title?: string
  setId?: number
  waitSeconds?: number
}

/**
 * 删除一组：网页 / MCP 都只能「申请」，真正的删除发生在手机上那一下确认。
 *
 * 这里默认等 45 秒（大多数 MCP 客户端的工具超时是 60 秒），超时就告诉调用方
 * 去手机上点，再调一次 qbank_delete_set 不会重复发（服务端会复用同一条待确认请求）。
 */
export async function deleteSet(client: QbankClient, args: DeleteSetArgs): Promise<string> {
  const set = await findSet(client, { setId: args.setId, title: args.title })
  const req = await client.requestDelete(set.id)
  const wait = Math.min(300, Math.max(0, Math.trunc(args.waitSeconds ?? 45)))

  if (wait === 0) {
    return [
      `已把删除「${req.title}」的请求发到手机（requestId ${req.requestId}）。`,
      `${req.detail}`,
      '去手机上点「确认删除」才会真的删；10 分钟内没人理就作废。',
    ].join('\n')
  }

  const deadline = Date.now() + wait * 1000
  while (Date.now() < deadline) {
    await sleep(1500)
    let status: Awaited<ReturnType<QbankClient['deleteStatus']>>
    try {
      status = await client.deleteStatus(req.requestId)
    } catch {
      continue
    }
    if (status.status === 'approved') {
      return `手机上已确认，删掉了「${req.title}」（${set.paperCount} 卷、${set.questionCount} 题）。`
    }
    if (status.status === 'rejected') {
      return status.error
        ? `手机上确认了，但删除失败：${status.error}`
        : `手机上点了「不删」，「${req.title}」还在。`
    }
    if (status.status === 'expired') {
      return `请求已作废（10 分钟没确认）。要删就再调一次 qbank_delete_set。`
    }
  }

  return [
    `已经发了删除请求，${wait} 秒内还没在手机上确认，先不等了。`,
    `手机上会弹确认框（App 没在前台会收到通知）。确认后可以再调 qbank_delete_set，` +
      `服务端会复用这条请求（requestId ${req.requestId}），不会重复发。`,
  ].join('\n')
}
