# ⚡ J.A.R.V.I.S. — Universal Hands-Free AI Voice Assistant for Android

An Iron Man inspired, 100% free, zero-touch voice assistant built for Android that controls your phone, reads incoming messages from any app (WhatsApp, Instagram, SMS), makes/answers calls, executes system controls, and sees the world using **Google Gemini 1.5 Flash**.

---

## 🌟 Supercharged Features

1. **👁️ Vision AI Scanner (`Gemini 1.5 Flash Multimodal`):**
   - Say *"Jarvis, samne kya hai dekho"* or *"Ye kya likha hai padh kar batao"*.
   - Optical camera HUD activates, captures visual context, and Gemini speaks the description aloud.

2. **⚡ Floating Arc Reactor Overlay Widget (`SYSTEM_ALERT_WINDOW`):**
   - Movable Iron Man Arc Reactor bubble on your screen across all apps.
   - Tap anywhere to speak commands without opening the main app.
   - Features glowing neon pulse animation when listening.

3. **🎵 Media & Music Control (Spotify, YouTube, Device):**
   - *"Jarvis, Spotify par Arijit Singh ke gaane chalao"*
   - *"YouTube par trending videos chalao"*
   - Hands-free controls: *"Next song"*, *"Pause gaana"*, *"Volume 80% karo"*.

4. **💬 Full WhatsApp Automation (`AccessibilityService`):**
   - Reads incoming notifications: *"Rahul ne WhatsApp par message bheja hai: 'Kaha ho bhai?'"*
   - Direct Voice Reply: Say *"Reply karo: 'Mai abhi ghar ja raha hu'"*.
   - Autonomous WhatsApp messaging: *"Rahul ko WhatsApp par message karo"*.

5. **⏰ Alarms, Timers & Reminders (`AlarmClock`):**
   - *"Subah 6 baje ka alarm laga do"*
   - *"10 minute ka timer lagao"*
   - *"Shaam ko 5 baje reminder lagao"*

6. **🔋 Device Diagnostics & Battery Status:**
   - *"Jarvis, battery kitni hai?"* -> *"Battery level 85 percent hai aur phone charging par hai, Sir."*
   - Flashlight control (*"Torch on/off"*), WiFi, and Bluetooth shortcuts.

7. **📞 Full Phone Calling Control (`CallManager`):**
   - Incoming call announcement: *"Incoming call from Papa"*.
   - Say *"Uthao"* to answer or *"Cut kar do"* to reject hands-free.
   - Say *"Jarvis, Rahul ko call lagao"* to dial contacts automatically.

8. **24/7 Background Persistence & Permanent Signature:**
   - Auto-starts on reboot (`BootReceiver`) and runs in background.
   - Embedded release keystore guarantees every build shares the same signature for seamless in-place updates.

---

## 🚀 How to Build the APK (Without Android Studio!)

APK compiles automatically in the cloud via **GitHub Actions**:

1. Push to your repository:
   ```bash
   git add .
   git commit -m "Supercharge Jarvis with Vision, Floating Arc Reactor & Media controls"
   git push origin main
   ```
2. Go to your repository on GitHub.com and click the **Actions** tab.
3. Click on the running workflow **"Build Jarvis APK"**.
4. Once completed (~2-3 minutes), download **`jarvis-assistant-debug-apk`** or **`jarvis-assistant-release-apk`** under **Artifacts**!

---

## 📱 Phone Installation & Setup

1. **Install APK on Phone:**
   - If Play Protect shows *"Blocked by Play Protect"*:
     - Tap **More details** (˅) -> **Install anyway (unsafe)**.
2. **Launch J.A.R.V.I.S.:**
   - Grant **Notification Interceptor** (WhatsApp/SMS).
   - Grant **Screen Control & Automation** (Accessibility).
   - Grant **Microphone & Call Handling**.
   - Grant **Floating Arc Reactor** (Display over other apps).
   - Grant **Camera & Vision AI Scanner**.
3. **AI Configuration:**
   - Enter Assistant Name (e.g. `Jarvis`).
   - Paste free **Google Gemini API Key** from [aistudio.google.com](https://aistudio.google.com/).
   - Tap **Save AI Config**.
4. Toggle **Floating Arc Reactor On Screen** and experience the real-life Iron Man JARVIS!
