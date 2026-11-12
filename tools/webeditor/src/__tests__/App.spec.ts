import { describe, expect, it, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import App from '../App'

/**
 * 冒烟测试：不连手机也能验证「打开一组 → 加题 → 发布」这条主链路。
 * 手机端服务由 fetch 打桩代替，断言的是「前端发出去的 JSON 对不对」。
 */

const QUESTION = (stem: string, extra: Record<string, unknown> = {}) => ({
  type: 'single',
  subject: '言语理解',
  material: '',
  images: '',
  stem,
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
  ...extra,
})

const SETS = {
  sets: [
    {
      id: 1, title: '2024国考', source: '导入', paperCount: 1, questionCount: 3,
      missingAnswers: 0, editable: true,
      papers: [{ id: 11, name: '行测', questionCount: 3, missingAnswers: 0 }],
    },
  ],
  questionTotal: 3,
  paperTotal: 1,
}

const PAPER = {
  id: 1,
  title: '2024国考',
  questionCount: 3,
  missingAnswers: 0,
  sheets: [
    {
      name: '行测',
      questions: [
        QUESTION('第一题：没有语病的一项'),
        QUESTION('第二题：同比增加多少', { material: '2025年1—2月，总收入5280.0亿元。', subject: '资料分析' }),
        QUESTION('第三题：占比约多少', { material: '2025年1—2月，总收入5280.0亿元。', subject: '资料分析' }),
      ],
    },
  ],
}

const VERSIONS = {
  versions: [
    { id: 'v1', title: '2024国考', reason: 'publish', savedAt: 1762000000000, paperCount: 1, questionCount: 2 },
    { id: 'v2', title: '2024国考', reason: 'restore', savedAt: 1761000000000, paperCount: 1, questionCount: 3 },
  ],
}

let published: any = null
let deleteRequested: number[] = []
let deleteCancelled: string[] = []

function mockFetch() {
  published = null
  deleteRequested = []
  deleteCancelled = []
  const fn = vi.fn(async (input: any, init?: RequestInit) => {
    const url = String(input)
    if (url.startsWith('/api/papers')) return json(SETS)
    if (url.startsWith('/api/paper')) return json(PAPER)
    if (url.startsWith('/api/versions')) return json(VERSIONS)
    if (url.startsWith('/api/publish')) {
      published = JSON.parse(String(init?.body))
      return json({
        ok: true, setId: 1, title: published.title, questionCount: 99,
        replaced: false, version: null, papers: [],
      })
    }
    if (url.startsWith('/api/delete/request')) {
      const body = JSON.parse(String(init?.body))
      deleteRequested.push(body.setId)
      return json({ requestId: 'r1', title: '2024国考', detail: '这组有 1 份卷、3 道题。', ttlSec: 600 })
    }
    if (url.startsWith('/api/delete/status')) return json({ status: 'pending' })
    if (url.startsWith('/api/delete/cancel')) {
      const body = JSON.parse(String(init?.body))
      deleteCancelled.push(body.requestId)
      return json({ ok: true })
    }
    if (url.startsWith('/api/img')) return new Response('', { status: 404 })
    return json({ error: 'unexpected ' + url }, 404)
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  Element.prototype.scrollIntoView = () => {}
  mockFetch()
})

describe('局域网题库编辑器', () => {
  it('打开第一组，渲染题目列表与卷面预览', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    expect(wrapper.text()).toContain('2024国考')
    expect(wrapper.findAll('.qcard')).toHaveLength(3)
    // 材料组：两道题共用一段材料，预览里只出现一次
    expect(wrapper.findAll('.pv-q')).toHaveLength(3)
    expect(wrapper.findAll('.pv-mat')).toHaveLength(1)
    expect(wrapper.text()).toContain('第一题：没有语病的一项')
    wrapper.unmount()
  })

  it('加一道题后发布，JSON 里带上新题', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('＋ 新题'))
    expect(addBtn).toBeTruthy()
    await addBtn!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.qcard')).toHaveLength(4)

    const publishBtn = wrapper.findAll('button').find((b) => b.text().includes('发布到 App'))
    await publishBtn!.trigger('click')
    await flushPromises()

    expect(published).toBeTruthy()
    expect(published.title).toBe('2024国考')
    expect(published.sheets[0].name).toBe('行测')
    expect(published.sheets[0].questions).toHaveLength(4)
    // 前端临时字段不能带到手机端
    expect(published.sheets[0].questions[0]._uid).toBeUndefined()
    wrapper.unmount()
  })

  it('版本面板列出历史版本，标注是发布前还是回滚前的存档', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    const tab = wrapper.findAll('button').find((b) => b.text().includes('版本'))
    expect(tab).toBeTruthy()
    await tab!.trigger('click')
    await flushPromises()

    expect(wrapper.findAll('.ver-item')).toHaveLength(2)
    expect(wrapper.text()).toContain('发布前存档')
    expect(wrapper.text()).toContain('回滚前存档')
    wrapper.unmount()
  })

  it('删除只发请求给手机，网页上不直接删', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    const del = wrapper.findAll('.side-item .ops button').find((b) => b.text() === '删除')
    expect(del).toBeTruthy()
    await del!.trigger('click')
    await flushPromises()

    const send = wrapper.findAll('button').find((b) => b.text().includes('发到手机确认'))
    expect(send).toBeTruthy()
    await send!.trigger('click')
    await flushPromises()

    expect(deleteRequested).toEqual([1])
    expect(wrapper.text()).toContain('等手机上点「确认删除」')
    wrapper.unmount()
  })

  it('题干为空时发布会被拦下（本地校验给出错误）', async () => {
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()

    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('＋ 新题'))
    await addBtn!.trigger('click')
    await flushPromises()

    const checkBtn = wrapper.findAll('button').find((b) => b.text().trim() === '校验')
    await checkBtn!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('题干为空')
    wrapper.unmount()
  })
})
