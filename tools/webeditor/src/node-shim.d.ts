/**
 * 最小 Node 类型垫片：仓库没有装 @types/node，开发服务器插件只用到少量内置模块，
 * 这里按「any」声明，避免为了一个 vite 中间件引入整套 Node 类型依赖。
 */
declare module 'node:fs' {
  const fs: any
  export default fs
}

declare module 'node:path' {
  const path: any
  export default path
}

declare const process: { cwd(): string }
