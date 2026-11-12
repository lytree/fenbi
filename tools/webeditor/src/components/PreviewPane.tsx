import { defineComponent } from 'vue'
import type { Question } from '../types'
import { ansSet, currentQuestions, focusQuestion, groupsOf, state, TYPE_LABEL } from '../store'
import { esc } from '../util'
import { renderInline, renderMaterial } from '../markdown'

/**
 * 右侧卷面预览：尽量贴近 App 答题页的样子——材料在首题通栏、题型小标、圆形选项徽标。
 * 只读，但点任意一块会跳到左边对应的题目，改完顺手核对。
 */
export default defineComponent({
  name: 'PreviewPane',
  setup() {
    const blockHtml = (q: Question, index: number, material: string): string => {
      const focus = state.focused === q._uid ? ' focus' : ''
      const opts = q.options.filter((o) => o.text.trim())
      const chosen = ansSet(q)

      let h = `<div class="pv-q${focus}" data-uid="${q._uid}">`
      h += `<div class="pv-tagrow"><span class="pv-tag">${TYPE_LABEL[q.type]}</span>`
      h += `<span class="pv-no">第 ${index + 1} 题</span>`
      if (q.subject) h += `<span class="pv-no">· ${esc(q.subject)}</span>`
      h += '</div>'

      if (material) h += `<div class="pv-mat"><div class="mh">材料</div>${renderMaterial(material)}</div>`

      h += `<div class="pv-stem">${renderInline(q.stem) || '<span style="color:#a9b6c6">（题干为空）</span>'}</div>`

      if (opts.length) {
        h += '<div class="pv-opts">'
        h += opts
          .map(
            (o) =>
              `<div class="pv-opt${chosen.includes(o.key) ? ' on' : ''}">` +
              `<span class="pv-key">${esc(o.key)}</span>` +
              `<span class="pv-text">${renderInline(o.text)}</span></div>`,
          )
          .join('')
        h += '</div>'
      }

      h += `<div class="pv-ans">答案：${
        q.answer ? `<b>${esc(q.answer)}</b>` : '<span style="color:#b45309">待补</span>'
      }</div>`
      h += '</div>'
      return h
    }

    const html = (): string => {
      const qs = currentQuestions()
      if (!qs.length) return '<div class="pv-empty">这份卷还没有题目</div>'
      const groups = groupsOf(qs)
      let out = ''
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
      const hit = (e.target as HTMLElement).closest('.pv-q') as HTMLElement | null
      if (!hit) return
      const uid = Number(hit.dataset.uid)
      const index = currentQuestions().findIndex((q) => q._uid === uid)
      if (index >= 0) focusQuestion(index)
    }

    return () => (
      <div class="ppane" innerHTML={html()} onClick={onClick}></div>
    )
  },
})
