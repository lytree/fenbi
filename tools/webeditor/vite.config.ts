import { createRequire } from 'node:module'
import { defineConfig } from 'vitest/config'
import vueJsx from 'vue-jsx/vite'

/**
 * vue-jsx 会把 `/vue-jsx/vdom` 这类虚拟 id 注入到编译产物里，靠插件自己的 resolveId/load 提供内容。
 * Vite 8 换成 rolldown 内核后，vitest 的 module runner 不认这种以 `/` 开头的非磁盘 id，
 * 直接当成绝对路径去 pathToFileURL，报 “The argument 'filename' must be a file URL object…”。
 * 生产构建不受影响（走 rolldown 插件管线），所以只在这里把虚拟 id 指向 runtime 里的同源真实文件。
 */
const require = createRequire(import.meta.url)
const jsxHelper = (name: string) => require.resolve(`@vue-jsx/runtime/dist/${name}.js`)

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
  resolve: {
    alias: [
      { find: '/vue-jsx/vdom', replacement: jsxHelper('vdom') },
      { find: '/vue-jsx/ssr', replacement: jsxHelper('ssr') },
      { find: '/vue-jsx/props', replacement: jsxHelper('props') },
      { find: '/vue-jsx/vapor', replacement: jsxHelper('vapor') },
    ],
  },
  // Vite 8 起 sass 只剩现代编译 API，不再需要 api: 'modern-compiler'
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
    // jsdom 缺 Canvas 2D：markmap 量文字要用，补丁写在 setup.ts
    setupFiles: ['src/__tests__/setup.ts'],
    // 测试不需要真的处理样式：关掉 CSS/资源转换
    css: false,
    // 单进程跑：多个测试文件并发时，vitest 会同时往同一个临时文件写转换结果，
    // Windows 直接甩 EPERM（EPERM: operation not permitted, open ...\Temp\<id>\web\<hash>），
    // 表现为随机一轮测试报错。串行跑就没有这个竞争。
    // vitest 5 去掉了 poolOptions，等价写法是关掉文件并行 + 单 worker。
    fileParallelism: false,
    maxWorkers: 1,
  },
})
