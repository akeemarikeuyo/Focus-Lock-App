# Focus Lock App 🔒 🤖

A strict, AI-powered productivity app for Android designed to enforce deep work and eliminate digital distractions.

Focus Lock goes beyond standard screen-time limits. It physically blocks distracting apps using Android Overlay Services and features an integrated AI accountability coach (powered by Gemini 2.5 Flash) to help users stay on track and reflect on their habits.

## 🚀 Features
* **Aggressive App Blocking:** Instantly detects and overlays a lock screen when a user attempts to open a blacklisted app (TikTok, Instagram, etc.).
* **AI Focus Coach:** An integrated chatbot that acts as a tough-love accountability partner, powered by Google's Gemini 2.5 Flash model.
* **Smart Scheduling:** The AI can automatically parse natural language (e.g., "I need to study for 2 hours") and schedule a system-wide app block.
* **Cloud Analytics:** Automatically logs user commitment data to a secure Google Sheet backend.
* **Monetization:** Fully integrated with Google AdMob (Banner and Interstitial ads).

## 🛠️ Tech Stack
* **Language:** Kotlin
* **AI Integration:** Google Generative AI SDK (Gemini 2.5 Flash)
* **Backend:** Google Sheets API Webhook & Firebase Analytics
* **Android APIs:** `UsageStatsManager`, Foreground Services, `System_Alert_Window`
* **Concurrency:** Kotlin Coroutines & Dispatchers

  This branch is for features

## 🔒 Security & Local Setup
To protect API quotas, the sensitive keys have been removed from this public repository. If you are cloning this project to run locally, you must provide your own keys.

1. Clone the repository.
2. Navigate to `app/src/main/res/values/`.
3. Create a new file named `secrets.xml`.
4. Add your Gemini API key and Google Sheets Webhook URL:
```xml  
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="sheets_webhook_url">YOUR_GOOGLE_SHEETS_WEBHOOK_URL</string>
    <string name="gemini_api_key">YOUR_GEMINI_API_KEY</string>
</resources>
```

### Restrict Your API Key

`secrets.xml` keeps the Gemini key out of git, but the key still ships **inside the
compiled app** once it's built — anyone who decompiles the APK/AAB can extract it. To
cap the damage if that happens, restrict the key in Google Cloud Console rather than
leaving it unrestricted:

1. Go to Google Cloud Console → **APIs & Services → Credentials** and select the Gemini key.
2. Under **Application restrictions**, choose **Android apps** and add:
   - Package name: `com.focuslockapp.myapplication`
   - The SHA-1 signing fingerprint for both your debug and release keystores.
3. Under **API restrictions**, limit the key to the **Generative Language API** only.
4. (Recommended) Set a daily quota / budget alert on the key so a leaked or abused key fails loudly instead of silently.

This stops the key being reused from a browser, a script, or another app, and caps a
leak to this app's own quota. It does **not** stop a decompiled/repackaged clone of
this app from using the embedded key — fully closing that gap means routing Gemini
calls through a backend proxy instead of calling the API directly from the client,
which is a larger change than key restriction alone.
