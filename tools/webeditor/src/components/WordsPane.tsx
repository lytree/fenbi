import { defineComponent, ref } from 'vue'
import type { WordItem } from '../types'
import {
  addWordRow, deleteWordItem, loadWords, recite, saveWordItem, toggleWord, updateWordRow, WORD_CATEGORY_LABEL,
} from '../store'

/** 一条词条编辑态 */
const WordEditor = defineComponent({
  name: 'WordEditor',
  props: { word: { type: Object as () => WordItem, required: true } },
  setup(props) {
    const save = () => void saveWordItem(props.word)
    return () => {
      const w = props.word
      return (
        <div class="rbody wbody">
          <div class="rfield two">
            <div>
              <label>词条（唯一，撞名会拦）</label>
              <input
                value={w.text}
                spellcheck={false}
                placeholder="如：画蛇添足"
                onInput={(e) => updateWordRow(w.id, { text: (e.target as HTMLInputElement).value })}
              />
            </div>
            <div>
              <label>拼音（留空 = 按字表自动算；改了正文会重算）</label>
              <input
                value={w.pinyin}
                spellcheck={false}
                placeholder="hua she tian zu"
                onInput={(e) => updateWordRow(w.id, { pinyin: (e.target as HTMLInputElement).value })}
              />
            </div>
          </div>
          <div class="rfield two">
            <div>
              <label>分类</label>
              <select
                class="select"
                value={w.category}
                onChange={(e) => updateWordRow(w.id, { category: (e.target as HTMLSelectElement).value })}
              >
                {Object.entries(WORD_CATEGORY_LABEL).map(([code, label]) => (
                  <option key={code} value={code}>{label}</option>
                ))}
              </select>
            </div>
            <div>
              <label>细分标签（多段用 · 分隔，如「对比辨析·动作」）</label>
              <input
                value={w.tag}
                spellcheck={false}
                onInput={(e) => updateWordRow(w.id, { tag: (e.target as HTMLInputElement).value })}
              />
            </div>
          </div>
          <div class="rfield">
            <label>释义</label>
            <textarea
              rows={3}
              value={w.meaning}
              spellcheck={false}
              onInput={(e) => updateWordRow(w.id, { meaning: (e.target as HTMLTextAreaElement).value })}
            />
          </div>
          <div class="rfield two">
            <div>
              <label>例句</label>
              <input
                value={w.example}
                spellcheck={false}
                onInput={(e) => updateWordRow(w.id, { example: (e.target as HTMLInputElement).value })}
              />
            </div>
            <div>
              <label>出处</label>
              <input
                value={w.source}
                spellcheck={false}
                onInput={(e) => updateWordRow(w.id, { source: (e.target as HTMLInputElement).value })}
              />
            </div>
          </div>
          <div class="rops">
            <button class="primary sm" disabled={!w.text.trim()} onClick={save}>
              {w.id === 0 ? '新增词条' : '保存修改'}
            </button>
            <button class="ghost sm danger" onClick={() => void deleteWordItem(w)}>删除</button>
            <span class="rhint-inline">词条编辑直接写库，不用手机确认；删错可以再加回来。</span>
          </div>
        </div>
      )
    }
  },
})

/** 词条词库（App「背诵 → 词库」页的内容）：搜索 / 筛选 + 逐条编辑 */
export default defineComponent({
  name: 'WordsPane',
  setup() {
    const queryInput = ref(recite.wordQuery)
    let queryTimer: number | undefined
    const onQueryInput = (e: Event) => {
      queryInput.value = (e.target as HTMLInputElement).value
      window.clearTimeout(queryTimer)
      queryTimer = window.setTimeout(() => {
        recite.wordQuery = queryInput.value
        void loadWords()
      }, 250)
    }

    const onCategory = (v: string) => {
      recite.wordCategory = v
      void loadWords()
    }
    const onTag = (v: string) => {
      recite.wordTag = v
      void loadWords()
    }

    return () => {
      const shown = recite.words.slice(0, recite.wordLimit)
      return (
        <>
          <div class="toolbar">
            <select class="select" value={recite.wordCategory} onChange={(e) => onCategory((e.target as HTMLSelectElement).value)}>
              <option value="all">全部分类（{recite.wordsTotal}）</option>
              {Object.entries(WORD_CATEGORY_LABEL).map(([code, label]) => (
                <option key={code} value={code} disabled={!recite.wordsCounts[code]}>
                  {label}（{recite.wordsCounts[code] ?? 0}）
                </option>
              ))}
            </select>
            <select class="select" title="按细分标签筛" value={recite.wordTag} onChange={(e) => onTag((e.target as HTMLSelectElement).value)}>
              <option value="all">全部标签</option>
              {recite.wordsTags.map((t) => (
                <option key={t.name || '__none__'} value={t.name}>
                  {t.name || '未标注标签'}（{t.count}）
                </option>
              ))}
            </select>
            <input
              class="search"
              value={queryInput.value}
              spellcheck={false}
              placeholder="搜词条 / 拼音 / 释义 / 标签…（回车也行，输入会自动搜）"
              onInput={onQueryInput}
            />
            <div class="toolbar-actions">
              <button class="primary sm" onClick={addWordRow}>＋ 新词</button>
            </div>
          </div>

          <div class="qlist rlist">
            {shown.length === 0
              ? <div class="empty-inline">{recite.loading ? '读取中…' : '没有命中的词条'}</div>
              : shown.map((w) => {
                const open = recite.wordExpanded.includes(w.id)
                return (
                  <div key={w.id || `new-${w.text}`} class={['rcard word', open && 'open']}>
                    <div class="qhead" onClick={() => toggleWord(w.id)}>
                      <div class="rgrow">
                        <div class="wline">
                          <b class="wtext">{w.text || '（新词条）'}</b>
                          {w.pinyin ? <span class="wpinyin">{w.pinyin}</span> : null}
                          <span class="tag">{WORD_CATEGORY_LABEL[w.category] ?? w.category}</span>
                          {w.tag ? <span class="tag src">{w.tag}</span> : null}
                        </div>
                        {w.meaning ? <div class="rmeaning">{w.meaning}</div> : null}
                      </div>
                    </div>
                    {open ? <WordEditor word={w} /> : null}
                  </div>
                )
              })}
            {recite.words.length > shown.length
              ? (
                <div class="rmore">
                  <button class="ghost sm" onClick={() => { recite.wordLimit += 500 }}>
                    显示更多（还有 {recite.words.length - shown.length} 条）
                  </button>
                </div>
              )
              : null}
          </div>
        </>
      )
    }
  },
})
