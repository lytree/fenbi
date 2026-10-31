// 生成 App 内置示例（app/src/main/assets/samples/）：
//   资料分析示例.xlsx      —— 普通题库（材料里的图用 asset:// 内置图，渲染期解析）
//   资料分析题库包.zip     —— 题库包新结构：题库.xlsx + images/资料分析折线图.png + README.txt
//
// 数据源：仓库根的 sample/资料分析示例.json（与旧版内置 JSON 示例同源）。
// 用法：node tools/make_samples.js
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SAMPLES = path.join(ROOT, 'app/src/main/assets/samples');
const HEADERS = ['题型', '题干', '材料', '图片', '选项A', '选项B', '选项C', '选项D', '答案', '解析', '知识点', '难度', '地区', '科目', '来源', '年份', '分值'];

/* ---------------- 最小 xlsx 写入器（与 templates/make_templates.js 同一套实现） ---------------- */
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const colName = i => {
  let s = ''; i++;
  while (i > 0) { const m = (i - 1) % 26; s = String.fromCharCode(65 + m) + s; i = Math.floor((i - 1) / 26); }
  return s;
};

function buildXlsx(sheets) {
  const uniq = []; const idx = new Map();
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
  return [
    ['[Content_Types].xml', contentTypes],
    ['_rels/.rels', rels],
    ['xl/workbook.xml', wb],
    ['xl/_rels/workbook.xml.rels', wbRels],
    ['xl/sharedStrings.xml', shared],
    ...sheetEntries,
  ];
}

/* ---------------- 最小 zip 写入器（store 模式） ---------------- */
const zlib = require('zlib');
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
function writeZip(entries, outFile) {
  const chunks = []; const central = []; let offset = 0;
  for (const [name, content] of entries) {
    const nameBuf = Buffer.from(name, 'utf8');
    const data = Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8');
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x0800, 6);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    chunks.push(local, nameBuf, data);
    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0); cd.writeUInt16LE(20, 4); cd.writeUInt16LE(20, 6); cd.writeUInt16LE(0x0800, 8);
    cd.writeUInt32LE(crc, 16); cd.writeUInt32LE(data.length, 20); cd.writeUInt32LE(data.length, 24);
    cd.writeUInt16LE(nameBuf.length, 28); cd.writeUInt32LE(offset, 42);
    central.push(cd, nameBuf);
    offset += local.length + nameBuf.length + data.length;
  }
  const cdBuf = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(entries.length, 8); eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cdBuf.length, 12); eocd.writeUInt32LE(offset, 16);
  fs.writeFileSync(outFile, Buffer.concat([...chunks, cdBuf, eocd]));
}

/* ---------------- 数据准备 ---------------- */

const src = JSON.parse(fs.readFileSync(path.join(ROOT, 'sample/资料分析示例.json'), 'utf8'));
const PNG_NAME = '资料分析折线图.png';
const pngBuf = fs.readFileSync(path.join(SAMPLES, PNG_NAME));

function toRow(q, material) {
  const o = q.options || {};
  return [
    q.type || '单选', q.stem || '', material || '', q.images || '',
    o.A || '', o.B || '', o.C || '', o.D || '',
    q.answer || '', q.analysis || '', q.knowledge || '',
    String(q.difficulty ?? ''), q.region || '', q.subject || '', q.source || '', q.year || '', String(q.score ?? ''),
  ];
}

// 示例 1：普通 xlsx——材料里的图用 asset:// 指向内置图，渲染期解析
const plainRows = src.questions.map(q => toRow(q, q.material));
fs.writeFileSync(path.join(SAMPLES, '资料分析示例.xlsx'),
  Buffer.concat(writeZipChunks(buildXlsx([{ name: '题库', rows: [HEADERS, ...plainRows] }]))));

// 示例 2：题库包——题库.xlsx + images/（材料引用包内相对路径）
const IMG_REF = 'images/' + PNG_NAME;
const pkgMaterial = src.questions[0].material.replace(/asset:\/\/samples\/[^)]+/, IMG_REF);
const pkgRows = src.questions.map(q => toRow(q, pkgMaterial));
const pkgReadme = `资料分析题库包（示例）

结构：
  题库.xlsx                    题库主数据（统一 Excel 模板，材料里引用 images/资料分析折线图.png）
  images/资料分析折线图.png    材料配图
  README.txt                   这个文件，不参与导入（也可以删）

怎么用：
  1. 把 题库.xlsx 换成你自己的（模板见仓库 templates/题库模板.xlsx）；
  2. 图片放进 images/（文件名任意，Excel 的「材料 / 图片」列里写 images/xxx.png 这样的相对路径）；
  3. 整个文件夹压成 zip（外层多包一层文件夹也没关系）；
  4. App 里「导入」选这个 zip。
`;
writeZip([
  ['资料分析题库包/题库.xlsx', Buffer.concat(writeZipChunks(buildXlsx([{ name: '题库', rows: [HEADERS, ...pkgRows] }])))],
  [`资料分析题库包/${IMG_REF}`, pngBuf],
  ['资料分析题库包/README.txt', pkgReadme],
], path.join(SAMPLES, '资料分析题库包.zip'));

/** writeZip 的变体：返回 buffer 而不落盘（xlsx 嵌套进 zip 用） */
function writeZipChunks(entries) {
  const chunks = []; const central = []; let offset = 0;
  for (const [name, content] of entries) {
    const nameBuf = Buffer.from(name, 'utf8');
    const data = Buffer.isBuffer(content) ? content : Buffer.from(content, 'utf8');
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x0800, 6);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    chunks.push(local, nameBuf, data);
    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0); cd.writeUInt16LE(20, 4); cd.writeUInt16LE(20, 6); cd.writeUInt16LE(0x0800, 8);
    cd.writeUInt32LE(crc, 16); cd.writeUInt32LE(data.length, 20); cd.writeUInt32LE(data.length, 24);
    cd.writeUInt16LE(nameBuf.length, 28); cd.writeUInt32LE(offset, 42);
    central.push(cd, nameBuf);
    offset += local.length + nameBuf.length + data.length;
  }
  const cdBuf = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(entries.length, 8); eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(cdBuf.length, 12); eocd.writeUInt32LE(offset, 16);
  return [Buffer.concat([...chunks, cdBuf, eocd])];
}

// 清掉不再支持格式的旧示例
for (const f of ['行测基础示例.csv', '行测基础示例.json', '行测基础示例.txt', '资料分析示例.json']) {
  const p = path.join(SAMPLES, f);
  if (fs.existsSync(p)) fs.unlinkSync(p);
}

console.log('示例已生成：资料分析示例.xlsx / 资料分析题库包.zip（题库.xlsx + images/）');

/* ---------------- 行测基础示例.xlsx（同步为新表头） ---------------- */

const baseSrc = JSON.parse(fs.readFileSync(path.join(ROOT, 'sample/行测基础示例.json'), 'utf8'));
const baseRows = baseSrc.questions.map(q => toRow(q, q.material));
fs.writeFileSync(path.join(SAMPLES, '行测基础示例.xlsx'),
  Buffer.concat(writeZipChunks(buildXlsx([{ name: '题库', rows: [HEADERS, ...baseRows] }]))));
console.log(`行测基础示例.xlsx：${baseSrc.questions.length} 题`);

/* ---------------- 仓库 sample/ 目录同步（新结构：xlsx + images/） ---------------- */

const SAMPLE_DIR = path.join(ROOT, 'sample');
fs.writeFileSync(path.join(SAMPLE_DIR, '行测基础示例.xlsx'),
  Buffer.concat(writeZipChunks(buildXlsx([{ name: '题库', rows: [HEADERS, ...baseRows] }]))));

const samplePkgDir = path.join(SAMPLE_DIR, '资料分析题库包');
fs.mkdirSync(path.join(samplePkgDir, 'images'), { recursive: true });
fs.writeFileSync(path.join(samplePkgDir, '题库.xlsx'),
  Buffer.concat(writeZipChunks(buildXlsx([{ name: '题库', rows: [HEADERS, ...pkgRows] }]))));
fs.writeFileSync(path.join(samplePkgDir, 'images', PNG_NAME), pngBuf);
fs.writeFileSync(path.join(samplePkgDir, 'README.txt'), `资料分析题库包（示例）

结构：
  题库.xlsx                    题库主数据（统一 Excel 模板，材料里引用 images/资料分析折线图.png）
  images/资料分析折线图.png    材料配图
  README.txt                   这个文件，不参与导入（也可以删）

App 导入页「载入示例题库」用的就是这个包的打包版（app/src/main/assets/samples/）。
`, 'utf8');
fs.writeFileSync(path.join(SAMPLE_DIR, '资料分析题库包.zip'), fs.readFileSync(path.join(SAMPLES, '资料分析题库包.zip')));

for (const f of ['行测基础示例.csv', '行测基础示例.txt', '资料分析题库包/qbank.json']) {
  const p2 = path.join(SAMPLE_DIR, f);
  if (fs.existsSync(p2)) fs.unlinkSync(p2);
}
console.log('sample/ 已同步：行测基础示例.xlsx / 资料分析题库包（题库.xlsx + images/）');
