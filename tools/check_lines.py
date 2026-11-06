"""用像素投影估计每页文本行数，和 OCR 行数比对，找出可疑漏检页。

用法：python tools/check_lines.py
"""
import json

import numpy as np
import pymupdf

PDF = r"C:/Users/hiyan/Downloads/Documents/2027年政治理论背诵手册_2026102210434284.pdf"
OCR = r"F:/Code/Android/fenbi/tmp/zhengzhi/ocr_merged.json"
DPI = 110


def ink_rows(page):
    pm = page.get_pixmap(dpi=DPI, colorspace=pymupdf.csGRAY)
    a = np.frombuffer(pm.samples, dtype=np.uint8).reshape(pm.height, pm.width)
    # 只统计正文区（去掉页眉、页脚），与 OCR 的 y 过滤区间一致
    top = int(pm.height * 250 / 2304)
    bottom = int(pm.height * 2100 / 2304)
    a = a[top:bottom]
    dark = (a < 160).sum(axis=1)
    thresh = max(3, int(pm.width * 0.004))
    flags = dark > thresh
    # 连续墨迹行合并成一个文本行
    count, prev = 0, False
    for f in flags:
        if f and not prev:
            count += 1
        prev = f
    return count


def main():
    ocr = json.load(open(OCR, encoding="utf-8"))
    doc = pymupdf.open(PDF)
    rows = []
    for pg in sorted(ocr, key=int):
        ys = sorted({round(ln["y"]) for ln in ocr[pg] if 250 <= ln["y"] <= 2100})
        # OCR 行按 y 聚类（同一行左右分栏算一行），容差 14
        clusters = 1
        for a, b in zip(ys, ys[1:]):
            if b - a > 14:
                clusters += 1
        est = ink_rows(doc[int(pg)])
        rows.append((int(pg), clusters, est, clusters - est))
    print("page  ocr  ink  diff")
    for pg, c, e, d in rows:
        flag = "  <<<" if abs(d) >= 2 else ""
        print(f"{pg:4d} {c:4d} {e:4d} {d:4d}{flag}")
    bad = [r for r in rows if abs(r[3]) >= 2]
    print("\n可疑页:", [r[0] for r in bad])


if __name__ == "__main__":
    main()
