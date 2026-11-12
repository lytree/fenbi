import { defineComponent } from 'vue'
import {
  addSheet, askConfirm, askText, removeSheet, renameSheet, state, switchSheet,
} from '../store'

/** 顶部卷标签：一张工作表 = App 里的一份卷。双击改名，✕ 删除（发布后生效） */
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
      if (name && name.trim()) addSheet(name)
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
        <div class="sheetbar">
          {sheets.map((s, i) => (
            <button
              key={i}
              class={['sheet-tab', state.sheet === i && 'active']}
              title="双击改名"
              onClick={() => switchSheet(i)}
              onDblclick={() => rename(i)}
            >
              {s.name}
              <span class="pill">{s.questions.length}</span>
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
            </button>
          ))}
          <button class="sheet-add" onClick={create}>＋ 工作表</button>
        </div>
      )
    }
  },
})
