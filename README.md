# 自定义题库刷题 App

一个可以**导入自己的题库**并刷题的 Android 应用。支持 `JSON` / `CSV` / `Excel(.xlsx)` / `TXT`
四种题库格式，自动探测格式与编码，导入后可按题型、难度、知识点组卷，限时答题、手写标注、查看成绩报告。

---

## 一、技术栈

| 项 | 说明 |
|---|---|
| 语言 | Kotlin |
| UI | 原生 View 体系 + ViewBinding（不引入 Compose，也不依赖第三方 UI 库） |
| 存储 | `SQLiteOpenHelper`，8 张表，无注解处理器 |
| 最低版本 | Android 7.0（API 24） |
| 编译 / 目标 | compileSdk 35 / targetSdk 35 |
| 构建 | AGP 8.7 + Gradle 8.11 + JDK 17 |
| 依赖 | 仅 AndroidX + Material；xlsx 解析为自研零依赖 OOXML 读取器（不引 Apache POI） |

---

## 二、功能

* **题库导入**：`JSON` / `CSV` / `Excel(.xlsx)` / `TXT` 四种格式，自动探测格式、自动识别编码（UTF-8 / GBK）、表头别名自动映射
* **导入校验**：逐行规范化 + 错误/警告清单（定位到行号），可勾选后再入库
* **题库管理**：多题库、题目列表、知识点/地区/难度/来源标签
* **组卷**：按题型、题量、难度、知识点、地区随机抽题，可设限时
* **答题页**：顶部关闭 / 计时胶囊 / 铅笔 / 书签 / 更多图标区，左上题库名 + 右上进度（`6/11`），浅蓝题型标签，圆形 A/B/C/D 选项徽标（选中变蓝色实心圆），底部上一题 / 答题卡 / 标记 / 下一题操作条
* **横向滑动切题**：`ViewPager2` 左右滑动翻页，与手写标注共存不冲突
* **手写笔标注**：触控笔（`TOOL_TYPE_STYLUS`）可在题干/选项上写画，**带压感笔迹粗细**；悬浮工具条提供撤销 / 清空 / 手指绘制开关 / 完成；笔迹按题目持久化，切题往返后自动恢复
* **资料分析左右分屏**：「更多 → 开启左右分屏」进入分屏页——左侧材料（Markdown 表格渲染成可横向滚动的真表格），右侧该材料对应的全部题目；两栏都能手写标注
* **标记与笔记**：书签图标标记本题，「更多 → 编辑笔记」写思路，笔记按题保存
* **计时与交卷**：倒计时、剩 5 分钟提醒、超时自动交卷、交卷二次确认（"你还有 N 道题目未作答，确定交卷吗？"）
* **成绩报告**：得分 / 正确率 / 用时 / 逐题解析，四态标色（全对 / 部分得分 / 错误 / 未答）
* **判题规则可配**：多选少选是否给分、不定项少选给 50%、填空漏填给部分分、主观题关键词命中给部分分、判断题兼容"对/错/√/×/T/F"

### 当前版本范围：仅选择题

答题流程当前只完整支持选择题（单选 / 多选 / 不定项 / 判断）。

* 答题页不渲染填空输入框与主观题作答区
* 题库中若存在填空 / 主观题，导入时会被拒绝或降级为单选题（带选项时）
* 数据模型层仍保留 `FILL` / `SUBJECTIVE` 题型定义与对应判题规则（`Judge.kt`），以便后续放开

### 判题口径（`engine/Judge.kt`）

| 题型 | 规则 |
|---|---|
| 单选 | 答案键完全一致才得分 |
| 多选 | 默认**全对才得分**（多选/少选/错选均不得分），可在设置里开启"少选给部分分" |
| 不定项 | 全对满分；少选给 50%（可配）；错选 0 分 |
| 判断 | 归一化 `T/F/对/错/√/×/Y/N` 后比对 |
| 填空 | 多空用 `\|` 分隔，逐空比对（忽略空白/标点/全角），漏空可给部分分 |
| 主观 | 按 `得分点` 关键词命中率给分，命中全部为满分 |

---

## 三、题库格式说明

所有格式都支持下列**字段别名**（大小写、空格、下划线、全半角都会自动归一）：

| 规范字段 | 可用表头别名 |
|---|---|
| 题型 | 题型 / 类型 / 题目类型 / type / questionType / category |
| 题干 | 题干 / 题目 / 试题 / 问题 / stem / question / title / content |
| 材料 | 材料 / 背景 / 资料 / material / passage / context |
| 选项 | 选项A…选项H / A / option_a / optionA（也支持把整列选项放一个单元格） |
| 答案 | 答案 / 正确答案 / 标准答案 / answer / correctAnswer / key |
| 解析 | 解析 / 答案解析 / 说明 / analysis / explanation / remark |
| 知识点 | 知识点 / 考点 / 标签 / knowledge / point / tag |
| 难度 | 难度 / 难度等级 / difficulty / level（支持 1/2/3 或 易/中/难） |
| 地区 | 地区 / 省份 / 城市 / region / area / province |
| 科目 | 科目 / 学科 / 分类 / 模块 / subject / category / section |
| 来源 | 来源 / 出处 / 试卷来源 / source / origin |
| 年份 | 年份 / 年度 / year |
| 分值 | 分值 / 分 / 分数 / score / points |
| 得分点 | 得分点 / 关键词 / 采分点 / keywords / keyPoints（主观题按此判分） |

### 题型写法

`单选` `多选` `不定项` `判断` `填空` `主观` —— 也接受 `single` / `multi` / `uncertain` / `judge` / `fill` / `subjective` 与 `1`~`6`。

### 答案写法

* 单选：`A`
* 多选 / 不定项：`A,C`（CSV 中含逗号时**必须用双引号包裹**：`"A,B,D"`）
* 判断：`正确` / `错误` / `T` / `F` / `A` / `B`
* 填空：多空用 `|` 分隔，如 `人民代表大会制度|全国人民代表大会`
* 主观：整段参考答案 + 单独的「得分点」列

### 示例文件

`sample/` 目录下提供同一份题库的四种格式（各 **11 题**，覆盖选择题的全部四种题型 + 2 道资料分析带表材料题）：

* `行测基础示例.json` ← **唯一数据源**
* `行测基础示例.csv`
* `行测基础示例.xlsx`
* `行测基础示例.txt`

四种格式由 `sample/make_xlsx.js` 从 JSON 单一数据源生成，改题只需改 JSON 后重跑：

```bash
node sample/make_xlsx.js
```

---

## 四、构建与运行

需要准备：

| 组件 | 版本 |
|---|---|
| JDK | 17（AGP 8.7 / Kotlin 编译器不支持 JDK 21+，会报 `IllegalArgumentException`） |
| Android SDK | platform 35 + build-tools 35.0.0 |
| Gradle | 用仓库自带 wrapper（`./gradlew`） |

```bash
# 按自己的环境设置
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> `local.properties` 里的 `sdk.dir` 由 Android Studio 自动生成，不入库。
> 如果你的机器同时装了多个 JDK，可在 `gradle.properties` 中用 `org.gradle.java.home` 固定到 17。

### 自动化测试

```bash
./gradlew connectedDebugAndroidTest
```

`app/src/androidTest/.../ImportInstrumentedTest.kt` 覆盖的用例：

| 用例 | 覆盖点 |
|---|---|
| `json_imports_all_questions_and_types` | JSON 解析 11 题、选择题四种题型齐全、选项/答案/知识点正确 |
| `csv_imports_all_questions` | CSV 表头别名映射、`"A,B,D"` 带引号多选答案 |
| `xlsx_imports_all_questions` | xlsx 解析、sharedStrings 读取 |
| `txt_imports_all_questions` | TXT 段落分题、`答案:`/`解析:`/`知识点:` 前缀识别 |
| `format_detection_works` | 四种格式的自动探测 |
| `judging_rules_work` | 单选/多选（全对、少选、错选）/判断（多种写法） |
| `material_with_table_is_parsed` | 带表格的材料被完整解析，不污染题干 |
| `txt_material_is_kept_whole` | TXT 多行材料块完整保留 |
| `all_imported_questions_are_answerable` | 所有导入题目都是选择题（当前版本范围） |

---

## 五、代码结构

```
app/src/main/java/com/lytree/qbank/
├── data/
│   ├── model/Question.kt        # 题目 / 选项 / 题型 / 记录 模型
│   ├── importer/
│   │   ├── Normalizer.kt        # 表头别名表 + 逐行规范化与校验
│   │   ├── Importer.kt          # 格式探测 + 导入调度
│   │   ├── JsonImporter.kt
│   │   ├── CsvImporter.kt       # 分隔符/编码/引号全兼容
│   │   ├── XlsxReader.kt        # 零依赖 OOXML 读取器（不引 Apache POI）
│   │   └── TxtImporter.kt       # 按题号切块 + 多行材料累积
│   └── db/QBankDb.kt            # SQLiteOpenHelper，8 张表，无注解处理器
├── engine/Judge.kt              # 判题计分 + 汇总
└── ui/
    ├── MainActivity.kt          # 4 个 Tab
    ├── ImportActivity.kt        # 选文件 → 解析 → 校验预览 → 勾选入库（支持外部 Intent 传入文件）
    ├── BankDetailActivity.kt
    ├── ComposeActivity.kt       # 组卷
    ├── ExamActivity.kt          # 答题（ViewPager2 横向切题 + 手写标注）
    ├── QuestionPagerAdapter.kt  # ViewPager2 适配器 + 圆形选项徽标三态
    ├── QuestionListAdapter.kt   # 多题连续滚动列表
    ├── SplitExamActivity.kt     # 资料分析左右分屏
    ├── InkOverlay.kt            # 手写标注层（触控笔 + 压感 + 按题持久化）
    ├── InkToolbarController.kt  # 手写工具条（撤销/清空/手指绘制/完成）
    ├── MaterialRenderer.kt      # 材料渲染，Markdown 表格 → 可横滚真表格
    ├── AnswerCardActivity.kt    # 答题卡
    ├── ReportActivity.kt        # 成绩报告
    └── settings/SettingsFragment.kt  # 判题规则
```

---

## 六、界面说明

界面布局与交互遵循刷题类应用的通行范式，全部为独立实现，不包含任何第三方应用的代码与资源。
色板与尺寸以 `res/values/colors.xml`、`dimens.xml` 中的 `fb_*` 令牌集中管理，改主题只需改这两处。

---

## 七、许可

见 [LICENSE](LICENSE)。
