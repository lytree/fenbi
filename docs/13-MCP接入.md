# 13 MCP 接入（让 AI 直接改题库）

局域网编辑器解决的是「**人**在电脑上改题」；这一层解决「**AI** 在电脑上改题」。
手机 App 里那个 HTTP 服务没有任何改动，只是多了一个跑在电脑上的 MCP server，
把同一套接口翻译成 AI 客户端能调用的工具。

```
Claude Desktop / Cursor / Cherry Studio
        │  stdio（MCP 协议）
        ▼
tools/qbank-mcp/dist/index.js
        │  HTTP + ?t=口令（同一 Wi-Fi）
        ▼
手机 App · LanEditorServer（8777）──▶ QBankDb（SQLite）
```

> 代码与配置示例：[tools/qbank-mcp/README.md](../tools/qbank-mcp/README.md)

---

## 为什么用 MCP 而不是让 AI 直接调 HTTP

- **工具语义**：`qbank_add_questions` 内部做了「读整组 → 追加 → 整组发布」，
  模型不需要知道 App 的发布是覆盖式的，也不用来回传整组题目。
- **输入校验**：题目结构用 zod 定义，缺字段给默认值；真正落库前手机端还会再校验一遍。
- **安全边界落在工具上**：删除工具只发请求、必须手机确认——模型再怎么「想删」也删不掉。
- **一处配置**：地址和口令在客户端配置里写一次，不用每次对话粘贴 URL。

---

## 从 App 复制配置（推荐）

局域网编辑器页面底部有一张 **AI 接入（MCP）** 卡片：

- 服务启动后，卡片里就是**带当前地址和口令的完整配置**，点「复制配置」整段进剪贴板；
- 粘到客户端的 `mcpServers` 里，只改 `args` 那条路径（指向电脑上的
  `tools/qbank-mcp/dist/index.js`），其余不用动；
- 服务没启动时显示的是带占位地址的样例，方便先看清结构。

为什么不让用户手拼：口令是每次启动新生成的 12 位随机串，手抄一遍漏一个字符就是
「未授权」，而且报错信息不会告诉你少了哪个字符。

---

## 能做什么

| 工具 | 说明 |
|---|---|
| `qbank_list_sets` | 列组（卷数 / 题数 / 缺答案数 / 卷名） |
| `qbank_get_set` | 读题，分页（默认 20 题），可只看某卷 / 只看缺解析 |
| `qbank_list_versions` | 历史版本列表 |
| `qbank_list_records` | 作答记录：分数、正确率、用时 |
| `qbank_get_record` | 一次作答的逐题对错（`wrongOnly` 只看错题） |
| `qbank_audit_set` | 体检：缺答案 / 选项不足 / 答案越界 / 解析与答案冲突 / 题干重复… |
| `qbank_add_questions` | 往某卷末尾加题（组不存在则新建） |
| `qbank_update_question` | 按 `sheet + index` 改一道题，只传要改的字段 |
| `qbank_batch_update_questions` | 一次改多题（体检后批量修） |
| `qbank_set_analysis` | 批量写解析（一次只重导一次） |
| `qbank_delete_question` | 删一道题；整卷删空则移除该卷 |
| `qbank_publish_set` | 整组发布（覆盖需 `replace: true`） |
| `qbank_restore_version` | 回滚到某一版 |
| `qbank_delete_set` | 删整组——**手机确认后才执行** |

### 不做什么

- 不改**作答记录**（分数与对错以 App 为准，批改是只读 + 讲解）。
- 不导入 Excel / 试卷包（那是 App 的导入链路）。
- 不上传图片：MCP 传二进制不划算；需要插图时先在网页上 `Ctrl+V` 或「图」按钮传，
  题目里写 `images/xxx.png` 即可。

### 两条典型工作流

**修错**：`qbank_audit_set` 出清单（哪张卷第几题、什么问题）→ 逐条判断 →
`qbank_batch_update_questions` 一次写回。能自动定位的包括：缺答案、选项不足两个、答案不在选项范围、
单选多答案、判断题答案写成 A/B、缺解析、解析里写的「答案：X」与字段不一致、题干重复、HTML 残留。
「解析和答案谁对」这种要人判断的，工具只报不改。

**批改**：`qbank_list_records` 拿 `recordId` → `qbank_get_record({ wrongOnly: true })` 看错题 →
讲错因；如果发现某几题解析缺了或写错了，顺手 `qbank_set_analysis` / `qbank_batch_update_questions` 补上。
分数不会变——想改分只能回 App 里补录答案后重判。

---

## 三条硬口径

1. **写操作 = 整组重导**。加一道题也会把整组重新发布一次，旧卷的作答记录随之被替换；
   但手机端会先把旧内容存进「版本」，`qbank_list_versions` 可查、`qbank_restore_version` 可回滚。
   AI 在描述结果时应当把这一点说出来。
2. **删除必须人工确认**。`qbank_delete_set` 调用后只是登记请求，手机弹框、人点「确认删除」才动库，
   10 分钟无人处理自动作废。默认等 45 秒（多数客户端工具超时是 60 秒），超时如实返回「去手机上点」。
3. **批改只读**。`qbank_list_records` / `qbank_get_record` 不改分数、不改作答记录：
   让模型悄悄改掉已经确认过的判分，比不批改更糟。AI 给的是错因与讲解，修题走
   `qbank_batch_update_questions`（有版本存档兜底）。

---

## 安全边界

- **仍然是局域网**：MCP server 只是 HTTP 客户端，手机服务不暴露到公网。
- **口令即凭证**：`QBANK_URL` 里的 `?t=` 就是访问口令，别提交进仓库、别贴到公开聊天里。
  服务重启会换口令，旧配置随之失效——这既是麻烦也是保护。
- **模型只能做工具允许的事**：没有「删库」「改作答记录」「执行 SQL」这类工具。
- **写操作可回滚**：覆盖发布前自动存档（每组留 20 版），最坏情况能回到上一版题目。

---

## 开发与验证

```bash
cd tools/qbank-mcp
pnpm install
pnpm typecheck
pnpm test      # 15 例：地址解析 / 读改写 / 真实 stdio 起进程调工具
pnpm build
```

测试不依赖真机：`src/__tests__/mock-server.ts` 用 `node:http` 复刻了手机端的
`/api/papers`、`/api/paper`、`/api/publish`（含「同名必须 replace」「题干不能空」），
`src/__tests__/mcp.test.ts` 则真的 spawn `dist/index.js`、用官方 SDK 的 Client 走 stdio 调工具，
断言「客户端看得到 14 个工具」「加题后 mock 里真的多了一题」「报错以 isError 返回」。

写协议时记住：**stdout 只跑 MCP 协议**，日志一律 `console.error`。

---

## 排错

| 现象 | 多半是 |
|---|---|
| 客户端里没有工具 | 没 `pnpm build`；`args` 路径不是绝对路径 |
| 每个工具都报「连不上手机」 | 不在同一 Wi-Fi / 服务停了 / 用了旧口令 |
| 报「未授权：请用 App 里显示的完整链接打开」 | `QBANK_URL` 少了 `?t=` |
| 加题成功但 App 没变 | App 列表没刷新；或加到了另一组（同名即同组） |
| 手机上不弹删除确认 | App 不在前台 → 先看通知；Android 13+ 没给通知权限就打开 App 那一页 |
