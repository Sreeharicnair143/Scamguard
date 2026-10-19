# 🛡️ ScamGuard AI — Smart Android Dialer & Telecom Fraud Protection

**ScamGuard AI** is a privacy-preserving, Material You dark-themed replacement Default Phone Dialer (`ROLE_DIALER`) and In-Call application for Android. It replaces the default phone application to provide proactive caller reputation, crowdsourced spam blocking, built-in call recording with an interactive media player, and a context-aware call management experience—while remaining architecturally ready for real-time on-device Edge AI threat detection.

---

## ✨ Key Features & Architecture

### 1. Smart In-Call Service (`ScamGuardInCallService`)
The core background telephony service extending Android's `InCallService` to manage the complete call lifecycle:
- **Context-Aware Incoming Call Presentation**:
  - **Phone Locked / Screen Off**: Wakes the screen and launches the full-screen incoming-call UI directly over the lock screen (`showWhenLocked` / `turnScreenOn`) so users can view caller details and answer or decline without unlocking the phone.
  - **Phone Unlocked / Using Another App**: Displays a non-intrusive, high-priority `NotificationCompat.CallStyle` heads-up banner with **Answer** and **Decline** actions without interrupting the user's active app.
- **Persistent Ongoing-Call Notification**: Maintains a native `CallStyle` foreground service notification when the app is minimized, complete with a live chronometer timer and working **Mute**, **Speaker**, **Hold/Resume**, and **End Call** actions.
- **Complete Call & Audio Controls**: Handles answering, rejecting, holding/resuming, inline 3x4 DTMF keypad tones, speakerphone/earpiece/Bluetooth routing, and **Add Call** with carrier conference call merging via the `⋮ More` menu.

### 2. Scam Intelligence & Caller ID (`ScamIntelligenceRepo`)
A local threat-intelligence and contact management repository designed to evaluate callers and block unwanted spam:
- **Caller Reputation & Heuristics**: Evaluates incoming and dialed phone numbers, assigning risk levels (`SAFE`, `SUSPICIOUS`, `CRITICAL_SCAM`) and automatically flagging TRAI `140`-series promotional telemarketers vs. `160`-series transactional banking/service lines.
- **Community Spam Reporting & Auto-Blocking**: Allows users to tag unknown caller names, categorize scams (e.g., Digital Arrest, CBI/Authority Impersonation, Courier Fraud, OTP Phishing), and automatically reject incoming calls from blacklisted numbers.
- **Canonical Contact Deduplication & Account Filtering**: Normalizes phone numbers into 10-digit canonical keys to prevent duplicate contact rows and supports filtering contacts by synced Google Accounts.
- **Searchable Favorites Management**: Features a searchable **Manage Favorites** bottom sheet (`+ Add`) with instant name/number filtering and a horizontal quick-dial Favorites strip at the top of the Recents and Contacts tabs.

### 3. Built-In Call Recording & Interactive Audio Player
- **Hardware-Verified Call Recording**: Captures active call audio (where supported by the device and Android OS) into `.m4a` AAC files stored inside the app's dedicated `CallRecordings` directory.
- **Persistent Metadata Tracking**: Logs the caller's name, phone number, recording timestamp, duration, and file path in persistent JSON metadata with automatic disk discovery across app restarts.
- **Interactive Media Player Bottom Sheet**: Tapping any saved recording opens a custom player featuring **Play/Pause**, a scrubbable **`SeekBar`**, live elapsed/total time (`00:00 / 00:00`), media speaker routing (`USAGE_MEDIA`), and file deletion cleanup.

### 4. Swipe-Up Quick SMS Replies
- Enables users to swipe up (or tap) the **💬 Reply with SMS** pill on the incoming-call screen to send preset or custom SMS replies via `SmsManager` and automatically terminate the incoming call (with built-in VoLTE/IMS modem retry handling).

### 5. Premium Dialer UI & 6-Tab Recents Filtering
- **Smart T9 Keypad**: Rounded Material You keypad with live contact matching, instant Caller ID lookup, vector handset icons, and one-tap contact saving.
- **6-Tab Call Log Filters**: Filter recent calls instantly by **All**, **Missed**, **Contacts**, **Non-Spam**, **Spam**, and **Recordings**.
- **Modern Contact Action Sheet**: Tapping any contact or call log opens a bottom sheet with aligned **Call**, **Send SMS**, and **Favorite** buttons alongside **Edit Contact** (opening Android's native contact editor without creating duplicates), **Report Scam**, and **Block Number**.

---

## 🛠️ Tech Stack & System Requirements

- **IDE**: [Android Studio](https://developer.android.com/studio) (Ladybug / Meerkat or latest stable version recommended)
- **Language**: Kotlin
- **UI Architecture**: XML Views with `ViewBinding` enabled and programmatic Material You (`GradientDrawable` / `RippleDrawable`) styling
- **JDK Version**: Java 11 (`JavaVersion.VERSION_11`)
- **Android SDK Configuration**:
  - `minSdk = 31` (Android 12.0+)
  - `targetSdk = 37`
  - `compileSdk = 37`
- **Core Android APIs**:
  - `android.telecom.InCallService` & `TelecomManager`
  - `android.app.role.RoleManager` (`ROLE_DIALER`)
  - `androidx.core.app.NotificationCompat.CallStyle`
  - `android.telephony.SmsManager`
  - `android.media.MediaRecorder`, `MediaPlayer`, & `MediaMetadataRetriever`
  - `android.provider.ContactsContract` & `CallLog.Calls`

---

## 📂 Project Structure

```text
app/src/main/
├── AndroidManifest.xml                              # Permissions, ROLE_DIALER intents, InCallService & Receiver
├── java/com/example/scamguard/
│   ├── MainActivity.kt                              # Dialer UI, Search, Favorites, Filters, In-Call UI & Audio Player
│   ├── ScamGuardInCallService.kt                    # Telecom InCallService, CallStyle Notifications, SMS & Recorder
│   ├── CallNotificationActionReceiver.kt            # BroadcastReceiver for background notification call actions
│   └── ScamIntelligenceRepo.kt                      # Caller ID lookup, Spam DB, Favorites & Recordings storage
└── res/
    ├── drawable/
    │   ├── ic_call_answer.xml                       # Upright vector telephone handset icon
    │   └── ic_call_end.xml                          # Tilted hang-up vector telephone handset icon
    └── layout/
        └── activity_main.xml                        # Main Dialer tabs & Full-Screen Incoming/Active Call layouts
```

---

## 🚀 Detailed Setup Guide for Teammates (Android Studio)

Follow these step-by-step instructions to set up, build, and run **ScamGuard AI** on your local machine:

### Prerequisites
1. Install the latest stable version of **[Android Studio](https://developer.android.com/studio)**.
2. A **physical Android smartphone** running **Android 12 (API 31)** or higher is strongly recommended for testing real cellular calls, ringtones, SMS replies, and audio routing (an Android 12+ emulator can also be used for UI testing).

### Step 1: Clone the Repository
Open your terminal (or Git Bash / PowerShell) and run:
```bash
git clone [https://github.com/Sreeharicnair143/Scamguard.git](https://github.com/Sreeharicnair143/Scamguard.git)
cd Scamguard
```

### Step 2: Open the Project in Android Studio
1. Launch **Android Studio**.
2. Click **Open** on the welcome screen (or go to **File > Open** if another project is already open).
3. Navigate to the cloned `Scamguard` folder, select it, and click **OK**.
4. Android Studio will automatically generate a local `local.properties` file pointing to your machine's Android SDK directory.

### Step 3: Verify SDK & JDK Configuration
1. **Install Android SDK 37**:
   - Go to **Tools > SDK Manager** (or **Settings > Languages & Frameworks > Android SDK**).
   - Under the **SDK Platforms** tab, ensure **Android SDK Platform 37** is checked and installed.
   - Under the **SDK Tools** tab, ensure **Android SDK Build-Tools** and **Android SDK Platform-Tools** are installed.
2. **Verify Gradle JDK 11+**:
   - Go to **File > Settings > Build, Execution, Deployment > Build Tools > Gradle** (or **Android Studio > Settings** on macOS).
   - Ensure **Gradle JDK** is set to **JDK 11** (or Android Studio's embedded JetBrains Runtime **jbr-17 / jbr-21**, which supports compiling target Java 11).

### Step 4: Sync Project with Gradle Files
1. Wait for Android Studio to complete the automatic Gradle sync shown in the bottom status bar.
2. If it does not start automatically, click the **"Sync Project with Gradle Files"** icon (elephant with an arrow) in the top-right toolbar.

> **💡 Windows Build Troubleshooting (File-Locking Fix):**
> If you are building on Windows and see a `FileNotFoundException (...The process cannot access the file because it is being used by another process)` error during compilation, verify that these two lines are present at the bottom of your root `gradle.properties` file:
> ```properties
> org.gradle.workers.max=1
> org.gradle.vfs.watch=false
> ```
> If a background daemon is still holding a lock, open the Android Studio **Terminal** (`Alt + F12`), run `./gradlew --stop`, and click **Run (`▶`)** again.

### Step 5: Connect Your Device & Run the App
1. **On a Physical Android Phone (Recommended)**:
   - Open **Settings > About phone** and tap **Build number** 7 times to unlock Developer Options.
   - Go to **Settings > System > Developer options** and enable **USB debugging** (or **Wireless debugging**).
   - Connect your phone to your PC via USB and tap **Allow** on the USB debugging prompt.
2. Select your connected phone (or virtual emulator) from the device dropdown menu in the top Android Studio toolbar.
3. Click the green **Run (`▶`)** button or press **`Shift + F10`**.

### Step 6: Set ScamGuard as the Default Phone App (`ROLE_DIALER`)
Because ScamGuard replaces the system phone dialer, Android requires it to hold the **Default Phone App** role in order to intercept incoming calls and display the custom in-call screen:
1. **Automatic Prompt**: When you open **ScamGuard** for the first time, it will automatically display the system prompt: *"Set Scamguard as your default phone app?"* — select **Scamguard** and tap **Set as default**.
2. **Grant Runtime Permissions**: Accept the prompts for **Phone**, **Contacts**, **Call Logs**, **SMS**, **Microphone**, and **Notifications**.
3. **Manual Fallback**: If you dismissed the prompt on first launch, either tap the **Enable** banner at the top of the app or configure it manually on your phone via:
   - **Settings > Apps > Default apps > Phone app > Scamguard**

---

## 📊 Verified Device Performance Benchmarks

Profiled on physical Android hardware using Android Studio Profiler, `adb shell dumpsys meminfo`, and `adb shell dumpsys batterystats`:

| Metric Category | Measured Value | Technical Notes |
| :--- | :--- | :--- |
| **Total Memory Footprint (PSS)** | **~121.1 MB** (`121,136 KB`) | Includes UI, active Telecom bindings, and synced contact list |
| **Java / Kotlin Heap** | **~15.2 MB** (`15,232 KB`) | **0 Memory Leaks / 0 Duplicates** verified via Heap Dump analyzer |
| **Native C/C++ Heap** | **~8.0 MB** (`8,052 KB`) | Clean native footprint with ample headroom for Edge AI models |
| **Graphics Buffers** | **~46.7 MB** (`46,752 KB`) | Hardware-accelerated dark UI cards, avatars, and vector drawables |
| **Idle CPU Utilization** | **0% – 1%** | Near-zero background processor load when idle |
| **Active Call / Sync CPU** | **5% – 15%** | Brief spikes during contact queries and audio route transitions |
| **Background Battery Drain** | **Negligible** (`107 ms` partial wakelock) | Releases wakelocks cleanly and enters deep sleep after call end |

---
*Happy Coding!*
