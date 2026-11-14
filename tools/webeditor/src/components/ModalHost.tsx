import { defineComponent, nextTick, ref, watch } from 'vue'
import { cancelWaiting, closeModal, confirmImport, ui } from '../store'
import type { ImportPreview } from '../store'

/**
 * 弹层宿主：改名、确认覆盖、发布报错、批量粘贴、导入文件预览都走这里。
 * 没有引 UI 库，五种形态够用——文本框 / 确认 / 错误清单 / 大段粘贴 / 导入预览。
 */

const HOW_LABEL = { stem: '按题干', no: '按题号', order: '按顺序' } as const

/** 导入预览正文：题目导入显示各卷摘要，答案后补显示匹配结果 */
function ImportBody({ p }: { p: ImportPreview }) {
  const total = p.sheets.reduce((s, x) => s + x.questions.length, 0)
  const missing = p.sheets.reduce((s, x) => s + x.missing, 0)
  const notes = p.notes

  return (
    <div class="mbody">
      {p.mode === 'questions'
        ? (
          <>
            <div class="confirm-text">
              解析出 {p.sheets.length} 份卷 · 共 {total} 题
              {missing ? `（缺答案 ${missing} 题，按「待补答案」处理）` : ''}
            </div>
            <div class="hint" style="margin:8px 0 0">
              {p.sheets.map((s) => (
                <div key={s.name}>
                  · {s.name}：{s.questions.length} 题
                  {s.dupSkipped ? `（重复跳过 ${s.dupSkipped}）` : ''}
                </div>
              ))}
            </div>
            {p.sheets.length === 1
              ? (
                <div class="hint" style="margin:10px 0 0">
                  落到哪儿：
                  <label style="margin:0 12px 0 4px">
                    <input
                      type="radio"
                      checked={p.target === 'current'}
                      onChange={() => { p.target = 'current' }}
                    />{' '}
                    追加到当前工作表
                  </label>
                  <label>
                    <input
                      type="radio"
                      checked={p.target === 'new'}
                      onChange={() => { p.target = 'new' }}
                    />{' '}
                    新建工作表
                  </label>
                </div>
              )
              : (
                <div class="hint" style="margin:10px 0 0">
                  多份卷会各建一张工作表（表名取卷名）。
                </div>
              )}
          </>
        )
        : (
          <>
            <div class="confirm-text">
              识别为答案 / 解析文件 · 共 {p.entries.length} 条
              {p.plan
                ? (
                  <>
                    <br />
                    匹配上 {p.plan.matches.length} / {p.entries.length} 条
                    {(() => {
                      const byHow = { stem: 0, no: 0, order: 0 }
                      for (const m of p.plan!.matches) byHow[m.how]++
                      const seg = [
                        byHow.stem ? `题干 ${byHow.stem}` : '',
                        byHow.no ? `题号 ${byHow.no}` : '',
                        byHow.order ? `顺序 ${byHow.order}` : '',
                      ].filter(Boolean).join(' · ')
                      return seg ? `（${seg}）` : ''
                    })()}
                    <br />
                    {[
                      p.plan.matches.filter((m) => m.overwriteAnswer).length
                        ? `会覆盖 ${p.plan.matches.filter((m) => m.overwriteAnswer).length} 题已有答案`
                        : '',
                      p.plan.matches.filter((m) => m.overwriteAnalysis).length
                        ? `覆盖 ${p.plan.matches.filter((m) => m.overwriteAnalysis).length} 题已有解析`
                        : '',
                      p.plan.unmatched.length ? `${p.plan.unmatched.length} 条没匹配上（忽略）` : '',
                    ].filter(Boolean).join('，') || '不会覆盖任何已有内容'}
                  </>
                )
                : null}
            </div>
            <div class="hint" style="margin:8px 0 0;max-height:180px;overflow:auto">
              {p.plan?.matches.slice(0, 40).map((m, i) => (
                <div key={i}>
                  · 第 {m.entry.line} 行 → {HOW_LABEL[m.how]}匹配
                  {m.answer ? '；写答案' : ''}{m.analysis ? '；写解析' : ''}
                  {m.overwriteAnswer || m.overwriteAnalysis ? '；覆盖原值' : ''}
                </div>
              ))}
              {(p.plan?.matches.length ?? 0) > 40 ? <div>…其余 {p.plan!.matches.length - 40} 条略</div> : null}
            </div>
            <div class="hint" style="margin:10px 0 0">
              匹配顺序：题干 &gt; 题号 &gt; 顺序。只动答案和解析，题干、选项、收藏都不碰。
            </div>
          </>
        )}
      {notes.length
        ? (
          <div class="hint" style="margin:10px 0 0;max-height:140px;overflow:auto">
            {notes.slice(0, 20).map((n, i) => (
              <div key={i} style={n.level === 'error' ? 'color:var(--err,#c0392b)' : ''}>
                {n.level === 'error' ? '✕' : '！'} {n.message}
              </div>
            ))}
            {notes.length > 20 ? <div>…还有 {notes.length - 20} 条</div> : null}
          </div>
        )
        : null}
      <div class="hint" style="margin:10px 0 0">
        「图片」列按原文写入，路径要在手机上有效才显示得出来。导进来的内容都要点「发布到 App」才落库。
      </div>
    </div>
  )
}
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
      // 导入预览没有输入框，「确定」就是执行导入
      if (m.kind === 'import') return confirmImport()
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
                : m.kind === 'import'
                  ? ui.importPreview ? <ImportBody p={ui.importPreview} /> : <div class="mbody" />
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
