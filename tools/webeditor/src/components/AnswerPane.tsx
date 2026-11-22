import { defineComponent, nextTick, onMounted, ref, watch } from 'vue'
import type { Question } from '../types'
import { currentQuestions, focusQuestion, groupsOf, state, TYPE_LABEL } from '../store'
import { esc } from '../util'
import { renderInline, renderMaterial, renderStem } from '../markdown'
import { LEVEL_NONE, level1Of, level2Of } from '../xingce'

/**
 * 右侧「答案解析」页：粉笔解析页的版式——每题一张卡，答案铺成灰底宫格条，
 * 解析装进可折叠的「解析」盒（蓝条 + 标题 + 箭头，点标题收起 / 展开），
 * 知识点画成蓝色胶囊，App 端写的笔记以只读行收尾。
 * 卷面预览（PreviewPane）保持纯卷面；双向联动口径与它一致：点题跳左卡片，左边选中这里滚过来。
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

    const blockHtml = (q: Question, index: number): string => {
      const focus = state.focused === q._uid ? ' focus' : ''
      const opts = q.options.filter((o) => o.text.trim())

      let h = `<div class="pv-q${focus}" data-uid="${q._uid}">`
      // 粉笔口径：题号在前，题型标签跟在后
      const lv1 = level1Of(q)
      const lv2 = level2Of(q)
      const hasTypeTree = !!lv1 || !!lv2
      h += `<div class="pv-tagrow"><span class="pv-no">${index + 1}.</span><span class="pv-tag">${
        hasTypeTree ? esc(lv1 || LEVEL_NONE) : TYPE_LABEL[q.type]
      }</span>`
      if (hasTypeTree) h += `<span class="pv-tag lv2">${esc(lv2 || LEVEL_NONE)}</span>`
      h += '</div>'

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

      // 答案宫格：空答案标「待补」，整卷扫缺一眼能看到
      const score = typeof q.score === 'string' ? q.score.trim() : String(q.score ?? '').trim()
      const cells = [
        `<div class="ov"><div class="v g">${q.answer ? esc(q.answer) : '<span class="pv-pending">待补</span>'}</div><div class="k">正确答案</div></div>`,
      ]
      if (score && Number(score) > 0) {
        cells.push(`<div class="ov"><div class="v b">${esc(score)}</div><div class="k">分值</div></div>`)
      }
      h += `<div class="ov-grid c${cells.length}">${cells.join('')}</div>`

      // 解析盒：点标题行收起 / 展开（箭头跟着翻）；没有解析就不出这个盒
      if (q.analysis.trim()) {
        h += '<div class="sol-box" data-sol>' +
          '<div class="sol-h"><span class="bar"></span><span class="t">解析</span><span class="chev">❯</span></div>' +
          `<div class="sol-body">${renderStem(q.analysis)}</div>` +
          '</div>'
      }
      if (q.knowledge.trim()) {
        h += `<div class="kp-row"><span class="kp">${esc(q.knowledge)}</span></div>`
      }
      // 笔记是 App 端写的，这里只读：有内容摆内容，没内容摆一句提示占位
      h += '<div class="note-row">' +
        '<span class="nl">笔记</span>' +
        (q.note?.trim()
          ? `<span class="nb">${esc(q.note)}</span>`
          : '<span class="nb empty">暂无笔记 · 在 App 答题页长按画笔添加</span>') +
        '</div>'
      h += '</div>'
      return h
    }

    /** 材料卡：资料分析的形式——材料自己一张卡放在小题前面 */
    const materialHtml = (material: string, size: number): string =>
      `<div class="pv-mat"><div class="mh">资料分析 · ${size} 小题</div>${renderMaterial(material)}</div>`

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
          out += materialHtml(g.material, g.end - g.start + 1)
          for (let k = g.start; k <= g.end; k++) out += blockHtml(qs[k], k)
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
      // 解析盒标题行：收起 / 展开，不触发下面的跳题
      const solHead = target.closest('.sol-h') as HTMLElement | null
      if (solHead) {
        solHead.closest('.sol-box')?.classList.toggle('collapsed')
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
