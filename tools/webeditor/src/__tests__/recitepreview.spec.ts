import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import RecitePreview from '../components/RecitePreview'
import { mindmap, recite } from '../store'
import type { MindMapCatalog, ReciteCatalog } from '../types'

/**
 * 背诵模式右栏预览：卡片正反面、词条释义里的表格、导图主题。
 *
 * 这一栏是「只读照镜子」——不维护自己的选中状态，点开左边哪张就看哪张，
 * 所以这里钉的是三件事：取到的是不是当前那条、翻面会不会真的换内容、表格有没有排出来。
 */

/** 每个用例结束就卸载：预览挂着 watch，残留组件会被下一个用例的状态改动带着重渲染 */
enableAutoUnmount(afterEach)

const catalog = (): ReciteCatalog => ({
  subject: 'politics',
  title: '政治理论',
  intro: '',
  updated: '2026-11-15',
  hasEdits: false,
  categories: [{ id: 'c1', name: '宪法', group: '宪法法律' }],
  cards: [
    {
      id: 'k1',
      cat: 'c1',
      text: '我国现行宪法是 ____ 年颁布的，共 ____ 章。',
      answers: ['1982', '四'],
      note: '1982 年 12 月 4 日通过',
      source: '超格 · 宪法第一节',
    },
    {
      id: 'k2',
      cat: 'c1',
      text: '第二张卡：只有一段没有填空的知识点。',
      answers: [],
      note: '',
      source: '',
    },
  ],
})

const mindMapCatalog = (): MindMapCatalog => ({
  version: 2,
  title: '政治理论思维导图大盘点',
  intro: '',
  updated: '2026-11-15',
  hasEdits: false,
  sections: [
    {
      id: 's01',
      name: '党的建设与全面从严治党',
      topics: [
        {
          id: 'mm02',
          label: '书页 02',
          name: '党性与从严治吏',
          nodes: [{ text: '增强党性是根本性任务', children: [{ text: '把党性修养作为终身课题' }] }],
          pairs: [{ left: '坚持党的领导', right: '是中国特色社会主义最本质的特征' }],
        },
      ],
    },
  ],
})

beforeEach(() => {
  recite.subject = 'politics'
  recite.catalog = catalog()
  recite.expanded = []
  recite.query = ''
  recite.catFilter = 'all'
  mindmap.catalog = null
  mindmap.openTopics = []
})

describe('RecitePreview', () => {
  it('默认看第一条：正面是编号空位，不露答案', () => {
    const w = mount(RecitePreview)
    expect(w.text()).toContain('宪法')
    expect(w.findAll('.rcp-blank')).toHaveLength(2)
    expect(w.find('.rcp-body').html()).not.toContain('1982')
    // 答案清单背面才出现（备注照旧在下面）
    expect(w.find('.rcp-answers').exists()).toBe(false)
    // 备注与来源照 App 的样子附在下面
    expect(w.text()).toContain('1982 年 12 月 4 日通过')
    expect(w.text()).toContain('超格 · 宪法第一节')
  })

  it('点「看答案」把答案填进空位，再点翻回正面', async () => {
    const w = mount(RecitePreview)
    await w.find('.rcp-flip').trigger('click')
    expect(w.find('.rcp-body').html()).toContain('1982')
    expect(w.findAll('.rcp-answers .rcp-answer')).toHaveLength(2)

    await w.find('.rcp-flip').trigger('click')
    expect(w.find('.rcp-body').html()).not.toContain('1982')
    expect(w.find('.rcp-answers').exists()).toBe(false)
  })

  it('展开哪张就看哪张（无填空的卡不显示翻面按钮）', () => {
    recite.expanded = ['k2']
    const w = mount(RecitePreview)
    expect(w.text()).toContain('第二张卡')
    expect(w.find('.rcp-flip').exists()).toBe(false)
  })

  it('卡片正文里的表格排成真表格', () => {
    recite.catalog!.cards[0].text = '| 项目 | 值 |\n| --- | --- |\n| 颁布年 | ____ |'
    const w = mount(RecitePreview)
    expect(w.find('table.pv-tbl').exists()).toBe(true)
    expect(w.find('table.pv-tbl').text()).toContain('颁布年')
  })

  it('切到导图：默认按 App 的样子先给图，连线配对单列', () => {
    recite.subject = 'mindmap'
    mindmap.catalog = mindMapCatalog()
    const w = mount(RecitePreview)
    expect(w.text()).toContain('党的建设与全面从严治党')
    expect(w.text()).toContain('党性与从严治吏')
    // 图是 App 里那块画布，默认就在
    expect(w.find('.rcp-graph svg.mm-graph-svg').exists()).toBe(true)
    expect(w.text()).toContain('坚持党的领导')
    expect(w.text()).toContain('是中国特色社会主义最本质的特征')
  })

  it('切到「大纲」：按层级排树，默认摊到第二层', async () => {
    recite.subject = 'mindmap'
    mindmap.catalog = mindMapCatalog()
    const w = mount(RecitePreview)
    await w.find('[data-view="outline"]').trigger('click')
    expect(w.find('.rcp-graph').exists()).toBe(false)
    expect(w.text()).toContain('增强党性是根本性任务')
    // 默认摊到第二层，子节点直接可见
    expect(w.text()).toContain('把党性修养作为终身课题')
  })

  it('导图主题可以逐级收起', async () => {
    recite.subject = 'mindmap'
    mindmap.catalog = mindMapCatalog()
    const w = mount(RecitePreview)
    await w.find('[data-view="outline"]').trigger('click')
    await w.findAll('.rcp-twist')[0].trigger('click')
    expect(w.text()).not.toContain('把党性修养作为终身课题')
  })

  it('图上点节点：切回大纲并把那一支展开', async () => {
    recite.subject = 'mindmap'
    mindmap.catalog = mindMapCatalog()
    const w = mount(RecitePreview)
    // 收起子节点后点图上的它，大纲应该自己展开
    await w.find('[data-view="outline"]').trigger('click')
    await w.findAll('.rcp-twist')[0].trigger('click')
    await w.find('[data-view="graph"]').trigger('click')
    const graph = w.findComponent({ name: 'MindMapGraph' })
    graph.vm.$emit('pick', [0, 0])
    await w.vm.$nextTick()
    await w.vm.$nextTick()
    expect(w.find('.rcp-graph').exists()).toBe(false)
    expect(w.text()).toContain('把党性修养作为终身课题')
  })
})
