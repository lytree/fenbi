import { defineComponent, ref } from 'vue'
import type { LibraryItem } from '../types'
import type { LibraryGroup } from '../store'
import { renderRecite } from '../markdown'
import {
  addLibraryItem, askConfirm, library, libraryBlankCount, libraryGroups, libraryItemIssue,
  libraryIssueCount, libraryLimitOf, libraryMainTag, libraryShowMore, libraryShown, librarySources,
  librarySourceTags, libraryTopicTags, libraryVisibleItems, loadLibrary, moveLibraryItemToTag,
  removeLibraryItem, resetLibrary, saveLibrary, toggleLibraryItem, ui, updateLibraryItemAnswer,
  updateLibraryItemSource, updateLibraryItemText,
} from '../store'

/** 行内输入框跟着内容长高：大纲的正文是一句话到一段话，不该出滚动条 */
function grow(el: HTMLTextAreaElement) {
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
}

/**
 * 政治理论大纲编辑器（统一库 `recite_library.json`）。
 *
 * 这一份就是 App 政治页看到的内容：**按内容标签分段，每段一句一条**，
 * 正文用 `____` 标挖空、答案按顺序排在下面。页面上的两件事：
 *
 * - 读：搜索 / 按标签筛 / 按节筛，正文里挖空显示成编号方格（跟 App 大纲页一致）；
 * - 改：点一条展开，改正文（挖空数变了答案数组自动跟着变）、改答案、换标签、删条目。
 *
 * 1400 多条不可能一次铺满，每段先出 30 条，点「显示更多」再放一页。
 */
export default defineComponent({
  name: 'LibraryPane',
  setup() {
    if (!library.catalog && !library.loading) void loadLibrary()

    const query = ref('')
    let timer: number | undefined
    const onQuery = (e: Event) => {
      const v = (e.target as HTMLInputElement).value
      query.value = v
      // 搜一次要扫 1400 条的正文，按字实时过滤会掉帧
      window.clearTimeout(timer)
      timer = window.setTimeout(() => { library.query = v }, 150)
    }

    const confirmRemove = async (item: LibraryItem) => {
      const ok = await askConfirm(
        '删掉这一条？',
        `「${item.text.slice(0, 40)}${item.text.length > 40 ? '…' : ''}」\n\n保存到手机后才真正从大纲里消失。`,
        '删掉',
      )
      if (ok) removeLibraryItem(item.id)
    }

    const Row = (item: LibraryItem) => {
      const open = library.expanded.includes(item.id)
      const issue = libraryItemIssue(item)
      const blanks = libraryBlankCount(item.text)
      const tag = libraryMainTag(item)
      return (
        <div class={['lib-item', open && 'open', issue && 'bad']} key={item.id}>
          <div class="lib-sum" onClick={() => toggleLibraryItem(item.id)}>
            <span class={['lib-dot', issue ? 'bad' : '']} title={issue ?? '没问题'} />
            <span
              class="lib-text"
              // 折叠时按 App 的样子显示挖空（编号方格），点开才是可编辑的原文
              innerHTML={item.text.trim()
                ? renderRecite(item.text, item.answers, false)
                : '<i class="rcp-hint-inline">（还没写正文）</i>'}
            />
            <span class="lib-tags">
              {item.tags.map((t) => {
                const name = library.catalog?.tags.find((x) => x.id === t)?.name ?? t
                return <span class={['tag', t === tag ? '' : 'src']}>{name}</span>
              })}
            </span>
            <span class="lib-count">{blanks ? `${blanks} 空` : '无填空'}</span>
          </div>

          {open
            ? (
              <div class="lib-edit">
                <div class="lib-fields">
                  <label class="rfield">
                    <span>正文</span>
                    <textarea
                      rows={2}
                      spellcheck={false}
                      value={item.text}
                      placeholder="用 ____（四个下划线）标挖空"
                      onInput={(e) => {
                        const el = e.target as HTMLTextAreaElement
                        updateLibraryItemText(item.id, el.value)
                        grow(el)
                      }}
                      onFocus={(e) => grow(e.target as HTMLTextAreaElement)}
                    />
                  </label>

                  <div class="lib-answers">
                    {item.answers.length
                      ? item.answers.map((a, i) => (
                        <label class="lib-answer">
                          <span class="ridx">{i + 1}</span>
                          <input
                            value={a}
                            placeholder="填这个空的答案"
                            onInput={(e) => updateLibraryItemAnswer(item.id, i, (e.target as HTMLInputElement).value)}
                          />
                        </label>
                      ))
                      : <div class="mini-hint">这条没有挖空，正文会整句显示（不遮答案）</div>}
                  </div>

                  <div class="lib-fields-row">
                    <label class="rfield">
                      <span>所属标签</span>
                      <select
                        value={tag}
                        onChange={(e) => moveLibraryItemToTag(item.id, (e.target as HTMLSelectElement).value)}
                      >
                        {libraryTopicTags.value.map((t) => (
                          <option value={t.id}>{t.name}</option>
                        ))}
                      </select>
                    </label>
                    <label class="rfield">
                      <span>来源（节）</span>
                      <input
                        value={item.source}
                        placeholder="如 第四节 重要文件"
                        onInput={(e) => updateLibraryItemSource(item.id, (e.target as HTMLInputElement).value)}
                      />
                    </label>
                  </div>

                  {issue ? <div class="lib-issue">{issue}</div> : null}

                  <div class="lib-actions">
                    <button class="ghost sm danger" onClick={() => void confirmRemove(item)}>删掉这条</button>
                    <span class="grow" />
                    <button class="ghost sm" onClick={() => toggleLibraryItem(item.id)}>收起</button>
                  </div>
                </div>
              </div>
            )
            : null}
        </div>
      )
    }

    const Group = (group: LibraryGroup) => {
      const shown = libraryShown(group)
      const key = group.tag?.id ?? '_none'
      const rest = group.items.length - shown.length
      return (
        <section class="lib-group" key={key}>
          <div class="lib-group-head">
            <span class="bar" />
            <span class="nm">{group.tag?.name ?? '未归类'}</span>
            <span class="mt">{group.items.length} 条</span>
            <span class="grow" />
            <button class="ghost sm" onClick={() => addLibraryItem(group.tag?.id)}>＋ 加一条</button>
          </div>
          {shown.map(Row)}
          {rest > 0
            ? (
              <button class="ghost sm lib-more" onClick={() => libraryShowMore(key)}>
                显示更多（还剩 {Math.min(rest, libraryLimitOf(key))} 条没显示）
              </button>
            )
            : null}
        </section>
      )
    }

    return () => {
      const cat = library.catalog
      const issues = libraryIssueCount()
      return (
        <div class="lib-pane">
          <div class="lib-toolbar">
            <input
              class="search"
              value={query.value}
              spellcheck={false}
              placeholder="搜索正文 / 节名…"
              onInput={onQuery}
            />
            <select
              class="select"
              title="按内容标签筛"
              value={library.tag}
              onChange={(e) => { library.tag = (e.target as HTMLSelectElement).value }}
            >
              <option value="all">全部标签（{(cat?.items ?? []).length} 条）</option>
              {libraryTopicTags.value.map((t) => (
                <option value={t.id}>{t.name}（{t.count ?? 0}）</option>
              ))}
            </select>
            <select
              class="select"
              title="按来源（书里的节）筛"
              value={library.source}
              onChange={(e) => { library.source = (e.target as HTMLSelectElement).value }}
            >
              <option value="all">全部来源</option>
              {librarySources.value.map((src) => (
                <option value={src}>{src}</option>
              ))}
            </select>
            <span class="filter-info">
              筛出 {libraryVisibleItems.value.length} / {(cat?.items ?? []).length} 条
              {cat?.hasEdits ? ' · 有覆盖版' : ' · 内置'}
              {library.dirty ? ' · 有未保存改动' : ''}
              {issues ? ` · ${issues} 处待改` : ''}
            </span>
            <span class="grow" />
            <button class="ghost sm" onClick={() => addLibraryItem()}>＋ 新条目</button>
            <button class="ghost sm danger" onClick={() => void resetLibrary()}>重置内置</button>
            <button
              class="primary sm"
              disabled={ui.busy || !library.dirty}
              onClick={() => void saveLibrary()}
            >
              保存到手机
            </button>
          </div>

          {library.loading && !cat
            ? <div class="side-empty">正在读大纲…</div>
            : !cat
            ? <div class="side-empty">读不到大纲<br />确认手机端局域网编辑器还开着</div>
            : libraryVisibleItems.value.length === 0
            ? <div class="side-empty">这个筛选下没有条目</div>
            : <div class="lib-groups">{libraryGroups.value.map(Group)}</div>}
        </div>
      )
    }
  },
})
