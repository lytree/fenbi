import unicodedata

def strip_tone(s: str) -> str:
    s = ''.join('v' if c in 'üǖǘǚǜÜǕǗǙǛ' else c for c in s)
    s = unicodedata.normalize('NFD', s)
    s = ''.join(c for c in s if not unicodedata.combining(c))
    return ''.join(c for c in s.lower() if 'a' <= c <= 'z')

rows = []
for line in open('pinyin_full.txt', encoding='utf-8'):
    line = line.strip()
    if not line or line.startswith('#') or ':' not in line:
        continue
    addr, rest = line.split(':', 1)
    try:
        cp = int(addr.strip().replace('U+', ''), 16)
    except ValueError:
        continue
    if cp > 0xFFFF:
        continue
    py = rest.split('#')[0].strip().split(',')[0].strip()
    py = strip_tone(py)
    if not py:
        continue
    rows.append((chr(cp), py))

rows.sort(key=lambda r: ord(r[0]))
out = ['# 汉字拼音表（单字 → 常用读音，无声调，ü 记作 v）',
       '# 数据源 https://github.com/mozillazg/pinyin-data pinyin.txt v0.15.0',
       '# 由 tools/gen_pinyin.py 生成，勿手改', '']
out += [c + ' ' + p for c, p in rows]
open('F:/Code/github/fenbi/app/src/main/assets/pinyin.txt', 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')
print('chars', len(rows))
