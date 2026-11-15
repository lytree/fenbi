/** 与手机端 LanEditorStore 的 JSON 约定，一一对应 */
export type QuestionType = 'single' | 'multi' | 'uncertain' | 'truefalse' | 'fill' | 'subjective'

export interface OptionItem {
  key: string
  text: string
}

export interface QIssue {
  level: 'error' | 'warn'
  message: string
}

export interface Question {
  _uid: number
  _issues?: QIssue[] | null
  type: QuestionType
  subject: string
  material: string
  images: string
  stem: string
  options: OptionItem[]
  answer: string
  analysis: string
  knowledge: string
  difficulty: string
  region: string
  source: string
  year: string
  score: string | number
  keywords: string
  /**
   * 选项排布（**每题独立**，只有网页编辑器能改）：
   * 空串 = 单列 1×4（默认）；'2x2' = 两列两行。只影响文字选项。
   */
  optionLayout: string
  id?: number
}

/** Web 端的一张工作表 = App 里的一份卷 */
export interface Sheet {
  name: string
  questions: Question[]
}

export interface EditorPaper {
  id: number
  name: string
  sheets: Sheet[]
}

export interface SetPaperInfo {
  id: number
  name: string
  questionCount: number
  missingAnswers: number
}

export interface PaperSetInfo {
  id: number
  title: string
  source: string
  paperCount: number
  questionCount: number
  missingAnswers: number
  editable: boolean
  /** 已归档：侧栏收进「归档」区，不再出现在编辑列表里；数据都还在 */
  archived: boolean
  papers: SetPaperInfo[]
}

export interface Issue {
  level: 'error' | 'warn'
  sheet: number
  index: number
  message: string
}

export type Filter = 'all' | 'issue' | 'noanswer' | 'image' | 'group'

/** 编辑视图：题目卡列表 / 以材料为中心的编辑模式 / 拖拽分组板 */
export type EditorView = 'list' | 'material' | 'board'

/** 按题型筛（单选 / 判断 / 填空 …） */
export type TypeFilter = 'all' | QuestionType

/**
 * 按一级题型筛（复用「科目」）。值是归一后的大类名（「行测·资料分析」→「资料分析」），
 * 所以选项只能从当前这卷里现算；'all' = 不限，没填一级的题单独一项。
 */
export type SubjectFilter = string

export interface Group {
  start: number
  end: number
  material: string
}

/** 传给手机端的结构：前端临时字段（_uid/_issues）不出去 */
export type WireQuestion = Omit<Question, '_uid' | '_issues'>

export interface WireSheet {
  name: string
  questions: WireQuestion[]
}

/** 版本存档：覆盖发布 / 回滚之前自动存的一份旧内容 */
export interface VersionInfo {
  id: string
  title: string
  /** publish = 发布前的旧版；restore = 回滚前的旧版 */
  reason: string
  savedAt: number
  paperCount: number
  questionCount: number
}

/** 删除请求在手机上的处理结果 */
export type ConfirmStatus = 'pending' | 'approved' | 'rejected' | 'expired'

/** 一组试卷的图片资源（按组编号分目录） */
export interface ResourceFile {
  /** 题目里的相对路径，如 images/12/xxx.png */
  path: string
  name: string
  size: number
  mtime: number
}

/** 图片迁移的结果统计 */
export interface MigrationReport {
  ok: boolean
  /** 整理了几个组 */
  sets: number
  /** 扫了多少道题 */
  questions: number
  /** 实际改写并写回题库的题数 */
  updated: number
  /** 复制进组目录的图片张数 */
  moved: number
  /** 本来就在组目录里的引用数 */
  reused: number
  /** 引用找不到文件的处数 */
  missing: number
  /** 图片根目录下没被任何题引用的散图数量（不搬，留给人工判断） */
  orphans: number
}

/* ------------------------------------------------------------------ 背诵词库（政治 / 常识） */

/** 背诵内容的一块：政治 / 常识 */
export interface ReciteSubjectInfo {
  id: string
  title: string
  intro: string
  updated: string
  categoryCount: number
  cardCount: number
  /** 是否有网页 / AI 存过的覆盖内容（false = 还是 App 内置那份） */
  hasEdits: boolean
}

export interface ReciteCategory {
  id: string
  name: string
  /** 二级分组标题，空串 = 未分组 */
  group: string
  /** 该分类下的卡片数（服务端算好） */
  count?: number
}

/** 一张背诵卡：正文用 ____ 标填空，answers 按顺序对应每个空 */
export interface ReciteCard {
  id: string
  cat: string
  text: string
  answers: string[]
  note: string
  source: string
}

/** 整份词库（服务端整读整写，编辑在前端做完一次提交） */
export interface ReciteCatalog {
  subject: string
  title: string
  intro: string
  updated: string
  hasEdits: boolean
  categories: ReciteCategory[]
  cards: ReciteCard[]
}

/** 保存 / 重置成功后的摘要 */
export interface ReciteSaveResult {
  ok: boolean
  subject: string
  title?: string
  categoryCount?: number
  cardCount?: number
  updated?: string
  /** 仅重置：是否真的删过覆盖文件（false = 本来就没有） */
  reset?: boolean
}

/* ------------------------------------------------------------------ 词库（词条） */

export interface WordItem {
  id: number
  text: string
  pinyin: string
  initial: string
  category: string
  tag: string
  meaning: string
  example: string
  source: string
}

export interface WordsResponse {
  words: WordItem[]
  total: number
  /** 分类计数：idiom / word / other */
  counts: Record<string, number>
  /** 标签清单（含条数），「未标注」name 为空串 */
  tags: { name: string; count: number }[]
}

/* ------------------------------------------------------------------ 思维导图 */

/**
 * 导图的一个节点：一句话 + 它下面挂的要点。
 *
 * 树只由 children 表达（旧资产是扁平 outline + level 字段，深层一多就分不清缩进
 * 到底代表哪一级）。叶子节点不写 children，省体积。
 */
export interface MindMapNode {
  text: string
  children?: MindMapNode[]
}

/** 书页下半部「内涵连线」的一组对应：提法 ↔ 内涵 */
export interface MindMapPair {
  left: string
  right: string
}

/** 一页书 = 一个主题 */
export interface MindMapTopic {
  id: string
  /** 源 PDF 页码（书页号 + 2），对照扫件用 */
  page?: number
  /** 列表上的小标签，如「书页 02」 */
  label: string
  name: string
  /** 节点数（服务端算好；编辑后前端重算，只作展示） */
  nodeCount?: number
  nodes: MindMapNode[]
  /** 连线题单独存：新树把它们错还原成了父子，配对关系以这里为准 */
  pairs: MindMapPair[]
}

/** 书里的一个大板块 */
export interface MindMapSection {
  id: string
  name: string
  nodeCount?: number
  topics: MindMapTopic[]
}

/** 整份导图（服务端整读整写，编辑在前端做完一次提交） */
export interface MindMapCatalog {
  version: number
  title: string
  intro: string
  updated: string
  hasEdits: boolean
  topicCount?: number
  nodeCount?: number
  sections: MindMapSection[]
}

/** 保存 / 重置成功后的摘要 */
export interface MindMapSaveResult {
  ok: boolean
  sectionCount?: number
  topicCount?: number
  nodeCount?: number
  updated?: string
  /** 仅重置：是否真的删过覆盖文件（false = 本来就没有） */
  reset?: boolean
}
