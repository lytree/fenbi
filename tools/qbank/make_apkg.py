#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Package the 700-question bank into a one-click Anki deck (.apkg).

Deck layout:  图推700题 :: 第01套 ... 第35套
Note type:    图推700题  (fields: 题图 / 答案 / 来源)
Images are embedded as media, so importing the .apkg needs no manual file copying.
"""
import json
import os

import genanki

HERE = os.path.dirname(os.path.abspath(__file__))   # tools/qbank
ROOT = os.path.dirname(os.path.dirname(HERE))       # 仓库根目录
OUT_DIR = os.path.join(ROOT, 'qbank_out')           # 生成产物目录（.gitignore 已忽略）
IMG_DIR = os.path.join(OUT_DIR, 'images')
OUT_APKG = os.path.join(OUT_DIR, '图推700题.apkg')

MODEL_ID = 1743902500117
DECK_ID_BASE = 1743902500200

MODEL = genanki.Model(
    MODEL_ID,
    '图推700题',
    fields=[{'name': '题图'}, {'name': '答案'}, {'name': '来源'}],
    templates=[{
        'name': '看图选答案',
        'qfmt': '<div class="q">{{题图}}</div>',
        'afmt': '{{FrontSide}}\n<hr id=answer>\n'
                '<div class="ans">{{答案}}</div>\n'
                '<div class="src">{{来源}}</div>',
    }],
    css="""
.card {
  font-family: -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 18px;
  text-align: center;
  color: #1b1b1b;
  background: #ffffff;
  padding: 14px 10px;
  line-height: 1.6;
}
/* Question art is black-on-white line drawing; never invert it, the puzzle
   semantics (filled vs empty faces, colours) would be destroyed. */
.q img { max-width: 100%; height: auto; background: #ffffff; border-radius: 2px; }
hr#answer { border: 0; border-top: 1px solid #dcdcdc; margin: 14px auto; width: 60%; }
.ans { font-size: 42px; font-weight: 700; color: #c0392b; letter-spacing: 6px; line-height: 1.2; }
.src { font-size: 12px; color: #9a9a9a; margin-top: 8px; }
.nightMode.card, .night_mode.card { background: #1f1f1f; color: #e6e6e6; }
.nightMode hr#answer, .night_mode hr#answer { border-top-color: #4a4a4a; }
.nightMode .ans, .night_mode .ans { color: #ff7b6b; }
.nightMode .src, .night_mode .src { color: #808080; }
""",
)


def main():
    records = json.load(open(os.path.join(OUT_DIR, 'questions.json'), encoding='utf-8'))
    assert len(records) == 700, len(records)

    root = genanki.Deck(DECK_ID_BASE, '图推700题')
    decks = {s: genanki.Deck(DECK_ID_BASE + s, f'图推700题::第{s:02d}套')
             for s in range(1, 36)}

    media = []
    for r in records:
        img = r['image']
        path = os.path.join(IMG_DIR, img)
        assert os.path.exists(path), path
        media.append(path)
        note = genanki.Note(
            model=MODEL,
            fields=[f'<img src="{img}">', r['answer'], f"第{r['set']}套 第{r['qnum']}题"],
            tags=['图推700题', f"第{r['set']:02d}套"],
            guid=genanki.guid_for('tutui700', r['set'], r['qnum']),
        )
        decks[r['set']].add_note(note)

    # root deck is the parent container; children carry the notes
    pkg = genanki.Package(list(decks.values()) + [root])
    pkg.media_files = sorted(set(media))
    pkg.write_to_file(OUT_APKG)
    print('wrote', OUT_APKG)
    print('notes:', len(records), ' media:', len(set(media)))
    print(f'size: {os.path.getsize(OUT_APKG)/1024/1024:.1f} MB')


if __name__ == '__main__':
    main()
