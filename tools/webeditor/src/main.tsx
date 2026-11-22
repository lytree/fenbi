import { createApp } from 'vue'
import App from './App'
import FullView from './components/FullView'
import { FULL_PANE_LABEL, readFullQuery } from './fullview'
import './styles.scss'

/**
 * 同一个 bundle 两个壳：
 * 地址上带 `?view=`（卷面预览 / 答案解析 / 记录）就挂整页只读视图，
 * 否则是编辑器本身。路由判断见 `fullview.ts`。
 */
const full = readFullQuery()
if (full) {
  document.title = `${FULL_PANE_LABEL[full.pane]} · 题库`
  createApp(FullView, { query: full }).mount('#app')
} else {
  createApp(App).mount('#app')
}
