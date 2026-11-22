import { defineComponent, nextTick, onMounted, ref, watch } from 'vue'
import type { Question } from '../types'
import {
  currentQuestions, focusQuestion, groupsOf, state, TYPE_LABEL,
} from '../store'
import { esc } from '../util'
import { renderMaterial, renderStem } from '../markdown'
import { LEVEL_NONE, level1Of, level2Of } from '../xingce'
import { optionListHtml, writeZoneHtml, materialGroupHtml } from '../qview'

/**
 * 整页版「卷面预览」：按粉笔答题页的版式排——材料卡与它的小题成组、同列上下排，
 * 题号在题型标签前，选项是圆徽标行。纯卷面，不带答案（答案/解析看旁边的「答案解析」页）。
 * 双向联动：点预览里的题会跳到左边对应卡片；左边选中哪题，预览也滚到同一题并高亮。
 */
export default defineComponent({
  name: 'PreviewPane',
  setup() {
    const pane = ref<HTMLElement | null>(null)

    /** 把预览里当前选中（state.focused）的那题滚到面板中间 */
    const scrollToFocused = (smooth: boolean) => {
      const uid = state.focused
      if (uid === null) return
      const hit = pane.value?.querySelector(`.pv-q[data-uid="${uid}"]`) as HTMLElement | null
      hit?.scrollIntoView({ block: 'center', behavior: smooth ? 'smooth' : 'auto' })
    }

    // 预览内容每次都是整段 innerHTML 重建的，聚焦项的高亮类跟着重建，
    // 所以等这一帧渲染完再去滚（否则拿到的是旧的节点）
    watch(() => state.focused, () => { void nextTick(() => scrollToFocused(true)) })
    // 从「校验 / 版本」弹框关掉、预览重新露出时组件还在，滚一次兜底
    onMounted(() => { void nextTick(() => scrollToFocused(false)) })

    const blockHtml = (q: Question, index: number): string => {
      const focus = state.focused === q._uid ? ' focus' : ''
      const typeKey = q.type

      let h = `<div class="pv-q${focus}" data-uid="${q._uid}">`
      // 粉笔口径：题号在前，题型标签跟在后；有行测两级题型就按「一级 · 二级」显示
      const lv1 = level1Of(q)
      const lv2 = level2Of(q)
      const hasTypeTree = !!lv1 || !!lv2
      h += `<div class="pv-tagrow"><span class="pv-no">${index + 1}.</span><span class="pv-tag">${
        hasTypeTree ? esc(lv1 || LEVEL_NONE) : TYPE_LABEL[typeKey]
      }</span>`
      if (hasTypeTree) h += `<span class="pv-tag lv2">${esc(lv2 || LEVEL_NONE)}</span>`
      h += '</div>'

      // 题干里带表格时 renderStem 会排出真表格（和 App 的 renderStemRich 同一套口径）
      h += `<div class="pv-stem">${renderStem(q.stem) || '<span class="pv-ph">（题干为空）</span>'}</div>`

      // 选项按题型走共用渲染：多选方徽标、判断勾叉；填空 / 主观题没有选项，给作答区
      h += optionListHtml(q) || writeZoneHtml(typeKey)

      h += '</div>'
      return h
    }

    /** 材料题：材料卡 + 小题收进同一个 .pv-group，小题跟在后面、共用左轨 */
    const html = (): string => {
      const qs = currentQuestions()
      if (!qs.length) return '<div class="pv-empty">这份卷还没有题目</div>'
      const groups = groupsOf(qs)
      // 样张页眉：一行小字说明「这是卷面、不是编辑器」，跟左边的校样条区分开
      let out = '<div class="pv-head"><span class="pv-head-t">卷面样张</span>' +
        `<span class="pv-head-n">${qs.length} 题 · 与 App 答题页同版式</span></div>`
      let i = 0
      while (i < qs.length) {
        const g = groups.find((x) => x.start === i)
        if (g) {
          out += materialGroupHtml({
            start: g.start + 1,
            count: g.end - g.start + 1,
            materialHtml: renderMaterial(g.material),
            questionsHtml: qs
              .slice(g.start, g.end + 1)
              .map((q, k) => blockHtml(q, g.start + k))
              .join(''),
          })
          i = g.end + 1
        } else {
          out += blockHtml(qs[i], i)
          i += 1
        }
      }
      return out
    }

    const onClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement
      // 资料头的「收起 / 展开」：只把材料正文收起来，小题留着
      const fold = target.closest('.mfold') as HTMLElement | null
      if (fold) {
        fold.closest('.pv-mat')?.classList.toggle('collapsed')
        return
      }
      const hit = target.closest('.pv-q') as HTMLElement | null
      if (!hit) return
      const uid = Number(hit.dataset.uid)
      const index = currentQuestions().findIndex((q) => q._uid === uid)
      if (index >= 0) focusQuestion(index)
    }

    return () => (
      <div class="ppane" ref={pane} innerHTML={html()} onClick={onClick}></div>
    )
  },
})
