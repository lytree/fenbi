// OCR the left-margin question numbers ("1." ... "20.") of every page.
//
// Prerequisite: render the left margin band of each page to <repo>/tmp/margin/pNNN.png
// (clip x 172-240 @150dpi, rendered at 400dpi) with pymupdf, e.g.
//     for i in range(len(doc)):
//         clip = pymupdf.Rect(172*72/150, 0, 240*72/150, doc[i].rect.height)
//         doc[i].get_pixmap(dpi=400, clip=clip).save(f"tmp/margin/p{i+1:03d}.png")
// Run this script from a directory that has tesseract.js installed (npm i tesseract.js).
// Output: <repo>/qbank_out/ocr_margin.json  (consumed by build_qbank_v6.py)
const { createWorker } = require('tesseract.js');
const path = require('path');
const fs = require('fs');

const ROOT = path.resolve(__dirname, '..', '..');   // tools/qbank -> 仓库根目录
const DIR = path.join(ROOT, 'tmp', 'margin');
const OUT = path.join(ROOT, 'qbank_out', 'ocr_margin.json');

(async () => {
  const files = fs.readdirSync(DIR).filter(f => f.endsWith('.png')).sort();
  const worker = await createWorker('eng', 1, { cachePath: '.' });
  await worker.setParameters({
    tessedit_pageseg_mode: '6',
    tessedit_char_whitelist: '0123456789AC.',
  });
  const result = {};
  for (let i = 0; i < files.length; i++) {
    const f = files[i];
    const pno = parseInt(f.slice(1, 4), 10);
    const { data } = await worker.recognize(path.join(DIR, f), {}, { blocks: true });
    const words = [];
    const walk = (node) => {
      if (!node) return;
      if (node.words) for (const w of node.words) words.push(w);
      for (const k of ['blocks', 'paragraphs', 'lines']) if (node[k]) node[k].forEach(walk);
    };
    if (data.blocks) data.blocks.forEach(walk);
    result[pno] = words.map(w => ({
      text: (w.text || '').trim(),
      y0: Math.round(w.bbox.y0 * 150 / 400),
      y1: Math.round(w.bbox.y1 * 150 / 400),
      conf: Math.round(w.confidence || 0),
    })).filter(t => t.text.length > 0);
    if (i % 40 === 0) console.log(`progress ${i + 1}/${files.length}`);
  }
  await worker.terminate();
  fs.writeFileSync(OUT, JSON.stringify(result, null, 1), 'utf-8');
  console.log('saved', OUT);
})();
