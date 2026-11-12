import { defineComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import TopBar from './components/TopBar'
import Sidebar from './components/Sidebar'
import SheetBar from './components/SheetBar'
import QuestionList from './components/QuestionList'
import PreviewPane from './components/PreviewPane'
import IssuePane from './components/IssuePane'
import VersionPane from './components/VersionPane'
import ModalHost from './components/ModalHost'
import Toast from './components/Toast'
import {
  addQuestion, cancelWaiting, closeModal, issueCounts, loadSets, loadVersions,
  openPasteDialog, openSet, publish, showErrors, state, toast, ui,
} from './store'
import { handleFileInput } from './images'

export default defineComponent({
  name: 'App',
  setup() {
    const previewHidden = ref(false)

    onMounted(async () => {
      window.addEventListener('keydown', onKeydown)
      window.addEventListener('beforeunload', onBeforeUnload)
      try {
        await loadSets()
        const first = state.sets.find((s) => s.id > 0)
        if (first) await openSet(first.id, { force: true })
      } catch (e) {
        const msg = e instanceof Error ? e.message : '加载失败'
        toast(`连不上手机端服务：${msg}`, 'err')
        showErrors('连不上手机端服务', `${msg}\n\n确认手机上「局域网编辑器」还开着，再用它显示的地址重新打开这个页面。`)
      }
    })

    onBeforeUnmount(() => {
      window.removeEventListener('keydown', onKeydown)
      window.removeEventListener('beforeunload', onBeforeUnload)
    })

    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      if (!state.dirty) return
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

    return () => (
      <div class="app-root">
        <TopBar />

        <main class="layout" style={previewHidden.value ? 'grid-template-columns:236px minmax(0,1fr)' : undefined}>
          <Sidebar />

          <section class="editor">
            <SheetBar />

            <div class="toolbar">
              <input
                class="search"
                value={state.query}
                spellcheck={false}
                placeholder="搜索题干 / 材料 / 答案…"
                onInput={(e) => { state.query = (e.target as HTMLInputElement).value }}
              />
              <select
                class="select"
                value={state.filter}
                onChange={(e) => { state.filter = (e.target as HTMLSelectElement).value as typeof state.filter }}
              >
                <option value="all">全部题目</option>
                <option value="issue">只看有问题</option>
                <option value="noanswer">只看缺答案</option>
                <option value="image">只看带图</option>
                <option value="group">只看材料组</option>
              </select>
              <span class="grow"></span>
              <button class="ghost sm" disabled={!state.paper} onClick={openPasteDialog}>批量粘贴</button>
              <button class="primary sm" disabled={!state.paper} onClick={() => addQuestion()}>＋ 新题</button>
            </div>

            <div class="qlist">
              <QuestionList />
            </div>

            {!state.paper
              ? (
                <div class="empty">
                  <div class="empty-card">
                    <h2>左边选一组，或者新建一组</h2>
                    <p>
                      「一组」就是 App 首页最上面那一层标题，组里的每份卷对应顶部一个标签页。
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
                  <button class="icon-btn" title="收起预览" onClick={() => { previewHidden.value = true }}>⟩</button>
                </div>
                {state.pane === 'preview'
                  ? <PreviewPane />
                  : state.pane === 'issue'
                    ? <IssuePane />
                    : <VersionPane />}
              </aside>
            )}

          {previewHidden.value
            ? <button class="preview-fab" title="展开预览" onClick={() => { previewHidden.value = false }}>⟨</button>
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
