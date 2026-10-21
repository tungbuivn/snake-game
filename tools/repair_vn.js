// Repairs the Vietnamese literals that were corrupted by an ANSI round trip.
// Anchors are the ASCII parts of each statement, so the mojibake itself never
// has to be matched character by character.
const fs = require('fs');

const file = 'D:/snake/app/src/main/java/com/dsh/snake/GameView.java';
let src = fs.readFileSync(file, 'utf8');

const fixes = [
  [/-\s+"[^"]*"\s+manual, swipe controls/g,
    '- "CHƠI"    manual, swipe controls'],
  [/-\s+"[^"]*"\s+the AutoPilot plays by itself \(watch mode\)/g,
    '- "TỰ ĐỘNG" the AutoPilot plays by itself (watch mode)'],

  [/String scoreLabel = "[^"]*";/g, 'String scoreLabel = "ĐIỂM";'],
  [/String bestLabel = "[^"]*";/g, 'String bestLabel = "KỶ LỤC";'],

  [/String hint = autoMode\s*\r?\n\s*\? "[^"]*"\s*\r?\n\s*: "[^"]*";/g,
    'String hint = autoMode\n                ? "AI đang chơi — vuốt để tự điều khiển"\n                : "Vuốt để điều khiển  •  Ăn mồi, tránh tường";'],

  [/c\.drawText\("[^"]*", cx, cy - dp\(70f\), readySubPaint\);/g,
    'c.drawText("Chọn chế độ để bắt đầu", cx, cy - dp(70f), readySubPaint);'],

  [/drawPrimaryButton\(c, startBtn, "[^"]*", false\);/g,
    'drawPrimaryButton(c, startBtn, "CHƠI", false);'],
  [/drawSecondaryButton\(c, autoBtn, "[^"]*", true\);/g,
    'drawSecondaryButton(c, autoBtn, "TỰ ĐỘNG", true);'],

  [/c\.drawText\("[^"]*", cx,\s*\r?\n\s*by \+ bh \+ dp\(38f\), readyHintPaint\);/g,
    'c.drawText("Ăn mồi để lớn lên — đừng đâm vào tường hay thân", cx,\n                by + bh + dp(38f), readyHintPaint);'],
  [/c\.drawText\("[^"]*", cx,\s*\r?\n\s*by \+ bh \+ dp\(62f\), readyHintPaint\);/g,
    'c.drawText("Vuốt để đổi hướng khi tự chơi  •  II để tạm dừng", cx,\n                by + bh + dp(62f), readyHintPaint);'],

  [/c\.drawText\("[^"]*", cx, cy - dp\(92f\), pausedTitlePaint\);/g,
    'c.drawText("TẠM DỪNG", cx, cy - dp(92f), pausedTitlePaint);'],

  [/drawPrimaryButton\(c, resumeBtn, "[^"]*", false\);/g,
    'drawPrimaryButton(c, resumeBtn, "TIẾP TỤC", false);'],
  [/drawSecondaryButton\(c, modeBtn, autoMode \? "[^"]*" : "[^"]*", false\);/g,
    'drawSecondaryButton(c, modeBtn, autoMode ? "THỦ CÔNG" : "TỰ ĐỘNG", false);'],

  [/c\.drawText\(autoMode \? "[^"]*" : "[^"]*",\s*\r?\n\s*cx, by \+ bh \+ dp\(40f\), readyHintPaint\);/g,
    'c.drawText(autoMode ? "Đang ở chế độ TỰ ĐỘNG" : "Đang ở chế độ THỦ CÔNG",\n                cx, by + bh + dp(40f), readyHintPaint);'],

  [/c\.drawText\("[^"]*", cx, top \+ ch \* 0\.17f, cardTitlePaint\);/g,
    'c.drawText("KẾT THÚC", cx, top + ch * 0.17f, cardTitlePaint);'],
  [/c\.drawText\("[^"]*" \+ highScore, cx, top \+ ch \* 0\.52f, cardSubPaint\);/g,
    'c.drawText("Kỷ lục: " + highScore, cx, top + ch * 0.52f, cardSubPaint);'],

  [/drawPrimaryButton\(c, restartBtn, "[^"]*", false\);/g,
    'drawPrimaryButton(c, restartBtn, "CHƠI LẠI", false);'],
  [/drawSecondaryButton\(c, autoRestartBtn, "[^"]*", false\);/g,
    'drawSecondaryButton(c, autoRestartBtn, "TỰ ĐỘNG", false);'],

  [/c\.drawText\("[^"]*", cx, by \+ bh \+ dp\(26f\),/g,
    'c.drawText("hoặc chạm ra ngoài để chơi lại", cx, by + bh + dp(26f),'],
];

let applied = 0;
for (const [re, rep] of fixes) {
  const before = src;
  src = src.replace(re, rep);
  if (src !== before) applied++;
  else console.log('not matched:', re.source.slice(0, 60));
}
fs.writeFileSync(file, src, 'utf8');
console.log(`applied ${applied}/${fixes.length} fixes`);
