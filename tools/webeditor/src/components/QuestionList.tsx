import { computed, defineComponent } from 'vue'
import QuestionCard from './QuestionCard'
import type { Group } from '../types'
import {
  currentQuestions, groupAt, setGroupMaterial, state, visibleIndexes,
} from '../store'
import { grow } from '../util'

/** 图文排版按钮：值与 App 的 MaterialRenderer.FigureLayout 一致 */
const FIGURE_LAYOUTS: [string, string][] = [
  ['left', '左图右文'],
  ['right', '右图左文'],
  ['top', '图在上'],
  ['bottom', '图在下'],
  ['row', '多图并排'],
]

/** 材料输入框：按组首下标记，排版按钮要用它的光标位置包块 */
const areas = new Map<number, HTMLTextAreaElement>()

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
    /**
     * 把选中的那段材料包成 `:::figure` 图文块。
     * 不选就插一段「图 + 字」的示例——手写围栏容易漏结束行，给个能跑的样板更省事。
     */
    const applyFigure = (g: Group, layout: string) => {
      const el = areas.get(g.start)
      const text = g.material || ''
      const from = el ? el.selectionStart : text.length
      const to = el ? el.selectionEnd : text.length
      const picked = text.slice(from, to).trim()
      const body = picked || '![说明](images/示例.png)\n这里的文字会和图片并排显示'
      const block = `:::figure\nlayout: ${layout}\nwidth: 38%\n${body}\n:::`
      const head = text.slice(0, from)
      const tail = text.slice(to)
      setGroupMaterial(g.start, `${head}${head && !head.endsWith('\n') ? '\n' : ''}${block}${tail}`)
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

      for (const i of indexes) {
        const g = groupAt(state.groups, i)
        if (g && g.start !== lastGroupStart) {
          nodes.push(
            <div class="group-head" key={`group-${g.start}`}>
              <div class="gh-top">
                <span class="gh-title">材料组</span>
                <span class="gh-count">
                  第 {g.start + 1}–{g.end + 1} 题 · {g.end - g.start + 1} 题共用
                </span>
                <span class="grow"></span>
                <span class="gh-count">材料在这里改，组内自动同步</span>
              </div>
              <textarea
                rows={1}
                value={g.material}
                spellcheck={false}
                ref={(el: any) => {
                  if (el) areas.set(g.start, el as HTMLTextAreaElement)
                  else areas.delete(g.start)
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
        if (!g) lastGroupStart = -1

        nodes.push(<QuestionCard key={qs[i]._uid} question={qs[i]} index={i} group={g} />)
      }

      return nodes
    }
  },
})
