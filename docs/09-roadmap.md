# 09 — Roadmap

Milestones are **scope-based with exit criteria**, not date-based. Each ends with a usable, tagged release.

**Rev. 2 focus:** Android and Desktop (Windows + macOS) are built **together** from one Kotlin codebase. The owner tests **Android ↔ Android** and **Android ↔ PC**. iOS and official Linux support come after v1.0.

```mermaid
flowchart LR
  M0[M0<br/>Foundations<br/>+ spikes] --> M1[M1<br/>First call<br/>Android ↔ Android<br/>Android ↔ Windows<br/>v0.1]
  M1 --> M2[M2<br/>Everyday calling<br/>+ chat<br/>v0.2]
  M2 --> M3[M3<br/>Team features<br/>v0.3]
  M3 --> M4[M4<br/>Hardening<br/>v1.0]
  M4 --> M5[M5<br/>Stand-out<br/>v1.x]
  M5 --> M6[iOS · Linux official<br/>v2]
```

---

## M0 — Foundations + technical spikes

**Goal:** a working build on the Windows dev PC and proof that the risky libraries work.

Setup
- [ ] Windows dev environment ready ([11-dev-environment-windows.md](11-dev-environment-windows.md))
- [ ] Gradle monorepo skeleton: `shared/core/*`, `shared/media`, `shared/ui`, `android`, `desktop`, `tools/cli`
- [ ] `.gitattributes`, `.editorconfig`, CONTRIBUTING, SECURITY, CODE_OF_CONDUCT, CODEOWNERS
- [ ] CI: `ci-shared.yml`, `android.yml`, `desktop.yml` (Windows + macOS runners)
- [ ] `protocol/proto/zoocall/v1` schemas + Wire code generation
- [ ] Design: brand seed color, app icon draft, tokens → Kotlin theme, light/dark
- [ ] Hi-fi mockups (phone + desktop): onboarding, People, incoming call, in-call, chat, add + verify

Spikes (each is a small throwaway test that proves a risky part works)
- [ ] **Crypto:** libsodium KMP loads and works on Android, Windows and macOS (CI). Noise XX passes official test vectors
- [ ] **Discovery:** Android `NsdManager` ↔ Desktop `JmDNS` see each other on a real Wi-Fi network
- [ ] **Media:** Android libwebrtc ↔ Windows webrtc-java audio + video call with manually exchanged SDP on LAN
- [ ] **Storage:** SQLDelight + SQLCipher works on Android and desktop JVM
- [ ] **CLI milestone:** two CLI peers (Windows PC + another machine or container) discover each other, verify SAS and chat over Noise

**Exit:** all spikes green (or a fallback chosen and documented as an ADR), CI green on Windows + macOS + Linux runners, mockups approved.

## M1 — First call (v0.1 alpha)

**Goal:** Android ↔ Android and Android ↔ Windows 1:1 calls that feel finished. macOS builds in CI.

Core
- [ ] Identity + key storage (Android Keystore, DPAPI, Keychain)
- [ ] Transport: listener, Noise handshake, framing, keepalive, reconnection, rate limits
- [ ] Discovery + manual IP
- [ ] Call state machine (1:1), signaling, collision, timeouts, DTLS fingerprint binding
- [ ] Storage: profile, contacts, calls

App (Android + Desktop, shared UI)
- [ ] Onboarding (name + avatar)
- [ ] People: nearby + contacts, search
- [ ] Add contact with **safety code** and **QR** (phone scans PC's QR or another phone's QR)
- [ ] 1:1 **audio** and **video** call: mute, camera on/off, flip camera (phone), device selection (PC), audio routing (phone), proximity
- [ ] **Incoming call**: Android Core-Telecom + full-screen notification. Desktop tray + always-on-top incoming call window + ringtone
- [ ] Missed calls + Recents
- [ ] Theme: System/Light/Dark, dynamic color on Android
- [ ] Accessibility pass on the call flow

Release
- [ ] APK on GitHub Releases, Windows MSI + macOS DMG (unsigned) on GitHub Releases

**Exit:** owner makes Android ↔ Android and Android ↔ Windows audio + video calls on home Wi-Fi. Call setup < 1 s. No WAN traffic. Five non-technical testers make a call within 60 s without help.

## M2 — Everyday calling + chat (v0.2 beta)

- [ ] 1:1 **text chat** with offline outbox, delivery/read receipts, typing indicator
- [ ] Replies, reactions, message search
- [ ] **Photos & files** with progress, pause/resume, hash verification
- [ ] Presence: Available / Busy / DND / Away, custom status, auto-busy in call
- [ ] Decline with quick message, call waiting / hold
- [ ] Audio → video upgrade with consent
- [ ] **Reconnecting** after Wi-Fi blips (ICE restart), quality indicator, call stats panel
- [ ] Picture-in-Picture (Android), separate resizable call window (Desktop)
- [ ] "Stay reachable" foreground mode (Android), start at login + tray (Desktop)
- [ ] Favorites, block list, "Allow calls from", stranger requests
- [ ] Windows firewall rule in installer

**Exit:** a small team (≥ 5 devices, mixed Android + Windows) uses Zoocall for a week of daily calls and chat.

## M3 — Team features (v0.3 beta)

- [ ] **Group calls:** audio ≤ 8, video ≤ 4, add people mid-call
- [ ] **Push-to-talk** (1:1 and group), including desktop `Space` hotkey
- [ ] **Knock** with one-tap replies
- [ ] **Voice notes**
- [ ] **Screen sharing** from Desktop (receive on Android and Desktop)
- [ ] **Network Doctor** (Wi-Fi, VPN, multicast, client isolation, Windows Public profile, firewall, virtual adapters)
- [ ] Flash alert for incoming calls (accessibility), ringtones & sounds settings
- [ ] Global mute hotkey (Desktop)

**Exit:** interop matrix (Android, Windows, macOS) green for all features. Group call with 3 phones + 1 PC works.

## M4 — Hardening (v1.0 stable)

- [ ] Encrypted attachments at rest
- [ ] Bengali translation, RTL verified, strings complete
- [ ] Full accessibility pass: TalkBack, Narrator/NVDA, VoiceOver, 200 % font scale
- [ ] Performance budgets met (startup, CPU, battery, installer size)
- [ ] Security: fuzzing ≥ 24 h clean, MITM lab test, Noise interop test, external audit request
- [ ] Docs: user guide, IT admin guide, protocol spec 1.0 frozen
- [ ] Signing: Android release key, Windows Authenticode (SignPath), macOS notarization if Apple account available
- [ ] Distribution: GitHub Releases, F-Droid, Google Play, winget, Microsoft Store

**Exit:** security + accessibility + interop checklists pass. Protocol v1 frozen.

## M5 — Stand-out features (v1.x)

Ordered by value / effort. Each ships independently behind capabilities.

1. Contact groups + broadcast announcements
2. Private discovery ("Contacts only" with rotating tags)
3. On-device **live captions**
4. ML noise suppression + background blur
5. Linked devices (ring phone + PC together)
6. Desk intercom mode (desktop, opt-in)
7. Encrypted export/import, app lock, disappearing messages
8. Small group chats (≤ 32)
9. Screen sharing from Android
10. Call recording with consent, call transfer

## v2 — New platforms

- **Linux official:** packaging (DEB/RPM/Flatpak), PipeWire and Wayland screen share verification
- **iOS / iPadOS:** KMP core on iOS, UI decision, CallKit, WebRTC.xcframework, Apple entitlements (apply months ahead)

## Future (only if it stays simple)

- LAN host relay (SFU) for bigger meetings
- Wi-Fi Direct / Wi-Fi Aware calling with no router
- Wear OS quick answer + PTT
- On-device caption translation
- Emergency alert broadcast

---

## Definition of Done (every feature)

- [ ] Works on **Android and Desktop** (shared code), tested Android ↔ Android and Android ↔ Windows
- [ ] Works against the `tools/cli` peer in automated tests
- [ ] Capability-gated if it needs protocol support
- [ ] Phone, tablet and desktop layouts. Light/dark, large text, RTL screenshots updated
- [ ] Screen reader labels + announcements verified
- [ ] Strings externalized
- [ ] Edge cases from [07-edge-cases.md](07-edge-cases.md) covered by tests or documented
- [ ] Docs updated
- [ ] No new WAN traffic, no new permissions without plan update
