# ADR 0001 — Implementation choices made while building M1

Status: Accepted · 2026-09-13

These refine the approved plan (docs/00–10) where the plan left details open or reality forced a choice.

| # | Decision | Why |
|---|---|---|
| 1 | **Toolchain:** Kotlin 2.4.20, AGP 9.4, Gradle 9.7.1, Compose Multiplatform 1.12, JDK 21. KMP libraries use the AGP `com.android.kotlin.multiplatform.library` plugin; conventions live in `build-logic/`. | Latest stable versions at start of development. AGP 9 no longer supports `com.android.library` + KMP. |
| 2 | **`compileSdk` 37, `targetSdk` 36.** | Compose 1.12 artifacts require compiling against API 37. Targeting 37 would enable Android 17 local-network protection, which needs its own permission flow; that's planned with Network Doctor (M3). |
| 3 | **Application ID `io.github.flytorakib.zoocall`** (open question Q2). Kotlin packages use `app.zoocall.*`. | No owned domain yet. Packages can stay stable if the ID changes before store release. |
| 4 | **Safety code derivation** = `BE32(BLAKE2b-32(h ‖ "zoocall-sas")[0..4]) mod 10⁶`. | "20 bits → 000000–999999" in docs/03 doesn't map uniformly onto 6 digits; mod 10⁶ of 32 bits has negligible bias. Normative text in `protocol/spec/protocol-v1.md` §3.2. |
| 5 | **Rekey by bytes only** (2³⁰ per direction), not by time. | Both peers must rekey at the same frame. Byte counts are identical on both ends; wall-clock intervals are not. |
| 6 | **Modules:** `presence` and `files` are not separate modules yet. Presence travels in `Hello`; files arrive in M2. | Avoids empty modules (principle 7, no over-engineering). |
| 7 | **`MediaEngine` port lives in `:shared:media` commonMain**, and `:shared:core:call` depends on it. | Keeps the call module free of platform code while avoiding a cycle between core and media. |
| 8 | **Android "stay reachable" foreground service ships in M1**, not M2. | Android ↔ Android incoming calls are unreliable without it once the screen turns off. It uses `specialUse` and starts only while the app is visible. |
| 9 | **QR scanning uses CameraX + ZXing core**, not zxing-cpp or ML Kit. | Pure Java, no Google Play services (F-Droid compatible), stable API. |
| 10 | **CLI peer uses plain argument parsing** (no Clikt) and has no media engine. | Small tool; declines calls. Media for automated E2E tests comes with the desktop media tests. |
| 11 | **Desktop call UI** is a separate always-on-top incoming window and a call window; the main window stays in the tray on close. | Matches docs/06 §2 and keeps calls reachable while the main window is hidden. |
| 12 | **macOS key storage** temporarily uses an owner-only (0600) file until the Keychain adapter lands. Windows uses DPAPI. | The dev machine is Windows; Keychain needs testing on a Mac. Tracked for M1 exit. |
