#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 tools/extra_tags.json 里的语义分组标签补进内置词库。

`extract_word_tags.py` 只认识四海 800 词文档里出现过的 801 个词；
词库里另外六百多条成语来自《成语与实词汇总（合并版）》，源文档没有分组，
所以那批词的「细分」一直是空的。这个脚本把人工按释义逐个归类的
`tools/extra_tags.json` 写进 CSV 的空「细分」列。

规则：
  · 只填空标签，**不覆盖**已有标签（文档来的分组优先级更高）
  · 词库里没有的词条只报告、不新增（没有释义、没有分类，凭空加行没有意义）
  · UTF-8 无 BOM + LF，和仓库 .gitattributes 的 eol=lf 对齐

用法：
  uv run python tools/apply_extra_tags.py            # 干跑，只打印统计
  uv run python tools/apply_extra_tags.py --write    # 真正写回 CSV
"""
import argparse
import csv
import json
import os
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="app/src/main/assets/builtin/words.csv")
    ap.add_argument("--tags", default="tools/extra_tags.json")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()

    tags = json.load(open(args.tags, encoding="utf-8"))
    with open(args.csv, encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        fields = reader.fieldnames
        rows = list(reader)

    have = {r["词条"] for r in rows}
    missing = [w for w in tags if w not in have]
    filled = skipped = 0
    for r in rows:
        t = tags.get(r["词条"])
        if not t:
            continue
        if r["细分"].strip():
            skipped += 1
        else:
            r["细分"] = t
            filled += 1

    blank_idiom = sum(1 for r in rows if r["分类"] == "成语" and not r["细分"].strip())
    blank_word = sum(1 for r in rows if r["分类"] != "成语" and not r["细分"].strip())
    print(f"标签表 {len(tags)} 条；本次填充 {filled} 条，已有标签跳过 {skipped} 条")
    print(f"词库里没有的映射 {len(missing)} 个：{missing[:10]}")
    print(f"剩余无标签：成语 {blank_idiom} 条 / 实词 {blank_word} 条")

    if args.write and filled:
        with open(args.csv, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
            w.writeheader()
            w.writerows(rows)
        print(f"已写回 {args.csv}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
