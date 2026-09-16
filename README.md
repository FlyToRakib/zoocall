# Zoocall

**Free, open-source, end-to-end encrypted calling and messaging over your local Wi-Fi. No internet, no accounts, no servers.**

Audio calls, video calls and chat between **Android phones and Windows / macOS computers** on the same network. iOS and Linux come later.

> **Status:** milestone M1 (first call) in progress. Android ↔ Android is the first target, then Android ↔ Desktop. See the [plan](docs/00-zoocall-final-plan.md) and [roadmap](docs/09-roadmap.md).

## Implementation status

"Tested" means covered by automated tests on the JVM. Everything marked *device testing pending* is implemented but hasn't been run on real phones or PCs yet.

| Area | State |
|---|---|
| Noise XX secure channel, safety codes, frame codec | Tested (official Noise vectors + integration tests) |
| Transport: handshake, Hello, keepalive, duplicate-connection rule, rate limits | Tested (in-memory network) |
| 1:1 call state machine and signaling (ring, accept, decline, busy, collision, mute) | Tested (fake media engine) |
| Encrypted database (SQLCipher) and DPAPI key storage | Tested on Windows |
| Discovery (Android NsdManager, desktop JmDNS), add by IP, QR add | Device testing pending |
| Audio/video media (libwebrtc Android, webrtc-java desktop) | Device testing pending |
| Android shell: Core-Telecom, lock-screen call screen, CallStyle notifications, foreground service | Device testing pending |
| Shared UI: onboarding, People, verify, chat, recents, settings, call screen | Device testing pending |

## Repository layout

| Folder | Contents |
|---|---|
| [`docs/`](docs/) | Plan, architecture, protocol, security, UX, roadmap, dev setup |
| [`protocol/`](protocol/) | `.proto` schema, normative [spec](protocol/spec/protocol-v1.md), capability registry, test vectors |
| [`shared/core/`](shared/core/) | Kotlin Multiplatform core: model, protocol, crypto, transport, discovery, call, chat, store, app facade |
| [`shared/media/`](shared/media/) | `MediaEngine` port + libwebrtc (Android) and webrtc-java (desktop) implementations |
| [`shared/ui/`](shared/ui/) | Compose Multiplatform design system and every screen |
| [`android/`](android/) | Android app shell: Telecom, foreground service, notifications, QR scanner |
| [`desktop/`](desktop/) | Desktop app shell for Windows and macOS: tray, call windows, packaging |
| [`tools/cli/`](tools/cli/) | Headless test peer |
| [`build-logic/`](build-logic/) | Gradle convention plugins |

## Build and run

Requirements: JDK 21 and the Android SDK (see [docs/11-dev-environment-windows.md](docs/11-dev-environment-windows.md)).

Run all shared tests:

```bash
./gradlew desktopTest
```

Build and install the Android app on a connected phone:

```bash
./gradlew :android:installDebug
```

Run the desktop app:

```bash
./gradlew :desktop:run
```

Run the command-line test peer:

```bash
./gradlew :tools:cli:run --args="--name TestPeer"
```

Real calls need real devices on the same Wi-Fi. The Android emulator sits behind its own NAT and can't be discovered on the LAN.

## License

[MIT](LICENSE). Free forever.
