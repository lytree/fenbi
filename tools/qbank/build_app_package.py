#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把「图推700题」的 Anki 导入包（anki_import.tsv + images/）转换成当前 App
能直接导入的「试卷包」，并按套拆成 35 份试卷：

    图推700题试卷包/
    ├── 试卷.xlsx          # 35 张工作表 = 35 份试卷（图推第01套 … 图推第35套）
    └── images/            # 每题 5 张：题干图 + A/B/C/D 四张选项图（共 3500 张）

再压成 图推700题试卷包.zip，可直接在 App 导入页选入；导入预览页顶部会出现
35 个卷名胶囊，想建哪几套就勾哪几套。

背景：原始数据是 Anki 的四列 TSV（`<img src>` + 答案字母 + 题号标签 + 标签列），
一张图就是一道完整题（题干图形和 A–D 四个选项都画在同一张图里），没有文字选项。
这里用 `split_options.py` 把整图**拆成「题干区 + 4 张选项图」**：
题干图裁掉底部选项后写进「图片」列；四个选项图分别写进「选项A..D」列
（`![选项](images/xxx_A.png)`，App 的选项单元格支持图片，会和字母一起渲染）。

当前 App 的规则是：

* 一个工作表 = 一份试卷（docs/02-试卷格式规范.md）——所以「按套拆卷」就是把
  每套题写成一张工作表，而不是把 700 题平铺在一张表里；
* 题干必填且不渲染 Markdown —— 题图走「图片」列（导入时转成 material 里的图片
  并前置渲染，出现在题干上方），题干只放「第几套·第几题」的定位文字；
* 选项列里的 `![选项](相对路径)` 由导入器统一改写为解压后的绝对路径，四个选项
  各自显示自己的图形，不再出现「见题图」。

运行（走 uv，避免踩到 Windows 商店那个坏掉的 python 重定向；拆图需要 numpy+pillow）：
    uv run --with numpy --with pillow python tools/qbank/build_app_package.py
    uv run --with numpy --with pillow python tools/qbank/build_app_package.py --zip ~/Downloads/图推700题_tsv导入包.zip
    ... --emit-dir      # 同时把解开的 试卷.xlsx + images/ 落到包目录
    ... --per-set       # 另外再拆 35 个单卷包
"""

import argparse
import os
import re
import shutil
import sys
import tempfile
import zipfile
import xml.sax.saxutils as sx
from io import BytesIO

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))          # tools/qbank -> 仓库根
OUT = os.path.join(ROOT, "qbank_out")

DEFAULT_TSV = os.path.join(OUT, "anki_import.tsv")
DEFAULT_IMAGES = os.path.join(OUT, "images")

PKG_NAME = "图推700题试卷包"
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


def opt_base(image):
    return image[:-4] if image.lower().endswith(".png") else image


def option_image_refs(image):
    """某题的四个选项图在包内的相对路径（images/xxx_A.png …）。"""
    base = opt_base(image)
    return ["images/%s_%s.png" % (base, k) for k in "ABCD"]


def rows_of_set(records):
    """一套题的表格行：表头 + 每题一行，题号在卷内 1..N 连续。"""
    rows = [list(HEADERS)]
    set_no = records[0]["set"]
    for i, q in enumerate(records, start=1):
        # 题干只放定位文字：图形推理这类大标题由「科目」列决定，题图走「图片」列
        stem = "%s·第%d题" % (sheet_name(set_no), q["qnum"])
        opts = option_image_refs(q["image"])
        rows.append([
            TYPE,                                   # 题型
            SUBJECT,                                # 科目（决定卷内大类）
            i,                                      # 题号（卷内连续）
            stem,                                   # 题干（纯文本定位，App 不渲染 Markdown）
            "images/%s" % q["image"],               # 图片（题干区，已裁掉选项）
            "![选项](%s)" % opts[0],                # 选项A（图）
            "![选项](%s)" % opts[1],                # 选项B（图）
            "![选项](%s)" % opts[2],                # 选项C（图）
            "![选项](%s)" % opts[3],                # 选项D（图）
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


def _png_bytes(im):
    """存 PNG。本来就是黑白扫描件的转成灰度存（体积能小一半以上），
    真有彩色内容的（彩色像素占比不低）保持 RGB。"""
    import numpy as np
    a = np.asarray(im.convert("RGB"))
    if a.size:
        spread = a.max(axis=2).astype(np.int16) - a.min(axis=2).astype(np.int16)
        if float((spread > 24).mean()) < 0.01:
            im = im.convert("L")
    buf = BytesIO()
    im.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


# 选项图最小边长：原卷里「A. 1」这种文字选项只有 14px 高，App 最多把图放大到原图的
# 2 倍（MaterialRenderer.UPSCALE_CAP），不放大就会在格子里小到看不清。
# 这里把过小的选项图按整数倍插值放大（内容是原像素，不改动任何信息）。
OPT_MIN_H = 48          # 放大后的目标高度
OPT_TRIGGER_H = 36      # 只有比这更矮的才放大（文字选项 / 极小的图）
OPT_UPSCALE_MAX = 4


def _opt_png(im, box):
    """裁出选项；过矮的（文字选项）按整数倍放大，免得在格子里看不清。

    只放大「确实太小」的，避免整包体积暴涨——正常图形的选项本来就够大。
    """
    from PIL import Image
    import math
    crop = im.crop((box[0], box[1], box[2], box[3]))
    if 0 < crop.height < OPT_TRIGGER_H:
        factor = min(OPT_UPSCALE_MAX,
                     max(2, int(math.ceil(OPT_MIN_H / float(crop.height)))))
        crop = crop.resize((crop.width * factor, crop.height * factor),
                           Image.LANCZOS)
    return _png_bytes(crop)


def _xlsx_bytes(sheets):
    buf = BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in build_xlsx(sheets).items():
            z.writestr(name, data.encode("utf-8"))
    return buf.getvalue()


MANUAL_RECTS = os.path.join(HERE, "manual_rects.json")


def _manual_rects():
    """手工坐标（少数版式几何规则识别不了，见 manual_rects.json 的说明）。"""
    import json
    if not os.path.exists(MANUAL_RECTS):
        return {}
    with open(MANUAL_RECTS, encoding="utf-8") as fh:
        data = json.load(fh)
    return {k: v for k, v in data.items() if not k.startswith("_")}


def _question_images(src, image, so, manual=None):
    """把一道题拆成 {文件名: png_bytes}（题干 + 4 选项）。返回 (dict, ok)。"""
    from PIL import Image
    base = opt_base(image)
    items = {}

    ov = (manual or {}).get(os.path.splitext(image)[0])
    if ov:
        from PIL import ImageDraw
        im = Image.open(src).convert("RGB")
        sx0, sy0, sx1, sy1 = ov["stem_box"]
        stem = im.crop((sx0, sy0, sx1, sy1))
        # 把选项列从题干图里涂白：题目和选项彻底分开，且题干不漏内容
        d = ImageDraw.Draw(stem)
        for wx0, wy0, wx1, wy1 in ov.get("whiteout", []):
            d.rectangle((wx0 - sx0, wy0 - sy0, wx1 - sx0, wy1 - sy0),
                        fill=(255, 255, 255))
        items[image] = _png_bytes(stem)
        for k, box in zip("ABCD", ov["options"]):
            items["%s_%s.png" % (base, k)] = _opt_png(im, box)
        return items, True

    try:
        res = so.split_question(src)
        im = Image.open(src).convert("RGB")
        sx0, sy0, sx1, sy1 = res["stem_box"]
        items[image] = _png_bytes(im.crop((sx0, sy0, sx1, sy1)))
        for k, box in zip("ABCD", res["options"]):
            items["%s_%s.png" % (base, k)] = _opt_png(im, box)
        return items, True
    except Exception as e:                       # 兜底也要出 4 张选项图
        print("  拆分失败、整图兜底：%s（%s）" % (image, e))
        im = Image.open(src).convert("RGB")
        w, h = im.size
        items[image] = _png_bytes(im)
        top = int(h * 0.55)
        cw = w // 4
        for i, k in enumerate("ABCD"):
            items["%s_%s.png" % (base, k)] = _png_bytes(
                im.crop((i * cw, top, (i + 1) * cw, h)))
        return items, False


def write_package(sheets, records, img_dir, emit_dir=False):
    """直接生成主 zip；emit_dir=True 时同时把解开的文件写到 PKG_DIR（覆盖写、不删目录）。
    返回 (目录或 None, zip 路径)。"""
    import split_options as so

    if os.path.exists(PKG_ZIP):
        os.remove(PKG_ZIP)
    out_images = os.path.join(PKG_DIR, "images")
    if emit_dir:
        os.makedirs(out_images, exist_ok=True)

    xlsx_bytes = _xlsx_bytes(sheets)
    if emit_dir:
        with open(os.path.join(PKG_DIR, "试卷.xlsx"), "wb") as fh:
            fh.write(xlsx_bytes)

    print("拆分题干/选项图（%d 题）…" % len(records))
    manual = _manual_rects()
    ok = 0
    with zipfile.ZipFile(PKG_ZIP, "w") as z:
        z.writestr("%s/试卷.xlsx" % PKG_NAME, xlsx_bytes,
                   compress_type=zipfile.ZIP_DEFLATED)
        for r in records:
            src = os.path.join(img_dir, r["image"])
            items, good = _question_images(src, r["image"], so, manual)
            for fname, data in items.items():
                z.writestr("%s/images/%s" % (PKG_NAME, fname), data,
                           compress_type=zipfile.ZIP_STORED)
                if emit_dir:
                    with open(os.path.join(out_images, fname), "wb") as fh:
                        fh.write(data)
            if good:
                ok += 1
    print("  拆分完成：%d / %d 题" % (ok, len(records)))
    return (PKG_DIR if emit_dir else None), PKG_ZIP


def write_split_packages(sets, img_dir):
    """可选：每套单独一个 zip（只想练某一套时用）。"""
    import split_options as so

    os.makedirs(SPLIT_DIR, exist_ok=True)
    manual = _manual_rects()
    made = []
    for recs in sets:
        name = sheet_name(recs[0]["set"])
        zpath = os.path.join(SPLIT_DIR, name + ".zip")
        with zipfile.ZipFile(zpath, "w") as z:
            z.writestr("%s/试卷.xlsx" % name,
                       _xlsx_bytes([(name, rows_of_set(recs))]),
                       compress_type=zipfile.ZIP_DEFLATED)
            for r in recs:
                items, _ = _question_images(os.path.join(img_dir, r["image"]),
                                            r["image"], so, manual)
                for fname, data in items.items():
                    z.writestr("%s/images/%s" % (name, fname), data,
                               compress_type=zipfile.ZIP_STORED)
        made.append(zpath)
    return made


def main():
    ap = argparse.ArgumentParser(description="图推700题 → App 试卷包（按套拆 35 卷）")
    ap.add_argument("--zip", help="原始 Anki 导入包（含 tsv + images），给了就从中取源")
    ap.add_argument("--tsv", default=DEFAULT_TSV, help="anki TSV 路径")
    ap.add_argument("--images", default=DEFAULT_IMAGES, help="题图目录")
    ap.add_argument("--per-set", action="store_true", help="另外再拆成 35 个单卷小包")
    ap.add_argument("--emit-dir", action="store_true",
                    help="同时把解开的 试卷.xlsx + images/ 写到包目录（覆盖写，不删旧目录）")
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

    pkg_dir, pkg_zip = write_package(sheets, records, img_dir, emit_dir=args.emit_dir)

    print("题图：%d 张" % len(records))
    print("试卷：%d 套（每套 %s 题）%s"
          % (len(sheets), sorted({len(r) - 1 for _, r in sheets}),
             "—" if len({len(r) for _, r in sheets}) == 1 else "（题量不一致，注意）"))
    print("答案分布：%s" % " ".join(
        "%s=%d" % (k, sum(1 for r in records if r["answer"] == k)) for k in "ABCD"))
    print("表名：%s … %s" % (names[0], names[-1]))
    if pkg_dir:
        print("包目录：%s" % pkg_dir)
    print("导入包：%s（%.1f MB）" % (pkg_zip, os.path.getsize(pkg_zip) / 1048576.0))

    if args.per_set:
        made = write_split_packages(sets, img_dir)
        print("单卷小包：%d 个 -> %s" % (len(made), SPLIT_DIR))

    if tmp:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
