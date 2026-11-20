import { describe, expect, it, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import RecordPane from '../components/RecordPane'
import { records } from '../store'

/**
 * 练习记录是只读回看页：进来就该把「我的选择 / 正确答案 / 解析」全铺开，
 * 不靠点开详情页逐级展开。这里用假的手机端响应验证这一点。
 */

const RECORD = {
  id: 7,
  paperId: 11,
  name: '行测 · 资料分析',
  paperName: '行测',
  mode: 'exam',
  status: 'done',
  startedAt: 1762000000000,
  submittedAt: 1762000600000,
  durationSec: 600,
  total: 2,
  answered: 2,
  correct: 0,
  partial: 0,
  score: 0,
  fullScore: 2,
}

const ANSWER = (extra: Record<string, unknown>) => ({
  type: 'single',
  subject: '资料分析',
  material: '2025年1—2月，总收入5280.0亿元。',
  images: '',
  stem: '',
  options: [
    { key: 'A', text: '选项一' },
    { key: 'B', text: '选项二' },
    { key: 'C', text: '选项三' },
    { key: 'D', text: '选项四' },
  ],
  answer: 'B',
  analysis: '',
  knowledge: '',
  difficulty: '',
  region: '',
  source: '',
  year: '',
  score: 1,
  keywords: '',
  optionLayout: '',
  userAnswer: '',
  correct: false,
  partial: false,
  pending: false,
  earned: 0,
  full: 1,
  ...extra,
})

const DETAIL = {
  ...RECORD,
  answers: [
    ANSWER({ no: 1, stem: '第一题：同比增加多少', userAnswer: 'A', knowledge: '增长率' }),
    ANSWER({ no: 2, stem: '第二题：占比约多少', answer: 'C', analysis: '占比 = 部分 ÷ 整体。' }),
  ],
}

beforeEach(() => {
  Element.prototype.scrollIntoView = () => {}
  records.list = []
  records.total = 0
  records.loaded = false
  records.loading = false
  records.details = {}
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: any) => {
      const url = String(input)
      if (url.startsWith('/api/records')) return json({ records: [RECORD], total: 1 })
      if (url.startsWith('/api/record')) return json(DETAIL)
      return json({ error: `unexpected ${url}` }, 404)
    }),
  )
})

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('练习记录面板', () => {
  it('每条记录都直接铺开逐题明细：答案、解析、我的选择', async () => {
    const w = mount(RecordPane)
    await flushPromises()

    const text = w.text()
    expect(text).toContain('行测 · 资料分析')
    expect(text).toContain('第一题：同比增加多少')
    expect(text).toContain('正确答案')
    expect(text).toContain('我的选择')
    // 第一题选 A 标准答案是 B：判错，答案正文也要把两个值都写出来
    expect(text).toContain('答错')
    expect(text).toContain('增长率')
    // 第二题没作答，解析照样给出来
    expect(text).toContain('未作答')
    expect(text).toContain('占比 = 部分 ÷ 整体。')
  })

  it('选项按对错着色：正确答案标绿、我选错的那项标红', async () => {
    const w = mount(RecordPane)
    await flushPromises()

    // 只看第一题（选 A、答案 B）：第二题另一道题也有自己的绿标
    const first = w.findAll('.rec-q')[0]
    const right = first.findAll('.rec-opt.right')
    expect(right.length).toBe(1)
    expect(right[0].text()).toContain('选项二')

    const wrong = first.findAll('.rec-opt.wrong')
    expect(wrong.length).toBe(1)
    expect(wrong[0].text()).toContain('选项一')
  })

  it('相邻题共用材料时只渲染一次', async () => {
    const w = mount(RecordPane)
    await flushPromises()

    // 两道题写的是同一段材料：第一道铺全文，第二道只留「材料同第 1 题」
    expect(w.findAll('.rec-material').length).toBe(1)
    expect(w.text()).toContain('材料同第 1 题')
  })

  it('没有记录时给一句怎么才会有记录的提示', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ records: [], total: 0 })))
    const w = mount(RecordPane)
    await flushPromises()

    expect(w.text()).toContain('还没有练习记录')
  })
})
