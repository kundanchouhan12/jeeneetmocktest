# JEE NEET Mock Test App — Kotlin Android

A complete Android app for JEE/NEET exam preparation with AdMob monetization and IAP.

## Project Structure

```
MockTestApp/
├── app/
│   └── src/main/
│       ├── java/com/mocktest/app/
│       │   ├── MainActivity.kt              ← Home screen (exam selector + banner ad)
│       │   ├── MockTestApplication.kt       ← App class (AdMob init)
│       │   ├── admob/
│       │   │   └── AdManager.kt             ← Banner, Interstitial, Rewarded ads
│       │   ├── data/
│       │   │   ├── model/Models.kt          ← Question, TestSession, TestResult, ExamConfig
│       │   │   └── repository/
│       │   │       ├── Database.kt          ← Room DB + DAOs
│       │   │       └── MockTestRepository.kt ← Data access + 15 seeded PYQ questions
│       │   ├── ui/
│       │   │   ├── test/
│       │   │   │   ├── TestActivity.kt      ← Live test (timer, palette, MCQ)
│       │   │   │   └── TestViewModel.kt     ← Test state, scoring, countdown timer
│       │   │   └── result/
│       │   │       └── ResultActivity.kt    ← Score screen + rewarded ad unlock
│       │   └── utils/
│       │       └── PrefManager.kt           ← SharedPreferences (IAP, streak, settings)
│       ├── AndroidManifest.xml
│       └── res/values/
│           ├── colors.xml
│           ├── themes.xml
│           └── strings.xml
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## Setup Steps

### 1. Open in Android Studio
File → Open → select the MockTestApp folder.
Android Studio will sync Gradle automatically.

### 2. Replace AdMob IDs
In `app/src/main/AndroidManifest.xml`:
```xml
<meta-data
    android:name="com.google.android.gms.ads.APPLICATION_ID"
    android:value="ca-app-pub-XXXXXXXXXXXXXXXX~XXXXXXXXXX" />  ← your real App ID
```

In `app/src/main/java/com/mocktest/app/data/model/Models.kt`:
```kotlin
object AdUnitIds {
    const val BANNER_PROD       = "ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX"
    const val INTERSTITIAL_PROD = "ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX"
    const val REWARDED_PROD     = "ca-app-pub-XXXXXXXXXXXXXXXX/XXXXXXXXXX"
    val IS_DEBUG = false  // ← Change to false before release!
}
```

### 3. Add google-services.json
- Go to Firebase Console → Add Android app → package: `com.mocktest.app`
- Download `google-services.json`
- Place it in the `app/` folder

### 4. Set up IAP in Play Console
- Create in-app products with these exact Product IDs:
  - `remove_ads` (one-time, ₹99)
  - `jee_physics_pack` (one-time, ₹49)
  - `jee_chemistry_pack` (one-time, ₹49)
  - `jee_maths_pack` (one-time, ₹49)
  - `neet_biology_pack` (one-time, ₹49)
  - `neet_chemistry_pack` (one-time, ₹49)
  - `all_access_yearly` (subscription, ₹299/year)

### 5. Build & Run
- Connect Android device (API 24+) or start emulator
- Click Run ▶ in Android Studio

## Monetization Flow

| Screen | Ad Type | When |
|--------|---------|------|
| Home | Banner | Always (bottom) |
| After test submit | Interstitial | Between test → result |
| Result screen | Rewarded | User taps "Watch to unlock solutions" |

## Ad Revenue Estimate (India)
- Banner eCPM: ₹5–15 per 1,000 views
- Interstitial eCPM: ₹20–50 per 1,000 views  
- Rewarded eCPM: ₹30–80 per 1,000 views
- At 10,000 MAU: ₹3,000–8,000/month
- At 50,000 MAU: ₹15,000–40,000/month

## Adding More Questions

In `MockTestRepository.kt`, add to the `sampleQuestions()` list:
```kotlin
Question(
    examType = "JEE",          // or "NEET"
    subject = "Physics",       // Physics / Chemistry / Maths / Biology
    chapter = "Optics",
    difficulty = "Medium",     // Easy / Medium / Hard
    year = 2024,               // PYQ year, or 0 for original
    questionText = "Your question here...",
    options = listOf("Option A", "Option B", "Option C", "Option D"),
    correctOptionIndex = 1,    // 0-indexed (B = 1)
    explanation = "Because..."
)
```

## Key Classes to Extend

| What to add | Where |
|-------------|-------|
| Solution viewer screen | Create `SolutionActivity`, launch from `ResultActivity.unlockSolutions()` |
| Analytics/weak topics | Add queries to `TestResultDao`, display in new `AnalysisFragment` |
| Firebase question sync | Add Firestore fetch in `MockTestRepository`, merge with local DB |
| Daily quiz notification | Use `WorkManager` + `NotificationCompat` |
| Leaderboard | Firebase Firestore collection `leaderboard/{userId}` |

## Play Store Checklist Before Publishing
- [ ] Switch `AdUnitIds.IS_DEBUG = false`
- [ ] Add real AdMob App ID in Manifest
- [ ] Add `google-services.json`
- [ ] Generate signed APK/AAB (Build → Generate Signed Bundle)
- [ ] Add Privacy Policy URL in Play Console
- [ ] Complete content rating questionnaire
- [ ] Fill ASO: title, description, screenshots
