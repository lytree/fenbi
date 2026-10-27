#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把《成语与实词汇总（合并版）》docx 转成 App 内置词库 CSV。

用法：
    python tools/build_builtin_words.py <源文件.docx> <输出.csv>

源文档结构（必须一致，否则脚本会报错而不是瞎猜）：
    子表一 · 成语汇总
        H1 成语汇总
            H3 A/B/C…（首字母分节）
                条目段落：「词条：释义〔分类标签〕」
        H2 成语归类表
            表格：成语分类 | 子类 | 个数 | 成语（顿号分隔）
    子表二 · 实词汇总
        一、汇总概览      表格：来源 | 结构 | 条数（跳过）
        二、高频实词辨析  表格：组 | 词语 | 释义 | 下册分组
        三、高频三字词    表格：词语 | 释义 | 下册分组
        四、400 组        表格：拼音 | 对比词组 | 释义 | 下册分组
        五、下册独有      表格：词语 | 下册分组

输出列与 App 的 WordImporter 表头别名对齐：词条,拼音,分类,细分,释义,出处
拼音留空——App 首次启动导入时会用内置拼音表逐字算，避免这里算错多音字。
"""

import csv
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import OrderedDict

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"

CJK = re.compile(r"[\u4e00-\u9fff]")
BRACKET = re.compile(r"[〔【\[]([^〕】\]]+)[〕】\]]\s*$")
MAX_LEN = 16


# ---------------- docx 读取 ----------------

def para_text(p):
    return "".join(t.text or "" for t in p.iter(W + "t")).strip()


def para_style(p):
    ppr = p.find(W + "pPr")
    if ppr is None:
        return ""
    st = ppr.find(W + "pStyle")
    return st.get(W + "val") if st is not None else ""


def heading_level(p):
    """返回 1..9，非标题返回 0。中文 Word 的标题样式多为 heading 1 / 1 / 标题 1。"""
    st = para_style(p)
    if not st:
        return 0
    m = re.search(r"(\d)$", st)
    if not m:
        return 0
    low = st.lower()
    if "heading" in low or low.startswith("标题") or st in ("1", "2", "3"):
        return int(m.group(1))
    return 0


def table_rows(tbl):
    rows = []
    for tr in tbl.findall(W + "tr"):
        cells = []
        for tc in tr.findall(W + "tc"):
            cells.append("".join(para_text(p) for p in tc.findall(W + "p")).strip())
        rows.append(cells)
    return rows


def load_body(path):
    with zipfile.ZipFile(path) as z:
        xml = z.read("word/document.xml")
    root = ET.fromstring(xml)
    body = root.find(W + "body")
    if body is None:
        raise SystemExit("document.xml 里没有 body")
    return body


# ---------------- 条目清洗 ----------------

def clean_word(raw):
    """去掉序号、项目符号、首尾引号；只保留能当词条的内容。"""
    s = raw.strip().strip("　 ")
    s = re.sub(r"^[0-9０-９]+\s*[.、．)）]\s*", "", s)
    s = re.sub(r"^[·•●▪◆\-—*]\s*", "", s)
    s = s.strip("“”\"'‘’《》「」")
    return s.strip()


def accept_word(s):
    if not s or len(s) > MAX_LEN:
        return False
    if not CJK.search(s):
        return False
    if re.search(r"[\s，。；：、,;:]", s):
        return False
    return True


def split_tag(text):
    """「释义〔分类标签〕」→ (释义, 标签)；没有标签时标签为空。"""
    m = BRACKET.search(text)
    if not m:
        return text.strip(), ""
    return text[: m.start()].strip(), m.group(1).strip()


# ---------------- 主流程 ----------------

class Entry:
    __slots__ = ("text", "category", "tag", "meaning", "source")

    def __init__(self, text, category, tag="", meaning="", source=""):
        self.text = text
        self.category = category
        self.tag = tag
        self.meaning = meaning
        self.source = source


def clean_tag(t):
    """源表用「—」占位表示没有分组，不要把它当标签。"""
    t = (t or "").strip()
    return "" if t in ("—", "-", "——", "/", "无") else t


# 「第一组 动作」「第十一组：需要积累的其他词语」——分组编号对使用者没意义，只留描述
GROUP_PREFIX = re.compile(r"^第\s*[0-9零〇一二三四五六七八九十百千两]{1,4}\s*组[\s:：、,，\-—]*")
# 标签分段：源表里既用「·」也偶尔用「；」把两个细分配在一起
TAG_SPLIT = re.compile(r"[·；;]")


def strip_group(t):
    """去掉标签开头的「第 N 组」编号；整段只有编号时丢掉。"""
    return GROUP_PREFIX.sub("", (t or "").strip()).strip()


def merge_tag(*tags):
    """合并细分标签：按 ·/；拆段、去空、去「第 N 组」、去重（保序）再拼回去。

    同一个词在正文段落和归类表里都会出现，直接 `a + "·" + b` 会拼出
    「动作·对比辨析·动作」这种自我重复，App 的标签筛选就会多出无意义的项。
    """
    parts = []
    for t in tags:
        for part in TAG_SPLIT.split(t or ""):
            p = strip_group(part)
            if p and p not in parts:
                parts.append(p)
    return "·".join(parts)


def main():
    if len(sys.argv) < 3:
        raise SystemExit("用法: build_builtin_words.py <源.docx> <输出.csv>")
    src, dst = sys.argv[1], sys.argv[2]
    body = load_body(src)

    entries = []            # 保持文档顺序
    cur_h1 = ""
    idiom_section = False   # 是否处于「成语汇总」的 A-Z 段落区
    idiom_count = 0
    tables = []             # (前面的 h1/h2 上下文, rows)

    for child in body:
        tag = child.tag
        if tag == W + "p":
            text = para_text(child)
            lvl = heading_level(child)
            if lvl:
                cur_h1 = text
                if text == "成语汇总":
                    idiom_section = True
                elif text in ("成语归类表", "实词汇总（以 800 词为准 · 下册分组）"):
                    idiom_section = False
                continue
            if not idiom_section or not text:
                continue
            if "：" not in text and ":" not in text:
                continue
            sep = "：" if "：" in text else ":"
            word_raw, rest = text.split(sep, 1)
            word = clean_word(word_raw)
            if not accept_word(word):
                continue
            meaning, tag = split_tag(rest)
            tag = clean_tag(tag)
            entries.append(Entry(word, "成语", tag, meaning, "内置·成语汇总"))
            idiom_count += 1
        elif tag == W + "tbl":
            tables.append((cur_h1, table_rows(child)))

    # ---- 成语归类表：给成语补细分（文档里没有〔标签〕的） ----
    idiom_tag = {}
    for ctx, rows in tables:
        if not rows or rows[0][:4] != ["成语分类", "子类", "个数", "成语"]:
            continue
        group = ""
        for r in rows[1:]:
            if len(r) < 4:
                continue
            raw_group = r[0].strip()
            if raw_group:
                group = re.sub(r"^【[^】]*】", "", raw_group).strip() or raw_group
            sub = r[1].strip()
            for w in re.split(r"[、,，/]", r[3]):
                w = clean_word(w)
                if accept_word(w):
                    idiom_tag.setdefault(w, "·".join(x for x in (clean_tag(group), clean_tag(sub)) if x))

    for e in entries:
        if not e.tag and e.text in idiom_tag:
            e.tag = idiom_tag[e.text]

    # ---- 实词四张表 ----
    real_count = 0
    for ctx, rows in tables:
        if not rows:
            continue
        head = rows[0]
        body_rows = [r for r in rows[1:] if any(x.strip() for x in r)]

        if head[:4] == ["组", "词语", "释义", "下册分组"]:
            for r in body_rows:
                if len(r) < 3:
                    continue
                w = clean_word(r[1])
                if not accept_word(w):
                    continue
                entries.append(Entry(w, "实词", clean_tag(r[3] if len(r) > 3 else ""),
                                     r[2].strip(), "内置·实词辨析"))
                real_count += 1
        elif head[:3] == ["词语", "释义", "下册分组"]:
            for r in body_rows:
                w = clean_word(r[0]) if r else ""
                if not accept_word(w):
                    continue
                entries.append(Entry(w, "实词", "三字词", r[1].strip(),
                                     "内置·高频三字词"))
                real_count += 1
        elif head[:4] == ["拼音", "对比词组", "释义", "下册分组"]:
            for r in body_rows:
                if len(r) < 3:
                    continue
                pair = [clean_word(x) for x in re.split(r"[/／]", r[1])]
                pair = [x for x in pair if accept_word(x)]
                if not pair:
                    continue
                gl = r[2].strip()
                group = clean_tag(r[3] if len(r) > 3 else "")
                for w in pair:
                    others = "、".join(x for x in pair if x != w)
                    mean = ("与「%s」辨析：%s" % (others, gl)) if others else gl
                    entries.append(Entry(w, "实词", "对比辨析" + (("·" + group) if group else ""),
                                         mean, "内置·实词对比"))
                    real_count += 1
        elif head[:2] == ["词语", "下册分组"]:
            for r in body_rows:
                w = clean_word(r[0]) if r else ""
                if not accept_word(w):
                    continue
                entries.append(Entry(w, "实词", clean_tag(r[1] if len(r) > 1 else ""),
                                     "", "内置·下册独有"))
                real_count += 1

    # ---- 去重：同词条合并释义 / 标签，成语优先 ----
    merged = OrderedDict()
    for e in entries:
        key = e.text
        old = merged.get(key)
        if old is None:
            merged[key] = e
            continue
        if e.category == "成语" and old.category != "成语":
            old.category = "成语"
        if len(e.meaning) > len(old.meaning):
            old.meaning = e.meaning
        if e.tag:
            old.tag = merge_tag(old.tag, e.tag)

    for e in merged.values():
        e.tag = merge_tag(e.tag)

    # 行尾写死 \n：仓库 .gitattributes 是 eol=lf，本地留 \r\n 会让「我编的包」和「CI 编的包」内容不一致
    with open(dst, "w", encoding="utf-8", newline="") as f:
        wr = csv.writer(f, lineterminator="\n")
        wr.writerow(["词条", "拼音", "分类", "细分", "释义", "出处"])
        for e in merged.values():
            wr.writerow([e.text, "", e.category, e.tag, e.meaning, e.source])

    idioms = sum(1 for e in merged.values() if e.category == "成语")
    reals = len(merged) - idioms
    print("成语段落 %d 条，实词行 %d 条，去重后合计 %d 条（成语 %d / 实词 %d）"
          % (idiom_count, real_count, len(merged), idioms, reals))
    print("已写入 %s" % dst)


if __name__ == "__main__":
    main()
