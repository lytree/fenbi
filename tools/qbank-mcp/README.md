# 题库 MCP server

把手机 App 里那个「局域网编辑器」包装成 **MCP（Model Context Protocol）** 工具，
让 Claude Desktop / Cursor / Cherry Studio 这类客户端**直接读写手机里的题库**：
列组、读题、加题、改题、删题、整组发布、回滚版本、申请删除整组。

它不碰数据库，只是 `LanEditorServer` 那套 HTTP 接口的一层薄封装——
和你在电脑浏览器里点「发布到 App」走的是同一条路。

```
AI 客户端 ──stdio──▶ dist/index.js ──HTTP(+?t=口令)──▶ 手机 App（LanEditorServer）──▶ SQLite
```

---

## 一、准备

1. 手机打开 **设置 → 在电脑上加题 → 局域网编辑器 → 启动服务**。
2. 点「复制地址」，形如：`http://192.168.1.23:8777/?t=k3m9x2q7ab4c`。
   地址里那串 `?t=` 是口令，**每次启动都会变**。
3. 手机和跑 AI 客户端的电脑在同一个 Wi-Fi。
4. 同页面往下拉到 **AI 接入（MCP）**，点「复制配置」——里面已经带好当前地址和口令，
   粘到客户端配置里只要改一处路径（见下一节）。

```bash
cd tools/qbank-mcp
pnpm install
pnpm build          # 产物在 dist/
```

> 配置从哪来：手拼也行，但口令每次启动都变，**从 App 页面直接复制最省事**。
> 未启动服务时页面显示的是一份带占位地址的样例，方便先看清结构。

## 二、接到客户端

把 `mcp.example.json` 里的内容抄进客户端的 MCP 配置，改两处：`args` 的绝对路径、
`env.QBANK_URL` 的地址。

Claude Desktop（`claude_desktop_config.json`）：

```json
{
  "mcpServers": {
    "fenbi-qbank": {
      "command": "node",
      "args": ["F:\\Code\\Android\\fenbi\\tools\\qbank-mcp\\dist\\index.js"],
      "env": {
        "QBANK_URL": "http://192.168.1.23:8777/?t=k3m9x2q7ab4c"
      }
    }
  }
}
```

Cursor / 其他客户端同理，只是配置文件位置不同。也可以不用 `env`，改成命令行参数：

```json
{
  "command": "node",
  "args": [
    "F:\\Code\\Android\\fenbi\\tools\\qbank-mcp\\dist\\index.js",
    "--url",
    "http://192.168.1.23:8777/?t=k3m9x2q7ab4c"
  ]
}
```

也可以只给 host 和 token（省得每次拼 URL）：

```json
"env": { "QBANK_HOST": "192.168.1.23:8777", "QBANK_TOKEN": "k3m9x2q7ab4c" }
```

改完重启客户端，工具列表里应该出现 14 个 `qbank_*` 工具。

## 三、工具清单

| 工具 | 作用 | 破坏性 |
|---|---|---|
| **读题** | | |
| `qbank_list_sets` | 列组：卷数、题数、缺答案数、每份卷的名字 | 只读 |
| `qbank_get_set` | 读某组题目（默认 20 题一页，`offset` 翻页，可只看某卷；`onlyMissingAnalysis` 只看缺解析） | 只读 |
| `qbank_list_versions` | 列历史版本（覆盖发布 / 回滚前自动存档） | 只读 |
| **批改** | | |
| `qbank_list_records` | 作答记录：分数、正确率、用时，拿 `recordId` | 只读 |
| `qbank_get_record` | 一次作答的逐题明细：你答什么、正确答案、对错、得分、现有解析（`wrongOnly` 只看错题） | 只读 |
| **体检 / 修错** | | |
| `qbank_audit_set` | 体检：缺答案、选项不足、答案越界、单选多答案、判断题写法、缺解析、解析与答案冲突、题干重复、HTML 残留 | 只读 |
| `qbank_batch_update_questions` | 一次改多题（题干 / 选项 / 答案 / 解析），只重导一次 | 覆盖整组 |
| `qbank_set_analysis` | 批量写解析（通常配合 `onlyMissingAnalysis`） | 覆盖整组 |
| **写题** | | |
| `qbank_add_questions` | 往某组某卷末尾加题，组不存在就新建 | 覆盖整组 |
| `qbank_update_question` | 按 `sheet + index` 改一道题（只传要改的字段） | 覆盖整组 |
| `qbank_delete_question` | 删一道题（整卷删空则移除该卷） | 覆盖整组 |
| `qbank_publish_set` | 整组发布：新建或覆盖（覆盖要显式 `replace: true`） | 覆盖整组 |
| `qbank_restore_version` | 回滚到某一版（回滚前也会先存一版） | 覆盖整组 |
| `qbank_delete_set` | 删整组——**要手机点确认才执行** | 删除 |

### 三条必须知道的口径

1. **写操作都是「整组重导」**。加一道题，实际是读出整组、追加、整组重新发布。
   旧卷的**作答记录会被替换掉**（这是 App 导入的语义，不是 MCP 加的）。
   题目按 id 原地更新，所以**收藏、笔记、卷描述、限时都保留**。
   题目内容也不会丢：覆盖前手机端会自动存一个版本，`qbank_list_versions` 能查、能回滚
   （存档失败会直接取消这次覆盖，不会「没备份也照写」）。
2. **删除不由 AI 决定**。`qbank_delete_set` 只是把请求发到手机，手机上弹确认框，
   人点了「确认删除」才真的删，10 分钟没人理就作废。默认等 45 秒，超时就如实告诉用户去手机上点。
3. **批改只读**。`qbank_list_records` / `qbank_get_record` 不会改分数、不会改作答记录——
   AI 的批改是「看错题、讲错因、指出解析缺漏」，分数仍以 App 为准。
   要改题目本身，走 `qbank_batch_update_questions`（会存版本，可回滚）。

## 四、典型用法

> 「看看我手机里有哪些试卷」

调 `qbank_list_sets`。

> 「把下面这道题加到 2024国考 的行测卷最后」（后面贴题目）

调 `qbank_add_questions`，只需要传新题，不用回传整组。

> 「第二题答案好像错了，应该是 C」

先 `qbank_get_set`（确认第 2 题是哪道），再 `qbank_update_question`：`{ sheet: "行测", index: 2, patch: { answer: "C" } }`。

> 「上次改的那版有个错别字，回到上一版」

`qbank_list_versions` → `qbank_restore_version`。

> 「把 2019省考 那组删了」

`qbank_delete_set` → 手机会弹确认框，手机上点完才有结果。

> 「帮我检查一下 2024国考 有没有错题」

`qbank_audit_set` 出一张清单（哪张卷第几题、什么毛病），逐条确认后调
`qbank_batch_update_questions` 一次写回。典型能自动修的：答案不在选项范围、解析里写的答案与字段不一致、
缺解析、题干里的 HTML 残留；拿不准的（比如解析和答案谁对）它会先列出来问你。

> 「批改我昨天做的那套行测」

`qbank_list_records` 找到那次记录 → `qbank_get_record({ recordId, wrongOnly: true })` 逐题看错因，
讲完如果发现某几题解析缺失或写错，直接 `qbank_set_analysis` / `qbank_batch_update_questions` 顺手补上。

## 五、排错

**客户端里看不到工具**：`pnpm build` 有没有跑过？`args` 里的路径是不是绝对路径、有没有反斜杠转义？

**每次调用都提示「连不上手机」**：检查 Wi-Fi 是否同一网段、App 里的服务是否还在跑、
地址是不是最近一次启动复制的那串（服务重启后口令会变）。

**提示「未授权：请用 App 里显示的完整链接打开」**：`QBANK_URL` 里少了 `?t=`。

**加题成功了但 App 里没变化**：发布成功后 App 的试卷列表需要刷新一次；另外确认加的是同一组
（同名组就是同一个，重名会覆盖）。

**AI 说要删组但手机没弹窗**：App 不在前台时会先发通知，点通知进去才弹确认框；
Android 13+ 没给通知权限的话，只有把 App 打开到「局域网编辑器」页面才会弹。

## 六、开发

```bash
pnpm typecheck   # tsc --noEmit
pnpm test        # 20 个用例：地址解析 / 读改写 / 真实 stdio 起进程调工具
pnpm build
pnpm start       # 手动跑（需要 QBANK_URL），日志在 stderr
```

测试不连真机：`src/__tests__/mock-server.ts` 起一个本地 HTTP 服务，按 `LanEditorServer.kt`
的约定复刻了 `/api/papers`、`/api/paper`、`/api/publish`（包括同名要 `replace`、题干不能空）。

写协议输出时注意：**stdout 是 MCP 的协议通道**，任何调试信息都得走 `console.error`，
写到 stdout 会让客户端解析失败。
