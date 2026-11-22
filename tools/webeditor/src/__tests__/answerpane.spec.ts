import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AnswerPane from '../components/AnswerPane'
import { state } from '../store'

/**
 * 答案解析的两种外框：
 * - 编辑器右栏（窄）：资料题上下排，不带分屏类；
 * - 整页查看（`full`）：资料题左右分屏（材料左、题目右）。
 * 同一份 DOM，靠 `.split` + `.fullsheet` 决定摆法，这里验类名这一层。
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
    expect(group.classes()).not.toContain('split')
    expect(group.findAll('.pv-q')).toHaveLength(2)
    wrapper.unmount()
  })

  it('整页查看：同一组带上 split，交给 CSS 做左右分屏', () => {
    const wrapper = mount(AnswerPane, { props: { full: true } })
    const group = wrapper.find('.pv-group')
    expect(group.classes()).toContain('split')
    // 组里仍是「材料在前、小题在后」：分屏只是换个摆法
    expect(group.find('.pv-mat').exists()).toBe(true)
    expect(group.findAll('.pv-subs .pv-q')).toHaveLength(2)
    wrapper.unmount()
  })
})
