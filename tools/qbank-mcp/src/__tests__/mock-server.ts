import { createServer } from 'node:http'
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { AddressInfo } from 'node:net'

/**
 * 假的「手机端局域网编辑器」，只实现 MCP server 用到的那几个接口。
 *
 * 这样 test 不必真的连手机：起一个本地 http 服务，把 `/api/*` 的行为按
 * LanEditorServer.kt 的约定复刻一遍（同名组要 replace、发布整组替换、题干不能空）。
 */

export interface MockQuestion {
  type: string
  stem: string
  options: { key: string; text: string }[]
  answer: string
  material?: string
  subject?: string
  analysis?: string
  [k: string]: unknown
}

export interface MockSheet {
  id?: number
  name: string
  questions: MockQuestion[]
}

export interface MockSet {
  id: number
  title: string
  sheets: MockSheet[]
}

export interface MockState {
  sets: MockSet[]
  /** 一次作答记录，用来测批改（只读） */
  records: MockRecord[]
  /** 每次 publish 的请求体，断言用 */
  publishes: { title: string; replace: boolean; sheets: MockSheet[] }[]
  /** 背诵词库（politics / common 各一份）+ 词条词库 */
  recite: Record<string, MockReciteCatalog>
  builtinRecite: Record<string, MockReciteCatalog>
  words: { id: number; text: string; pinyin: string; initial: string; category: string; tag: string; meaning: string; example: string; source: string }[]
  nextId: number
}

export interface MockReciteCard {
  id: string
  cat: string
  text: string
  answers: string[]
  note: string
  source: string
}

export interface MockReciteCatalog {
  title: string
  intro: string
  updated: string
  categories: { id: string; name: string; group: string }[]
  cards: MockReciteCard[]
}

export interface MockRecord {
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
  answers: (MockQuestion & {
    no: number
    userAnswer: string
    correct: boolean
    partial: boolean
    pending: boolean
    earned: number
    full: number
  })[]
}

const json = (res: ServerResponse, status: number, body: unknown) => {
  const text = JSON.stringify(body)
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(text)
}

const readBody = (req: IncomingMessage): Promise<string> =>
  new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    req.on('data', (c) => chunks.push(c as Buffer))
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })

const activeQuestions = (q: MockQuestion[]) => q.filter((x) => x.disabled !== true)
const countQuestions = (set: MockSet) =>
  set.sheets.reduce((n, s) => n + activeQuestions(s.questions).length, 0)
const countMissing = (set: MockSet) =>
  set.sheets.reduce(
    (n, s) => n + activeQuestions(s.questions).filter((q) => !String(q.answer ?? '').trim()).length,
    0,
  )
const countDisabled = (set: MockSet) =>
  set.sheets.reduce((n, s) => n + s.questions.length - activeQuestions(s.questions).length, 0)

export function createState(): MockState {
  const state: MockState = {
    sets: [],
    records: [],
    publishes: [],
    recite: reciteBuiltin(),
    builtinRecite: reciteBuiltin(),
    words: [],
    nextId: 1,
  }
  wordsBuiltin(state)
  state.nextId = 2
  state.sets.push({
    id: 1,
    title: '2024国考',
    sheets: [
      {
        name: '行测',
        questions: [
          {
            type: 'single',
            stem: '第一题：没有语病的一项',
            options: [
              { key: 'A', text: '甲' },
              { key: 'B', text: '乙' },
            ],
            answer: 'B',
            subject: '言语理解',
          },
          {
            type: 'single',
            stem: '第二题：同比增加多少',
            options: [
              { key: 'A', text: '1' },
              { key: 'B', text: '2' },
            ],
            answer: 'A',
            material: '2025年1—2月，总收入5280.0亿元。',
            subject: '资料分析',
          },
        ],
      },
    ],
  })
  state.nextId = 2

  // 一条「做完了」的记录：第 1 题选错、第 2 题选对，用来测批改链路
  const doneAt = 1763000000000
  state.records.push({
    id: 7,
    paperId: 11,
    name: '行测',
    paperName: '行测',
    mode: 'exam',
    status: 'done',
    startedAt: doneAt - 1800000,
    submittedAt: doneAt,
    durationSec: 1800,
    total: 2,
    answered: 2,
    correct: 1,
    partial: 0,
    score: 1,
    fullScore: 2,
    answers: [
      {
        ...state.sets[0].sheets[0].questions[0],
        no: 1,
        userAnswer: 'C',
        correct: false,
        partial: false,
        pending: false,
        earned: 0,
        full: 1,
      },
      {
        ...state.sets[0].sheets[0].questions[1],
        no: 2,
        userAnswer: 'A',
        correct: true,
        partial: false,
        pending: false,
        earned: 1,
        full: 1,
      },
    ],
  })

  return state
}

/** 内置的背诵词库 + 词条，给 recite / words 接口当初始数据 */
function reciteBuiltin(): Record<string, MockReciteCatalog> {
  const catalog: MockReciteCatalog = {
    title: '测试政治背诵',
    intro: 'mock 内置版',
    updated: '2026-01-01',
    categories: [{ id: 'p01', name: '宪法', group: '精编' }],
    cards: [
      { id: 'p01-001', cat: 'p01', text: '我国现行宪法是 ____ 年颁布的。', answers: ['1982'], note: '', source: '宪法' },
      { id: 'p01-002', cat: 'p01', text: '全程无填空的纯知识点卡。', answers: [], note: '', source: '' },
    ],
  }
  const common: MockReciteCatalog = {
    title: '测试常识背诵',
    intro: '',
    updated: '',
    categories: [],
    cards: [],
  }
  return { politics: catalog, common }
}

function wordsBuiltin(state: MockState): void {
  state.words.push(
    { id: 1, text: '画蛇添足', pinyin: 'hua she tian zu', initial: 'H', category: 'idiom', tag: '多余·动作', meaning: '做了多余的事。', example: '', source: '内置' },
    { id: 2, text: '踌躇', pinyin: 'chou chu', initial: 'C', category: 'word', tag: '', meaning: '犹豫。', example: '', source: '内置' },
  )
}

export interface MockServer {
  url: string
  state: MockState
  close: () => Promise<void>
}

export async function startMockServer(state = createState()): Promise<MockServer> {
  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost')
    const path = url.pathname

    // 写入口令只在请求头里；等于别的值就当口令过期
    const key = String(req.headers['x-lan-key'] ?? '')
    if (key && key !== 'test-key') {
      return json(res, 401, { error: '写入口令不对' })
    }

    if (path === '/api/papers' && req.method === 'GET') {
      return json(res, 200, {
        sets: state.sets.map((s) => ({
          id: s.id,
          title: s.title,
          source: 'mock',
          paperCount: s.sheets.length,
          questionCount: countQuestions(s),
          missingAnswers: countMissing(s),
          disabledQuestions: countDisabled(s),
          editable: true,
          papers: s.sheets.map((sh, i) => ({
            id: sh.id ?? s.id * 100 + i,
            name: sh.name,
            questionCount: activeQuestions(sh.questions).length,
            missingAnswers: activeQuestions(sh.questions).filter((q) => !String(q.answer ?? '').trim()).length,
          })),
        })),
        questionTotal: state.sets.reduce((n, s) => n + countQuestions(s), 0),
        paperTotal: state.sets.reduce((n, s) => n + s.sheets.length, 0),
      })
    }

    if (path === '/api/paper' && req.method === 'GET') {
      const id = Number(url.searchParams.get('id'))
      const set = state.sets.find((s) => s.id === id)
      if (!set) return json(res, 404, { error: '找不到这组试卷' })
      return json(res, 200, {
        id: set.id,
        title: set.title,
        sheets: set.sheets,
        questionCount: countQuestions(set),
        missingAnswers: countMissing(set),
        disabledQuestions: countDisabled(set),
      })
    }

    if (path === '/api/records' && req.method === 'GET') {
      const setId = Number(url.searchParams.get('setId') ?? 0)
      const list = setId ? state.records.filter((r) => r.paperId === setId) : state.records
      return json(res, 200, { records: list, total: list.length })
    }

    if (path === '/api/record' && req.method === 'GET') {
      const id = Number(url.searchParams.get('id'))
      const rec = state.records.find((r) => r.id === id)
      if (!rec) return json(res, 404, { error: '找不到这条作答记录（可能已被删掉）' })
      return json(res, 200, rec)
    }

    if (path === '/api/publish' && req.method === 'POST') {
      const raw = await readBody(req)
      let body: { title?: string; replace?: boolean; sheets?: MockSheet[] }
      try {
        body = JSON.parse(raw)
      } catch {
        return json(res, 400, { error: '请求不是合法 JSON' })
      }
      const title = (body.title ?? '').trim()
      const sheets = body.sheets ?? []
      if (!title) return json(res, 400, { error: '标题不能为空' })
      if (!sheets.length) return json(res, 400, { error: '至少要有一份试卷' })

      // 和 LanEditorStore 一样：题干为空 / 选择题不足两个选项都拦下
      const errors: string[] = []
      for (const sh of sheets) {
        sh.questions.forEach((q, i) => {
          if (!String(q.stem ?? '').trim()) errors.push(`「${sh.name}」第 ${i + 1} 题：题干为空`)
          if (q.type !== 'truefalse' && q.type !== 'fill' && q.type !== 'subjective') {
            const opts = (q.options ?? []).filter((o) => o.text.trim())
            if (opts.length < 2) errors.push(`「${sh.name}」第 ${i + 1} 题：选择题至少要 2 个有内容的选项`)
          }
        })
      }
      if (errors.length) return json(res, 400, { error: errors.join('\n') })

      const existing = state.sets.find((s) => s.title === title)
      if (existing && !body.replace) {
        return json(res, 400, { error: `已经有同名的组「${title}」，确认覆盖请点「覆盖发布」` })
      }

      state.publishes.push({ title, replace: !!body.replace, sheets })

      // 与手机端 mergeSet 同口径：原地合并，题按 id / 题干找回原行；
      // 这次没回传的旧题只标 disabled，不删；卷按 id / 卷名对应
      const set: MockSet = existing ?? { id: state.nextId++, title, sheets: [] }
      const oldSheets = set.sheets
      const merged: MockSheet[] = sheets.map((sh) => {
        const old = (sh.id ? oldSheets.find((o) => o.id === sh.id) : undefined)
          ?? oldSheets.find((o) => o.name === sh.name)
        const oldQs = old?.questions ?? []
        const used = new Set<number>()
        const questions: MockQuestion[] = []
        for (const q of sh.questions) {
          let at = -1
          if (typeof q.id === 'number') {
            at = oldQs.findIndex((o, idx) => o.id === q.id && !used.has(idx))
          }
          if (at < 0) {
            at = oldQs.findIndex((o, idx) => !used.has(idx) && String(o.stem ?? '') === String(q.stem ?? ''))
          }
          if (at >= 0) {
            used.add(at)
            questions.push({ ...oldQs[at], ...q, disabled: q.disabled === true })
          } else {
            questions.push({ ...q, id: state.nextId++, disabled: q.disabled === true })
          }
        }
        // 没回传的旧题：留在卷上，标成禁用（和手机端一样，随时能恢复）
        oldQs.forEach((o, idx) => {
          if (!used.has(idx)) questions.push({ ...o, disabled: true })
        })
        return { id: sh.id ?? old?.id ?? state.nextId++, name: sh.name, questions }
      })
      // 整卷没出现在这次发布里：卷停用，题目与记录都留着
      oldSheets.forEach((o) => {
        if (sheets.some((sh) => (sh.id && sh.id === o.id) || sh.name === o.name)) return
        merged.push({ ...o, questions: o.questions.map((q) => ({ ...q, disabled: true })) })
      })
      set.sheets = merged
      if (!existing) state.sets.push(set)

      return json(res, 200, {
        ok: true,
        setId: set.id,
        title,
        questionCount: countQuestions(set),
        disabledQuestions: countDisabled(set),
        replaced: !!existing,
        version: existing
          ? {
              id: '1763000000000-123',
              title,
              reason: 'publish',
              savedAt: 1763000000000,
              paperCount: sheets.length,
              questionCount: countQuestions(set),
            }
          : null,
        papers: set.sheets.map((sh) => ({
          name: sh.name,
          questionCount: activeQuestions(sh.questions).length,
        })),
      })
    }

    // ---- 背诵词库（政治 / 常识） ----

    if (path === '/api/recite/subjects' && req.method === 'GET') {
      return json(res, 200, {
        subjects: Object.entries(state.recite).map(([id, r]) => ({
          id,
          title: r.title,
          intro: r.intro,
          updated: r.updated,
          categoryCount: r.categories.length,
          cardCount: r.cards.length,
          hasEdits: JSON.stringify(r) !== JSON.stringify(state.builtinRecite[id]),
        })),
      })
    }

    if (path === '/api/recite/catalog' && req.method === 'GET') {
      const subject = url.searchParams.get('subject') ?? ''
      const catalog = state.recite[subject]
      if (!catalog) return json(res, 400, { error: `不认识的背诵词库：${subject}` })
      return json(res, 200, {
        subject,
        title: catalog.title,
        intro: catalog.intro,
        updated: catalog.updated,
        hasEdits: JSON.stringify(catalog) !== JSON.stringify(state.builtinRecite[subject]),
        categories: catalog.categories,
        cards: catalog.cards,
      })
    }

    if (path === '/api/recite/save' && req.method === 'POST') {
      const raw = await readBody(req)
      let body: { subject?: string; title?: string; intro?: string; categories?: MockReciteCatalog['categories']; cards?: MockReciteCard[] }
      try {
        body = JSON.parse(raw)
      } catch {
        return json(res, 400, { error: '请求不是合法 JSON' })
      }
      const subject = body.subject ?? ''
      if (!state.recite[subject]) return json(res, 400, { error: `不认识的背诵词库：${subject}` })
      const categories = body.categories ?? []
      const cards = body.cards ?? []
      if (!categories.length) return json(res, 400, { error: '至少要保留一个分类' })
      const catIds = new Set(categories.map((c) => c.id))
      const errors: string[] = []
      const seen = new Set<string>()
      for (const c of cards) {
        if (seen.has(c.id)) errors.push(`卡片 id 重复：${c.id}`)
        seen.add(c.id)
        if (!catIds.has(c.cat)) errors.push(`卡片「${c.id}」的分类 ${c.cat} 不存在`)
        const blanks = c.text.split('____').length - 1
        if (blanks !== (c.answers ?? []).length) {
          errors.push(`卡片「${c.id}」正文有 ${blanks} 个 ____，但给了 ${(c.answers ?? []).length} 个答案`)
        }
      }
      if (errors.length) return json(res, 400, { error: errors.join('\n') })

      state.recite[subject] = {
        title: body.title ?? state.recite[subject].title,
        intro: body.intro ?? '',
        updated: '2026-11-14',
        categories,
        cards,
      }
      return json(res, 200, {
        ok: true,
        subject,
        title: state.recite[subject].title,
        categoryCount: categories.length,
        cardCount: cards.length,
        updated: '2026-11-14',
      })
    }

    if (path === '/api/recite/reset' && req.method === 'POST') {
      const raw = await readBody(req)
      let body: { subject?: string }
      try {
        body = JSON.parse(raw)
      } catch {
        return json(res, 400, { error: '请求不是合法 JSON' })
      }
      const subject = body.subject ?? ''
      if (!state.recite[subject]) return json(res, 400, { error: `不认识的背诵词库：${subject}` })
      const had = JSON.stringify(state.recite[subject]) !== JSON.stringify(state.builtinRecite[subject])
      state.recite[subject] = JSON.parse(JSON.stringify(state.builtinRecite[subject]))
      const r = state.recite[subject]
      return json(res, 200, {
        ok: true,
        subject,
        reset: had,
        categoryCount: r.categories.length,
        cardCount: r.cards.length,
      })
    }

    // ---- 词库（词条） ----

    if (path === '/api/words' && req.method === 'GET') {
      const q = (url.searchParams.get('q') ?? '').trim().toLowerCase()
      const category = url.searchParams.get('category') ?? ''
      const tag = url.searchParams.get('tag') ?? ''
      let list = state.words
      if (category) list = list.filter((w) => w.category === category)
      if (tag) list = list.filter((w) => w.tag.split('·').includes(tag))
      if (q) {
        list = list.filter((w) =>
          `${w.text} ${w.pinyin} ${w.meaning} ${w.tag}`.toLowerCase().includes(q)
        )
      }
      const counts: Record<string, number> = {}
      for (const w of state.words) counts[w.category] = (counts[w.category] ?? 0) + 1
      const tags = new Map<string, number>()
      for (const w of state.words) {
        for (const t of w.tag.split('·').filter(Boolean)) tags.set(t, (tags.get(t) ?? 0) + 1)
      }
      return json(res, 200, {
        words: list,
        total: state.words.length,
        counts,
        tags: [...tags.entries()].map(([name, count]) => ({ name, count })),
      })
    }

    if (path === '/api/word' && req.method === 'POST') {
      const raw = await readBody(req)
      let body: { id?: number; text?: string; pinyin?: string; category?: string; tag?: string; meaning?: string; example?: string; source?: string }
      try {
        body = JSON.parse(raw)
      } catch {
        return json(res, 400, { error: '请求不是合法 JSON' })
      }
      const text = (body.text ?? '').trim()
      if (!text) return json(res, 400, { error: '词条不能为空' })
      const dup = state.words.find((w) => w.text === text && w.id !== (body.id ?? 0))
      if (dup) return json(res, 400, { error: `词库里已经有「${text}」了` })
      if (body.id) {
        const w = state.words.find((x) => x.id === body.id)
        if (!w) return json(res, 400, { error: '词条不存在' })
        Object.assign(w, body)
        return json(res, 200, w)
      }
      const w = {
        id: 900 + state.words.length + 1,
        text,
        pinyin: body.pinyin ?? '',
        initial: '#',
        category: body.category || 'word',
        tag: body.tag ?? '',
        meaning: body.meaning ?? '',
        example: body.example ?? '',
        source: body.source || '网页编辑',
      }
      state.words.push(w)
      return json(res, 200, w)
    }

    if (path === '/api/word' && req.method === 'DELETE') {
      const id = Number(url.searchParams.get('id'))
      const before = state.words.length
      state.words = state.words.filter((w) => w.id !== id)
      if (state.words.length === before) return json(res, 400, { error: '词条不存在' })
      return json(res, 200, { ok: true, id })
    }

    return json(res, 404, { error: `mock 没实现 ${req.method} ${path}` })
  })

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const { port } = server.address() as AddressInfo
  return {
    url: `http://127.0.0.1:${port}`,
    state,
    close: () =>
      new Promise<void>((resolve, reject) =>
        server.close((err) => (err ? reject(err) : resolve()))
      ),
  }
}
