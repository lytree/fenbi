#!/usr/bin/env node
/**
 * fenbi-qbank MCP server
 *
 * 把手机 App 里那个「局域网编辑器」的 HTTP 接口包装成 MCP 工具，
 * 让 Claude Desktop / Cursor / Cherry Studio 这类客户端直接读写题库：
 * 读组、读题、加题、改题、删题、发布、回滚版本、申请删除整组。
 *
 * 传输用 stdio（客户端拉起本进程，一问一答），所以 **stdout 只能跑协议**，
 * 所有日志一律写 stderr。
 */
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { z } from 'zod'
import { configFromEnv } from './config.js'
import { QbankClient } from './client.js'
import { questionSchema, sheetSchema } from './schema.js'
import {
  addQuestions,
  auditSet,
  batchUpdateQuestions,
  deleteQuestion,
  deleteSet,
  findSet,
  formatRecords,
  formatSets,
  listVersions,
  publishSet,
  readRecord,
  readSet,
  restoreVersion,
  setAnalysis,
  updateQuestion,
} from './tools.js'

const VERSION = '0.1.0'

const text = (value: string) => ({ content: [{ type: 'text' as const, text: value }] })
const failure = (e: unknown) => ({
  content: [{ type: 'text' as const, text: `出错了：${e instanceof Error ? e.message : String(e)}` }],
  isError: true,
})

/** 配置有问题不退出：让客户端能连上，调工具时再明确告诉它该配什么 */
let client: QbankClient | null = null
let configError: string | null = null
try {
  client = new QbankClient(configFromEnv())
  process.stderr.write(`[fenbi-qbank-mcp] 目标手机：${client.host}\n`)
} catch (e) {
  configError = e instanceof Error ? e.message : String(e)
  process.stderr.write(`[fenbi-qbank-mcp] ${configError}\n`)
}

function need(): QbankClient {
  if (!client) throw new Error(configError ?? '还没配置手机地址')
  return client
}

const server = new McpServer({ name: 'fenbi-qbank', version: VERSION })

/* ------------------------------------------------------------------ 读 */

server.registerTool(
  'qbank_list_sets',
  {
    title: '列出题库里的所有组',
    description:
      '列出手机题库里现有的「组」（App 首页那一层标题），带卷数、题数、缺答案数，以及每份卷的名字。' +
      '任何操作之前先调它，拿到组名和 id。',
    inputSchema: {},
    annotations: { readOnlyHint: true },
  },
  async () => {
    try {
      const r = await need().listSets()
      return text(formatSets(r.sets, r.questionTotal))
    } catch (e) {
      return failure(e)
    }
  }
)

server.registerTool(
  'qbank_get_set',
  {
    title: '读一组的题目',
    description:
      '读某一组的题目（按卷、按卷面顺序），带题型、材料、选项、答案、解析。' +
      '默认每次 20 题，用 offset 翻页；题多的时候不要一次拉完，先看前 20 题。' +
      '返回里的「【卷名】第 N 题」就是改题 / 删题要用的 sheet + index。',
    inputSchema: {
      setId: z.number().int().positive().optional().describe('组 id；和 title 二选一'),
      title: z.string().optional().describe('组名；和 setId 二选一（支持唯一的部分匹配）'),
      sheet: z.string().optional().describe('只看这一张卷；不传就是全部卷'),
      offset: z.number().int().min(0).optional().describe('从第几题开始（0 开始数），默认 0'),
      limit: z.number().int().min(1).max(100).optional().describe('一次读几题，默认 20，最多 100'),
      onlyMissingAnalysis: z
        .boolean()
        .optional()
        .describe('只看还没有解析的题；补解析时先用它把缺口找出来'),
    },
    annotations: { readOnlyHint: true },
  },
  async (args) => {
    try {
      return text(await readSet(need(), args))
    } catch (e) {
      return failure(e)
    }
  }
)

server.registerTool(
  'qbank_list_versions',
  {
    title: '列出版本存档',
    description:
      '列出某一组（或全部）的历史版本。每次覆盖发布 / 回滚之前，手机端会自动把旧内容存一版；' +
      '这里的 id 可以交给 qbank_restore_version 回滚。',
    inputSchema: {
      title: z.string().optional().describe('组名；不传就列全部'),
    },
    annotations: { readOnlyHint: true },
  },
  async (args) => {
    try {
      return text(await listVersions(need(), args.title))
    } catch (e) {
      return failure(e)
    }
  }
)

/* ------------------------------------------------------------------ 批改 */

server.registerTool(
  'qbank_list_records',
  {
    title: '列出作答记录（批改入口）',
    description:
      '列出手机上的作答记录（做完的、没做完的都在），带分数、正确率、用时。' +
      '要批改某次练习时先调它拿 recordId，再用 qbank_get_record 看逐题对错。' +
      '传 title 就只看某一组的记录。',
    inputSchema: {
      title: z.string().optional().describe('只列这一组（组名）的记录；不传就是全部'),
      limit: z.number().int().min(1).max(200).optional().describe('最多返回几条，默认 20'),
    },
    annotations: { readOnlyHint: true },
  },
  async (args) => {
    try {
      const c = need()
      const setId = args.title ? (await findSet(c, { title: args.title })).id : undefined
      const r = await c.listRecords(setId)
      const limit = Math.min(200, Math.max(1, Math.trunc(args.limit ?? 20)))
      return text(formatRecords(r.records.slice(0, limit), r.total))
    } catch (e) {
      return failure(e)
    }
  }
)

server.registerTool(
  'qbank_get_record',
  {
    title: '读一次作答的逐题对错',
    description:
      '读某次作答的逐题明细：题干、选项、你选的答案、正确答案、对错、得分、题目现有解析。' +
      '批改时先用 wrongOnly=true 只看错题；题多时用 offset / limit 翻页（默认 20 条）。' +
      '**只读**：不改分数、不改作答记录——AI 的批改是讲解和建议，最终分数仍归 App。',
    inputSchema: {
      recordId: z.number().int().positive().describe('qbank_list_records 里的 #id'),
      wrongOnly: z.boolean().optional().describe('只看错题（未答 / 有待补答案的题不算），默认 false'),
      offset: z.number().int().min(0).optional().describe('从第几条开始，默认 0'),
      limit: z.number().int().min(1).max(50).optional().describe('一次读几题，默认 20，最多 50'),
    },
    annotations: { readOnlyHint: true },
  },
  async (args) => {
    try {
      return text(await readRecord(need(), args))
    } catch (e) {
      return failure(e)
    }
  }
)

server.registerTool(
  'qbank_set_analysis',
  {
    title: '批量写入解析',
    description:
      '把解析写回题目并整组发布。一次传多条（通常 10–20 条），整组只重导一次。' +
      'index 从 1 开始，是 qbank_get_set 里显示的「第 N 题」；跨卷时在每条里带 sheet。' +
      '只要有一条 sheet/index 对不上，就整批不写并列出是哪些。' +
      '配合 qbank_get_set({ onlyMissingAnalysis: true }) 先找缺解析的题。',
    inputSchema: {
      title: z.string().min(1).describe('组名'),
      updates: z
        .array(
          z.object({
            sheet: z.string().optional().describe('卷名；不传就用第一张卷'),
            index: z.number().int().min(1).describe('第几题（从 1 开始）'),
            analysis: z.string().min(1).describe('解析正文（纯文本或 Markdown）'),
          })
        )
        .min(1)
        .max(50)
        .describe('要写入的解析，一次最多 50 条'),
    },
    annotations: { destructiveHint: true },
  },
  async (args) => {
    try {
      return text(await setAnalysis(need(), args))
    } catch (e) {
      return failure(e)
    }
  }
)

server.registerTool(
  'qbank_audit_set',
  {
    title: '体检：找出可能有错的题',
    description:
      '按 App 的导入口径和常见脏数据体检一组题：缺答案、选项不足两个、答案不在选项范围、' +
      '单选多答案、判断题答案写法不对、缺解析、解析里写的答案与字段不一致、题干重复、HTML 残留。' +
      '只报不改——拿到清单后自己判断怎么修，再用 qbank_batch_update_questions 一次写回。',
    inputSchema: {
      title: z.string().optional().describe('组名；和 setId 二选一'),
      setId: z.number().int().positive().optional().describe('组 id；和 title 二选一'),
      limit: z.number().int().min(1).max(200).optional().describe('最多列多少条，默认 60'),
    },
    annotations: { readOnlyHint: true },
  },
  async (args) => {
    try {
      return text(await auditSet(need(), args))
    } catch (e) {
      return failure(e)
    }
  }
)

server.registerTool(
  'qbank_batch_update_questions',
  {
    title: '批量修正题目内容',
    description:
      '按 sheet + index 一次改多道题，然后整组发布一次。patch 只传要改的字段（题干 / 选项 / 答案 / 解析…），' +
      '没传的保持原样；改选项要传完整的 options 数组。任何一条 sheet/index 对不上就整批不写。' +
      '典型用法：qbank_audit_set 出清单 → 逐条修 → 调这个工具一次写回。',
    inputSchema: {
      title: z.string().min(1).describe('组名'),
      updates: z
        .array(
          z.object({
            sheet: z.string().optional().describe('卷名；不传就用第一张卷'),
            index: z.number().int().min(1).describe('第几题（从 1 开始）'),
            patch: questionSchema.partial().describe('要改的字段，没传的保持原样'),
          })
        )
        .min(1)
        .max(50)
        .describe('要改的题目，一次最多 50 条'),
    },
    annotations: { destructiveHint: true },
  },
  async (args) => {
    try {
      return text(await batchUpdateQuestions(need(), args))
    } catch (e) {
      return failure(e)
    }
  }
)

/* ------------------------------------------------------------------ 写 */

server.registerTool(
  'qbank_add_questions',
  {
    title: '往一组里加题',
    description:
      '往指定组（组不存在就新建）的某一卷末尾追加题目，然后整组发布到 App。' +
      '这是加题的首选工具：只需要传新题，不用把整组题目回传。' +
      '注意手机端是「整组重导」：提交成功后旧卷的作答记录会被替换掉（题目内容会先自动存一个版本，可回滚）。',
    inputSchema: {
      title: z.string().min(1).describe('组名（App 首页那一层标题）'),
      sheet: z.string().optional().describe('卷名；不传就加到第一张卷，组是新建的则默认叫「试卷」'),
      questions: z.array(questionSchema).min(1).describe('要追加的题目，按卷面顺序'),
    },
    annotations: { destructiveHint: true },
  },
  async (args) => {
    try {
      return text(await addQuestions(need(), args))
    } catch (e) {
      return failure(e)
    }
  }
)

server.registerTool(
  'qbank_update_question',
  {
    title: '改一道题',
    description:
      '把某一组的某道题按 patch 合并修改（只传要改的字段，比如 {"answer":"B"}），然后整组发布。' +
      'index 从 1 开始，是 qbank_get_set 里显示的「第 N 题」。改选项要传完整的 options 数组。',
    inputSchema: {
      title: z.string().min(1).describe('组名'),
      sheet: z.string().optional().describe('卷名；不传就是第一张卷'),
      index: z.number().int().min(1).describe('第几题（从 1 开始）'),
      patch: questionSchema.partial().describe('要改的字段，没传的保持原样'),
    },
    annotations: { destructiveHint: true },
  },
  async (args) => {
    try {
      return text(await updateQuestion(need(), args))
    } catch (e) {
      return failure(e)
    }
  }
)

server.registerTool(
  'qbank_delete_question',
  {
    title: '删一道题',
    description:
      '删掉某组的某道题，然后整组重新发布。index 从 1 开始。' +
      '如果删完整张卷空了，这张卷会一起移除；整组只剩一张卷时会拒绝，改用 qbank_delete_set。',
    inputSchema: {
      title: z.string().min(1).describe('组名'),
      sheet: z.string().optional().describe('卷名；不传就是第一张卷'),
      index: z.number().int().min(1).describe('第几题（从 1 开始）'),
    },
    annotations: { destructiveHint: true },
  },
  async (args) => {
    try {
      return text(await deleteQuestion(need(), args))
    } catch (e) {
      return failure(e)
    }
  }
)

server.registerTool(
  'qbank_publish_set',
  {
    title: '整组发布（覆盖或新建）',
    description:
      '按标题把一整组卷发布到手机题库，等于网页上点「发布到 App」。' +
      '同名组已存在时必须显式传 replace=true，否则会失败并提示；覆盖会替换旧卷与作答记录，' +
      '但旧题目内容会先自动存一个版本（qbank_list_versions 可查、qbank_restore_version 可回滚）。' +
      '一般加题用 qbank_add_questions 就够了，这个工具用于批量导入或整组重写。',
    inputSchema: {
      title: z.string().min(1).describe('组名'),
      sheets: z.array(sheetSchema).min(1).describe('整组的卷与题目（会完整替换这一组）'),
      replace: z.boolean().optional().describe('同名组是否覆盖；默认 false'),
    },
    annotations: { destructiveHint: true },
  },
  async (args) => {
    try {
      return text(await publishSet(need(), args))
    } catch (e) {
      return failure(e)
    }
  }
)

server.registerTool(
  'qbank_restore_version',
  {
    title: '回滚到某一版',
    description:
      '把某一组恢复成历史版本里的样子。回滚本身也是一次覆盖，所以手机端会先把「现在」也存一版，' +
      '滚错了还能再滚回来。作答记录不会跟着回来。',
    inputSchema: {
      versionId: z.string().min(1).describe('qbank_list_versions 里那一行的 id'),
      title: z.string().optional().describe('回滚落到哪个组；不传就按版本里存的组名'),
    },
    annotations: { destructiveHint: true },
  },
  async (args) => {
    try {
      return text(await restoreVersion(need(), args))
    } catch (e) {
      return failure(e)
    }
  }
)

server.registerTool(
  'qbank_delete_set',
  {
    title: '删除一整组（要手机确认）',
    description:
      '删除一组试卷。**不会立即执行**：请求会发到手机上弹确认框，人在手机上点「确认删除」才真的删，' +
      '10 分钟没人理就作废。默认等 45 秒看结果；没等到就告诉用户去手机上点，稍后再调一次不会重复发请求。',
    inputSchema: {
      setId: z.number().int().positive().optional().describe('组 id；和 title 二选一'),
      title: z.string().optional().describe('组名；和 setId 二选一'),
      waitSeconds: z
        .number()
        .int()
        .min(0)
        .max(300)
        .optional()
        .describe('等手机确认多少秒，默认 45；传 0 表示发完就返回'),
    },
    annotations: { destructiveHint: true },
  },
  async (args) => {
    try {
      return text(await deleteSet(need(), args))
    } catch (e) {
      return failure(e)
    }
  }
)

/* ------------------------------------------------------------------ 启动 */

const transport = new StdioServerTransport()
await server.connect(transport)
process.stderr.write(`[fenbi-qbank-mcp] 已就绪（v${VERSION}）\n`)
