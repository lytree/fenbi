#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把旧包（qbank_out/超格行测套卷）里已人工校验/重绘的 images 合并进新包，重建 zip。

用法：
    python tools/qbank/merge_old_images.py 行测套卷1 [行测套卷2 ...]
    python tools/qbank/merge_old_images.py --all

策略：
  1. 旧图优先：同名文件用旧包覆盖新包（旧图经过图推自绘重绘与人工选项框标定）；
  2. 新包里独有、旧包没有的图保持不动；
  3. 重建 zip 前校验 xlsx 里每一处 images/xxx 引用都存在，缺失就报出来并失败；
  4. 顺带校验：题数、空答案、选项数、题干空值。
"""
import os
import re
import shutil
import sys
import zipfile

import openpyxl

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
NEW = os.path.join(ROOT, 'tmp', 'build', 'deliver_v3')
OLD = os.path.join(ROOT, 'qbank_out', '超格行测套卷')
REF_RE = re.compile(r'!\[[^\]]*\]\(images/([^)]+)\)')

def is_fragment(stem_path, opt_paths):
    """题干图是不是拆图失败裁出的残片（细长条/远小于选项图）。"""
    try:
        from PIL import Image
        sw, sh = Image.open(stem_path).size
        dims = [Image.open(q).size for q in opt_paths if os.path.exists(q)]
        if not dims:
            return False
        mw = sum(d[0] for d in dims) / len(dims)
        mh = sum(d[1] for d in dims) / len(dims)
        if mw <= 0 or mh <= 0:
            return False
        return (sw < 0.4 * mw) and (sw * sh < 0.35 * mw * mh)
    except Exception:
        return False



def ink_ratio(path):
    """图片墨迹占比；读不出来就当作非空。"""
    try:
        from PIL import Image
        import numpy as np
        a = np.asarray(Image.open(path).convert('L'))
        return float((a < 128).mean())
    except Exception:
        return 1.0


def merge(paper):
    nimg_dir = os.path.join(NEW, paper, 'images')
    oimg_dir = os.path.join(OLD, paper, 'images')
    xlsx = os.path.join(NEW, paper, '试卷.xlsx')
    if not os.path.exists(xlsx):
        return {'paper': paper, 'error': 'no xlsx'}

    copied = 0
    if os.path.isdir(oimg_dir):
        os.makedirs(nimg_dir, exist_ok=True)
        for fn in os.listdir(oimg_dir):
            shutil.copyfile(os.path.join(oimg_dir, fn), os.path.join(nimg_dir, fn))
            copied += 1

    # 1) 继承旧包已拆好的选项图（新包拆不动、旧包有 qNNN_A~D 的题）；
    # 2) 补齐题干图：选项是 images/qNNN_X.png 的拆图题，若材料里没引 qNNN.png 而包里有这张图，补回去
    wb = openpyxl.load_workbook(xlsx)
    ws = wb.worksheets[0]
    stem_fixed, split_inherited, blank_stem = [], [], []
    for idx, row in enumerate(ws.iter_rows(min_row=2), start=1):
        opt_cells = row[4:8]
        opt_imgs = [str(c.value or '') for c in opt_cells]
        if (opt_imgs and not any(v.startswith('![') for v in opt_imgs)
                and all('见图' in v for v in opt_imgs)
                and all(os.path.exists(os.path.join(nimg_dir, 'q%03d_%s.png' % (idx, k))) for k in 'ABCD')):
            for c, k in zip(opt_cells, 'ABCD'):
                c.value = '![%s](images/q%03d_%s.png)' % (k, idx, k)
            split_inherited.append(idx)
            opt_imgs = [str(c.value) for c in opt_cells]

        m = None
        for v in opt_imgs:
            mm = re.search(r'images/(q\d{3})_[A-D]\.png', v)
            if mm:
                m = mm.group(1)
                break
        if not m:
            continue
        stem_img = 'images/%s.png' % m
        mat_cell = row[2]
        mat = str(mat_cell.value or '')
        stem_path = os.path.join(nimg_dir, '%s.png' % m)
        # 题干图是空白（拆图失败时裁出的残片）→ 删掉这条引用，别让 App 显示一张空白图
        opt_paths = [os.path.join(nimg_dir, 'q%03d_%s.png' % (idx, k)) for k in 'ABCD']
        if stem_img in mat and (ink_ratio(stem_path) < 0.01 or is_fragment(stem_path, opt_paths)):
            mat_cell.value = re.sub(r'!\[[^\]]*\]\(%s\)\n*' % re.escape(stem_img), '', mat).strip()
            blank_stem.append(idx)
            continue
        if os.path.exists(stem_path) and stem_img not in mat:
            mat_cell.value = ('![题干图形](%s)\n\n%s' % (stem_img, mat)).strip()
            stem_fixed.append(row[0].row)
    if stem_fixed or split_inherited or blank_stem:
        wb.save(xlsx)
    wb.close()

    wb = openpyxl.load_workbook(xlsx, read_only=True)
    ws = wb.worksheets[0]
    rows = list(ws.iter_rows(min_row=2, values_only=True))
    wb.close()

    refs, missing, empty_stem, no_ans, bad_opt = [], [], [], [], []
    for r in rows:
        stem = (r[1] or '').strip()
        mat = (r[2] or '')
        opts = [r[4], r[5], r[6], r[7]]
        no = len(refs) + 1
        if not stem:
            empty_stem.append(no)
        if len([o for o in opts if o and str(o).strip()]) < 4:
            bad_opt.append(no)
        if not (r[8] or '').strip():
            no_ans.append(no)
        for m in REF_RE.findall(stem + '\n' + mat + '\n' + '\n'.join(str(o or '') for o in opts)):
            refs.append(m)
            if not os.path.exists(os.path.join(nimg_dir, m)):
                missing.append(m)

    zpath = os.path.join(NEW, '%s.zip' % paper)
    if os.path.exists(zpath):
        os.remove(zpath)
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
        z.write(xlsx, '试卷.xlsx')
        used = set(refs)
        for fn in sorted(os.listdir(nimg_dir)):
            if fn in used:
                z.write(os.path.join(nimg_dir, fn), 'images/%s' % fn)

    return {
        'paper': paper, 'questions': len(rows), 'old_imgs_copied': copied,
        'imgs_in_pkg': len(os.listdir(nimg_dir)), 'refs': len(refs),
        'missing': sorted(set(missing)), 'empty_stem': empty_stem,
        'no_ans': no_ans, 'bad_opt': bad_opt, 'stem_fixed': stem_fixed,
        'split_inherited': split_inherited, 'blank_stem': blank_stem,
        'zip_mb': round(os.path.getsize(zpath) / 1e6, 2),
    }


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if '--all' in sys.argv:
        args = sorted([d for d in os.listdir(NEW) if os.path.isdir(os.path.join(NEW, d))],
                      key=lambda s: int(re.search(r'(\d+)', s).group(1)))
    bad = 0
    for paper in args:
        r = merge(paper)
        print(('%-10s 题%-4s 旧图%-3s 包内图%-3s 引用%-3s 继承拆图%s 删空白题干图%s 补题干图%s 缺图%s 空题干%s 无答案%s 选项不全%s zip=%sMB'
               % (r['paper'], r.get('questions'), r.get('old_imgs_copied'), r.get('imgs_in_pkg'),
                  r.get('refs'), r.get('split_inherited'), r.get('blank_stem'), len(r.get('stem_fixed') or []),
                  r.get('missing'), r.get('empty_stem'), r.get('no_ans'),
                  r.get('bad_opt'), r.get('zip_mb'))))
        if r.get('missing') or r.get('empty_stem') or r.get('bad_opt') or r.get('error'):
            bad += 1
    print('异常卷数：', bad)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
