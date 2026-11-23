import {
  defineComponent, nextTick, onBeforeUnmount, onBeforeUpdate, onMounted, onUpdated, ref, watch,
} from 'vue'
import type { Group, Question } from '../types'
import { currentQuestions, focusQuestion, groupsOf, state, TYPE_LABEL } from '../store'
import { esc } from '../util'
import { renderMaterial, renderStem } from '../markdown'
import { chaptersOf, optionListHtml, writeZoneHtml } from '../qview'
import { splitGroupHtml, useSplitGroup } from '../splitpane'
import type { SplitTab } from '../splitpane'
import PaperDock from './PaperDock'

/**
 * 整页版「卷面预览」：照粉笔试卷作答页（`spa.fenbi.com/ti/exam/exercise/…`）实测的版式排。
 *
 * - 顶上一张「章」卡：科目名 + 题数（`（20题）`）+ 该部分的作答说明，底色是蓝白渐变
 * - 每题一张白卡：题号 15px + 蓝色小标签（单选题 / 多选题…）；
 *   题干 16px、选项 48px 高一行、32px 圆徽标（校样页不再挂右上角那支笔记笔）
 * - 资料分析：1180×700 的大卡切成两半——左边「材料」白卡（自己滚，可拖宽），
 *   右边顶上题号页签、下面小题连着滚（点页签跳题），和作答页一模一样
 * - 底部一条固定的「答题卡」（`PaperDock`）：展开按科目铺题号格，点格子跳题
 *
 * 分屏那块（结构 + 页签跟滚动 + 拖把手）来自共用的 `splitpane.ts`，
 * 答案解析 / 练习记录两页用的是同一份，三页看着是一套东西。
 *
 * 纯卷面：不给答案、不给解析（那两样在「答案解析」页）。点题跳左栏卡片，
 * 左栏选中哪题这里滚到同一题并高亮。
 *
 * 一个实现上的坑：整卷是**一段 innerHTML**，任何一次组件重绘都会重建 DOM，
 * 页签的选中态和两栏的滚动位置都会丢。所以「当前题」的高亮不走模板，
 * 只在 watcher 里改类名；真需要重画（改了题干 / 笔记）时，重画前记下各栏
 * 的 scrollTop，重画后原样还回去。
 */
export default defineComponent({
  name: 'PreviewPane',
  setup() {
    const pane = ref<HTMLElement | null>(null)
    const split = useSplitGroup(pane)

    /** 章头：科目 + 题数 + 说明（粉笔的蓝白渐变小卡） */
    const CHAPTER_DESC: Record<string, string> = {
      政治理论: '根据题目要求，在四个选项中选出一个最恰当的答案。',
      常识判断: '根据题目要求，在四个选项中选出一个最恰当的答案。',
      言语理解与表达:
        '本部分包括表达与理解两方面的内容。请根据题目要求，在四个选项中选出一个最恰当的答案。',
      数量关系: '在这部分试题中，每道题呈现一段表述数字关系的文字，要求你迅速、准确地计算出答案。',
      判断推理:
        '本部分包括图形推理、定义判断、类比推理与逻辑判断四种类型的试题，在四个选项中选出一个最恰当的答案。',
      资料分析:
        '所给出的图、表、文字或综合性资料均有若干个问题要你回答。你应根据资料提供的信息进行分析、比较、计算和判断处理。',
    }

    const chapterHtml = (name: string, count: number): string => {
      const desc = CHAPTER_DESC[name] || ''
      return (
        '<section class="pv-chapter">' +
        `<div class="ch-name">${esc(name)}<span class="ch-num">（${count}题）</span></div>` +
        (desc ? `<div class="ch-desc">${esc(desc)}</div>` : '') +
        '</section>'
      )
    }

    /** 一道题的白卡：题号 + 题型标签，下面是题干与选项（卷面预览只校样，不放笔记笔） */
    const cardHtml = (q: Question, index: number): string => {
      let h = `<article class="pv-q" data-uid="${q._uid}" id="q-${index + 1}">`
      h += '<div class="pv-tagrow">' +
        `<span class="pv-no">${index + 1}.</span>` +
        `<span class="pv-tag">${esc(TYPE_LABEL[q.type] || q.type)}</span>` +
        '</div>'
      h += `<div class="pv-stem">${renderStem(q.stem) || '<span class="pv-ph">（题干为空）</span>'}</div>`
      // 选项按题型走共用渲染：多选方徽标、判断勾叉；填空 / 主观题给作答区
      h += optionListHtml(q) || writeZoneHtml(q.type)
      h += '</article>'
      return h
    }

    /**
     * 资料题：照作答页切成一块——左「材料」白卡自己滚，右栏题号页签 + 小题连着排，
     * 点页签滚到对应小题（结构与交互都来自 `splitpane.ts`）。
     */
    const splitHtml = (g: Group, qs: Question[]): string => {
      const cards = qs.slice(g.start, g.end + 1)
      const tabs: SplitTab[] = cards.map((q, k) => ({ id: q._uid, label: `${g.start + k + 1}题` }))
      return splitGroupHtml({
        key: g.start,
        tabs,
        activeId: cards[0]._uid,
        materialHtml: renderMaterial(g.material),
        bodyHtml: cards.map((q, k) => cardHtml(q, g.start + k)).join(''),
      })
    }

    const html = (): string => {
      const qs = currentQuestions()
      if (!qs.length) return '<div class="pv-empty">这份卷还没有题目</div>'
      const groups = groupsOf(qs)
      let out = ''
      for (const ch of chaptersOf(qs)) {
        out += chapterHtml(ch.name, ch.end - ch.start + 1)
        let i = ch.start
        while (i <= ch.end) {
          const g = groups.find((x) => x.start === i)
          if (g) {
            out += splitHtml(g, qs)
            i = g.end + 1
          } else {
            out += cardHtml(qs[i], i)
            i += 1
          }
        }
      }
      return out
    }

    /** 当前题的高亮只改类名：整卷 innerHTML 不重建，滚动位置就保得住 */
    const markFocus = () => {
      const uid = state.focused
      pane.value?.querySelectorAll<HTMLElement>('.pv-q').forEach((el) => {
        el.classList.toggle('focus', Number(el.dataset.uid) === uid)
      })
    }

    const scrollToFocused = (smooth: boolean) => {
      if (state.focused === null) return
      split.scrollToCard(state.focused, smooth)
    }

    watch(() => state.focused, () => {
      markFocus()
      void nextTick(() => scrollToFocused(true))
    })

    const onClick = (e: MouseEvent) => {
      // 题号页签：只滚右栏，不换左栏选中
      if (split.onClick(e)) return
      const hit = (e.target as HTMLElement).closest('.pv-q') as HTMLElement | null
      if (!hit) return
      const uid = Number(hit.dataset.uid)
      const index = currentQuestions().findIndex((q) => q._uid === uid)
      if (index >= 0) focusQuestion(index)
    }

    // 重画（改了题干 / 笔记 / 题型）前把两栏的位置记下来，重画后还回去，
    // 顺带把页签与当前题的高亮补上——DOM 是新的一批，类名得重贴
    onBeforeUpdate(() => split.remember())

    onUpdated(() => {
      split.restore()
      markFocus()
    })

    const onScrollCapture = (e: Event) => split.onScrollCapture(e)

    onMounted(() => {
      pane.value?.addEventListener('scroll', onScrollCapture, true)
      markFocus()
      void nextTick(() => {
        scrollToFocused(false)
        split.applyWidths()
        split.syncAll()
      })
    })
    onBeforeUnmount(() => { pane.value?.removeEventListener('scroll', onScrollCapture, true) })

    return () => (
      <>
        <div
          class="ppane paper"
          ref={pane}
          innerHTML={html()}
          onClick={onClick}
          onPointerdown={split.onPointerDown}
        ></div>
        <PaperDock />
      </>
    )
  },
})
