import { describe, expect, it, beforeEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import RecordsPane from '../components/RecordsPane'
import { state } from '../store'

/**
 * 练习记录详情的两个外框：
 * - 编辑器右栏：资料题仍是「材料卡 + 小题成组」的上下排；
 * - 整页查看（`standalone`）：与卷面预览 / 答案解析同一块左右分屏——
 *   左边材料白卡、右边题号页签 + 这一组的小题连着排。
 *
 * 数据面打桩 fetch（记录接口是只读的），验的是版式与页签。
 */

const A = (no: number, extra: Record<string, unknown> = {}): any => ({
  id: 500 + no,
  no,
  type: 'single',
  stem: `第 ${no} 题`,
  material: '',
  options: [{ key: 'A', text: '甲' }, { key: 'B', text: '乙' }],
  answer: 'B',
  analysis: `第 ${no} 题的解析`,
  knowledge: '',
  note: '',
  userAnswer: 'B',
  correct: true,
  partial: false,
  pending: false,
  earned: 1,
  full: 1,
  ...extra,
})

const RECORD = {
  id: 7,
  paperId: 11,
  name: '行测 · 第 3 次',
  paperName: '行测',
  mode: '练习',
  status: 'done',
  startedAt: 1763000000000,
  submittedAt: 1763003600000,
  durationSec: 600,
  total: 3,
  answered: 3,
  correct: 2,
  partial: 0,
  score: 0,
  fullScore: 0,
  answers: [
    A(1),
    // 第 2、3 题共用一则材料：整页里要并成一块分屏
    A(2, { material: '材料正文' }),
    A(3, { material: '材料正文', correct: false, userAnswer: 'A' }),
  ],
}

const json = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  Element.prototype.scrollIntoView = () => {}
  state.paper = null
  state.focused = null
  vi.stubGlobal('fetch', vi.fn(async (input: any) => {
    const url = String(input)
    if (url.startsWith('/api/record?')) return json(RECORD)
    if (url.startsWith('/api/records')) return json({ records: [RECORD], total: 1 })
    return json({ error: 'unexpected ' + url })
  }))
})

describe('练习记录详情的资料题', () => {
  it('整页查看：左材料、右题号页签 + 小题连着排（与卷面预览同一块）', async () => {
    const wrapper = mount(RecordsPane, { props: { standalone: true, initialRecordId: 7 } })
    await flushPromises()
    await flushPromises()

    const split = wrapper.find('.pp-split')
    expect(split.exists()).toBe(true)
    expect(split.find('.pv-mat-tag').text()).toBe('材料')
    expect(split.find('.pp-mat-body').text()).toContain('材料正文')

    // 两道小题 → 两枚页签（题号按记录里的原题号），默认第一题亮
    const tabs = split.findAll('.pv-tabs .pv-tab')
    expect(tabs.map((t) => t.text())).toEqual(['2题', '3题'])
    expect(split.find('.pv-tab.active').text()).toBe('2题')

    // 右栏是一条流：对错色、答案对照、解析都还在
    const cards = split.findAll('.pp-qs-body .pv-q')
    expect(cards).toHaveLength(2)
    expect(cards[0].attributes('data-uid')).toBe('2')
    expect(cards[0].classes()).toContain('r-ok')
    expect(cards[1].classes()).toContain('r-no')
    expect(split.text()).toContain('正确答案')
    expect(split.text()).toContain('第 3 题的解析')
    wrapper.unmount()
  })

  it('点第二枚题号页签：页签亮到第三题，小题都留在右栏里', async () => {
    const wrapper = mount(RecordsPane, { props: { standalone: true, initialRecordId: 7 } })
    await flushPromises()
    await flushPromises()

    await wrapper.findAll('.pp-split .pv-tab')[1].trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.pp-split .pv-tab.active').text()).toBe('3题')
    expect(wrapper.findAll('.pp-qs-body .pv-q')).toHaveLength(2)
    wrapper.unmount()
  })

  it('底部答题卡跳到材料组里的小题：把它带到眼前（不换成别的题）', async () => {
    const wrapper = mount(RecordsPane, { props: { standalone: true, initialRecordId: 7 } })
    await flushPromises()
    await flushPromises()

    await wrapper.find('.paper-dock .dock-tag').trigger('click')
    await wrapper.findAll('.paper-dock .dock-cell')[2].trigger('click')
    await wrapper.vm.$nextTick()

    // 三题都在卷面上，跳题只挪视口：右栏里仍是那两道小题
    expect(wrapper.findAll('.pp-qs-body .pv-q')).toHaveLength(2)
    wrapper.unmount()
  })

  it('编辑器右栏不分屏：资料与小题还是上下成组', async () => {
    const wrapper = mount(RecordsPane, { props: { initialRecordId: 7 } })
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('.pp-split').exists()).toBe(false)
    expect(wrapper.find('.pv-group').exists()).toBe(true)
    wrapper.unmount()
  })
})
