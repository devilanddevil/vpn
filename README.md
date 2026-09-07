# ⚡ J.A.R.V.I.S. — Universal Hands-Free AI Voice Assistant for Android

An Iron Man inspired, 100% free, zero-touch voice assistant built for Android that controls your phone, reads incoming messages from any app (WhatsApp, Instagram, SMS), makes/answers calls, and executes system controls using **Google Gemini 1.5 Flash**.

---

## 🌟 Key Features

1. **Universal Notification Interceptor (`NotificationListenerService`):**
   - Intercepts incoming messages from **WhatsApp, Instagram, Telegram, SMS, Gmail**, etc.
   - Speaks the sender and message aloud: *"Rahul ne WhatsApp par message bheja hai: 'Kaha ho bhai?'"*
   - **Direct Voice Reply:** Say *"Reply karo: 'Mai abhi ghar ja raha hu'"* to send the reply directly in the background without unlocking the phone or opening the app!

2. **Full Phone Calling Control (`CallManager`):**
   - Incoming call announcement: *"Incoming call from Papa"*.
   - Say *"Uthao"* to answer or *"Cut kar do"* to reject hands-free.
   - Say *"Jarvis, Rahul ko call lagao"* to dial contacts automatically.

3. **Screen & Device Automation (`AccessibilityService`):**
   - Taps buttons, types text, scrolls screens, and launches apps by voice.
   - Controls Flashlight (Torch ON/OFF), Volume, and Home/Back navigation.

4. **Bilingual AI Brain:**
   - Understands **Hindi, Hinglish, and English** effortlessly using Google Gemini 1.5 Flash.

5. **24/7 Background Persistence:**
   - Runs in the background with a foreground service and auto-starts when the phone reboots (`BootReceiver`).

---

## 🚀 How to Build the APK (Without Android Studio!)

You do not need to install Android Studio on your PC. The APK compiles automatically in the cloud via **GitHub Actions**:

1. Push this code to your GitHub repository:
   ```bash
   git add .
   git commit -m "Initialize Jarvis AI Assistant"
   git push origin main
   ```
2. Go to your repository on GitHub.com and click the **Actions** tab.
3. Click on the running workflow **"Build Jarvis APK"**.
4. Once completed (~2 minutes), scroll down to **Artifacts** and click **`jarvis-assistant-release-apk`** to download your APK directly onto your phone!

---

## 📱 Phone Installation & Setup (One-Time)

1. Open the downloaded `.apk` file on your phone and tap **Install**.
2. Launch **J.A.R.V.I.S.**:
   - Tap **Grant** on **Notification Interceptor** and toggle ON Jarvis.
   - Tap **Grant** on **Screen Control & Automation** (Accessibility) and toggle ON Jarvis.
   - Tap **Grant** on **Microphone & Calls** to allow voice recording and call control.
3. In **AI Configuration**:
   - Enter your Assistant Name (e.g. `Jarvis`, `Friday`, `Chitti`).
   - Paste your free **Google Gemini API Key** from [aistudio.google.com](https://aistudio.google.com/).
   - Tap **Save AI Config**.
4. Done! You can now control your phone completely by voice.
