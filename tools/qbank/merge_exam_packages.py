#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把 10 个单卷试卷包汇总成一个「超格10套卷」包。

App 的规则是「一个工作表 = 一份试卷」，所以汇总 = 把 10 份卷写成同一个 试卷.xlsx 里的
10 张工作表（表名即卷名），图片全放进同一个 images/。

坑：单卷包里的图都叫 `images/q076.png`、`images/q076_A.png`，10 套之间会撞名。所以
汇总时统一加卷前缀 `s01_`、`s02_`…，并把单元格里的引用一起改掉。

产物：qbank_out/超格行测套卷/超格10套卷.zip（试卷.xlsx + images/），导入后是一个标题、
下面 10 份卷。

跑法：
    uv run --with openpyxl python tools/qbank/merge_exam_packages.py
    ... --src qbank_out/超格行测套卷   --out qbank_out/超格行测套卷/超格10套卷.zip
"""
import argparse
import io
import json
import os
import re
import zipfile

import openpyxl

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SRC = os.path.join(ROOT, 'qbank_out', '超格行测套卷')
OUT = os.path.join(SRC, '超格10套卷.zip')

IMG_REF = re.compile(r'images/([^)\s"\']+)')


def paper_no(name):
    m = re.search(r'(\d+)', name)
    return int(m.group(1)) if m else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', default=SRC)
    ap.add_argument('--out', default=OUT)
    ap.add_argument('--prefix', default='超格10套卷')
    args = ap.parse_args()

    zips = [f for f in os.listdir(args.src)
            if re.fullmatch(r'行测套卷\d+\.zip', f)]
    zips.sort(key=paper_no)
    if not zips:
        raise SystemExit('没找到单卷包：%s' % args.src)
    print('汇总 %d 个单卷包：%s' % (len(zips), ', '.join(zips)))

    wb = openpyxl.Workbook()
    wb.remove(wb.active)

    images = {}          # 包内新路径 -> bytes
    summary = []
    header_ref = None
    for zname in zips:
        no = paper_no(zname)
        tag = 's%02d' % no
        sheet_name = '行测套卷%d' % no
        with zipfile.ZipFile(os.path.join(args.src, zname)) as z:
            names = set(z.namelist())
            wb_in = openpyxl.load_workbook(io.BytesIO(z.read('试卷.xlsx')))
            ws_in = wb_in.active
            header = [c.value for c in ws_in[1]]
            if header_ref is None:
                header_ref = header
            elif header != header_ref:
                print('  注意：%s 的表头与第一份不一致，按自己的表头写' % zname)

            ws = wb.create_sheet(sheet_name)
            rows, refs, missing = 0, 0, []
            for r in range(1, ws_in.max_row + 1):
                out_row = []
                for c in range(1, ws_in.max_column + 1):
                    v = ws_in.cell(r, c).value
                    if isinstance(v, str) and 'images/' in v:
                        def fix(m):
                            new = 'images/%s_%s' % (tag, m.group(1))
                            if ('images/%s' % m.group(1)) not in names:
                                missing.append(m.group(1))
                            elif new not in images:
                                images[new] = z.read('images/%s' % m.group(1))
                            return new
                        v = IMG_REF.sub(fix, v)
                        refs += len(IMG_REF.findall(ws_in.cell(r, c).value))
                    out_row.append(v)
                ws.append(out_row)
                rows += 1
            if missing:
                print('  警告：%s 引用里缺图 %s' % (zname, missing[:5]))
            summary.append({'sheet': sheet_name, 'rows': rows - 1, 'img_refs': refs})

    xlsx_buf = io.BytesIO()
    wb.save(xlsx_buf)

    if os.path.exists(args.out):
        os.remove(args.out)
    with zipfile.ZipFile(args.out, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('试卷.xlsx', xlsx_buf.getvalue())
        for name, data in sorted(images.items()):
            z.writestr(name, data)

    print('工作表：%s' % ', '.join(s['sheet'] for s in summary))
    print('题目合计：%d 行，图片引用 %d 处，图片文件 %d 张'
          % (sum(s['rows'] for s in summary), sum(s['img_refs'] for s in summary), len(images)))
    print('汇总包：%s（%.1f MB）' % (args.out, os.path.getsize(args.out) / 1048576.0))

    # 回读校验：每张表的图片引用都能在 zip 里找到
    with zipfile.ZipFile(args.out) as z:
        names = set(z.namelist())
        wb2 = openpyxl.load_workbook(io.BytesIO(z.read('试卷.xlsx')))
        bad = []
        for ws in wb2.worksheets:
            for row in ws.iter_rows(values_only=True):
                for v in row:
                    if isinstance(v, str):
                        for m in IMG_REF.findall(v):
                            if 'images/%s' % m not in names:
                                bad.append((ws.title, m))
        print('校验：工作表 %d 张，缺图引用 %d 处' % (len(wb2.worksheets), len(bad)))
        if bad:
            print('  ', bad[:5])


if __name__ == '__main__':
    main()
