import { defineComponent, onBeforeUnmount, onMounted, ref, watch, type PropType } from 'vue'
import type { Markmap } from '@markmap/view'
import type { MindMapTopic } from '../types'
import { createGraph, graphOptions, type GraphMode } from '../mindmap/render'
import { pathOfGraphNode, topicSignature, topicToGraph } from '../mindmap/tree'

/**
 * 只读导图：把一页书画成折叠 / 缩放 / 拖动的图。
 *
 * 编辑仍然只在大纲里做——这里不做双向同步，避免出现「图改了字、大纲没跟着变」的两套真相。
 * 点节点只做一件事：把 path 抛给父级，切回大纲并定位到那一行。
 */
export default defineComponent({
  name: 'MindMapGraph',
  props: {
    topic: { type: Object as PropType<MindMapTopic>, required: true },
    mode: { type: String as PropType<GraphMode>, default: 'light' },
  },
  emits: {
    pick: (path: number[]) => Array.isArray(path),
  },
  setup(props, { emit, expose }) {
    const svg = ref<SVGSVGElement | null>(null)
    let mm: Markmap | null = null
    let observer: ResizeObserver | null = null
    let raf = 0

    /** 换主题 / 改内容后都要重排一次；等浏览器量完尺寸再 fit，否则拿到的是 0 */
    const refit = () => {
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(() => mm?.fit())
    }

    const draw = () => {
      if (!mm) return
      mm.setTree(topicToGraph(props.topic), graphOptions(props.mode))
      mm.foldToLevel(2)
      refit()
    }

    onMounted(() => {
      const el = svg.value
      if (!el) return
      mm = createGraph(el, topicToGraph(props.topic), props.mode)
      mm.foldToLevel(2)
      refit()

      // 侧栏收起 / 窗口缩放后画布尺寸变了，图要重新适应，否则停在上一个比例
      if (typeof ResizeObserver !== 'undefined') {
        observer = new ResizeObserver(() => refit())
        observer.observe(el)
      }
    })

    watch(() => topicSignature(props.topic), draw)
    watch(() => props.mode, () => {
      if (!mm) return
      mm.setOptions(graphOptions(props.mode))
      refit()
    })

    onBeforeUnmount(() => {
      cancelAnimationFrame(raf)
      observer?.disconnect()
      mm?.destroy()
      mm = null
    })

    const onClick = (e: MouseEvent) => {
      if (!mm) return
      const [x, y] = mm.layoutPoint(e.clientX, e.clientY)
      const hit = mm.hitAt(x, y)
      // 折叠圆点也是 nodeId，但它自己会 toggle，别把用户弹回大纲
      if (!hit || hit.kind !== 'node') return
      const path = pathOfGraphNode(props.topic.id, hit.nodeId)
      if (path) emit('pick', path)
    }

    expose({
      fit: () => mm?.fit(),
      foldToLevel: (level: number) => mm?.foldToLevel(level),
      expandAll: () => mm?.expandAll(),
    })

    return () => (
      <svg
        ref={svg}
        class="mm-graph-svg"
        role="img"
        aria-label={`思维导图：${props.topic.name}`}
        onClick={onClick}
      />
    )
  },
})
