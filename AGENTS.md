# 🤖 Project Knowledge & Memory - MockTestApp Automation

This document persists key architecture decisions, automation schedules, and pipeline rules across AI sessions.

---

## ⚡ Master Daily Automation (`scripts/run_daily_automation.py`)

The daily pipeline is orchestrated via `.github/workflows/daily_automation.yml` and runs automatically every night at **12:00 AM IST (18:30 UTC)**.

### Pipeline Execution Order:
1. **Web Question Ingestion (`scripts/web_question_ingestion.py`)**:
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
5. **Bi-Weekly Power 100 Rebuild (`scripts/build_power100_live.py`)**:
   - **Schedule**: Power 100 refreshes **bi-weekly (1st and 15th of each month)** instead of daily to preserve user test progress for 2 weeks.
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
