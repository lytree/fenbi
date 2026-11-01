#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从两册《高频成语+高频实词800词》docx 抽「分组标签 + 释义」，给内置词库补「细分」。

源文档结构（两册一致）：
  H1 高频成语篇 / 高频实词篇
    4 列分类表：分类（【第N组】组名）| 细分 | 个数 | 词（顿号分隔）  ← 标签主来源
    H2 成语解释 / 实词解释 下的 2 列小表：细分 | 词               ← 标签补充
    H2 成语解释 / 实词解释 → H3 ✎词条 + 释义段 + 【例】段          ← 释义来源
    H2 真题示例｜表格里是题目，**不参与**标签

输出：
  · tools/word_tags.json —— {词条: ["组名·细分", ...]}
  · --write-csv 直接把标签写进词的「细分」列，并把词库里没有的词补成新行

用法：
  uv run --with python-docx python tools/extract_word_tags.py \
      --upper "…上册.docx" --lower "…下册.docx" --write-csv
"""
import argparse
import csv
import json
import os
import re
import sys

from docx import Document
from docx.table import Table
from docx.text.paragraph import Paragraph

GROUP_RE = re.compile(r"^【\s*第?\s*[0-9零〇一二三四五六七八九十百千两]{1,4}\s*组\s*】\s*")
WORD_SPLIT = re.compile(r"[、，,]")
SKIP_WORD = {"个数", "成语", "词"}
MEANING_PREFIX = "✎"


def walk(doc):
    for child in doc.element.body.iterchildren():
        tag = child.tag.split("}")[-1]
        if tag == "p":
            yield ("p", Paragraph(child, doc))
        elif tag == "tbl":
            yield ("t", Table(child, doc))


def cell_text(cell):
    return cell.text.replace("\n", " ").replace("\u00a0", " ").strip()


def clean_group(raw):
    s = GROUP_RE.sub("", cell_text(raw) if hasattr(raw, "text") else str(raw))
    return re.sub(r"\s+", "", s).strip(" 　·:：")


def clean_sub(raw):
    s = cell_text(raw) if hasattr(raw, "text") else str(raw)
    return re.sub(r"\s+", "", s).strip(" 　·:：")


def split_words(raw):
    text = cell_text(raw) if hasattr(raw, "text") else str(raw)
    out = []
    for part in WORD_SPLIT.split(text):
        w = part.strip().strip("“”\"'")
        if not w or w in SKIP_WORD:
            continue
        if not re.search(r"[\u4e00-\u9fff]", w) or len(w) > 12:
            continue
        out.append(w)
    return out


def add_tag(tags, word, label):
    if not word or not label:
        return False
    lst = tags.setdefault(word, [])
    if label in lst:
        return False
    lst.append(label)
    return True


def parse_docx(path, tags, entries, stats):
    doc = Document(path)
    section = ""      # 高频成语篇 / 高频实词篇
    sub_head = ""     # 成语解释 / 实词解释 / 真题示例 …
    cur_word = ""
    buf = []

    def flush():
        nonlocal cur_word, buf
        if not cur_word:
            return
        meaning = ""
        for line in buf:
            if line.startswith("【例】"):
                continue
            if line:
                meaning = line
                break
        cat = "成语" if "成语" in section else ("实词" if "实词" in section else "")
        old = entries.get(cur_word)
        if old is None or (not old["meaning"] and meaning):
            entries[cur_word] = {"category": cat or (old or {}).get("category", ""),
                                 "meaning": meaning or (old or {}).get("meaning", "")}

    for typ, obj in walk(doc):
        if typ == "p":
            style = obj.style.name
            text = obj.text.replace("\u00a0", " ").strip()
            if style == "Heading 1":
                flush()
                cur_word, buf = "", []
                section = text
            elif style == "Heading 2":
                flush()
                cur_word, buf = "", []
                sub_head = text
            elif style == "Heading 3":
                flush()
                buf = []
                cur_word = text.lstrip(MEANING_PREFIX).strip()
            elif cur_word and text:
                buf.append(text)
            continue

        # ---- 表格 ----
        rows = obj.rows
        if not rows:
            continue
        head = [cell_text(c) for c in rows[0].cells]
        is_class_table = "分类" in (head[0] if head else "")

        if len(obj.columns) >= 4:
            # 分类表：前两列是组 / 细分，第三列是个数（纯数字），第四列是顿号分隔的词。
            # 有的表带头（第一行是「成语分类|…|个数|成语」），有的直接就是数据，两种都要认。
            group = ""
            for r in rows:
                cells = [cell_text(c) for c in r.cells]
                if len(cells) < 4:
                    continue
                if not re.fullmatch(r"\d{1,3}", cells[2] or ""):
                    continue          # 不是分类行（表头 / 真题选项 / 其它）
                g = clean_group(cells[0])
                if g:
                    group = g
                sub = clean_sub(cells[1])
                label = group if (group and group == sub) else "·".join(x for x in (group, sub) if x)
                for w in split_words(cells[3]):
                    if add_tag(tags, w, label):
                        stats["tagged"] += 1
        elif len(obj.columns) == 2 and "解释" in sub_head:
            for r in rows:
                cells = [cell_text(c) for c in r.cells]
                if len(cells) != 2:
                    continue
                sub = clean_sub(cells[0])
                for w in split_words(cells[1]):
                    if add_tag(tags, w, sub):
                        stats["supp"] += 1
    flush()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--upper", required=True)
    ap.add_argument("--lower", required=True)
    ap.add_argument("--csv", default="app/src/main/assets/builtin/words.csv")
    ap.add_argument("--out", default="tools/word_tags.json")
    ap.add_argument("--write-csv", action="store_true")
    args = ap.parse_args()

    tags, entries, stats = {}, {}, {"tagged": 0, "supp": 0}
    parse_docx(args.upper, tags, entries, stats)
    parse_docx(args.lower, tags, entries, stats)

    # 同一批词既在分类表（组·细分）又在解释小表（只有细分）出现时，
    # 保留「组·细分」这条，去掉会重复的那条裸细分。
    for w, lst in tags.items():
        tails = {l.split("·", 1)[1] for l in lst if "·" in l}
        tags[w] = [l for l in lst if "·" in l or l not in tails]

    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump({k: v for k, v in sorted(tags.items())}, f, ensure_ascii=False, indent=1)

    print(f"文档标签：{len(tags)} 个词（分类表 {stats['tagged']} 条 / 小表补充 {stats['supp']} 条）")
    print(f"文档释义：{sum(1 for e in entries.values() if e['meaning'])} 个词有条目释义")

    if not os.path.exists(args.csv):
        return 0
    with open(args.csv, encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        fields = reader.fieldnames
        rows = list(reader)

    hit = changed = 0
    for r in rows:
        t = tags.get(r["词条"])
        if not t:
            continue
        hit += 1
        new_tag = "·".join(t)
        if r["细分"].strip() != new_tag:
            r["细分"] = new_tag
            changed += 1

    have = {r["词条"] for r in rows}
    missing = [w for w in tags if w not in have]
    added = []
    for w in sorted(missing):
        e = entries.get(w, {})
        added.append({
            "词条": w,
            "拼音": "",
            "分类": e.get("category") or ("成语" if len(w) == 4 else "实词"),
            "细分": "·".join(tags[w]),
            "释义": e.get("meaning", ""),
            "出处": "内置·四海 800 词",
        })

    print(f"词库 {len(rows)} 条；命中标签 {hit} 条（改了 {changed} 条）；文档里有、词库没有 {len(missing)} 个")

    if args.write_csv:
        out_rows = rows + added
        with open(args.csv, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
            w.writeheader()
            w.writerows(out_rows)
        print(f"已写回 {args.csv}：{len(out_rows)} 条（新增 {len(added)} 条）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
