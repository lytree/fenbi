import {
  defineComponent, nextTick, onBeforeUnmount, onBeforeUpdate, onMounted, onUpdated, ref, watch,
} from 'vue'
import type { Group, Question } from '../types'
import { currentQuestions, focusQuestion, groupsOf, state, TYPE_LABEL } from '../store'
import { esc } from '../util'
import { renderMaterial, renderStem } from '../markdown'
import { chaptersOf, optionListHtml, writeZoneHtml } from '../qview'
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
    /** 重画前记下的各栏滚动位置（键 = 资料组的首题下标） */
    let scrollMemo: Record<string, number> = {}

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
     * 资料题：照作答页切成 1180×700 的一块——左「材料」白卡自己滚，
     * 右栏题号页签 + 小题连着排，点页签滚到对应小题。
     * 宽度写在 `--mat-w` 上（拖中间把手时只改这个变量，不重建 DOM）。
     */
    const splitHtml = (g: Group, qs: Question[]): string => {
      const count = g.end - g.start + 1
      const tabs = Array.from({ length: count }, (_, i) =>
        `<button class="pv-tab${i === 0 ? ' active' : ''}" data-tab="${g.start + i}">${g.start + i + 1}题</button>`,
      ).join('')
      const cards = qs
        .slice(g.start, g.end + 1)
        .map((q, k) => cardHtml(q, g.start + k))
        .join('')
      return (
        `<div class="pp-split" data-g="${g.start}" style="--mat-w:60%">` +
        '<div class="pp-mat">' +
        '<span class="pv-mat-tag">材料</span>' +
        `<div class="pp-mat-body">${renderMaterial(g.material)}</div>` +
        '</div>' +
        '<div class="pp-grip" title="拖动调整材料宽度"></div>' +
        '<div class="pp-qs">' +
        `<div class="pv-tabs">${tabs}</div>` +
        `<div class="pp-qs-body">${cards}</div>` +
        '</div>' +
        '</div>'
      )
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

    /**
     * 滚到当前题。
     *
     * 分屏里的小题要滚两层：先把整块分屏顶到视口上方（`block: 'start'`，配合 CSS 的
     * scroll-margin 留出顶栏），再把右栏自己滚到那道小题的顶部——两层都用
     * `scrollIntoView` 的话，内层会被「居中」到栏中间，页签跟着跳错。
     */
    const scrollToFocused = (smooth: boolean) => {
      const uid = state.focused
      if (uid === null) return
      const hit = pane.value?.querySelector(`.pv-q[data-uid="${uid}"]`) as HTMLElement | null
      if (!hit) return
      const body = hit.closest('.pp-qs-body') as HTMLElement | null
      const outer = body ? hit.closest('.pp-split') as HTMLElement | null : null
      const box = outer ?? hit
      // jsdom（单测）里没有 scrollIntoView，跳过这一步
      if (typeof box.scrollIntoView === 'function') {
        box.scrollIntoView({ block: 'start', behavior: smooth ? 'smooth' : 'auto' })
      }
      if (body) {
        const delta = hit.getBoundingClientRect().top - body.getBoundingClientRect().top
        body.scrollTop += delta - 8
        syncTabs(body)
      }
    }

    watch(() => state.focused, () => {
      markFocus()
      void nextTick(() => scrollToFocused(true))
    })

    /**
     * 右栏滚动时把页签跟上去：哪一个小题贴在栏顶，哪一个页签就亮。
     * 用 rect 差值算「相对栏顶的位置」——offsetTop 的参照物是最近定位祖先，
     * 在这里并不可靠（之前页签跟错题就是栽在这一步）。
     */
    const syncTabs = (body: HTMLElement) => {
      const split = body.closest('.pp-split') as HTMLElement | null
      if (!split) return
      const cards = [...body.querySelectorAll<HTMLElement>('.pv-q')]
      if (!cards.length) return
      const bodyTop = body.getBoundingClientRect().top
      let active = cards[0]
      for (const c of cards) {
        if (c.getBoundingClientRect().top - bodyTop <= 24) active = c
      }
      const uid = Number(active.dataset.uid)
      const qs = currentQuestions()
      split.querySelectorAll<HTMLElement>('.pv-tab').forEach((t) => {
        const q = qs[Number(t.dataset.tab)]
        t.classList.toggle('active', q?._uid === uid)
      })
    }

    const syncAllTabs = () => {
      pane.value?.querySelectorAll<HTMLElement>('.pp-qs-body').forEach(syncTabs)
    }

    /** 点题号页签：在右栏里滚到那道小题（只滚右栏，不换左栏选中） */
    const jumpToTab = (tab: HTMLElement) => {
      const split = tab.closest('.pp-split') as HTMLElement | null
      const idx = Number(tab.dataset.tab)
      if (!split || !Number.isFinite(idx)) return
      const q = currentQuestions()[idx]
      const body = split.querySelector('.pp-qs-body') as HTMLElement | null
      const hit = body?.querySelector(`.pv-q[data-uid="${q?._uid}"]`) as HTMLElement | null
      if (hit && body) {
        const delta = hit.getBoundingClientRect().top - body.getBoundingClientRect().top
        const top = body.scrollTop + delta - 8
        // jsdom（单测）里没有 scrollTo：退回直接改 scrollTop，测试照样能验行为
        if (typeof body.scrollTo === 'function') body.scrollTo({ top, behavior: 'smooth' })
        else body.scrollTop = top
      }
    }

    const onClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement
      const tab = target.closest('.pv-tab') as HTMLElement | null
      if (tab) {
        jumpToTab(tab)
        tab.parentElement?.querySelectorAll('.pv-tab').forEach((t) => {
          t.classList.toggle('active', t === tab)
        })
        return
      }
      const hit = target.closest('.pv-q') as HTMLElement | null
      if (!hit) return
      const uid = Number(hit.dataset.uid)
      const index = currentQuestions().findIndex((q) => q._uid === uid)
      if (index >= 0) focusQuestion(index)
    }

    // 滚动不冒泡，只能挂在根上捕获：右栏内部滚 / 整页滚都在这里过一遍
    const onScrollCapture = (e: Event) => {
      const el = e.target as HTMLElement | null
      if (el?.classList?.contains('pp-qs-body')) syncTabs(el)
    }

    /** 拖动两栏之间的把手：改左栏宽度（45%–72%），只动 CSS 变量不重建 */
    const onPointerDown = (e: PointerEvent) => {
      const grip = (e.target as HTMLElement).closest('.pp-grip') as HTMLElement | null
      if (!grip || e.button !== 0) return
      const split = grip.closest('.pp-split') as HTMLElement | null
      if (!split) return
      e.preventDefault()
      const rect = split.getBoundingClientRect()
      const move = (ev: PointerEvent) => {
        const pct = Math.min(72, Math.max(45, ((ev.clientX - rect.left) / rect.width) * 100))
        split.style.setProperty('--mat-w', `${Math.round(pct)}%`)
      }
      const up = () => {
        window.removeEventListener('pointermove', move)
        window.removeEventListener('pointerup', up)
        window.removeEventListener('pointercancel', up)
        document.body.classList.remove('split-dragging')
      }
      document.body.classList.add('split-dragging')
      window.addEventListener('pointermove', move)
      window.addEventListener('pointerup', up)
      window.addEventListener('pointercancel', up)
    }

    // 重画（改了题干 / 笔记 / 题型）前把两栏的位置记下来，重画后还回去，
    // 顺带把页签与当前题的高亮补上——DOM 是新的一批，类名得重贴
    onBeforeUpdate(() => {
      scrollMemo = {}
      pane.value?.querySelectorAll<HTMLElement>('.pp-split').forEach((s) => {
        const body = s.querySelector('.pp-qs-body') as HTMLElement | null
        if (s.dataset.g && body) scrollMemo[s.dataset.g] = body.scrollTop
      })
    })

    onUpdated(() => {
      pane.value?.querySelectorAll<HTMLElement>('.pp-split').forEach((s) => {
        const body = s.querySelector('.pp-qs-body') as HTMLElement | null
        const top = s.dataset.g ? scrollMemo[s.dataset.g] : undefined
        if (body && typeof top === 'number') body.scrollTop = top
      })
      markFocus()
      syncAllTabs()
    })

    onMounted(() => {
      pane.value?.addEventListener('scroll', onScrollCapture, true)
      markFocus()
      void nextTick(() => { scrollToFocused(false); syncAllTabs() })
    })
    onBeforeUnmount(() => { pane.value?.removeEventListener('scroll', onScrollCapture, true) })

    return () => (
      <>
        <div class="ppane paper" ref={pane} innerHTML={html()} onClick={onClick} onPointerdown={onPointerDown}></div>
        <PaperDock />
      </>
    )
  },
})
