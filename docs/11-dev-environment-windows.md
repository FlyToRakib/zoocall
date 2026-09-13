# 11 — Development Environment (Windows PC)

Development happens on a **Windows 10/11 PC**. This guide covers moving the repo from the Mac, installing tools, and testing Android ↔ Android and Android ↔ PC calls.

## 1. Move the repository from the Mac

The plan files were created on the Mac and must reach the Windows PC. The safest path is through GitHub (`origin` = `https://github.com/FlyToRakib/zoocall.git`).

On the Mac:
```bash
git add -A
git commit -m "docs: add Zoocall plan"
git push origin main
```

On the Windows PC (PowerShell or Git Bash), in a **short path** without spaces:
```bash
git config --global core.autocrlf false
git config --global core.longpaths true
git clone https://github.com/FlyToRakib/zoocall.git C:\dev\zoocall
```

- `core.autocrlf false` + the repo's `.gitattributes` keep line endings consistent between Windows and CI.
- A short path like `C:\dev\zoocall` avoids Windows path-length problems in Gradle builds.

## 2. Tools to install

| Tool | Why | Notes |
|---|---|---|
| **Git for Windows** | Version control | Includes Git Bash |
| **Android Studio** (latest stable) | Android SDK, emulator, Gradle, Kotlin, and it runs the Desktop app too | Install the **Kotlin Multiplatform** plugin. Android Studio bundles a JDK (JetBrains Runtime 21) |
| **JDK 21** (Eclipse Temurin) | Gradle on the command line and Desktop packaging (`jpackage`) | Set `JAVA_HOME` to it. Android Studio's bundled JDK also works inside the IDE |
| Android SDK Platform (latest) + Build-Tools + Platform-Tools | Build and install on phones | Through Android Studio → SDK Manager |
| **WiX Toolset v3** | Only needed to build the Windows MSI locally | CI builds the MSI anyway, so this is optional |
| `buf` CLI | Protobuf lint locally | Optional, CI runs it |
| Docker Desktop (WSL 2) | `tools/netlab` network impairment tests | Optional, CI runs it |

**Disk space:** reserve about **25 GB** (Android SDK + emulator image ≈ 10 GB, Gradle caches ≈ 5–10 GB, builds ≈ 3 GB).

## 3. Opening and running the project

Once the Gradle skeleton exists (milestone M0):

- Open `C:\dev\zoocall` in Android Studio.
- **Android app:** choose the `android` run configuration and select a connected phone.
- **Desktop app:** run `desktop` configuration, or from a terminal:

```bash
gradlew.bat :desktop:run
```

- **Build Windows installer** locally (optional):

```bash
gradlew.bat :desktop:packageMsi
```

- **Run all shared tests:**

```bash
gradlew.bat :shared:core:allTests
```

- **CLI test peer:**

```bash
gradlew.bat :tools:cli:run --args="listen --name TestPeer"
```

## 4. Testing with real devices

### Android ↔ Android
- Two Android phones on the **same Wi-Fi**.
- Enable **Developer options → USB debugging** (or Wireless debugging) on both.
- Install the debug APK on both from Android Studio (select each device in turn) or with `adb install`.
- The Android emulator **cannot** be used for real calls: it sits behind its own NAT and isn't visible on the LAN. Use it only for UI work.

### Android ↔ Windows PC
- The PC can be on Wi-Fi or **Ethernet**, as long as it is on the same router/subnet as the phone.
- Set the network to **Private** in Windows: *Settings → Network & internet → Wi-Fi (or Ethernet) → [network] → Network profile type → Private*.
- On first launch, Windows Defender Firewall asks to allow Zoocall (or `java.exe` when running from the IDE). Allow it on **Private networks**.
- If the PC has Hyper-V, WSL, Docker or VPN adapters, check that Zoocall uses the real LAN adapter (Network Doctor will show this from M3; until then, check the address shown in the app's "My code" screen).
- Quick check that the phone can reach the PC: find the PC's IP with `ipconfig`, then on the phone use **Add by address**.

### Android ↔ Mac
- CI produces an unsigned DMG on every build. When a Mac with free storage is available, install it, allow "Open anyway" in *System Settings → Privacy & Security*, and grant **Local Network**, **Microphone** and **Camera** permissions.

## 5. Common problems on Windows

| Problem | Fix |
|---|---|
| Gradle "filename too long" | Clone to a short path (`C:\dev\zoocall`), `git config --global core.longpaths true` |
| Phone doesn't see the PC | Network profile is Public, firewall blocked, PC on a guest network, or VPN active |
| PC doesn't see the phone | Same checks. Also make sure the phone's Wi-Fi isn't a separate guest/IoT network |
| `adb` doesn't list the phone | Install the phone maker's USB driver, change USB mode to "File transfer", accept the debugging prompt |
| Antivirus slows builds | Exclude `C:\dev\zoocall` and `%USERPROFILE%\.gradle` from real-time scanning |
| Line-ending diffs everywhere | `core.autocrlf false`, then `git add --renormalize .` |
