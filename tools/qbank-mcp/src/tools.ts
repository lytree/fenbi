import { QbankClient } from './client.js'
import type {
  ReciteCard,
  ReciteCatalog,
  ReciteSubjectInfo,
  RecordAnswer,
  RecordDetail,
  RecordSummary,
  SetDetail,
  SetSummary,
  StoredQuestion,
  WireQuestion,
  WireSheet,
  WordItem,
} from './schema.js'

/* ------------------------------------------------------------------ 小工具 */

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

const clip = (value: unknown, max = 160): string => {
  const text = String(value ?? '').replace(/\s+/g, ' ').trim()
  return text.length > max ? `${text.slice(0, max)}…` : text
}

/**
 * 读题正文用：保留换行，超长才截断，而且**必须标出来**。
 *
 * 改题接口是无损的，模型很容易把截断后的文本当成原文回写，
 * 一写就把长题干 / 长解析永久截短了——所以截断处要明确告诉它「这不是全文」。
 */
const clipText = (value: unknown, max: number, label: string): string => {
  const text = String(value ?? '').trim()
  if (text.length <= max) return text
  return `${text.slice(0, max)}…（${label}原文 ${text.length} 字，已截断；要看全文用 qbank_get_set 加 full=true）`
}

const TYPE_LABEL: Record<string, string> = {
  single: '单选',
  multi: '多选',
  uncertain: '不定项',
  truefalse: '判断',
  fill: '填空',
  subjective: '主观',
}

/**
 * 手机端存的题目 → 可以回传的发布结构：缺的字段补空。
 *
 * `id` 必须带回去：服务端按 id 判断「这题原来就在库里」，命中就原地 UPDATE。
 * 丢了 id 等于整组题目全部重建，收藏 / 笔记 / 错题本会跟着一起没。
 * `disabled` 也原样带回：回传禁用题时保持它的禁用状态，不会被一次发布悄悄启用。
 */
export function toWire(q: StoredQuestion): WireQuestion {
  return {
    id: typeof q.id === 'number' && q.id > 0 ? q.id : undefined,
    disabled: q.disabled === true ? true : undefined,
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

/** 整组回传：卷 id 一起带走，服务端靠它认出「还是那卷」，卷上的记录不用搬家 */
const toSheets = (detail: SetDetail): WireSheet[] =>
  detail.sheets.map((s) => ({
    id: s.id,
    name: s.name,
    questions: (s.questions ?? []).map(toWire),
  }))

/**
 * 写操作用的回传结构：**只带启用的题**。
 *
 * 「第 N 题」在 `qbank_get_set` 里是按启用题编号的（禁用题默认不列），
 * 写回时也必须按同一批题算序号，否则 index 会整体错位。
 * 没回传的禁用题由手机端原地保留（仍是禁用、仍挂在卷上），不会被这次发布删掉。
 */
const toActiveSheets = (detail: SetDetail): WireSheet[] =>
  detail.sheets.map((s) => ({
    id: s.id,
    name: s.name,
    questions: (s.questions ?? []).filter((q) => !q.disabled).map(toWire),
  }))

/**
 * 按 setId 或标题找组；找到就返回，找不到抛一句能直接给用户看的话。
 *
 * @param mode write = 改题 / 删题 / 补解析。写操作只认**完整组名**：
 *   退化成「包含」匹配时，「2024国考」可能落到「2024国考真题」上，
 *   而删改错组是不可逆的（删组虽然有手机确认，改题没有）。
 */
export async function findSet(
  client: QbankClient,
  by: { setId?: number; title?: string },
  mode: 'read' | 'write' = 'read'
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
  if (mode === 'write') {
    const hint = loose.length
      ? `名字里含「${title}」的有：${loose.map((s) => s.title).join('、')}。`
      : '先调 qbank_list_sets 看现有组名。'
    throw new Error(`找不到组名为「${title}」的组。改题 / 删题只认完整组名，${hint}`)
  }
  if (loose.length === 1) {
    if (!loose[0].editable) throw new Error(`「${loose[0].title}」是「未分组」里的卷，不支持在线编辑。`)
    return loose[0]
  }
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

export function formatQuestion(
  q: StoredQuestion,
  sheet: string,
  index: number,
  full = false
): string {
  const type = TYPE_LABEL[String(q.type ?? 'single')] ?? String(q.type ?? '')
  const head = `【${sheet}】第 ${index + 1} 题 · ${type}${q.subject ? ` · ${q.subject}` : ''}${q.disabled ? ' · 已禁用' : ''}`
  const lines = [head]
  // full = 原文照给；否则截断并在断点标出原文长度
  const text = (v: unknown, max: number, label: string) =>
    full ? String(v ?? '').trim() : clipText(v, max, label)
  if (q.material?.trim()) lines.push(`材料：${text(q.material, 200, '材料')}`)
  lines.push(text(q.stem, 400, '题干') || '（题干为空）')
  for (const o of q.options ?? []) {
    if (!String(o.text ?? '').trim()) continue
    lines.push(`${o.key}. ${text(o.text, 200, '选项')}`)
  }
  const answer = String(q.answer ?? '').trim()
  lines.push(`答案：${answer || '（未填）'}`)
  if (q.analysis?.trim()) lines.push(`解析：${text(q.analysis, 240, '解析')}`)
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
  /** 返回完整原文，不截断。要把读到的内容写回去时必须用它 */
  full?: boolean
  /** 连已禁用的题一起读（默认不读）。要恢复某道禁用题时用它拿序号 */
  includeDisabled?: boolean
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
    // 禁用题默认不列：它们不参与卷面与统计，列进来只会让「第 N 题」和 App 里对不上
    const list = (s.questions ?? []).filter((q) => args.includeDisabled || !q.disabled)
    list.forEach((q, index) => flat.push({ sheet: s.name, index, q }))
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
  const off = detail.disabledQuestions ?? 0
  const range = page.length
    ? `下面是第 ${offset + 1}–${offset + page.length} 题`
    : offset >= flat.length
      ? `offset=${offset} 已经超过总数，没有更多题了`
      : '这一页没有题'
  const header =
    `「${detail.title}」${scope}共 ${flat.length} 题，${range}` +
    `（共 ${detail.sheets.length} 卷、${detail.questionCount} 题；缺答案 ${detail.missingAnswers} 题` +
    `${off > 0 ? `；已禁用 ${off} 题，默认不列` : ''}）`

  const body = page.map((it) => formatQuestion(it.q, it.sheet, it.index, args.full)).join('\n\n')
  const more: string[] = []
  if (offset + page.length < flat.length) {
    // 翻页必须带上过滤条件，否则第二页会把已有解析的题混进来，offset 也跟着错位
    more.push(
      `继续读：offset=${offset + page.length}, limit=${limit}` +
        `${args.sheet ? `, sheet="${args.sheet}"` : ''}` +
        `${args.onlyMissingAnalysis ? ', onlyMissingAnalysis=true' : ''}`
    )
  }
  if (!args.sheet && detail.sheets.length > 1) {
    more.push(`只看某一卷：sheet="${detail.sheets[0].name}"`)
  }
  if (off > 0 && !args.includeDisabled) {
    more.push('要看 / 恢复已禁用的题：加 includeDisabled=true（此时「第 N 题」按全部题算）')
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
  const sheets = toActiveSheets(detail)
  const name = sheetName || sheets[0]?.name || '试卷'
  let target = sheets.find((s) => s.name === name)
  if (!target) {
    target = { name, questions: [] }
    sheets.push(target)
  }

  // 去重：上一次调用可能已经写进去了（比如超时后重试），同题干再加一遍就是双份。
  // 按题干判定，和 App「重复题会被去掉」的导入口径一致。
  const existingStems = new Set(
    sheets.flatMap((s) => (s.questions ?? []).map((q) => String(q.stem ?? '').trim()))
  )
  const skipped: string[] = []
  const fresh = args.questions.filter((q) => {
    const stem = String(q.stem ?? '').trim()
    if (stem && existingStems.has(stem)) {
      skipped.push(stem.slice(0, 30))
      return false
    }
    if (stem) existingStems.add(stem)
    return true
  })
  if (!fresh.length) {
    throw new Error(
      `这 ${args.questions.length} 道题的题干在「${detail.title}」里都已经有了，没有新增。` +
        '如果刚才那次调用超时了，写操作很可能已经生效，先用 qbank_get_set 核对，别重复加。'
    )
  }
  target.questions.push(...fresh)

  const r = await client.publish(title, true, sheets)
  const version = r.version ? `\n覆盖前的旧版已存档（版本 ${r.version.id}，${r.version.questionCount} 题），可回滚。` : ''
  const dup = skipped.length
    ? `\n跳过了 ${skipped.length} 道题干重复的题（${skipped.join('、')}${skipped.length > 3 ? '…' : ''}）。`
    : ''
  return [
    `已给「${r.title} / ${name}」加 ${fresh.length} 题，现在整组 ${r.questionCount} 题。${dup}`,
    `（这次提交的是 ${r.papers.map((p) => `${p.name} ${p.questionCount} 题`).join('、')}；练习记录保留）${version}`,
  ].join('\n')
}

export interface UpdateQuestionArgs {
  title: string
  sheet?: string
  index: number
  patch: Partial<WireQuestion>
}

export async function updateQuestion(client: QbankClient, args: UpdateQuestionArgs): Promise<string> {
  const set = await findSet(client, { title: args.title }, 'write')
  const detail = await client.getSet(set.id)
  const sheets = toActiveSheets(detail)

  const name = (args.sheet ?? '').trim() || sheets[0]?.name
  const target = sheets.find((s) => s.name === name)
  if (!target) throw new Error(`「${detail.title}」里没有叫「${name}」的卷`)

  const at = Math.trunc(args.index) - 1
  if (at < 0 || at >= target.questions.length) {
    throw new Error(`index 超范围：${name} 只有 ${target.questions.length} 题，收到 ${args.index}（从 1 开始数）`)
  }

  // patch 为空时整组会白重发一次（并清掉作答记录），却什么都不改——先拦住
  if (!args.patch || !Object.keys(args.patch).length) {
    throw new Error(
      'patch 是空的：写上要改的字段（stem / options / answer / analysis / knowledge / difficulty …）。' +
        '字段名写错时也会落到这里——拼错一个字母，模型会以为改成功了，其实什么都没动。'
    )
  }

  const before = target.questions[at]
  target.questions[at] = { ...before, ...args.patch } as WireQuestion
  const r = await client.publish(detail.title, true, sheets)
  const changed = Object.keys(args.patch).join('、')
  return `已改「${r.title} / ${name}」第 ${args.index} 题（${changed}），整组重新发布，共 ${r.questionCount} 题。`
}

export interface DisableQuestionArgs {
  title: string
  sheet?: string
  index: number
}

/**
 * 「删题」= **禁用**。手机里的题行、收藏、笔记、历史作答都留着，只是不再出现在卷面与统计里。
 *
 * 这是刻意的：真删一道题，做过它的那些记录里这条作答就成了找不到题的孤儿，
 * 分数、正确率、复盘都跟着缺口。禁用随时能恢复（[restoreQuestion]）。
 */
export async function disableQuestion(client: QbankClient, args: DisableQuestionArgs): Promise<string> {
  const set = await findSet(client, { title: args.title }, 'write')
  const detail = await client.getSet(set.id)
  const sheets = toActiveSheets(detail)

  const name = (args.sheet ?? '').trim() || sheets[0]?.name
  if (!name) throw new Error(`「${detail.title}」里没有可编辑的卷`)
  const target = sheets.find((s) => s.name === name)
  if (!target) throw new Error(`「${detail.title}」里没有叫「${name}」的卷`)

  const at = Math.trunc(args.index) - 1
  if (at < 0 || at >= target.questions.length) {
    throw new Error(`index 超范围：${name} 有 ${target.questions.length} 道启用的题，收到 ${args.index}（从 1 开始数）`)
  }

  const q = target.questions[at]
  if (target.questions.length === 1) {
    throw new Error(
      `「${name}」只剩这一道启用的题。手机端不允许一卷一道可用题都没有——` +
        '要整组停用就在 App 里归档 / 删除这一组（qbank_delete_set 需要手机确认）。'
    )
  }

  target.questions[at] = { ...q, disabled: true }
  const r = await client.publish(detail.title, true, sheets)
  return (
    `已禁用「${name}」第 ${args.index} 题（${clip(q.stem, 40)}…），整组现在 ${r.questionCount} 题可用` +
    `${r.disabledQuestions ? `、${r.disabledQuestions} 题已禁用` : ''}。` +
    '\n题目、收藏、笔记和历史作答都还在；要恢复用 qbank_restore_question（先 qbank_get_set 加 includeDisabled=true 看序号）。'
  )
}

export interface RestoreQuestionArgs {
  title: string
  sheet?: string
  /** 按「包含禁用题」的序号，即 qbank_get_set({ includeDisabled: true }) 里的「第 N 题」 */
  index: number
}

/** 把一道禁用题重新启用（发布后回到手机卷面）。index 按含禁用题的全量顺序算 */
export async function restoreQuestion(client: QbankClient, args: RestoreQuestionArgs): Promise<string> {
  const set = await findSet(client, { title: args.title }, 'write')
  const detail = await client.getSet(set.id)
  const sheets = toSheets(detail)

  const name = (args.sheet ?? '').trim() || sheets[0]?.name
  if (!name) throw new Error(`「${detail.title}」里没有可编辑的卷`)
  const target = sheets.find((s) => s.name === name)
  if (!target) throw new Error(`「${detail.title}」里没有叫「${name}」的卷`)

  const at = Math.trunc(args.index) - 1
  if (at < 0 || at >= target.questions.length) {
    throw new Error(
      `index 超范围：${name} 共 ${target.questions.length} 题（含已禁用），收到 ${args.index}（从 1 开始数）。` +
        '恢复时序号要按 qbank_get_set({ includeDisabled: true }) 的输出算。'
    )
  }

  const q = target.questions[at]
  if (!q.disabled) {
    throw new Error(`「${name}」第 ${args.index} 题本来就是启用状态，不用恢复。`)
  }

  target.questions[at] = { ...q, disabled: false }
  const r = await client.publish(detail.title, true, sheets)
  return `已启用「${name}」第 ${args.index} 题（${clip(q.stem, 40)}…），整组现在 ${r.questionCount} 题可用。`
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
  if (r.disabledQuestions) lines.push(`另有 ${r.disabledQuestions} 题处于禁用状态（不参与卷面与统计）。`)
  lines.push('覆盖发布是原地合并：练习记录与未交卷草稿都保留，没出现在这次内容里的题只停用、不删除。')
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
  const set = await findSet(client, { title: args.title }, 'write')
  const detail = await client.getSet(set.id)
  const sheets = toActiveSheets(detail)

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
  const set = await findSet(client, { title: args.title }, 'write')
  const detail = await client.getSet(set.id)
  const sheets = toActiveSheets(detail)

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
    const hit = sets.sets.find((s) => s.title === args.title)
    if (!hit) {
      // 组名打错时不能静默 setId=0：那会让服务端退回快照里的旧组名，
      // 旧组名不存在时就成了「新建一组」——救火操作里凭空多出一组，比报错难查得多
      throw new Error(
        `找不到组「${args.title}」。组名要写完整（回滚不会替你新建组）；` +
          `也可以先 qbank_list_versions 确认这份存档属于哪一组。`
      )
    }
    setId = hit.id
  }
  const r = await client.restoreVersion(args.versionId, setId)
  return [
    `已回滚「${r.title}」到版本 ${args.versionId}：${r.papers.map((p) => `${p.name} ${p.questionCount} 题`).join('、')}，共 ${r.questionCount} 题。`,
    r.replaced === false
      ? '注意：这次是**新建**了一组（原本没有同名组），不是覆盖。'
      : '回滚前的当前内容也存了一版，需要的话可以再滚回来。',
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
  const set = await findSet(client, { setId: args.setId, title: args.title }, 'write')
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

/* ==================================================================
   背诵词库（政治 / 常识）+ 词库（词条）
   内容结构见 App docs/11-背诵.md：卡片正文用 ____ 标填空，
   answers 按出现顺序对应每个空，数量必须相等。
   ================================================================== */

export function formatReciteSubjects(
  subjects: ReciteSubjectInfo[],
  wordsTotal: number
): string {
  const lines = ['手机里现有的背诵内容（App「背诵」Tab：词库 / 政治大纲 / 常识 / 导图）：', '']
  for (const s of subjects) {
    const meta = s.kind === 'library'
      ? `${s.tagCount ?? 0} 标签 · ${s.itemCount ?? 0} 条（政治页读的就是这份，改名 / 改正文请在网页编辑器的「政治理论大纲」里做）`
      : `${s.categoryCount ?? 0} 分类 · ${s.cardCount ?? 0} 卡`
    lines.push(`${s.id} 「${s.title}」—— ${meta}` + (s.hasEdits ? '（有覆盖版）' : '（App 内置版）'))
    if (s.intro) lines.push(`   ${clip(s.intro, 120)}`)
  }
  lines.push(`words 词条词库 —— 共 ${wordsTotal} 条（搜索 / 增改用 qbank_words_search / qbank_word_save）`)
  lines.push(
    '',
    '政治卡片库已退场：政治页现在读统一库（上面 id 为 library 的那条），卡片读写工具只服务常识；',
    '读卡：qbank_recite_get_cards(subject=common)；改卡：qbank_recite_save_cards(...)'
  )
  return lines.join('\n')
}

export interface ReciteGetCardsArgs {
  subject: string
  /** 只看某个分类（分类 id，qbank_recite_get_cards 输出里有） */
  cat?: string
  /** 关键词：搜正文 / 答案 / 备注 / 来源 */
  q?: string
  offset?: number
  limit?: number
  full?: boolean
}

const BLANK = '____'

const countBlanks = (text: string): number => text.split(BLANK).length - 1

function formatReciteCard(c: ReciteCard, index: number, catName: string, full: boolean): string {
  const lines = [`【${index + 1}】id=${c.id} · ${catName}${c.source ? ` · 来源：${clip(c.source, 40)}` : ''}`]
  lines.push(full ? c.text : clipText(c.text, 400, '卡片正文'))
  if (c.answers.length) {
    lines.push(`填空答案（${c.answers.length} 个，按 ____ 顺序）：${c.answers.join(' | ')}`)
  } else {
    lines.push('（无填空：纯知识点卡）')
  }
  if (c.note?.trim()) lines.push(`备注：${full ? c.note : clip(c.note, 120)}`)
  return lines.join('\n')
}

/** 读背诵卡片（分页）。返回给模型看的文本；改卡要用的 id 在每行开头。 */
export async function readReciteCards(client: QbankClient, args: ReciteGetCardsArgs): Promise<string> {
  const subject = args.subject?.trim()
  if (subject !== 'politics' && subject !== 'common') {
    throw new Error(`subject 只能是 politics（政治）或 common（常识），收到「${subject}」`)
  }
  const catalog = await client.reciteCatalog(subject)
  const catName = (id: string) => {
    const c = catalog.categories.find((x) => x.id === id)
    return c ? (c.group ? `${c.group}·${c.name}` : c.name) : id
  }

  let cards = catalog.cards
  if (args.cat?.trim()) {
    const cat = args.cat.trim()
    if (!catalog.categories.some((x) => x.id === cat)) {
      throw new Error(
        `没有分类 ${cat}。现有分类：${catalog.categories.map((x) => `${x.id}=${catName(x.id)}`).join('、')}`
      )
    }
    cards = cards.filter((c) => c.cat === cat)
  }
  const kw = args.q?.trim().toLowerCase()
  if (kw) {
    cards = cards.filter((c) =>
      `${c.text} ${c.answers.join(' ')} ${c.note} ${c.source}`.toLowerCase().includes(kw)
    )
  }

  const offset = Math.max(0, Math.trunc(args.offset ?? 0))
  const limit = Math.min(50, Math.max(1, Math.trunc(args.limit ?? 10)))
  const page = cards.slice(offset, offset + limit)

  const header =
    `「${catalog.title}」（${subject}）符合条件 ${cards.length} 张卡，` +
    `下面是第 ${page.length ? offset + 1 : 0}–${offset + page.length} 张` +
    `（整库 ${catalog.categories.length} 分类 / ${catalog.cards.length} 卡` +
    `${catalog.hasEdits ? '，有覆盖版' : '，内置版'}）`
  const body = page.map((c, i) => formatReciteCard(c, offset + i, catName(c.cat), !!args.full)).join('\n\n')
  const more: string[] = []
  if (offset + page.length < cards.length) {
    more.push(`继续读：offset=${offset + page.length}, limit=${limit}${args.cat ? `, cat="${args.cat}"` : ''}${kw ? `, q="${kw}"` : ''}`)
  }
  more.push('改卡用 qbank_recite_save_cards（按 id patch / 增 / 删，一次调用整份保存）')

  return [header, '', body || '（没有命中的卡片）', '', ...more.map((m) => `· ${m}`)].join('\n').trim()
}

/* ---------------- 整份保存：patch / 增 / 删卡 + 分类维护 ---------------- */

export interface ReciteCardPatch {
  /** 要改哪张卡（qbank_recite_get_cards 输出里的 id） */
  id: string
  patch: {
    text?: string
    answers?: string[]
    note?: string
    source?: string
    /** 换分类 = 移动卡片 */
    cat?: string
  }
}

export interface ReciteCardAddition {
  cat: string
  text: string
  answers: string[]
  note?: string
  source?: string
}

export interface ReciteSaveArgs {
  subject: string
  /** 改弹层标题 / 说明（不传保持原样） */
  title?: string
  intro?: string
  patches?: ReciteCardPatch[]
  additions?: ReciteCardAddition[]
  /** 要删的卡片 id */
  deletions?: string[]
  /** 分类维护：加分类 / 改名换组 */
  categories?: {
    add?: { name: string; group?: string }[]
    rename?: { id: string; name?: string; group?: string }[]
  }
}

let reciteAddSeq = 0

/**
 * 背诵词库的修改入口：读整份 → 按 id 增删改 → 整份保存。
 *
 * 服务端保存是「整份覆盖」，所以所有修改攒成一次调用发过去；
 * 任何一条 id / cat 对不上、____ 数与答案数不一致，就整批不写——
 * 半套修改在背诵内容上比不改更糟（App 里会直接显示成乱码或对不上分类）。
 */
export async function saveReciteCards(client: QbankClient, args: ReciteSaveArgs): Promise<string> {
  const subject = args.subject?.trim()
  if (subject !== 'politics' && subject !== 'common') {
    throw new Error(`subject 只能是 politics（政治）或 common（常识），收到「${subject}」`)
  }
  const catalog = await client.reciteCatalog(subject)
  const errors: string[] = []

  const byId = new Map(catalog.cards.map((c) => [c.id, c]))
  const catIds = new Set(catalog.categories.map((c) => c.id))
  const catName = (id: string) => {
    const c = catalog.categories.find((x) => x.id === id)
    return c ? (c.group ? `${c.group}·${c.name}` : c.name) : id
  }

  // ---- 分类维护先做（新增的分类马上能接卡片）----
  for (const add of args.categories?.add ?? []) {
    const name = add.name?.trim()
    if (!name) { errors.push('categories.add：name 为空'); continue }
    let id = `p${name.replace(/[^\u4e00-\u9fa5a-zA-Z0-9]/g, '').slice(0, 6)}` || `cat${Date.now().toString(36)}`
    let n = 2
    while (catIds.has(id)) id = `${id}${n++}`
    catIds.add(id)
    catalog.categories.push({ id, name, group: add.group?.trim() ?? '' })
  }
  for (const r of args.categories?.rename ?? []) {
    const c = catalog.categories.find((x) => x.id === r.id)
    if (!c) { errors.push(`分类 ${r.id} 不存在`); continue }
    if (r.name?.trim()) c.name = r.name.trim()
    if (r.group !== undefined) c.group = r.group.trim()
  }

  // ---- 删卡 ----
  const deleted = new Set<string>()
  for (const id of args.deletions ?? []) {
    if (!byId.delete(id)) errors.push(`要删的卡片 ${id} 不存在`)
    else deleted.add(id)
  }

  // ---- 改卡 ----
  const touched: string[] = []
  for (const u of args.patches ?? []) {
    const card = byId.get(u.id)
    if (!card) { errors.push(`要改的卡片 ${u.id} 不存在（可能已被删除或 id 写错）`); continue }
    const next: ReciteCard = { ...card, ...u.patch }
    if (u.patch.cat !== undefined && !catIds.has(u.patch.cat)) {
      errors.push(`卡片 ${u.id} 的目标分类 ${u.patch.cat} 不存在`)
      continue
    }
    if (!next.text.trim()) { errors.push(`卡片 ${u.id} 的正文不能改成空`); continue }
    const blanks = countBlanks(next.text)
    if (blanks !== next.answers.length) {
      errors.push(`卡片 ${u.id}：正文有 ${blanks} 个 ____，但给了 ${next.answers.length} 个答案`)
      continue
    }
    if (next.answers.some((a) => !a.trim())) {
      errors.push(`卡片 ${u.id}：有空答案`)
      continue
    }
    byId.set(u.id, next)
    touched.push(`${u.id}(${Object.keys(u.patch).join('/')})`)
  }

  // ---- 加卡 ----
  for (const a of args.additions ?? []) {
    if (!catIds.has(a.cat)) {
      errors.push(`新卡的分类 ${a.cat} 不存在（先用 categories.add 加分类，或用现有 id）`)
      continue
    }
    if (!a.text?.trim()) { errors.push('新卡正文为空'); continue }
    const blanks = countBlanks(a.text)
    const answers = a.answers ?? []
    if (blanks !== answers.length) {
      errors.push(`新卡「${clip(a.text, 24)}…」正文有 ${blanks} 个 ____，但给了 ${answers.length} 个答案`)
      continue
    }
    reciteAddSeq++
    let id = `mcp-${Date.now().toString(36)}-${reciteAddSeq.toString(36)}`
    while (byId.has(id)) id += 'x'
    const card: ReciteCard = {
      id,
      cat: a.cat,
      text: a.text,
      answers,
      note: a.note ?? '',
      source: a.source ?? 'MCP',
    }
    byId.set(id, card)
    touched.push(`${id}(新增)`)
  }

  if (errors.length) throw new Error(`这些没写进去（整份都没保存，放心改完再来）：\n${errors.join('\n')}`)
  if (!touched.length && !deleted.size && !args.title && args.intro === undefined &&
      !args.categories?.add?.length && !args.categories?.rename?.length) {
    throw new Error('没有任何修改：patches / additions / deletions / categories 都是空的')
  }

  const cards = [...byId.values()]
  const r = await client.reciteSave({
    subject,
    title: args.title?.trim() || catalog.title,
    intro: args.intro !== undefined ? args.intro : catalog.intro,
    categories: catalog.categories.map((c) => ({ id: c.id, name: c.name, group: c.group })),
    cards,
  })
  return [
    `已保存「${r.title ?? subject}」：${r.categoryCount} 分类 / ${r.cardCount} 卡` +
      `（本次：改 ${args.patches?.length ?? 0}、加 ${args.additions?.length ?? 0}、删 ${deleted.size}）。`,
    'App 里重新进入背诵页就能看到新版；收藏与背诵进度按卡片 id 保留。',
  ].join('\n')
}

/* ---------------- 重置 ---------------- */

export async function resetRecite(client: QbankClient, subject: string): Promise<string> {
  const s = subject?.trim()
  if (s !== 'politics' && s !== 'common') {
    throw new Error(`subject 只能是 politics（政治）或 common（常识），收到「${subject}」`)
  }
  const catalog = await client.reciteCatalog(s)
  const r = await client.reciteReset(s)
  if (r.reset === false) {
    return `「${catalog.title}」本来就没有覆盖版，内置内容原样。`
  }
  return `已重置「${catalog.title}」：删掉覆盖版，回到 App 内置内容（${r.categoryCount} 分类 / ${r.cardCount} 卡）。`
}

/* ---------------- 词库（词条） ---------------- */

const WORD_CATEGORY: Record<string, string> = {
  idiom: '成语',
  word: '实词',
  other: '其他',
}

export interface WordsSearchArgs {
  q?: string
  category?: string
  tag?: string
  offset?: number
  limit?: number
}

export async function searchWords(client: QbankClient, args: WordsSearchArgs): Promise<string> {
  const r = await client.listWords({ q: args.q, category: args.category, tag: args.tag })
  const offset = Math.max(0, Math.trunc(args.offset ?? 0))
  const limit = Math.min(50, Math.max(1, Math.trunc(args.limit ?? 20)))
  const page = r.words.slice(offset, offset + limit)

  const catLabel = (code: string) => WORD_CATEGORY[code] ?? code
  const header =
    `词条词库共 ${r.total} 条（成语 ${r.counts.idiom ?? 0} · 实词 ${r.counts.word ?? 0} · 其他 ${r.counts.other ?? 0}），` +
    `命中 ${r.words.length} 条，下面是第 ${page.length ? offset + 1 : 0}–${offset + page.length} 条`
  const body = page
    .map((w) =>
      [
        `id=${w.id} 「${w.text}」${w.pinyin ? ` [${w.pinyin}]` : ''} · ${catLabel(w.category)}` +
          (w.tag ? ` · ${w.tag}` : ''),
        w.meaning ? clipText(w.meaning, 160, '释义') : '',
      ].filter(Boolean).join('\n')
    )
    .join('\n\n')
  const more: string[] = []
  if (offset + page.length < r.words.length) {
    more.push(`继续读：offset=${offset + page.length}, limit=${limit}${args.q ? `, q="${args.q}"` : ''}`)
  }
  more.push('加词 / 改词：qbank_word_save；删词：qbank_word_delete')

  return [header, '', body || '（没有命中的词条）', '', ...more.map((m) => `· ${m}`)].join('\n').trim()
}

export interface WordSaveArgs {
  /** 不传 = 新增词条 */
  id?: number
  text: string
  /** 留空 = 按内置字表自动算（新词 / 正文改了才重算，老词条手改过的拼音不会被冲掉） */
  pinyin?: string
  /** idiom（成语）/ word（实词）/ other（其他）；留空按字数推测（4 字成语，其余实词） */
  category?: string
  /** 细分标签，多段用 · 分隔 */
  tag?: string
  meaning?: string
  example?: string
  source?: string
}

export async function saveWord(client: QbankClient, args: WordSaveArgs): Promise<string> {
  if (!args.text?.trim()) throw new Error('text 不能为空')
  const saved = await client.saveWord({
    id: args.id && args.id > 0 ? args.id : undefined,
    text: args.text.trim(),
    pinyin: args.pinyin ?? '',
    category: args.category ?? '',
    tag: args.tag ?? '',
    meaning: args.meaning ?? '',
    example: args.example ?? '',
    source: args.source ?? '',
  })
  const isNew = !args.id
  return [
    `${isNew ? '已新增' : '已保存'}词条「${saved.text}」（id=${saved.id}，${WORD_CATEGORY[saved.category] ?? saved.category}` +
      `${saved.pinyin ? ` · ${saved.pinyin}` : ''}${saved.tag ? ` · ${saved.tag}` : ''}）。`,
    '词条直接写库，不用手机确认；App 词库页搜索即可看到。',
  ].join('\n')
}

export async function deleteWord(client: QbankClient, id: number): Promise<string> {
  if (!id || id <= 0) throw new Error('id 不合法：先用 qbank_words_search 拿到词条 id')
  const r = await client.deleteWord(id)
  return `已删除词条 id=${r.id}（直接写库，不过手机确认；删错可以再用 qbank_word_save 加回来）。`
}
