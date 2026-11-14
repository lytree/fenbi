import { defineComponent, nextTick, onMounted, ref, watch } from 'vue'
import type { Question } from '../types'
import { ansSet, currentQuestions, focusQuestion, groupsOf, state, TYPE_LABEL } from '../store'
import { esc } from '../util'
import { renderInline, renderMaterial } from '../markdown'
import { LEVEL_NONE, level1Of, level2Of } from '../xingce'

/**
 * 右侧卷面预览：尽量贴近 App 答题页的样子——材料在首题通栏、题型小标、圆形选项徽标。
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
    // 从「校验 / 版本」切回预览时组件才挂载，先直接定位到当前选中的题，不做平滑滚动
    onMounted(() => { void nextTick(() => scrollToFocused(false)) })

    const blockHtml = (q: Question, index: number, material: string): string => {
      const focus = state.focused === q._uid ? ' focus' : ''
      const opts = q.options.filter((o) => o.text.trim())
      const chosen = ansSet(q)

      let h = `<div class="pv-q${focus}" data-uid="${q._uid}">`
      // 卷面题型小标：有行测两级题型就按「一级 · 二级」显示，否则退回作答形式（单选 / 多选…）
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

      h += `<div class="pv-stem">${renderInline(q.stem) || '<span class="pv-ph">（题干为空）</span>'}</div>`

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
        q.answer ? `<b>${esc(q.answer)}</b>` : '<span class="pv-pending">待补</span>'
      }</div>`
      h += '</div>'
      return h
    }

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
      <div class="ppane" ref={pane} innerHTML={html()} onClick={onClick}></div>
    )
  },
})
