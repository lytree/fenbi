# 局域网题库编辑器（前端）

手机 App 里那个网页编辑器的前端源码：**Vue 3 + TSX，禁模板**（仓库里没有 `.vue` 文件，
所有组件都是 `defineComponent` + 渲染函数/JSX）。

编译器用 [**vue-jsx**](https://vuejsx.dev/)（Rust / Oxc，Vite 插件 `vue-jsx/vite`），
不是官方的 Babel 版 `@vitejs/plugin-vue-jsx`：同样的 TSX，构建快一个量级，
默认输出普通 Virtual DOM 代码（Vapor 需要 Vue 3.6+，这里没开）。
配套要两处配置，缺一个就编不过：

- `vite.config.ts` → `plugins: [vueJsx()]`，来源是 `vue-jsx/vite`
- `tsconfig.json` → `"jsx": "preserve"` + `"jsxImportSource": "vue-jsx"`（这个包里没有全局 JSX 命名空间，
  所以 `jsxImportSource` 不能省）

## 为什么要单独一个工程

产物要打进 `app/src/main/assets/webeditor/`，由手机端的 `LanEditorServer` 直接吐给浏览器。
所以这里只做一件事：把 `src/` 编成 `index.html + assets/*.js|css`。

```
tools/webeditor/
  index.html              # 入口（Vite 用）
  vite.config.ts          # base=/web/、outDir 指向 App assets
  src/
    main.tsx              # createApp
    App.tsx               # 三栏布局 + 快捷键 + 文件选择器
    store.ts              # 全部状态与动作（响应式单例，没有 Pinia）
    api.ts                # /api/* 的封装
    paste.ts              # 批量粘贴文本 → 题目
    markdown.ts           # 预览用的 Markdown 子集（表格/图片/:::chart）
    figure.ts             # 图文排版：把选中段落包成 :::figure（两种视图共用）
    images.ts             # 插图、粘贴截图
    types.ts              # 与 LanEditorStore.kt 的 JSON 约定
    styles.scss           # 与 App 同一套浅色令牌
    components/           # TopBar / Sidebar / SheetBar / QuestionList / MaterialList /
                          # QuestionCard / PreviewPane / IssuePane / VersionPane / ModalHost / Toast
    __tests__/App.spec.ts # vitest + jsdom 冒烟
```

## 常用命令

```bash
pnpm install       # 首次；锁文件是 pnpm-lock.yaml
pnpm dev           # 本地起 dev server（接口需要真机服务或自建 mock）
pnpm test          # 冒烟 + 材料模式 + 预览同步：打开一组 → 加题 → 发布 / 版本面板 / 删除只发请求（28 例）
pnpm build         # 产物写进 ../../app/src/main/assets/webeditor/
```

`pnpm build` 之后直接 `./gradlew assembleDebug` 就行，Kotlin 侧不用改。

包管理用 **pnpm 12**（`packageManager` 已钉在 `package.json` 里）。
pnpm 10+ 默认拦掉依赖的安装脚本，esbuild 必须在 `pnpm-workspace.yaml` 的白名单里：

```yaml
allowBuilds:
  esbuild: true
```

漏了这行，`pnpm install` 会以 `ERR_PNPM_IGNORED_BUILDS` 失败。
**不要**写回 `package.json` 的 `pnpm.onlyBuiltDependencies`——pnpm 12 已经不读它了。

> 构建产物（`app/src/main/assets/webeditor/index.html` 与 `assets/*`）**要提交**，
> 这样别人 clone 下来不装 Node 也能打包 APK。`node_modules/` 不提交。

## 约束（改代码前先看）

- **不许用模板**：不建 `.vue`、不写 `template` 字符串；组件一律 TSX 渲染函数。
- **不引 UI 库**：按钮、弹层、开关都是自己写的几十行，跟 App 的浅色版式对齐即可。
- **状态只在 `store.ts`**：组件不各自持有业务状态，避免「左边改了右边没变」。
- **数据模型只有一套**：`types.ts` 与 `ui/laneditor/LanEditorStore.kt` 一一对应，
  改字段两边一起改；`_uid` / `_issues` 是前端临时字段，`serialize()` 时会剥掉。
- **材料逐行重复**是 App 的硬规则：材料只在「材料组头」或「材料模式」的材料编辑区里改，
  两条路径都走 `setGroupMaterial()` 写回组内每一题，不要在单题上散着改（除非点「移出材料组」）。
  材料模式的说明见 [docs/14](../../docs/14-材料多题编辑模式.md)。
- **删除不在网页上执行**：`requestDeleteSet()` 只调 `/api/delete/request`，然后每 1.2 秒轮询
  `/api/delete/status`，直到手机点确认 / 拒绝 / 超时。别在前端加「直接删」的调用——
  手机端根本没有那个接口，加了也是 404。
- **版本面板只是存档**：`/api/versions`、`/api/version/restore`、`/api/version/delete`。
  回滚要带当前 `setId`，否则改过名字的组会被回滚到旧标题下、凭空多一组。
