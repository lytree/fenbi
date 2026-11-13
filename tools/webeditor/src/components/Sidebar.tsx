import { defineComponent } from 'vue'
import {
  askConfirm, askText, loadLocalPackages, loadSets, newPaper, openLocalPackage, openSet,
  requestDeleteSet, state, switchSource, toast,
} from '../store'

/** 左侧：App 里现有的「导入组」列表。点一组就打开它，改完发布回去 */
export default defineComponent({
  name: 'Sidebar',
  setup() {
    const create = async () => {
      if (state.dirty) {
        const ok = await askConfirm('当前这组还没发布', '新建会丢掉刚才的改动，确定吗？', '丢掉改动')
        if (!ok) return
      }
      const name = await askText({
        title: '新建一组',
        label: '组名：App 首页最上面那一层标题，发布后还能在 App 里改',
        value: `新试卷-${new Date().toISOString().slice(5, 10)}`,
        rows: 1,
      })
      if (!name || !name.trim()) return
      newPaper(name.trim())
      toast('点「＋ 新题」开始，加完发布到 App', 'ok')
    }

    const refresh = async () => {
      if (state.dirty) {
        const ok = await askConfirm('当前改动还没发布', '刷新会丢掉这些改动，确定吗？', '丢掉改动')
        if (!ok) return
      }
      try {
        const keepId = state.paper && state.paper.id > 0 ? state.paper.id : 0
        await loadSets()
        if (keepId) await openSet(keepId, { force: true })
        toast('列表已刷新', 'ok')
      } catch (e) {
        toast(e instanceof Error ? e.message : '刷新失败', 'err')
      }
    }

    const pick = async (id: number, editable: boolean) => {
      if (!editable) {
        toast('「未分组」里的卷不支持在线编辑，先在 App 里归到某个标题下', 'err')
        return
      }
      if (state.paper && state.paper.id === id) return
      try {
        await openSet(id)
      } catch (e) {
        toast(e instanceof Error ? e.message : '打开失败', 'err')
      }
    }

    const pickLocal = async (id: string) => {
      if (state.local?.id === id) return
      try {
        await openLocalPackage(id)
      } catch (e) {
        toast(e instanceof Error ? e.message : '打开本地试卷包失败', 'err')
      }
    }

    const refreshLocal = async () => {
      try {
        await loadLocalPackages()
        toast(`找到 ${state.localPackages.length} 份本地试卷包`, 'ok')
      } catch (e) {
        toast(e instanceof Error ? e.message : '读取本地试卷包失败', 'err')
      }
    }

    const changeSource = async (src: 'phone' | 'local') => {
      try {
        await switchSource(src)
      } catch (e) {
        toast(e instanceof Error ? e.message : '切换数据源失败', 'err')
      }
    }

    const sourceBar = () => (
      <div class="source-seg">
        <button
          class={['source-btn', state.source === 'phone' && 'on']}
          data-source="phone"
          onClick={() => changeSource('phone')}
        >
          手机题库
        </button>
        <button
          class={['source-btn', state.source === 'local' && 'on']}
          data-source="local"
          onClick={() => changeSource('local')}
        >
          本地试卷包
        </button>
      </div>
    )

    const localSidebar = () => (
      <>
        <div class="side-head">
          <button class="ghost sm" onClick={refreshLocal}>刷新本地列表</button>
        </div>
        <div class="side-list">
          {state.localPackages.length === 0
            ? (
              <div class="side-empty">
                没找到本地 JSON 试卷包<br />
                把文件放到 qbank_out / sample / templates 下
              </div>
            )
            : state.localPackages.map((p) => (
              <div
                key={p.id}
                class={['side-item', state.local?.id === p.id && 'active']}
                title={p.path}
                onClick={() => pickLocal(p.id)}
              >
                <div>
                  <div class="nm">{p.title}</div>
                  <div class="mt">{p.questionCount} 题 · 本地 JSON</div>
                </div>
              </div>
            ))}
        </div>
        <div class="side-foot">
          <span class="dot"></span>
          <span>{state.localPackages.length} 份本地试卷</span>
        </div>
      </>
    )

    return () => (
      <aside class="sidebar">
        {sourceBar()}
        {state.source === 'local'
          ? localSidebar()
          : (
            <>
              <div class="side-head">
                <button class="primary sm" onClick={create}>＋ 新建一组</button>
                <button class="ghost sm" onClick={refresh}>刷新列表</button>
              </div>

              <div class="side-list">
                {state.sets.length === 0
                  ? <div class="side-empty">App 里还没有试卷<br />点上面「新建一组」开始</div>
                  : state.sets.map((s) => (
                    <div
                      key={s.id}
                      class={['side-item', state.paper?.id === s.id && 'active']}
                      title={s.title}
                      onClick={() => pick(s.id, s.editable)}
                    >
                      <div>
                        <div class="nm">{s.title}</div>
                        <div class="mt">
                          {s.paperCount} 卷 · {s.questionCount} 题
                          {s.missingAnswers ? ` · 缺 ${s.missingAnswers} 答案` : ''}
                          {s.editable ? null : <span class="tag">未归组</span>}
                        </div>
                      </div>
                      {s.editable
                        ? (
                          <div class="ops">
                            <button
                              title="删除这一组：请求会发到手机上确认"
                              onClick={(e) => {
                                e.stopPropagation()
                                requestDeleteSet(s.id, s.title)
                              }}
                            >
                              删除
                            </button>
                          </div>
                        )
                        : null}
                    </div>
                  ))}
              </div>

              <div class="side-foot">
                <span class="dot"></span>
                <span>{state.sets.length} 组 · 共 {state.questionTotal} 题</span>
              </div>
            </>
          )}
      </aside>
    )
  },
})
