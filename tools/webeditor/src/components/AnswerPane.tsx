import { defineComponent, nextTick, onMounted, ref, watch } from 'vue'
import type { Question } from '../types'
import { ansSet, currentQuestions, focusQuestion, groupsOf, state, TYPE_LABEL } from '../store'
import { esc } from '../util'
import { renderInline, renderMaterial, renderStem } from '../markdown'
import { LEVEL_NONE, level1Of, level2Of } from '../xingce'

/**
 * 右侧「答案解析」页：和卷面预览同结构，但每题带答案、解析、知识点，
 * 用来整卷过一遍答案质量；卷面预览（PreviewPane）保持纯卷面、不带答案。
 * 双向联动口径与 PreviewPane 一致：点题跳左卡片，左边选中这里滚过来高亮。
 */
export default defineComponent({
  name: 'AnswerPane',
  setup() {
    const pane = ref<HTMLElement | null>(null)

    /** 把当前选中（state.focused）的那题滚到面板中间 */
    const scrollToFocused = (smooth: boolean) => {
      const uid = state.focused
      if (uid === null) return
      const hit = pane.value?.querySelector(`.pv-q[data-uid="${uid}"]`) as HTMLElement | null
      hit?.scrollIntoView({ block: 'center', behavior: smooth ? 'smooth' : 'auto' })
    }

    watch(() => state.focused, () => { void nextTick(() => scrollToFocused(true)) })
    onMounted(() => { void nextTick(() => scrollToFocused(false)) })

    const blockHtml = (q: Question, index: number, material: string): string => {
      const focus = state.focused === q._uid ? ' focus' : ''
      const opts = q.options.filter((o) => o.text.trim())

      let h = `<div class="pv-q${focus}" data-uid="${q._uid}">`
      const lv1 = level1Of(q)
      const lv2 = level2Of(q)
      const hasTypeTree = !!lv1 || !!lv2
      h += `<div class="pv-tagrow"><span class="pv-tag">${
        hasTypeTree ? esc(lv1 || LEVEL_NONE) : TYPE_LABEL[q.type]
      }</span>`
      if (hasTypeTree) h += `<span class="pv-tag lv2">${esc(lv2 || LEVEL_NONE)}</span>`
      h += `<span class="pv-no">第 ${index + 1} 题</span>`
      h += '</div>'

      if (material) h += `<div class="pv-mat"><div class="mh">材料</div>${renderMaterial(material)}</div>`

      h += `<div class="pv-stem">${renderStem(q.stem) || '<span class="pv-ph">（题干为空）</span>'}</div>`

      if (opts.length) {
        const two = q.optionLayout === '2x2' && opts.length >= 2
        h += `<div class="pv-opts${two ? ' two' : ''}">`
        h += opts
          .map(
            (o) =>
              `<div class="pv-opt">` +
              `<span class="pv-key">${esc(o.key)}</span>` +
              `<span class="pv-text">${renderInline(o.text)}</span></div>`,
          )
          .join('')
        h += '</div>'
      }

      // 答案行：空答案标「待补」，方便整卷扫缺
      h += `<div class="pv-ans">答案：${
        q.answer ? `<b>${esc(q.answer)}</b>` : '<span class="pv-pending">待补</span>'
      }</div>`

      if (q.analysis.trim()) {
        h += `<div class="ap-analysis"><div class="mh">解析</div>${renderStem(q.analysis)}</div>`
      }
      if (q.knowledge.trim()) {
        h += `<div class="ap-know">知识点：${esc(q.knowledge)}</div>`
      }
      h += '</div>'
      return h
    }

    const html = (): string => {
      const qs = currentQuestions()
      if (!qs.length) return '<div class="pv-empty">这份卷还没有题目</div>'
      const groups = groupsOf(qs)
      const missing = qs.filter((q) => !q.answer).length
      let out = '<div class="pv-head"><span class="pv-head-t">答案解析</span>' +
        `<span class="pv-head-n">${qs.length} 题${
          missing ? ` · ${missing} 题待补答案` : ' · 答案齐全'
        }</span></div>`
      let i = 0
      while (i < qs.length) {
        const g = groups.find((x) => x.start === i)
        if (g) {
          for (let k = g.start; k <= g.end; k++) {
            out += blockHtml(qs[k], k, k === g.start ? g.material : '')
          }
          i = g.end + 1
        } else {
          out += blockHtml(qs[i], i, '')
          i += 1
        }
      }
      return out
    }

    const onClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement
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
