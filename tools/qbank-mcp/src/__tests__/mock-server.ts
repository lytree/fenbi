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
  nextId: number
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

const countQuestions = (set: MockSet) => set.sheets.reduce((n, s) => n + s.questions.length, 0)
const countMissing = (set: MockSet) =>
  set.sheets.reduce((n, s) => n + s.questions.filter((q) => !q.answer.trim()).length, 0)

export function createState(): MockState {
  const state: MockState = { sets: [], records: [], publishes: [], nextId: 1 }
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
          editable: true,
          papers: s.sheets.map((sh, i) => ({
            id: s.id * 100 + i,
            name: sh.name,
            questionCount: sh.questions.length,
            missingAnswers: sh.questions.filter((q) => !q.answer.trim()).length,
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

      const set: MockSet = existing ?? { id: state.nextId++, title, sheets: [] }
      set.sheets = sheets.map((sh) => ({ name: sh.name, questions: sh.questions }))
      if (!existing) state.sets.push(set)

      return json(res, 200, {
        ok: true,
        setId: set.id,
        title,
        questionCount: countQuestions(set),
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
        papers: set.sheets.map((sh) => ({ name: sh.name, questionCount: sh.questions.length })),
      })
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
