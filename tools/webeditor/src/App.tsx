import { defineComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import TopBar from './components/TopBar'
import Sidebar from './components/Sidebar'
import SheetBar from './components/SheetBar'
import QuestionList from './components/QuestionList'
import MaterialList from './components/MaterialList'
import GroupBoard from './components/GroupBoard'
import PreviewPane from './components/PreviewPane'
import IssuePane from './components/IssuePane'
import VersionPane from './components/VersionPane'
import ModalHost from './components/ModalHost'
import Toast from './components/Toast'
import {
  TYPE_SHORT, addQuestion, cancelWaiting, closeModal, currentQuestions, filtersActive, flushDraft,
  issueCounts, level1Options, level2Options, loadSets, loadVersions, maybeRestoreDraft,
  openPasteDialog, openSet, publish, resetFilters, showErrors, startDraftAutoSave, state,
  stopDraftAutoSave, switchSource, toast, typeCounts, ui, visibleIndexes,
} from './store'
import type { QuestionType } from './types'
import { handleFileInput } from './images'

export default defineComponent({
  name: 'App',
  setup() {
    /** 低于这个宽度默认收起预览，把地方让给编辑区；手动开合后按手动来 */
    const NARROW_PREVIEW = 1180
    const previewHidden = ref(typeof window !== 'undefined' && window.innerWidth < NARROW_PREVIEW)
    let previewManual: boolean | null = null

    const syncViewport = () => {
      const narrow = window.innerWidth < NARROW_PREVIEW
      previewHidden.value = previewManual ?? narrow
    }

    const setPreviewHidden = (hidden: boolean) => {
      previewManual = hidden
      previewHidden.value = hidden
    }

    onMounted(async () => {
      window.addEventListener('keydown', onKeydown)
      window.addEventListener('beforeunload', onBeforeUnload)
      window.addEventListener('resize', syncViewport)
      document.addEventListener('visibilitychange', onVisibility)
      syncViewport()
      // 停手 700ms 存一次之外，每 30 秒再兜一次底；关页面时立刻落盘
      startDraftAutoSave()
      let sourceReady = false
      try {
        await loadSets()
        sourceReady = true
      } catch {
        // 手机没连上时退回本地试卷包：开发服务器会扫描 qbank_out / sample / templates
        try {
          await switchSource('local')
          sourceReady = true
          toast('手机题库没连上，已切到本地试卷包', '')
        } catch {
          // 两边都打不开，下面统一报错
        }
      }
      if (!sourceReady) {
        showErrors(
          '连不上题库',
          '手机上的「局域网编辑器」没开，本地试卷包也没读到。\n\n' +
            '用手机重新打开带口令的地址，或者在 tools/webeditor 下跑 pnpm dev 使用本地试卷包。',
        )
        return
      }
      try {
        // 上次还有没发布的改动就问一句要不要接着改；不然就从手机上当前内容打开
        const restored = await maybeRestoreDraft()
        if (restored) return
        if (state.source === 'phone') {
          const first = state.sets.find((s) => s.id > 0)
          if (first) await openSet(first.id, { force: true })
        }
      } catch (e) {
        // 这里也可能是「这组太大，超过单卷上限」这类业务错误，别一律说成连不上
        const msg = e instanceof Error ? e.message : '加载失败'
        toast(`打不开题库：${msg}`, 'err')
        showErrors(
          '打不开题库',
          `${msg}\n\n如果是连不上：确认手机上「局域网编辑器」还开着，再用它显示的地址重新打开这个页面。`,
        )
      }
    })

    onBeforeUnmount(() => {
      window.removeEventListener('keydown', onKeydown)
      window.removeEventListener('beforeunload', onBeforeUnload)
      window.removeEventListener('resize', syncViewport)
      document.removeEventListener('visibilitychange', onVisibility)
      stopDraftAutoSave()
    })

    const onVisibility = () => {
      // 切到后台（合盖子、切标签页）时手机可能被系统杀掉，先把草稿落地
      if (document.visibilityState === 'hidden') flushDraft()
    }

    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (!state.dirty) return
      flushDraft()
      e.preventDefault()
      e.returnValue = ''
    }

    const onKeydown = (e: KeyboardEvent) => {
      const mod = e.ctrlKey || e.metaKey
      if (mod && e.key.toLowerCase() === 's') {
        e.preventDefault()
        publish()
        return
      }
      if (mod && e.shiftKey && e.key.toLowerCase() === 'n') {
        e.preventDefault()
        if (state.paper) addQuestion()
        return
      }
      if (e.key === 'Escape' && ui.modal) {
        // 等手机确认时按 Esc 等于撤回请求，别只是把弹窗关掉留个悬案
        if (ui.modal.kind === 'waiting') { void cancelWaiting(); return }
        closeModal(ui.modal.kind === 'confirm' ? false : null)
      }
    }

    const counts = () => issueCounts()

    // 搜索防抖：过滤一次要扫整卷（逐题算校验），按字实时过滤在千题规模下会明显掉帧
    const queryInput = ref(state.query)
    let queryTimer: number | undefined
    const onQueryInput = (e: Event) => {
      const v = (e.target as HTMLInputElement).value
      queryInput.value = v
      window.clearTimeout(queryTimer)
      queryTimer = window.setTimeout(() => { state.query = v }, 150)
    }

    return () => (
      <div class="app-root">
        <TopBar />

        <main class={['layout', previewHidden.value && 'no-preview']}>
          <Sidebar />

          <section class="editor">
            <div class="editor-body">
              <SheetBar />

              <div class="editor-main">
                <div class="toolbar">
                  <div class="seg" title="编辑视图：按题平铺 / 按材料成块">
                    <button
                      class={['seg-btn', state.view === 'list' && 'on']}
                      data-view="list"
                      onClick={() => { state.view = 'list' }}
                    >
                      题目 {currentQuestions().length}
                    </button>
                    <button
                      class={['seg-btn', state.view === 'material' && 'on']}
                      data-view="material"
                      onClick={() => { state.view = 'material' }}
                    >
                      材料 {state.groups.length}
                    </button>
                    <button
                      class={['seg-btn', state.view === 'board' && 'on']}
                      data-view="board"
                      title="拖拽题目分配材料组"
                      onClick={() => { state.view = 'board' }}
                    >
                      分组 {state.groups.length}
                    </button>
                  </div>
                  <input
                    class="search"
                    value={queryInput.value}
                    spellcheck={false}
                    placeholder="搜索题干 / 材料 / 答案…"
                    onInput={onQueryInput}
                  />
                  <select
                    class="select"
                    title="按状态筛"
                    value={state.filter}
                    onChange={(e) => { state.filter = (e.target as HTMLSelectElement).value as typeof state.filter }}
                  >
                    <option value="all">全部题目</option>
                    <option value="issue">只看有问题</option>
                    <option value="noanswer">只看缺答案</option>
                    <option value="image">只看带图</option>
                    <option value="group">只看材料组</option>
                  </select>
                  <select
                    class="select"
                    title="按题型筛"
                    value={state.typeFilter}
                    onChange={(e) => { state.typeFilter = (e.target as HTMLSelectElement).value as typeof state.typeFilter }}
                  >
                    <option value="all">全部题型</option>
                    {(Object.keys(TYPE_SHORT) as QuestionType[]).map((t) => {
                      const n = typeCounts()[t] ?? 0
                      return (
                        <option key={t} value={t} disabled={!n}>
                          {TYPE_SHORT[t]}（{n}）
                        </option>
                      )
                    })}
                  </select>
                  <select
                    class="select"
                    title="按一级题型筛（科目归一后的大类）"
                    value={state.subjectFilter}
                    onChange={(e) => {
                      state.subjectFilter = (e.target as HTMLSelectElement).value
                      // 换一级后二级的候选跟着变，留着上一级的二级只会筛出空列表
                      state.subTopicFilter = 'all'
                    }}
                  >
                    <option value="all">全部一级题型</option>
                    {level1Options().map((o) => (
                      <option key={o.value} value={o.value}>{o.label}（{o.count}）</option>
                    ))}
                  </select>
                  <select
                    class="select"
                    title="按二级题型筛（知识点，跟一级联动）"
                    value={state.subTopicFilter}
                    onChange={(e) => { state.subTopicFilter = (e.target as HTMLSelectElement).value }}
                  >
                    <option value="all">全部二级题型</option>
                    {level2Options().map((o) => (
                      <option key={o.value} value={o.value}>{o.label}（{o.count}）</option>
                    ))}
                  </select>
                {filtersActive()
                  ? (
                    <>
                      <span class="filter-info">
                        筛出 {visibleIndexes().length} / {currentQuestions().length} 题
                      </span>
                      <button class="ghost sm" onClick={resetFilters}>清除筛选</button>
                    </>
                  )
                  : null}
                <span class="grow"></span>
                <button class="ghost sm" disabled={!state.paper} onClick={openPasteDialog}>批量粘贴</button>
                <button class="primary sm" disabled={!state.paper} onClick={() => addQuestion()}>＋ 新题</button>
                </div>

                <div class="qlist">
                  {state.view === 'material'
                    ? <MaterialList />
                    : state.view === 'board'
                      ? <GroupBoard />
                      : <QuestionList />}
                </div>
              </div>
            </div>

            {!state.paper
              ? (
                <div class="empty">
                  <div class="empty-card">
                    <h2>左边选一组，或者新建一组</h2>
                    <p>
                      「一组」就是 App 首页最上面那一层标题，组里的每份卷对应工作表列表里的一项。
                      改完点<strong>发布到 App</strong>，题目直接入库，手机上就能刷。
                    </p>
                  </div>
                </div>
              )
              : null}
          </section>

          {previewHidden.value
            ? null
            : (
              <aside class="preview">
                <div class="ptabs">
                  <button
                    class={['ptab', state.pane === 'preview' && 'active']}
                    onClick={() => { state.pane = 'preview' }}
                  >
                    卷面预览
                  </button>
                  <button
                    class={['ptab', state.pane === 'issue' && 'active']}
                    onClick={() => { state.pane = 'issue' }}
                  >
                    校验
                    <span class={['pill', counts().errors > 0 && 'hot']}>
                      {counts().errors + counts().warns}
                    </span>
                  </button>
                  <button
                    class={['ptab', state.pane === 'version' && 'active']}
                    onClick={() => { state.pane = 'version'; void loadVersions() }}
                  >
                    版本
                    <span class="pill">{state.versions.length}</span>
                  </button>
                  <span class="grow"></span>
                  <button class="icon-btn" title="收起预览" onClick={() => setPreviewHidden(true)}>⟩</button>
                </div>
                {state.pane === 'preview'
                  ? <PreviewPane />
                  : state.pane === 'issue'
                    ? <IssuePane />
                    : <VersionPane />}
              </aside>
            )}

          {previewHidden.value
            ? <button class="preview-fab" title="展开预览" onClick={() => setPreviewHidden(false)}>⟨</button>
            : null}
        </main>

        <ModalHost />
        <Toast />

        <input
          id="file-image"
          type="file"
          accept="image/*"
          multiple
          style="display:none"
          onChange={(e) => handleFileInput((e.target as HTMLInputElement).files)}
        />
      </div>
    )
  },
})
