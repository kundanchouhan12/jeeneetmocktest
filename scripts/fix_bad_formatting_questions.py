"""
fix_bad_formatting_questions.py — One-time Firestore cleanup

Scans ALL questions in the 'questions' collection and:
  1. Finds questions with literal \\n or \\t escape sequences in questionText/options/explanation
  2. Finds questions using \\ce{} mhchem LaTeX notation (unsupported by Android KaTeX)

For each bad question, AUTO-FIX in-place:
  - Strips literal \\n -> space, \\t -> space
  - Converts \\ce{formula} -> plain KaTeX text (e.g. MnO_4^{-})

Usage:
    python scripts/fix_bad_formatting_questions.py --dry-run
    python scripts/fix_bad_formatting_questions.py
"""

import argparse
import os
import re
import sys

import firebase_admin
from firebase_admin import credentials, firestore

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), 'serviceAccountKey.json')


def init_firebase():
    if firebase_admin._apps:
        return firestore.client()
    firebase_admin.initialize_app(credentials.Certificate(SERVICE_ACCOUNT_PATH))
    return firestore.client()


# ── Detectors ──────────────────────────────────────────────────────────────────

_LITERAL_NEWLINE = re.compile(r'\\n|\\t')
_CE_NOTATION = re.compile(r'\\ce\{[^}]*\}')


def needs_fix(q: dict) -> tuple:
    """Returns (needs_fix: bool, reasons: list[str])."""
    reasons = []
    fields = [q.get('questionText', ''), q.get('explanation', '')] + [str(o) for o in q.get('options', [])]
    full = ' '.join(fields)
    if _LITERAL_NEWLINE.search(full):
        reasons.append('literal_newline')
    if _CE_NOTATION.search(full):
        reasons.append('ce_notation')
    return bool(reasons), reasons


# ── Auto-fixers ────────────────────────────────────────────────────────────────

def _ce_replacer(m):
    inner = m.group(0)[4:-1]  # strip \ce{ and }
    # Convert plain subscript numbers: H2O -> H_2O, MnO4 -> MnO_4
    plain = re.sub(r'([A-Za-z)])(\d+)', r'\1_{\2}', inner)
    # Replace arrow
    plain = plain.replace('->', r'\rightarrow ')
    # Wrap in inline math if it has special chars
    if re.search(r'[_^{}+\-\\]', plain):
        return '$' + plain + '$'
    return plain


def fix_field(text: str) -> str:
    t = text.replace('\\n', ' ').replace('\\t', ' ')
    t = _CE_NOTATION.sub(_ce_replacer, t)
    t = re.sub(r'[ \t]{2,}', ' ', t)
    return t.strip()


def fix_question(q: dict) -> dict:
    """Returns Firestore update dict (only changed fields)."""
    updates = {}

    qt = q.get('questionText', '')
    fqt = fix_field(qt)
    if fqt != qt:
        updates['questionText'] = fqt

    exp = q.get('explanation', '')
    fexp = fix_field(exp)
    if fexp != exp:
        updates['explanation'] = fexp

    opts = [str(o) for o in q.get('options', [])]
    fopts = [fix_field(o) for o in opts]
    if fopts != opts:
        updates['options'] = fopts

    return updates


# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dry-run', action='store_true', help='Report without writing.')
    args = parser.parse_args()
    dry = args.dry_run

    print(f'\n{"DRY RUN" if dry else "LIVE FIX"} — Scanning questions for literal \\\\n and \\\\ce{{}} issues...\n')

    db = init_firebase()
    col = db.collection('questions')

    total = scanned = fixed = skipped = 0
    last_doc = None

    while True:
        q_ref = col.limit(500)
        if last_doc:
            q_ref = q_ref.start_after(last_doc)
        docs = list(q_ref.stream())
        if not docs:
            break

        for doc in docs:
            total += 1
            q = doc.to_dict()
            bad, reasons = needs_fix(q)
            if not bad:
                continue

            scanned += 1
            exam = q.get('examType', '?')
            subj = q.get('subject', '?')
            preview = q.get('questionText', '')[:80].replace('\n', ' ')
            print(f'  [{exam}/{subj}] {doc.id[:20]}... | {", ".join(reasons)}')
            print(f'    "{preview}"')

            updates = fix_question(q)
            if not updates:
                print(f'    ⚠️  No changes generated — skipping.')
                skipped += 1
                continue

            if dry:
                print(f'    → Would update: {list(updates.keys())}')
            else:
                col.document(doc.id).update(updates)
                fixed += 1
                print(f'    ✅ Fixed: {list(updates.keys())}')

        last_doc = docs[-1]
        if len(docs) < 500:
            break

    print(f'\n{"─" * 55}')
    print(f'Total scanned : {total}')
    print(f'Issues found  : {scanned}')
    if dry:
        print(f'Would fix     : {scanned - skipped}')
        print(f'\nRun without --dry-run to apply fixes.')
    else:
        print(f'Fixed         : {fixed}')
        print(f'Skipped       : {skipped}')
        print(f'\n✅ Cleanup complete.')


if __name__ == '__main__':
    main()
