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
    // 测试不需要真的处理样式：关掉 CSS/资源转换，vitest 就不会往系统临时目录里写文件
    // （Windows 上临时目录常被安全软件锁住，写失败会整轮测试报错）
    css: false,
    deps: {
      web: {
        transformCss: false,
        transformAssets: false,
      },
    },
  },
})
