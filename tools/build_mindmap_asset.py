#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把《政治理论思维导图大盘点》的逐页结构（tmp/mindmap/out/pNN.json）合成 App 的导图资产。

输入：
  tmp/mindmap/out/p04.json … p39.json   # 每页 {page, outline:[{level,text}], pairs:[{left,right}]}
输出：
  app/src/main/assets/recite_mindmap.json

为什么分两步：导图是扫描版矢量图，没有文本层。OCR 只给带坐标的零散行，
层级 / 折行归并 / 竖排标签要靠对着页图判断，所以逐页的结构化结果单独存放，
这里只做合并、补章节名与分组、校验覆盖率，重跑幂等。

用法：
    python tools/build_mindmap_asset.py            # 合成资产并校验
    python tools/build_mindmap_asset.py --check    # 只校验，不写文件
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, 'tmp', 'mindmap', 'out')
PAGES_TEXT_DIR = os.path.join(ROOT, 'tmp', 'mindmap', 'pages_text')
ASSET = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'recite_mindmap.json')

# PDF 页 -> 章节名（与政治背诵卡里的 mmXX 分类同名，便于对照）
NAMES = {
    4: '五中全会3-4 · 党性与从严治吏',
    5: '五中全会5-6 · 组织建设与反腐',
    6: '习近平党建思想',
    7: '党的建设1 · 政治/思想/制度',
    8: '党的建设2 · 组织/作风/纪律/反腐',
    9: '党的建设3-4 · 自我革命',
    10: '党的建设5 · 正确政绩观',
    11: '两个结合 · 新思想科学体系',
    12: '社会主义现代化强国',
    13: '中国式现代化 · 四个时期',
    14: '改革开放与对外开放',
    15: '高质量发展',
    16: '中国特色金融发展之路',
    17: '现代金融体系',
    18: '实体经济与现代化产业体系',
    19: '扩大内需',
    20: '全过程人民民主',
    21: '全面依法治国',
    22: '教育强国 · 立德树人',
    23: '基础研究',
    24: '科学技术 · 奖励大会',
    25: '人工智能',
    26: '文化自信 · 文化发展道路',
    27: '民族宗教',
    28: '收入分配',
    29: '人口高质量发展 · 社会建设',
    30: '三农 · 乡村振兴',
    31: '三农 · 县乡村治理',
    32: '城市工作',
    33: '总体国家安全观',
    34: '平安中国 · 公共安全',
    35: '强军建设',
    36: '生态文明建设 · 四大转变',
    37: '全球治理倡议 · 大国关系',
    38: '建党105周年 · 伟大成就',
    39: '闽宁协作 · 义乌经验 · 六尺巷',
}

# 弹层里的二级分组：按书的编排顺序把 36 个主题分块
GROUPS = [
    (4, 10, '党的建设'),
    (11, 14, '新思想与现代化'),
    (15, 19, '经济与金融'),
    (20, 21, '民主法治'),
    (22, 25, '教育科技人才'),
    (26, 27, '文化与宗教'),
    (28, 32, '民生与社会'),
    (33, 35, '安全与国防'),
    (36, 37, '生态与外交'),
    (38, 39, '党史与经验'),
]

# 书页 04 下半部的「党建思想内涵连线」答案。
# 原书左右两列是打乱的两个序列，配对关系按政治理论标准表述给出，
# 与正文里的提法一一对应（例：党中央集中统一领导 = 党的领导的最高原则）。
PAIRS_P6 = [
    ('坚持党的领导', '是中国特色社会主义最本质的特征'),
    ('坚持党中央集中统一领导', '是党的领导的最高原则'),
    ('坚持全面从严治党', '是新时代党的建设的战略方针'),
    ('坚持不忘初心、牢记使命', '是党的宗旨立场和价值取向'),
    ('坚持以党的政治建设为统领', '是新时代党的建设的根本要求'),
    ('坚持用党的创新理论凝心铸魂', '是党的思想建设的根本任务'),
    ('坚持锤炼坚强党性', '是党员、干部的终身课题'),
    ('坚持健全上下贯通、执行有力的组织体系', '是党的优势所在、力量所在'),
    ('坚持建设堪当民族复兴重任的高素质干部队伍', '是关系党和国家事业的关键性问题'),
    ('坚持推进作风建设常态化长效化', '是保持党同人民群众血肉联系的有效途径'),
    ('坚持用严明的纪律管全党治全党', '是全面从严治党的治本之策'),
    ('坚持一体推进不敢腐不能腐不想腐', '是全面从严治党的重要方略和反腐败斗争的基本方针'),
    ('坚持制度治党、依规治党', '是全面从严治党的长远和根本之策'),
    ('坚持落实管党治党政治责任', '是全面从严治党需要牢牢牵住的“牛鼻子”'),
]


def group_of(page):
    for lo, hi, name in GROUPS:
        if lo <= page <= hi:
            return name
    return ''


def norm(text):
    """覆盖率比对用：去掉空白、标点与编号，只留文字和数字"""
    return re.sub(r'[\s，。；：、！？“”‘’（）《》—…\-\.\(\)\"\']', '', text)


def ocr_text(page):
    path = os.path.join(PAGES_TEXT_DIR, 'p%02d.txt' % page)
    if not os.path.exists(path):
        return ''
    lines = []
    with open(path, encoding='utf-8') as f:
        for line in f:
            m = re.match(r'y=\d+ x=\d+ \| (.*)$', line.rstrip('\n'))
            if m:
                lines.append(m.group(1))
    return norm(''.join(lines))


def main():
    check_only = '--check' in sys.argv
    pages = []
    problems = []
    for page in range(4, 40):
        path = os.path.join(OUT_DIR, 'p%02d.json' % page)
        if not os.path.exists(path):
            problems.append('缺少 p%02d.json' % page)
            continue
        with open(path, encoding='utf-8') as f:
            data = json.load(f)
        outline = []
        for node in data.get('outline', []):
            text = (node.get('text') or '').strip()
            if not text:
                continue
            outline.append({'level': max(0, min(4, int(node.get('level', 1)))), 'text': text})
        if not outline:
            problems.append('p%02d 大纲为空' % page)
            continue
        if outline[0]['level'] != 0:
            problems.append('p%02d 第一条不是中心主题（level=%d）' % (page, outline[0]['level']))

        pairs = PAIRS_P6 if page == 6 else []
        pages.append({
            'id': 'mm%02d' % page,
            'page': page - 2,
            'name': NAMES.get(page, '书页 %02d' % (page - 2)),
            'group': group_of(page),
            'outline': outline,
            'pairs': [{'left': l, 'right': r} for l, r in pairs],
        })

    # 覆盖率：结构化文本应覆盖该页 OCR 的绝大部分文字（漏行 / 改写会掉下来）
    print('%-6s %-5s %-5s %-6s %s' % ('页', '节点', '分支', '覆盖率', '备注'))
    for p in pages:
        page_pdf = p['page'] + 2
        joined = norm(
            ''.join(n['text'] for n in p['outline'])
            + ''.join(q['left'] + q['right'] for q in p['pairs'])
        )
        ocr = ocr_text(page_pdf)
        cover = 100.0
        if ocr:
            from collections import Counter
            c1, c2 = Counter(joined), Counter(ocr)
            hit = sum(min(c1[ch], c2[ch]) for ch in c2)
            cover = 100.0 * hit / max(1, sum(c2.values()))
        branches = sum(1 for n in p['outline'] if n['level'] == 1)
        flag = ''
        if cover < 90:
            flag = '覆盖率偏低，检查是否漏行'
            problems.append('p%02d 覆盖率 %.0f%%' % (page_pdf, cover))
        print('p%02d   %-5d %-5d %5.1f%%  %s' % (page_pdf, len(p['outline']), branches, cover, flag))

    asset = {
        'title': '政治理论思维导图大盘点',
        'updated': '2026-11-14',
        'intro': '原书 36 个主题的导图按页还原成层级大纲：中心主题 → 分支 → 要点，'
                 '整句保留、不挖空，适合通读和朗读；书页 02 的「党建思想内涵连线」做成了配对自测。',
        'pages': pages,
    }
    total_nodes = sum(len(p['outline']) for p in pages)
    print('\n共 %d 页 / %d 个节点 / %d 组配对' % (
        len(pages), total_nodes, sum(len(p['pairs']) for p in pages)))

    if problems:
        print('\n校验问题：')
        for x in problems:
            print('  - ' + x)
    if check_only:
        return 1 if problems else 0

    with open(ASSET, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(asset, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print('已写入 %s' % os.path.relpath(ASSET, ROOT))
    return 0


if __name__ == '__main__':
    sys.exit(main())
