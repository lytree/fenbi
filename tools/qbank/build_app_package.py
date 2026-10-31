#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把「图推700题」的 Anki 导入包（anki_import.tsv + images/）转换成当前 App
能直接导入的「题库包」，并按套拆成 35 份试卷：

    图推700题题库包/
    ├── 题库.xlsx          # 35 张工作表 = 35 份试卷（图推第01套 … 图推第35套）
    └── images/            # 700 张题图，随包走、换设备不丢图

再压成 图推700题题库包.zip，可直接在 App 导入页选入；导入预览页顶部会出现
35 个卷名胶囊，想建哪几套就勾哪几套。

背景：原始数据是 Anki 的四列 TSV（`<img src>` + 答案字母 + 题号标签 + 标签列），
一张图就是一道完整题（题干文字和 A–D 四个选项都画在图里），没有文字选项。
当前 App 的规则是：

* 一个工作表 = 一份试卷（docs/02-题库格式规范.md）——所以「按套拆卷」就是把
  每套题写成一张工作表，而不是把 700 题平铺在一张表里；
* 题干必填且不渲染 Markdown —— 题图走「图片」列（导入时转成 material 里的图片
  并前置渲染，出现在题干上方），题干只放「第几套·第几题」的定位文字；
* 只支持选择题 —— 每题补 4 个选项 A–D，文本统一写「见题图」，让答案字母有落点、
  能正常判分；真正的四个图形就在题图里，选项行只起「点字母」的作用。

运行（走 uv，避免踩到 Windows 商店那个坏掉的 python 重定向）：
    uv run python tools/qbank/build_app_package.py
    uv run python tools/qbank/build_app_package.py --zip ~/Downloads/图推700题_tsv导入包.zip
    uv run python tools/qbank/build_app_package.py --per-set      # 另外再拆 35 个单卷包
"""

import argparse
import os
import re
import shutil
import sys
import tempfile
import zipfile
import xml.sax.saxutils as sx

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))          # tools/qbank -> 仓库根
OUT = os.path.join(ROOT, "qbank_out")

DEFAULT_TSV = os.path.join(OUT, "anki_import.tsv")
DEFAULT_IMAGES = os.path.join(OUT, "images")

PKG_NAME = "图推700题题库包"
PKG_DIR = os.path.join(OUT, PKG_NAME)
PKG_ZIP = os.path.join(OUT, PKG_NAME + ".zip")
SPLIT_DIR = os.path.join(OUT, PKG_NAME + "-分卷")

SHEET_PREFIX = "图推第"          # 卷名 = 工作表名 = 「图推第01套」
SUBJECT = "行测·图形推理"        # 卷内唯一大类，归一成「图形推理」当大标题
TYPE = "单选"
SOURCE = "图推700题"
OPTION_TEXT = "见题图"           # 选项行只负责「点字母」，图形在题图里
VALID_ANSWERS = set("ABCD")

# 表头顺序：题型 / 科目 / 题号 / 题干 / 图片 / 选项A..D / 答案 / 来源
HEADERS = ["题型", "科目", "题号", "题干", "图片",
           "选项A", "选项B", "选项C", "选项D", "答案", "来源"]
OPTION_KEYS = ["A", "B", "C", "D"]

IMG_SRC = re.compile(r'<img[^>]*src=["\']([^"\']+)["\']', re.IGNORECASE)
QNAME = re.compile(r"第\s*(\d+)\s*套\s*第\s*(\d+)\s*题")
FNAME = re.compile(r"^s(\d+)_q(\d+)\.", re.IGNORECASE)


def col_letter(idx0):
    """0-based 列索引 -> Excel 列字母 (0->A)。"""
    s = ""
    n = idx0 + 1
    while n > 0:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


def esc(text):
    return sx.escape(str(text))


def sheet_name(set_no):
    return "%s%02d套" % (SHEET_PREFIX, set_no)


# ---------------------------------------------------------------- 读源数据

def parse_tsv(tsv_path):
    """Anki TSV -> [{set, qnum, image, answer}]，按套号/题号排好序。"""
    records = []
    bad = []
    with open(tsv_path, encoding="utf-8-sig") as f:
        for lineno, line in enumerate(f, 1):
            line = line.rstrip("\r\n")
            if not line.strip() or line.startswith("#"):
                continue
            cells = line.split("\t")
            if len(cells) < 2:
                bad.append((lineno, "列数不足"))
                continue
            src = IMG_SRC.search(cells[0])
            image = ""
            if src:
                image = os.path.basename(src.group(1)).strip()
            answer = cells[1].strip().upper()
            label = cells[2].strip() if len(cells) > 2 else ""

            m = QNAME.search(label)
            if m:
                set_no, qnum = int(m.group(1)), int(m.group(2))
            else:
                m = FNAME.match(image)
                if not m:
                    bad.append((lineno, "认不出套号/题号：%s" % label))
                    continue
                set_no, qnum = int(m.group(1)), int(m.group(2))

            if not image:
                bad.append((lineno, "行内没有 <img src>"))
                continue
            if answer not in VALID_ANSWERS:
                bad.append((lineno, "答案不是 A-D：%r" % answer))
                answer = ""

            records.append({"set": set_no, "qnum": qnum,
                            "image": image, "answer": answer})

    records.sort(key=lambda r: (r["set"], r["qnum"]))
    if bad:
        print("跳过 %d 行（不影响其余题目）：" % len(bad))
        for lineno, why in bad[:10]:
            print("    第 %d 行：%s" % (lineno, why))
    if not records:
        raise SystemExit("TSV 里一条题都没解析出来：%s" % tsv_path)
    return records


def group_sets(records):
    """按套号分组，顺手检查「一套必须是连续题号 1..N」。"""
    sets = {}
    for r in records:
        sets.setdefault(r["set"], []).append(r)
    problems = []
    for set_no in sorted(sets):
        rows = sets[set_no]
        nums = [r["qnum"] for r in rows]
        if nums != list(range(1, len(nums) + 1)):
            problems.append("第 %02d 套题号不连续：%s" % (set_no, nums[:30]))
    return [sets[k] for k in sorted(sets)], problems


# ---------------------------------------------------------------- 造 xlsx

def sheet_xml(rows):
    """rows: list[list[str]]，第一行表头。inlineStr，不依赖 sharedStrings。"""
    out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>']
    out.append('<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">')
    out.append('<sheetData>')
    for ri, row in enumerate(rows, start=1):
        cells = []
        for ci, val in enumerate(row):
            ref = "%s%d" % (col_letter(ci), ri)
            if val == "" or val is None:
                cells.append('<c r="%s"/>' % ref)
            else:
                cells.append(
                    '<c r="%s" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c>'
                    % (ref, esc(val))
                )
        out.append('<row r="%d">%s</row>' % (ri, "".join(cells)))
    out.append('</sheetData>')
    out.append('</worksheet>')
    return "".join(out)


def build_xlsx(sheets):
    """sheets: [(卷名, rows)]，一张表一份试卷。返回 {zip 内路径: 文本}。"""
    n = len(sheets)
    overrides = "".join(
        '<Override PartName="/xl/worksheets/sheet%d.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        % (i + 1) for i in range(n)
    )
    content_types = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        + overrides + '</Types>'
    )
    rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
        'Target="xl/workbook.xml"/></Relationships>'
    )
    sheet_tags = "".join(
        '<sheet name="%s" sheetId="%d" r:id="rId%d"/>' % (esc(name), i + 1, i + 1)
        for i, (name, _) in enumerate(sheets)
    )
    workbook = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        '<sheets>' + sheet_tags + '</sheets></workbook>'
    )
    wb_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + "".join(
            '<Relationship Id="rId%d" '
            'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
            'Target="worksheets/sheet%d.xml"/>' % (i + 1, i + 1) for i in range(n)
        ) + '</Relationships>'
    )
    files = {
        "[Content_Types].xml": content_types,
        "_rels/.rels": rels,
        "xl/workbook.xml": workbook,
        "xl/_rels/workbook.xml.rels": wb_rels,
    }
    for i, (_, rows) in enumerate(sheets):
        files["xl/worksheets/sheet%d.xml" % (i + 1)] = sheet_xml(rows)
    return files


def rows_of_set(records):
    """一套题的表格行：表头 + 每题一行，题号在卷内 1..N 连续。"""
    rows = [list(HEADERS)]
    set_no = records[0]["set"]
    for i, q in enumerate(records, start=1):
        # 题干只放定位文字：图形推理这类大标题由「科目」列决定，题图走「图片」列
        stem = "%s·第%d题" % (sheet_name(set_no), q["qnum"])
        rows.append([
            TYPE,                                   # 题型
            SUBJECT,                                # 科目（决定卷内大类）
            i,                                      # 题号（卷内连续）
            stem,                                   # 题干（纯文本定位，App 不渲染 Markdown）
            "images/%s" % q["image"],               # 图片（包内相对路径）
            OPTION_TEXT, OPTION_TEXT, OPTION_TEXT, OPTION_TEXT,   # 选项A..D
            q["answer"] or "",                      # 答案
            SOURCE,                                 # 来源
        ])
    return rows


# ---------------------------------------------------------------- 主流程

def extract_package(zip_path):
    """从 Anki 导入包 zip 里取出 tsv 和 images，解到临时目录。"""
    tmp = tempfile.mkdtemp(prefix="tuitui_src_")
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            name = info.filename.replace("\\", "/")
            if name.lower().endswith((".tsv", ".txt")):
                target = os.path.join(tmp, os.path.basename(name))
            elif "/images/" in "/" + name or name.lower().endswith(".png"):
                base = os.path.basename(name)
                target = os.path.join(tmp, "images", base)
                if not base.lower().endswith(".png"):
                    continue
            else:
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with z.open(info) as src, open(target, "wb") as dst:
                shutil.copyfileobj(src, dst)
    tsv = os.path.join(tmp, "anki_import.tsv")
    if not os.path.exists(tsv):
        cands = [f for f in os.listdir(tmp) if f.lower().endswith(".tsv")]
        if not cands:
            raise SystemExit("压缩包里没找到 tsv：%s" % zip_path)
        tsv = os.path.join(tmp, cands[0])
    return tmp, tsv, os.path.join(tmp, "images")


def check_images(records, img_dir):
    missing = [r["image"] for r in records
               if not os.path.exists(os.path.join(img_dir, r["image"]))]
    return missing


def write_package(sheets, records, img_dir):
    """写包目录 + 主 zip；返回 (目录, zip 路径)。"""
    if os.path.exists(PKG_DIR):
        shutil.rmtree(PKG_DIR)
    os.makedirs(os.path.join(PKG_DIR, "images"))

    xlsx_path = os.path.join(PKG_DIR, "题库.xlsx")
    with zipfile.ZipFile(xlsx_path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in build_xlsx(sheets).items():
            z.writestr(name, data.encode("utf-8"))

    for r in records:
        shutil.copy2(os.path.join(img_dir, r["image"]),
                     os.path.join(PKG_DIR, "images", r["image"]))

    if os.path.exists(PKG_ZIP):
        os.remove(PKG_ZIP)
    with zipfile.ZipFile(PKG_ZIP, "w") as z:
        z.write(xlsx_path, "%s/题库.xlsx" % PKG_NAME, compress_type=zipfile.ZIP_DEFLATED)
        for r in records:
            z.write(os.path.join(img_dir, r["image"]),
                    "%s/images/%s" % (PKG_NAME, r["image"]),
                    compress_type=zipfile.ZIP_STORED)
    return PKG_DIR, PKG_ZIP


def write_split_packages(sets, img_dir):
    """可选：每套单独一个 zip（只想练某一套时用）。"""
    if os.path.exists(SPLIT_DIR):
        shutil.rmtree(SPLIT_DIR)
    os.makedirs(SPLIT_DIR)
    made = []
    for recs in sets:
        name = sheet_name(recs[0]["set"])
        tsv_dir = os.path.join(SPLIT_DIR, name)
        os.makedirs(os.path.join(tsv_dir, "images"))
        with zipfile.ZipFile(os.path.join(tsv_dir, "题库.xlsx"), "w",
                             zipfile.ZIP_DEFLATED) as z:
            for fname, data in build_xlsx([(name, rows_of_set(recs))]).items():
                z.writestr(fname, data.encode("utf-8"))
        for r in recs:
            shutil.copy2(os.path.join(img_dir, r["image"]),
                         os.path.join(tsv_dir, "images", r["image"]))
        zpath = os.path.join(SPLIT_DIR, name + ".zip")
        with zipfile.ZipFile(zpath, "w") as z:
            z.write(os.path.join(tsv_dir, "题库.xlsx"), "%s/题库.xlsx" % name,
                    compress_type=zipfile.ZIP_DEFLATED)
            for r in recs:
                z.write(os.path.join(img_dir, r["image"]),
                        "%s/images/%s" % (name, r["image"]),
                        compress_type=zipfile.ZIP_STORED)
        shutil.rmtree(tsv_dir)
        made.append(zpath)
    return made


def main():
    ap = argparse.ArgumentParser(description="图推700题 → App 题库包（按套拆 35 卷）")
    ap.add_argument("--zip", help="原始 Anki 导入包（含 tsv + images），给了就从中取源")
    ap.add_argument("--tsv", default=DEFAULT_TSV, help="anki TSV 路径")
    ap.add_argument("--images", default=DEFAULT_IMAGES, help="题图目录")
    ap.add_argument("--per-set", action="store_true", help="另外再拆成 35 个单卷小包")
    args = ap.parse_args()

    tmp = None
    tsv_path, img_dir = args.tsv, args.images
    if args.zip:
        tmp, tsv_path, img_dir = extract_package(args.zip)
        print("从压缩包取源：%s" % args.zip)
    if not os.path.exists(tsv_path):
        raise SystemExit("找不到 TSV：%s（先跑 OCR/切片流程，或 --zip 指定原始包）" % tsv_path)
    if not os.path.isdir(img_dir):
        raise SystemExit("找不到题图目录：%s" % img_dir)

    records = parse_tsv(tsv_path)
    sets, problems = group_sets(records)
    if problems:
        print("警告：")
        for p in problems:
            print("   ", p)

    missing = check_images(records, img_dir)
    if missing:
        raise SystemExit("缺 %d 张题图（例：%s），先补齐再打包" % (len(missing), missing[:5]))

    sheets = [(sheet_name(recs[0]["set"]), rows_of_set(recs)) for recs in sets]
    names = [n for n, _ in sheets]
    assert len(set(names)) == len(names), "卷名重复：%s" % names
    assert all(len(n) <= 31 for n in names), "工作表名超过 31 字符"

    pkg_dir, pkg_zip = write_package(sheets, records, img_dir)

    print("题图：%d 张" % len(records))
    print("试卷：%d 套（每套 %s 题）%s"
          % (len(sheets), sorted({len(r) - 1 for _, r in sheets}),
             "—" if len({len(r) for _, r in sheets}) == 1 else "（题量不一致，注意）"))
    print("答案分布：%s" % " ".join(
        "%s=%d" % (k, sum(1 for r in records if r["answer"] == k)) for k in "ABCD"))
    print("表名：%s … %s" % (names[0], names[-1]))
    print("包目录：%s" % pkg_dir)
    print("导入包：%s（%.1f MB）" % (pkg_zip, os.path.getsize(pkg_zip) / 1048576.0))

    if args.per_set:
        made = write_split_packages(sets, img_dir)
        print("单卷小包：%d 个 -> %s" % (len(made), SPLIT_DIR))

    if tmp:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
