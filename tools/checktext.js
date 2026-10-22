// Verifies the Java source really holds proper UTF-8 Vietnamese text
// (the console may mangle non-ASCII output, so everything is printed escaped).
const fs = require('fs');

const file = process.argv[2] || 'D:/snake/app/src/main/java/com/dsh/snake/GameView.java';
const buf = fs.readFileSync(file);
const txt = buf.toString('utf8');

const expected = [
  'ĐIỂM',
  'KỶ LỤC',
  'CHƠI',
  'TỰ ĐỘNG',
  'THỦ CÔNG',
  'TẠM DỪNG',
  'KẾT THÚC',
  'Kỷ lục: ',
  'Chọn chế độ để bắt đầu',
  'Ăn mồi để lớn lên',
  'Vuốt để điều khiển',
  'Chạm để tiếp tục',
  'Đang ở chế độ',
  'TIẾP TỤC',
  'chọn CHƠI LẠI / AUTOPLAY để chơi ván mới',
];

let bad = 0;
for (const s of expected) {
  const ok = txt.includes(s);
  if (!ok) bad++;
  console.log((ok ? 'OK   ' : 'MISS ') + JSON.stringify(s));
}

const markers = ['Ã', 'Ä', 'á»', 'â€', 'Æ', 'áº', 'Ä‚', '\uFFFD'];
const found = markers.filter((m) => txt.includes(m));
console.log('mojibake markers:', found.length ? JSON.stringify(found) : 'none');

const idx = txt.indexOf('scoreLabel');
console.log('snippet:', JSON.stringify(txt.slice(idx - 20, idx + 50)));
process.exit(bad || found.length ? 1 : 0);
