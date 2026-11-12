import { insertAtCursor } from './util'
import { uploadImage } from './store'

let pending: ((path: string) => void) | null = null

/** 「插图」按钮：打开系统选图，选完把路径交给调用方插进光标处 */
export function pickImage(target: (path: string) => void) {
  pending = target
  const input = document.getElementById('file-image') as HTMLInputElement | null
  if (!input) return
  input.value = ''
  input.click()
}

export async function handleFileInput(files: FileList | null) {
  if (!files || !files.length) return
  for (const file of Array.from(files)) {
    const path = await uploadImage(file)
    if (path && pending) pending(path)
  }
  pending = null
}

/** 在 textarea 里 Ctrl+V 贴一张截图：直接存进手机、把那串 markdown 写进去 */
export async function insertPastedImage(e: ClipboardEvent, el?: HTMLTextAreaElement | null) {
  const items = Array.from(e.clipboardData?.items ?? [])
  const hit = items.find((i) => i.kind === 'file' && i.type.startsWith('image/'))
  if (!hit) return
  e.preventDefault()
  const file = hit.getAsFile()
  if (!file) return
  const path = await uploadImage(file)
  if (path && el) insertAtCursor(el, `![](${path})`)
}
