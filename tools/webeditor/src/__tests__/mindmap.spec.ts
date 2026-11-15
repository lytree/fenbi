import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MindMapPane from '../components/MindMapPane'
import {
  addMindMapChild, addMindMapSibling, indentMindMapNode, mindMapIssue, mindMapLocate,
  mindmap, moveMindMapNode, outdentMindMapNode, removeMindMapNode, toggleMindMapTopic,
} from '../store'
import type { MindMapCatalog } from '../types'

/**
 * 导图树的编辑逻辑：路径定位、缩进 / 反缩进、同级移动、保存前校验。
 *
 * 树的层级只靠 children 表达，所有编辑都是「按索引链找到节点 → 改它所在的兄弟数组」，
 * 索引链算错一个位置，改的就不是用户看着的那一行，所以这里逐条钉住。
 */

const catalog = (): MindMapCatalog => ({
  version: 2,
  title: '政治理论思维导图大盘点',
  intro: '',
  updated: '2026-11-15',
  hasEdits: false,
  topicCount: 1,
  nodeCount: 5,
  sections: [
    {
      id: 's01',
      name: '党的建设与全面从严治党',
      topics: [
        {
          id: 'mm02',
          page: 4,
          label: '书页 02',
          name: '党性与从严治吏',
          nodes: [
            {
              text: '中心主题',
              children: [
                { text: '分支 A', children: [{ text: '要点 A1' }] },
                { text: '分支 B' },
                { text: '分支 C' },
              ],
            },
          ],
          pairs: [],
        },
      ],
    },
  ],
})

const topic = () => mindmap.catalog!.sections[0].topics[0]

beforeEach(() => {
  mindmap.catalog = catalog()
  mindmap.dirty = false
})

describe('mindMapLocate', () => {
  it('能按索引链取到节点', () => {
    const first = mindMapLocate(topic().nodes, [0])
    expect(first?.list[first.index].text).toBe('中心主题')

    const deep = mindMapLocate(topic().nodes, [0, 0, 0])
    expect(deep?.list[deep.index].text).toBe('要点 A1')
  })

  it('越界返回 null，不会把 undefined 当节点', () => {
    expect(mindMapLocate(topic().nodes, [])).toBeNull()
    expect(mindMapLocate(topic().nodes, [9])).toBeNull()
    expect(mindMapLocate(topic().nodes, [0, 9])).toBeNull()
  })
})

describe('缩进 / 反缩进', () => {
  it('缩进把节点挂到上一个兄弟下面，并返回新路径', () => {
    // 挂上去以后是「分支 B」的第一个子节点
    const next = indentMindMapNode('mm02', [0, 2])
    expect(next).toEqual([0, 1, 0])
    const branches = topic().nodes[0].children!
    expect(branches.map((n) => n.text)).toEqual(['分支 A', '分支 B'])
    expect(branches[1].children?.map((n) => n.text)).toEqual(['分支 C'])
    expect(mindmap.dirty).toBe(true)
  })

  it('第一个兄弟没有可挂的父节点，缩进无效', () => {
    expect(indentMindMapNode('mm02', [0, 0])).toBeNull()
    expect(topic().nodes[0].children!.map((n) => n.text)).toEqual(['分支 A', '分支 B', '分支 C'])
  })

  it('反缩进把节点升成父节点的兄弟，排在父节点后面', () => {
    const next = outdentMindMapNode('mm02', [0, 0, 0])
    expect(next).toEqual([0, 1])
    const top = topic().nodes[0].children!
    expect(top.map((n) => n.text)).toEqual(['分支 A', '要点 A1', '分支 B', '分支 C'])
    expect(top[0].children ?? []).toEqual([])
  })

  it('顶层节点没有父可反缩进', () => {
    expect(outdentMindMapNode('mm02', [0])).toBeNull()
  })
})

describe('同级移动', () => {
  it('下移把两个兄弟换位', () => {
    expect(moveMindMapNode('mm02', [0, 0], 1)).toEqual([0, 1])
    expect(topic().nodes[0].children!.map((n) => n.text)).toEqual(['分支 B', '分支 A', '分支 C'])
  })

  it('到头就不再动', () => {
    expect(moveMindMapNode('mm02', [0, 0], -1)).toBeNull()
    expect(moveMindMapNode('mm02', [0, 2], 1)).toBeNull()
    expect(topic().nodes[0].children!.map((n) => n.text)).toEqual(['分支 A', '分支 B', '分支 C'])
  })
})

describe('增删节点', () => {
  it('加子节点挂在末尾并把空节点返回给界面聚焦', () => {
    const path = addMindMapChild('mm02', [0, 1])
    expect(path).toEqual([0, 1, 0])
    expect(topic().nodes[0].children![1].children).toEqual([{ text: '' }])
  })

  it('加同级节点插在当前节点后面', () => {
    const path = addMindMapSibling('mm02', [0, 1])
    expect(path).toEqual([0, 2])
    expect(topic().nodes[0].children!.map((n) => n.text)).toEqual(['分支 A', '分支 B', '', '分支 C'])
  })

  it('删节点连同子树一起没了', () => {
    removeMindMapNode('mm02', [0, 0])
    const branches = topic().nodes[0].children!
    expect(branches.map((n) => n.text)).toEqual(['分支 B', '分支 C'])
    expect(mindmap.dirty).toBe(true)
  })
})

describe('保存前校验', () => {
  it('内容齐全时通过', () => {
    expect(mindMapIssue(mindmap.catalog!)).toBeNull()
  })

  it('空白节点会被拦下来（改了一半直接保存的状态）', () => {
    topic().nodes[0].children!.push({ text: '  ' })
    expect(mindMapIssue(mindmap.catalog!)).toContain('空白节点')
  })

  it('主题没有节点、板块没有主题都拦', () => {
    const cat = mindmap.catalog!
    cat.sections[0].topics[0].nodes = []
    expect(mindMapIssue(cat)).toContain('至少要有一个节点')

    cat.sections[0].topics = []
    expect(mindMapIssue(cat)).toContain('至少要有一个主题')
  })
})

describe('MindMapPane 渲染', () => {
  it('按板块分段，主题卡默认收起，展开后按层级出树行', async () => {
    mindmap.catalog = catalog()
    mindmap.openTopics = []
    mindmap.openNodes = {}
    const w = mount(MindMapPane)

    expect(w.findAll('.mm-section')).toHaveLength(1)
    expect(w.text()).toContain('党的建设与全面从严治党')
    expect(w.findAll('.mm-card')).toHaveLength(1)
    // 收起状态下一行树都不渲染（一页 40 多条要点，全渲染会卡）
    expect(w.findAll('.mm-row')).toHaveLength(0)

    toggleMindMapTopic('mm02')
    await w.vm.$nextTick()
    // 默认摊开到第 2 层：中心主题 + 3 个分支 + 分支 A 的要点 = 5 行
    expect(w.findAll('.mm-row')).toHaveLength(5)
    // 行里是 textarea，文字不在 text() 里，得读 value
    const texts = w.findAll('textarea.mm-text').map((t) => (t.element as HTMLTextAreaElement).value)
    expect(texts).toContain('要点 A1')

    // 收起「分支 A」这一支，它下面的要点跟着收起
    const twist = w.findAll('.mm-twist')[1]
    await twist.trigger('click')
    expect(w.findAll('.mm-row')).toHaveLength(4)
  })

  it('搜索只留命中的主题', async () => {
    mindmap.catalog = catalog()
    mindmap.openTopics = []
    const w = mount(MindMapPane)
    const search = w.find('input.search')
    await search.setValue('分支 B')
    expect(w.findAll('.mm-card')).toHaveLength(1)

    await search.setValue('不存在的词')
    expect(w.findAll('.mm-card')).toHaveLength(0)
    expect(w.text()).toContain('没有命中的主题')
  })
})
