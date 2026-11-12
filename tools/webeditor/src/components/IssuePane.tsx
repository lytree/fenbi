import { defineComponent } from 'vue'
import { collectIssues, focusQuestion, state } from '../store'

/** 校验面板：把「发布时会被拦下 / 会被静默丢掉」的坑一次列出来，点一条跳到那道题 */
export default defineComponent({
  name: 'IssuePane',
  setup() {
    return () => {
      if (!state.paper) return <div class="pv-empty">还没有打开任何一组</div>
      const issues = collectIssues()
      if (!issues.length) {
        return (
          <div class="issue ok" style="cursor:default">
            没发现结构性问题，可以发布了
          </div>
        )
      }
      const errors = issues.filter((i) => i.level === 'error').length
      return (
        <>
          <div class="issue ok" style="cursor:default;color:#5b6a7c">
            共 {issues.length} 条 · {errors} 个错误（会拦下发布）· 点一条跳到那道题
          </div>
          {issues.map((it, k) => (
            <div
              key={k}
              class={['issue', it.level === 'error' ? 'e' : 'w']}
              onClick={() => focusQuestion(it.index)}
            >
              <span class="lv">{it.level === 'error' ? '!' : '·'}</span>
              <span class="msg">{it.message}</span>
            </div>
          ))}
        </>
      )
    }
  },
})
