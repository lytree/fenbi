import { defineComponent } from 'vue'
import type { PaperSetInfo } from '../types'
import {
  askConfirm, askText, loadReciteSubjects, loadSets, mindmap, newPaper, openReciteSubject,
  openSet, recite, requestDeleteSet, setSetArchived, state, toast,
} from '../store'

/** 左侧：题库模式 = App 里的「导入组」列表；背诵模式 = 词库 / 政治 / 常识 / 思维导图 四块内容 */
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

    const refreshRecite = () => void loadReciteSubjects()

    /** 归档一整组：数据不删，只是从列表和 App 首页收起来，随时能恢复 */
    const archive = async (s: PaperSetInfo) => {
      if (state.paper?.id === s.id && state.dirty) {
        const ok = await askConfirm(
          '正在编辑的这一组还没发布',
          '归档后编辑器会关掉，这些没发布的改动不会保留。确定归档吗？',
          '归档',
        )
        if (!ok) return
      } else {
        const ok = await askConfirm(
          `归档「${s.title}」？`,
          '归档后这组不再出现在列表和 App 首页，题目、作答记录都保留，随时能恢复。',
          '归档',
        )
        if (!ok) return
      }
      await setSetArchived(s.id, true)
    }

    const restore = async (s: PaperSetInfo) => {
      const ok = await askConfirm(
        `恢复「${s.title}」？`,
        '恢复后这组回到编辑列表，App 首页也会重新显示。',
        '恢复',
      )
      if (!ok) return
      await setSetArchived(s.id, false)
    }

    return () => {
      if (state.mode === 'recite') {
        return (
          <aside class="sidebar">
            <div class="side-head">
              <button class="ghost sm" onClick={refreshRecite}>刷新</button>
            </div>
            <div class="side-list">
              <div
                class={['side-item', recite.subject === 'words' && 'active']}
                onClick={() => void openReciteSubject('words')}
              >
                <div>
                  <div class="nm">词库</div>
                  <div class="mt">{recite.wordsTotal || '…'} 条词条 · 逐条编辑</div>
                </div>
              </div>
              {recite.subjects.map((s) => (
                <div
                  key={s.id}
                  class={['side-item', recite.subject === s.id && 'active']}
                  title={s.intro}
                  onClick={() => void openReciteSubject(s.id)}
                >
                  <div>
                    <div class="nm">{s.title}</div>
                    <div class="mt">
                      {s.kind === 'library'
                        ? `${s.tagCount ?? 0} 标签 · ${s.itemCount ?? 0} 条`
                        : `${s.categoryCount ?? 0} 分类 · ${s.cardCount ?? 0} 卡`}
                      {s.hasEdits ? <span class="tag">有覆盖版</span> : <span class="tag">内置</span>}
                    </div>
                  </div>
                </div>
              ))}
              <div
                class={['side-item', recite.subject === 'mindmap' && 'active']}
                title="原书 10 个板块的导图，按板块 / 主题 / 层级浏览和编辑"
                onClick={() => void openReciteSubject('mindmap')}
              >
                <div>
                  <div class="nm">思维导图</div>
                  <div class="mt">
                    {mindmap.catalog
                      ? `${mindmap.catalog.sections.length} 板块 · ${mindmap.catalog.topicCount ?? 0} 主题`
                      : '点开才读'}
                    {mindmap.catalog?.hasEdits ? <span class="tag">有覆盖版</span> : null}
                  </div>
                </div>
              </div>
              {recite.subjects.length === 0
                ? <div class="side-empty">读不到背诵词库<br />确认手机端局域网编辑器还开着</div>
                : null}
            </div>
            <div class="side-foot">
              <span class="dot"></span>
              <span>大纲 / 常识整份保存要手机确认</span>
            </div>
          </aside>
        )
      }

      const active = state.sets.filter((s) => !s.archived)
      const archived = state.sets.filter((s) => s.archived)

      return (
        <aside class="sidebar">
          <div class="side-head">
            <button class="primary sm" onClick={create}>＋ 新建一组</button>
            <button class="ghost sm" onClick={refresh}>刷新列表</button>
          </div>

          <div class="side-list">
            {state.sets.length === 0
              ? <div class="side-empty">App 里还没有试卷<br />点上面「新建一组」开始</div>
              : active.length === 0
                ? <div class="side-empty">试卷都归档了<br />在下面「归档」里点「恢复」就能拿回来</div>
                : active.map((s) => (
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
                        {s.disabledQuestions ? ` · 禁用 ${s.disabledQuestions}` : ''}
                        {s.editable ? null : <span class="tag">未归组</span>}
                      </div>
                    </div>
                    {s.editable
                      ? (
                        <div class="ops">
                          <button
                            class="arch"
                            title="归档这一组：题目与记录都保留，随时能恢复"
                            onClick={(e) => {
                              e.stopPropagation()
                              void archive(s)
                            }}
                          >
                            归档
                          </button>
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

            {archived.length > 0
              ? (
                <div class="side-archive">
                  <div class="side-archive-head">
                    <span>归档（{archived.length}）</span>
                    <span class="hint">点「恢复」回到列表</span>
                  </div>
                  {archived.map((s) => (
                    <div key={s.id} class="side-item archived" title={s.title}>
                      <div>
                        <div class="nm">{s.title}</div>
                        <div class="mt">
                          {s.paperCount} 卷 · {s.questionCount} 题
                          {s.disabledQuestions ? ` · 禁用 ${s.disabledQuestions}` : ''}
                        </div>
                      </div>
                      <div class="ops always">
                        <button
                          class="arch restore"
                          title="恢复到编辑列表与 App 首页"
                          onClick={(e) => {
                            e.stopPropagation()
                            void restore(s)
                          }}
                        >
                          恢复
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )
              : null}
          </div>

          <div class="side-foot">
            <span class="dot"></span>
            <span>
              {active.length} 组 · 共 {state.questionTotal} 题
              {archived.length ? ` · 归档 ${archived.length}` : ''}
            </span>
          </div>
        </aside>
      )
    }
  },
})
