import { defineComponent, ref } from 'vue'
import QuestionCard from './QuestionCard'
import type { Group, Question } from '../types'
import {
  currentQuestions, groupAt, moveQuestionsTo, setGroupMaterial, state, visibleIndexes,
} from '../store'
import { grow } from '../util'
import { FIGURE_LAYOUTS, wrapFigure } from '../figure'

/** 材料输入框：按组首下标记，排版按钮要用它的光标位置包块 */
const areas = new Map<number, HTMLTextAreaElement>()

/** 拖动中的题目：jsdom 里没有 dataTransfer，靠它兜底 */
let dragUid: number | null = null

/**
 * 题目列表：折叠卡片 + 材料组头。
 *
 * 材料组头只在组首出现一次，改它就是改组内每一题的材料——这正是 App 需要的
 * 「材料逐行重复」，用户不用自己复制粘贴三段一样的文字。
 * 筛选 / 搜索时组头跟着首题走，不会出现「材料组浮在没有成员的空中」。
 */
export default defineComponent({
  name: 'QuestionList',
  setup() {
    /** 拖到哪张卡的上 / 下边，以及拖到哪个材料组头上 */
    const hoverUid = ref<number | null>(null)
    const hoverPos = ref<'before' | 'after'>('before')
    const hoverGroup = ref<number | null>(null)

    const clearHover = () => {
      hoverUid.value = null
      hoverGroup.value = null
    }

    const readDrag = (e: DragEvent): number | null => {
      if (dragUid !== null) return dragUid
      const raw = e.dataTransfer?.getData('text/plain') || ''
      if (!raw.startsWith('q:')) return null
      const uid = Number(raw.slice(2).split(',')[0])
      return Number.isFinite(uid) ? uid : null
    }

    const endDrag = () => {
      dragUid = null
      clearHover()
    }

    const onGripDragstart = (e: DragEvent, q: Question) => {
      // 只认把手：在题干 / 选项里拖选文字不该变成排序
      if (!(e.target as HTMLElement).closest('.qgrip')) return
      dragUid = q._uid
      e.dataTransfer?.setData('text/plain', `q:${q._uid}`)
      if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
      clearHover()
    }

    /** 落在卡片上半 = 插到它前面，下半 = 插到它后面；落进材料组就跟着共用该材料 */
    const dropPosition = (e: DragEvent): 'before' | 'after' => {
      const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
      return e.clientY < rect.top + rect.height / 2 ? 'before' : 'after'
    }

    const onCardDragover = (e: DragEvent, q: Question) => {
      if (readDrag(e) === null) return
      e.preventDefault()
      e.stopPropagation()
      hoverUid.value = q._uid
      hoverPos.value = dropPosition(e)
      hoverGroup.value = null
    }

    const onCardDragleave = (e: DragEvent, q: Question) => {
      const el = e.currentTarget as HTMLElement
      if (el.contains(e.relatedTarget as Node)) return
      if (hoverUid.value === q._uid) hoverUid.value = null
    }

    const onCardDrop = (e: DragEvent, q: Question) => {
      const uid = readDrag(e)
      if (uid === null) return
      e.preventDefault()
      e.stopPropagation()
      const position = dropPosition(e)
      endDrag()
      if (uid === q._uid) return
      moveQuestionsTo([uid], { kind: 'index', uid: q._uid, position })
    }

    const onGroupDragover = (e: DragEvent, g: Group) => {
      if (readDrag(e) === null) return
      e.preventDefault()
      e.stopPropagation()
      hoverGroup.value = g.start
      hoverUid.value = null
    }

    const onGroupDragleave = (e: DragEvent, g: Group) => {
      const el = e.currentTarget as HTMLElement
      if (el.contains(e.relatedTarget as Node)) return
      if (hoverGroup.value === g.start) hoverGroup.value = null
    }

    const onGroupDrop = (e: DragEvent, g: Group) => {
      const uid = readDrag(e)
      if (uid === null) return
      e.preventDefault()
      e.stopPropagation()
      endDrag()
      moveQuestionsTo([uid], { kind: 'group', start: g.start })
    }

    /**
     * 把选中的那段材料包成 `:::figure` 图文块。
     */
    const applyFigure = (g: Group, layout: string) => {
      const el = areas.get(g.start)
      const text = g.material || ''
      const from = el ? el.selectionStart : text.length
      const to = el ? el.selectionEnd : text.length
      setGroupMaterial(g.start, wrapFigure(text, from, to, layout))
    }

    return () => {
      if (!state.paper) return null
      const qs = currentQuestions()
      if (!qs.length) {
        return (
          <div class="pv-empty" style="padding:48px 10px">
            这份卷还没有题目。<br />
            点右上「＋ 新题」，或者用「批量粘贴」把一段题目贴进来。
          </div>
        )
      }

      const indexes = visibleIndexes()
      if (!indexes.length) {
        return <div class="pv-empty" style="padding:40px 10px">没有符合筛选条件的题目</div>
      }

      const nodes: any[] = []
      let lastGroupStart = -1
      // 组内小题先攒着，成组后再统一塞进 .grp-nest：
      // 阶梯缩进要的是一条**连续**的脊线，不能一张卡画一段（卡片之间的 7px 间隙会断线）
      let nestBuf: any[] = []
      let nestKey = 0
      const flushNest = () => {
        if (!nestBuf.length) return
        nodes.push(<div class="grp-nest" key={`nest-${nestKey}`}>{nestBuf}</div>)
        nestBuf = []
      }

      for (const i of indexes) {
        const g = groupAt(state.groups, i)
        if (g && g.start !== lastGroupStart) {
          flushNest()
          nestKey = g.start
          nodes.push(
            <div
              class={['group-head', hoverGroup.value === g.start && 'drop-on']}
              key={`group-${g.start}`}
              onDragover={(e: DragEvent) => onGroupDragover(e, g)}
              onDragleave={(e: DragEvent) => onGroupDragleave(e, g)}
              onDrop={(e: DragEvent) => onGroupDrop(e, g)}
            >
              <div class="gh-top">
                <span class="gh-title">材料组</span>
                <span class="gh-count">
                  第 {g.start + 1}–{g.end + 1} 题 · {g.end - g.start + 1} 题共用
                </span>
                <span class="grow"></span>
                <span class="gh-count">把题目拖到这里 = 并入本组；材料在这里改，组内自动同步</span>
              </div>
              <textarea
                rows={1}
                value={g.material}
                spellcheck={false}
                ref={(el: any) => {
                  if (el) areas.set(g.start, el as HTMLTextAreaElement)
                  else areas.delete(g.start)
                  grow(el as HTMLTextAreaElement)
                }}
                placeholder="材料正文：支持 Markdown 表格、图片、:::chart、:::figure 图文排版"
                onInput={(e) => {
                  const el = e.target as HTMLTextAreaElement
                  setGroupMaterial(g.start, el.value)
                  grow(el)
                }}
              />
              <div class="gh-tools">
                <span class="t">图文排版</span>
                {FIGURE_LAYOUTS.map(([key, label]) => (
                  <button key={key} title={`把选中的图文包成 :::figure（layout: ${key}）`}
                    onClick={() => applyFigure(g, key)}>
                    {label}
                  </button>
                ))}
                <span class="mini-hint">选中要和图排在一起的那段字再点；没选就插一段示例</span>
              </div>
            </div>,
          )
          lastGroupStart = g.start
        }
        const card = (
          <div
            key={qs[i]._uid}
            class={[
              'qwrap',
              hoverUid.value === qs[i]._uid && (hoverPos.value === 'before' ? 'drop-before' : 'drop-after'),
            ]}
            onDragstart={(e: DragEvent) => onGripDragstart(e, qs[i])}
            onDragend={endDrag}
            onDragover={(e: DragEvent) => onCardDragover(e, qs[i])}
            onDragleave={(e: DragEvent) => onCardDragleave(e, qs[i])}
            onDrop={(e: DragEvent) => onCardDrop(e, qs[i])}
          >
            <QuestionCard question={qs[i]} index={i} group={g} sortable={true} />
          </div>
        )

        // 组内题进缩进容器；独立题先收掉上一组的缩进再平铺
        if (g) {
          nestBuf.push(card)
        } else {
          flushNest()
          lastGroupStart = -1
          nodes.push(card)
        }
      }

      flushNest()
      return nodes
    }
  },
})
