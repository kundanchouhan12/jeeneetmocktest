# 🔍 Pipeline End-to-End Verification Checklist

> **Why this exists:** On 2026-09-18 the NEET daily vault showed only 7/30 questions.
> Root cause: Python scripts wrote `correctOption` but the Android app's `parseQuestion()`
> read `correctOptionIndex` — missing field silently returned `null`, dropping 23 questions.
> Full verification was declared complete without cross-checking field names against the app model.
>
> **Rule:** After ANY change to ingestion scripts, vault scheduler, or Android parsing —
> run through every item below before calling it "verified".

---

## ✅ Checklist — Run Before Every "Verified" Declaration

### 1. Field Name Cross-Check (MOST CRITICAL)

Before any pipeline change, explicitly verify:

| Script | Field(s) Written to Firestore | Android Reads |
|--------|-------------------------------|---------------|
| `web_question_ingestion.py` | `correctOption` + `correctOptionIndex` | `correctOptionIndex` → fallback `correctOption` |
| `neet_web_question_ingestion.py` | `correctOption` + `correctOptionIndex` | `correctOptionIndex` → fallback `correctOption` |
| `auto_question_pipeline.py` | `correctOption` + `correctOptionIndex` | `correctOptionIndex` → fallback `correctOption` |
| `vault_scheduler.py` | `correctOption` + `correctOptionIndex` | `correctOptionIndex` → fallback `correctOption` |
| `build_power100_live.py` | `correctOption` + `correctOptionIndex` | `correctOptionIndex` → fallback `correctOption` |

**Check:** grep the exact field names in both the Python script and `QuestionSyncManager.kt`/`Power100SyncManager.kt` `parseQuestion()` — they must align.

---

### 2. Firestore Write Count vs. Parseable Count

> The vault may write 30 docs but app sees fewer if `parseQuestion()` returns `null`.

After scheduling a vault:
- [ ] Query Firestore: count vault docs for that date/exam
- [ ] Confirm **every doc** has `correctOptionIndex` (not just `correctOption`)
- [ ] Confirm `options` array has exactly 4 non-empty strings
- [ ] Confirm `examType`, `subject`, `chapter`, `questionText` are non-null

```bash
# Quick Firestore spot-check (from scripts/ directory)
python - <<'EOF'
import firebase_admin
from firebase_admin import credentials, firestore
firebase_admin.initialize_app(credentials.Certificate('serviceAccountKey.json'))
db = firestore.client()
docs = db.collection('questions').where('vaultDate','==','YYYY-MM-DD').where('examType','==','NEET').get()
issues = [d.id for d in docs if d.to_dict().get('correctOptionIndex') is None]
print(f"Total: {len(docs)}, Missing correctOptionIndex: {len(issues)}")
if issues: print("Problem docs:", issues[:5])
EOF
```

---

### 3. Android `parseQuestion()` Null-Return Audit

Both parsers in the Android app **silently return `null`** and discard the question if:
- `examType` is missing
- `subject` is missing
- `chapter` is missing
- `questionText` is missing
- `options` list is not exactly 4 strings
- **`correctOptionIndex` AND `correctOption` are both missing**

Verify these 6 fields exist in every Firestore document before calling it done.

**Files to check:**
- `app/src/main/java/com/jeeneet/mocktest/data/repository/QuestionSyncManager.kt` (lines 269–297)
- `app/src/main/java/com/jeeneet/mocktest/data/repository/Power100SyncManager.kt` (lines 152–175)

---

### 4. Module-Level Side Effects

Any script imported by `run_daily_automation.py` must **not** connect to Firebase or exit at import time.

- [ ] `cleanup_corrupted_questions.py` — Firebase init is inside `cleanup()`, not module-level ✅
- [ ] `purge_duplicate_questions.py` — Firebase init is inside `purge_duplicates()` / `main()` ✅
- [ ] `vault_scheduler.py` — Firebase init is inside `main()` ✅
- [ ] `build_power100_live.py` — Firebase init is inside `init_firebase()` called from `main()` ✅
- [ ] `auto_question_pipeline.py` — Firebase init is inside `init_firebase()` ✅

---

### 5. GitHub Actions Workflow Health

- [ ] `daily_automation.yml` cron is `30 18 * * *` (= 12:00 AM IST)
- [ ] Secrets `GROQ_API_KEY` and `FIREBASE_SERVICE_ACCOUNT` are set in repo settings
- [ ] `requirements.txt` lists every pip dependency used (currently: `firebase-admin`, `requests`)
- [ ] Groq API is called via `requests` (REST) — **no** `groq` Python package needed
- [ ] The workflow step exits **non-zero** on any failed sub-step (`run_daily_automation.py` does this)

---

### 6. Vault Count Sanity

After nightly run, confirm in Firestore:

| Vault | Expected | Acceptable minimum |
|-------|----------|--------------------|
| JEE vault (tomorrow's date) | 30 | 27 (pool running thin) |
| NEET vault (tomorrow's date) | 30 | 27 (pool running thin) |

If either exam shows < 27, the question bank is too thin — increase ingestion count per subject.

---

### 7. Dry-Run Test Before Any Major Script Change

```bash
cd scripts
python run_daily_automation.py --dry-run
```

Expected output:
- ✅ No `ImportError` or `FileNotFoundError` at import time
- ✅ Web ingestion dry-run prints sample question with `correctOption` AND `correctOptionIndex`
- ✅ Vault scheduler dry-run prints "Would write N vault questions"
- ✅ Final line: `🎉 Daily Automation Completed Successfully!`

---

## 📋 What Was Fixed (2026-09-18)

| File | Fix Applied |
|------|-------------|
| `QuestionSyncManager.kt` | 4-level fallback: `correctOptionIndex as Long → correctOption as Long → correctOptionIndex as Int → correctOption as Int` |
| `Power100SyncManager.kt` | Same 4-level fallback |
| `neet_web_question_ingestion.py` | `item["correctOption"] = corr; item["correctOptionIndex"] = corr` in fetch loop |
| `web_question_ingestion.py` | Same dual-field set |
| `auto_question_pipeline.py` | Same dual-field set in `generate_questions()` |
| `vault_scheduler.py` | Normalize + write both fields when copying questions into vault docs |
| `build_power100_live.py` | Read either field, write both to Power 100 payload |
| `cleanup_corrupted_questions.py` | Moved Firebase init from module-level into `cleanup()` to prevent crash-on-import |

---

## 🚨 The Golden Rule

> **Never declare a pipeline change "verified" without checking:**
> 1. That the Python field name matches what `parseQuestion()` in Kotlin reads
> 2. That the **count of parseable vault docs** (not just written docs) equals 30
