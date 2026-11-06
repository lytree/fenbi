"""把解析结果写成 App 能直接导入的 xlsx（一张表 = 一份试卷）。

两种产物：
  1) 整卷：200 题一张表
  2) 分组：每 20 题一张表，共 10 份卷

用法：
  python tools/build_zhengzhi_xlsx.py            # 整卷
  python tools/build_zhengzhi_xlsx.py --group 20 # 每 20 题一表
"""
import json
import os
import sys
import zipfile
import xml.sax.saxutils as sx

SRC = r"F:/Code/Android/fenbi/tmp/zhengzhi/questions.json"
OUT_DIR = r"F:/Code/Android/fenbi/qbank_out"

SUBJECT = "政治理论"
TYPE = "单选"
SOURCE = "超格《2027政治理论背诵手册》考前200题"
HEADERS = ["题型", "科目", "题号", "题干", "选项A", "选项B", "选项C", "选项D",
           "答案", "来源"]


def esc(s):
    return sx.escape(str(s))


def col_letter(idx0):
    s, n = "", idx0 + 1
    while n > 0:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


def sheet_xml(rows):
    out = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
           '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">',
           "<sheetData>"]
    for ri, row in enumerate(rows, start=1):
        cells = []
        for ci, val in enumerate(row):
            ref = "%s%d" % (col_letter(ci), ri)
            if val == "" or val is None:
                cells.append('<c r="%s"/>' % ref)
            else:
                cells.append('<c r="%s" t="inlineStr"><is><t xml:space="preserve">%s</t></is></c>'
                             % (ref, esc(val)))
        out.append('<row r="%d">%s</row>' % (ri, "".join(cells)))
    out.append("</sheetData></worksheet>")
    return "".join(out)


def write_xlsx(path, sheets):
    """sheets: [(卷名, rows)]"""
    n = len(sheets)
    overrides = "".join(
        '<Override PartName="/xl/worksheets/sheet%d.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        % (i + 1) for i in range(n))
    content_types = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        + overrides + "</Types>"
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
        for i, (name, _) in enumerate(sheets))
    workbook = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        "<sheets>" + sheet_tags + "</sheets></workbook>"
    )
    wb_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + "".join(
            '<Relationship Id="rId%d" '
            'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
            'Target="worksheets/sheet%d.xml"/>' % (i + 1, i + 1) for i in range(n))
        + "</Relationships>"
    )
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", content_types)
        z.writestr("_rels/.rels", rels)
        z.writestr("xl/workbook.xml", workbook)
        z.writestr("xl/_rels/workbook.xml.rels", wb_rels)
        for i, (_, rows) in enumerate(sheets):
            z.writestr("xl/worksheets/sheet%d.xml" % (i + 1), sheet_xml(rows))


def rows_for(qs, label):
    rows = [list(HEADERS)]
    for q in qs:
        rows.append([
            TYPE, SUBJECT, q["no"], q["stem"],
            q["options"]["A"], q["options"]["B"], q["options"]["C"], q["options"]["D"],
            q["answer"], label,
        ])
    return rows


def main():
    group = 0
    if "--group" in sys.argv:
        group = int(sys.argv[sys.argv.index("--group") + 1])
    qs = json.load(open(SRC, encoding="utf-8"))

    if not group:
        name = "政治理论考前200题"
        path = os.path.join(OUT_DIR, name + ".xlsx")
        write_xlsx(path, [(name, rows_for(qs, SOURCE))])
        print("整卷", path, len(qs), "题")
        return

    name = "政治理论考前200题（%d题一组）" % group
    path = os.path.join(OUT_DIR, name + ".xlsx")
    sheets = []
    for i in range(0, len(qs), group):
        chunk = qs[i:i + group]
        rng = "%d-%d题" % (chunk[0]["no"], chunk[-1]["no"])
        title = "第%02d组（%s）" % (i // group + 1, rng)
        sheets.append((title, rows_for(chunk, SOURCE + " " + rng)))
    write_xlsx(path, sheets)
    print("分组", path, len(sheets), "张表 /", len(qs), "题")
    for t, r in sheets:
        print("  ", t, len(r) - 1, "题")


if __name__ == "__main__":
    main()
