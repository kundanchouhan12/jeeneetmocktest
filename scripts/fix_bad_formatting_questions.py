"""
fix_bad_formatting_questions.py — Firestore formatting cleanup.

Scans questions in the 'questions' collection and repairs formatting issues
that cause raw markup to show in the Android app:
  1. Literal \\n / \\t escape sequences in questionText/options/explanation.
  2. mhchem \\ce{} notation (unsupported by shipped Android JLaTeXMath builds).
  3. Bare LaTeX / chemical-formula tokens missing $...$ delimiters.

For each affected question, AUTO-FIX in place (update, never delete):
  - Strips literal \\n -> space, \\t -> space.
  - Converts \\ce{formula} -> plain KaTeX (e.g. \\ce{K2Cr2O7} -> K_{2}Cr_{2}O_{7}),
    preserving any surrounding $...$ / \\(..\\) delimiters.
  - Wraps bare LaTeX / chemical tokens with delimiters so MathRenderer.kt renders them.

Can run standalone (one-time backfill) or as a step inside run_daily_automation.py
via run_formatting_fix(db, dry_run, all_docs=...).

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


from web_question_ingestion import wrap_inline_latex, wrap_bare_latex, strip_ce_notation


# ── Detectors ─────────────────

# Strip literal \n / \t escape artifacts WITHOUT eating LaTeX commands that start
# with n or t (\theta, \times, \text, \tan, \nu, \nabla, \neq, ...). A real LaTeX
# command is always \ + a letter. Do not use a generic lowercase lookahead:
# prose such as ``\tcol2`` is an escape artifact, while known n/t commands must
# remain intact.
# A literal escape artifact is ambiguous with a LaTeX command prefix: ``\\t``
# can mean a tab artifact, while ``\\to`` / ``\\text`` / ``\\theta`` are valid
# commands. Keep a shared allowlist of common KaTeX/LaTeX commands beginning with
# n/t; the regex consumes only the backslash plus the artifact letter, never the
# command text. This prevents ``\\to`` from becoming ``o``.
_LATEX_NT_COMMANDS = (
    't', 'tan', 'tanh', 'text', 'textbf', 'textit', 'textrm', 'textsf', 'texttt',
    'theta', 'thetas', 'times', 'tilde', 'top', 'to', 'today', 'triangle',
    'tau', 'nabla', 'natural', 'nearrow', 'neg', 'neq', 'newcommand', 'nexists',
    'ni', 'nmid', 'not', 'nu', 'nwarrow', 'nolinebreak', 'nolimits',
)
_LATEX_COMMANDS = _LATEX_NT_COMMANDS + (
    'frac', 'sqrt', 'Delta', 'cdot', 'land', 'lor', 'rightarrow', 'leftarrow',
    'leftrightarrow', 'mathrm', 'mathbf', 'mathbb', 'mu', 'pi', 'alpha', 'beta',
    'gamma', 'lambda', 'sigma', 'omega', 'leq', 'geq', 'in', 'partial', 'sum',
)
_LATEX_NT_COMMAND_PATTERN = '|'.join(sorted(_LATEX_NT_COMMANDS, key=len, reverse=True))
_LITERAL_NL_TAB = re.compile(
    rf'\\(?!(?:{_LATEX_NT_COMMAND_PATTERN})(?![A-Za-z]))[nt]'
)
# Legacy JSON/Python decoding can leave ``\\`` followed by an actual tab/newline
# before the rest of a LaTeX command (for example ``\\<TAB>ext{m}``). Restore
# only commands explicitly listed above; unknown artifacts are intentionally left
# untouched rather than guessed.
_LATEX_COMMAND_TAIL_PATTERN = '|'.join(sorted(
    (command[1:] for command in _LATEX_COMMANDS if len(command) > 1),
    key=len, reverse=True,
))
_LEGACY_TAIL_PATTERN = _LATEX_COMMAND_TAIL_PATTERN
_LEGACY_N_TAIL_PATTERN = _LATEX_COMMAND_TAIL_PATTERN
_LEGACY_T_COMMAND = re.compile(
    rf'\\\t(?=(?:{_LEGACY_TAIL_PATTERN})(?![A-Za-z]))'
)
_LEGACY_N_COMMAND = re.compile(
    rf'\\\n(?=(?:{_LEGACY_N_TAIL_PATTERN})(?![A-Za-z]))'
)


def _restore_allowlisted_legacy_commands(t: str) -> str:
    t = _LEGACY_T_COMMAND.sub(r'\\t', t)
    return _LEGACY_N_COMMAND.sub(r'\\n', t)


def _strip_literal_escapes(t: str) -> str:
    # Restore only allowlisted legacy ``backslash + actual whitespace`` commands.
    # Unknown escape artifacts remain untouched. Then remove genuine two-character
    # ``\\n``/``\\t`` separators without truncating valid LaTeX commands.
    t = _restore_allowlisted_legacy_commands(t)
    return _LITERAL_NL_TAB.sub(' ', t)


# ── Auto-fixers ────────

# mhchem \ce{...} is expanded to plain KaTeX via the shared strip_ce_notation(),
# which PRESERVES any existing $...$ / \(..\) delimiters instead of blindly
# re-wrapping (the old local _ce_replacer produced broken double-delimited
# "$ $...$ $" strings on already-wrapped options like the vault screenshot).

def fix_field_prose(text: str) -> str:
    if not text:
        return text
    t = _strip_literal_escapes(text)
    t = strip_ce_notation(t)
    t = wrap_inline_latex(t)
    t = re.sub(r'[ \t]{2,}', ' ', t)
    return t.strip()


def fix_field_option(text: str) -> str:
    if not text:
        return text
    t = _strip_literal_escapes(text)
    t = strip_ce_notation(t)
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


# ── Pipeline entry point ──────────────────

def run_formatting_fix(db, dry_run: bool = False, all_docs=None) -> tuple[int, set]:
    """Scans the question bank and repairs formatting IN PLACE: strips literal
    \\n/\\t, expands mhchem \\ce{...} to plain KaTeX, and wraps bare LaTeX/chemical
    tokens with delimiters so MathRenderer.kt renders them.

    Fixes are UPDATES, never deletes, so this also heals already-published Daily
    Vault docs without shrinking the vault (safe under the exact-30 vault contract).

    Meant to run inside run_daily_automation.py AFTER dedup/cleanup and BEFORE
    vault scheduling, so the vault only ever copies already-clean source questions.
    Accepts the shared `all_docs` snapshot to avoid an extra full-collection read.

    Returns (fixed_count, changed_ids).
    """
    print(f'\n🧴 Formatting Auto-Fix (\\ce / \\n / bare LaTeX) — {"DRY RUN" if dry_run else "LIVE"}...', flush=True)

    if all_docs is None:
        try:
            all_docs = db.collection('questions').get()
        except Exception as e:
            print(f"  ⚠️ Could not fetch questions for formatting fix: {e}", flush=True)
            return 0, set()

    total = 0
    changed_ids: set = set()
    batch = db.batch() if not dry_run else None
    batch_count = 0
    shown = 0

    for doc in all_docs:
        total += 1
        q = doc.to_dict() or {}
        updates = fix_question(q)
        if not updates:
            continue

        changed_ids.add(doc.id)
        if shown < 10:
            shown += 1
            exam = q.get('examType', '?')
            subj = q.get('subject', '?')
            vault = ' [VAULT]' if q.get('isDailyVault') is True else ''
            preview = updates.get('questionText', q.get('questionText', ''))[:80].replace('\n', ' ')
            print(f"  [{exam}/{subj}]{vault} {doc.id[:24]} | Updated: {list(updates.keys())}", flush=True)
            print(f'    Preview: "{preview}"', flush=True)

        if not dry_run:
            batch.update(doc.reference, updates)
            batch_count += 1
            if batch_count >= 400:
                batch.commit()
                print(f'  ⚡ Committed batch of {batch_count} formatting fixes.', flush=True)
                batch = db.batch()
                batch_count = 0

    if not dry_run and batch_count > 0:
        batch.commit()
        print(f'  ⚡ Committed final batch of {batch_count} formatting fixes.', flush=True)

    print(f'  • Scanned {total} docs. {"Would fix" if dry_run else "Fixed"} {len(changed_ids)}.', flush=True)
    return len(changed_ids), changed_ids


# ── Standalone / one-time CLI ─────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dry-run', action='store_true', help='Report without writing.')
    args = parser.parse_args()

    print(f'\n{"DRY RUN" if args.dry_run else "LIVE FIX"} — Scanning questions for formula formatting & LaTeX wrapping...\n', flush=True)

    db = init_firebase()
    if not db:
        print("ERROR: Could not connect to Firestore.", flush=True)
        return

    run_formatting_fix(db, dry_run=args.dry_run)

    if args.dry_run:
        print('\nRun without --dry-run to apply fixes to Firestore.', flush=True)
    else:
        print('\n✅ Firestore cleanup complete.', flush=True)


if __name__ == '__main__':
    main()
