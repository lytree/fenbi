// 由「试卷模板.json」这一份数据源生成全部模板，并在末尾回读校验。
//
// 用法：node templates/make_templates.js
//
// 产物（都在 templates/ 下，**统一只有 Excel 一种格式**）：
//   试卷模板.xlsx            两张表：「试卷」填数据 +「使用说明」内置指南
//   答案模板.xlsx            补答案：题号 / 答案 / 解析
//   解析模板.xlsx            只补解析：题号 / 解析（不动答案）
//   试卷包模板.zip            试卷包：试卷.xlsx + images/chart-01.png + README.txt
//   试卷包模板/               上面 zip 的目录形态
//
// 试卷模板.json 只是生成脚本的数据源，不再是可导入格式（导入只认 Excel）。
// 回读校验按 App 导入器的规则做（xlsx sharedStrings、zip 条目），
// 任何一条不通过都会以非零码退出，避免模板悄悄坏掉。
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const DIR = __dirname;
const SOURCE = path.join(DIR, '试卷模板.json');
const HEADERS = ['题型', '题干', '材料', '图片', '选项A', '选项B', '选项C', '选项D', '答案', '解析', '知识点', '难度', '地区', '科目', '来源', '年份', '分值'];

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
function zipChunks(entries) {
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
  return Buffer.concat([...chunks, cdBuf, eocd]);
}
const writeZip = (data, outFile) =>
  fs.writeFileSync(outFile, Buffer.isBuffer(data) ? data : zipChunks(data));

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

/* ============================ xlsx 构建 ============================ */

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

/** 多表 xlsx：sheets = [{ name, rows }]，返回 zip 字节 */
function buildXlsx(sheets) {
  const uniq = [];
  const idx = new Map();
  sheets.forEach(s => s.rows.forEach(r => r.forEach(c => {
    if (!idx.has(c)) { idx.set(c, uniq.length); uniq.push(c); }
  })));
  const shared = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${uniq.length}" uniqueCount="${uniq.length}">${uniq.map(s => `<si><t xml:space="preserve">${esc(s)}</t></si>`).join('')}</sst>`;
  const sheetEntries = [];
  const sheetOverrides = sheets.map((s, i) => {
    const sheetRows = s.rows.map((r, ri) => {
      const cells = r.map((c, ci) => {
        const ref = `${colName(ci)}${ri + 1}`;
        if (c === '' || c === undefined || c === null) return `<c r="${ref}"/>`;
        return `<c r="${ref}" t="s"><v>${idx.get(c)}</v></c>`;
      }).join('');
      return `<row r="${ri + 1}">${cells}</row>`;
    }).join('');
    sheetEntries.push([`xl/worksheets/sheet${i + 1}.xml`, `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${sheetRows}</sheetData></worksheet>`]);
    return `<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`;
  }).join('');
  const contentTypes = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>${sheetOverrides}<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/></Types>`;
  const rels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`;
  const wb = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${sheets.map((s, i) => `<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>`).join('')}</sheets></workbook>`;
  const n = sheets.length;
  const wbRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheets.map((_, i) => `<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>`).join('')}<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="xl/sharedStrings.xml"/></Relationships>`;
  return zipChunks([
    ['[Content_Types].xml', contentTypes],
    ['_rels/.rels', rels],
    ['xl/workbook.xml', wb],
    ['xl/_rels/workbook.xml.rels', wbRels],
    ['xl/sharedStrings.xml', shared],
    ...sheetEntries,
  ]);
}

/* ============================ 读取数据源 ============================ */

const src = JSON.parse(fs.readFileSync(SOURCE, 'utf8'));
const questions = src.questions;
if (!Array.isArray(questions) || questions.length === 0) throw new Error('试卷模板.json 里没有 questions');

function toRow(q) {
  const o = q.options || {};
  return [
    q.type || '单选',
    q.stem || '',
    q.material || '',
    q.images || '',
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

/** 「使用说明」表内容：两列（条目 / 说明），跟「试卷」表一起打进 xlsx */
const GUIDE_ROWS = [
  ['试卷模板 · 使用说明', '（本表只是说明，导入时会被自动跳过）'],
  ['怎么导入', '在「试卷」表里填题（一行一题，表头别改），App 里点「导入」选这个文件；一次可以选多个文件。统一只支持 Excel（.xlsx）'],
  ['一次导入一个标题', '每个文件在试卷列表里成一个标题（默认取文件名，导入前可改），这个文件拆出的卷挂在标题下面——列表就是「标题 → 试卷 → 题目」三级，没有单独的试卷层'],
  ['多套卷怎么放', '一个工作表 = 一份试卷（表名就是卷名）。按套分表放同一个文件里，一次导入就多建几份卷，它们同属这一个标题；一张表里再排几套题，加一列「试卷」也能按行分开'],
  ['带图片怎么导', '图片多时做成试卷包：把 试卷.xlsx 和图片文件夹（如 images/）一起压成 zip 再导入（见 试卷包模板.zip）；材料 / 图片列里写 images/xxx.png 这样的包内相对路径'],
  ['必填列', '只有「题干」必填，其余列全部可以留空'],
  ['题型', '留空按单选；多选 / 判断 / 不定项要写清楚；多选答案用逗号分隔（如 A,C,D）'],
  ['答案', '可以整列先空着 = 待补答案。导完先做题，之后在成绩报告页「补答案」：逐题填，或导入文件（答案模板.xlsx），会按你的作答自动重判'],
  ['解析', '可以整列先空着。之后导入「解析模板.xlsx」（题号 + 解析两列），App 自动识别、只补解析不动答案'],
  ['图片', '填图片地址，多张用 | 分隔；支持手机本地路径、content://、base64；试卷包里写包内相对路径（见 试卷包模板.zip）。图片会显示在材料顶部'],
  ['材料', '大段文字（含表格）放这里；支持 Markdown 表格、内嵌图片 ![](路径)、自绘图 :::chart。同一道题的材料和图片都写在它自己的行里'],
  ['选项', '最多到 H 列，用不到的留空即可'],
  ['更多帮助', '格式细节见 docs/02-试卷格式规范.md；答案 / 解析后补见 docs/04-答案后补.md；试卷包见 docs/03-试卷包.md'],
];

/* ============================ 产物 ============================ */

// 1) 试卷模板.xlsx：试卷 + 使用说明
const xlsxPath = path.join(DIR, '试卷模板.xlsx');
writeZip(buildXlsx([
  { name: '试卷', rows: [HEADERS, ...rows] },
  { name: '使用说明', rows: GUIDE_ROWS },
]), xlsxPath);

// 2) 答案模板.xlsx：题号 / 答案 / 解析
const answerRows = questions.map((q, i) => [String(i + 1), q.answer || '', q.analysis || '']);
writeZip(buildXlsx([{ name: '答案', rows: [['题号', '答案', '解析'], ...answerRows] }]),
  path.join(DIR, '答案模板.xlsx'));

// 3) 解析模板.xlsx：题号 / 解析（只补解析，不改答案）
const analysisRows = questions.map((q, i) => [String(i + 1), q.analysis || '']);
writeZip(buildXlsx([{ name: '解析', rows: [['题号', '解析'], ...analysisRows] }]),
  path.join(DIR, '解析模板.xlsx'));

// 4) 试卷包：试卷.xlsx + images/ + README.txt
const PKG_DIR = path.join(DIR, '试卷包模板');
const PKG_NAME = '试卷包模板';
fs.mkdirSync(path.join(PKG_DIR, 'images'), { recursive: true });

const PKG_XLSX_NAME = '试卷.xlsx';
const pkgQuestions = questions.map((q, i) => {
  const r = toRow(q);
  if (i === 0) r[3] = 'images/chart-01.png';                       // 图片列示范
  if (i === questions.length - 1) {
    r[2] = `${q.material || ''}\n\n![示例图：替换成你自己的图](images/chart-01.png)`; // 材料内嵌示范
  }
  return r;
});
const pkgXlsx = buildXlsx([{ name: '试卷', rows: [HEADERS, ...pkgQuestions] }]);
fs.writeFileSync(path.join(PKG_DIR, PKG_XLSX_NAME), pkgXlsx);

const pngBuf = makeChartPng(720, 360);
fs.writeFileSync(path.join(PKG_DIR, 'images', 'chart-01.png'), pngBuf);

const pkgReadme = `试卷包模板

结构：
  试卷.xlsx            试卷主数据（统一 Excel 模板，「材料 / 图片」列里引用 images/xxx.png）
  images/              图片等媒体资源
  README.txt           这个文件，不参与导入（也可以删）

怎么用：
  1. 把 试卷.xlsx 换成你自己的（直接改这份，或从 试卷模板.xlsx 复制表头）；
  2. 把 images/chart-01.png 换成你自己的图（文件名任意，记得同步改 Excel 里的引用）；
  3. 整个文件夹压成 zip（外层多包一层文件夹也没关系）；
  4. App 里「导入」选这个 zip。

细节见 docs/03-试卷包.md。
`;
fs.writeFileSync(path.join(PKG_DIR, 'README.txt'), pkgReadme, 'utf8');

const pkgZipPath = path.join(DIR, '试卷包模板.zip');
writeZip([
  [`${PKG_NAME}/${PKG_XLSX_NAME}`, pkgXlsx],
  [`${PKG_NAME}/images/chart-01.png`, pngBuf],
  [`${PKG_NAME}/README.txt`, pkgReadme],
], pkgZipPath);

/* ============================ 回读校验 ============================ */
// 规则对齐 App 的导入器：xlsx sharedStrings 解析、zip 条目。

const ALIASES = {
  type: ['题型', '类型', '题目类型', 'type', 'questiontype', 'qtype', 'category'],
  stem: ['题干', '题目', '试题', '问题', 'stem', 'question', 'title', 'content', 'problem', 'q'],
  material: ['材料', '材料内容', '背景', '背景材料', '资料', 'material', 'passage', 'context'],
  images: ['图片', '图片地址', '配图', '插图', '题图', 'image', 'images', 'img', 'pic'],
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

function readSheet(entries, sheetFile) {
  const sstXml = entries.get('xl/sharedStrings.xml').toString('utf8');
  const shared = [...sstXml.matchAll(/<si>([\s\S]*?)<\/si>/g)]
    .map(m => [...m[1].matchAll(/<t[^>]*>([\s\S]*?)<\/t>/g)].map(x => x[1]).join(''));
  const sheetXml = entries.get(`xl/worksheets/${sheetFile}`).toString('utf8');
  const rows = [...sheetXml.matchAll(/<row[^>]*>([\s\S]*?)<\/row>/g)].map(m => {
    const cells = [];
    // 空单元格是自闭合的 <c r="C2"/>，必须单独处理，否则会把后一个单元格吞掉
    for (const c of m[1].matchAll(/<c\b([^>]*?)(\/>|>([\s\S]*?)<\/c>)/g)) {
      const attr = c[1];
      const ref = /r="([A-Z]+)\d+"/.exec(attr);
      if (!ref) continue;
      const col = ref[1].charCodeAt(0) - 65; // 模板只用 A–Q，单字母足够
      const body = c[3] || '';
      const v = /<v>([\s\S]*?)<\/v>/.exec(body);
      cells[col] = v ? (attr.includes('t="s"') ? shared[Number(v[1])] : v[1]) : '';
    }
    return Array.from(cells, x => (x === undefined ? '' : x));
  });
  return rows;
}

const checks = [];
const check = (name, ok, detail = '') => checks.push({ name, ok: !!ok, detail });

// 题数以数据源为准，脚本不再写死——加减示例题后校验跟着走
const N = questions.length;

// 1) JSON 数据源自身
check('试卷模板.json 可解析', N >= 3, `${N} 题`);
for (const [i, q] of questions.entries()) {
  const keys = Object.keys(q.options || {});
  const ansKeys = String(q.answer || '').split(/[,，、\s]+/).filter(Boolean).map(s => s.toUpperCase()).sort();
  const ok = q.stem && keys.length >= 2 && ansKeys.length > 0 && ansKeys.every(k => keys.map(x => x.toUpperCase()).includes(k));
  check(`第 ${i + 1} 题字段自洽（题干/选项/答案在选项内）`, ok);
}

// 2) 试卷模板.xlsx
const xlsxEntries = readZipStore(fs.readFileSync(xlsxPath));
const xlsxRows = readSheet(xlsxEntries, 'sheet1.xml');
check(`试卷模板.xlsx 行数 = 表头 + ${N}`, xlsxRows.length === N + 1, `${xlsxRows.length} 行`);
check('试卷模板.xlsx 表头正确', JSON.stringify(xlsxRows[0]) === JSON.stringify(HEADERS));
const xlsxFlatSame = xlsxRows.slice(1).every((r, i) => r.slice(1).every((c, j) => c === rows[i][j + 1]));
check('试卷模板.xlsx 单元格内容与源数据一致', xlsxFlatSame);

// 「使用说明」表：必须排在试卷表后面——App 的 XlsxReader 取第一个有数据的 sheet
const wbXml = xlsxEntries.get('xl/workbook.xml').toString('utf8');
check('XLSX 双表：试卷在前、使用说明在后',
  wbXml.includes('name="试卷"') && wbXml.includes('name="使用说明"') &&
  wbXml.indexOf('name="试卷"') < wbXml.indexOf('name="使用说明"'));
const guideRows = readSheet(xlsxEntries, 'sheet2.xml');
check(`使用说明表有 ${GUIDE_ROWS.length} 行说明`,
  guideRows.length === GUIDE_ROWS.length &&
  guideRows[0][0] === GUIDE_ROWS[0][0] &&
  guideRows.every(r => (r[0] || '').length > 0 && (r[1] || '').length > 0),
  `${guideRows.length} 行`);
check('使用说明不残留其他格式说法',
  guideRows.every(r => !/CSV|TXT|JSON/.test((r[1] || ''))), guideRows.map(r => r[1]).find(t => /CSV|TXT|JSON/.test(t || '')) || '');

// 3) 答案模板.xlsx（题号 / 答案 / 解析）
{
  const e = readZipStore(fs.readFileSync(path.join(DIR, '答案模板.xlsx')));
  const rs = readSheet(e, 'sheet1.xml');
  const fields = rs[0].map(resolveField);
  check('答案模板.xlsx 表头识别（题号/答案/解析）',
    fields[0] === 'no' && fields[1] === 'answer' && fields[2] === 'analysis', fields.join(','));
  check(`答案模板.xlsx ${N} 行答案`,
    rs.length === N + 1 && rs.slice(1).every((r, i) => Number(r[0]) === i + 1 && r[1].length > 0));
}

// 4) 解析模板.xlsx（题号 / 解析，没有答案列——App 按解析导入，只更新解析）
{
  const e = readZipStore(fs.readFileSync(path.join(DIR, '解析模板.xlsx')));
  const rs = readSheet(e, 'sheet1.xml');
  const fields = rs[0].map(resolveField);
  check('解析模板.xlsx 表头识别（题号/解析，无答案列）',
    fields[0] === 'no' && fields[1] === 'analysis' && !fields.includes('answer'), fields.join(','));
  check(`解析模板.xlsx ${N} 行解析`,
    rs.length === N + 1 && rs.slice(1).every((r, i) => Number(r[0]) === i + 1 && (r[1] || '').length > 0));
}

// 5) 试卷包
const pkgEntries = readZipStore(fs.readFileSync(pkgZipPath));
const pkgNames = [...pkgEntries.keys()];
check('试卷包内有 试卷.xlsx', pkgNames.includes(`${PKG_NAME}/${PKG_XLSX_NAME}`), pkgNames.join(','));
check('试卷包内有 images/chart-01.png', pkgNames.includes(`${PKG_NAME}/images/chart-01.png`));
const png = pkgEntries.get(`${PKG_NAME}/images/chart-01.png`);
check('占位图是合法 PNG', png.slice(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])), `${png.length} bytes`);
const pkgXlsxEntries = readZipStore(pkgEntries.get(`${PKG_NAME}/${PKG_XLSX_NAME}`));
const pkgRows = readSheet(pkgXlsxEntries, 'sheet1.xml');
check('试卷包 试卷.xlsx 行数正确', pkgRows.length === N + 1, `${pkgRows.length} 行`);
check('试卷包第 1 题图片列引用相对路径', pkgRows[1][3] === 'images/chart-01.png', pkgRows[1][3]);
check('试卷包末题材料内嵌图片引用相对路径',
  /!\[[^\]]*\]\(images\/chart-01\.png\)/.test(pkgRows[N][2] || ''));
check('试卷包外层目录单一（可被自动剥掉）',
  new Set(pkgNames.map(n => n.split('/')[0])).size === 1);

/* ============================ 输出 ============================ */

const failed = checks.filter(c => !c.ok);
console.log(`模板已生成：${questions.length} 题 → 试卷.xlsx（含使用说明）/ 答案.xlsx / 解析.xlsx / 试卷包\n`);
for (const c of checks) {
  if (!c.ok) console.log(`  ✗ ${c.name}${c.detail ? ' — ' + c.detail : ''}`);
}
console.log(`\n回读校验：${checks.length - failed.length}/${checks.length} 通过`);
if (failed.length) {
  console.error('模板存在问题，请修正后重跑。');
  process.exit(1);
}
