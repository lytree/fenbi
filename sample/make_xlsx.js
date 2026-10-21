// 以「行测基础示例.json」为唯一数据源，生成 CSV / TXT / XLSX 三种同源格式。
// 用法：node make_xlsx.js
const fs = require('fs');
const path = require('path');

/* ---------- 最小 ZIP 写入器（store 模式，无压缩） ---------- */
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
  const chunks = [];
  const central = [];
  let offset = 0;
  for (const [name, content] of entries) {
    const nameBuf = Buffer.from(name, 'utf8');
    const data = Buffer.from(content, 'utf8');
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

/* ---------- 读取 JSON 单一数据源 ---------- */
const src = JSON.parse(fs.readFileSync(path.join(__dirname, '行测基础示例.json'), 'utf8'));
const HEADERS = ['题型', '题干', '材料', '选项A', '选项B', '选项C', '选项D', '答案', '解析', '知识点', '难度', '地区', '科目', '来源', '年份', '分值'];

function questionToRow(q) {
  const opts = q.options || {};
  return [
    q.type || '单选',
    q.stem || '',
    q.material || '',
    opts.A || '', opts.B || '', opts.C || '', opts.D || '',
    q.answer || '',
    q.analysis || '',
    q.knowledge || '',
    String(q.difficulty || ''),
    q.region || '',
    q.subject || '',
    q.source || '',
    q.year || '',
    String(q.score || ''),
  ];
}
const rows = src.questions.map(questionToRow);

/* ---------- CSV ---------- */
function csvCell(v) {
  const s = String(v ?? '');
  return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
}
const csv = '﻿' + [HEADERS, ...rows].map(r => r.map(csvCell).join(',')).join('\r\n') + '\r\n';
fs.writeFileSync(path.join(__dirname, '行测基础示例.csv'), csv, 'utf8');

/* ---------- TXT ---------- */
const OPT_KEYS = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'];
let txt = '';
src.questions.forEach((q, i) => {
  txt += `${i + 1}. ${q.stem || ''}\n`;
  if (q.material) {
    txt += `材料：\n${q.material}\n\n`;
  }
  const opts = q.options || {};
  OPT_KEYS.forEach(k => { if (opts[k]) txt += `${k}. ${opts[k]}\n`; });
  txt += `答案：${q.answer || ''}\n`;
  if (q.analysis) txt += `解析：${q.analysis}\n`;
  if (q.knowledge) txt += `知识点：${q.knowledge}\n`;
  if (q.difficulty) txt += `难度：${q.difficulty}\n`;
  if (q.source) txt += `来源：${q.source}\n`;
  if (q.year) txt += `年份：${q.year}\n`;
  if (q.score) txt += `分值：${q.score}\n`;
  txt += '\n';
});
fs.writeFileSync(path.join(__dirname, '行测基础示例.txt'), txt, 'utf8');

/* ---------- XLSX ---------- */
const allRows = [HEADERS, ...rows];
const uniq = [];
const idx = new Map();
allRows.forEach(r => r.forEach(c => { if (!idx.has(c)) { idx.set(c, uniq.length); uniq.push(c); } }));
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const shared = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${uniq.length}" uniqueCount="${uniq.length}">${uniq.map(s => `<si><t xml:space="preserve">${esc(s)}</t></si>`).join('')}</sst>`;

function colName(i) {
  let s = ''; i++;
  while (i > 0) { const m = (i - 1) % 26; s = String.fromCharCode(65 + m) + s; i = Math.floor((i - 1) / 26); }
  return s;
}
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

const out = path.join(__dirname, '行测基础示例.xlsx');
writeZip([
  ['[Content_Types].xml', contentTypes],
  ['_rels/.rels', rels],
  ['xl/workbook.xml', wb],
  ['xl/_rels/workbook.xml.rels', wbRels],
  ['xl/sharedStrings.xml', shared],
  ['xl/worksheets/sheet1.xml', sheet],
], out);

console.log('generated from JSON:', src.questions.length, 'questions');
console.log('  CSV :', fs.statSync(path.join(__dirname, '行测基础示例.csv')).size, 'bytes');
console.log('  TXT :', fs.statSync(path.join(__dirname, '行测基础示例.txt')).size, 'bytes');
console.log('  XLSX:', fs.statSync(out).size, 'bytes');
