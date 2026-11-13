import { defineComponent, onMounted, onUpdated, ref } from 'vue'
import type { PropType } from 'vue'
import type { Group, Question } from '../types'
import {
  CHOICE, TYPE_LABEL, TYPE_SHORT, addOption, addQuestion, adoptPreviousMaterial,
  ansSet, ansValues, currentQuestions, duplicateQuestion, isExpanded, localIssues, markDirty,
  moveQuestion, removeOption, removeQuestion, setQuestionType, toggleAnswer,
  toggleExpand, ungroupMaterial, updateQuestion,
} from '../store'
import { askText } from '../store'
import { grow, growAll, insertAtCursor } from '../util'
import { plain } from '../markdown'
import { pickImage, insertPastedImage } from '../images'
import { LEVEL1_NAMES, LEVEL_NONE, level1Of, level2NamesOf, level2Of } from '../xingce'

/**
 * 一道题的卡片。折叠时只有一行摘要（几百道题也不卡），展开才长出输入框。
 * 材料不在这里编辑——它属于「材料组」，由列表顶部的组头统一改（材料必须逐行重复，
 * 组头改一次等于把组内每道题的材料都改掉，正好对上 App 的卷面派生规则）。
 */
export default defineComponent({
  name: 'QuestionCard',
  props: {
    question: { type: Object as PropType<Question>, required: true },
    index: { type: Number, required: true },
    group: { type: Object as PropType<Group | null>, default: null },
  },
  setup(props) {
    const root = ref<HTMLElement | null>(null)
    const stemRef = ref<HTMLTextAreaElement | null>(null)

    const growThis = () => {
      growAll(root.value)
    }
    onMounted(growThis)
    onUpdated(growThis)

    const onHeadClick = (e: MouseEvent) => {
      if ((e.target as HTMLElement).closest('button, input, textarea, select, details, summary')) return
      toggleExpand(props.question._uid)
    }

    const field = (name: keyof Question) => (e: Event) => {
      const el = e.target as HTMLInputElement | HTMLTextAreaElement
      updateQuestion(props.question, name, el.value)
      grow(el as HTMLTextAreaElement)
    }

    const stemImage = () =>
      pickImage((path) => {
        const el = stemRef.value
        if (el) insertAtCursor(el, `![](${path})`)
        else updateQuestion(props.question, 'stem', `${props.question.stem}${props.question.stem ? '\n' : ''}![](${path})`)
      })

    const optionImage = (key: string) =>
      pickImage((path) => {
        const target = props.question.options.find((o) => o.key === key)
        if (!target) return
        target.text = `${target.text}${target.text ? '\n' : ''}![](${path})`
        markDirty()
      })

    const addMaterial = async () => {
      const text = await askText({
        title: '给这题加材料',
        label: '材料渲染在题干上方：支持 Markdown 表格、图片、:::chart；同一段材料的多道题要挨在一起',
        value: '',
        rows: 8,
        okText: '用这段材料',
      })
      if (text === null) return
      updateQuestion(props.question, 'material', text)
    }

    return () => {
      const q = props.question
      const open = isExpanded(q._uid)
      const issues = localIssues(q)
      const hasError = issues.some((i) => i.level === 'error')
      const hasWarn = !hasError && issues.length > 0
      const optCount = q.options.filter((o) => o.text.trim()).length
      const answers = ansSet(q)
      const group = props.group
      const hasImage = /!\[.*?\]\(/.test(`${q.material}${q.stem}${q.options.map((o) => o.text).join('')}`)

      const typeOptions = (Object.keys(TYPE_LABEL) as (keyof typeof TYPE_LABEL)[]).map((t) => (
        <option value={t} selected={q.type === t}>{TYPE_LABEL[t]}</option>
      ))
      // 行测题型两级：一级取科目（行测·资料分析 → 资料分析），二级取知识点
      const lv1 = level1Of(q)
      const lv2 = level2Of(q)
      const hasTypeTree = !!lv1 || !!lv2
      const lv1ListId = `xc-lv1-${q._uid}`
      const lv2ListId = `xc-lv2-${q._uid}`

      return (
        <article
          ref={root}
          class={['qcard', open && 'open', hasError && 'bad', hasWarn && 'warn']}
          data-uid={q._uid}
        >
          <div class="qhead" onClick={onHeadClick}>
            <span class="qno">{props.index + 1}</span>
            {hasTypeTree
              ? (
                <>
                  <span class="qtype lv1">{lv1 || LEVEL_NONE}</span>
                  <span class={['qtype', 'lv2', !lv2 && 'none']}>{lv2 || LEVEL_NONE}</span>
                </>
              )
              : <span class={['qtype', q.type]}>{TYPE_SHORT[q.type]}</span>}
            <div class="qsum">
              <div class="stem-preview">
                {q.stem.trim() ? plain(q.stem, 120) : <span class="ph">（题干还没写）</span>}
              </div>
              <div class="qsub">
                {q.answer.trim()
                  ? <span class="ans">答案 {q.answer}</span>
                  : <span class="ans empty">待补答案</span>}
                {optCount ? <span>{optCount} 选项</span> : null}
                {hasTypeTree ? <span class="chip">{TYPE_SHORT[q.type]}</span> : null}
                {group ? <span class="chip">材料组</span> : null}
                {hasImage ? <span class="chip">有图</span> : null}
              </div>
            </div>
            <div class="badges">
              {hasError ? <span class="badge e" title="有错误，发布时会被拦下">!</span> : null}
              {issues.length
                ? <span class="badge w" title={issues[0].message}>{issues.length}</span>
                : null}
            </div>
            <div class="qacts">
              <button title="上移" onClick={() => moveQuestion(q._uid, -1)}>↑</button>
              <button title="下移" onClick={() => moveQuestion(q._uid, 1)}>↓</button>
              <button title="复制本题" onClick={() => duplicateQuestion(q._uid)}>复制</button>
              <button class="danger" title="删除本题" onClick={() => removeQuestion(q._uid)}>删除</button>
            </div>
          </div>

          {open
            ? (
              <div class="qbody">
                <div class="row">
                  <label>作答</label>
                  <select
                    class="field small" style="max-width:110px"
                    onChange={(e) => setQuestionType(q, (e.target as HTMLSelectElement).value as Question['type'])}
                  >
                    {typeOptions}
                  </select>
                  <label>一级题型</label>
                  <input
                    class="field small" style="max-width:180px" value={q.subject} list={lv1ListId}
                    placeholder="如 言语理解与表达 / 资料分析"
                    onInput={field('subject')}
                  />
                  <label>二级题型</label>
                  <input
                    class="field small" style="max-width:160px" value={q.knowledge} list={lv2ListId}
                    placeholder="如 逻辑填空 / 增长量"
                    onInput={field('knowledge')}
                  />
                  <label>难度</label>
                  <select
                    class="field small" style="max-width:86px" value={q.difficulty}
                    onChange={field('difficulty')}
                  >
                    <option value="">未标</option>
                    <option value="1">易</option>
                    <option value="2">中</option>
                    <option value="3">难</option>
                  </select>
                  <datalist id={lv1ListId}>
                    {LEVEL1_NAMES.map((n) => <option value={n} />)}
                  </datalist>
                  <datalist id={lv2ListId}>
                    {level2NamesOf(q.subject).map((n) => <option value={n} />)}
                  </datalist>
                </div>

                {group
                  ? (
                    <div class="mgroup">
                      <span class="t">材料组</span>
                      <span>与第 {group.start + 1}–{group.end + 1} 题共用，材料在上面那段里改</span>
                      <span class="grow"></span>
                      <button onClick={() => ungroupMaterial(q)}>移出材料组</button>
                    </div>
                  )
                  : (
                    <div class="mgroup">
                      <span class="t">无材料</span>
                      <span>这题按独立题处理</span>
                      <span class="grow"></span>
                      <button onClick={addMaterial}>＋ 加材料</button>
                      {props.index > 0 && (currentQuestions()[props.index - 1]?.material || '').trim()
                        ? <button onClick={() => adoptPreviousMaterial(q)}>沿用上一题材料</button>
                        : null}
                    </div>
                  )}

                <div class="row" style="align-items:flex-start">
                  <label style="padding-top:6px">题干</label>
                  <div style="flex:1;min-width:0">
                    <textarea
                      ref={stemRef}
                      class="field"
                      rows={2}
                      spellcheck={false}
                      value={q.stem}
                      placeholder="题干必填。多段内容直接回车换行；截图可以直接 Ctrl+V 贴进来"
                      onInput={field('stem')}
                      onPaste={(e) => insertPastedImage(e as ClipboardEvent, stemRef.value)}
                    />
                    <div class="stem-tools">
                      <button class="ghost sm" onClick={stemImage}>插图</button>
                      <span class="mini-hint">图片会以 ![](images/…) 写进内容，发布时换成手机里的绝对路径</span>
                    </div>
                  </div>
                </div>

                <div class="opts">
                  {q.options.map((o) => (
                    <div class="opt">
                      <button
                        class={['opt-key', answers.includes(o.key) && 'on']}
                        title="点一下设为正确答案"
                        onClick={() => toggleAnswer(q, o.key)}
                      >
                        {o.key}
                      </button>
                      <textarea
                        class="field"
                        rows={1}
                        value={o.text}
                        placeholder={`选项 ${o.key} 的内容（也可以贴图片地址 / 点右侧「图」上传）`}
                        onInput={(e) => {
                          o.text = (e.target as HTMLTextAreaElement).value
                          markDirty()
                          grow(e.target as HTMLTextAreaElement)
                        }}
                        onPaste={(e) => insertPastedImage(e as ClipboardEvent, e.target as HTMLTextAreaElement)}
                      />
                      <div class="opt-tools">
                        <button title="插入图片" onClick={() => optionImage(o.key)}>图</button>
                        {q.options.length > 2
                          ? <button title="删除该选项" onClick={() => removeOption(q, o.key)}>✕</button>
                          : null}
                      </div>
                    </div>
                  ))}
                </div>
                {q.options.length < 8
                  ? <button class="opt-add" onClick={() => addOption(q)}>＋ 加一个选项</button>
                  : null}

                <div class="ansrow">
                  <span class="lbl">答案</span>
                  {CHOICE.includes(q.type)
                    ? ansValues(q).map((v) => (
                      <button
                        class={['ans-chip', answers.includes(v) && 'on']}
                        onClick={() => toggleAnswer(q, v)}
                      >
                        {v}
                      </button>
                    ))
                    : (
                      <input
                        class="field" style="max-width:420px" value={q.answer}
                        placeholder="填空多空用 | 分隔；主观题写参考答案"
                        onInput={field('answer')}
                      />
                    )}
                  {q.type === 'multi' || q.type === 'uncertain'
                    ? <span class="mini-hint">可多选，入库是 A,C</span>
                    : null}
                  {q.type === 'truefalse'
                    ? <span class="mini-hint">入库是 正确 / 错误</span>
                    : null}
                  {q.answer
                    ? <button class="ans-clear" onClick={() => updateQuestion(q, 'answer', '')}>清空</button>
                    : null}
                </div>

                <div class="row" style="align-items:flex-start">
                  <label style="padding-top:6px">解析</label>
                  <textarea
                    class="field"
                    rows={2}
                    spellcheck={false}
                    value={q.analysis}
                    placeholder="可以整段留空，之后在 App 里也能补"
                    onInput={field('analysis')}
                  />
                </div>

                <details class="more">
                  <summary>更多字段（地区 / 来源 / 年份 / 分值 / 得分点）</summary>
                  <div class="grid">
                    <input class="field" value={q.region} placeholder="地区" onInput={field('region')} />
                    <input class="field" value={q.source} placeholder="来源" onInput={field('source')} />
                    <input class="field" value={q.year} placeholder="年份" onInput={field('year')} />
                    <input class="field" value={String(q.score ?? '')} placeholder="分值" onInput={field('score')} />
                    <input class="field" value={q.keywords} placeholder="得分点（主观题用 ; 分隔）" onInput={field('keywords')} />
                  </div>
                </details>

                <div class="qfoot">
                  <button class="ghost sm" onClick={() => addQuestion(props.index)}>在下方插入一题</button>
                  <button class="ghost sm" onClick={() => duplicateQuestion(q._uid)}>复制本题</button>
                  <button class="ghost sm danger-text" onClick={() => removeQuestion(q._uid)}>删除本题</button>
                </div>
              </div>
            )
            : null}
        </article>
      )
    }
  },
})
