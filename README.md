# dropp

**A seamless, cloud-free, native cross-platform ecosystem for Android and Windows.**

![Platform](https://img.shields.io/badge/Platform-Windows%2011%20%7C%20Android-blue)
![Tech Stack](https://img.shields.io/badge/Tech-WinUI%203%20%7C%20Kotlin-purple)
![License](https://img.shields.io/badge/License-MIT-green)

**dropp** is an open-source connectivity ecosystem designed to bridge the gap between Windows PCs and Android devices. Built as an Universal Alternative to  proprietary systems like Apple Continuity, Microsoft Phone Link, and Samsung DeX, dropp operates entirely locally via peer-to-peer connections. No cloud servers, no account logins, and no internet connection required.

It deeply integrates into the native operating systems—utilizing the Android Share Sheet and the Windows 11 Right-Click Share Menu, so it feels like a built-in OS feature, not just a standalone app.

---

## ✨ Current Features (Engine & Core Integration)

The foundational P2P engine is currently active and fully functional.

- **Zero-Config Discovery:** Instant background device discovery using mDNS. No IP addresses to type.
- **Native OS File Handoff:**
  - **Windows to Android:** Right-click any file on your PC > **Share** > **dropp**. Bypasses the Windows App SDK sandbox to beam files directly to your phone's Downloads folder.
  - **Android to Windows:** Select any file/photo > **Share** > **dropp**. Files land securely on your PC at gigabit Wi-Fi Direct/TCP speeds.
- **Universal Clipboard (Beta):** Copy text on your phone and instantly paste it on your PC (and vice-versa).
- **Background Execution:** Runs as an invisible, persistent utility on both devices.

---

## 🚀 Roadmap

dropp is evolving into a complete hardware-sharing and screen-virtualization suite. Here is the upcoming roadmap categorized by system complexity:

### Tier 1: Seamless Data & Communications (In Progress)
- [ ] **Notification Mirroring:** Android notifications appear as native Windows Toast notifications. Swiping away on PC clears them on the phone.
- [ ] **Inline Replies:** Reply to WhatsApp, Telegram, or SMS directly from the Windows popup.
- [ ] **Universal Clipboard v2:** Support for copying/pasting images, URLs, and rich files between devices.
- [ ] **Instant Hotspot & Power Control:** Wake phone via Bluetooth to toggle cellular hotspot from the PC taskbar. Lock/unlock Windows from the phone.

### Tier 2: Input & Control
- [ ] **Cross-Control (KVM):** Move your Windows mouse past the edge of your monitor to seamlessly control your Android screen and type with your PC keyboard.
- [ ] **Cross-Device Search:** Securely index and search for PDFs, photos, or documents residing on your phone directly from the Windows Start Menu.

### Tier 3: Screen & Hardware Virtualization
- [ ] **High-Res Wireless Webcam:** Mount your phone and use its main camera lenses natively in Zoom, Teams, or OBS on Windows (via Virtual Camera Driver).
- [ ] **Audio Routing / Call Passthrough:** Hand off Spotify audio to PC speakers, or route incoming cellular phone calls through the PC's microphone and WebRTC audio bridge.
- [ ] **Single App Streaming:** Launch an Android app from Windows; the UI streams into a floating Windows frame while processing locally on the phone.
- [ ] **Secondary Display:** Use your smartphone or tablet as a wireless external monitor for Windows.
- [ ] **Mobile Desktop Mode:** Cast a full, customized desktop UI from the phone to a Windows monitor.

### Tier 4: AI-Driven Automation
- [ ] **Contextual Share:** Highlight text on Windows and send it to the phone's local LLM to summarize or add to a calendar.
- [ ] **Natural Language Routing:** Voice commands (e.g., *"Cast this video to my laptop"*) to automate the device handshake and protocol routing.

---

## 🛠️ Architecture & Tech Stack

**dropp** is built with modern, native frameworks to ensure minimal latency and battery drain.

- **Windows Client:** C#, .NET 8, WinUI 3 (Windows App SDK). Packaged as an MSIX application for deep OS-level registry hooks (Share Target, Notifications).
- **Android Client:** Kotlin, Jetpack Compose.
- **Networking Engine:** Custom asynchronous TCP socket architecture wrapped with local mDNS broadcasting.
- **Future Protocols:** WebRTC (for AV streaming), Android Accessibility Services (for KVM), Windows Virtual Device Drivers (for webcam).

---

## 💻 Getting Started (For Developers)

Because dropp utilizes deep OS hooks, specific configurations are required to build and run the project locally.

### Prerequisites
- **Visual Studio 2022** (v17.10+) with the following workloads:
  - `.NET desktop development`
  - `Windows App SDK C# Templates`
  - `Single-project MSIX Packaging Tools` (Required to compile the Native Share menu)
- **Android Studio** (Latest)
- **Windows 11 Developer Mode** (Must be enabled in Windows Settings to sideload the WinUI 3 app).

### Building the Windows Client
1. Clone the repository.
2. Open `dropp.Windows.sln` in Visual Studio 2022.
3. Ensure the build configuration is set to **x64**.
4. Set the Run profile to **dropp.Windows (Package)**.
5. Press **F5** to build, register the app with Windows 11, and launch.

### Building the Android Client
1. Open the Android project directory in Android Studio.
2. Sync Gradle files.
3. Deploy to a physical Android device on the same local network as your PC (Emulators may have trouble resolving mDNS packets across virtual NATs).

---

## 🤝 Contributing
Contributions, issues, and feature requests are welcome! Because of the ambitious roadmap, developers with experience in **WebRTC, Windows Driver Development, or Android Accessibility Services** are highly encouraged to contribute.

## 📄 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
