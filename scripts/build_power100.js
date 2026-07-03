// build_power100.js — Builds jee_power100.json from extracted PYQ files.
// Run: node scripts/build_power100.js
// Output: scripts/jee_power100.json (100 questions: 34 Physics + 33 Chem + 33 Maths)

const fs   = require('fs');
const path = require('path');

const EXTRACTED = path.join(__dirname, 'extracted');
const OUT       = path.join(__dirname, 'jee_power100.json');

const TARGETS = { Physics: 31, Chemistry: 36, Maths: 33 };
// No more than this many questions from one chapter
const MAX_PER_CHAPTER = 4;

// ─── Text utilities ───────────────────────────────────────────────────────────

function countWords(s) {
  return (s.match(/[a-zA-Z]{2,}/g) || []).length;
}

function clean(s) {
  return s.replace(/\s+/g, ' ')
          .replace(/#PaperPhodnaHai/gi, '')
          .replace(/Click here to download/gi, '')
          .trim();
}

// ─── Core parser ─────────────────────────────────────────────────────────────

/**
 * Returns { questionText, options:[4] } or null.
 * We look for the LAST run of \n(1)..\n(2)..\n(3)..\n(4).. in the text.
 */
function extractOptions(raw) {
  // Normalise line endings and remove the first line (date/shift header)
  const lines = raw.replace(/\r\n/g, '\n').split('\n');
  let body = lines.slice(1).join('\n'); // drop "16 March (Shift 1) - Single Correct"

  // Remove trailing chapter/promo lines
  body = body.replace(/#PaperPhodnaHai[\s\S]*/gi, '')
             .replace(/Click here to download[\s\S]*/gi, '')
             .trim();

  // Find every occurrence of \n(1) \n(2) \n(3) \n(4) with their positions
  const re   = /\n\(([1-4])\)\s*/g;
  const hits = [];
  let m;
  while ((m = re.exec(body)) !== null) {
    hits.push({ n: Number(m[1]), start: m.index, textStart: m.index + m[0].length });
  }

  // Locate the LAST contiguous 1-2-3-4 block
  let base = -1;
  for (let i = hits.length - 4; i >= 0; i--) {
    if (hits[i].n === 1 && hits[i+1].n === 2 && hits[i+2].n === 3 && hits[i+3].n === 4) {
      base = i;
      break;
    }
  }
  if (base === -1) return null;

  const h = hits;
  const opts = [
    body.slice(h[base  ].textStart, h[base+1].start),
    body.slice(h[base+1].textStart, h[base+2].start),
    body.slice(h[base+2].textStart, h[base+3].start),
    base+4 < h.length ? body.slice(h[base+3].textStart, h[base+4].start)
                      : body.slice(h[base+3].textStart),
  ].map(s => clean(s));

  const qText = clean(body.slice(0, h[base].start));
  return { questionText: qText, options: opts };
}

/**
 * True if a string has any readable content — alphabetic words OR numbers with units.
 */
function hasContent(s) {
  return countWords(s) > 0 || /\d[\d.,×⁻\-\s]*(?:[a-zA-Z]+)?/.test(s.trim());
}

/**
 * Readability score — higher is better.
 * Tier 1 (≥30): fully-text conceptual options
 * Tier 2 (10–29): mixed text + numbers
 * Tier 3 (1–9): mostly numeric options but question text is readable
 * Rejected (−1): empty options OR question text < 3 words
 */
function score(qText, opts) {
  const qWords  = countWords(qText);
  const optWords = opts.map(countWords);
  const avgOpt   = optWords.reduce((a, b) => a + b, 0) / 4;

  // Question text completely unreadable
  if (qWords < 3) return -1;

  // All options empty / symbols only
  if (opts.every(o => !hasContent(o))) return -1;

  // Bonus for statement/assertion/conceptual style
  const isConceptual = /statement|assertion|reason|following|correct|incorrect|true|false|match|given below|which of/i.test(qText + opts.join(' '));
  const textBonus = isConceptual ? 20 : 0;

  return qWords + avgOpt * 2 + textBonus;
}

// ─── Difficulty heuristic ─────────────────────────────────────────────────────

function difficulty(qText, chapter) {
  const hardTopics = /rotational|induction|wave optic|nuclear|coordinat compound|electrochemi|definite integr|differential eq|vector 3d|three dim/i;
  const easyTopics = /units|dimensions|basic concept|mole concept|sets|relation|sequence|binomial/i;
  if (hardTopics.test(chapter)) return 'Hard';
  if (easyTopics.test(chapter)) return 'Easy';
  if (qText.length > 400) return 'Hard';
  if (qText.length < 150) return 'Easy';
  return 'Medium';
}

// ─── Process one subject directory ────────────────────────────────────────────

function processSubject(subject) {
  const dir   = path.join(EXTRACTED, subject);
  const files = fs.readdirSync(dir).filter(f => f.endsWith('.json'));
  const pool  = [];
  const seen  = new Set(); // deduplicate by (chapter, questionText)

  for (const file of files) {
    let data;
    try { data = JSON.parse(fs.readFileSync(path.join(dir, file), 'utf-8')); }
    catch { continue; }

    const chapter = (data.chapter || file.replace(/\.json$/, '').replace(/_/g, ' ')).trim();
    const year    = data.year || 2021;

    for (const q of (data.questions || [])) {
      const raw = q.raw_text || '';
      // Only MCQ (answer 1–4)
      if (!['1','2','3','4'].includes(String(q.answer_key))) continue;
      // Only "Single Correct" rows
      if (!/Single Corr/i.test(raw)) continue;

      const parsed = extractOptions(raw);
      if (!parsed) continue;

      const { questionText, options } = parsed;
      const sc = score(questionText, options);
      if (sc < 0) continue;

      // Dedup by first 80 chars of question text
      const key = chapter + '|' + questionText.slice(0, 80);
      if (seen.has(key)) continue;
      seen.add(key);

      pool.push({
        subject,
        chapter,
        difficulty: difficulty(questionText, chapter),
        questionText,
        options,
        correctOptionIndex: Number(q.answer_key) - 1,
        explanation: `Correct answer: Option ${q.answer_key}. (${chapter}, JEE ${year})`,
        _score: sc,
      });
    }
  }

  return pool;
}

// ─── Selection: balanced across chapters ─────────────────────────────────────

function select(pool, n) {
  // Sort by score descending
  pool.sort((a, b) => b._score - a._score);

  const chapterCount = {};
  const selected     = [];

  for (const q of pool) {
    if (selected.length >= n) break;
    const cc = chapterCount[q.chapter] || 0;
    if (cc >= MAX_PER_CHAPTER) continue;
    chapterCount[q.chapter] = cc + 1;
    selected.push(q);
  }

  // If we still need more, relax the per-chapter cap
  if (selected.length < n) {
    for (const q of pool) {
      if (selected.length >= n) break;
      if (!selected.includes(q)) selected.push(q);
    }
  }

  return selected.slice(0, n);
}

// ─── Main ─────────────────────────────────────────────────────────────────────

function main() {
  const all = [];

  for (const [subject, target] of Object.entries(TARGETS)) {
    console.log(`\nProcessing ${subject}...`);
    const pool     = processSubject(subject);
    console.log(`  Found ${pool.length} readable MCQ candidates`);
    const selected = select(pool, target);
    console.log(`  Selected ${selected.length}/${target}`);

    // Log chapter distribution
    const dist = {};
    selected.forEach(q => { dist[q.chapter] = (dist[q.chapter] || 0) + 1; });
    Object.entries(dist).sort((a,b) => b[1]-a[1]).forEach(([ch, cnt]) => {
      console.log(`    ${cnt}x ${ch}`);
    });

    all.push(...selected);
  }

  // Assign positions 1–100 and strip internal fields
  const output = all.map(({ _score, ...q }, i) => ({ ...q, position: i + 1 }));

  // Remove position (the update_power100.py script uses array index for position)
  const clean = output.map(({ position, ...q }) => q);

  fs.writeFileSync(OUT, JSON.stringify(clean, null, 2), 'utf-8');
  console.log(`\n✓ Wrote ${clean.length} questions to ${OUT}`);

  // Stats
  const subCounts = {};
  clean.forEach(q => { subCounts[q.subject] = (subCounts[q.subject] || 0) + 1; });
  console.log('Subject counts:', subCounts);

  const diffCounts = {};
  clean.forEach(q => { diffCounts[q.difficulty] = (diffCounts[q.difficulty] || 0) + 1; });
  console.log('Difficulty counts:', diffCounts);
}

main();
