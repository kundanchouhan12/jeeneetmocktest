# 🚀 MockTestApp Admin Guide

This guide explains how to manage your production question bank and the Daily Vault system using the master controller.

---

## 🛠️ Prerequisites

1. **Python 3.x** installed.
2. **Firebase Key**: Place your `serviceAccountKey.json` in the `scripts/` folder.
3. **Libraries**:
   ```powershell
   pip install firebase-admin PyPDF2
   ```

---

## 📂 Project Structure

- `content/`: **Master Content Source.** Organised by Exam > Subject > Chapter.
  - `jee/`: (physics, chemistry, maths)
  - `neet/`: (physics, chemistry, biology)
- `scripts/`: **Automation Tools.**
  - `manage.py`: **Main Tool.** Use this for all daily operations.
  - `pdf_converter.py`: Utility to extract questions from books/PDFs.

---

## 📖 The "Daily Master" Workflow

### 1. Syncing Content (Whenever you add/edit questions)
To upload your local JSON files to the Cloud:
```powershell
python scripts/manage.py import
```
*This scans your `content/` folder and syncs everything to Firestore.*

### 2. Launching Today's Challenge (Daily Task)
To refresh the Daily Vault for all users:
```powershell
python scripts/manage.py schedule
```
*This command automatically:*
- Randomly picks **50 JEE** and **50 NEET** questions.
- Sets the reward to **+50 Coins**.
- **Sends a Push Notification** to all users' phones instantly.

### 3. Master Daily Automation (GitHub Actions)
The daily automation runs automatically in the cloud every night at 12:00 AM IST (18:30 UTC) via `.github/workflows/daily_automation.yml`:
```powershell
python scripts/run_daily_automation.py
```
- **Web Ingestion**: Scrapes and sanitizes PCM/PCB web questions (`web_question_ingestion.py`).
- **Dual-Pass Verification**: Independently re-derives answers before accepting.
- **Daily Vault**: Auto-publishes 30 questions for JEE and 30 for NEET daily (`vault_scheduler.py`).
- **Weekly Power 100**: Refreshes standard tests every **7 days (every Monday)** (`build_power100_live.py`). To force a rebuild: `python scripts/run_daily_automation.py --force-power100`.

---

## 📄 Converting PDFs to JSON
If you have a book in PDF format:
1. Open `scripts/pdf_converter.py`.
2. Update the `convert(...)` call at the bottom with your PDF path.
3. Run: `python scripts/pdf_converter.py`.
4. It will generate a JSON file in the correct `content/` folder automatically.

---

## ❓ FAQ & Tips

**Q: How do I change the notification message?**  
A: Edit the `send_global_notification` text in `scripts/manage.py`.

**Q: Can I run 'import' multiple times?**  
A: Yes. It is safe. It will only update existing questions if they changed; it won't create duplicates.

**Q: What if the vault is empty?**  
A: Ensure you have run `import` first so there is a pool of questions for the `schedule` command to pick from.
