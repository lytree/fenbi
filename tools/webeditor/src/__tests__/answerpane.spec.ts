import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AnswerPane from '../components/AnswerPane'
import { currentQuestions, state } from '../store'

/**
 * 答案解析的两种外框：
 * - 编辑器右栏（窄）：资料题上下排（材料卡 + 小题成组）；
 * - 整页查看（`full`）：与卷面预览 / 练习记录同一块左右分屏——左边材料白卡、
 *   中间拖把手、右边题号页签 + 这一组的小题连着排（点页签滚过去，不是一题一页）。
 */

// 测试里不逐个字段对齐 Question 的联合类型：给一份够用的形状就行
const Q = (extra: Record<string, unknown>): any => ({
  _uid: Math.random(),
  type: 'single',
  subject: '资料分析',
  material: '',
  images: '',
  stem: '题干',
  options: [{ key: 'A', text: '甲' }, { key: 'B', text: '乙' }],
  answer: 'B',
  analysis: '解析',
  knowledge: '',
  difficulty: '',
  region: '',
  source: '',
  year: '',
  score: '',
  keywords: '',
  optionLayout: '',
  note: '',
  disabled: false,
  ...extra,
})

beforeEach(() => {
  state.paper = {
    id: 1,
    name: '测试卷',
    sheets: [{
      id: 11,
      name: '行测',
      questions: [
        Q({ id: 101, stem: '独立题' }),
        Q({ id: 102, material: '材料正文', stem: '第一小题' }),
        Q({ id: 103, material: '材料正文', stem: '第二小题' }),
      ],
    }],
  }
  state.sheet = 0
  state.focused = null
})

describe('答案解析的资料分组', () => {
  it('编辑器右栏：资料与小题成组，但不分屏', () => {
    const wrapper = mount(AnswerPane)
    const group = wrapper.find('.pv-group')
    expect(group.exists()).toBe(true)
    expect(group.findAll('.pv-q')).toHaveLength(2)
    expect(wrapper.find('.pp-split').exists()).toBe(false)
    wrapper.unmount()
  })

  it('整页查看：左材料、右题号页签 + 小题连着排（与卷面预览同一块）', () => {
    const wrapper = mount(AnswerPane, { props: { full: true } })
    const split = wrapper.find('.pp-split')
    expect(split.exists()).toBe(true)

    // 左：材料白卡 + 「材料」小签 + 正文
    expect(split.find('.pv-mat-tag').text()).toBe('材料')
    expect(split.find('.pp-mat-body').text()).toContain('材料正文')

    // 右：两道小题 → 两枚页签，默认第一题亮
    expect(split.findAll('.pv-tabs .pv-tab')).toHaveLength(2)
    expect(split.find('.pv-tabs .pv-tab.active').text()).toBe('2题')

    // 小题是一条流：两道都在右栏里，不是一题一页
    const cards = split.findAll('.pp-qs-body .pv-q')
    expect(cards).toHaveLength(2)
    expect(split.find('.pp-qs-body').text()).toContain('第一小题')
    expect(split.find('.pp-qs-body').text()).toContain('第二小题')
    // 答案解析该有的东西（答案宫格 / 解析盒）也在
    expect(split.find('.ov-grid').exists()).toBe(true)
    expect(split.findAll('.sol-box').length).toBe(2)
    wrapper.unmount()
  })

  it('点第二枚题号页签：页签亮到第二题（右栏自己滚过去，小题不换）', async () => {
    const wrapper = mount(AnswerPane, { props: { full: true } })
    const tabs = wrapper.findAll('.pp-split .pv-tabs .pv-tab')
    await tabs[1].trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.pp-split .pv-tab.active').text()).toBe('3题')
    // 两道小题都还在右栏里，点页签只挪视口
    expect(wrapper.findAll('.pp-qs-body .pv-q')).toHaveLength(2)
    wrapper.unmount()
  })

  it('整页查看：底部也有一条答题卡（没有作答进度，只写题数）', () => {
    const wrapper = mount(AnswerPane, { props: { full: true } })
    const dock = wrapper.find('.paper-dock')
    expect(dock.exists()).toBe(true)
    expect(dock.find('.dt-sub').text()).toBe('3 题')
    // 缺答案提示由页头负责，这里不再重复
    expect(dock.find('.dock-miss').exists()).toBe(false)
    wrapper.unmount()
  })

  it('答题卡跳到材料组里的小题：那个组的页签跟着切过去', async () => {
    const wrapper = mount(AnswerPane, { props: { full: true } })
    await wrapper.find('.dock-tag').trigger('click')
    // 第 3 题（下标 2）是这一组的第二小题
    await wrapper.findAll('.paper-dock .dock-cell')[2].trigger('click')
    await wrapper.vm.$nextTick()

    expect(state.focused).toBe(currentQuestions()[2]._uid)
    expect(wrapper.find('.pp-split .pv-tab.active').text()).toBe('3题')
    // 当前题在右栏里带 focus 高亮（跳过去要看得见是哪一道）
    expect(wrapper.find('.pp-split .pp-qs-body .pv-q.focus').text()).toContain('第二小题')
    wrapper.unmount()
  })

  it('编辑器右栏不挂答题卡（那条是钉在整页底边的）', () => {
    const wrapper = mount(AnswerPane)
    expect(wrapper.find('.paper-dock').exists()).toBe(false)
    wrapper.unmount()
  })
})
