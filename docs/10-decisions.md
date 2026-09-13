# 10 — Architecture Decisions & Open Questions

Format: lightweight ADRs. New decisions after implementation starts go into `docs/adr/NNNN-title.md`.

---

## ADR-001 — Serverless peer-to-peer on the LAN
**Decision:** no required server. Devices discover each other via mDNS and connect directly.
**Why:** zero setup, works with no internet, no infra cost for a free project, and no central point of data collection.
**Rejected:** (a) a LAN server/PBX: setup burden and a single point of failure. (b) Internet signaling server: contradicts privacy and offline goals.
**Consequence:** background reachability depends on each OS. Larger meetings need an optional relay host later.

## ADR-002 — WebRTC (libwebrtc) for media
**Decision:** use Google's libwebrtc on all platforms, with host ICE candidates only. Android uses `io.github.webrtc-sdk:android`, Desktop uses `webrtc-java`.
**Why:** it's the best available AEC3/NS/AGC, jitter buffer, congestion control, HW codec integration and DTLS-SRTP. It's proven by billions of calls, and BSD-licensed. Both Java wrappers expose very similar APIs, so one `MediaEngine` interface covers both.
**Rejected:** (a) pure-Kotlin/JVM RTP stack: no production-grade echo cancellation. (b) GStreamer: great on Linux, weak on Android and Windows integration.
**Consequence:** a large native dependency on each platform. Versions are pinned and the Phase 0 spike proves Android ↔ Windows media.

## ADR-003 — Noise XX instead of TLS for the control channel
**Decision:** `Noise_XX_25519_ChaChaPoly_BLAKE2b`, with a Zoocall Noise state machine on libsodium primitives.
**Why:** identity is a raw public key (no X.509, no certificate authorities, no ASN.1 parsing). Small, formally analyzed and easy to audit. It also gives a handshake hash for safety codes. BLAKE2b (instead of BLAKE2s) because libsodium provides it on every target.
**Rejected:** TLS 1.3 with self-signed pinned certs (more complexity and attack surface, awkward raw-key mapping). An unmaintained JVM Noise library (noise-java).
**Consequence:** a small custom Noise implementation. It must pass official test vectors, interop with a reference implementation, be fuzzed, and be first in line for external audit (see [04-security-privacy.md](04-security-privacy.md#3-cryptography-summary)).

## ADR-004 (rev. 2) — Kotlin Multiplatform + Compose Multiplatform for Android and Desktop
**Status:** Accepted 2026-09-13. **Supersedes** the rev. 1 decision "shared Rust core with native UIs per platform (Kotlin/Compose, SwiftUI, WinUI 3, GTK4)".
**Decision:** all v1 code is Kotlin. `shared/core` (logic) and `shared/ui` (Compose screens) are used by both the Android app and one Desktop app for Windows + macOS (+ Linux).
**Why the change:**
- The owner wants **Android and PC built at the same time** and tests Android ↔ Android and Android ↔ PC. With KMP, every feature lands on both at once instead of being built twice (or four times).
- Development moved to a **Windows PC**. A native macOS SwiftUI app can't be built on Windows, but a Compose Desktop app can be developed on Windows and packaged for macOS in CI.
- **One language, one build system, one IDE.** No Rust ↔ Kotlin FFI layer, no native library cross-compilation per ABI and OS. This matches the "simple, not over-engineered" principle.
- iOS is still possible later, because KMP targets iOS for the core.
**Rejected:**
- **Rust core + native UIs (rev. 1):** best native feel and strongest memory-safety story, but four UI codebases and a cross-language toolchain were too heavy for Android + PC in parallel from a Windows machine.
- **Rust core + Kotlin UIs:** keeps one UI codebase but adds FFI and per-OS native builds for little v1 benefit.
- **Flutter:** one codebase too, but platform calling integration relies on plugins, the WebRTC plugin often lags libwebrtc, and it adds Dart as a second language next to the necessary Kotlin Android integration.
- **Electron/Tauri for desktop:** Electron is too heavy. Tauri needs a separate UI stack and WebKitGTK WebRTC is unreliable on Linux.
**Consequence:**
- Desktop uses the Zoocall Material-based design system rather than native Fluent/macOS controls. Platform conventions (theme, accent, shortcuts, tray/menu bar, window chrome) are still respected.
- Desktop installers include a Java runtime (~100 MB) and use more memory than native apps. Budgets are tracked.
- Desktop Compose accessibility needs extra manual testing.

## ADR-005 (rev. 2) — Platform media behind one `MediaEngine` interface
**Decision:** `shared/media` defines `MediaEngine`/`MediaSession`. `androidMain` implements them with the libwebrtc Android SDK, `desktopMain` with webrtc-java. The call state machine and signaling stay in `shared/core`.
**Why:** mobile camera/audio must integrate with Telecom and Android lifecycles, and desktop needs device selection and screen capture. The rest of the call logic is identical, and a fake `MediaEngine` makes the call logic testable without media.

## ADR-006 — Protocol Buffers for the wire format
**Decision:** proto3 in `protocol/`, Kotlin code generated by Square Wire (KMP-ready), `buf` for lint and breaking-change checks in CI.
**Why:** compact, strongly typed, great forward/backward compatibility (unknown fields ignored), and codegen for any language a future client uses.
**Rejected:** JSON (bigger, weaker schema evolution), CBOR (weaker schema/tooling), FlatBuffers (unnecessary here).

## ADR-007 — Mesh for group calls in v1
**Decision:** full mesh, audio ≤ 8, video ≤ 4.
**Why:** no server, LAN bandwidth is plentiful, and it's the simplest design that works for team huddles.
**Consequence:** CPU/battery limits cap group size. An optional LAN SFU host is planned later.

## ADR-008 — MIT license, free forever
**Decision:** keep the existing MIT license. No paid tiers, ads or tracking.
**Why:** maximum adoption, compatible with all app stores, easy for organizations to deploy.
**Trade-off:** others may create closed forks. Accepted in exchange for reach. Trademark/name usage guidance goes in `TRADEMARK.md`.

## ADR-009 (rev. 2) — Android + Desktop first, iOS later
**Decision:** Android, Windows and macOS are the v1 platforms, built together. Linux official support follows v1.0. iOS comes in v2.
**Why:** the owner's primary use cases are Android ↔ Android and Android ↔ PC. Android and desktop OSes allow background listening on the LAN without special entitlements. iOS needs restricted Apple entitlements for background calls, so it's planned later, with entitlement applications made months ahead.

## ADR-010 — No internet, verified by CI
**Decision:** the app must never make WAN connections, including avatars, link previews, update checks, fonts and crash reporting.
**Why:** a core promise of the product and a privacy guarantee users can trust.
**Consequence:** updates come only via stores/package managers. Everything is bundled, including fonts and sounds.

## ADR-011 — SQLDelight + SQLCipher for storage
**Decision:** SQLDelight with SQLCipher-capable drivers: `sqlcipher-android` on Android, `sqlite-jdbc-crypt` on desktop.
**Why:** type-safe SQL, KMP support, verified migrations, FTS5 search, and encryption at rest on both platforms.
**Rejected:** Room KMP (its bundled driver has no SQLCipher support on desktop), plain files (no search, no transactions).

## ADR-012 — Development on Windows, macOS builds in CI
**Decision:** day-to-day development happens on the owner's Windows PC. macOS packages and macOS tests run on GitHub Actions macOS runners.
**Why:** the Mac's disk is full. Everything except macOS packaging (and later iOS) works on Windows.
**Consequence:** macOS-specific bugs may be found later. Mitigated by CI smoke tests and manual testing once a Mac is available.

---

## Open questions (owner: project maintainer)

| # | Question | Recommendation | Needed by |
|---|---|---|---|
| Q1 | Final product name spelling & trademark check ("Zoocall" vs "ZooCall") | Use **Zoocall**, check for conflicts before store listing | M0 |
| Q2 | Application ID / package name (needs a domain or GitHub namespace) | `io.github.flytorakib.zoocall`, or register a domain (e.g. `zoocall.app`) for `app.zoocall` | M0 |
| Q3 | GitHub organization vs personal repo | Personal repo is fine for now. Move to an org when contributors join | Before v1.0 |
| Q4 | Default visibility: *Everyone* vs *Contacts only* | *Everyone* for the team use case, with a clear toggle in onboarding | M1 |
| Q5 | Default "Allow calls from": *Everyone* vs *Contacts* | *Everyone*, with strangers labeled "Not verified" | M1 |
| Q6 | Minimum Android version: API 28 vs API 29/31 | API 28 for wider reach on low-cost team devices. Re-evaluate after device testing | M1 |
| Q7 | Test devices: how many real Android phones are available? | At least 2 phones + the Windows PC. A 3rd phone for group call testing by M3 | M1 |
| Q8 | Apple Developer Program for signed macOS builds | Optional for v1 (unsigned DMG with instructions). Required for iOS later | M4 |
| Q9 | Design tool: Penpot (open) vs Figma | Penpot to keep everything open source | M0 |
| Q10 | First translation languages beyond English + Bengali | Based on community demand: Hindi, Arabic (RTL test), Spanish, Indonesian | M4 |
