# Focus Lock App 🔒 🤖

A strict, AI-powered productivity app for Android designed to enforce deep work and eliminate digital distractions.

Focus Lock goes beyond standard screen-time limits. It physically blocks distracting apps using Android Overlay Services and features an integrated AI accountability coach (powered by Gemini 3.5 Flash) to help users stay on track and reflect on their habits.

## 🚀 Features
* **Aggressive App Blocking:** Instantly detects and overlays a lock screen when a user attempts to open a blacklisted app (TikTok, Instagram, etc.).
* **AI Focus Coach:** An integrated chatbot that acts as a tough-love accountability partner, powered by Google's Gemini 3.5 Flash model.
* **Smart Scheduling:** The AI can automatically parse natural language (e.g., "I need to study for 2 hours") and schedule a system-wide app block.
* **Cloud Analytics:** Automatically logs user commitment data to a secure Google Sheet backend.
* **Monetization:** Fully integrated with Google AdMob (Banner and Interstitial ads).

## 🛠️ Tech Stack
* **Language:** Kotlin
* **AI Integration:** Firebase AI Logic (Gemini 3.5 Flash) + Firebase App Check
* **Backend:** Google Sheets API Webhook & Firebase Analytics
* **Android APIs:** `UsageStatsManager`, Foreground Services, `System_Alert_Window`
* **Concurrency:** Kotlin Coroutines & Dispatchers

  This branch is for features

## 🔒 Security & Local Setup
To protect API quotas, the sensitive keys have been removed from this public repository. If you are cloning this project to run locally, you must provide your own keys.

1. Clone the repository.
2. Add your own Firebase config: download `google-services.json` from your Firebase project into `app/`.
3. Create `app/src/main/res/values/secrets.xml` with your Google Sheets webhook URL:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="sheets_webhook_url">YOUR_GOOGLE_SHEETS_WEBHOOK_URL</string>
</resources>
```

### AI Coach: no API key in the app

The Focus Coach calls Gemini through **Firebase AI Logic**, so there is **no Gemini API
key in the app or in this repo**. The key lives inside the Firebase project, and every
request is verified with **Firebase App Check** (Play Integrity), so scripts and
repackaged copies of the app are rejected.

To set it up in your own Firebase project:
1. Firebase Console → **AI Logic** → Get started → choose the **Gemini Developer API**.
2. Firebase Console → **App Check** → register the Android app with **Play Integrity**
   (add your release SHA-256 fingerprint), then **enforce** App Check for AI Logic.
3. For debug builds, run the app once, copy the debug token printed in Logcat, and add it
   under App Check → Apps → **Manage debug tokens**.
4. (Recommended) In AI Logic settings, lower the per-user rate limit, and in Google Cloud
   Console set a daily quota on the Generative Language API plus a budget alert.

The app also limits coach usage on the device (see `CoachRateLimiter.kt`): 20 messages
per day, 10 seconds between messages, 300 characters per message.
