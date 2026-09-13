# 06 — Platforms

All v1 clients share `shared/core`, `shared/media` (interface), `shared/ui`, `protocol/` and `design/` tokens.

| | Android | Windows | macOS | Linux | iOS |
|---|---|---|---|---|---|
| Priority | **v1 (first)** | **v1 (first)** | **v1 (first)** | After v1 | Later (v2) |
| Folder | `android/` | `desktop/` | `desktop/` | `desktop/` | `apple/` |
| Language | Kotlin | Kotlin (JVM) | Kotlin (JVM) | Kotlin (JVM) | Kotlin/Native core + Swift or Compose UI |
| UI | Compose Multiplatform (`shared/ui`) | same | same | same | TBD |
| Min OS | Android 9 (API 28), target latest | Windows 10 22H2 x64, Windows 11 x64/ARM64 | macOS 13 (Apple Silicon + Intel) | Ubuntu 22.04+ class distros | iOS 18 |
| Media | libwebrtc Android SDK (`io.github.webrtc-sdk:android`) | webrtc-java | webrtc-java | webrtc-java | WebRTC.xcframework |
| Discovery | `NsdManager` | JmDNS | JmDNS | JmDNS | NWBrowser |
| Call integration | Core-Telecom (self-managed) | Incoming call window + tray | Incoming call window + menu bar | Incoming call window + tray | CallKit |
| Keys | Android Keystore | DPAPI | Keychain | Secret Service | Keychain |
| Packaging | APK / AAB | MSI (+ portable ZIP) | DMG | DEB / RPM / Flatpak | IPA |
| Distribution | GitHub, F-Droid, Google Play | GitHub, winget, Microsoft Store | GitHub, Homebrew cask | GitHub, Flathub | App Store |
| Built on | Windows dev PC | Windows dev PC | **GitHub Actions macOS runner** | GitHub Actions Linux runner | Mac + Xcode (later) |

**Why one `desktop/` folder instead of separate `windows/` and `macos/` folders:** Windows and macOS share 100% of their Kotlin code. OS-specific details (DPAPI vs Keychain, tray vs menu bar, installer config) are small adapters inside `desktop/src/main/kotlin/platform/{windows,macos,linux}` and `desktop/packaging/{windows,macos,linux}`. Separate folders would duplicate the app.

---

## 1. Android (`android/`)

### Project structure
```
android/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    ├── kotlin/app/zoocall/android/
    │   ├── ZoocallApp.kt               # Application, Koin start, core start
    │   ├── MainActivity.kt             # hosts shared/ui navigation
    │   ├── call/CallActivity.kt        # lock-screen capable call UI host (shared/ui call screen)
    │   ├── call/TelecomIntegration.kt  # Core-Telecom CallsManager (CallIntegration port)
    │   ├── call/AndroidAudioRouter.kt
    │   ├── service/ReachableService.kt # foreground service for "stay reachable"
    │   ├── service/CallService.kt      # phoneCall/microphone/camera FGS during calls
    │   ├── notify/AndroidNotifier.kt   # CallStyle, messaging style, missed calls
    │   ├── platform/NsdDiscovery.kt    # Discovery port (or in shared/core androidMain)
    │   └── platform/KeystoreKeyStore.kt
    └── res/                            # icons, adaptive launcher icon, ringtones
```

### Key APIs & behaviours
| Topic | Plan |
|---|---|
| Discovery | `NsdManager` register/discover/resolve (API 34+ `registerServiceInfoCallback` for live updates, older resolve fallback). `WifiManager.MulticastLock` held only while browsing |
| Local network permission | Handle Android 16+ local network protection (`NEARBY_WIFI_DEVICES` / local-network permission as enforced), requested just-in-time with an explanation |
| Incoming calls | **Core-Telecom** `CallsManager` (self-managed): system call integration, Bluetooth/car/watch handling, audio focus. `CallStyle` notification with full-screen intent (`USE_FULL_SCREEN_INTENT` special access check on Android 14+, with a guide if denied) |
| Foreground services | During calls: `phoneCall` + `microphone` (+ `camera` for video). "Stay reachable" mode: persistent low-priority notification ("Zoocall is reachable on Office-5G"), service type chosen per current Play policy (`specialUse` with justification). F-Droid builds are unaffected |
| Battery | Reachable mode is offered after the first call ("Receive calls when Zoocall is closed?"). Guide for OEM battery killers. Automatically pauses when leaving Wi-Fi and resumes on a known network |
| Audio | `AudioManager` communication device APIs (API 31+, legacy fallback for 28–30): earpiece/speaker/BT/wired routing, `MODE_IN_COMMUNICATION`, proximity wake lock |
| Camera | libwebrtc Camera2 capturer, front/back switch, rotation handling |
| PiP | `setAutoEnterEnabled` (API 31+), remote video only in PiP |
| Adaptive UI | Window size classes, foldables (tabletop mode for video calls), predictive back |
| Screen share (P2) | MediaProjection foreground service type `mediaProjection` |
| No Google dependencies | No GMS/Firebase, so the F-Droid build is identical to Play |
| ABIs | arm64-v8a, armeabi-v7a, x86_64 (emulator). App bundle splits per ABI |

## 2. Desktop: Windows + macOS (`desktop/`)

### Project structure
```
desktop/
├── build.gradle.kts                    # compose.desktop { application { nativeDistributions { ... } } }
├── src/main/kotlin/app/zoocall/desktop/
│   ├── Main.kt                         # application { } entry, single-instance lock
│   ├── window/MainWindow.kt            # list-detail shared UI
│   ├── window/CallWindow.kt            # resizable, always-on-top option, multi-monitor
│   ├── window/IncomingCallWindow.kt    # compact, undecorated, always on top, corner of screen
│   ├── tray/TrayController.kt          # tray (Windows) / menu bar extra (macOS): status, quit, reachable
│   ├── media/DesktopMediaEngine.kt     # webrtc-java (or in shared/media desktopMain)
│   ├── hotkeys/GlobalHotkeys.kt        # global mute (JNativeHook), opt-in
│   ├── autostart/Autostart.kt          # Windows registry Run key / macOS LaunchAgent
│   └── platform/
│       ├── windows/DpapiKeyStore.kt · WindowsFirewallCheck.kt · WindowsTheme.kt
│       ├── macos/KeychainKeyStore.kt · MacTheme.kt
│       └── linux/SecretServiceKeyStore.kt
└── packaging/
    ├── windows/                        # icon.ico, MSI upgrade UUID, firewall rule script
    ├── macos/                          # icon.icns, Info.plist additions, entitlements.plist
    └── linux/                          # icon.png, .desktop file
```

### Common desktop behaviour
| Topic | Plan |
|---|---|
| Runtime | Bundled, jlink-minimized Java 21 runtime. Users never install Java |
| Media | **webrtc-java** (libwebrtc for JVM: Windows x64, macOS x64/arm64, Linux x64/arm64). Includes audio device module (AEC/NS/AGC), camera capture and desktop/window capture for screen share |
| Video rendering | Decoded I420 frames converted to Skia `Bitmap` and drawn in a Compose `Canvas`. The conversion runs off the UI thread with a reused buffer. Optimize to direct pixel buffers if profiling shows need |
| Incoming calls | App runs in the tray / menu bar. A compact always-on-top incoming call window appears with a looped ringtone and Accept/Decline. Also brings attention (taskbar flash on Windows, dock bounce on macOS) |
| Single instance | Lock file + local socket. A second launch focuses the existing window |
| Start at login | Off by default, offered after onboarding |
| Screen sharing | Screen/window picker built on webrtc-java desktop capture (P1) |
| Hotkeys | In-app shortcuts always. Global mute hotkey opt-in (P1) |
| Devices | Mic, speaker and camera selection in settings and in-call. Hot-plug detection |
| Updates | No in-app update checks (no internet). Package managers handle updates |

### Windows specifics
| Topic | Plan |
|---|---|
| Installer | MSI via Compose `nativeDistributions` (jpackage + WiX). Per-user install (no admin) by default. Portable ZIP also offered |
| Firewall | Windows Defender Firewall prompts for inbound access on first listen. The installer adds a rule for **Private** networks (TCP control port + app executable for UDP). Network Doctor detects a blocked or "Public" network profile and shows the fix |
| Network profile | If the Wi-Fi is set to "Public", discovery is often blocked. Doctor explains how to switch to "Private" |
| Theme | Reads `AppsUseLightTheme` and accent color from registry, and listens for changes |
| Keys | DPAPI via JNA `Crypt32Util` |
| Signing | Authenticode via SignPath Foundation (free for OSS). Unsigned dev builds show SmartScreen warning, which is documented |
| ARM64 | Windows on ARM supported when webrtc-java ARM64 binaries are available. Otherwise x64 emulation |

### macOS specifics
| Topic | Plan |
|---|---|
| Build | **Must be packaged on macOS.** GitHub Actions `macos-latest` runner builds the DMG. The Windows dev PC builds and runs the Windows version only |
| Info.plist | `NSMicrophoneUsageDescription`, `NSCameraUsageDescription`, `NSLocalNetworkUsageDescription`, `NSBonjourServices = [_zoocall._tcp]` (macOS 15+ local network privacy), screen recording permission for screen share |
| Entitlements | Hardened runtime: `com.apple.security.device.audio-input`, `com.apple.security.device.camera`, `com.apple.security.cs.allow-jit` + `disable-library-validation` (JVM + native libs) |
| Signing | Developer ID + notarization needs the Apple Developer Program. Until then, unsigned DMG with documented "Open anyway" steps |
| Menu bar | Menu bar extra for status and Reachable toggle. Standard app menu with `⌘,` Settings, `⌘Q` |
| Keys | Keychain |
| Testing | Manual testing on a Mac when one with free storage is available. CI runs unit tests + launch smoke test on the macOS runner |

## 3. Linux (after v1)

The desktop app already builds for Linux. Official support after v1.0 adds: DEB/RPM + Flatpak packaging, PipeWire camera/audio verification, xdg-desktop-portal screen share on Wayland, Secret Service keys, and firewall (`ufw`/`firewalld`) detection in Network Doctor.

## 4. iOS / iPadOS (later)

Planned after Android + Desktop v1.0:
- Add iOS targets to `shared/core` (the `commonMain` rules keep this possible).
- UI: decide between Compose Multiplatform iOS (max reuse) and SwiftUI (most native) at that time.
- `MediaEngine` with WebRTC.xcframework, CallKit integration, `NWBrowser` discovery.
- **Background incoming calls** need Apple's restricted **Local Push Connectivity** entitlement (`NEAppPushProvider`) and the **multicast** entitlement. Apply for them a few months before the iOS phase starts because approval takes time.

## 5. Interop matrix (must pass before each release)

| Caller ↓ / Callee → | Android | Windows | macOS |
|---|---|---|---|
| **Android** | ✔ owner tests (2 phones) | ✔ owner tests | ✔ when Mac available |
| **Windows** | ✔ owner tests | ✔ (2 PCs or PC + CLI peer) | ✔ |
| **macOS** | ✔ | ✔ | ✔ |

Checked per pair: discovery, add + verify, audio call, video call, chat, file transfer, PTT, group call (3 devices), reconnect after Wi-Fi blip.

Automated part: every client runs end-to-end tests against the `tools/cli` peer. Manual part: a release checklist on real devices.

## 6. Platform risk register

| Risk | Impact | Mitigation |
|---|---|---|
| libsodium KMP bindings don't load on one desktop OS | Crypto blocked on that OS | Phase 0 spike on Android, Windows and macOS. Fallback to lazysodium behind the same interface |
| webrtc-java lags libwebrtc or lacks a feature (e.g. ARM64 Windows, a codec) | Desktop media limits | Phase 0 spike: Android ↔ Windows video call. Pin versions. Contribute fixes upstream |
| JmDNS ↔ NsdManager interop quirks | Peers not discovered | Phase 0 spike. Manual IP + QR fallback always available. Optional unicast probing of last-known addresses |
| Desktop Compose accessibility gaps | Screen reader issues on desktop | Manual Narrator/VoiceOver pass each release. Report and contribute upstream |
| JVM desktop size and memory | Larger installer (~100 MB), ~250 MB RAM | jlink minimal runtime, lazy loading, budgets tracked in CI |
| Google Play foreground-service policy for "reachable" mode | Play rejection | Precise justification + video. F-Droid/GitHub builds unaffected |
| OEM battery killers (Xiaomi, Samsung, etc.) | Missed calls on Android | In-app guide per OEM, Network Doctor battery check |
| Windows firewall / "Public" network profile | Incoming calls fail on PC | Installer rule, Network Doctor fix steps |
| No Mac for local testing | macOS bugs found late | CI builds + smoke tests on macOS runners, and manual testing as soon as a Mac is available |
| Enterprise Wi-Fi with client isolation / VLANs | Discovery or calls fail entirely | Network Doctor + IT admin guide. Manual IP fallback |
