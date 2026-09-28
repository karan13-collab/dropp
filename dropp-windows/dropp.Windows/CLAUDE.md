# dropp — Windows Client

An Apple-like ecosystem continuity client for Windows that pairs with an Android device.

## Tech Stack & Architecture
- **Framework:** .NET 8.0, WinUI 3 (Windows App SDK)
- **Language:** C# 12
- **UI / XAML:** WinUI 3 Fluent Design (Window controls, Tray integration, Toast notifications)
- **Architecture Pattern:** MVVM (CommunityToolkit.Mvvm)

## Directory Structure
- `/Core/Networking/`: mDNS broadcasting and discovery, TCP/UDP communication, WebRTC data channels.
- `/Core/Agentic/`: AI prompt contracts, structured JSON intent routers, and command parsers.
- `/Services/`: Long-running background workers, Windows system tray controllers, notification bridge.
- `/UI/`: XAML pages, view models, and UI components.
- `/Interop/`: Low-level Windows API wrappers (P/Invoke, Win32 hooks).

## Critical Development Rules
1. **Thread Safety:** Never run blocking network calls or infinite discovery loops on the WinUI UI thread. Wrap background tasks in `Task.Run` with `CancellationToken` support.
2. **mDNS Networking:** Use `Makaretu.Dns` for advertising `_dropp._tcp` and `Zeroconf` for discovering Android peers.
3. **Capabilities:** Ensure `Package.appxmanifest` retains `internetClientServer`, `privateNetworkClientServer`, and `runFullTrust`.
4. **Branding:** The project name must always be formatted in all lowercase: `dropp`.

## Build Commands
- Restore dependencies: `dotnet restore`
- Build solution (Debug): `dotnet build -c Debug`
- Build solution (Release): `dotnet build -c Release`