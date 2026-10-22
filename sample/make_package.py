"""生成「题库包」示例：标准目录结构 + 打成 zip。

标准结构：
    资料分析题库包.zip
    └── 资料分析题库包/
        ├── qbank.json
        ├── images/chart-01.png
        └── README.txt
"""
import io
import json
import os
import shutil
import zipfile

ROOT = 'sample/资料分析题库包'
PKG_NAME = '资料分析题库包'

shutil.rmtree(ROOT, ignore_errors=True)
os.makedirs(os.path.join(ROOT, 'images'), exist_ok=True)

data = json.load(io.open('sample/资料分析示例.json', encoding='utf-8'))
data['bank'] = '资料分析题库包（标准结构）'
data['description'] = 'zip 题库包示例：qbank.json + images/，材料里的图片写相对路径即可'

# 图片引用换成包内相对路径
for q in data['questions']:
    q['material'] = q['material'].replace(
        'asset://samples/资料分析折线图.png', 'images/chart-01.png'
    )

io.open(os.path.join(ROOT, 'qbank.json'), 'w', encoding='utf-8', newline='\n').write(
    json.dumps(data, ensure_ascii=False, indent=2) + '\n'
)

shutil.copyfile(
    'app/src/main/assets/samples/资料分析折线图.png',
    os.path.join(ROOT, 'images', 'chart-01.png'),
)

readme = (
    '资料分析题库包 · 标准结构示例\r\n'
    '\r\n'
    '目录：\r\n'
    '  qbank.json          题库主数据（必需，也可叫 questions.json / bank.json）\r\n'
    '  images/             材料配图目录（可选，名字随意，只要和 qbank.json 里引用的相对路径一致）\r\n'
    '  README.txt          说明文件（可选，不会导入）\r\n'
    '\r\n'
    '材料里引用图片写相对路径：\r\n'
    '  ![图注](images/chart-01.png)\r\n'
    '导入后 App 会把包解压到应用私有目录，并把相对路径换成本机绝对路径。\r\n'
    '\r\n'
    '打包：把整个「资料分析题库包」文件夹压缩为 zip 即可，外层文件夹会被自动剥掉。\r\n'
)
io.open(os.path.join(ROOT, 'README.txt'), 'w', encoding='utf-8', newline='').write(readme)

# 打 zip（外层保留一层目录，模拟压缩软件的默认行为）
out = os.path.join('sample', PKG_NAME + '.zip')
if os.path.exists(out):
    os.remove(out)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for base, _, files in os.walk(ROOT):
        for f in sorted(files):
            full = os.path.join(base, f)
            rel = os.path.relpath(full, ROOT)
            z.write(full, os.path.join(PKG_NAME, rel))

assets_dir = 'app/src/main/assets/samples'
shutil.copyfile(out, os.path.join(assets_dir, PKG_NAME + '.zip'))

print('zip:', out, os.path.getsize(out), 'bytes')
with zipfile.ZipFile(out) as z:
    for i in z.infolist():
        print(' ', i.filename, i.file_size)
