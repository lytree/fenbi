import { defineComponent, onMounted, ref } from 'vue'
import PreviewPane from './PreviewPane'
import AnswerPane from './AnswerPane'
import RecordsPane from './RecordsPane'
import Toast from './Toast'
import { currentQuestions, loadSets, openSet, state } from '../store'
import { initTheme, theme, toggleTheme } from '../theme'
import { initScale } from '../scale'
import { FULL_PANES, FULL_PANE_HINT, FULL_PANE_LABEL, editorUrl, fullUrl } from '../fullview'
import type { FullPane, FullQuery } from '../fullview'

/**
 * 单独查看页：把右栏的「卷面预览 / 答案解析 / 记录」整页铺开看。
 *
 * 和编辑器共用同一个 bundle 与同一份 store，只是换了外壳：
 * 一条窄顶栏（标题 + 三页切换 + 换卷 + 刷新 + 回编辑器）+ 居中的一栏正文。
 * 地址记住了当前看的是哪一组 / 哪一卷 / 哪一页，刷新和分享都落在原地。
 *
 * 只读：这里没有发布、没有编辑，改动请回编辑器。
 */
export default defineComponent({
  name: 'FullView',
  props: {
    query: { type: Object as () => FullQuery, required: true },
  },
  setup(props) {
    const pane = ref<FullPane>(props.query.pane)
    const ready = ref(false)
    const error = ref('')
    /** 记录页当前落在哪条记录上（0 = 在列表）：跟着地址走，刷新还能停在同一条 */
    const recordId = ref(props.query.recordId)

    const sheet = () => state.paper?.sheets[state.sheet] ?? null
    const questions = () => currentQuestions().length

    /** 当前状态写回地址：换页 / 换卷 / 点开哪条记录后刷新都还停在原地 */
    const syncUrl = () => {
      const url = fullUrl(pane.value, {
        setId: state.paper?.id ?? 0,
        sheetId: sheet()?.id ?? 0,
        recordId: pane.value === 'records' ? recordId.value : 0,
      })
      try { window.history.replaceState(null, '', url) } catch { /* file:// 下不让改就随它 */ }
    }

    /** 地址里指定的组不可用（删了 / 归档了）就退回第一组 */
    const pickSetId = (): number => {
      const want = props.query.setId
      if (want && state.sets.some((s) => s.id === want)) return want
      return state.sets.find((s) => s.id > 0 && !s.archived)?.id ?? 0
    }

    const load = async () => {
      ready.value = false
      error.value = ''
      try {
        await loadSets()
      } catch {
        error.value =
          '连不上题库：手机上的「局域网编辑器」没开，或者口令变了。' +
          '用手机显示的那个带口令的地址重新打开这个页面。'
        return
      }
      const id = pickSetId()
      if (!id) {
        error.value = '题库里还没有试卷组，先去手机上导入一份卷。'
        return
      }
      try {
        // force：这是只读页，不该弹「还没发布」的确认框
        await openSet(id, { force: true })
      } catch (e) {
        error.value = e instanceof Error ? e.message : '打不开题库'
        return
      }
      const sheets = state.paper?.sheets ?? []
      const want = props.query.sheetId
      const idx = want ? sheets.findIndex((s) => s.id === want) : -1
      state.sheet = idx >= 0 ? idx : 0
      // 打开一组时 store 默认把第一题设成「当前题」——这是只读页，没有编辑区可联动，
      // 留一圈聚焦光会给第一题凭空镀个边，看着像选中了什么
      state.focused = null
      syncUrl()
      ready.value = true
    }

    const switchPane = (p: FullPane) => {
      pane.value = p
      syncUrl()
    }

    const onSheetChange = (e: Event) => {
      state.sheet = Number((e.target as HTMLSelectElement).value)
      syncUrl()
    }

    const backToEditor = () => {
      // 编辑器就是不带 view 的那个地址。用 window.open 打开时带了 noopener，
      // 这边拿不到 opener，所以统一走跳转（不依赖「关窗口」这种会被拦的动作）
      if (window.opener) { window.close(); return }
      window.location.href = editorUrl()
    }

    onMounted(() => {
      initTheme()
      initScale()
      void load()
    })

    const meta = (): string => {
      const p = state.paper
      if (!p) return '—'
      const s = sheet()
      return [p.name, s?.name, `${questions()} 题`].filter(Boolean).join(' · ')
    }

    const body = () => {
      if (error.value) {
        return (
          <div class="full-fallback">
            <div class="full-fallback-card">
              <h2>打不开这一页</h2>
              <p>{error.value}</p>
              <div class="full-fallback-ops">
                <button class="ghost sm" onClick={() => void load()}>重试</button>
                <button class="primary sm" onClick={backToEditor}>回到编辑器</button>
              </div>
            </div>
          </div>
        )
      }
      if (!ready.value) return <div class="full-loading">读取中…</div>
      if (!state.paper || !sheet()) return <div class="full-loading">这份卷还不存在</div>
      return (
        <div class={['fullsheet', `is-${pane.value}`]}>
          {pane.value === 'preview'
            ? <PreviewPane />
            : pane.value === 'answer'
              ? <AnswerPane />
              : (
                <RecordsPane
                  initialRecordId={recordId.value}
                  standalone
                  onRecordChange={(id) => { recordId.value = id; syncUrl() }}
                />
              )}
        </div>
      )
    }

    return () => (
      <div class="full-root">
        <header class="fullbar">
          <div class="full-brand">
            <span class="full-logo">题</span>
            <div class="full-brand-text">
              <b>{FULL_PANE_LABEL[pane.value]}</b>
              <span class="full-meta">
                {FULL_PANE_HINT[pane.value]}
                {state.paper ? ` · ${meta()}` : ''}
              </span>
            </div>
          </div>

          <div class="seg full-tabs" title="换一页看">
            {FULL_PANES.map((p) => (
              <button
                key={p}
                class={['seg-btn', pane.value === p && 'on']}
                onClick={() => switchPane(p)}
              >
                {FULL_PANE_LABEL[p]}
              </button>
            ))}
          </div>

          <div class="full-actions">
            {state.paper && state.paper.sheets.length > 1
              ? (
                <select class="select" title="换一份卷" value={String(state.sheet)} onChange={onSheetChange}>
                  {state.paper.sheets.map((s, i) => (
                    <option key={`${s.id}-${i}`} value={String(i)}>{s.name}</option>
                  ))}
                </select>
              )
              : null}
            <button
              class="ghost sm"
              title={theme.mode === 'dark' ? '切到白天' : '切到夜间'}
              onClick={toggleTheme}
            >
              {theme.mode === 'dark' ? '白天' : '夜间'}
            </button>
            <button class="ghost sm" disabled={!ready.value} onClick={() => void load()}>刷新</button>
            <button class="primary sm" title="回到编辑器（改题、发布都在那边）" onClick={backToEditor}>回到编辑器</button>
          </div>
        </header>

        <main class="fullmain">{body()}</main>
        <Toast />
      </div>
    )
  },
})
