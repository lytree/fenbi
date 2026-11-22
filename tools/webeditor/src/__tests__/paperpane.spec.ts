import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import PreviewPane from '../components/PreviewPane'
import { state } from '../store'

/**
 * 卷面预览照粉笔「试卷作答页」排：分章（章头 + 题数 + 作答说明）、
 * 每章一串题卡、资料题左右分屏、底部一条可展开的答题卡。
 * 这里验结构这一层：章切得对不对、资料是不是左材料右小题、答题卡能不能跳题。
 */

const Q = (extra: Record<string, unknown>): any => ({
  _uid: 0,
  type: 'single',
  subject: '',
  material: '',
  images: '',
  stem: '题干',
  options: [{ key: 'A', text: '甲' }, { key: 'B', text: '乙' }],
  answer: 'B',
  analysis: '',
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
        Q({ id: 101, _uid: 1, subject: '言语理解与表达', stem: '言语题' }),
        Q({ id: 102, _uid: 2, subject: '资料分析', material: '材料正文', stem: '第一小题' }),
        Q({ id: 103, _uid: 3, subject: '资料分析', material: '材料正文', stem: '第二小题' }),
        // 故意留一道没答案：答题卡上要标出来
        Q({ id: 104, _uid: 4, subject: '判断推理', stem: '判断题', answer: '' }),
      ],
    }],
  }
  state.sheet = 0
  state.focused = null
})

describe('卷面预览（粉笔作答页版式）', () => {
  it('按科目切章：章头写题数，说明只在认得出的科目上出现', () => {
    const wrapper = mount(PreviewPane)
    const names = wrapper.findAll('.pv-chapter .ch-name').map((n) => n.text().replace(/\s+/g, ''))
    expect(names).toEqual(['言语理解与表达（1题）', '资料分析（2题）', '判断推理（1题）'])

    // 资料分析这一章带粉笔的作答说明；认不出的科目不出这一行
    const chapters = wrapper.findAll('.pv-chapter')
    expect(chapters[1].find('.ch-desc').text()).toContain('所给出的图、表、文字或综合性资料')
    expect(chapters[2].find('.ch-desc').exists()).toBe(true)

    expect(wrapper.findAll('.paper .pv-q')).toHaveLength(4)
    // 题号按卷面顺序走，不按章重头数
    expect(wrapper.findAll('.paper .pv-q .pv-no').map((n) => n.text())).toEqual(['1.', '2.', '3.', '4.'])
    wrapper.unmount()
  })

  it('资料题：左边材料白卡、右边题号页签 + 小题连着排', () => {
    const wrapper = mount(PreviewPane)
    const split = wrapper.find('.pp-split')
    expect(split.exists()).toBe(true)

    expect(split.find('.pv-mat-tag').text()).toBe('材料')
    expect(split.find('.pp-mat-body').text()).toContain('材料正文')

    expect(split.findAll('.pv-tabs .pv-tab')).toHaveLength(2)
    expect(split.find('.pv-tab').text()).toBe('2题')
    // 作答页是「右栏一串小题」，不是解析页那样一次只摆一道
    expect(split.findAll('.pp-qs-body .pv-q')).toHaveLength(2)
    expect(split.find('.pp-qs-body').text()).toContain('第一小题')
    expect(split.find('.pp-qs-body').text()).toContain('第二小题')
    wrapper.unmount()
  })

  it('点题卡：把这一题设成当前题，左栏跟着走', async () => {
    const wrapper = mount(PreviewPane)
    const third = wrapper.findAll('.paper .pv-q')[2]
    await third.trigger('click')
    expect(state.focused).toBe(3)
    // 高亮靠类名贴，不重建整卷
    expect(third.classes()).toContain('focus')
    wrapper.unmount()
  })

  it('答题卡：收起时只有标签，展开按科目铺题号，缺答案的格子标出来', async () => {
    const wrapper = mount(PreviewPane)
    const dock = wrapper.find('.paper-dock')
    expect(dock.exists()).toBe(true)
    expect(dock.find('.dt-sub').text()).toContain('0')
    expect(dock.find('.dt-sub').text()).toContain('4')
    expect(dock.find('.dock-body').exists()).toBe(false)
    // 有一题没答案：标签旁边提示缺 1 题
    expect(dock.find('.dock-miss').text()).toContain('1 题缺答案')

    await dock.find('.dock-tag').trigger('click')
    const cells = wrapper.findAll('.paper-dock .dock-cell')
    expect(cells).toHaveLength(4)
    expect(cells.map((c) => c.text())).toEqual(['1', '2', '3', '4'])
    expect(wrapper.findAll('.dock-cell.miss')).toHaveLength(1)
    expect(wrapper.findAll('.dock-cell.miss')[0].text()).toBe('4')
    wrapper.unmount()
  })

  it('点答题卡上的题号：滚到那道题并设为当前题', async () => {
    const wrapper = mount(PreviewPane)
    await wrapper.find('.dock-tag').trigger('click')
    await wrapper.findAll('.dock-cell')[2].trigger('click')
    expect(state.focused).toBe(3)
    wrapper.unmount()
  })

  it('题号行不再挂笔记笔：卷面预览只校样', () => {
    const wrapper = mount(PreviewPane)
    expect(wrapper.find('.pv-tagrow .pv-acts').exists()).toBe(false)
    // 题号与题型标签还在
    expect(wrapper.findAll('.paper .pv-q .pv-tagrow .pv-tag').length).toBeGreaterThan(0)
    wrapper.unmount()
  })
})
