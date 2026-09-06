# MockTestApp - Session Summary
**Project:** JEE NEET Mock Test App
**Workspace:** E:\MockTestApp
**GitHub:** https://github.com/kundanchouhan12/jeeneetmocktest
**Last Updated:** September 6, 2026
**Latest Commit:** 13a63bf on main
**App Version:** 1.2.1 (versionCode 19)
**Signed AAB:** E:\MockTestApp\app\build\outputs\bundle\release\app-release.aab

---

## All Changes Made This Session

### 1. Question Quality & Corruption Fixes
- Purged corrupted & duplicate questions from jee_power100.json and Firestore
- 727 duplicate questions removed (2,584 clean questions remain)
- Hardened scripts: cleanup_corrupted_questions.py, auto_question_pipeline.py, web_question_ingestion.py, update_power100.py
- Strict filters: 4 distinct options, min 25 chars, no broken LaTeX, no image refs, no duplicates

### 2. Dark Mode LaTeX Math Fix
- File: MathRenderer.kt
- Fix: Detects dark mode, applies white text to KaTeX formulas

### 3. AdMob Revenue Optimization
- AdManager.kt: Preloads Interstitial + Rewarded Ads at app launch
- AppOpenAdManager.kt: Cooldown 5 hours -> 15 minutes
- ScanActivity.kt: 2 free AI doubt scans/day, then rewarded video to unlock more
- ResultActivity.kt: Rewarded video to unlock full answer key & explanations
- Power100ResultActivity.kt: Rewarded video to revive Power 100 streak
- PrefManager.kt: Daily scan count tracking added
- Mediation: Meta (Facebook) + Unity Ads configured in AdMob console

### 4. Leaderboard Upgrade
- File: LeaderboardActivity.kt
- Minimum qualifying score: > 0 pts -> >= 100 pts
- Visible slots: Top 10 -> Top 25
- Firestore fetch limit: 50 -> 100
- Dynamic status card shows points needed to qualify

### 5. Signed Release AAB Build
- Version: 1.2.1 (code 19)
- Signing key: C:\Users\Chouh\Desktop\App.jks (alias: key0)
- AAB: E:\MockTestApp\app\build\outputs\bundle\release\app-release.aab (34.69 MB)
- Build: .\gradlew bundleRelease --no-daemon
- JVM fix (7GB RAM machine): gradle.properties -> -Xmx3072m -XX:+UseSerialGC

---

## Key File Locations
- app/build.gradle: versionCode 19, versionName 1.2.1
- gradle.properties: JVM heap 3072m SerialGC
- local.properties: Keystore path & passwords (NOT in git)
- app/build/outputs/bundle/release/app-release.aab: UPLOAD THIS to Play Console
- scripts/run_daily_automation.py: Run daily for question bank maintenance

---

## Git Commits This Session
13a63bf  chore: bump version to 1.2.1 (code 19) for Play Console release
c7aeaac  refactor: update minimum leaderboard score requirement to 100 points
1144d8e  refactor: increase leaderboard score qualification threshold and expand to Top 25
6fb8aa4  feat: AdMob rewarded video placements + MathRenderer dark mode fix

---

## Play Store Release Notes v1.2.1
Whats new in v1.2.1:

Features:
- Leaderboard now requires 100+ points to qualify
- Leaderboard expanded to Top 25 students
- Unlock AI Doubt solutions by watching a short video
- Unlock full Answer Key after test by watching a short video
- Power 100 streak revive with rewarded video

Bug Fixes:
- Repeated and corrupted questions removed from question bank
- Math formula display fixed in dark mode

---

## Future Ideas
- Interstitial ad between test sessions
- Push notification for daily study reminder (FCM ready)
- Upgrade deprecated GoogleSignIn to Credential Manager
- Fix deprecated systemUiVisibility in TestActivity.kt
