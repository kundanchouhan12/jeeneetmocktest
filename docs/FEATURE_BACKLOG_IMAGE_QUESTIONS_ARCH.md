# 🖼️ Feature Backlog & Architecture Blueprint: Image-Based STEM Questions & Solutions (JEE & NEET)

## 📌 Executive Summary

Currently, **~35% of JEE Physics (circuits, ray optics, mechanics, P-V diagrams), ~25% of Chemistry (reaction mechanisms, stereochemistry, crystal lattices), and ~40% of NEET Biology (anatomy, cell diagrams, genetic pedigrees)** depend on diagrams.

Because our daily automation ingestion pipeline explicitly rejected any question mentioning:
```regex
r'refer to (the )?(given |above )?(figure|diagram|image)'
r'as shown in (the )?(figure|diagram|circuit diagram|image|graph below)'
r'in the given (figure|diagram|circuit|graph)'
r'from the given (figure|diagram|graph)'
```
the system was previously discarding many of the highest-yield, highest-difficulty questions (especially for JEE Advanced and NEET).

This document establishes the end-to-end architecture to ingest, store, verify, and render diagram-based questions and solutions in the app.

---

## 🔍 Codebase Audit & Current State

A complete audit of the project identified the current touchpoints and required upgrades:

| Layer | File / Module | Current State | Required Upgrade |
|---|---|---|---|
| **Data Model (Room)** | `com.jeeneet.mocktest.data.model.Models.kt` | `Question` entity has no image fields | Add `imageUrl: String?`, `solutionImageUrl: String?`, `optionImageUrls: List<String>?` |
| **Firestore Parser** | `com.jeeneet.mocktest.data.repository.QuestionFirestoreParser.kt` | Parses text, options, correctOptionIndex | Coerce `imageUrl` & `solutionImageUrl` with null-safety |
| **Image Loading Library** | `app/build.gradle` | Neither Glide nor Coil present | Add `io.coil-kt:coil:2.6.0` (Coroutine-native, auto disk/memory cache) |
| **UI Test Engine** | `TestActivity.kt`, `Power100Activity.kt` | Renders `tvQuestionText` via `MathRenderer` | Add `ivQuestionDiagram` with aspect ratio, shimmer placeholder & pinch-to-zoom |
| **UI Solution Viewer** | `SolutionActivity.kt` | Renders text explanation only | Render `ivSolutionDiagram` for vector free-body / circuit redraw steps |
| **Web Ingestion** | `scripts/web_question_ingestion.py`, `neet_web_question_ingestion.py` | Drops diagram questions via regex filter | Extract image files, convert to WebP, upload to Firebase Storage, retain question |
| **AI Question Top-Up** | `scripts/auto_question_pipeline.py` | Prompts strictly forbid diagrams | Retain text-only focus for synthetic top-up; reserve diagrams for official PYQ ingestion |

---

## 🏗️ 1. End-to-End System Architecture

```
[ Web / PYQ Source / PDF / OCR ]
             │
             ▼
   [ Ingestion Script ] ──(Extracts Text + Crops Diagram)
             │
             ├──► [ Image Processing ]: Resize, clean borders, convert to WebP (≤80KB)
             ├──► [ Cloud Storage ]: Upload to Firebase Storage (`/questions/{exam}/{id}.webp`)
             └──► [ Multimodal Verification ]: Vision LLM verifies diagram legibility & labels
             │
             ▼
      [ Firestore ] ──(Writes Question doc with `imageUrl`, `solutionImageUrl`)
             │
             ▼
  [ QuestionSyncManager ] ──(Downloads metadata & pre-fetches image URLs)
             │
             ▼
      [ Room SQLite ] ──(Stores `imageUrl`, `solutionImageUrl`)
             │
             ▼
    [ Android Client ] ──(Coil Caching + Pinch-to-Zoom Modal + KaTeX Layout)
```

---

## 🧩 2. Image Source Reality & AI Division of Labor

### A. Can Groq AI Generate STEM Diagrams?
**No, Groq cannot create diagrams directly.**
* Groq is an ultra-fast text/code inference engine (e.g. `llama-3.3-70b-versatile`). It outputs text, LaTeX math, and JSON — it **cannot draw or paint JPEG/PNG image files**.
* Even if general-purpose image generators (Midjourney, DALL-E) are used, **generative AI cannot be trusted for JEE/NEET physics, chemistry, or biology**. AI image models hallucinate component values, blur resistor lines, skew ray angles, and misplace anatomical pointers. Confusing students with incorrect diagrams is unacceptable for competitive exam preparation.

### B. Where the Images Actually Come From
The authentic diagrams already exist on official past-year papers, NTA PDFs, and educational portals:

```
Educational Webpage / NTA PYQ Page
┌────────────────────────────────────────────────────────┐
│ Q. Find the current through resistor R2 in the circuit: │
│                                                        │
│   [ <img src="https://source.com/circuits/q14.png"> ]  │  <── Real Official Diagram
│                                                        │
│ (A) 2A   (B) 4A   (C) 1A   (D) 0.5A                    │
└────────────────────────────────────────────────────────┘
```

#### New Image-Enabled Ingestion Pipeline:
1. **Download Original Image**: The script downloads the actual figure (`q14.png`) from the source.
2. **WebP Optimization**: Uses Python `Pillow` to normalize borders, balance contrast, and convert to **WebP** (typically only $30\text{KB} - 60\text{KB}$).
3. **Upload to Firebase Storage**: Uploads to the app's bucket:
   `gs://mocktest-app/questions/jee/phy_circuit_2024_q14.webp`
4. **Link in Firestore**: Saves the question document with the public HTTPS download URL.

### C. Groq's Role: Quality Controller & LaTeX Formatter
Groq does not create the diagram, but acts as the **Smart Quality Inspector**:
1. **LaTeX & KaTeX Cleaner**: Formats text surrounding the figure into proper KaTeX math.
2. **Dual-Pass Verification**: Independently solves the problem using the diagram's numerical parameters to verify the answer key.
3. **Vision Legibility Pass (Multimodal)**: Assesses whether the diagram is crisp, labels (A, B, C) are readable, and no axes are cropped.

### D. Pipeline Division of Labor
| Pipeline Component | Primary Source | Question Types Handled |
|---|---|---|
| **Web Ingestion** (`web_question_ingestion.py`, `neet_web_question_ingestion.py`) | Real NTA PYQs, official papers | **Diagram Questions + Pure Text PYQs** (authentic official figures) |
| **AI Question Top-Up** (`auto_question_pipeline.py`) | Groq Llama 3.3 | **High-Yield Text & Math Questions** (calculus, stoichiometry, numericals, conceptual) |

---

## 📐 3. Data Model & Database Schema Migration

### A. Room Entity (`com.jeeneet.mocktest.data.model.Models.kt`)
Add nullable fields with default values for backward compatibility:

```kotlin
@Entity(tableName = "questions")
@TypeConverters(Converters::class)
data class Question(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val examType: String,
    val subject: String,
    val chapter: String,
    val difficulty: String,
    val year: Int,
    val questionText: String,
    val options: List<String>,
    val correctOptionIndex: Int,
    val explanation: String,
    val isPremium: Boolean = false,
    val isDailyVault: Boolean = false,
    val vaultDate: String = "",
    val vaultGroupId: String = "",

    // ─── NEW IMAGE FIELDS (Nullable with default null) ───
    val imageUrl: String? = null,
    val solutionImageUrl: String? = null,
    val optionImageUrls: List<String>? = null
)
```

### B. Firestore Schema Invariant
All documents written to `question_bank` and `daily_vault` maintain:
```json
{
  "examType": "JEE",
  "subject": "Physics",
  "chapter": "Current Electricity",
  "difficulty": "Hard",
  "year": 2023,
  "questionText": "In the circuit shown below, the potential difference between points $A$ and $B$ is:",
  "imageUrl": "https://firebasestorage.googleapis.com/v0/b/mocktest.appspot.com/o/questions%2Fjee%2Fphy_circuit_1042.webp?alt=media",
  "solutionImageUrl": "https://firebasestorage.googleapis.com/v0/b/mocktest.appspot.com/o/solutions%2Fjee%2Fphy_circuit_1042_sol.webp?alt=media",
  "options": ["$12\\text{ V}$", "$6\\text{ V}$", "$0\\text{ V}$", "$4\\text{ V}$"],
  "correctOption": 1,
  "correctOptionIndex": 1,
  "explanation": "Applying nodal analysis at junction C..."
}
```

### C. Safe Coercion in `QuestionFirestoreParser.kt`
```kotlin
val imageUrl = (data["imageUrl"] as? String)?.trim()?.takeIf { it.isNotBlank() }
val solutionImageUrl = (data["solutionImageUrl"] as? String)?.trim()?.takeIf { it.isNotBlank() }
```

---

## 🖼️ 4. App UI & User Experience Design

### A. Coil Image Loading (`io.coil-kt:coil:2.6.0`)
- **100% Kotlin & Coroutines**: Integrates seamlessly with `lifecycleScope`.
- **Automatic Caching**: Downloads once and stores in disk cache so offline tests load diagrams instantly.
- **Hardware Bitmap Support**: Minimal RAM footprint on budget Android devices.

### B. Test Mode Layout (`TestActivity.kt`, `Power100Activity.kt`)
1. **Vertical Hierarchy**:
   - Question Number & Marks badge (`Q 14 of 30 · +4 / -1`)
   - Question Text (KaTeX math rendered via `MathRenderer`)
   - **Diagram Card**:
     - Contained in a rounded card with a subtle border.
     - Constrained max height (`maxHeight="220dp"`, `adjustViewBounds="true"`).
     - Shimmer placeholder during initial load.
     - **Tap to Zoom badge** (`🔍 Tap to zoom`).
   - Options A, B, C, D (option highlight colors mutate without re-rendering the image).
2. **Pinch-to-Zoom Full-Screen Modal**:
   - Tapping the diagram opens a full-screen dialog with smooth pinch-to-zoom ($1\times$ to $5\times$), double-tap to reset, and a close button.
   - Essential for complex circuit resistors, optical ray angles, and microscopic anatomy labels.
3. **Dark Mode Eye Comfort**:
   - Most STEM diagrams have white backgrounds with black line art. Displaying an unpadded raw white box on dark themes creates jarring glare.
   - **Solution**: Wrap the image inside a styled card (`Corner.L`, dark border) or provide an optional subtle invert filter for transparent line art.

### C. Solution Mode Layout (`SolutionActivity.kt`)
- Displays both the original question diagram and any corresponding **step-by-step solution diagram** (free-body diagrams, redrawn bridge circuits).

---

## 📋 5. Phased Implementation Roadmap

| Phase | Status | Milestone | Deliverables |
|---|---|---|---|
| **Phase 1: Client Readiness** | ✅ **COMPLETED** | App UI & Database Model | 1. Added `io.coil-kt:coil:2.6.0` to `app/build.gradle`.<br>2. Added `imageUrl`, `solutionImageUrl`, `optionImageUrls` to `Question` & `Power100Question` Room entities (`MIGRATION_16_17`).<br>3. Updated `QuestionFirestoreParser.kt` & `Power100SyncManager.kt` to read image fields.<br>4. Implemented `DiagramRenderer.kt` (aspect ratio card + full-screen $1\times-5\times$ pinch-to-zoom modal with 1-handed zoom controls) in `TestActivity`, `Power100Activity`, `SolutionActivity`.<br>5. Tested locally on device with seeded questions and hero carousel demo. |
| **Phase 2: Storage & Ingestion Pipeline** | ✅ **COMPLETED** | Pipeline Ingestion & Cloud Storage | 1. Configured Firebase Storage bucket `apps-273d9.firebasestorage.app`.<br>2. Built `scripts/diagram_processor.py` (border trim, 800px downscale, WebP $q=85$ compression, CDN download token upload).<br>3. Integrated `diagram_processor` into `web_question_ingestion.py` & `neet_web_question_ingestion.py`.<br>4. Lifted rejection filter for questions with valid `imageUrl`.<br>5. Verified with 65/65 passing unit tests (`test_diagram_processor.py`, `test_latex_rendering.py`, `test_vault_scheduler.py`). |
| **Phase 3: Curriculum Rollout** | 🚀 **READY** | Production Deployment | 1. Ingest diagram-heavy chapters (*Physics: Circuits, Ray Optics, Mechanics*; *NEET: Anatomy, Plant Morphology*).<br>2. Schedule diagram questions in Daily Vault & Power 100.<br>3. Monitor client image load latency and cache hits. |
