// 由「题库模板.json」这一份数据源生成其余全部模板，并在末尾回读校验。
//
// 用法：node templates/make_templates.js
//
// 产物（都在 templates/ 下）：
//   题库模板.csv / 题库模板.txt / 题库模板.xlsx
//   答案模板.csv / 答案模板.txt
//   题库包模板/qbank.json + 题库包模板/images/chart-01.png + 题库包模板.zip
//
// 回读校验按 App 导入器的规则做（表头别名、TXT 题号切块、XLSX sharedStrings、zip 条目），
// 任何一条不通过都会以非零码退出，避免模板悄悄坏掉。
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const DIR = __dirname;
const SOURCE = path.join(DIR, '题库模板.json');
const HEADERS = ['题型', '题干', '材料', '选项A', '选项B', '选项C', '选项D', '答案', '解析', '知识点', '难度', '地区', '科目', '来源', '年份', '分值'];
const OPT_KEYS = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'];

/* ============================ 基础工具 ============================ */

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();
function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

/** 最小 ZIP 写入器（store 模式，不压缩；文件名按 UTF-8 标记） */
function writeZip(entries, outFile) {
  const chunks = [];
  const central = [];
  let offset = 0;
  for (const [name, content] of entries) {
    const nameBuf = Buffer.from(name, 'utf8');
    const data = Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8');
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(0x21, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    chunks.push(local, nameBuf, data);

    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0);
    cd.writeUInt16LE(20, 4);
    cd.writeUInt16LE(20, 6);
    cd.writeUInt16LE(0x0800, 8);
    cd.writeUInt16LE(0, 10);
    cd.writeUInt16LE(0, 12);
    cd.writeUInt16LE(0x21, 14);
    cd.writeUInt32LE(crc, 16);
    cd.writeUInt32LE(data.length, 20);
    cd.writeUInt32LE(data.length, 24);
    cd.writeUInt16LE(nameBuf.length, 28);
    cd.writeUInt16LE(0, 30);
    cd.writeUInt16LE(0, 32);
    cd.writeUInt16LE(0, 34);
    cd.writeUInt16LE(0, 36);
    cd.writeUInt32LE(offset, 42);
    central.push(cd, nameBuf);
    offset += local.length + nameBuf.length + data.length;
  }
  const cdBuf = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cdBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);
  fs.writeFileSync(outFile, Buffer.concat([...chunks, cdBuf, eocd]));
}

/** 只读 store 模式 zip（自己写出来的包就是这种） */
function readZipStore(buf) {
  const out = new Map();
  let i = 0;
  while (i + 30 <= buf.length && buf.readUInt32LE(i) === 0x04034b50) {
    const size = buf.readUInt32LE(i + 18);
    const nameLen = buf.readUInt16LE(i + 26);
    const extraLen = buf.readUInt16LE(i + 28);
    const name = buf.slice(i + 30, i + 30 + nameLen).toString('utf8');
    const start = i + 30 + nameLen + extraLen;
    out.set(name, buf.slice(start, start + size));
    i = start + size;
  }
  return out;
}

/** 生成一张折线图风格的占位 PNG（无文字，替换成你自己的图即可） */
function makeChartPng(w, h) {
  const bg = [247, 249, 252];
  const grid = [226, 231, 240];
  const axis = [203, 210, 224];
  const bar = [76, 111, 255];
  const bar2 = [124, 199, 232];
  const left = 56, right = w - 32, top = 32, bottom = h - 48;
  const bars = [0.42, 0.58, 0.5, 0.72, 0.66];
  const bars2 = [0.22, 0.3, 0.26, 0.38, 0.34];
  const slot = (right - left) / bars.length;

  const pixel = (x, y) => {
    if (y < top || y > bottom || x < left || x > right) return bg;
    // 网格线
    for (let g = 0; g <= 4; g++) {
      const gy = Math.round(top + ((bottom - top) * g) / 4);
      if (Math.abs(y - gy) <= 0) return grid;
    }
    const idx = Math.floor((x - left) / slot);
    if (idx < 0 || idx >= bars.length) return bg;
    const x0 = left + idx * slot + slot * 0.18;
    const x1 = left + idx * slot + slot * 0.58;
    if (x >= x0 && x < x1) {
      const y0 = bottom - (bottom - top) * bars[idx];
      if (y >= y0) return bar;
    }
    const x2 = left + idx * slot + slot * 0.64;
    const x3 = left + idx * slot + slot * 0.8;
    if (x >= x2 && x < x3) {
      const y0 = bottom - (bottom - top) * bars2[idx];
      if (y >= y0) return bar2;
    }
    return bg;
  };

  const raw = Buffer.alloc((w * 3 + 1) * h);
  let p = 0;
  for (let y = 0; y < h; y++) {
    raw[p++] = 0; // filter: none
    for (let x = 0; x < w; x++) {
      const c = y === bottom || x === left ? axis : pixel(x, y);
      raw[p++] = c[0];
      raw[p++] = c[1];
      raw[p++] = c[2];
    }
  }

  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const t = Buffer.from(type, 'ascii');
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(Buffer.concat([t, data])));
    return Buffer.concat([len, t, data, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 2;  // color type: truecolor
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/* ============================ 读取数据源 ============================ */

const src = JSON.parse(fs.readFileSync(SOURCE, 'utf8'));
const questions = src.questions;
if (!Array.isArray(questions) || questions.length === 0) throw new Error('题库模板.json 里没有 questions');

function toRow(q) {
  const o = q.options || {};
  return [
    q.type || '单选',
    q.stem || '',
    q.material || '',
    o.A || '', o.B || '', o.C || '', o.D || '',
    q.answer || '',
    q.analysis || '',
    q.knowledge || '',
    String(q.difficulty ?? ''),
    q.region || '',
    q.subject || '',
    q.source || '',
    q.year || '',
    String(q.score ?? ''),
  ];
}
const rows = questions.map(toRow);

/* ============================ CSV ============================ */

function csvCell(v) {
  const s = String(v ?? '');
  return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}
const csvText = '\uFEFF' + [HEADERS, ...rows].map(r => r.map(csvCell).join(',')).join('\r\n') + '\r\n';
const csvPath = path.join(DIR, '题库模板.csv');
fs.writeFileSync(csvPath, csvText, 'utf8');

/* ============================ TXT ============================ */

let txt = '';
questions.forEach((q, i) => {
  txt += `${i + 1}. ${q.stem || ''}\n`;
  if (q.material) txt += `材料：\n${q.material}\n\n`;
  const o = q.options || {};
  OPT_KEYS.forEach(k => { if (o[k]) txt += `${k}. ${o[k]}\n`; });
  txt += `答案：${q.answer || ''}\n`;
  if (q.analysis) txt += `解析：${q.analysis}\n`;
  if (q.knowledge) txt += `知识点：${q.knowledge}\n`;
  if (q.difficulty) txt += `难度：${q.difficulty}\n`;
  if (q.subject) txt += `科目：${q.subject}\n`;
  if (q.source) txt += `来源：${q.source}\n`;
  if (q.year) txt += `年份：${q.year}\n`;
  if (q.score) txt += `分值：${q.score}\n`;
  txt += '\n';
});
const txtPath = path.join(DIR, '题库模板.txt');
fs.writeFileSync(txtPath, txt, 'utf8');

/* ============================ XLSX ============================ */

const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const colName = i => {
  let s = '';
  i++;
  while (i > 0) {
    const m = (i - 1) % 26;
    s = String.fromCharCode(65 + m) + s;
    i = Math.floor((i - 1) / 26);
  }
  return s;
};

function buildXlsx(allRows) {
  const uniq = [];
  const idx = new Map();
  allRows.forEach(r => r.forEach(c => {
    if (!idx.has(c)) { idx.set(c, uniq.length); uniq.push(c); }
  }));
  const shared = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${uniq.length}" uniqueCount="${uniq.length}">${uniq.map(s => `<si><t xml:space="preserve">${esc(s)}</t></si>`).join('')}</sst>`;
  const sheetRows = allRows.map((r, ri) => {
    const cells = r.map((c, ci) => {
      const ref = `${colName(ci)}${ri + 1}`;
      if (c === '' || c === undefined || c === null) return `<c r="${ref}"/>`;
      return `<c r="${ref}" t="s"><v>${idx.get(c)}</v></c>`;
    }).join('');
    return `<row r="${ri + 1}">${cells}</row>`;
  }).join('');
  const sheet = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${sheetRows}</sheetData></worksheet>`;
  const contentTypes = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/></Types>`;
  const rels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`;
  const wb = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="题库" sheetId="1" r:id="rId1"/></sheets></workbook>`;
  const wbRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="xl/sharedStrings.xml"/></Relationships>`;
  return [
    ['[Content_Types].xml', contentTypes],
    ['_rels/.rels', rels],
    ['xl/workbook.xml', wb],
    ['xl/_rels/workbook.xml.rels', wbRels],
    ['xl/sharedStrings.xml', shared],
    ['xl/worksheets/sheet1.xml', sheet],
  ];
}

const xlsxPath = path.join(DIR, '题库模板.xlsx');
writeZip(buildXlsx([HEADERS, ...rows]), xlsxPath);

/* ============================ 答案模板 ============================ */

const answerRows = questions.map((q, i) => [String(i + 1), q.answer || '', q.analysis || '']);
const answerCsv = '\uFEFF' + [['题号', '答案', '解析'], ...answerRows]
  .map(r => r.map(csvCell).join(',')).join('\r\n') + '\r\n';
fs.writeFileSync(path.join(DIR, '答案模板.csv'), answerCsv, 'utf8');

const answerTxt = questions.map((q, i) => `${i + 1}.${q.answer || ''}`).join('\n') + '\n';
fs.writeFileSync(path.join(DIR, '答案模板.txt'), answerTxt, 'utf8');

/* ============================ 题库包 ============================ */

const PKG_DIR = path.join(DIR, '题库包模板');
const PKG_NAME = '题库包模板';
fs.mkdirSync(path.join(PKG_DIR, 'images'), { recursive: true });

const pkgQuestions = [
  {
    ...questions[0],
  },
  {
    ...questions[questions.length - 1],
    material: `${questions[questions.length - 1].material}\n\n![示例图：替换成你自己的图](images/chart-01.png)`,
  },
];
const pkgJson = {
  bank: PKG_NAME,
  description: '题库包模板：qbank.json + images/，材料里的图片写包内相对路径，导入时自动换成绝对路径',
  questions: pkgQuestions,
};
const pkgJsonText = JSON.stringify(pkgJson, null, 2) + '\n';
fs.writeFileSync(path.join(PKG_DIR, 'qbank.json'), pkgJsonText, 'utf8');

const pngBuf = makeChartPng(720, 360);
fs.writeFileSync(path.join(PKG_DIR, 'images', 'chart-01.png'), pngBuf);

const pkgReadme = `题库包模板

结构：
  qbank.json          题库主数据（题目、答案都在里面）
  images/             材料配图，qbank.json 里写 images/xxx.png 这样的相对路径
  README.txt          这个文件，不参与导入（也可以删）

怎么用：
  1. 把 qbank.json 里的题目换成你自己的；
  2. 把 images/chart-01.png 换成你自己的图（文件名任意，记得同步改 qbank.json 里的引用）；
  3. 整个文件夹压成 zip（外层多包一层文件夹也没关系）；
  4. App 里「导入」选这个 zip。

细节见 docs/03-题库包.md。
`;
fs.writeFileSync(path.join(PKG_DIR, 'README.txt'), pkgReadme, 'utf8');

const pkgZipPath = path.join(DIR, '题库包模板.zip');
writeZip([
  [`${PKG_NAME}/qbank.json`, pkgJsonText],
  [`${PKG_NAME}/images/chart-01.png`, pngBuf],
  [`${PKG_NAME}/README.txt`, pkgReadme],
], pkgZipPath);

/* ============================ 回读校验 ============================ */
// 规则对齐 App 的导入器：CSV 表头别名、TXT 题号切块、XLSX sharedStrings、zip 条目。

const ALIASES = {
  type: ['题型', '类型', '题目类型', 'type', 'questiontype', 'qtype', 'category'],
  stem: ['题干', '题目', '试题', '问题', 'stem', 'question', 'title', 'content', 'problem', 'q'],
  material: ['材料', '材料内容', '背景', '背景材料', '资料', 'material', 'passage', 'context'],
  options: ['选项', '选项列表', 'options', 'choices'],
  answer: ['答案', '正确答案', '标准答案', '参考答案', 'answer', 'correctanswer', 'key', 'ans'],
  analysis: ['解析', '答案解析', '说明', 'analysis', 'explanation', 'remark'],
  knowledge: ['知识点', '考点', '标签', 'knowledge', 'point', 'tag'],
  difficulty: ['难度', '难度等级', 'difficulty', 'level'],
  region: ['地区', '省份', '城市', 'region', 'area', 'province'],
  subject: ['科目', '学科', '分类', '模块', 'subject', 'category', 'section'],
  source: ['来源', '出处', 'source', 'origin'],
  year: ['年份', '年度', 'year'],
  score: ['分值', '分', '分数', 'score', 'points'],
  no: ['题号', '题序', '序号', '顺序', '编号', 'no', 'number', 'index', 'seq'],
};
const normHeader = h => String(h)
  .replace(/[\uFF01-\uFF5E]/g, ch => String.fromCharCode(ch.charCodeAt(0) - 0xFEE0))
  .replace(/[\s_\-*:：()（）]/g, '')
  .toLowerCase();

function resolveField(h) {
  const n = normHeader(h);
  for (const [field, list] of Object.entries(ALIASES)) if (list.some(a => normHeader(a) === n)) return field;
  const m = /^(?:选项|option)?([1-8])$/.exec(n);
  if (m) return 'OPT' + String.fromCharCode(64 + Number(m[1]));
  if (/^(?:选项)?[a-h]$/.test(n)) return 'OPT' + n.slice(-1).toUpperCase();
  return null;
}

function parseTable(text) {
  const rows = [];
  let row = [], cell = '', inQuote = false;
  const t = text.replace(/^\uFEFF/, '');
  for (let i = 0; i < t.length; i++) {
    const c = t[i];
    if (inQuote) {
      if (c === '"') {
        if (t[i + 1] === '"') { cell += '"'; i++; } else inQuote = false;
      } else cell += c;
    } else if (c === '"') inQuote = true;
    else if (c === ',') { row.push(cell); cell = ''; }
    else if (c === '\r') { /* skip */ }
    else if (c === '\n') { row.push(cell); rows.push(row); row = []; cell = ''; }
    else cell += c;
  }
  if (cell !== '' || row.length) { row.push(cell); rows.push(row); }
  return rows.filter(r => r.some(c => c !== ''));
}

const checks = [];
const check = (name, ok, detail = '') => checks.push({ name, ok: !!ok, detail });

// 1) JSON 模板自身
check('题库模板.json 可解析', questions.length === 3, `${questions.length} 题`);
for (const [i, q] of questions.entries()) {
  const keys = Object.keys(q.options || {});
  const ansKeys = String(q.answer || '').split(/[,，、\s]+/).filter(Boolean).map(s => s.toUpperCase()).sort();
  const ok = q.stem && keys.length >= 2 && ansKeys.length > 0 && ansKeys.every(k => keys.map(x => x.toUpperCase()).includes(k));
  check(`第 ${i + 1} 题字段自洽（题干/选项/答案在选项内）`, ok);
}

// 2) CSV
const csvRows = parseTable(fs.readFileSync(csvPath, 'utf8'));
const headerFields = csvRows[0].map(resolveField);
check('CSV 表头全部被识别', headerFields.every(f => f !== null), headerFields.join(','));
check('CSV 只有一份题干列', headerFields.filter(f => f === 'stem').length === 1);
check('CSV 行数 = 表头 + 3', csvRows.length === 4, `${csvRows.length} 行`);
const csvStemIdx = headerFields.indexOf('stem');
const csvAnsIdx = headerFields.indexOf('answer');
check('CSV 第 2 行题干与源一致', csvRows[1][csvStemIdx] === questions[0].stem);
check('CSV 多选答案带引号后完整', csvRows[2][csvAnsIdx] === 'A,B,C,D', csvRows[2][csvAnsIdx]);
check('CSV 材料单元格含换行', csvRows[3][headerFields.indexOf('material')].includes('\n'));

// 3) TXT
const TXT_HEAD = /^(?:\(?\d+\s*[.、．)）]|第\s*\d+\s*题\s*[.、．:：]?)\s*.+$/;
const blocks = [];
{
  let cur = null;
  for (const line of fs.readFileSync(txtPath, 'utf8').split('\n')) {
    const t = line.trim();
    if (t && TXT_HEAD.test(t)) { cur = []; blocks.push(cur); }
    if (!cur) { cur = []; blocks.push(cur); }
    cur.push(line);
  }
}
const blocksTrim = blocks.map(b => b.join('\n').trim()).filter(Boolean);
check('TXT 切出 3 个题块', blocksTrim.length === 3, `${blocksTrim.length} 块`);

const parsedTxt = blocksTrim.map((block) => {
  const q = { stem: '', options: {}, fields: {} };
  let stemDone = false, inMaterial = false;
  const matBuf = [];
  const PREFIX = { 答案: 'answer', 解析: 'analysis', 知识点: 'knowledge', 难度: 'difficulty', 科目: 'subject', 来源: 'source', 年份: 'year', 分值: 'score', 题型: 'type', 材料: 'material' };
  const flush = () => { if (inMaterial) { q.fields.material = matBuf.join('\n').trim(); inMaterial = false; } };
  for (const rawLine of block.split('\n')) {
    const line = rawLine.trim();
    if (!line) continue;
    let content = line
      .replace(/^\(?\d+\s*[.、．)）]\s*/, '')
      .replace(/^第\s*\d+\s*题\s*[.、．:：]?\s*/, '');
    const opt = /^([A-Ha-h])\s*[.、．:：)）]\s*(.*)$/.exec(content);
    if (opt) { flush(); q.options[opt[1].toUpperCase()] = opt[2].trim(); continue; }
    const pre = /^([\u4e00-\u9fa5A-Za-z]{1,6})\s*[:：]\s*(.*)$/.exec(content);
    if (pre && PREFIX[pre[1]]) {
      flush();
      if (PREFIX[pre[1]] === 'material') { inMaterial = true; if (pre[2]) matBuf.push(pre[2]); continue; }
      q.fields[PREFIX[pre[1]]] = pre[2];
      continue;
    }
    if (inMaterial) { matBuf.push(content); continue; }
    if (!stemDone) { q.stem = content; stemDone = true; } else { q.stem += '\n' + content; }
  }
  flush();
  return q;
});
check('TXT 每题都解析出题干', parsedTxt.every(q => q.stem.length > 0));
check('TXT 每题都有 4 个选项', parsedTxt.every(q => Object.keys(q.options).length === 4),
  parsedTxt.map(q => Object.keys(q.options).length).join('/'));
check('TXT 多选答案原样保留', parsedTxt[1].fields.answer === 'A,B,C,D', parsedTxt[1].fields.answer);
check('TXT 材料块完整（含表格行）', (parsedTxt[2].fields.material || '').includes('| 总收入（亿元） | 4990.5 | 5280.0 |'));

// 4) XLSX
function readXlsx(file) {
  const entries = readZipStore(fs.readFileSync(file));
  const sstXml = entries.get('xl/sharedStrings.xml').toString('utf8');
  const shared = [...sstXml.matchAll(/<si>([\s\S]*?)<\/si>/g)]
    .map(m => [...m[1].matchAll(/<t[^>]*>([\s\S]*?)<\/t>/g)].map(x => x[1]).join(''));
  const sheetXml = entries.get('xl/worksheets/sheet1.xml').toString('utf8');
  const rows = [...sheetXml.matchAll(/<row[^>]*>([\s\S]*?)<\/row>/g)].map(m => {
    const cells = [];
    // 空单元格是自闭合的 <c r="C2"/>，必须单独处理，否则会把后一个单元格吞掉
    for (const c of m[1].matchAll(/<c\b([^>]*?)(\/>|>([\s\S]*?)<\/c>)/g)) {
      const attr = c[1];
      const ref = /r="([A-Z]+)\d+"/.exec(attr);
      if (!ref) continue;
      const col = ref[1].charCodeAt(0) - 65; // 模板只用 A–P，单字母足够
      const body = c[3] || '';
      const v = /<v>([\s\S]*?)<\/v>/.exec(body);
      cells[col] = v ? (attr.includes('t="s"') ? shared[Number(v[1])] : v[1]) : '';
    }
    return Array.from(cells, x => (x === undefined ? '' : x));
  });
  return rows;
}
const xlsxRows = readXlsx(xlsxPath);
check('XLSX 行数 = 表头 + 3', xlsxRows.length === 4, `${xlsxRows.length} 行`);
check('XLSX 表头与 CSV 一致', JSON.stringify(xlsxRows[0]) === JSON.stringify(HEADERS));
const xlsxFlatSame = xlsxRows.slice(1).every((r, i) => r.slice(1).every((c, j) => c === rows[i][j + 1]));
check('XLSX 单元格内容与源数据一致', xlsxFlatSame);

// 5) 答案模板
const ansRows = parseTable(fs.readFileSync(path.join(DIR, '答案模板.csv'), 'utf8'));
const ansFields = ansRows[0].map(resolveField);
check('答案模板.csv 表头识别（题号/答案/解析）',
  ansFields[0] === 'no' && ansFields[1] === 'answer' && ansFields[2] === 'analysis', ansFields.join(','));
check('答案模板.csv 3 条答案', ansRows.length === 4 && ansRows.slice(1).every((r, i) => Number(r[0]) === i + 1));
const ansTxtLines = fs.readFileSync(path.join(DIR, '答案模板.txt'), 'utf8').split('\n').filter(Boolean);
check('答案模板.txt 每行「题号.答案」', ansTxtLines.length === 3 &&
  ansTxtLines.every((l, i) => new RegExp(`^${i + 1}\\.`).test(l)), ansTxtLines.join(' | '));

// 6) 题库包
const pkgEntries = readZipStore(fs.readFileSync(pkgZipPath));
const pkgNames = [...pkgEntries.keys()];
check('题库包内有 qbank.json', pkgNames.includes(`${PKG_NAME}/qbank.json`), pkgNames.join(','));
check('题库包内有 images/chart-01.png', pkgNames.includes(`${PKG_NAME}/images/chart-01.png`));
const png = pkgEntries.get(`${PKG_NAME}/images/chart-01.png`);
check('占位图是合法 PNG', png.slice(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])), `${png.length} bytes`);
const pkgData = JSON.parse(pkgEntries.get(`${PKG_NAME}/qbank.json`).toString('utf8'));
check('题库包主数据 2 题', pkgData.questions.length === 2);
check('题库包材料引用相对路径', /!\[[^\]]*\]\(images\/chart-01\.png\)/.test(pkgData.questions[1].material));
check('题库包外层目录单一（可被自动剥掉）',
  new Set(pkgNames.map(n => n.split('/')[0])).size === 1);

/* ============================ 输出 ============================ */

const failed = checks.filter(c => !c.ok);
console.log(`模板已生成：${questions.length} 题 → CSV / TXT / XLSX / 答案 ×2 / 题库包\n`);
for (const c of checks) {
  if (!c.ok) console.log(`  ✗ ${c.name}${c.detail ? ' — ' + c.detail : ''}`);
}
console.log(`\n回读校验：${checks.length - failed.length}/${checks.length} 通过`);
if (failed.length) {
  console.error('模板存在问题，请修正后重跑。');
  process.exit(1);
}
