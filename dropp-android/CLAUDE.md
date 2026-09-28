# dropp — Android Client

An Apple-like ecosystem continuity client for Android that pairs with a Windows PC.

## Tech Stack & Architecture
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Material 3)
- **Asynchrony:** Kotlin Coroutines & StateFlow / SharedFlow
- **Minimum SDK:** 29 (Android 10.0) | **Target SDK:** 34+
- **Architecture Pattern:** MVVM with clean separation of UI, Services, and Networking

## Directory Structure
- `app/src/main/java/<package>/core/networking/`: mDNS discovery, raw TCP/UDP sockets, WebRTC bridge.
- `app/src/main/java/<package>/core/agentic/`: Local device automation and structured intent handling.
- `app/src/main/java/<package>/services/`: Android Foreground Services (`connectedDevice`), Notification Listener service.
- `app/src/main/java/<package>/ui/`: Compose screens, theme definitions, and ViewModels.

## Critical Development Rules
1. **Never block the main thread:** All socket operations, network discovery, and file I/O must run on `Dispatchers.IO`.
2. **Foreground Service Compliance:** Any background sync or continuous listener service must declare explicit `foregroundServiceType` attributes (`connectedDevice` or `dataSync`) in `AndroidManifest.xml`.
3. **mDNS Implementation:** Use `android.net.nsd.NsdManager`. For service resolution, use modern callback flows to prevent memory leaks and handle Wi-Fi state shifts gracefully.
4. **Permissions:** Explicitly request runtime permissions (e.g., `POST_NOTIFICATIONS`, `NEARBY_WIFI_DEVICES`) before initializing services.
5. **Branding:** The project name must always be formatted in all lowercase: `dropp`.

## Build & Test Commands
- Assemble debug APK: `./gradlew assembleDebug`
- Run unit tests: `./gradlew test`
- Run lint checks: `./gradlew lint`