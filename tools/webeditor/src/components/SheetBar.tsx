import { defineComponent } from 'vue'
import {
  addSheet, askConfirm, askText, removeSheet, renameSheet, state, switchSheet,
} from '../store'

/**
 * 工作表列表：一个工作表 = App 里的一份卷。
 *
 * 原来是一条横排标签，卷一多就挤成一条细长条、还得横向滚；现在改成编辑区左侧的竖排列表，
 * 一眼能看到所有卷和各自的题数，点一条切卷，双击改名，✕ 删除（发布后生效）。
 * 列表里不提供「全部」这种把多卷并在一起的选项——App 那边也是一份一份卷开的，并起来反而不像。
 */
export default defineComponent({
  name: 'SheetBar',
  setup() {
    const create = async () => {
      const name = await askText({
        title: '新建工作表',
        label: '一个工作表 = App 里的一份试卷，表名就是卷名',
        value: `卷${(state.paper?.sheets.length ?? 0) + 1}`,
        rows: 1,
      })
      if (name && name.trim()) addSheet(name.trim())
    }

    const rename = async (index: number) => {
      const current = state.paper?.sheets[index]?.name ?? ''
      const name = await askText({
        title: '重命名工作表',
        label: '卷名（App 里显示的名字）',
        value: current,
        rows: 1,
      })
      if (name && name.trim()) renameSheet(index, name)
    }

    const drop = async (index: number) => {
      const sheet = state.paper?.sheets[index]
      if (!sheet || (state.paper?.sheets.length ?? 0) <= 1) return
      const ok = await askConfirm(
        '删除这张工作表？',
        `「${sheet.name}」里的 ${sheet.questions.length} 道题会一起没掉（发布后生效）`,
        '删除',
      )
      if (ok) removeSheet(index)
    }

    return () => {
      if (!state.paper) return null
      const sheets = state.paper.sheets
      return (
        <aside class="sheetbar">
          <div class="sb-head">
            <span class="sb-title">工作表</span>
            <span class="sb-count">{sheets.length}</span>
          </div>

          <div class="sb-list">
            {sheets.map((s, i) => (
              <div
                key={i}
                class={['sb-item', state.sheet === i && 'active']}
                title="双击改名"
                onClick={() => switchSheet(i)}
                onDblclick={() => rename(i)}
              >
                <div class="nm">{s.name}</div>
                <div class="mt">
                  {s.questions.length} 题
                  {sheets.length > 1
                    ? (
                      <span
                        class="x"
                        title="删除工作表"
                        onClick={(e) => { e.stopPropagation(); drop(i) }}
                      >
                        ✕
                      </span>
                    )
                    : null}
                </div>
              </div>
            ))}
          </div>

          <button class="sheet-add" onClick={create}>＋ 工作表</button>
        </aside>
      )
    }
  },
})
