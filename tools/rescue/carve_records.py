#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
被覆盖发布清掉的作答记录 · 挖掘脚本。

用法：
    uv run tools/rescue/carve_records.py <qbank.db 或 备份包.zip>

输入可以是：
  - 备份包 zip（推荐，直接用 App「备份」生成的那份；自动解开取 qbank.db）
  - 裸 db 文件

原理：SQLite 删行只是把页挂上 freelist，**freelist 的叶页内容原封不动**，
直到页被新数据复用。所以把整个文件每一页都按「表 b-tree 叶页」硬解析一遍，
再按列签名认出 records / record_answers / record_inks / record_question_times /
papers / paper_questions / questions 的行；同时从 sqlite_master 走出真实 b-tree，
凡是不在真实树里的行就是被删的残迹。

输出：<输入>.rescue.json —— live（还在库里的）与 deleted（被删的残迹）分列，
deleted 里就是能救回来的东西。报告看完再决定回灌方案，本脚本只读不写。
"""
import json
import struct
import sys
import zipfile
from pathlib import Path

# ---------------------------------------------------------------- SQLite 底层

def read_varint(buf: bytes, i: int):
    n = 0
    for k in range(9):
        b = buf[i + k]
        if k == 8:
            return (n << 8) | b, i + 9
        n = (n << 7) | (b & 0x7F)
        if not (b & 0x80):
            return n, i + k + 1
    raise ValueError("varint 越界")


def serial_size(t: int) -> int:
    if t in (0, 8, 9):
        return 0
    if 1 <= t <= 4:
        return t
    if t == 5:
        return 6
    if t in (6, 7):
        return 8
    if t >= 12:
        return (t - 12) // 2 if t % 2 == 0 else (t - 13) // 2
    raise ValueError(f"非法 serial type {t}")


def decode_value(t: int, raw: bytes):
    if t == 0:
        return None
    if 1 <= t <= 6:
        n = int.from_bytes(raw, "big", signed=True)
        return n
    if t == 7:
        return struct.unpack(">d", raw)[0]
    if t == 8:
        return 0
    if t == 9:
        return 1
    if t >= 13 and t % 2 == 1:
        return raw.decode("utf-8", errors="replace")
    return raw.hex()  # blob


def parse_record(buf: bytes, i: int):
    """解析一条记录：返回 (值列表, 新位置)。buf 从记录头开始。"""
    header_len, i = read_varint(buf, i)
    body_start = i + header_len
    types = []
    j = i
    while j < body_start:
        t, j = read_varint(buf, j)
        types.append(t)
    vals = []
    k = body_start
    for t in types:
        size = serial_size(t)
        vals.append(decode_value(t, buf[k:k + size]))
        k += size
    return vals, k


# ---------------------------------------------------------------- 页解析

class Carver:
    def __init__(self, data: bytes):
        self.data = data
        self.page_size = struct.unpack(">H", data[16:18])[0]
        if self.page_size == 1:
            self.page_size = 65536
        self.reserved = data[20]
        self.usable = self.page_size - self.reserved
        self.n_pages = len(data) // self.page_size
        self.rows = []  # (page_no, rowid, values)

    def page(self, no: int) -> bytes:
        return self.data[(no - 1) * self.page_size: no * self.page_size]

    def cell_payload(self, page: bytes, cell_off: int, page_no: int) -> bytes:
        """取一个 cell 的完整 payload（含溢出页追链）。"""
        p, i = read_varint(page, cell_off)
        rowid, i = read_varint(page, i)
        u = self.usable
        x = u - 35
        if p <= x:
            return rowid, page[i:i + p]
        m = ((u - 12) * 32) // 255 - 23
        k = m + ((p - m) % (u - 4))
        local = k if k <= x else m
        payload = bytearray(page[i:i + local])
        next_no = struct.unpack(">I", page[i + local:i + local + 4])[0]
        seen = set()
        while next_no and next_no not in seen and len(payload) < p:
            seen.add(next_no)
            op = self.page(next_no)
            next_no = struct.unpack(">I", op[:4])[0]
            payload += op[4:4 + min(u - 4, p - len(payload))]
        return rowid, bytes(payload[:p])

    def parse_leaf_page(self, page_no: int) -> int:
        """把一页硬按叶页解析，返回成功解析的行数。"""
        page = self.page(page_no)
        base = 100 if page_no == 1 else 0
        if page[base] != 0x0D:
            return 0
        n_cells = struct.unpack(">H", page[base + 3:base + 5])[0]
        ptr_off = base + 8
        count = 0
        for c in range(n_cells):
            try:
                off = struct.unpack(">H", page[ptr_off + 2 * c:ptr_off + 2 * c + 2])[0]
                if off < base or off >= self.usable:
                    continue
                rowid, payload = self.cell_payload(page, off, page_no)
                vals, _ = parse_record(payload, 0)
                self.rows.append((page_no, rowid, vals))
                count += 1
            except Exception:
                continue
        return count

    def walk_tree(self, root: int, seen: set):
        """真实 b-tree 遍历：返回这棵树可达的全部 rowid（叶页行由 rows 汇总）。"""
        out = set()
        stack = [root]
        while stack:
            no = stack.pop()
            if no in seen or no < 1 or no > self.n_pages:
                continue
            seen.add(no)
            page = self.page(no)
            base = 100 if no == 1 else 0
            t = page[base]
            if t == 0x0D:
                n_cells = struct.unpack(">H", page[base + 3:base + 5])[0]
                for c in range(n_cells):
                    off = struct.unpack(">H", page[base + 8 + 2 * c:base + 10 + 2 * c])[0]
                    if base <= off < self.usable:
                        try:
                            rowid, _ = self.cell_payload(page, off, no)
                            out.add(rowid)
                        except Exception:
                            pass
            elif t == 0x05:
                n_cells = struct.unpack(">H", page[base + 3:base + 5])[0]
                for c in range(n_cells):
                    off = struct.unpack(">H", page[base + 12 + 2 * c:base + 14 + 2 * c])[0]
                    if base <= off < self.usable:
                        stack.append(struct.unpack(">I", page[off:off + 4])[0])
                stack.append(struct.unpack(">I", page[base + 8:base + 12])[0])
        return out


# ---------------------------------------------------------------- 行分类

def is_ts(v) -> bool:
    return isinstance(v, int) and 1_000_000_000_000 <= v <= 4_000_000_000_000


def is_i(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def is_t(v) -> bool:
    return isinstance(v, str)


def classify(vals, rowid):
    """按列签名认表；认不出返回 None。签名见 QBankDb 建表语句（ALTER 列追加在尾部）。"""
    s = [type(v).__name__ for v in vals]
    n = len(vals)
    if n == 16 and vals[0] is None:
        # records: id(=rowid), paper_id, name, mode, started_at, submitted_at, duration_sec,
        #          total, answered, correct, partial, score, full_score, marked, status, scope
        if (is_i(vals[1]) and is_t(vals[2]) and is_t(vals[3]) and is_ts(vals[4])
                and is_i(vals[5]) and is_i(vals[7]) and is_i(vals[10])
                and isinstance(vals[11], float) and isinstance(vals[12], float)
                and is_t(vals[13]) and is_t(vals[14]) and vals[14] in ("", "done", "ongoing", "abandoned")
                and is_t(vals[15])):
            return "records"
        # questions: id(=rowid), type, stem, material, options, answer, analysis, knowledge,
        #            difficulty, region, subject, source, year, score, keywords, option_layout
        if (is_t(vals[1]) and is_t(vals[2]) and is_i(vals[8]) and isinstance(vals[13], float)
                and is_t(vals[15])):
            return "questions"
        return None
    if n == 7 and vals[0] is None and is_t(vals[1]) and is_t(vals[2]) and is_ts(vals[3]) \
            and is_i(vals[4]) and is_t(vals[5]) and is_i(vals[6]):
        return "papers"  # id, name, description, created_at, duration_min, mode, set_id
    if n == 5:
        # record_answers: record_id, question_id, user_answer, earned, correct
        if (is_i(vals[0]) and is_i(vals[1]) and is_t(vals[2]) and isinstance(vals[3], float)
                and vals[4] in (0, 1)):
            return "record_answers"
        # record_inks: record_id, kind, target_id, strokes, updated_at
        if is_i(vals[0]) and is_t(vals[1]) and vals[1] in ("question", "material") \
                and is_i(vals[2]) and is_t(vals[3]) and is_i(vals[4]):
            return "record_inks"
        return None
    if n == 4 and all(is_i(v) for v in vals):
        return "record_question_times"  # record_id, question_id, seconds, updated_at
    if n == 3 and all(is_i(v) for v in vals):
        return "paper_questions"  # paper_id, question_id, position
    return None


# ---------------------------------------------------------------- 主流程

def main():
    src = Path(sys.argv[1])
    if src.suffix.lower() == ".zip":
        with zipfile.ZipFile(src) as z:
            names = z.namelist()
            db_names = [n for n in names if n.endswith(".db")]
            if not db_names:
                sys.exit("备份包里没找到 db 文件")
            data = z.read(db_names[0])
            out_base = src.with_suffix("")
    else:
        data = src.read_bytes()
        out_base = src

    carver = Carver(data)
    print(f"页大小 {carver.page_size}，共 {carver.n_pages} 页，文件 {len(data)} 字节")

    # 1) 全文件硬解析：每一页都当叶页试一遍（freelist 叶页原封不动，能直接读）
    parsed = sum(carver.parse_leaf_page(no) for no in range(1, carver.n_pages + 1))
    print(f"硬解析出 {parsed} 行")

    # 2) sqlite_master → 各表真实 b-tree → 每张表「还活着」的 rowid
    master_root = 1
    master_rows = {}
    seen = set()
    live_by_table = {}
    tree_root = {}
    # 先从 master 的行里拿各表 root page
    master_carver = Carver(data)
    master_carver.walk_tree(master_root, seen)
    for _, rowid, vals in master_carver.rows:
        if len(vals) == 5 and isinstance(vals[3], int):
            master_rows[vals[1]] = vals[3]  # name → rootpage
    live_by_table = {}
    for name, root in master_rows.items():
        cv = Carver(data)
        live_by_table[name] = cv.walk_tree(root, set())
        tree_root[name] = root
    print("真实树：", {k: len(v) for k, v in sorted(live_by_table.items())})

    # 3) 分类 + 区分 live / deleted
    result = {"live": {}, "deleted": {}, "meta": {
        "page_size": carver.page_size, "pages": carver.n_pages, "parsed_rows": parsed,
        "roots": tree_root,
    }}
    for page_no, rowid, vals in carver.rows:
        table = classify(vals, rowid)
        if table is None:
            continue
        rec = {"page": page_no, "rowid": rowid, "values": vals}
        live_ids = live_by_table.get(table, set())
        # records/questions/papers 的 id 是 rowid 别名；其余表用真实 rowid 比对
        key_id = rowid
        bucket = "live" if key_id in live_ids else "deleted"
        result[bucket].setdefault(table, []).append(rec)

    out = Path(str(out_base) + ".rescue.json")
    out.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")

    for bucket in ("live", "deleted"):
        counts = {k: len(v) for k, v in sorted(result[bucket].items())}
        print(f"[{bucket}] {counts}")
    print(f"\n报告 → {out}")

    # 摘要：被删的 records 一眼能看的字段
    deleted_records = result["deleted"].get("records", [])
    if deleted_records:
        print(f"\n=== 被删的 {len(deleted_records)} 条作答记录 ===")
        for r in sorted(deleted_records, key=lambda x: x["rowid"]):
            v = r["values"]
            submitted = "-" if not v[5] else v[5]
            print(f"  id={r['rowid']} paper={v[1]} 「{v[2]}」 {v[3]} {v[14]} "
                  f"{v[7]}/{v[8]}题 对{v[9]} {v[11]:g}分 (页{r['page']})")


if __name__ == "__main__":
    main()
