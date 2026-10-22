资料分析题库包 · 标准结构示例

目录：
  qbank.json          题库主数据（必需，也可叫 questions.json / bank.json）
  images/             材料配图目录（可选，名字随意，只要和 qbank.json 里引用的相对路径一致）
  README.txt          说明文件（可选，不会导入）

材料里引用图片写相对路径：
  ![图注](images/chart-01.png)
导入后 App 会把包解压到应用私有目录，并把相对路径换成本机绝对路径。

打包：把整个「资料分析题库包」文件夹压缩为 zip 即可，外层文件夹会被自动剥掉。
