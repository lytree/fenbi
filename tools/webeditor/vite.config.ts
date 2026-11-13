import { defineConfig } from 'vitest/config'
import vueJsx from 'vue-jsx/vite'
import { localServer } from './local-server'

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
  plugins: [vueJsx(), localServer()],
  css: {
    preprocessorOptions: {
      // 用现代编译 API：sass 1.8x 起旧 API 会打一堆弃用警告
      scss: { api: 'modern-compiler' },
    },
  },
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
    // 测试不需要真的处理样式：关掉 CSS/资源转换
    css: false,
    deps: {
      web: {
        transformCss: false,
        transformAssets: false,
      },
    },
    // 单进程跑：多个测试文件并发时，vitest 会同时往同一个临时文件写转换结果，
    // Windows 直接甩 EPERM（EPERM: operation not permitted, open ...\Temp\<id>\web\<hash>），
    // 表现为随机一轮测试报错。串行跑就没有这个竞争。
    pool: 'forks',
    poolOptions: {
      forks: { singleFork: true },
    },
  },
})
