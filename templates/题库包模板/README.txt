题库包模板

结构：
  qbank.json          题库主数据（题目、答案都在里面）
  images/             材料配图，qbank.json 里写 images/xxx.png 这样的相对路径
  README.txt          这个文件，不参与导入（也可以删）

怎么用：
  1. 把 qbank.json 里的题目换成你自己的；
  2. 把 images/chart-01.png 换成你自己的图（文件名任意，记得同步改 qbank.json 里的引用）；
  3. 整个文件夹压成 zip（外层多包一层文件夹也没关系）；
  4. App 里「导入」选这个 zip。

细节见 docs/03-题库包.md。
