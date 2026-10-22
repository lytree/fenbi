# 自定义题库刷题 App

[![CI](https://github.com/lytree/fenbi/actions/workflows/ci.yml/badge.svg)](https://github.com/lytree/fenbi/actions/workflows/ci.yml)
[![Release](https://github.com/lytree/fenbi/actions/workflows/release.yml/badge.svg)](https://github.com/lytree/fenbi/actions/workflows/release.yml)

一个可以**导入自己的题库**并刷题的 Android 应用。支持 `JSON` / `CSV` / `Excel(.xlsx)` / `TXT` / `ZIP 题库包`
五种导入格式，自动探测格式与编码。**一个导入文件就是一份试卷**，导入后直接开练：限时答题、手写标注、查看成绩报告。

![导入预览](docs/images/screenshots/01-导入预览.png)
![答题页](docs/images/screenshots/02-答题页.png)

---

## 功能

**导入**

* 五种格式：`JSON` / `CSV` / `Excel(.xlsx)` / `TXT` / `ZIP 题库包`，自动探测格式、自动识别编码（UTF-8 / GBK）
* 表头别名自动映射（`题干` / `题目` / `stem` / `question` … 都认），全半角、空格、下划线一并归一
* 导入校验：逐行规范化 + 错误/警告清单，定位到行号，勾选后再生成试卷
* **答案可后补**：缺答案的题按「待补答案」正常导入，做完再导一份答案文件，按已有作答直接重判
* **题库包**：`qbank.json` + `images/` 打成 zip，材料配图随包走，换设备不丢图

**答题**

* 单题 / 多题双模式：一屏一题（左右滑动）↔ 连续滚动列表，切换时按当前题双向定位
* 计时交卷：倒计时、剩 5 分钟提醒、超时自动交卷、未答题二次确认
* 夜间模式：答题流程全套换色，偏好持久化
* 标记、笔记、答题卡、字号调节

**标注**

* 题目遮盖层：手写 / 触控笔直接写，压感笔迹，写字时不会误点选项、不会翻页
* 四档遮盖（关 / 浅 / 中 / 深），贴屏幕右边缘拖动可滚动题目，笔迹跟着文字走
* 资料分析左右分屏：左材料右题目，两栏都能标注

**材料**

* Markdown 表格渲染成真表格，可横向滚动
* 配图支持本地路径 / `file://` / `content://` / `asset://` / base64，点一下整屏看大图
* 没有配图时可用 `:::chart` 写数据让 App 自绘折线 / 柱状图
* 「弹出材料」放大到 86% 屏独立滚动，A- / A+ 调字号

**复盘**

* 成绩报告：得分 / 正确率 / 用时 / 逐题解析，四态标色（全对 / 部分得分 / 错误 / 未答）
* 判题规则可配：多选少选给分、不定项少选 50%、填空部分给分、主观关键词命中给分

> 当前版本答题流程只完整支持**选择题**（单选 / 多选 / 不定项 / 判断）。填空、主观题在数据层保留，
> 导入时会被拒绝或降级，详见 [格式规范](docs/02-题库格式规范.md#只支持选择题的原因)。

---

## 三步导入

1. 「题库」或「试卷」页 → 右上角「导入」
2. 选文件（或点「载入示例题库」先跑一遍）
3. 看预览里的统计与提示 → 勾选题目 → 导入，直接生成一份试卷

手边没有现成题库：下载 [templates/](templates/) 里的模板照着填。

| 格式 | 适合 | 模板 |
|---|---|---|
| `.xlsx` | 已经在 Excel / WPS 里整理 | [题库模板.xlsx](templates/题库模板.xlsx) |
| `.csv` | 脚本生成、要 diff | [题库模板.csv](templates/题库模板.csv) |
| `.json` | 字段最多、程序生成 | [题库模板.json](templates/题库模板.json) |
| `.txt` | 纸质卷子手打 | [题库模板.txt](templates/题库模板.txt) |
| `.zip` | 材料带图 | [题库包模板.zip](templates/题库包模板.zip) |

---

## 文档

| 文档 | 内容 |
|---|---|
| [docs/README](docs/README.md) | 文档索引与三条阅读路径 |
| [01 快速开始](docs/01-快速开始.md) | 安装、导入、答题、交卷、看成绩 |
| [02 题库格式规范](docs/02-题库格式规范.md) | 字段别名、题型、答案写法、四种格式写法、材料/图片/图表、校验提示对照 |
| [03 题库包](docs/03-题库包.md) | zip 结构、主数据命名、图片相对路径、打包 |
| [04 答案后补](docs/04-答案后补.md) | 先导入没答案的卷子，做完再补答案重判 |
| [05 界面与交互](docs/05-界面与交互.md) | 版式令牌、答题页、夜间、遮盖层写画、分屏 |
| [06 构建与测试](docs/06-构建与测试.md) | JDK/SDK、打包命令、仪器测试用例 |
| [07 代码结构](docs/07-代码结构.md) | 包结构、导入链路、数据库表 |

---

## 目录

```
app/            Android 工程（Kotlin + 原生 View + ViewBinding）
docs/           文档与配图（images/）
sample/         有内容的完整示例（11 题四种同源格式、资料分析题库包）
templates/      可直接导入的空模板 + 生成脚本
```

`sample/` 与 `templates/` 都由一份 JSON 单一数据源生成：

```bash
node sample/make_xlsx.js        # 改 sample/行测基础示例.json 后重跑，生成 csv / txt / xlsx
node templates/make_templates.js # 重新生成模板并回读校验
python sample/make_package.py   # 生成题库包目录与 zip
```

---

## 构建

需要 JDK 17、Android SDK platform 35；用仓库自带 wrapper：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> `gradle.properties` 里的 `org.gradle.java.home` 是作者本机路径，换机器记得改。
> 环境细节与测试见 [06 构建与测试](docs/06-构建与测试.md)。

CI 用 GitHub Actions 跑：推送 `main` 或提 PR 自动编译 debug 包（产物在 Actions 里下载）；
推送 `v*` tag 自动出 release 包并发 Release，签名密钥从 Secrets 读，没配就用 debug 签名。

```bash
git tag v1.0.1 && git push origin v1.0.1   # 触发发版构建
```

细节见 [06 构建与测试 § CI](docs/06-构建与测试.md#ci-github-actions)。

---

## 技术栈

Kotlin · 原生 View + ViewBinding（无 Compose、无第三方 UI 库）· `SQLiteOpenHelper`（9 张表，无注解处理器）·
compileSdk / targetSdk 35 · minSdk 24 · AGP 8.7 + Gradle 8.11 · xlsx 解析为自研零依赖 OOXML 读取器（不引 Apache POI）

---

## 许可

见 [LICENSE](LICENSE)。
