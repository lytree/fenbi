import { defineComponent, ref } from 'vue'
import { currentQuestions, focusQuestion, state } from '../store'
import { chaptersOf } from '../qview'

/**
 * 卷面预览底部那条「答题卡」（照粉笔作答页）。
 *
 * 收起时只有一枚蓝色标签：`答题卡 0/10`。摊开后按科目铺题号格，
 * 点格子跳到那道题；**缺答案的格子右上角一颗橙点**——卷面预览是校样用的，
 * 这一格代表「这题还没录答案」，不是「答错了」。
 *
 * 单独一个组件，是为了让「当前题」的变化只重画这一条：
 * 挂在 PreviewPane 里会让整卷 innerHTML 重建，滚动位置和页签全丢。
 */
export default defineComponent({
  name: 'PaperDock',
  setup() {
    const open = ref(false)

    const missingCount = () => currentQuestions().filter((q) => !q.answer.trim()).length

    const cells = (start: number, end: number) => {
      const qs = currentQuestions()
      const out = []
      for (let i = start; i <= end; i++) {
        const q = qs[i]
        const missing = !q.answer.trim()
        out.push(
          <button
            key={q._uid}
            class={['dock-cell', state.focused === q._uid && 'on', missing && 'miss']}
            title={missing ? `第 ${i + 1} 题 · 还没有答案` : `第 ${i + 1} 题`}
            onClick={() => focusQuestion(i)}
          >
            {i + 1}
          </button>,
        )
      }
      return out
    }

    return () => {
      const qs = currentQuestions()
      if (!qs.length) return null
      const miss = missingCount()
      return (
        <div class={['paper-dock', open.value && 'open']}>
          <button class="dock-tag" title="展开 / 收起题号" onClick={() => { open.value = !open.value }}>
            <span class="dt-name">答题卡</span>
            <span class="dt-sub">0<i>/</i>{qs.length}</span>
            <span class="dt-chev">❯</span>
          </button>
          {miss
            ? <span class="dock-miss" title="还没录答案的题数">{miss} 题缺答案</span>
            : <span class="dock-miss ok">答案齐全</span>}

          {open.value
            ? (
              <div class="dock-body">
                {chaptersOf(qs).map((ch) => (
                  <div class="dock-ch" key={`${ch.name}-${ch.start}`}>
                    <div class="dock-ch-name">{ch.name}<span>（{ch.end - ch.start + 1}题）</span></div>
                    <div class="dock-grid">{cells(ch.start, ch.end)}</div>
                  </div>
                ))}
              </div>
            )
            : null}
        </div>
      )
    }
  },
})
