# 08 — Engineering, Quality & Release

## 1. Monorepo structure

```
zoocall/
├── README.md                       # What Zoocall is, screenshots, download links, build quick start
├── LICENSE                         # MIT
├── CONTRIBUTING.md                 # Setup (Windows/macOS/Linux), workflow, commit style
├── CODE_OF_CONDUCT.md              # Contributor Covenant
├── SECURITY.md                     # Private vulnerability reporting
├── .gitattributes  .editorconfig  .gitignore
│
├── settings.gradle.kts             # includes :shared:*, :android, :desktop, :tools:cli
├── build.gradle.kts                # root plugins only
├── gradle/libs.versions.toml       # single version catalog for everything
├── gradlew  gradlew.bat
│
├── docs/                           # Plans, architecture, ADRs, user guide, IT admin guide
│
├── protocol/
│   ├── proto/zoocall/v1/*.proto    # Source of truth for messages
│   ├── spec/                       # Normative protocol spec (Markdown, RFC 2119 language)
│   ├── capabilities.md
│   └── test-vectors/               # JSON vectors: Noise, fingerprints, SAS, frames
│
├── shared/
│   ├── core/
│   │   ├── model/  protocol/  crypto/  discovery/  transport/
│   │   ├── presence/  call/  chat/  files/  store/  app/
│   ├── media/                      # MediaEngine: commonMain interface, androidMain, desktopMain
│   └── ui/                         # designsystem/, screens/, viewmodels/, navigation/, resources/
│
├── android/                        # Android application module (thin shell)
├── desktop/                        # Desktop application module: Windows, macOS, Linux (thin shell + packaging)
├── apple/                          # iOS / iPadOS (later)
│
├── design/
│   ├── tokens/                     # W3C design tokens JSON (source of truth)
│   ├── icons/  brand/  sounds/  mockups/
│
├── tools/
│   ├── cli/                        # Headless JVM test peer (Gradle module :tools:cli)
│   ├── tokens/                     # Token → Kotlin generator
│   └── netlab/                     # Docker/netns scenarios: loss, jitter, isolation, no-WAN
│
└── .github/
    ├── workflows/                  # ci-shared.yml, android.yml, desktop.yml, protocol.yml,
    │                               # nightly.yml (fuzz + netlab), release.yml
    ├── ISSUE_TEMPLATE/  PULL_REQUEST_TEMPLATE.md
    ├── CODEOWNERS
    └── renovate.json
```

### Why a monorepo with one Gradle build
- Protocol, core, UI, Android and Desktop change together. One PR updates a `.proto`, the core and both apps at once.
- One version catalog, so Android and Desktop never drift on library versions.
- Open the repo root in Android Studio or IntelliJ IDEA, and every module is available with run configurations for Android and Desktop.

### Dependency direction (enforced)
```
protocol ← shared/core ← shared/media ← shared/ui ← {android, desktop}
design/tokens → shared/ui
tools/cli → shared/core + shared/media
```
`android` and `desktop` never depend on each other.

## 2. Tooling & conventions

| Area | Choice |
|---|---|
| Build | Gradle (Kotlin DSL) with the wrapper, version catalog, configuration cache, build cache |
| JDK | **Java 21** (Temurin, or the JetBrains Runtime bundled with Android Studio) |
| Kotlin | Kotlin 2.x (K2), Compose Multiplatform, Compose compiler plugin |
| Formatting & lint | ktlint (via Spotless), Detekt, Android Lint |
| Proto | Wire Gradle plugin generates Kotlin. `buf lint` + `buf breaking` run in CI |
| Commits | Conventional Commits (`feat(android): ...`, `fix(core): ...`) |
| Branching | Trunk-based. Short-lived feature branches → PR → squash merge to `main`. Release branches `release/x.y` |
| Versioning | **One app version** for Android and Desktop (SemVer). **Protocol version** separate (`zoocall/1`) |
| Changelog | Generated from Conventional Commits (git-cliff) |
| Line endings | `.gitattributes` normalizes to LF in the repo (important when working on Windows). `gradlew.bat` and `*.ps1` stay CRLF |
| Code review | CODEOWNERS per folder. 2 reviewers for `shared/core/crypto`, `transport`, `protocol`, and the `protocol/` folder |
| Docs | Every user-visible feature updates `docs/`. New architectural decisions get an ADR |

## 3. CI/CD (GitHub Actions, free for public repos)

| Workflow | Trigger (paths) | Runner | Jobs |
|---|---|---|---|
| `protocol.yml` | `protocol/**` | ubuntu | buf lint, buf breaking, vector schema check |
| `ci-shared.yml` | `shared/**`, `protocol/**`, `gradle/**` | ubuntu, **windows**, **macos** | Detekt, unit tests (`jvmTest` + Android host tests), Noise vectors, SQLDelight migration verify, Kover coverage |
| `android.yml` | `android/**`, `shared/**`, `design/**` | ubuntu | Lint, unit tests, Roborazzi screenshot tests, instrumented UI tests on Gradle Managed Device, assemble debug + release APK/AAB |
| `desktop.yml` | `desktop/**`, `shared/**`, `design/**` | **windows**, **macos**, ubuntu | Build, Compose desktop UI tests, launch smoke test, package MSI (Windows), DMG (macOS), DEB (Linux) as artifacts |
| `nightly.yml` | schedule | ubuntu | Jazzer fuzzing (1 h per target), netlab interop & impairment scenarios with multiple CLI peers, Noise interop vs reference implementation |
| `release.yml` | tag `v*` | all | Build, sign, checksums, SBOM, GitHub Release draft, store uploads (manual approval gate) |

**The macOS DMG is always built in CI**, so the Windows development PC never needs a Mac for day-to-day work.

## 4. Testing strategy

| Level | What | Tools |
|---|---|---|
| Unit | Core logic: state machines, codecs, crypto wrappers, outbox, migrations | `kotlin.test`, JUnit 5, kotest property testing, Turbine for flows, `kotlinx-coroutines-test` virtual time |
| Protocol conformance | Noise official vectors + Zoocall vectors + message round-trips | Shared JSON vectors |
| Integration (core) | N in-process peers on loopback: discover (fake discovery), handshake, call signaling with a fake `MediaEngine`, chat, files, collisions, reconnect | JVM tests |
| Media integration | Two `webrtc-java` sessions in one JVM with fake audio/video sources: connects, frames flow, DTLS binding enforced | JVM tests on Windows/macOS/Linux runners |
| Fuzz | Parsers & handshake | Jazzer |
| Network impairment | 1–10 % loss, 20–200 ms jitter, bandwidth caps, interface flaps, multicast blocked, no-WAN assertion | `tools/netlab` (Docker + `tc netem`) with CLI peers |
| UI | View models, Compose UI tests (shared), screenshot tests (phone/tablet/desktop × light/dark × large font × RTL) | Compose UI test, Roborazzi |
| E2E | Android app ↔ CLI peer and Desktop app ↔ CLI peer: call connects, tone detected, chat delivered | Compose UI test / UI Automator + CLI peer |
| Accessibility | Automated checks + manual screen-reader pass per release (TalkBack, Narrator/NVDA, VoiceOver) | Android Accessibility Test Framework, Accessibility Scanner, Accessibility Insights for Windows |
| Performance | Startup, call setup time, CPU/battery during 30-min call, memory | Macrobenchmark (Android), Baseline Profiles, JFR (desktop) |
| Manual | Interop matrix on real devices, OEM battery behaviour, Bluetooth routing, Windows firewall | Release checklist in `docs/release-checklist.md` |

**Coverage targets:** `shared/core` ≥ 80 % lines (crypto/transport/call ≥ 90 %). UI is covered by behaviour and screenshot tests, not a percentage.

## 5. Performance & quality budgets

| Metric | Budget |
|---|---|
| Android cold start to interactive (mid-range phone) | < 1.0 s |
| Desktop start to interactive | < 2.5 s |
| People list populated | < 2 s after launch |
| Tap call → callee ringing | < 300 ms |
| Accept → media flowing | < 700 ms |
| Mouth-to-ear latency (LAN) | < 150 ms |
| Audio quality | Opus 32–64 kbps, wideband/fullband |
| Video | 720p30 default, 1080p30 on strong 5 GHz, adaptive down to 180p |
| CPU during 1:1 720p video (mid-range phone) | < 35 % of one big core average with HW codecs |
| Battery: 30-min video call (phone) | < 12 % |
| Battery: reachable mode idle (phone) | < 1 % per hour |
| Android APK (per ABI) | < 30 MB |
| Desktop installer | < 120 MB (includes Java runtime + libwebrtc) |
| Desktop memory idle / in 1:1 video call | < 300 MB / < 500 MB |
| Crash-free sessions | ≥ 99.5 % (measured by beta testers + manual reports) |

## 6. Logging & diagnostics (no telemetry)

- Structured logging through a small `Logger` interface in `shared/core` (Logcat on Android, rolling file on desktop). Release builds log `info`+ with redaction.
- **"Export diagnostics"** creates a zip: redacted logs, app/OS version, network interfaces summary, Network Doctor results, last call stats. The user shares it manually (e.g. attaches to a GitHub issue).
- Debug builds only: developer overlay with live call stats.

## 7. Release & distribution

| Channel | Platform | Notes |
|---|---|---|
| GitHub Releases | Android, Windows, macOS | Signed binaries, SHA-256 checksums, cosign signatures, SBOM |
| F-Droid | Android | Reproducible build. No proprietary deps |
| Google Play | Android | Free, no ads/IAP. Internal → closed → open testing → production |
| Obtainium | Android | Direct from GitHub releases |
| winget | Windows | Manifest PR per release |
| Microsoft Store | Windows | Free listing for individuals |
| Homebrew cask | macOS | After signing/notarization is set up |
| Flathub, DEB/RPM | Linux | After v1.0 |
| App Store | iOS | Later |

**Code signing costs:** Android is free. Windows Authenticode is free for OSS via SignPath Foundation. Apple Developer Program (≈ $99/yr) is only needed for signed/notarized macOS builds and, later, iOS.

**Release cadence:** a minor release roughly every 6–8 weeks after 1.0, with patch releases as needed. Every release follows `docs/release-checklist.md`: interop matrix, security checklist, accessibility pass and store metadata.

## 8. Open-source project health

- **License:** MIT (already in repo). Third-party license notices generated with AboutLibraries and shown in Settings → About.
- **Governance:** maintainer-led (repo owner). `GOVERNANCE.md` when contributors grow.
- **Contribution:** good-first-issue labels, DCO sign-off, templates for bugs (with diagnostics export) and features (must reference a principle in the plan).
- **Community:** GitHub Discussions, translations through Weblate, docs site from `docs/` (MkDocs Material on GitHub Pages).
- **Funding (optional, never required):** GitHub Sponsors / Open Collective for the Apple developer fee and test devices, and grants (NLnet, Sovereign Tech Fund). The app stays free with no paid tier.
- **IT admin guide:** `docs/admin-guide.md` covering ports, mDNS, VLANs, client isolation, Windows firewall rules and managed deployment (Android Enterprise managed config, MSI silent install parameters).
