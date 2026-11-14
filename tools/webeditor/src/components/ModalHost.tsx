import { defineComponent, nextTick, ref, watch } from 'vue'
import { cancelWaiting, closeModal, ui } from '../store'

/**
 * 弹层宿主：改名、确认覆盖、发布报错、批量粘贴都走这里。
 * 没有引 UI 库，四种形态够用——文本框 / 确认 / 错误清单 / 大段粘贴。
 */
export default defineComponent({
  name: 'ModalHost',
  setup() {
    const draft = ref('')
    const inputRef = ref<HTMLInputElement | null>(null)
    const areaRef = ref<HTMLTextAreaElement | null>(null)

    watch(
      () => ui.modal,
      async (m) => {
        if (!m) return
        draft.value = m.value ?? ''
        await nextTick()
        const rows = m.rows ?? 1
        if (m.kind === 'paste' || (m.kind === 'text' && rows > 3)) {
          areaRef.value?.focus()
        } else if (m.kind === 'text') {
          inputRef.value?.focus()
          inputRef.value?.select()
        }
      },
      { immediate: true },
    )

    const ok = () => {
      const m = ui.modal
      if (!m) return
      if (m.kind === 'confirm') return closeModal(true)
      if (m.kind === 'errors') return closeModal()
      closeModal(draft.value)
    }

    const cancel = () => {
      const m = ui.modal
      if (!m) return
      // 「等手机确认」没有取消这一说，只有撤回请求
      if (m.kind === 'waiting') { void cancelWaiting(); return }
      closeModal(m.kind === 'confirm' ? false : null)
    }

    const onKeydown = (e: KeyboardEvent) => {
      const m = ui.modal
      if (!m) return
      if (e.key === 'Escape') { e.preventDefault(); cancel(); return }
      const singleLine = m.kind === 'text' && (m.rows ?? 1) <= 1
      if (e.key === 'Enter' && (singleLine || e.ctrlKey || e.metaKey)) {
        e.preventDefault()
        ok()
      }
    }

    return () => {
      const m = ui.modal
      if (!m) return <div class="modal-mask hidden"></div>
      const rows = m.rows ?? 1

      return (
        <div class="modal-mask" onClick={(e) => { if (e.target === e.currentTarget) cancel() }}>
          <div class="modal" onKeydown={onKeydown}>
            <h3>{m.title}</h3>

            {m.kind === 'waiting'
              ? (
                <div class="mbody">
                  <div class="wait-line">
                    <span class="spinner"></span>
                    <b>{m.message}</b>
                  </div>
                  <div class="hint" style="margin-top:10px">{m.label}</div>
                </div>
              )
              : m.kind === 'confirm'
                ? (
                <div class="mbody">
                  <div class="confirm-text">{m.message}</div>
                </div>
              )
              : m.kind === 'errors'
                ? (
                  <div class="mbody">
                    <div class="hint" style="margin:0 0 10px">按 App 的导入规则先卡了一遍，改完这几处再发布：</div>
                    <div class="error-text">{m.message}</div>
                  </div>
                )
                : (
                  <div class="mbody">
                    {m.label ? <div class="hint" style="margin:0 0 8px">{m.label}</div> : null}
                    {m.kind === 'paste'
                      ? (
                        <textarea
                          ref={areaRef}
                          class="field big"
                          rows={rows}
                          spellcheck={false}
                          value={draft.value}
                          placeholder={'1. 下列句子中，没有语病的一项是（  ）\nA. 第一项内容\nB. 第二项内容\nC. 第三项内容\nD. 第四项内容\n答案：B\n解析：这里写解析\n\n2. 下一题题干……\nA. ……\n答案：AC'}
                          onInput={(e) => { draft.value = (e.target as HTMLTextAreaElement).value }}
                        />
                      )
                      : rows > 3
                        ? (
                          <textarea
                            ref={areaRef}
                            class="field"
                            rows={rows}
                            spellcheck={false}
                            value={draft.value}
                            onInput={(e) => { draft.value = (e.target as HTMLTextAreaElement).value }}
                          />
                        )
                        : (
                          <input
                            ref={inputRef}
                            class="field"
                            value={draft.value}
                            spellcheck={false}
                            onInput={(e) => { draft.value = (e.target as HTMLInputElement).value }}
                          />
                        )}
                    {m.kind === 'paste'
                      ? (
                        <div class="hint">
                          每道题用 <code>1.</code> <code>2.</code> 编号，或者题与题之间空一行；<br />
                          认 <code>A.</code> <code>A、</code> <code>A)</code> 开头的选项行，
                          <code>答案：</code> <code>解析：</code> <code>材料：</code> 前缀；<br />
                          两个以上答案字母自动记成多选题。插进来之后再逐题核对一遍最稳。
                        </div>
                      )
                      : null}
                  </div>
                )}

            <div class="mfoot">
              {m.kind === 'errors'
                ? <button class="primary" onClick={ok}>知道了</button>
                : m.kind === 'waiting'
                  ? <button class="ghost" onClick={cancel}>撤回请求</button>
                  : (
                    <>
                      <button class="ghost" onClick={cancel}>取消</button>
                      <button class="primary" onClick={ok}>{m.okText ?? '确定'}</button>
                    </>
                  )}
            </div>
          </div>
        </div>
      )
    }
  },
})
