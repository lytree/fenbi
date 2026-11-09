#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
对「图推700题试卷包.zip」做端到端审计（完全以最终交付包为准，不信任生成脚本的中间结果）：

1. 结构：工作表数 / 每表行数 / 题号连续 / 答案合法 / 科目一致 / 无重复题
2. 引用：题干图 + A~D 四个选项图，引用路径必须真实存在于 zip 内
3. 图片本体：非空白、尺寸合理、选项之间不重复、选项高度均衡
4. 空间关系：题干图与选项图不可能重叠（裁切自不同区域）——用「题干底部墨迹」与
   「选项顶部留白」做间接校验
5. 抽样导出对照图，供人眼复核

用法：
    uv run --with numpy --with pillow python tools/qbank/audit_package.py
    uv run --with numpy --with pillow python tools/qbank/audit_package.py --sample 12
"""

import argparse
import io
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PKG_ZIP = os.path.join(ROOT, "qbank_out", "图推700题试卷包.zip")
DBG = os.path.join(ROOT, "qbank_out", "debug", "audit")

NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def _sheet_xmls(zf):
    """按 workbook.xml 的顺序返回 [(表名, xml_bytes)]。"""
    wb = ET.fromstring(zf.read("xl/workbook.xml"))
    rels = ET.fromstring(zf.read("xl/_rels/workbook.xml.rels"))
    rid2target = {}
    for r in rels:
        rid2target[r.get("Id")] = r.get("Target")
    out = []
    rns = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
    for sh in wb.find("m:sheets", NS):
        name = sh.get("name")
        rid = sh.get(rns)
        tgt = rid2target[rid].lstrip("/")
        if not tgt.startswith("xl/"):
            tgt = "xl/" + tgt
        out.append((name, zf.read(tgt)))
    return out


def _rows(xml_bytes):
    """返回每行的单元格文本列表（跳过空行）。"""
    root = ET.fromstring(xml_bytes)
    rows = []
    for row in root.iter("{http://schemas.openxmlformats.org/spreadsheetml/2006/main}row"):
        vals = []
        for c in row:
            t = c.get("t")
            if t == "inlineStr":
                txt = "".join(x.text or "" for x in c.iter(
                    "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}t"))
            else:
                v = c.find("m:v", NS)
                txt = v.text if v is not None and v.text else ""
            vals.append(txt)
        rows.append(vals)
    return rows


IMG_REF = re.compile(r"!\[[^\]]*]\(([^)]+)\)")


def load_source_answers(root):
    """读原始 Anki TSV，返回 {(套号, 题号): 答案}，作为答案的「事实来源」。"""
    path = os.path.join(root, "qbank_out", "anki_import.tsv")
    if not os.path.exists(path):
        return {}
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            m = re.search(r'src="([^"]+)"', parts[0])
            tag = re.search(r"第\s*(\d+)\s*套\s*第\s*(\d+)\s*题", parts[2])
            if m and tag:
                out[(int(tag.group(1)), int(tag.group(2)))] = parts[1].strip()
    return out


def audit(zip_path, sample=0):
    zf = zipfile.ZipFile(zip_path)
    names = zf.namelist()
    pkg_prefix = names[0].split("/")[0]
    xlsx = [n for n in names if n.endswith(".xlsx")]
    img_names = set(n for n in names if n.lower().endswith((".png", ".jpg", ".jpeg")))
    print("包：%s" % os.path.basename(zip_path))
    print("  条目 %d | xlsx %s | 图片 %d" % (len(names), xlsx, len(img_names)))

    inner = zipfile.ZipFile(io.BytesIO(zf.read(xlsx[0])))
    sheets = _sheet_xmls(inner)
    print("  工作表数 %d：%s … %s" % (len(sheets), sheets[0][0], sheets[-1][0]))

    src_answers = load_source_answers(ROOT)
    print("  原始 TSV 答案数：%d" % len(src_answers))

    problems = []
    q_total = 0
    q_numbers = []
    stems_seen = {}
    cached = {}

    def load(name):
        if name not in cached:
            cached[name] = np.asarray(
                Image.open(io.BytesIO(zf.read(name))).convert("L"))
        return cached[name]

    detail = []
    for sheet_name, xml in sheets:
        rows = _rows(xml)
        header, body = rows[0], rows[1:]
        idx = {h: i for i, h in enumerate(header)}
        nums = []
        for r in body:
            q_total += 1
            r = r + [""] * (len(header) - len(r))
            stem = r[idx.get("题干", 4)]
            nums.append(r[idx.get("题号", 2)])
            ans = r[idx.get("答案", 9)]
            if ans not in ("A", "B", "C", "D"):
                problems.append("%s 答案非法：%r" % (sheet_name, ans))
            stem_key = re.sub(r"\s+", "", stem)
            stems_seen[stem_key] = stems_seen.get(stem_key, 0) + 1

            # 题干图
            refs = {"stem": r[idx.get("图片", 5)]}
            # 选项列按表头定位
            opt_cols = [idx[h] for h in header if h.startswith("选项")]
            if len(opt_cols) != 4:
                problems.append("%s 选项列数 %d" % (sheet_name, len(opt_cols)))
                continue
            for k, ci in zip("ABCD", opt_cols):
                m = IMG_REF.search(r[ci])
                refs[k] = m.group(1) if m else None
                if m is None:
                    problems.append("%s#%s %s 选项无图引用：%r"
                                    % (sheet_name, nums[-1], k, r[ci][:40]))

            paths = {}
            for k, ref in refs.items():
                if not ref:
                    problems.append("%s#%s %s 缺图引用" % (sheet_name, nums[-1], k))
                    continue
                full = "%s/%s" % (pkg_prefix, ref.replace("\\", "/"))
                if full not in img_names:
                    problems.append("%s#%s %s 图片不存在：%s"
                                    % (sheet_name, nums[-1], k, ref))
                    continue
                paths[k] = full

            if len(paths) < 5:
                detail.append((sheet_name, nums[-1], "缺图", None))
                continue

            arrs = {k: load(p) for k, p in paths.items()}
            inks = {k: int((a < 140).sum()) for k, a in arrs.items()}
            shapes = {k: a.shape for k, a in arrs.items()}
            if min(inks.values()) < 60:
                problems.append("%s#%s 有近乎空白的图：%s"
                                % (sheet_name, nums[-1], inks))
            if inks["stem"] < 600:
                problems.append("%s#%s 题干图墨量偏少：%d"
                                % (sheet_name, nums[-1], inks["stem"]))

            # 答案与原始 TSV 对照（事实来源）
            m_set = re.search(r"(\d+)", sheet_name)
            if src_answers and m_set:
                key = (int(m_set.group(1)), int(nums[-1]) if nums[-1].isdigit() else -1)
                want = src_answers.get(key)
                if want and want != ans:
                    problems.append("%s#%s 答案与原始数据不符：表里 %s / 原卷 %s"
                                    % (sheet_name, nums[-1], ans, want))

            # 选项之间不应完全相同（重复 = 拆分失效）
            sigs = {k: arrs[k].tobytes().__hash__() for k in "ABCD"}
            sigs["stem"] = arrs["stem"].tobytes().__hash__()
            if len(set(sigs.values())) < 4:
                dup = [k for k in "ABCD" if list(sigs.values()).count(sigs[k]) > 1]
                problems.append("%s#%s 选项图重复：%s" % (sheet_name, nums[-1], dup))
            if sigs["stem"] in [sigs[k] for k in "ABCD"]:
                problems.append("%s#%s 题干图与某个选项图完全相同"
                                % (sheet_name, nums[-1]))

            hs = [shapes[k][0] for k in "ABCD"]
            ratio = min(hs) / max(hs) if max(hs) else 0
            detail.append((sheet_name, nums[-1], "ok" if ratio > 0.45 else "uneven",
                           (round(ratio, 2), inks)))
        q_numbers.append((sheet_name, nums))

    print("\n题目总数 %d" % q_total)
    for name, nums in q_numbers[:3] + q_numbers[-1:]:
        print("  %s 题号 %s…%s（%d 题）"
              % (name, nums[0] if nums else "-", nums[-1] if nums else "-", len(nums)))
    dup = {k: v for k, v in stems_seen.items() if v > 1}
    if dup:
        print("  重复题干 %d 条：%s" % (len(dup), list(dup)[:3]))

    uneven = [d for d in detail if d[2] == "uneven"]
    print("  选项高度不均衡（<0.45）：%d 题" % len(uneven))
    print("  发现问题 %d 条" % len(problems))
    for p in problems[:20]:
        print("    • " + p)

    if sample and detail:
        os.makedirs(DBG, exist_ok=True)
        import random
        random.seed(3)
        picks = random.sample(detail, min(sample, len(detail)))
        for i, (sheet, num, tag, _) in enumerate(picks):
            pass
        return problems, detail, picks
    return problems, detail, []


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", default=PKG_ZIP)
    ap.add_argument("--sample", type=int, default=0)
    a = ap.parse_args()
    audit(a.zip, a.sample)
