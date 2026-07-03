/**
 * Play Store Screenshot Generator
 * Composites app screenshots with Android phone frame + feature text overlays
 * Output: playstore_output/ — 8 PNG files at 1080×1920 px
 */

const sharp = require('sharp');
const fs = require('fs');
const path = require('path');

// ─── Canvas ───────────────────────────────────────────────────────────────────
const W = 1080, H = 1920;

// ─── Phone frame geometry ─────────────────────────────────────────────────────
const PH_W = 500, PH_H = 1040, PH_R = 56;
const PH_X = (W - PH_W) / 2;   // 290  (centred)
const PH_Y = 700;

// Screen inside phone
const SC_PAD_X = 22, SC_PAD_T = 50, SC_PAD_B = 68;
const SC_X = PH_X + SC_PAD_X;                     // 312
const SC_Y = PH_Y + SC_PAD_T;                     // 750
const SC_W = PH_W - SC_PAD_X * 2;                 // 456
const SC_H = PH_H - SC_PAD_T - SC_PAD_B;          // 922
const SC_R = 38;

// ─── Output dir ───────────────────────────────────────────────────────────────
const OUT = path.join(__dirname, 'playstore_output');
if (!fs.existsSync(OUT)) fs.mkdirSync(OUT);

// ─── Screenshot configs ───────────────────────────────────────────────────────
const SHOTS = [
  {
    file: 'screenshots/first screen shot.jpg', out: '01_home.png',
    badge: 'JEE & NEET',
    line1: 'Complete Exam', line2: 'Preparation App',
    desc: 'Mock Tests  •  Chapterwise  •  PYQs  •  Full Series',
    pills: ['Mock Test', 'Chapterwise', 'PYQs', 'Full Series'],
    bg1: '#BF360C', bg2: '#E65100', bg3: '#FF6D00',
    accent: '#FFE082', textClr: '#FFFFFF',
  },
  {
    file: 'screenshots/1.jpg', out: '02_chapterwise.png',
    badge: 'CHAPTERWISE',
    line1: 'Master Every', line2: 'Chapter Separately',
    desc: '30 Questions per chapter  •  Progress tracked  •  Free chapters',
    pills: ['30 Qs / chapter', 'Progress bar', 'FREE chapters'],
    bg1: '#880E4F', bg2: '#AD1457', bg3: '#E91E8C',
    accent: '#FF80AB', textClr: '#FFFFFF',
  },
  {
    file: 'screenshots/4.jpg', out: '03_ai_advisor.png',
    badge: 'AI POWERED',
    line1: 'Real Exam +', line2: 'AI Daily Plan',
    desc: '180-min full mock  •  AI builds your personal study plan daily',
    pills: ['180 min mock', 'AI daily plan', 'Exam readiness %'],
    bg1: '#004D40', bg2: '#00695C', bg3: '#00897B',
    accent: '#64FFDA', textClr: '#FFFFFF',
  },
  {
    file: 'screenshots/7.jpg', out: '04_analytics.png',
    badge: 'ANALYTICS',
    line1: 'Subject-wise', line2: 'Performance Analysis',
    desc: 'Physics, Chemistry & Maths accuracy  •  Weak topics auto-flagged',
    pills: ['Subject accuracy', 'Weak topics', 'Correct / Wrong'],
    bg1: '#0D47A1', bg2: '#1565C0', bg3: '#1976D2',
    accent: '#82B1FF', textClr: '#FFFFFF',
  },
  {
    file: 'screenshots/6.jpg', out: '05_leaderboard.png',
    badge: 'COMPETE',
    line1: 'Weekly Leaderboard', line2: 'Beat the Best',
    desc: 'Rank among thousands of JEE & NEET aspirants  •  Resets weekly',
    pills: ['Top 10 ranking', 'Weekly reset', 'Streak tracking'],
    bg1: '#4A148C', bg2: '#6A1B9A', bg3: '#7B1FA2',
    accent: '#CE93D8', textClr: '#FFFFFF',
  },
  {
    file: 'screenshots/11.jpg', out: '06_scan_solve.png',
    badge: 'AI TOOL',
    line1: 'Scan Any Question', line2: 'Get AI Solution',
    desc: 'Point camera at any question  •  Instant step-by-step answer',
    pills: ['Camera scan', 'Type question', 'AI explanation'],
    bg1: '#1C2833', bg2: '#273746', bg3: '#2E4053',
    accent: '#FFA000', textClr: '#FFFFFF',
  },
  {
    file: 'screenshots/12.jpg', out: '07_test_interface.png',
    badge: 'LIVE TEST',
    line1: 'Real NTA Exam UI', line2: 'Timer & Marking',
    desc: '+4 / -1 marking  •  Hint  •  Mark for Review  •  Like NTA exactly',
    pills: ['+4 / -1 marking', 'Live timer', 'Mark for review'],
    bg1: '#B71C1C', bg2: '#C62828', bg3: '#D32F2F',
    accent: '#FF8A80', textClr: '#FFFFFF',
  },
  {
    file: 'screenshots/14.jpg', out: '08_results.png',
    badge: 'RESULTS',
    line1: 'Instant Results', line2: '& Smart Analysis',
    desc: 'Q-by-Q breakdown  •  Retry wrong answers  •  Unlock solutions',
    pills: ['Q-by-Q analysis', 'Retry wrongs', 'Unlock solutions'],
    bg1: '#1B5E20', bg2: '#2E7D32', bg3: '#388E3C',
    accent: '#B9F6CA', textClr: '#FFFFFF',
  },
];

// ─── Helpers ──────────────────────────────────────────────────────────────────
function x(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function pillW(text) {
  return Math.min(Math.max(text.length * 13 + 44, 140), 200);
}

// ─── Background SVG (gradient header + text + pills + phone body) ─────────────
function bgSVG(s) {
  // pills
  let pillsXml = '';
  let px = 90;
  const pillY = 510, pillH = 48, pillR = 24;
  for (const p of s.pills) {
    const pw = pillW(p);
    pillsXml += `
      <rect x="${px}" y="${pillY}" width="${pw}" height="${pillH}" rx="${pillR}"
            fill="rgba(255,255,255,0.16)" stroke="rgba(255,255,255,0.32)" stroke-width="1.5"/>
      <text x="${px + pw / 2}" y="${pillY + 31}" font-family="Arial,sans-serif" font-size="20"
            font-weight="700" fill="${s.textClr}" text-anchor="middle">${x(p)}</text>`;
    px += pw + 16;
  }

  return `<svg width="${W}" height="${H}" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="hdr" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%"   stop-color="${s.bg1}"/>
      <stop offset="60%"  stop-color="${s.bg2}"/>
      <stop offset="100%" stop-color="${s.bg3}"/>
    </linearGradient>
    <linearGradient id="fade" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%"   stop-color="${s.bg3}"/>
      <stop offset="100%" stop-color="#0A0A18"/>
    </linearGradient>
    <linearGradient id="base" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%"   stop-color="#0A0A18"/>
      <stop offset="100%" stop-color="#12121E"/>
    </linearGradient>
    <linearGradient id="btmglow" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%"   stop-color="#0A0A18"/>
      <stop offset="100%" stop-color="${s.bg1}" stop-opacity="0.45"/>
    </linearGradient>
  </defs>

  <!-- Dark base -->
  <rect width="${W}" height="${H}" fill="url(#base)"/>

  <!-- Header gradient block -->
  <rect width="${W}" height="${PH_Y + 20}" fill="url(#hdr)"/>

  <!-- Header → dark fade -->
  <rect y="${PH_Y - 80}" width="${W}" height="160" fill="url(#fade)"/>

  <!-- Bottom glow -->
  <rect y="${PH_Y + PH_H + 30}" width="${W}" height="${H - PH_Y - PH_H - 30}" fill="url(#btmglow)"/>

  <!-- Decorative circles -->
  <circle cx="1000" cy="70"  r="220" fill="rgba(255,255,255,0.045)"/>
  <circle cx="-20"  cy="440" r="160" fill="rgba(255,255,255,0.03)"/>
  <circle cx="850"  cy="600" r="90"  fill="rgba(255,255,255,0.025)"/>

  <!-- ── BADGE ── -->
  <rect x="90" y="85" width="${s.badge.length * 16 + 48}" height="46" rx="23"
        fill="rgba(0,0,0,0.25)" stroke="rgba(255,255,255,0.38)" stroke-width="1.5"/>
  <text x="${90 + (s.badge.length * 16 + 48) / 2}" y="116"
        font-family="Arial,sans-serif" font-size="21" font-weight="700" letter-spacing="2.5"
        fill="${s.textClr}" text-anchor="middle">${x(s.badge)}</text>

  <!-- ── TITLE LINE 1 ── -->
  <text x="90" y="235" font-family="Arial Black,Arial,sans-serif"
        font-size="74" font-weight="900" fill="${s.textClr}">${x(s.line1)}</text>

  <!-- ── TITLE LINE 2 (accent colour) ── -->
  <text x="90" y="335" font-family="Arial Black,Arial,sans-serif"
        font-size="74" font-weight="900" fill="${s.accent}">${x(s.line2)}</text>

  <!-- ── DESCRIPTION ── -->
  <text x="90" y="430" font-family="Arial,sans-serif" font-size="29" font-weight="400"
        fill="rgba(255,255,255,0.78)">${x(s.desc)}</text>

  <!-- ── PILLS ── -->
  ${pillsXml}

  <!-- ── PHONE BODY ── -->
  <rect x="${PH_X}" y="${PH_Y}" width="${PH_W}" height="${PH_H}" rx="${PH_R}"
        fill="#16162A"/>

  <!-- ── APP NAME WATERMARK ── -->
  <text x="${W / 2}" y="${H - 38}" font-family="Arial,sans-serif" font-size="25"
        font-weight="700" letter-spacing="1" fill="rgba(255,255,255,0.22)"
        text-anchor="middle">JEE NEET MOCK TEST</text>
</svg>`;
}

// ─── Phone frame decoration SVG (sits on top) ─────────────────────────────────
function frameSVG() {
  return `<svg width="${W}" height="${H}" xmlns="http://www.w3.org/2000/svg">

  <!-- Outer border -->
  <rect x="${PH_X}" y="${PH_Y}" width="${PH_W}" height="${PH_H}" rx="${PH_R}"
        fill="none" stroke="rgba(140,140,200,0.55)" stroke-width="2.5"/>

  <!-- Inner screen border -->
  <rect x="${SC_X}" y="${SC_Y}" width="${SC_W}" height="${SC_H}" rx="${SC_R}"
        fill="none" stroke="rgba(255,255,255,0.07)" stroke-width="1"/>

  <!-- Top notch bar -->
  <rect x="${PH_X + 1}" y="${PH_Y + 1}" width="${PH_W - 2}" height="${SC_PAD_T - 2}" rx="${PH_R}"
        fill="rgba(22,22,42,0.96)"/>

  <!-- Front camera -->
  <circle cx="${W / 2}" cy="${PH_Y + 27}" r="9"  fill="#0e0e1e"/>
  <circle cx="${W / 2}" cy="${PH_Y + 27}" r="5"  fill="#1a1a30"/>
  <circle cx="${W / 2}" cy="${PH_Y + 27}" r="2"  fill="rgba(60,60,120,0.6)"/>

  <!-- Bottom bar -->
  <rect x="${PH_X + 1}" y="${PH_Y + PH_H - SC_PAD_B + 2}" width="${PH_W - 2}" height="${SC_PAD_B - 3}" rx="${PH_R}"
        fill="rgba(22,22,42,0.96)"/>

  <!-- Home indicator -->
  <rect x="${W / 2 - 60}" y="${PH_Y + PH_H - 22}" width="120" height="5" rx="3"
        fill="rgba(160,160,200,0.5)"/>

  <!-- Volume buttons (left side) -->
  <rect x="${PH_X - 5}" y="${PH_Y + 155}" width="5" height="52" rx="3" fill="#252540"/>
  <rect x="${PH_X - 5}" y="${PH_Y + 222}" width="5" height="88" rx="3" fill="#252540"/>

  <!-- Power button (right side) -->
  <rect x="${PH_X + PH_W}" y="${PH_Y + 195}" width="5" height="68" rx="3" fill="#252540"/>
</svg>`;
}

// ─── Rounded-corner mask for screenshot ───────────────────────────────────────
function maskSVG() {
  return `<svg width="${SC_W}" height="${SC_H}" xmlns="http://www.w3.org/2000/svg">
  <rect width="${SC_W}" height="${SC_H}" rx="${SC_R}" ry="${SC_R}" fill="white"/>
</svg>`;
}

// ─── Generate one screenshot ──────────────────────────────────────────────────
async function generate(s) {
  const imgPath = path.join(__dirname, s.file);

  // 1. Resize screenshot to fit screen area
  const rawShot = await sharp(imgPath)
    .resize(SC_W, SC_H, { fit: 'cover', position: 'top' })
    .ensureAlpha()
    .toBuffer();

  // 2. Apply rounded-corner mask so screenshot edges match phone screen
  const roundedShot = await sharp(rawShot)
    .composite([{ input: Buffer.from(maskSVG()), blend: 'dest-in' }])
    .png()
    .toBuffer();

  // 3. Composite: bg → screenshot → phone frame decoration
  const outFile = path.join(OUT, s.out);
  await sharp(Buffer.from(bgSVG(s)))
    .composite([
      { input: roundedShot, top: SC_Y, left: SC_X },
      { input: Buffer.from(frameSVG()), top: 0, left: 0 },
    ])
    .png()
    .toFile(outFile);

  console.log(`  ✓  ${s.out}`);
}

// ─── Main ─────────────────────────────────────────────────────────────────────
(async () => {
  console.log('\nGenerating Play Store screenshots…\n');
  for (const s of SHOTS) {
    try {
      await generate(s);
    } catch (err) {
      console.error(`  ✗  ${s.out}  →  ${err.message}`);
    }
  }
  console.log(`\nDone! 8 PNGs saved to:  ${OUT}\n`);
})();
