# 💧 dropp

![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Android-blue?style=for-the-badge)
![Windows](https://img.shields.io/badge/WinUI_3-C%23-0078D7?style=for-the-badge&logo=windows)
![Android](https://img.shields.io/badge/Android-Kotlin_|_Compose-3DDC84?style=for-the-badge&logo=android)
![Status](https://img.shields.io/badge/Status-Active_Development-success?style=for-the-badge)

**dropp** is a cross-platform ecosystem continuity application designed to bridge the gap between Windows PCs and Android devices. Inspired by native ecosystem features like Apple's AirDrop and Universal Clipboard, `dropp` enables seamless, serverless local peer-to-peer discovery and data transfer over standard Wi-Fi.

No cloud servers. No accounts. Just instant, secure, local connection.

---

## ✨ Features (Planned & Active)
* 🔍 **Serverless Discovery:** Devices automatically find each other on the local network using Multicast DNS (mDNS).
* 📋 **Universal Clipboard (WIP):** Instantly sync copied text between your PC and Android phone.
* 📁 **Proximity File Drop (WIP):** Drag, drop, and blast binary files across the room over robust, chunked TCP sockets.
* 🔋 **Battery Efficient:** Utilizes standard Android foreground services and strict network state monitoring to prevent battery drain.

---

## 🏗️ Architecture & Tech Stack

The `dropp` ecosystem is built on a clean, modern, native stack for both platforms:

### 🖥️ dropp.Windows
* **Framework:** WinUI 3 / Windows App SDK (Unpackaged execution to bypass MSIX restrictions)
* **Language:** C# (.NET 8)
* **Networking:** `Makaretu.Dns` and `Zeroconf` for mDNS broadcasting and listening.
* **Architecture:** MVVM with strict separation of UI, Services, and Core/Networking layers.

### 📱 dropp-android
* **Framework:** Jetpack Compose 
* **Language:** Kotlin
* **SDK Target:** API 35 (Android 15), Minimum API 29 (Android 10)
* **Networking:** Android Native `NsdManager` bound to robust foreground services.

---

## 🚀 Rollout Roadmap

The development of `dropp` is structured into strict, tested phases to ensure network reliability before scaling data features.

### 🟢 Phase 1: Local Network Discovery (Completed)
Establishing the foundation for peer-to-peer communication without hardcoded IPs.
- [x] Scaffold WinUI 3 and Android Compose clean architectures.
- [x] Implement Android `NsdManager` broadcasting and discovery.
- [x] Implement Windows `Zeroconf` mDNS resolution.
- [x] Configure Android 14+ strict Foreground Service permissions (`connectedDevice`, `CHANGE_WIFI_STATE`).
- [x] Bypass OS firewalls and establish two-way IP handshakes across standard Wi-Fi and Mobile Hotspots.

### 🟡 Phase 2: The Data Pipe (In Progress)
Opening direct TCP sockets utilizing the IPs resolved in Phase 1.
- [ ] **Universal Clipboard:** Lightweight TCP stream that listens for OS clipboard changes and syncs UTF-8 payloads instantly.
- [ ] **Proximity File Drop:** Chunked binary TCP transfer protocol with metadata headers (filename, size) to prevent memory exhaustion on large files.
- [ ] **File UI:** Drag-and-drop zones in WinUI 3 and file-picker intents in Android.

### ⚪ Phase 3: Security & Polish (Upcoming)
Securing the local connection and refining the user experience.
- [ ] TLS/SSL socket encryption for all local traffic.
- [ ] Device pairing/trust mechanisms (accept/reject prompts).
- [ ] System tray minimization (Windows) and quick-settings tiles (Android).
- [ ] Polished UI/UX with connection status indicators.

---

## 🛠️ Getting Started (Local Development)

Because `dropp` relies on direct peer-to-peer network traffic, your local development environment must be configured correctly.

### Prerequisites
* **Windows:** Visual Studio 2022 (with .NET Desktop Development and Windows App SDK workloads).
* **Android:** Android Studio Ladybug (or newer), physical Android device (emulators often block mDNS bridging).

### Network Requirements ⚠️
`dropp` requires a network that allows Client/AP isolation to be disabled.
* **Supported:** Standard Home Wi-Fi routers, Windows Mobile Hotspots, Android Mobile Hotspots.
* **Unsupported:** iPhone Personal Hotspots (Apple enforces strict AP isolation), Enterprise/University Wi-Fi, Public Cafe Wi-Fi.
* **Windows Firewall:** Your Windows Wi-Fi connection profile **must be set to Private**. (Public networks block inbound UDP port 5353).
