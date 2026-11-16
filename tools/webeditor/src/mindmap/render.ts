import type { MarkmapNode } from '@markmap/core'
import { createCardsTheme, treeLayout } from '@markmap/render'
import { Markmap, type ViewOptions } from '@markmap/view'

export type GraphMode = 'light' | 'dark'

/** 分支色只用编辑器自己那套紫系，不用 markmap 默认的 d3 十色（那套在淡紫纸面上很跳） */
const PALETTE: Record<GraphMode, string[]> = {
  light: ['#6f4ff2', '#8e74ff', '#5b8def', '#3aa88a', '#d98b3a', '#c2557a'],
  dark: ['#b9a2ff', '#8e74ff', '#7aa7ff', '#58c07a', '#e0a45c', '#e08ba8'],
}

/**
 * 图的渲染参数。
 *
 * theme / layout 必须传**对象**：1.x 里传字符串（`theme: 'classic'`）会直接抛
 * "layout is not a function"，那是给 markdown frontmatter 走名字注册用的。
 * 另外 org-chart 在 19 个根时会把图横向拉爆、fit 后压成一条线，tree 是这堆数据里唯一能看的。
 */
export function graphOptions(mode: GraphMode): ViewOptions {
  return {
    layout: treeLayout,
    theme: createCardsTheme({ color: PALETTE[mode], colorDepth: 2 }),
    colorScheme: mode,
    duration: 200,
    wrapWidth: 200,
    levelGap: 44,
    siblingGap: 10,
    fitPadding: 24,
    minScale: 0.12,
  }
}

export function createGraph(svg: SVGSVGElement, tree: MarkmapNode, mode: GraphMode): Markmap {
  return Markmap.create(svg, tree, graphOptions(mode))
}
