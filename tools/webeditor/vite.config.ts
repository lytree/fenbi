import { defineConfig } from 'vitest/config'
import vueJsx from 'vue-jsx/vite'

/**
 * 构建产物直接落进 App 的 assets：
 *   app/src/main/assets/webeditor/index.html
 *   app/src/main/assets/webeditor/assets/*.js|css
 *
 * base 写 `/web/`：手机端的 Kotlin 服务把 `/` 映射到 index.html、`/web/**` 映射到其余文件，
 * 这样打包出来的引用路径不用改代码就能命中。
 */
export default defineConfig({
  base: '/web/',
  plugins: [vueJsx()],
  build: {
    outDir: '../../app/src/main/assets/webeditor',
    emptyOutDir: true,
    target: 'es2020',
    assetsDir: 'assets',
    chunkSizeWarningLimit: 900,
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts', 'src/**/*.spec.tsx'],
  },
})
