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


from web_question_ingestion import wrap_inline_latex, wrap_bare_latex


# ── Detectors ──────────────────────────────────────────────────────────────────

_LITERAL_NEWLINE = re.compile(r'\\n|\\t')
_CE_NOTATION = re.compile(r'\\ce\{[^}]*\}')


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


def fix_field_prose(text: str) -> str:
    if not text:
        return text
    t = text.replace('\\n', ' ').replace('\\t', ' ')
    t = _CE_NOTATION.sub(_ce_replacer, t)
    t = wrap_inline_latex(t)
    t = re.sub(r'[ \t]{2,}', ' ', t)
    return t.strip()


def fix_field_option(text: str) -> str:
    if not text:
        return text
    t = text.replace('\\n', ' ').replace('\\t', ' ')
    t = _CE_NOTATION.sub(_ce_replacer, t)
    t = wrap_bare_latex(t)
    t = re.sub(r'[ \t]{2,}', ' ', t)
    return t.strip()


def fix_question(q: dict) -> dict:
    """Returns Firestore update dict (only changed fields)."""
    updates = {}

    qt = q.get('questionText', '')
    fqt = fix_field_prose(qt)
    if fqt != qt:
        updates['questionText'] = fqt

    exp = q.get('explanation', '')
    fexp = fix_field_prose(exp)
    if fexp != exp:
        updates['explanation'] = fexp

    opts = [str(o) for o in q.get('options', [])]
    fopts = [fix_field_option(o) for o in opts]
    if fopts != opts:
        updates['options'] = fopts

    return updates


# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dry-run', action='store_true', help='Report without writing.')
    args = parser.parse_args()
    dry = args.dry_run

    print(f'\n{"DRY RUN" if dry else "LIVE FIX"} — Scanning questions for formula formatting & LaTeX wrapping...\n', flush=True)

    db = init_firebase()
    if not db:
        print("ERROR: Could not connect to Firestore.", flush=True)
        return

    col = db.collection('questions')

    total = scanned = fixed = 0
    subjects = ["Chemistry", "Physics", "Maths", "Biology"]

    for subj in subjects:
        print(f"\n📚 Fetching questions for Subject: {subj}...", flush=True)
        docs = list(col.where('subject', '==', subj).get())
        print(f"  Fetched {len(docs)} documents for {subj}. Processing...", flush=True)

        batch = db.batch() if not dry else None
        batch_count = 0

        for doc in docs:
            total += 1
            q = doc.to_dict()
            updates = fix_question(q)
            if not updates:
                continue

            scanned += 1
            if scanned <= 10 or dry:
                exam = q.get('examType', '?')
                preview = updates.get('questionText', q.get('questionText', ''))[:80].replace('\n', ' ')
                print(f'  [{exam}/{subj}] {doc.id[:20]}... | Updated: {list(updates.keys())}', flush=True)
                print(f'    Preview: "{preview}"', flush=True)

            if not dry:
                batch.update(doc.reference, updates)
                batch_count += 1
                fixed += 1

                if batch_count >= 400:
                    batch.commit()
                    print(f'  ⚡ Committed batch of {batch_count} updates for {subj}.', flush=True)
                    batch = db.batch()
                    batch_count = 0

        if not dry and batch_count > 0:
            batch.commit()
            print(f'  ⚡ Committed final batch of {batch_count} updates for {subj}.', flush=True)

    print(f'\n{"─" * 55}', flush=True)
    print(f'Total scanned : {total}', flush=True)
    print(f'Found to fix  : {scanned}', flush=True)
    if dry:
        print(f'\nRun without --dry-run to apply fixes to Firestore.', flush=True)
    else:
        print(f'Total fixed   : {fixed}', flush=True)
        print(f'\n✅ Firestore cleanup complete.', flush=True)


if __name__ == '__main__':
    main()
