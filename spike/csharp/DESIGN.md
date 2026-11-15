# QBank C# 版 · 方案设计（实验性）

> 定位：**实验性项目**。目标不是替换现有 Android 版，而是验证「同一套题库逻辑 + MAUI 壳」能否跨端跑通，
> 并保留与 Android 版的数据互通能力。允许砍功能，不允许改数据契约。

---

## 一、已经证实的部分

本轮 spike（`spike/csharp/ImportParity.cs`，906 行，dotnet file-based app）把导入与判题整条链路搬到 C# 后，
用仓库里两份真实试卷跑对照，结果全绿：

| 验证项 | 结果 |
| --- | --- |
| 超格老于言语（18 表中 1 张说明表） | 拆出 **17 份卷 / 600 题**，0 错误，答案 600/600，选项全为 4 |
| 疯狂刷 2500 题 | 拆出 **15 份卷 / 600 题**，答案分布 **A145 B167 C145 D143** |
| 与 `文本校验报告.md` 记载对照 | 答案分布**逐字一致**，题量一致 |
| 判题对拍（喂标准答案 / 空 / 错答） | **3600 / 3600** |
| 判题规则单测（多选少选、不定项半分、判断归一、填空部分分、主观关键词） | **12 / 12** |
| 表头别名 + 无表头列嗅探 | **11 / 11** + 嗅探 PASS |

两条结论：

1. **核心逻辑是等量搬迁，不是重写。** 四个模块 Kotlin 原文 960 行 → C# 906 行，比例约 1:1。
   业务规则一行没改，`Policy` 默认值与 Android 版完全一致。
2. **xlsx 解析用 ClosedXML 比手写 `XmlPullParser` 省事**，但这部分省下的量被 C# 的样板代码（record/init/显式类型）抵掉了。
   真正省事的在别处：WebDAV 不再需要为 `PROPFIND` 引 OkHttp。

> 保留 `ImportParity.cs` 作为**回归基线**：后续任何改动都要能重跑出同样数字。

---

## 二、目标与边界

**要做的**

- 核心逻辑（模型 / 导入 / 判题 / 存储访问）收敛为一个跨平台类库，Android 版与 C# 版共用同一份数据契约
- 用 MAUI 做 Android + Windows 双端；Windows 用于桌面改题与调试，Android 是主目标
- 与 Android 版的**备份 zip 双向互通**：C# 版能读 Android 备份、导出的备份 Android 能还原

**不做的**

- 不替换现有 Android 版，不迁移线上用户数据链路
- 不追求像素级 1:1 复刻（Material 组件用 MAUI 风格收敛，手写标注除外——那块必须原样）
- 不做 iOS（先留接口，等 Android 跑顺再评估）
- 背诵模块（词库 / 政治 / 常识）二期再说，一期只做试卷 + 答题 + 报告

---

## 三、技术选型

| 层 | 选型 | 理由 |
| --- | --- | --- |
| 运行时 | .NET 11（SDK 已装 11.0.100，另存 10.0.404） | file-based app 与 MAUI workload 都支持 |
| UI | **MAUI** | 官方支持 Android / Windows / iOS 一套 XAML；原生控件压力最小 |
| 自绘 | **SkiaSharp** | 手写笔迹、材料里的图表、卷面细线都靠它；`SKCanvasView` 可控像素 |
| 表格 | **ClosedXML** | 已验证；`DocumentFormat.OpenXml` 补 docx |
| 压缩 | `System.IO.Compression` | 试卷包与备份 zip |
| 存储 | **Microsoft.Data.Sqlite** | 现有 DDL 是手写 SQL，可原样复用 |
| 网络 | `HttpClient` + `HttpListener` | WebDAV 自定义动词天然支持；局域网编辑器不引第三方 |
| 测试 | xUnit | 把 `ImportParity` 的断言搬成测试用例 |

**否决项**

- **Avalonia**：桌面体验好，但 Android 后端的触控 / 触控笔事件链薄，手写标注是它的弱项，正好是我们的核心诉求。
- **Uno Platform**：WASM 优先，Android 上既要背 WinUI 语义又要处理平台差异，等于两条战线。
- **Blazor Hybrid**：手写标注要穿透 WebView 拿压感，直接出局。

---

## 四、架构

```
┌──────────────────────────────────────────────┐
│  QBank.App (MAUI)                            │
│  Shell / Pages / ViewModels / 平台适配层       │
│  ├─ Platforms/Android  前台服务、通知、MotionEvent │
│  └─ Platforms/Windows  指针事件、文件系统          │
└───────────────────┬──────────────────────────┘
                    │ 只依赖接口，不依赖平台
┌───────────────────▼──────────────────────────┐
│  QBank.Core (net11.0, 零 UI 依赖)             │
│  Model · Import · Judge · Store · Backup     │
└───────────────────┬──────────────────────────┘
                    │
        ┌───────────▼───────────┐
        │ SQLite / 备份 zip      │  ← 与 Android 版同一套 DDL
        └───────────────────────┘
```

铁律：**Core 里不允许出现任何 `Microsoft.Maui.*` 引用**。这样它既能被 App 用，也能被 `tools/` 下的脚本和对照测试直接调用。

---

## 五、模块映射

| Kotlin（现有） | 行数 | C# 落点 | 状态 |
| --- | --- | --- | --- |
| `engine/Judge.kt` | 210 | `Core/Judge` | ✅ 已移植，12/12 |
| `importer/Normalizer.kt` | 413 | `Core/Import/Normalizer` | ✅ 已移植 |
| `importer/ColumnSniffer.kt` | 121 | `Core/Import/ColumnSniffer` | ✅ 已移植 |
| `importer/CsvImporter.kt` | 216 | `Core/Import/RowParser` | ✅ 已移植（表格部分） |
| `model/Question.kt` | 216 | `Core/Model` | ✅ 已移植 |
| `importer/Importer.kt` | 515 | `Core/Import/WorkbookImporter` | 🟡 已走通主链路，`StructureCheck` 未搬 |
| `importer/PaperSplitter.kt` | — | `Core/Import/PaperSplitter` | ⬜ 待做（「试卷」列拆卷） |
| `data/db/QBankDb.kt` | 2257 | `Core/Store/*Repository` | ⬜ 待做（17 张表，SQL 可搬） |
| `importer/QbankPackage.kt` | 291 | `Core/Import/Package` | ⬜ 待做 |
| `backup/*` | ~1200 | `Core/Backup` | ⬜ 待做（WebDAV 反而更短） |
| `ui/laneditor/LanEditorServer.kt` | 506 | `App/Platforms/Android` | ⬜ 待做 |
| `ui/InkOverlay.kt` | 1292 | `App/Ink` + SkiaSharp | ⬜ **闸门** |
| `ui/MaterialRenderer.kt` | 1175 | `Core/Render/Markdown` + SkiaSharp | ⬜ 待做 |
| 其余 Activity / Adapter / Fragment | ~14000 | MAUI Pages + ViewModels | ⬜ 待做 |

---

## 六、目录结构

```
spike/csharp/
├─ ImportParity.cs          # 本轮对照程序（保留为回归基线）
├─ DESIGN.md                # 本文
└─ src/
   ├─ QBank.Core/           # net11.0 类库，零 MAUI 依赖
   │  ├─ Model/             # Question / Option / Paper / AnswerResult
   │  ├─ Import/            # WorkbookImporter / Normalizer / ColumnSniffer / Package
   │  ├─ Judge/             # Judge / Policy / Summary
   │  ├─ Store/             # Schema + 各 Repository（复用现有 DDL）
   │  └─ Backup/            # BackupArchive / WebDav / AutoBackup
   ├─ QBank.Core.Tests/     # xUnit，ImportParity 的断言搬进来
   └─ QBank.App/            # MAUI
      ├─ Pages/ ViewModels/
      ├─ Ink/               # SkiaSharp 手写层
      └─ Platforms/Android/ # 前台服务、通知、MotionEvent 适配
```

---

## 七、关键设计决策

**D1 · 数据契约不动，先锁互通**
DDL 与备份 zip 结构保持与 Android 版一致，`schema_version` 同步升。
验收方式：C# 版读 Android 备份 → 立即再导出 → 与源包 diff（除时间戳外应为空）。这条不通过，后面不用做。

**D2 · 手写标注的数据模型不改**
沿用「锚点 = 题 id / 材料 id + 归一化坐标 + 笔色 + 压感」的结构，`inks` 表原样。这样笔迹在两端能互相打开，
也避免 MAUI 版把标注做成一次性功能。

**D3 · 手写渲染走平台直通，不用 MAUI 的触摸抽象**
`SKCanvasView` 之上，Android 侧直接用 `MotionEvent`（取 `GetPressure` / `GetHistorical*`），
Windows 侧用 `PointerPressed/Moved`。MAUI 的 `TouchBehavior` 拿不到压感与历史点，这是硬约束。

**D4 · 判题保持纯函数**
`Judge.Judge1(Question, string, Policy)` 无副作用，现有 3600 条对拍直接变成 xUnit 断言，改动即回归。

**D5 · 局域网编辑器 API 契约不变**
路径、写入口令（请求头 `X-Lan-Key`）、发布 / 回滚 / 删除需手机确认——全部照搬。好处是 `tools/qbank-mcp/` 与 `webeditor/` 一行不用改，
桌面端继续可用，这也是 C# 版最容易被验证的部分。

**D6 · 列表分页沿用现有查询语义**
试卷树「标题 → 卷 → 大类 → 材料」四级懒加载。MAUI `CollectionView` 在 600+ 项时性能一般，
数据源按现有 `questionCount` 只带计数、展开才读题，把压力挡在查询层。

---

## 八、分阶段与验收

| 阶段 | 内容 | 验收口径 | 状态 |
| --- | --- | --- | --- |
| **P0** | Core：模型 + 导入 + 判题 | 两份真实试卷题量/答案分布与 Kotlin 一致 | ✅ 已达成 |
| **P1** | Core：SQLite 存储 + 备份 zip 互通 | 读 Android 备份还原成功；反向导出可被 Android 还原 | ⬜ |
| **P2** | MAUI 只读链路：试卷树 → 答题 → 交卷 → 报告 | 与 Android 版逐页对照，答案 / 得分一致 | ⬜ |
| **P3** | **手写闸门**：SkiaSharp 压感 + 掌心抑制 + 锚点跟随 | 见下方指标，**不过则砍功能或终止** | ⬜ |
| **P4** | 写操作：导入 / 删除 / 补答案 / 局域网编辑器 | 手机浏览器改题 → 入库 → App 可见 | ⬜ |
| **P5** | 打包、签名、CI | `dotnet publish` 出可安装 APK，与原版共存 | ⬜ |

**P3 闸门指标（go / no-go）**

- 落笔到可见延迟 ≤ 1 帧（60Hz 屏 ≤ 16ms）
- 掌托 / 第二指落下不打断笔迹，误触率 0
- 单题 2000 笔重绘 ≥ 50fps
- 滚动、切单题/多题、交卷出解析后笔迹不跑位
- 笔迹数据能被 Android 版打开并正确渲染

任一条不达标就先修，修不动就砍掉手写标注（其余功能不受影响），**不带着不确定的手写层往下走**。

---

## 九、风险

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| MAUI 在 Android 的自绘 + 触摸链路不成熟 | 手写层做不出来 | P3 提前到 P2 之前做，先证伪 |
| `CollectionView` 长列表性能 | 试卷树 / 题号网格卡顿 | 分页 + 只带计数；必要时自写 `RecyclerView` 适配 |
| Material 视觉缺失（53 布局 / 86 drawable） | 观感与 Android 版差距大 | 接受风格收敛，只保结构与交互一致 |
| MAUI workload 版本与 .NET 11 匹配 | 构建不稳 | 固定 SDK 版本；CI 锁 `global.json` |
| APK 体积（MAUI 基线比原生大） | 安装包变胖 | 开 AOT/裁剪；实验期可接受 |

---

## 十、需要你定的三件事

1. **平台优先级**：先跑 Windows 桌面（调试快、手写可用数位板验证），还是直接上 Android（真实手感，但迭代慢）？
2. **互通是否作为硬指标**：我按「备份 zip 双向互通」设计（D1）。如果实验期不要求互通，P1 可以简化成「只读已有 SQLite 文件」，省一阶段。
3. **视觉标准**：接受 MAUI 风格化（结构对、观感不同），还是要求与 Android 版贴近到可截图对比？

这三条定了，P1 就能直接开工。
