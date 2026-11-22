import { defineComponent } from 'vue'
import IssuePane from './IssuePane'
import VersionPane from './VersionPane'
import ResourcePane from './ResourcePane'
import { closeTool, issueCounts, state, ui } from '../store'

/**
 * 工具弹框：校验 / 版本 / 资源。
 *
 * 这三块以前是右栏的三个页签——把卷面预览 / 答案解析 / 记录挤成六选三，
 * 还容易被当成「无关紧要的辅助页」忽略掉。改成弹框后右栏只剩卷面三页，
 * 入口挂在页签条右端，带计数徽标：有错误时红底白字，扫一眼就知道能不能发布。
 *
 * 弹框比普通确认框宽（720px）：资源面板有缩略图、版本面板一行放得下操作按钮，
 * 窄了就得换行。Esc / 点遮罩 / 右上角 × 都能关。
 */

const TITLE: Record<'issue' | 'version' | 'resource', string> = {
  issue: '校验',
  version: '版本',
  resource: '资源',
}

export default defineComponent({
  name: 'ToolDialog',
  setup() {
    return () => {
      const tool = ui.tool
      if (!tool || state.mode !== 'paper') return null
      const counts = issueCounts()

      const badge = () => {
        if (tool === 'issue') {
          const n = counts.errors + counts.warns
          return n
            ? <span class={['tool-badge', counts.errors > 0 && 'hot']}>{n}</span>
            : <span class="tool-badge ok">✓</span>
        }
        if (tool === 'version') {
          return state.versions.length ? <span class="tool-badge">{state.versions.length}</span> : null
        }
        return state.resources.length ? <span class="tool-badge">{state.resources.length}</span> : null
      }

      return (
        <div class="modal-mask tool-mask" onClick={(e) => { if (e.target === e.currentTarget) closeTool() }}>
          <div class="modal tool-dialog" data-tool={tool}>
            <h3 class="tool-head">
              <span class="tool-title">{TITLE[tool]}</span>
              {badge()}
              <button class="icon-btn tool-close" title="关闭（Esc）" onClick={closeTool}>×</button>
            </h3>
            <div class="tool-body">
              {tool === 'issue'
                ? <IssuePane />
                : tool === 'version'
                  ? <VersionPane />
                  : <ResourcePane />}
            </div>
          </div>
        </div>
      )
    }
  },
})
