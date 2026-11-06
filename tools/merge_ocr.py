"""把多套参数的 OCR 结果合并成一份：按页取并集，坐标归一化到 220dpi 基准。

- 220dpi（ocr_a..e）：敏感参数，负责行全
- 300dpi（ocr_part0）：补漏
- 260dpi（ocr_f..i）：第三次交叉验证，补零星漏行
同一行多套结果按「文本聚类投票 + 取最长」合并。

用法：python tools/merge_ocr.py
"""
import difflib
import glob
import json
import os
import re

BASE = "F:/Code/Android/fenbi/tmp/zhengzhi"
OUT = BASE + "/ocr_merged.json"

DPI_BY_NAME = {"ocr_part0.json": 300}
for n in "abcde":
    DPI_BY_NAME["ocr_%s.json" % n] = 220
for n in "fghi":
    DPI_BY_NAME["ocr_%s.json" % n] = 260

ROW_TOL = 14   # 220dpi 下同一行的 y 容差（行距约 65）
SIM = 0.72     # 同一行不同来源文本的相似度阈值


def norm(t):
    return re.sub(r"[\s.．、:：,，]", "", t)


def load():
    per_page = {}
    for path in sorted(glob.glob(BASE + "/ocr_*.json")):
        name = re.split(r"[\\/]", path)[-1]
        dpi = DPI_BY_NAME.get(name)
        if not dpi:
            continue
        scale = 220.0 / dpi
        for pg, lines in json.load(open(path, encoding="utf-8")).items():
            bucket = per_page.setdefault(pg, [])
            for ln in lines:
                bucket.append({
                    "y": ln["y"] * scale,
                    "x": ln["x"] * scale,
                    "x2": ln["x2"] * scale,
                    "text": ln["text"],
                    "score": ln["score"],
                    "src": dpi,
                })
    return per_page


def is_optlike(t):
    return bool(re.match(r"^[A-D]\s*[.．、]", t)) or (t[:1] in "①②③④⑤⑥⑦⑧")


def is_circle(t):
    return t[:1] in "①②③④⑤⑥⑦⑧"


def is_letter_opt(t):
    return bool(re.match(r"^[A-D]\s*[.．、]", t))


def same_line(a, b):
    if abs(a["y"] - b["y"]) > ROW_TOL:
        return False
    lo, hi = max(a["x"], b["x"]), min(a["x2"], b["x2"])
    if hi - lo > 0:
        return True
    # 同一行被切成左右两段：间隙不大且不是「横排选项」那种并排结构
    gap = -hi
    if gap < 220 and not (is_letter_opt(a["text"]) and is_letter_opt(b["text"])):
        return True
    return False


def join_text(left, right):
    """拼接同一行被切开的左右两段，去掉重叠部分。"""
    a, b = left.rstrip(), right.lstrip()
    if norm(b) and norm(b) in norm(a):   # 右段已在左段里（重复识别）
        return a
    best = 0
    for k in range(min(len(a), len(b)), 0, -1):
        if a[-k:] == b[:k]:
            best = k
            break
    if best:
        return a + b[best:]
    if a and a[-1] in "，。；：、！？”》" or not a:
        return a + b
    if len(b) <= 20:
        return a + "——" + b
    return a + b


def merge_cluster(cluster):
    """多套结果聚成一组；同一行被切成几段时按 x 顺序拼接。"""
    groups = []  # [[norm_text, [items]]]
    for it in cluster:
        t = norm(it["text"])
        for g in groups:
            if (difflib.SequenceMatcher(None, t, g[0]).ratio() >= SIM
                    or t in g[0] or g[0] in t):
                g[1].append(it)
                if len(t) > len(g[0]):
                    g[0] = t
                break
        else:
            groups.append([t, [it]])

    # 每组挑一个代表
    reps = []
    for rep, items in groups:
        best = max(items, key=lambda i: (len(norm(i["text"])), i["score"]))
        reps.append({"x": best["x"], "x2": best["x2"], "text": best["text"],
                     "score": max(i["score"] for i in items), "src": best["src"],
                     "n": len(items)})
    reps.sort(key=lambda r: r["x"])
    y = sorted(i["y"] for i in cluster)[len(cluster) // 2]

    if len(reps) == 1:
        r = reps[0]
        return {**r, "y": y, "votes": r["n"]}

    optlike = [is_optlike(r["text"]) for r in reps]
    letters = [bool(re.match(r"^[A-D]\s*[.．、]", r["text"])) for r in reps]
    if sum(letters) >= 2:      # 横排选项 A/B/C/D，各段独立
        r = max(reps, key=lambda r: (r["n"], len(norm(r["text"]))))
        return {**r, "y": y, "votes": r["n"]}
    if sum(optlike) >= 2:      # 横排陈述 ①②③④，按 x 顺序拼成一行
        text = "　".join(r["text"] for r in reps)
        return {"x": reps[0]["x"], "x2": reps[-1]["x2"], "text": text, "y": y,
                "score": max(r["score"] for r in reps), "src": reps[0]["src"],
                "votes": min(r["n"] for r in reps)}

    text = reps[0]["text"]
    for r in reps[1:]:
        text = join_text(text, r["text"])
    return {"x": reps[0]["x"], "x2": reps[-1]["x2"], "text": text, "y": y,
            "score": max(r["score"] for r in reps), "src": reps[0]["src"],
            "votes": min(r["n"] for r in reps)}


def main():
    per_page = load()
    out = {}
    for pg, lines in per_page.items():
        lines.sort(key=lambda d: (d["y"], d["x"]))
        clusters = []
        for ln in lines:
            hit = next((c for c in clusters if same_line(c[0], ln)), None)
            if hit is None:
                clusters.append([ln])
            else:
                hit.append(ln)
        rows = [merge_cluster(c) for c in clusters]
        rows.sort(key=lambda d: (d["y"], d["x"]))
        out[pg] = [{"y": round(r["y"], 1), "x": round(r["x"], 1), "x2": round(r["x2"], 1),
                    "text": r["text"], "score": r["score"], "src": r["src"],
                    "votes": r.get("votes", 1)} for r in rows]

    # 人工视觉核对补录的漏检行
    fix_path = BASE + "/ocr_fixups.json"
    n_fix = 0
    if os.path.exists(fix_path):
        for pg, extra in json.load(open(fix_path, encoding="utf-8")).items():
            if pg.startswith("_"):
                continue
            rows = out.setdefault(pg, [])
            for e in extra:
                if any(abs(r["y"] - e["y"]) < 14 for r in rows):
                    continue
                rows.append(e)
                n_fix += 1
            rows.sort(key=lambda d: (d["y"], d["x"]))

    json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("pages", len(out), "lines", sum(len(v) for v in out.values()))
    single = sum(1 for v in out.values() for r in v if r["votes"] == 1)
    print("仅一套参数命中的行:", single, "| 补录行:", n_fix)


if __name__ == "__main__":
    main()
