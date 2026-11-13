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

/** 数据源：手机局域网题库 / 电脑本地试卷包（开发服务器提供） */
export type DataSource = 'phone' | 'local'

/** 本地试卷包（胶水层由 vite 开发服务器提供） */
export interface LocalPackageInfo {
  id: string
  title: string
  path: string
  questionCount: number
}

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
