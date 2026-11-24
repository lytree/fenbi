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
    main.tsx              # createApp（按 ?view= 决定挂编辑器还是整页视图）
    App.tsx               # 模式切换（题库 / 背诵）+ 三栏布局 + 快捷键 + 文件选择器
    store.ts              # 全部状态与动作（响应式单例，没有 Pinia）；背诵 / 词条状态也在里面
    api.ts                # /api/* 的封装
    fullview.ts           # 整页查看的地址约定：?view=preview|answer|records + setId/sheet/recordId
    paste.ts              # 批量粘贴文本 → 题目
    markdown.ts           # 预览用的 Markdown 子集（表格/图片）
    qview.ts              # 三页共用的题型渲染：单选圆 / 多选方 / 判断勾叉 / 填空主观作答区 + 宫格条
    splitpane.ts          # 三页共用的资料分屏：左材料 + 右题号页签 + 小题流（页签跟滚动、拖把手改宽度）
    images.ts             # 插图、粘贴截图
    types.ts              # 与 LanEditorStore.kt / LanEditorRecite.kt / LanEditorWords.kt 的 JSON 约定
    theme.ts              # 明暗主题：data-theme + localStorage + 跟系统
    scale.ts              # 界面缩放：CSS zoom 顶栏五档（A−/A+），档位记 localStorage
    styles.scss           # 「淡紫瓷面」皮肤：一套令牌 + 明 / 暗两套值（[data-theme="dark"]）
    components/           # TopBar（模式切换 + 复制 MCP）/ Sidebar / SheetBar / QuestionList /
                          # MaterialList / QuestionCard / PreviewPane / PaperDock / AnswerPane / RecordsPane /
                          # FullView（整页查看的壳）/ IssuePane / VersionPane /
                          # ModalHost / Toast / RecitePane（背诵卡编辑）/ WordsPane（词条编辑）
    __tests__/            # vitest + jsdom：App.spec（编辑器主链路）/ fullview.spec（整页查看）等
```

**整页查看**：卷面预览 / 答案解析 / 记录在右栏挤得慌（卷面预览已从右栏撤掉，只剩答案解析 / 记录），所以另有一个整页版。同一个 bundle、
同一个 store，`main.tsx` 读 `?view=preview|answer|records` 决定挂 `App` 还是 `FullView`；
Query 只带 `view / setId / sheet / recordId`，切页与换卷会 `replaceState` 写回地址，
刷新和分享都落在原地。答案解析与记录两页共用 `.pv-head / .pv-q / .pv-opt / .sol-box` 这套骨架，
记录页不再自带固定色（以前是粉笔蓝，夜里整页发白）。
「卷面预览」是照粉笔**试卷作答页**（`spa.fenbi.com/ti/exam/exercise/…`）另起的一层皮（`.paper` 作用域）：
章卡 + 白题卡 + 底部答题卡，资料题走 `.pp-split`（左「材料」白卡 + 右题号页签 + 小题流）。
题型仍然同源（都走 `qview.ts`），别把两套卡片的骨架混着改。
「答案解析」整页里，资料题与另外两页是**同一块左右分屏**：700 高大卡、左「材料」白卡（自己滚）、
中间可拖把手、右边题号页签 + 这一组的小题连着排（`.pp-split`，点页签滚过去、滚动时页签跟着亮）。
结构与交互都在 `src/splitpane.ts`，**卷面预览 / 答案解析 / 练习记录三页共用一份**，
别在某一页里另起一套。「练习记录」的详情也是这块，只是小题左沿多一条对错色条。
**底部那条答题卡（`PaperDock`）三页都有**：卷面预览写 `0/N` + 缺答案提示，解析页写 `N 题`，
记录页按对错给格子上色；收起时是一整条按钮（撑满整行），展开时题号板朝上长、标签留在最底下。

「背诵」模式下 `RecitePane`（政治 / 常识：分类筛选 + 卡片列表 + 逐卡编辑，整份保存走手机确认）
与 `WordsPane`（词条词库：搜索筛选 + 逐条编辑，直接写库）替换题库编辑区，右栏预览不渲染。
数据面见 `docs/12` 的「背诵词库编辑」一节与 `ui/laneditor/LanEditorRecite.kt`、`LanEditorWords.kt`。

## 常用命令

```bash
pnpm install       # 首次；锁文件是 pnpm-lock.yaml
pnpm dev           # 本地起 dev server（接口需要真机服务或自建 mock，没有本地试卷包退路）
pnpm test          # 冒烟 + 材料模式 + 预览同步 + 整页查看 + 题型渲染 + 卷面分章/答题卡 + 笔记（150 例）
pnpm build         # 产物写进 ../../app/src/main/assets/webeditor/
```

`pnpm build` 之后直接 `./gradlew assembleDebug` 就行，Kotlin 侧不用改。

包管理用 **pnpm 12**（`packageManager` 已钉在 `package.json` 里）。
pnpm 10+ 默认拦掉依赖的安装脚本，要在 `pnpm-workspace.yaml` 的白名单里放行：

```yaml
allowBuilds:
  '@parcel/watcher': true
  esbuild: true
```

漏了已装依赖对应的那行，`pnpm install` / `pnpm add` 会以 `ERR_PNPM_IGNORED_BUILDS` 失败
（注意 `pnpm add` 失败时是**静默中断**：先打印 “Already up to date”，版本其实没动）。
**不要**写回 `package.json` 的 `pnpm.onlyBuiltDependencies`——pnpm 12 已经不读它了。

### Vite 8 + vue-jsx 的虚拟模块

Vite 8 换成 rolldown 内核，vue-jsx 注入的 `/vue-jsx/vdom` 这类虚拟 id 在 **vitest** 的
module runner 里会被当成磁盘绝对路径，报 `The argument 'filename' must be a file URL object…
Received 'file:///vue-jsx/vdom'`。`vite.config.ts` 因此用 `resolve.alias` 把这四个 helper id
指向 `@vue-jsx/runtime/dist/*.js`（内容与插件 `load` 返回的同源），`@vue-jsx/runtime` 也就必须
显式留在 `devDependencies` 里。生产构建本身不受影响，删掉这段 alias 只会让 `pnpm test` 挂。

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
- **答案解析 / 记录共用一套骨架**：`.pv-head` / `.pv-q` / `.pv-opt` / `.sol-box`（可折叠解析盒），
  颜色只用令牌（`var(--*)`）。记录页**不要再引入自己的固定色**——以前那套粉笔蓝在夜间主题下就是一块白。
- **卷面预览是另一套皮**：`PreviewPane` 的根是 `.ppane.paper`，样式全部挂在 `.paper` 下（章卡 `.pv-chapter`、
  题卡尺寸、48 高一行的选项、32px 圆徽标）；资料题是 `.pp-split`（1180×700，左材料 + 右页签 + 小题流），
  底部 `PaperDock` 是固定条。**别在 `.paper` 外改 `.pv-q` 的尺寸**——解析页与记录页靠原值排版。
- **整卷是一段 innerHTML**：`PreviewPane` 里任何响应式状态都会触发整段重建，页签选中态与两栏滚动位置会丢。
  所以「当前题」只改类名（`markFocus`），答题卡单独拆成 `PaperDock` 组件，重画前用 `onBeforeUpdate`
  记下 `.pp-qs-body` 的 `scrollTop`、重画后还回去。
- **题型也共用一份渲染**：选项、答案宫格、作答区一律走 `qview.ts`（`optionListHtml / gridHtml /
  writeZoneHtml / noteActionHtml`），不要在某一页里另拼一套。单选圆徽标、多选与不定项方徽标、
  判断题 ✓/✗、填空与主观题无选项；答案串 `AC` / `A,C` / `A C` 都按字母拆（`ansSet`）。
- **一则资料带多题是一个组**：解析用 `materialGroupHtml()`（材料卡 + `.pv-subs` 一次拼好），
  记录页用 `materialCardHtml()` 拼材料、外面套 `.pv-group` 和 JSX 小题，卷面预览用 `.pp-split`。别再退回「材料一张卡、
  小题一堆散卡」——组头要写「资料 / 第 N–M 题 / k 小题共用」，收起只收材料正文、小题不动。
- **资料分屏只在整页**：`AnswerPane` 的 `full` 才走 `.pv-split`（照粉笔：左材料 + 右题号页签 + 单题，
  页签切换状态在组件里的 `tabIdx`，宽度在 `matWidth`）；`RecordsPane` 的 `standalone` 用
  `.pv-group.split`（材料钉左、题目全列）。编辑器窄栏永远上下排。
- **笔记可写、记录不可写**：题号行右端的 ✎（`editNote()`）走 `POST /api/note`，按题目 id 直写
  `notes` 表，和 App 共用、不参与发布。解析页 / 记录页有这支笔，**卷面预览没有**（那一页只做校样，
  只留卷面）；要加回来先想清楚它算不算编辑。记录页其它内容仍然只读，别顺手加「改分数」之类的入口。
- **图片尺寸只交给 CSS**：`markdown.ts` 的 `imgTag` **不许**往 `<img>` 上塞内联 `width / max-width`
  （以前那句 `onload="maxWidth = naturalWidth*2"` 就是图片冲出左栏、右半边被裁掉的原因）。
  `.pv-img { max-width:100% }` 一条就够：原图多大就多大，比栏宽就等比收窄。
- **校验 / 版本 / 资源是弹框**：`ui.tool` 记当前开的是哪个，`openTool() / closeTool()` 进出，
  `ToolDialog` 统一渲染；入口在页签条右端（`.tool-tab`，带计数徽标）。别再把它们塞回右栏页签——
  右栏只留答案解析与记录两页，**卷面预览只在整页查看里**。
- **删除不在网页上执行**：`requestDeleteSet()` 只调 `/api/delete/request`，然后每 1.2 秒轮询
  `/api/delete/status`，直到手机点确认 / 拒绝 / 超时。别在前端加「直接删」的调用——
  手机端根本没有那个接口，加了也是 404。
- **版本面板只是存档**：`/api/versions`、`/api/version/restore`、`/api/version/delete`。
  回滚要带当前 `setId`，否则改过名字的组会被回滚到旧标题下、凭空多一组。
