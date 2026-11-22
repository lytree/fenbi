import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AnswerPane from '../components/AnswerPane'
import { state } from '../store'

/**
 * 答案解析的两种外框：
 * - 编辑器右栏（窄）：资料题上下排（材料卡 + 小题成组）；
 * - 整页查看（`full`）：照粉笔解析页左右分屏——左边材料白卡，右边题号页签
 *   + 当前这一题（一次只摆一道）。
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
    expect(wrapper.find('.pv-split').exists()).toBe(false)
    wrapper.unmount()
  })

  it('整页查看：左材料、右题号页签，一次只摆当前这一题', () => {
    const wrapper = mount(AnswerPane, { props: { full: true } })
    const split = wrapper.find('.pv-split')
    expect(split.exists()).toBe(true)

    // 左：材料白卡 + 「材料」小签 + 正文
    expect(split.find('.pv-mat-tag').text()).toBe('材料')
    expect(split.find('.pv-mat-body').text()).toContain('材料正文')

    // 右：两道小题 → 两枚页签，默认第一题
    expect(split.findAll('.pv-tabs .pv-tab')).toHaveLength(2)
    expect(split.find('.pv-tabs .pv-tab.active').text()).toBe('2题')
    expect(split.findAll('.pv-tabbody .pv-q')).toHaveLength(1)
    expect(split.find('.pv-tabbody').text()).toContain('第一小题')
    wrapper.unmount()
  })

  it('整页查看：点第二枚题号页签，右栏换成第二小题', async () => {
    const wrapper = mount(AnswerPane, { props: { full: true } })
    const tabs = wrapper.findAll('.pv-split .pv-tabs .pv-tab')
    await tabs[1].trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.pv-split .pv-tab.active').text()).toBe('3题')
    const body = wrapper.find('.pv-tabbody')
    expect(body.text()).toContain('第二小题')
    expect(body.text()).not.toContain('第一小题')
    expect(wrapper.findAll('.pv-tabbody .pv-q')).toHaveLength(1)
    wrapper.unmount()
  })
})
