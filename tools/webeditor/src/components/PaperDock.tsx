import { defineComponent, ref } from 'vue'
import type { PropType } from 'vue'
import { currentQuestions, focusQuestion, state } from '../store'
import { chaptersOf } from '../qview'

/** 答题卡的一组题号（科目 / 整卷），下标与题目在卷里的位置一致（0-based） */
export interface DockGroup {
  name: string
  start: number
  end: number
}

/**
 * 底部那条「答题卡」（照粉笔作答页）。
 *
 * 收起时就是一整行按钮：`答题卡 0/10`（右边写着答案齐没齐）+ 箭头；摊开后按科目铺题号格，
 * 点格子跳到那道题。**缺答案的格子右上角一颗橙点**——卷面预览是校样用的，
 * 这一格代表「这题还没录答案」，不是「答错了」。
 *
 * 三页共用（卷面预览 / 答案解析 / 练习记录），差异都从 props 走：
 * - `progress`：卷面预览是 `0/135`（还没作答）；解析与记录页没有作答进度，只写题数
 * - `groups` / `marks`：记录页的题目不在 store 里，分组与每格的对错由调用方传进来
 * - `pick`：点格子干什么，默认是「把当前卷的第 i 题设成当前题」
 *
 * 单独一个组件，是为了让「当前题」的变化只重画这一条：
 * 挂在 PreviewPane 里会让整卷 innerHTML 重建，滚动位置和页签全丢。
 */
export default defineComponent({
  name: 'PaperDock',
  props: {
    /** 收起时的进度：`0/N`；给 false 就只写「N 题」 */
    progress: { type: Boolean, default: true },
    /** 缺答案提示（卷面预览校对用）；解析 / 记录页在页头已经说过了，不再重复 */
    hint: { type: Boolean, default: true },
    /** 题号网格怎么分组；不给就按当前卷的科目切章 */
    groups: { type: Array as PropType<DockGroup[] | null>, default: null },
    /** 每格的状态：''（普通）/ 'miss'（缺答案）/ 'ok' / 'no' / 'none' / 'pend' */
    marks: { type: Array as PropType<string[] | null>, default: null },
    /** 点格子之后做什么；不给就用 store 的 focusQuestion */
    pick: { type: Function as PropType<((index: number) => void) | null>, default: null },
  },
  setup(props) {
    const open = ref(false)

    /** 题目总数：记录页没有 store 里的卷，就按传进来的格数走 */
    const count = () => props.marks?.length ?? currentQuestions().length

    /** 缺答案的题数：记录页按 'miss' 标，没有标就视为答案齐全 */
    const missingCount = (): number => {
      if (props.marks) return props.marks.filter((m) => m === 'miss').length
      return currentQuestions().filter((q) => !q.answer.trim()).length
    }

    const groups = (): DockGroup[] => {
      if (props.groups) return props.groups
      return chaptersOf(currentQuestions()).map((ch) => ({
        name: ch.name,
        start: ch.start,
        end: ch.end,
      }))
    }

    /** 一格的类名：当前题高亮 > 调用方给的状态 > 缺答案 */
    const cellCls = (i: number): string[] => {
      const mark = props.marks?.[i] ?? ''
      const miss = props.marks ? mark === 'miss' : !currentQuestions()[i]?.answer.trim()
      // 记录页没有「当前题」这回事（题目也不在 store 里），别给格子镀一圈当前态
      const follow = !props.pick && state.focused !== null && isFocused(i)
      return ['dock-cell', follow && 'on', mark, miss && 'miss'].filter(Boolean) as string[]
    }

    /** 当前题：卷面 / 解析页按 uid 比，记录页没有 uid（state.focused 是卷里第几题） */
    const isFocused = (i: number): boolean => {
      const q = currentQuestions()[i]
      return q ? q._uid === state.focused : false
    }

    const go = (i: number) => {
      if (props.pick) props.pick(i)
      else focusQuestion(i)
      // 跳过去就把卡片收起来：这条压在页面上，跳完还摊着会挡住最后一道题
      open.value = false
    }

    return () => {
      const n = count()
      if (!n) return null
      const miss = missingCount()
      return (
        <div class={['paper-dock', open.value && 'open']}>
          {/* 摊开的题号板在**上面**：这条钉在视口底边，DOM 里放前面，展开时才是「向上长」，
              标签留在最底下（粉笔那张答题卡也是这么弹的） */}
          {open.value
            ? (
              <div class="dock-body">
                {groups().map((ch) => (
                  <div class="dock-ch" key={`${ch.name}-${ch.start}`}>
                    <div class="dock-ch-name">{ch.name}<span>（{ch.end - ch.start + 1}题）</span></div>
                    <div class="dock-grid">
                      {Array.from({ length: ch.end - ch.start + 1 }, (_, k) => ch.start + k).map((i) => (
                        <button
                          key={i}
                          class={cellCls(i)}
                          title={`第 ${i + 1} 题`}
                          onClick={() => go(i)}
                        >
                          {i + 1}
                        </button>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )
            : null}

          <button class="dock-tag" title="展开 / 收起题号" onClick={() => { open.value = !open.value }}>
            <span class="dt-name">答题卡</span>
            <span class="dt-sub">
              {props.progress ? <>0<i>/</i>{n}</> : <>{n} 题</>}
            </span>
            <span class="dt-spacer" />
            {props.hint
              ? (
                <span class={['dock-miss', miss ? 'alert' : 'ok']} title={miss ? '还没录答案的题数' : '每道题都有答案了'}>
                  {miss ? `${miss} 题缺答案` : '答案齐全'}
                </span>
              )
              : null}
            <span class="dt-chev">❯</span>
          </button>
        </div>
      )
    }
  },
})
