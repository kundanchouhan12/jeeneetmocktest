# 🤖 Project Knowledge & Memory - MockTestApp Automation

This document persists key architecture decisions, automation schedules, and pipeline rules across AI sessions.

---

## ⚡ Master Daily Automation (`scripts/run_daily_automation.py`)

The daily pipeline is orchestrated via `.github/workflows/daily_automation.yml` and runs automatically every night at **12:00 AM IST (18:30 UTC)**.

### Pipeline Execution Order:
1. **Web Question Ingestion (`scripts/web_question_ingestion.py` & `scripts/neet_web_question_ingestion.py`)**:
   - Extracts JEE (PCM) & NEET (PCB) questions from web educational sources.
   - **Sanitization**: Removes web noise, headers, footers, URLs, page numbers, and diagram dependencies.
   - **LaTeX Engine**: `repair_latex_json_escapes` left-to-right single pass scanner for mixed single/double backslashes; `wrap_bare_latex` for options math rendering.
   - **Dual-Pass Verification**: `verify_answer` re-solves questions independently before accepting. Mismatches are rejected.
2. **AI Question Generation Top-Up (`scripts/auto_question_pipeline.py`)**:
   - Generates additional high-yield questions using Groq (`reasoning_effort: "low"`).
3. **Deduplication & Corruption Audit (`scripts/purge_duplicate_questions.py`, `scripts/cleanup_corrupted_questions.py`)**:
   - Audits normalized text fingerprints to ensure 100% uniqueness in Firestore.
4. **Daily Vault Scheduler (`scripts/vault_scheduler.py`)**:
   - Schedules 30 questions for JEE and 30 for NEET daily for the next day.
5. **Weekly Power 100 Rebuild (`scripts/build_power100_live.py`)**:
   - **Schedule**: Power 100 refreshes **every 7 days (every Monday)** to preserve user test progress across the week while refreshing regularly.
   - **Manual Override**: `python scripts/run_daily_automation.py --force-power100` forces an immediate rebuild.
6. **App Auto-Sync**:
   - Bumps `metadata/question_bank` version counter so mobile clients auto-sync.

---

## 🛠️ GitHub Actions Workflow (`.github/workflows/daily_automation.yml`)

- **Secrets Required**:
  - `GROQ_API_KEY`
  - `FIREBASE_SERVICE_ACCOUNT`
- **Manual Trigger**:
  ```bash
  gh workflow run daily_automation.yml
  ```

---

## 📚 Exam & Syllabus Coverage
- **JEE (PCM)**: Physics, Chemistry, Maths (22 + 22 + 15 chapters).
- **NEET (PCB)**: Physics, Chemistry, Biology (22 + 22 + 15 chapters).
- **Official Chapters**: All questions are strictly mapped to Class 11 & Class 12 NTA NCERT chapters in `OFFICIAL_CHAPTERS`.

---

## 💡 Core Architecture Rationale & Play Store User Feedback

- **The Problem Solved**: Users previously gave 1-star reviews ("the same question is being repeated again and again") because of legacy recirculation logic that reshuffled exhausted questions, and a vault scheduler that lacked deduplication.
- **The Permanent Fix**:
  - **Daily Fresh Supply**: `web_question_ingestion.py` + `auto_question_pipeline.py` automatically inject new unique questions every single night into Firestore so the bank never runs out.
  - **Zero Duplicates Policy**: `purge_duplicate_questions.py` enforces normalized string fingerprint matching across all 3,300+ questions.
  - **Progress Preservation (7-Day Cycle)**: Power 100 refreshes weekly (every Monday) so users have 7 days of stable test progress without daily resets.
  - **Full Mock 7-Day Anti-Repetition**: Full-length 180 min (JEE) / 200 min (NEET) simulations and weekly mocks use ISO 8601 week keys (`YYYY-'W'ww`) with `pickWithSeenTracking` — questions never repeat week-over-week until the pool is fully exhausted.

---

## 🎯 Student Engagement & Retention Features (Added 2026-09-18)

### 1. ⏱️ Exam Countdown Banner (`MainActivity.kt`, `PrefManager.kt`)
- **Firestore Sourced**: Exam dates stored in `metadata/exam_dates` (`jee_main_session1`, `neet_ug`), cached in `PrefManager`.
- **120-Day Visibility**: Banner auto-displays only within 120 days of exam date; auto-hides after the date passes or if > 120 days away.
- **3 Urgency Tiers**: Critical (<15 days, red gradient), Urgent (15–45 days, amber), Standard (46–120 days, blue gradient).

### 2. 🧠 "Revise My Mistakes" Error Notebook (`ReviseMyMistakesActivity.kt`, `MockTestRepository.kt`)
- **Off-Main-Thread Processing**: Aggregation runs on `Dispatchers.IO` in `MockTestRepository.getWrongQuestions()`.
- **Deduplication by `Question.id`**: Unique mistakes collected across all completed test history.
- **Excludes Unattempted**: Only genuine wrong attempts (`ans != null && ans != correctOptionIndex`) are tracked.
- **Bite-Sized Sessions**: Capped at 30 questions max per revision session with weakest-subject focus mode.

---

## 🚨 Mandatory Pipeline Verification Rules (Added 2026-09-18)

> **Incident:** NEET daily vault showed only 7/30 questions because scripts wrote `correctOption`
> but `QuestionSyncManager.parseQuestion()` read `correctOptionIndex` — missing field caused
> silent `null` returns, dropping 23 questions. Full checklist: `docs/PIPELINE_VERIFICATION_CHECKLIST.md`

### Before declaring ANY pipeline change "verified", you MUST check:

1. **Field Name Cross-Check** — Grep the exact Firestore field names written by Python scripts and cross-reference against what `QuestionSyncManager.kt` and `Power100SyncManager.kt` `parseQuestion()` reads. Both `correctOption` AND `correctOptionIndex` must be written by every ingestion script.

2. **Parseable Count, Not Written Count** — After vault scheduling, verify the number of **parseable** vault docs (all 6 required fields present: `examType`, `subject`, `chapter`, `questionText`, `options[4]`, `correctOptionIndex`) equals 30 — not just that 30 docs were written.

3. **No Module-Level Firebase Init** — Any script imported by `run_daily_automation.py` must not connect to Firestore at import time. Firebase init must be inside a function, never at module top-level.

4. **Dry-Run Completes Clean** — Run `python scripts/run_daily_automation.py --dry-run` and confirm no `ImportError`, no `FileNotFoundError`, and final output is `🎉 Daily Automation Completed Successfully!`

5. **Vault Count Sanity** — JEE and NEET vaults for tomorrow's date must each have ≥ 27 questions (30 is target). Below 27 = bank too thin, increase ingestion count.

6. **Daily Vault 1-Day Cadence Invariant**:
   - **Incident Fixed**: Daily Vault previously locked local DB for 7 days (`today < nextRefreshDate` with 7-day offset copied from Power 100), causing users to see the same questions for a week.
   - **Mandatory Cadence**: Daily Vault MUST check daily freshness (`snapshotDate == today`). Its refresh offset MUST be 1 day (`+1 day`), NEVER 7 days.
   - **Power 100 Cadence**: Power 100 stays on a 7-day weekly refresh cycle (refreshes every Monday). Do not leak Power 100's 7-day freeze logic into Daily Vault.

### Firestore Field Invariant (must never break):
All ingestion scripts (`web_question_ingestion.py`, `neet_web_question_ingestion.py`, `auto_question_pipeline.py`) and the vault scheduler MUST always write BOTH `correctOption` and `correctOptionIndex` to every Firestore document, set to the same integer value.

---

## 🛡️ Production Incidents & Architectural Fixes (2026-09-26)

### 1. ⚡ Groq 429 Rate-Limit Hardening (Workflow #34 Root Cause & Resolution)
- **Incident**: Workflow #34 hung for 6 hours until GitHub Actions cancelled it. Ingestion repeatedly received HTTP 429 with large upstream `Retry-After` headers (307s, 591s, 1488s = ~25 min) and slept synchronously in uncapped `time.sleep()` loops, preventing execution from ever reaching Daily Vault Scheduling.
- **The Permanent Fix**:
  - `MAX_RETRY_AFTER_SECS = 45`: If upstream requests a wait $> 45\text{s}$, `_post_groq()` skips further retries immediately with **zero sleep**, returning `""` gracefully.
  - `max_retries`: Reduced from 5 to 3.
  - **Graceful Non-fatal Skips**: Callers (`fetch_web_questions_for_chapter`, `generate_questions`, `verify_answer`) treat `""` as empty output, skip upload without writing partial/corrupt data, and allow the pipeline to proceed directly to Daily Vault scheduling in $< 2\text{--}3$ minutes.

### 2. 🧠 3-Tier Freshness & 30-Day LRU Anti-Repeat Cooldown (`scripts/vault_scheduler.py`)
- **The Problem Solved**: Previously, `recently_used_ids` had no time dimension (a question used yesterday was in the same bucket as one used 6 months ago). When unvaulted supply ran low, it sampled uniformly at random from all stale questions, creating a risk that questions from yesterday/last week would repeat.
- **The 3-Tier Policy**:
  - **Tier 1 (Fresh / Never Used)**: Questions that have never appeared in any historical Daily Vault. Selected first (highest priority).
  - **Tier 2 (Cooldown Cleared)**: Questions whose latest `vaultDate` is strictly **MORE than 30 days** before `target_date` ($(\text{target\_date} - \text{last\_vault\_date}).\text{days} > 30$). Selected using **Least-Recently-Used (LRU)** order (oldest served questions chosen first).
  - **Tier 3 (Active Cooldown)**: Questions served within the last 30 days ($\le 30\text{ days}$). **Strictly blacklisted and never selected**.
- **Strict Contract Guard**: If $\text{len}(\text{Tier 1}) + \text{len}(\text{Tier 2}) < 30$, raises `VaultContractError` and halts rather than violating the 30-day cooldown policy.
- **Reservoir Capacity**:
  - JEE Main: ~2,000 valid questions (requires 900 for 30d $\to$ 2.2x buffer).
  - NEET UG: ~1,300 valid questions (requires 900 for 30d $\to$ 1.4x buffer).
  - Both exams are 100% mathematically safe from repeating questions for a full month even during temporary ingestion downtime.



