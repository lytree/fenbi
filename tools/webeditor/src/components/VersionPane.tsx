import { defineComponent } from 'vue'
import {
  deleteVersion, loadVersions, restoreVersion, state, versionReason,
} from '../store'
import { formatTime } from '../util'

/**
 * 版本面板：这一组每次被覆盖之前，旧内容都在这儿留了一份。
 *
 * 能做的事只有两件——回滚、删存档。回滚本身也会先存一版当前内容，
 * 所以手滑滚错还能再滚回来。
 */
export default defineComponent({
  name: 'VersionPane',
  setup() {
    return () => {
      if (!state.paper) return <div class="pv-empty">还没有打开任何一组</div>
      if (state.paper.id <= 0) {
        return (
          <div class="pv-empty">
            这一组还没发布过，没有历史版本
            <br />
            覆盖发布之后，旧内容会自动存一版
          </div>
        )
      }

      return (
        <div class="ppane">
          <div class="ver-tip">
            <span>覆盖发布 / 回滚之前，旧内容会先存一版（每组留最近 20 版）。</span>
            <button class="link-btn" onClick={() => loadVersions()}>刷新</button>
          </div>

          {state.versionLoading ? <div class="pv-empty">读取中…</div> : null}

          {!state.versionLoading && !state.versions.length
            ? (
              <div class="pv-empty">
                还没有历史版本
                <br />
                下次覆盖发布时会自动存一份
              </div>
            )
            : null}

          {state.versions.map((v) => (
            <div key={v.id} class="ver-item">
              <div class="ver-main">
                <div class="ver-time">
                  {formatTime(v.savedAt)}
                  <span class="ver-reason">{versionReason(v.reason)}</span>
                </div>
                <div class="ver-meta">{v.paperCount} 卷 · {v.questionCount} 题</div>
              </div>
              <div class="ver-ops">
                <button class="ghost sm" onClick={() => restoreVersion(v)}>回滚</button>
                <button class="ghost sm danger" title="只删存档，不动题库" onClick={() => deleteVersion(v)}>删</button>
              </div>
            </div>
          ))}
        </div>
      )
    }
  },
})
