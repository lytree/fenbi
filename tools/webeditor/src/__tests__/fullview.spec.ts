import { describe, expect, it, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import FullView from '../components/FullView'
import { fullUrl, readFullQuery } from '../fullview'
import { state } from '../store'

/**
 * 单独查看页：地址约定（?view=）+ 整页壳子。
 * 数据面照旧打桩 fetch，验的是「换壳之后还认得出是哪一组 / 哪一卷 / 哪一页」。
 */

const SETS = {
  sets: [
    {
      id: 1, title: '2025省考', source: '导入', paperCount: 2, questionCount: 3,
      missingAnswers: 0, editable: true, archived: false,
      papers: [
        { id: 11, name: '行测', questionCount: 2, missingAnswers: 0 },
        { id: 12, name: '申论', questionCount: 1, missingAnswers: 0 },
      ],
    },
  ],
  questionTotal: 3,
  paperTotal: 2,
}

const Q = (stem: string, extra: Record<string, unknown> = {}) => ({
  type: 'single',
  subject: '言语理解',
  material: '',
  images: '',
  stem,
  options: [
    { key: 'A', text: '甲' },
    { key: 'B', text: '乙' },
    { key: 'C', text: '丙' },
    { key: 'D', text: '丁' },
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
  ...extra,
})

const PAPER = {
  id: 1,
  title: '2025省考',
  questionCount: 3,
  missingAnswers: 0,
  sheets: [
    { id: 11, name: '行测', questions: [Q('第一题', { id: 101 }), Q('第二题', { id: 102, answer: 'A', analysis: '因为甲对' })] },
    { id: 12, name: '申论', questions: [Q('第三题', { id: 103 })] },
  ],
}

const RECORDS = {
  records: [
    {
      id: 7, paperId: 11, name: '行测 · 第 3 次', paperName: '行测', mode: '练习',
      status: 'done', startedAt: 1763000000000, submittedAt: 1763003600000,
      durationSec: 3600, total: 2, answered: 2, correct: 1, partial: 0,
      score: 0, fullScore: 0,
    },
  ],
  total: 1,
}

const RECORD = {
  ...RECORDS.records[0],
  answers: [
    {
      no: 1, type: 'single', stem: '第一题', material: '', options: [
        { key: 'A', text: '甲' }, { key: 'B', text: '乙' },
      ], answer: 'B', analysis: '选乙', knowledge: '实词', userAnswer: 'B',
      correct: true, partial: false, pending: false, earned: 1, full: 1,
    },
    {
      no: 2, type: 'single', stem: '第二题', material: '', options: [
        { key: 'A', text: '甲' }, { key: 'B', text: '乙' },
      ], answer: 'A', analysis: '', knowledge: '', userAnswer: 'B',
      correct: false, partial: false, pending: false, earned: 0, full: 1,
    },
  ],
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function mockFetch() {
  const fn = vi.fn(async (input: any) => {
    const url = String(input)
    if (url.startsWith('/api/papers')) return json(SETS)
    if (url.startsWith('/api/paper')) return json(PAPER)
    if (url.startsWith('/api/records')) return json(RECORDS)
    if (url.startsWith('/api/record')) return json(RECORD)
    if (url.startsWith('/api/versions')) return json({ versions: [] })
    if (url.startsWith('/api/img')) return new Response('', { status: 404 })
    return json({ error: 'unexpected ' + url }, 404)
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

beforeEach(() => {
  Element.prototype.scrollIntoView = () => {}
  state.paper = null
  state.sets = []
  state.sheet = 0
  window.history.replaceState(null, '', '/')
  mockFetch()
})

describe('单独查看页 · 地址约定', () => {
  it('认得三种视图，并带出组 / 卷 / 记录', () => {
    expect(readFullQuery('?view=preview')).toEqual({ pane: 'preview', setId: 0, sheetId: 0, recordId: 0 })
    expect(readFullQuery('view=records&setId=3&sheet=12&recordId=7')).toEqual({
      pane: 'records', setId: 3, sheetId: 12, recordId: 7,
    })
    expect(readFullQuery('?view=answer')?.pane).toBe('answer')
  })

  it('没有 view 或视图不认识时当普通编辑器（返回 null）', () => {
    expect(readFullQuery('')).toBeNull()
    expect(readFullQuery('?setId=1')).toBeNull()
    expect(readFullQuery('?view=solution')).toBeNull()
  })

  it('拼地址时不带 0，刷新也落在原地', () => {
    expect(fullUrl('preview')).toBe('/?view=preview')
    expect(fullUrl('answer', { setId: 1, sheetId: 11 })).toBe('/?view=answer&setId=1&sheet=11')
    expect(fullUrl('records', { setId: 0, recordId: 9 })).toBe('/?view=records&recordId=9')
  })
})

describe('单独查看页 · 整页壳', () => {
  it('按 ?view=answer 打开，铺开整页答案解析', async () => {
    const wrapper = mount(FullView, {
      props: { query: { pane: 'answer', setId: 1, sheetId: 0, recordId: 0 } },
      attachTo: document.body,
    })
    await flushPromises()

    expect(wrapper.find('.fullbar').text()).toContain('答案解析')
    expect(wrapper.find('.fullsheet').classes()).toContain('is-answer')
    // 卷面结构与右栏同源：还是 .pv-q 那一套
    expect(wrapper.findAll('.pv-q')).toHaveLength(2)
    expect(wrapper.find('.pv-head-t').text()).toBe('答案解析')
    expect(wrapper.text()).toContain('因为甲对')
    wrapper.unmount()
  })

  it('?sheet= 指定哪一份卷，顶栏跟着显示卷名与题数', async () => {
    const wrapper = mount(FullView, {
      props: { query: { pane: 'preview', setId: 1, sheetId: 12, recordId: 0 } },
      attachTo: document.body,
    })
    await flushPromises()

    expect(wrapper.find('.full-meta').text()).toContain('申论')
    expect(wrapper.findAll('.pv-q')).toHaveLength(1)
    expect(wrapper.find('.full-meta').text()).toContain('1 题')
    wrapper.unmount()
  })

  it('切页签会把当前页写回地址，刷新还停在这一页', async () => {
    const wrapper = mount(FullView, {
      props: { query: { pane: 'preview', setId: 1, sheetId: 0, recordId: 0 } },
      attachTo: document.body,
    })
    await flushPromises()

    const tab = wrapper.findAll('.full-tabs .seg-btn').find((b) => b.text() === '练习记录')
    await tab!.trigger('click')
    await flushPromises()

    expect(window.location.search).toContain('view=records')
    expect(window.location.search).toContain('setId=1')
    expect(wrapper.find('.fullsheet').classes()).toContain('is-records')
    wrapper.unmount()
  })

  it('?recordId= 直接落到这条记录的详情，逐题沿用卷面的卡片与选项样式', async () => {
    const wrapper = mount(FullView, {
      props: { query: { pane: 'records', setId: 1, sheetId: 0, recordId: 7 } },
      attachTo: document.body,
    })
    await flushPromises()
    await flushPromises()

    // 详情：成绩概要 + 答题卡
    expect(wrapper.find('.rec-hero').exists()).toBe(true)
    expect(wrapper.findAll('.rec-grid .cell')).toHaveLength(2)
    expect(wrapper.find('.rec-grid .cell.ok').exists()).toBe(true)
    expect(wrapper.find('.rec-grid .cell.no').exists()).toBe(true)

    // 逐题：跟卷面预览 / 答案解析同一套类名，不再自带一套
    expect(wrapper.findAll('.pv-q.static')).toHaveLength(2)
    expect(wrapper.find('.pv-opt.right').text()).toContain('乙')
    expect(wrapper.find('.pv-opt.wrong').text()).toContain('乙')
    expect(wrapper.find('.sol-box').text()).toContain('选乙')
    expect(wrapper.text()).toContain('正确答案')
    expect(wrapper.text()).toContain('你的答案')
    wrapper.unmount()
  })

  it('在记录页点开一条记录会把 recordId 写进地址，返回列表就摘掉', async () => {
    const wrapper = mount(FullView, {
      props: { query: { pane: 'records', setId: 1, sheetId: 0, recordId: 0 } },
      attachTo: document.body,
    })
    await flushPromises()

    await wrapper.find('.rec-card').trigger('click')
    await flushPromises()
    expect(window.location.search).toContain('recordId=7')
    expect(window.location.search).toContain('view=records')

    await wrapper.findAll('.link-btn').find((b) => b.text().includes('返回列表'))!.trigger('click')
    await flushPromises()
    expect(window.location.search).not.toContain('recordId')
    expect(wrapper.findAll('.rec-card')).toHaveLength(1)
    wrapper.unmount()
  })

  it('连不上题库时给一句能照做的说明，而不是白屏', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('boom') }))

    const wrapper = mount(FullView, {
      props: { query: { pane: 'preview', setId: 1, sheetId: 0, recordId: 0 } },
      attachTo: document.body,
    })
    await flushPromises()

    expect(wrapper.find('.full-fallback').text()).toContain('连不上题库')
    expect(wrapper.find('.full-fallback').text()).toContain('重试')
    wrapper.unmount()
  })
})
