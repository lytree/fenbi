import { describe, expect, it } from 'vitest'
import type { MindMapTopic } from '../types'
import {
  graphNodeCount, graphNodeId, graphRootId, pathOfGraphNode, topicSignature, topicToGraph,
} from '../mindmap/tree'

/**
 * 图跟大纲之间的对位关系：markmap 只认 id，点图上的节点要靠 id 反推回大纲里那一行的
 * path（`0.1.2` 这种索引链）。这套 id 一旦不稳定，点节点就会跳错行，所以单拎出来钉住。
 */

const topic = (): MindMapTopic => ({
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
      ],
    },
    { text: '第二个中心主题' },
  ],
  pairs: [],
})

describe('topicToGraph', () => {
  it('主题名做根，原来的中心主题降成第一层', () => {
    const root = topicToGraph(topic())
    expect(root.id).toBe(graphRootId('mm02'))
    expect(root.type).toBe('heading')
    expect(root.content).toEqual([{ type: 'text', value: '党性与从严治吏' }])
    expect(root.children).toHaveLength(2)
    expect(root.children.map((c) => c.id)).toEqual([
      graphNodeId('mm02', [0]),
      graphNodeId('mm02', [1]),
    ])
    expect(root.children.every((c) => c.type === 'listItem')).toBe(true)
  })

  it('节点 id 按 path 逐级编号，跨主题不撞车', () => {
    const root = topicToGraph(topic())
    const a = root.children[0]
    expect(a.children.map((c) => c.id)).toEqual(['mm02#0.0', 'mm02#0.1'])
    expect(a.children[0].children.map((c) => c.id)).toEqual(['mm02#0.0.0'])
    const other = topicToGraph({ ...topic(), id: 'mm03' })
    expect(other.children[0].id).toBe('mm03#0')
  })

  it('空主题也画得出来，不会因为没名字 / 没节点崩', () => {
    const root = topicToGraph({ ...topic(), name: '', nodes: [] })
    expect(root.content).toEqual([{ type: 'text', value: '（未命名主题）' }])
    expect(root.children).toEqual([])
  })
})

describe('pathOfGraphNode', () => {
  it('把图上的 id 还原成索引链', () => {
    expect(pathOfGraphNode('mm02', 'mm02#0.1')).toEqual([0, 1])
    expect(pathOfGraphNode('mm02', 'mm02#3')).toEqual([3])
  })

  it('根、别的主题、坏尾巴都返回 null', () => {
    expect(pathOfGraphNode('mm02', 'mm02#root')).toBeNull()
    expect(pathOfGraphNode('mm02', 'mm03#0')).toBeNull()
    expect(pathOfGraphNode('mm02', 'mm02#a.b')).toBeNull()
    expect(pathOfGraphNode('mm02', 'mm02#')).toBeNull()
  })
})

describe('计数与签名', () => {
  it('节点数 = 所有后代，不含合成根', () => {
    expect(graphNodeCount(topic())).toBe(5)
  })

  it('改一个字签名就变，原样不动签名不变', () => {
    const a = topicSignature(topic())
    expect(topicSignature(topic())).toBe(a)
    const edited = topic()
    const branch = edited.nodes[0].children?.[1]
    if (branch) branch.text = '分支 B（改）'
    expect(topicSignature(edited)).not.toBe(a)
  })
})
