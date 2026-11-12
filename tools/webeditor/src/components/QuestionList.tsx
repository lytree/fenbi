import { computed, defineComponent } from 'vue'
import QuestionCard from './QuestionCard'
import {
  currentQuestions, groupAt, setGroupMaterial, state, visibleIndexes,
} from '../store'
import { grow } from '../util'

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
                placeholder="材料正文：支持 Markdown 表格、图片、:::chart"
                onInput={(e) => {
                  const el = e.target as HTMLTextAreaElement
                  setGroupMaterial(g.start, el.value)
                  grow(el)
                }}
              />
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
