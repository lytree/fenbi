import { computed, defineComponent, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import {
  currentSetId, deleteResource, loadResources, state, toast, uploadImage,
} from '../store'
import type { Question } from '../types'

/** 题目里的图片引用（与手机端 IMAGE_REF 同一口径，排除绝对路径里的 images/） */
const IMAGE_REF = /(?<![\w./\\-])(?:\.\/)?images\/([^)\s"'<>]+)/g

function refsOf(q: Question): string[] {
  const blob = [q.material, q.stem, q.analysis, ...q.options.map((o) => o.text)].join('\n')
  const out: string[] = []
  let m: RegExpExecArray | null
  IMAGE_REF.lastIndex = 0
  while ((m = IMAGE_REF.exec(blob))) out.push(`images/${m[1]}`)
  return out
}

function humanSize(n: number): string {
  if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  if (n >= 1024) return `${Math.round(n / 1024)} KB`
  return `${n} B`
}

/**
 * 资源面板：这一组试卷的图片都按组编号存在 `images/<组编号>/` 下。
 *
 * 面板关心三件事——哪些图在用（引用次数）、哪些没人用了、哪些引用找不到文件。
 * 上传 / 换图 / 删图都直接落到手机上，题目里的相对路径不用手写。
 */
export default defineComponent({
  name: 'ResourcePane',
  setup() {
    const pendingReplace = ref<string | null>(null)
    const fileInput = ref<HTMLInputElement | null>(null)

    const counts = computed(() => {
      const map = new Map<string, number>()
      for (const s of state.paper?.sheets ?? []) {
        for (const q of s.questions) {
          for (const p of refsOf(q)) map.set(p, (map.get(p) ?? 0) + 1)
        }
      }
      return map
    })

    const files = computed(() =>
      state.resources
        .map((f) => ({ ...f, refs: counts.value.get(f.path) ?? 0 }))
        // 没用上的排后面，方便一眼看到能清的
        .sort((a, b) => Number(a.refs > 0) === Number(b.refs > 0)
          ? a.name.localeCompare(b.name, 'zh')
          : Number(b.refs > 0) - Number(a.refs > 0)),
    )

    const missing = computed(() => {
      const known = new Set(state.resources.map((f) => f.path))
      return [...counts.value.entries()]
        .filter(([p]) => !known.has(p))
        .map(([path, refs]) => ({ path, refs }))
    })

    const local = computed(() => state.source === 'local')
    const unpublished = computed(() => !!state.paper && state.paper.id <= 0)

    onMounted(() => { void loadResources() })
    // 换组（整个 paper 对象换掉）或新组发布拿到编号后，资源目录跟着变
    watch([() => state.paper, () => state.paper?.id], () => { void loadResources() })

    const pick = (replacePath?: string) => {
      pendingReplace.value = replacePath ?? null
      const el = fileInput.value
      if (!el) return
      el.value = ''
      el.click()
    }

    const onFile = async (e: Event) => {
      const input = e.target as HTMLInputElement
      const file = input.files?.[0]
      const replace = pendingReplace.value
      pendingReplace.value = null
      if (!file) return
      await uploadImage(file, replace ?? undefined)
    }

    const copyPath = async (path: string) => {
      try {
        await navigator.clipboard.writeText(path)
        toast(`已复制：${path}`, 'ok')
      } catch {
        toast(`路径：${path}`, '')
      }
    }

    return () => {
      if (!state.paper) return <div class="pv-empty">还没有打开任何一组</div>
      if (local.value) {
        return (
          <div class="pv-empty">
            本地试卷包模式不支持资源管理
            <br />
            图片直接放进试卷包的 images/ 目录，用相对路径引用即可
          </div>
        )
      }

      return (
        <div class="ppane res-pane">
          <div class="res-tip">
            <span>
              {unpublished.value
                ? '这一组还没发布，图片先存在临时目录，发布后自动归入本组编号的文件夹'
                : `本组图片目录：images/${currentSetId()}`}
            </span>
            <button class="link-btn" onClick={() => loadResources()}>刷新</button>
          </div>

          <div class="res-actions">
            <button class="primary sm" onClick={() => pick()}>＋ 上传图片</button>
            <span class="res-total">
              共 {state.resources.length} 张 · 引用 {[...counts.value.values()].reduce((a, b) => a + b, 0)} 次
            </span>
          </div>

          <input ref={fileInput} class="res-file" type="file" accept="image/*" onChange={onFile} />

          {state.resourceLoading ? <div class="pv-empty">读取中…</div> : null}

          {!state.resourceLoading && !files.value.length
            ? (
              <div class="pv-empty">
                这一组还没有图片
                <br />
                题干 / 选项旁的「图」按钮，或上面的「上传图片」都能存进手机
              </div>
            )
            : null}

          {files.value.map((f) => (
            <div key={f.path} class="res-item">
              <img
                key={`${f.path}-${f.mtime}`}
                class="res-thumb"
                src={api.imgUrl(f.path)}
                alt=""
                loading="lazy"
              />
              <div class="res-main">
                <div class="res-name" title={f.path}>{f.name}</div>
                <div class="res-meta">
                  {humanSize(f.size)}
                  <span class={['res-badge', f.refs ? 'used' : 'unused']}>
                    {f.refs ? `引用 ${f.refs} 次` : '未引用'}
                  </span>
                </div>
              </div>
              <div class="res-ops">
                <button class="ghost sm" title="复制相对路径" onClick={() => copyPath(f.path)}>复制</button>
                <button class="ghost sm" title="用另一张图替换，引用路径不变" onClick={() => pick(f.path)}>换图</button>
                <button class="ghost sm danger" onClick={() => deleteResource(f)}>删</button>
              </div>
            </div>
          ))}

          {missing.value.length
            ? (
              <div class="res-missing">
                <div class="res-missing-title">引用找不到文件（{missing.value.length}）</div>
                {missing.value.map((m) => (
                  <div key={m.path} class="res-missing-row">
                    <code>{m.path}</code>
                    <span>引用 {m.refs} 次</span>
                  </div>
                ))}
              </div>
            )
            : null}
        </div>
      )
    }
  },
})
