"""把 OCR 行还原成 200 道选择题的结构化数据。

输入：tmp/zhengzhi/ocr_merged.json（坐标已归一化到 220dpi）
输出：tmp/zhengzhi/questions.json + 控制台校验报告
"""
import json
import re
import sys

SRC = sys.argv[1] if len(sys.argv) > 1 else r"F:/Code/Android/fenbi/tmp/zhengzhi/ocr_merged.json"
OUT = r"F:/Code/Android/fenbi/tmp/zhengzhi/questions.json"

ANSWER_RAW = """
1~5:BBCCC 6~10:CACBA 11~15:ADBAA 16~20:DCBCD
21~25:CADDB 26~30:CBCBC 31~35:CCDCD 36~40:DCADD
41~45:CDBBA 46~50:ACCCD 51~55:CCCDD 56~60:BABBB
61~65:CBDCC 66~70:CDDAC 71~75:ADDDC 76~80:ACACB
81~85:BBCBD 86~90:CDDCB 91~95:DABCD 96~100:BBCAA
101~105:BBDCB 106~110:CCDAA 111~115:DDBBD 116~120:CBCDB
121~125:DBCCD 126~130:BCDDA 131~135:DCDDD 136~140:DABAC
141~145:CACDC 146~150:ADCCD 151~155:DCCBC 156~160:CBBBB
161~165:ABACA 166~170:ADBCB 171~175:BBCBC 176~180:DACAD
181~185:DBBAB 186~190:DBDAB 191~195:BCCCD 196~200:BDDAD
"""

QNUM = re.compile(r"^(\d{1,3})\s*[.．、,，]\s*")
OPT = re.compile(r"[A-D]\s*[.．、]")
OPT_HEAD = re.compile(r"^[A-D]\s*[.．、]\s*")
CIRCLE = "①②③④⑤⑥⑦⑧"
CIRCLE_FIX = re.compile(r"(?<=[①②③④⑤⑥⑦⑧])[1-8]|[1-8](?=[①②③④⑤⑥⑦⑧])")
NOISE = ("抖音", "超格教育", "追梦人", "听预测课")


def parse_answers():
    ans = {}
    for lo, letters in re.findall(r"(\d+)\s*[~～-]\s*\d+\s*[:：]\s*([A-D]+)", ANSWER_RAW):
        lo = int(lo)
        for k, ch in enumerate(letters):
            ans[lo + k] = ch
    return ans


def clean(text):
    t = text.replace("\u00a0", " ")
    t = re.sub(r"[ \t]+", "", t)
    return t.strip()


def is_noise(text, y):
    if y < 250 or y > 2100:            # 页眉 / 页脚
        return True
    if re.fullmatch(r"\d{1,4}", text):  # 页码
        return True
    if any(k in text for k in NOISE):
        return True
    return False


def fix_circle(text):
    return CIRCLE_FIX.sub(lambda x: CIRCLE[int(x.group()) - 1], text)


FIXES = {"美其名日": "美其名曰", "名日": "名曰", "会唔": "会晤", "会晒": "会晤",
         "福社": "福祉", "路身": "跻身", "深人": "深入",
         "融人": "融入", "注人": "注入", "加人": "加入", "投人": "投入",
         "人市": "入市", "进人常态": "进入常态"}


def polish(t):
    """OCR 常见成对错误：破折号被识别成“一一”，个别形近字。"""
    t = t.replace("一一", "——").replace("一—", "——").replace("—一", "——")
    t = re.sub(r"—{3,}", "——", t)
    t = re.sub(r"(.{4,40})——\1$", r"\1", t)   # 末尾整段重复
    for a, b in FIXES.items():
        t = t.replace(a, b)
    return t


def split_options(line):
    idx = [m.start() for m in OPT.finditer(line)]
    if not idx:
        return [line]
    parts = []
    for k, s in enumerate(idx):
        e = idx[k + 1] if k + 1 < len(idx) else len(line)
        parts.append(line[s:e].strip())
    return parts


def norm_option(part):
    m = re.match(r"^([A-D])\s*[.．、]\s*(.*)$", part)
    if not m:
        return None
    v = fix_circle(m.group(2).strip())
    # 组合选项里的圈号被读成阿拉伯数字：2345 -> ②③④⑤，①②35 -> ①②③⑤
    if re.fullmatch(r"[①②③④⑤⑥⑦⑧1-8]{2,6}", v):
        v = "".join(CIRCLE[int(c) - 1] if c in "12345678" else c for c in v)
    return m.group(1), v


def main():
    data = json.load(open(SRC, encoding="utf-8"))
    pages = sorted(data, key=int)

    questions, cur, last_field, expected = [], None, None, 1
    problems = []

    for pg in pages:
        for ln in data[pg]:
            text = clean(ln["text"])
            if not text or is_noise(text, ln["y"]):
                continue

            m = QNUM.match(text)
            # 220dpi 基准下：题目行缩进约 262，续行顶格约 201
            is_qstart = bool(m) and int(m.group(1)) == expected and (cur is None or ln["x"] > 230)

            if is_qstart:
                if cur:
                    questions.append(cur)
                cur = {"no": expected, "stem": text[m.end():], "options": {}, "page": int(pg)}
                last_field, expected = "stem", expected + 1
                continue
            if cur is None:
                continue

            if OPT_HEAD.match(text):
                for part in split_options(text):
                    nv = norm_option(part)
                    if not nv:
                        continue
                    k, v = nv
                    cur["options"][k] = v
                    last_field = k
                continue

            # 圈号陈述行；OCR 偶尔把 ⑤ 识别成 "5"，靠「已有圈号陈述」兜住
            has_circle = any(s and s[0] in CIRCLE for s in cur["stem"].split("\n"))
            m_digit = re.match(r"^([1-8])(?=[\u4e00-\u9fa5])", text)
            if text[0] in CIRCLE:
                cur["stem"] += "\n" + fix_circle(text)
                last_field = "stem"
                continue
            if has_circle and m_digit:
                t = CIRCLE[int(m_digit.group(1)) - 1] + text[1:]
                cur["stem"] += "\n" + fix_circle(t)
                last_field = "stem"
                continue

            if last_field in "ABCD" and last_field in cur["options"]:
                cur["options"][last_field] += text
            else:
                cur["stem"] += text

    if cur:
        questions.append(cur)

    ans = parse_answers()
    for q in questions:
        q["answer"] = ans.get(q["no"], "")
        q["stem"] = polish(q["stem"])
        q["options"] = {k: polish(v) for k, v in q["options"].items()}

    nos = [q["no"] for q in questions]
    if nos != list(range(1, 201)):
        missing = sorted(set(range(1, 201)) - set(nos))
        problems.append("题号序列异常：共 %d 题，缺 %s" % (len(nos), missing[:40]))
    for q in questions:
        if sorted(q["options"]) != ["A", "B", "C", "D"]:
            problems.append("第 %d 题选项不全：%s（p%d）" % (q["no"], sorted(q["options"]), q["page"]))
        if not q["answer"]:
            problems.append("第 %d 题缺答案" % q["no"])
        if len(q["stem"]) < 10:
            problems.append("第 %d 题题干过短：%r" % (q["no"], q["stem"]))
        n_circle = len(re.findall(r"[①②③④⑤⑥⑦⑧]", q["stem"]))
        if n_circle and n_circle < 3:
            problems.append("第 %d 题陈述只有 %d 条（疑似漏行）" % (q["no"], n_circle))
        for k, v in q["options"].items():
            if len(v) < 1:
                problems.append("第 %d 题选项 %s 为空" % (q["no"], k))

    json.dump(questions, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("题数:", len(questions), "总字数:",
          sum(len(q["stem"]) + sum(len(v) for v in q["options"].values()) for q in questions))
    print("问题数:", len(problems))
    for p in problems:
        print("  !", p)
    return 0


if __name__ == "__main__":
    sys.exit(main())
