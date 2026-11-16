import type { MarkmapNode } from '@markmap/core'
import type { MindMapNode, MindMapTopic } from '../types'

/**
 * 导图资产 → markmap 节点树。
 *
 * markmap 本来吃 markdown（一条标题 = 一个根），但我们的正文里满是「（2012）」「、」「·」
 * 这类字符，走 markdown 得先转义一遍；而它内部的节点结构其实很简单，直接构造更稳、还省掉
 * 整个 @markmap/parse（约 69KB gzip）。
 *
 * 多根怎么办：资产里一页书常有好几个「中心主题」（中位数 5 个，最多 22 个），markmap 的
 * 多根会被竖着串成一列、整图又高又窄。这里把**主题名做成可见根**，原来那些根降成第一层子节点，
 * 分支才会横着铺开。
 */

const SEP = '#'

/** 图上节点的 id：`<主题>#<path>`；根固定是 `<主题>#root` */
export function graphNodeId(topicId: string, path: number[]): string {
  return `${topicId}${SEP}${path.join('.')}`
}

export function graphRootId(topicId: string): string {
  return `${topicId}${SEP}root`
}

/** 从图上点中的 id 反推大纲里的 path；根或不认识的 id 返回 null */
export function pathOfGraphNode(topicId: string, id: string): number[] | null {
  if (!id.startsWith(`${topicId}${SEP}`)) return null
  const tail = id.slice(topicId.length + SEP.length)
  if (!tail || tail === 'root') return null
  const out: number[] = []
  for (const part of tail.split('.')) {
    const n = Number(part)
    if (!Number.isInteger(n) || n < 0) return null
    out.push(n)
  }
  return out.length ? out : null
}

function inline(value: string): MarkmapNode['content'] {
  return [{ type: 'text', value }]
}

function child(node: MindMapNode, topicId: string, path: number[]): MarkmapNode {
  return {
    id: graphNodeId(topicId, path),
    type: 'listItem',
    content: inline(node.text || '（空行）'),
    notes: [],
    data: {},
    children: (node.children ?? []).map((kid, i) => child(kid, topicId, [...path, i])),
  }
}

export function topicToGraph(topic: MindMapTopic): MarkmapNode {
  return {
    id: graphRootId(topic.id),
    type: 'heading',
    content: inline(topic.name || '（未命名主题）'),
    notes: [],
    data: { depth: 1 },
    children: topic.nodes.map((node, i) => child(node, topic.id, [i])),
  }
}

/** 主题下的节点总数（不含合成根），给工具栏上的「N 节点」用 */
export function graphNodeCount(topic: MindMapTopic): number {
  const walk = (nodes: MindMapNode[]): number =>
    nodes.reduce((n, x) => n + 1 + walk(x.children ?? []), 0)
  return walk(topic.nodes)
}

/**
 * 内容签名：主题改动后要不要重画，靠它判断。
 * 只拼文本和层级，比深比较便宜，也不会被 Vue 的响应式代理绊住。
 */
export function topicSignature(topic: MindMapTopic): string {
  const walk = (nodes: MindMapNode[], depth: number): string =>
    nodes.map((x) => `${depth}:${x.text}/${walk(x.children ?? [], depth + 1)}`).join('|')
  return `${topic.id}~${topic.name}~${walk(topic.nodes, 0)}`
}
