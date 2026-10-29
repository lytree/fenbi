#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把「超格·2027政治理论背诵手册」抓取结果整理成 App 的考点背诵资产
=================================================================
读取 course_content/ 下按课程分文件的 .txt（形如 `N. 内容`，记忆关键词在词两侧
用 2 个以上空格包住），转换成 ReciteRepository 需要的
`{id, cat, text, answers[], note, source}` 结构，并合并进
app/src/main/assets/recite_politics.json（保留原有精编卡片，不覆盖）。

用法：
    python tools/build_recite_asset.py \
        --src tmp/recite_extract/course_content \
        --asset app/src/main/assets/recite_politics.json

转换规则：
  · 每张卡片 = txt 里以空行分隔的一段，首行带 “N. ”；卡片内部还有一层 “N.”
  · 记忆关键词 = 被 2+ 个空格（含全角空格）夹住的一段 → 换成 `____`，原文进 answers
  · 空行/空段丢弃；text 中 `____` 数量必须与 answers 数量一致
"""
import argparse
import json
import os
import re
import sys

# 课程顺序（与超格「核心知识带背」18 节一致）
LESSONS = [
    "政治理论考情介绍+新思想导论",
    "新思想第一、二节",
    "新思想第三、四、五节",
    "新思想第六节",
    "新思想第七、八节",
    "新思想第九、十节",
    "新思想第十一、十二节",
    "新思想第十三、十四、十五节",
    "新思想第十六、十七节",
    "时政理论-谋划“十五五”1",
    "时政理论-谋划“十五五”2",
    "时政理论-谋划“十五五”3",
    "时政理论-系列讲话、指示1",
    "时政理论-系列讲话、指示2",
    "时政理论-系列讲话、指示3+重要会议",
    "时政理论-重要文件1",
    "时政理论-重要文件2",
    "时政理论-重要文件3",
]


def split_cards(txt):
    txt = txt.replace("\r\n", "\n").strip()
    return [c.strip() for c in re.split(r"\n\s*\n", txt) if c.strip()]


def convert_line(line, answers):
    parts = re.split(r"[ \u3000]{2,}", line)
    buf = []
    for i, part in enumerate(parts):
        if i % 2 == 0:
            buf.append(part)
        else:
            term = part.strip()
            if term:
                buf.append("____")
                answers.append(term)
    return "".join(buf)


def convert_card(raw):
    s = re.sub(r"^\d+\.", "", raw, count=1)      # 外层编号（不吃空白）
    s = re.sub(r"^\s", "", s, count=1)            # 外层与正文间 1 个空格
    s = re.sub(r"^\d+\.", "", s, count=1)         # 卡片自身编号（不吃空白）
    answers = []
    lines = [convert_line(ln, answers).strip() for ln in s.split("\n")]
    text = "\n".join(l for l in lines if l).strip()
    return text, answers


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="tmp/recite_extract/course_content")
    ap.add_argument("--asset", default="app/src/main/assets/recite_politics.json")
    ap.add_argument("--replace", action="store_true", help="清空原有卡片，只用超格内容")
    ap.add_argument("--prefix", default="超格·", help="新增分类名前缀")
    args = ap.parse_args()

    if not os.path.isdir(args.src):
        sys.exit(f"src 目录不存在: {args.src}")

    with open(args.asset, encoding="utf-8") as f:
        asset = json.load(f)

    existing_cats = asset.get("categories", [])
    existing_cards = [] if args.replace else list(asset.get("cards", []))
    used_ids = {c["id"] for c in asset.get("cards", [])}

    new_cats = []
    new_cards = []
    report = []
    total_blank_cards = 0

    for idx, lesson in enumerate(LESSONS, 1):
        path = os.path.join(args.src, lesson + ".txt")
        if not os.path.exists(path):
            print(f"[warn] 缺少文件: {path}")
            continue
        cat_id = f"cg{idx:02d}"
        while cat_id in {c["id"] for c in existing_cats}:
            cat_id += "x"
        new_cats.append({"id": cat_id, "name": args.prefix + lesson})

        cards = split_cards(open(path, encoding="utf-8").read())
        zero = 0
        for seq, raw in enumerate(cards, 1):
            text, answers = convert_card(raw)
            if not text:
                continue
            if text.count("____") != len(answers):
                print(f"[warn] {lesson} #{seq} 空位数与答案数不一致，跳过")
                continue
            if not answers:
                zero += 1
                total_blank_cards += 1
            cid = f"{cat_id}-{seq:03d}"
            while cid in used_ids:
                cid += "x"
            used_ids.add(cid)
            new_cards.append({
                "id": cid,
                "cat": cat_id,
                "text": text,
                "answers": answers,
                "note": "",
                "source": "超格 · " + lesson,
            })
        report.append((lesson, len(cards), zero))

    asset["categories"] = new_cats + list(existing_cats)
    asset["cards"] = new_cards + existing_cards
    asset["updated"] = "2026-10-29"
    asset["title"] = "2027 政治理论考点背诵（含超格背诵手册）"
    asset["intro"] = (
        "含两部分：① 精编政治理论考点；② 超格《2027政治理论背诵手册礼包》"
        "「核心知识带背」18 节全套考点，按课程分类，共 "
        f"{len(asset['cards'])} 张卡片。"
    )

    with open(args.asset, "w", encoding="utf-8") as f:
        json.dump(asset, f, ensure_ascii=False, indent=2)

    print(f"\n新增分类 {len(new_cats)} 个，新增卡片 {len(new_cards)} 张；"
          f"资产合计 {len(asset['categories'])} 分类 / {len(asset['cards'])} 卡片。")
    print(f"其中无填空卡片 {total_blank_cards} 张。\n")
    for lesson, n, zero in report:
        flag = f" (无填空 {zero})" if zero else ""
        print(f"  {lesson}: {n} 张{flag}")


if __name__ == "__main__":
    main()
