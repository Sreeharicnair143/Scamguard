# Scamguard

**Scamguard** is a custom Android Dialer and In-Call application designed to protect users from spam and scam calls. It replaces the default phone application to provide real-time scam intelligence, caller reputation, built-in call recording, and an enhanced call management experience.

## Key Features & Services

### 1. In-Call Service (`ScamGuardInCallService`)
This is the core background service that extends Android's `InCallService`. It acts as the backbone for managing active phone calls.
* **Call Management**: Handles answering, rejecting, putting calls on hold, and merging conference calls.
* **Audio Routing**: Toggles speakerphone and mutes/unmutes the microphone.
* **Call Notifications**: Manages high-priority heads-up notifications for incoming calls and ongoing silent notifications for active calls, seamlessly integrating with the Android system.
* **Lock-screen Support**: Capable of displaying the call UI directly over the lock screen for immediate access.

### 2. Scam Intelligence (`ScamIntelligenceRepo`)
A local repository designed to provide caller reputation and block unwanted calls.
* **Caller Reputation**: Evaluates incoming phone numbers and assigns risk levels (`SAFE`, `SUSPICIOUS`, `CRITICAL_SCAM`).
* **Spam Reporting & Blocking**: Allows users to report numbers, categorize them, and automatically block known scammers.
* **Favorites Management**: Keeps track of trusted contacts and favorites, ensuring they are never blocked.

### 3. Call Recording
Built-in capabilities to record phone conversations directly from the active call.
* **Secure Storage**: Records and saves audio files securely in the app's internal or external storage.
* **Metadata Tracking**: Keeps a history of call recordings, logging the caller's name, phone number, timestamp, and duration.

### 4. Quick SMS Replies
Enables users to reject incoming calls with pre-defined or custom SMS messages quickly.

---

## 🚀 Setup Guide for Teammates (Android Studio)

To start contributing to the project, follow these steps to set up your local development environment:

### Prerequisites
* Ensure you have [Android Studio](https://developer.android.com/studio) installed (latest stable version recommended).
* An Android device or emulator running Android 8.0 (API 26) or higher.

### Step-by-step Setup
1. **Clone the Repository**:
   ```bash
   git clone https://github.com/Sreeharicnair143/Scamguard.git
   ```
2. **Open the Project**:
   * Launch Android Studio.
   * Click on **Open** (or `File > Open` if a project is already open).
   * Navigate to the folder where you cloned `Scamguard` and select it.
3. **Sync Gradle**:
   * Android Studio will automatically start syncing the project with Gradle. 
   * If it doesn't, click the **"Sync Project with Gradle Files"** icon in the top toolbar (the elephant icon with an arrow).
4. **Build and Run**:
   * Select your emulator or physical device from the target device drop-down menu in the toolbar.
   * Click the **Run** button (green play icon) or press `Shift + F10`.

### ⚠️ Important Testing Note
Because Scamguard is a custom Dialer app, you **must set it as the Default Phone App** on your device/emulator to test the incoming and outgoing call screens properly.
1. Install the app on your device/emulator.
2. Go to **Settings > Apps > Default apps > Phone app**.
3. Select **Scamguard** from the list.
4. Ensure all necessary permissions (Phone, Contacts, Microphone, Notifications, etc.) are granted.

---
*Happy Coding!*
