# 📜 Summary of Fixes & Enhancements (2026-09-28)

## 📌 Executive Summary

On **September 28, 2026**, a comprehensive architectural overhaul was implemented across the Python Daily Vault Automation pipeline and the Android Kotlin client (`QuestionSyncManager`). The core objective was ensuring that **every workflow run (whether scheduled nightly or manually triggered via GitHub Actions)** successfully publishes **30 NEW questions for JEE and 30 NEW questions for NEET** with a unique timestamped `vaultGroupId`, while ensuring the Android app immediately invalidates local Room cache and displays the new questions.

---

## 🛠️ Key Fixes Implemented

### 1. Daily Vault Scheduler — Guaranteed Fresh Selection (`scripts/vault_scheduler.py`)
- **Removed Existing Document Skip**:
  - Previously, `schedule_vault()` skipped scheduling if 30 questions already existed in Firestore for that target date.
  - **Fix**: Removed `is_vault_complete_in_firestore` skip check. Every workflow execution now unconditionally selects 30 fresh questions for JEE and 30 for NEET for the target date.
- **Timestamped `vaultGroupId` Generation**:
  - `assert_selected_vault()` now appends an epoch timestamp to `vaultGroupId` on every execution (`<exam>_vault_<target_date>_<timestamp>`).
  - Example: `jee_vault_2026-09-28_1790610561` vs `jee_vault_2026-09-28_1790614200`.
- **Strict 30-Day Anti-Repeat Cooldown**:
  - Selection maintains the 3-Tier priority policy:
    - **Tier 1**: Fresh / Never-vaulted questions.
    - **Tier 2**: Cooldown-cleared (> 30 days since last vault) using Least-Recently-Used (LRU) order.
    - **Tier 3**: Active cooldown ($\le 30$ days) — strictly blacklisted.

---

### 2. Timezone-Aware Target Dates (`scripts/run_daily_automation.py`)
- **IST Timezone (`UTC+5:30`) Calculation**:
  - The script calculates target dates based on IST:
    - `today_ist`: Today in IST.
    - `tomorrow_ist`: Tomorrow in IST.
  - Schedules Daily Vault for both `today_ist` and `tomorrow_ist` on every execution to cover edge-case workflow timing near midnight.

---

### 3. Android Client Immediate Cache Invalidation (`QuestionSyncManager.kt`)
- **Firestore-First Group ID Check**:
  - Removed `DailyVaultContract.shouldSkipNetwork(...)` pre-network skip that blocked Firestore sync when local Room database had 30 cached rows.
  - `QuestionSyncManager.syncDailyVault()` queries Firestore first to compare the incoming doc `vaultGroupId` with the locally saved `savedGroupId` (`PrefManager.getDailyVaultGroupId()`).
- **Cache Replace Logic**:
  - If `incomingGroupId.isNotEmpty() && incomingGroupId != savedGroupId`, the app invalidates the local Room database cache and replaces it with the 30 newly published Firestore vault questions.
  - Users see fresh Daily Vault questions immediately after a workflow run without needing to clear app data manually.

---

### 4. CI/CD & GitHub Actions Workflow Hardening (`.github/workflows/daily_automation.yml`)
- **Python Byte-Exact Secret Restoration**:
  - Replaced bash `printf` in `Restore Firebase Service Account Key` step with a Python script:
    `python -c "import json, os; data = os.environ['FIREBASE_CREDENTIALS_JSON']; d = json.loads(data); os.makedirs('scripts', exist_ok=True); open('scripts/serviceAccountKey.json', 'w', encoding='utf-8').write(data); print(f'Firebase service account written OK for project: {d.get(\"project_id\")}')"`
  - Prevents bash shell mangling of RSA private key newlines (`\n` vs `\r\n`).
- **Compact Secret Upload**:
  - Cleaned and updated GitHub repository secret `FIREBASE_SERVICE_ACCOUNT` with normalized, single-line JSON.
- **Firestore Connectivity Retry**:
  - Added a 3-attempt retry loop with exponential backoff (`time.sleep(5)`) to `verify_firebase_connectivity(db)` in `scripts/run_daily_automation.py` to handle transient GCP rate-limit spikes.

---

## 🧪 Verification & Test Results

1. **Python Unit Tests**: **74 / 74** tests passing (`python -m unittest discover -s scripts -p "test_*.py"`).
2. **Kotlin Unit Tests**: Android test suite passed (`BUILD SUCCESSFUL`).
3. **Build & APK Installation**:
   - `.\gradlew assembleDebug` passed (`BUILD SUCCESSFUL in 39s`).
   - Installed fresh APK to ADB connected device via `adb install -r app/build/outputs/apk/debug/app-debug.apk` (`Success`).
4. **Git Repository State**: Clean status on `main` branch (`commit 6bf6ffd`).

---

## 📋 Architectural Rules & Contract Guarantees

1. **Every Workflow Run Publishes 30 New Questions**: Both scheduled midnight runs and manual `workflow_dispatch` runs publish 30 fresh questions for JEE and NEET with a new timestamped `vaultGroupId`.
2. **30-Day Anti-Repeat Cooldown Remains Intact**: Questions served within the last 30 days are never selected.
3. **Android Client Replaces Room Cache on New `vaultGroupId`**: The Android app detects any `incomingGroupId != savedGroupId` and updates local storage automatically.
