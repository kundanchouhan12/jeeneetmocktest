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
3b. **Formatting Auto-Fix (`scripts/fix_bad_formatting_questions.py` → `run_formatting_fix`)**:
   - Runs AFTER dedup/cleanup and BEFORE vault scheduling so the vault only ever copies already-clean source questions.
   - **In-place UPDATE, never delete**: expands mhchem `\ce{...}` → plain KaTeX (`strip_ce_notation`, preserving existing `$...$`/`\(..\)` delimiters — no double `$ $...$ $`), strips literal `\n`/`\t`, and wraps bare LaTeX/chemical tokens with `$...$`. Also heals already-published vault docs without shrinking the vault (safe under the exact-30 contract).
   - **Idempotent & quota-cheap**: a clean question produces zero writes; reuses the pipeline's shared question snapshot (no extra full read).
   - **Why it exists**: models sometimes emit `\ce{}` despite prompt instructions not to; shipped JLaTeXMath renders raw `\ce{}` as literal text (Play Store chemistry-formula bug, 2026-09-27).
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

### 3. 🔍 Safe STEM Deduplication Normalization (`scripts/purge_duplicate_questions.py`, `scripts/vault_scheduler.py`)
- **The Problem Solved**: Overly aggressive text stripping that deletes numbers, math operators (`+`, `-`, `*`, `/`, `^`, `=`), and trigonometric/calculus commands (`\sin`, `\cos`, `\sqrt`, `\frac`) can cause distinct STEM questions (e.g. `2 + 2` vs `2 + 3`, or `\sin x` vs `\cos x`) to be falsely flagged as duplicates.
- **The Safe Normalization Fix**:
  - **Preserves Critical STEM Semantics**: Numbers (`0-9`, decimals), math operators (`+`, `-`, `*`, `/`, `^`, `=`, `<`, `>`, `%`), LaTeX functions (`\sin`, `\cos`, `\tan`, `\sqrt`, `\frac`, `\Delta`, `\theta`), chemical formulas (`CH_3COO^-`, `H_2O`), and variables (`x`, `y`).
  - **Normalizes Formatting Differences Only**: Math delimiters (`$..$`, `\(..\)`), styling wrappers (`\text{..}`, `\mathrm{..}`), sub/superscript braces (`_{3}` $\to$ `_3`), trailing sentence punctuation (`?`, `.`, `!`), case, and multi-spaces.
  - Applied across both Firestore deduplication purges and Daily Vault scheduling.

### 4. 🧪 Inline LaTeX & Chemical Formula Auto-Wrapping (`wrap_inline_latex`)
- **The Problem Solved**: Chemistry formulas (e.g. `CH_3COO^-`, `SO_4^{2-}`, `Ca(OH)_2`) and exponents (e.g. `10^{-5}`) generated without `$..$` or `\(..\)` delimiters caused `MathRenderer.kt` to skip rendering entirely (showing raw markup). Conversely, whole-string `wrap_bare_latex()` cannot be used on long prose because one token wraps the whole paragraph into a single math block.
- **The Permanent Fix**:
  - `wrap_inline_latex()` uses a granular `_BARE_LATEX_TOKEN` scanner to wrap individual chemical formulas, KaTeX commands, and scientific notation exponents with `$...$` delimiters inside `questionText` and `explanation`.
  - Applied across `web_question_ingestion.py`, `neet_web_question_ingestion.py`, and `auto_question_pipeline.py`.
  - Verified by 57/57 passing automated test suite (`scripts/test_latex_rendering.py`, `scripts/test_vault_scheduler.py`).

### 5. 🔄 Unconditional Vault Publishing & Client Cache Replacement (2026-09-28)
- **The Problem Solved**: Previously, `schedule_vault()` skipped scheduling if 30 questions already existed in Firestore for that target date, and `QuestionSyncManager.kt` skipped Firestore sync if 30 local Room rows were cached. Manual workflow triggers and scheduled runs were unable to force fresh Daily Vault questions to mobile clients without manual data wipes.
- **The Permanent Fix**:
  - **Pipeline (`scripts/vault_scheduler.py`)**: Removed `is_vault_complete_in_firestore` skip check. Every workflow run unconditionally selects 30 fresh questions for JEE and 30 for NEET under the 30-day anti-repeat cooldown policy.
  - **Timestamped `vaultGroupId`**: `assert_selected_vault()` generates a fresh timestamped ID (`<exam>_vault_<target_date>_<timestamp>`) on every run.
  - **Android Client (`QuestionSyncManager.kt`)**: Removed `shouldSkipNetwork(...)` pre-network skip. The app queries Firestore first; whenever `incomingGroupId != savedGroupId`, it invalidates the local Room cache and reinstalls the 30 new questions.

### 6. 🖼️ End-to-End Image-Based STEM Questions & Diagram Pipeline (2026-09-29)
- **The Problem Solved**:
  - Previously, ~35% of JEE Physics (circuits, ray optics, kinematics), ~25% of Chemistry (reaction mechanisms, stereochemistry), and ~40% of NEET Biology (cell structures, anatomy, genetic crosses) were rejected because ingestion discarded any question containing `"refer to the given figure"`.
- **Division of Labor Across Pipelines**:
  - **Web Ingestion (`web_question_ingestion.py`, `neet_web_question_ingestion.py`)**: Responsible for extracting real PYQ diagrams. When diagram questions are parsed, `diagram_processor.py` auto-trims whitespace borders, downscales to mobile-optimal width ($\le 800\text{px}$), compresses to WebP ($q=85, \sim 25\text{--}45\text{KB}$), and uploads to Firebase Cloud Storage (`apps-273d9.firebasestorage.app`) with persistent download tokens (`imageUrl`, `solutionImageUrl`, `optionImageUrls`).
  - **AI Question Pipeline (`auto_question_pipeline.py`)**: Strictly remains 100% self-contained text/LaTeX equations to prevent AI hallucination of fake diagram URLs.
- **Android Mobile Client Architecture**:
  - **Room Database Migration (`v16 -> v17`)**: Added `imageUrl`, `solutionImageUrl`, and `optionImageUrls` to `Question` and `Power100Question` entities.
  - **High-Performance Image Caching**: Integrated Coil (`io.coil-kt:coil:2.6.0`) with disk and memory LRU caching.
  - **Dynamic Interactive Diagram Card & Full-Screen Modal (`DiagramRenderer.kt`)**:
    - `buildDiagramCard`: Clean dark-mode border, 220dp max height, aspect ratio maintenance, and "🔍 Tap to Zoom" pill badge.
    - `showZoomDialog`: Full-screen immersive modal featuring `ZoomableImageView` with smooth pinch-to-zoom ($1\times - 5\times$), double-tap toggle, pan boundary clamping, and 1-handed zoom controls (`[-]`, `[+]`, `[↺ Fit]`, `[✕ Close]`).
    - Integrated across `TestActivity`, `Power100Activity`, and `SolutionActivity`.

### 7. 🧬 Official JEE Main 2026 & NEET UG 2026 Syllabus & Question-Mode Architecture (2026-09-30)
- **The Problem Solved**:
  - Unstructured generation allowed AI to generate questions outside the official syllabus, invent non-standard question modes, or attempt decorative AI image generation for STEM/Chemistry/Maths.
  - NEET Biology lacked granular topic-to-mode mapping, leading to inappropriate binary "all diagram" or "no diagram" assumptions.
- **5-Level Taxonomy Architecture (`scripts/curriculum.json` & `scripts/curriculum.py`)**:
  - `exam` $\to$ `subject` $\to$ `official_unit` $\to$ `topic` $\to$ `question_modes`
  - **4 Question Modes**:
    1. `T` (**TEXT**): Theory, conceptual, definitions, statement analysis.
    2. `N` (**NUMERICAL**): Calculation, formula evaluation with dual-pass math validation.
    3. `D` (**DIAGRAM**): Deterministic vector plots, ray optics, circuit diagrams, apparatus, waveforms.
    4. `S` (**STRUCTURE**): Chemical structures, reaction mechanisms, molecular geometry, biological models.
- **Strict Curricular Invariants**:
  - **Zero Generative AI Images**: `ai_image_generation: false` across all subjects. Maths, Physics, and Chemistry diagrams are strictly rendered by deterministic Python/Matplotlib/SVG engines or authentic PYQ source images.
  - **JEE Maths (14 Units)**: Selective deterministic graphs/Argand diagrams for Complex Numbers, Calculus, Conics, 3D, and Vectors. Chemical/biological `STRUCTURE` is strictly blacklisted.
  - **JEE & NEET Physics (20 Units)**: First-class deterministic visual candidates for Kinematics, Laws of Motion, Rotation, Waves, Circuits, Optics, and Modern Physics.
  - **JEE & NEET Chemistry (20 Units)**: Physical (T, N), Inorganic (T, S, D), Organic (T, S, D). Uses deterministic molecular structure representations.
  - **NEET Biology (10 Broad Units)**: Structured into granular NCERT topic nodes with high visual priority in Structural Organisation (Unit 2), Human Physiology (Unit 5), and Genetics & Evolution (Unit 7).
- **Pipeline Gates**:
  - `auto_question_pipeline.py` dynamically injects the official unit, topic, and allowed modes into the LLM system prompt (`curriculum.get_prompt_constraints()`), preventing off-syllabus drift.
  - `stem_diagram_pipeline.py`, `web_question_ingestion.py`, and `neet_web_question_ingestion.py` validate all questions against `curriculum.validate_question_against_curriculum()`.

### 8. 🛡️ 7-Stage Curriculum Quality Gate & Honest Metadata Architecture (`scripts/curriculum_validator.py`)
- **Strict 7-Stage Quality Gate**:
  1. **Stage 1 (Curriculum)**: Exam, subject, official unit resolution via `curriculum.find_unit()`. Rejects unapproved units or hallucinated chapters.
  2. **Stage 2 (Origin & AI Image Invariant)**: Checks `sourceType` (`PYQ`, `WEB_SOURCE`, `ORIGINAL_PRACTICE`, `AI_GENERATED`). Enforces `ai_image_generation: false` — rejects questions with generative AI URLs. Authentic PYQ diagrams require genuine PYQ source; practice diagrams require deterministic renderers.
  3. **Stage 3 (Mode Compatibility)**: Validates that question mode (`TEXT`, `NUMERICAL`, `DIAGRAM`, `STRUCTURE`) is explicitly allowed in `curriculum.json` for that topic/unit.
  4. **Stage 4 (Question & LaTeX Integrity)**: Checks questionText, options (exactly 4 non-empty), valid `correctOptionIndex` (0-3), and KaTeX syntax. Enforces no raw `\ce{}` notation.
  5. **Stage 5 (Dual-Pass Math/Numerical Validation)**: For `NUMERICAL` questions, runs independent deterministic solver functions against `params` before publishing. Rejects questions where computed answer != options[correctOptionIndex].
  6. **Stage 6 (Diagram/Structure Validation)**: For `DIAGRAM`/`STRUCTURE` questions, validates image byte signatures (PNG/WebP magic numbers), aspect ratio and dimensions ($\ge 200 \times 150$), parameter consistency between question prose and diagram spec, and topic label presence.
  7. **Stage 7 (STEM-Safe Fingerprint Deduplication)**: Audits question against historical database using STEM-preserving fingerprints.
- **Honest Metadata Schema**:
  - `exam`: `JEE_MAIN` or `NEET_UG`
  - `subject`: `Physics`, `Chemistry`, `Mathematics`, `Biology`
  - `officialUnit`: Official syllabus unit name (e.g. `Kinematics`, `Genetics and Evolution`)
  - `topic`: Granular NCERT topic (e.g. `Wheatstone Bridge`, `Pedigree Analysis`)
  - `questionMode`: `TEXT` | `NUMERICAL` | `DIAGRAM` | `STRUCTURE`
  - `sourceType`: `PYQ` | `WEB_SOURCE` | `ORIGINAL_PRACTICE` | `AI_GENERATED`
  - `diagramRequired`: boolean
  - `diagramSource`: `DETERMINISTIC` | `AUTHENTIC_SOURCE` | `NONE`
  - `validationStatus`: `PASSED` | `FAILED`

### 9. 🏆 Curriculum-Driven Power 100 Architecture (`scripts/build_power100_live.py`)
- **Common Generation & Quality Gate Pipeline**:
  - Power 100 and Daily Vault share the **SAME single source of truth** (curriculum taxonomy, prompt constraints, and 7-stage quality gate). Power 100 does not have a separate content generator.
  - Consumes only questions that pass all 7 stages of `curriculum_validator.py` (`validationStatus == "PASSED"`) and resolve cleanly to official 2026 syllabus units.
- **Configurable Subject Distribution**:
  - Centralized in `scripts/curriculum.json` (`power100_config`):
    - **JEE Main**: Physics 31, Chemistry 36, Maths 33 (= 100 questions). Strictly zero Biology.
    - **NEET UG**: Physics 25, Chemistry 25, Biology 50 (= 100 questions). Strictly zero Maths.
- **Syllabus Breadth & Deduplication**:
  - Balances questions broadly across official NCERT units (capped at `max_per_unit = 4` per unit).
  - Enforces STEM-safe fingerprint uniqueness across all 100 questions.
- **Full Question-Mode & Diagram Support**:
  - Supports `TEXT`, `NUMERICAL`, `DIAGRAM`, and `STRUCTURE`.
  - Diagram and structure questions carry full metadata (`imageUrl`, `solutionImageUrl`, `optionImageUrls`, `diagramSource`) seamlessly consumed by the Android client (`Power100Activity.kt` and `Power100ResultActivity.kt` via `DiagramRenderer.kt`).
- **Android Offline Cache & Invalidation**:
  - Power 100 uploads to `standard_tests/{exam}` with an auto-incremented `version`.
  - Android client (`Power100SyncManager.kt`) detects the version bump, invalidates local Room cache, and resets progress cleanly without manual interventions.

### 10. 🧹 Deep LaTeX/OCR Corruption Purge & Quality Gate Hardening (2026-10-03)
- **The Problem Solved**:
  - Four user-reported defects in live Power 100 tests revealed legacy corruption:
    1. **Q.34/100**: Unescaped Python/JSON string literals evaluated `\f` as form feed (`\x0c`) and `\r` as carriage return (`\x0d`), degrading `\frac` $\to$ `rac{` and `\right` $\to$ `ight)`, plus legacy inclusion of deleted syllabus chapters (`Environmental Chemistry`).
    2. **Q.35/100**: Oversized 50+ char inline LaTeX chemical reaction (`$Cu + HNO_3 \to \dots$`) exceeded mobile screen line width, causing `JLatexMathDrawable`'s rigid non-wrapping `ImageSpan` to overflow into the right canvas void (showing as a blank line).
    3. **Q.98/100**: Legacy OCR scrapers from coaching PDFs (`scripts/extracted/Maths/Circle.json`) dropped watermarked diagrams/equations, leaving `Circle Circle Circle Circle` wrapped in `$$$\text{Circle}$$$`. Unbalanced triple dollar signs trapped subsequent English prose into KaTeX math mode, stripping all whitespace and rendering text in math italics.
    4. **Q.99/100**: Unrelated watermark trigonometric matrices glued to AP determinant problems with nested `$$$` and `$ ... $` inside `\begin{vmatrix}`, causing parser crashes and blank matrix rendering.
- **Root Cause Context**:
  - Prior to the 7-stage curriculum quality gate, old bulk-extracted questions sat dormant in Firestore. Although the daily pipeline protected newly generated questions, dormant legacy records remained unverified.
- **Permanent Architectural Fixes**:
  1. **Comprehensive Corruption Scanner (`scripts/cleanup_corrupted_questions.py`)**:
     - **Rule 6 (Broken LaTeX Escapes)**: Detects orphan `rac{`, `ight)`, `eft(`, `imes`, `qrt{`, `egin{`, `heta`, and ASCII control characters `\x0c`, `\x0d`.
     - **Rule 7 (Delimiter Integrity)**: Detects triple/quadruple dollar signs (`$$$+`) across both `questionText` and `explanation`.
     - **Rule 8 (Prose in Math)**: Detects English sentences trapped inside math delimiters (`$centre of circle$`, `$when the equation$`).
     - **Rule 9 (Missing Equation Holes)**: Detects OCR placeholders (`\text{Circle}`, `Circle Circle`, and blank equation/matrix prompts).
  2. **Stage 4 Gate Integration (`scripts/curriculum_validator.py`)**:
     - `CurriculumQualityGate.validate()` Stage 4 now mandatorily executes `is_corrupted()`. Any corrupted or broken question is rejected immediately.
  3. **Master Firestore Bank Purge**:
     - 38 corrupted legacy documents permanently deleted from the `questions` collection with auto-incremented `/metadata/question_bank` version counter.
  4. **Android Client Defensive Resilience (`MathRenderer.kt`)**:
     - `normalize()` safely strips ASCII control characters (`\u000C`, `\r`, `\u0008`) and collapses multiple dollar delimiters (`[$]{3,}` $\to$ `$$`) before JLatexMath parsing.
  5. **Clean Power 100 Rebuild**:
     - Rebuilt and verified with **0 flagged questions**:
       - **JEE Power 100**: Version **18** (Physics 31, Chemistry 36, Maths 33 = 100 questions, zero Biology, zero deleted chapters).
       - **NEET Power 100**: Version **16** (Physics 25, Chemistry 25, Biology 50 = 100 questions, zero Maths).

