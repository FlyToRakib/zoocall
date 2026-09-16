# Performance baseline

Measured against the budgets in [08-engineering-quality.md §5](08-engineering-quality.md). Update when a budget-relevant change lands.

## 2026-09-14 (M4)

| Budget | Target | Measured | Notes |
|---|---|---|---|
| Android APK per ABI (release, R8) | < 30 MB | arm64-v8a 19.1 MB · armeabi-v7a 12.8 MB · x86_64 23.0 MB | libwebrtc is most of it |
| Desktop start to interactive | < 2.5 s | 2.2 s (Windows 11, JDK 21) | `Startup` log line: JVM start → main window shown |
| Desktop memory idle | < 300 MB | 313 MB working set (327 MB before G1 tuning) | `-XX:+UseG1GC` with periodic uncommit and tighter heap-free ratios (desktop/build.gradle.kts) |
| Desktop installer | < 120 MB | not measured (app image 198 MB uncompressed) | MSI is compressed; measure on the release CI build |
| Android cold start, CPU, battery, call setup | see §5 | not measured | needs Macrobenchmark on a physical mid-range phone |

Idle desktop memory is slightly over budget; most of the remainder is native (Skia, libwebrtc) rather than Java heap.
