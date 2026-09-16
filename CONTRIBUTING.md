# Contributing to Zoocall

Thanks for helping. Zoocall is free, MIT-licensed and built in the open.

## Setup

- JDK 21 and the Android SDK. Windows setup: [docs/11-dev-environment-windows.md](docs/11-dev-environment-windows.md).
- Open the repository root in Android Studio or IntelliJ IDEA.
- Create `local.properties` with your SDK path using forward slashes, for example `sdk.dir=C:/Users/you/AppData/Local/Android/Sdk`.

## Workflow

1. Create a short-lived branch from `main`.
2. Keep changes focused. Every feature must work on Android **and** desktop, because they share one codebase.
3. Run the tests before opening a pull request:

   ```bash
   ./gradlew desktopTest :android:assembleDebug
   ```

4. Use [Conventional Commits](https://www.conventionalcommits.org/): `feat(android): …`, `fix(core): …`, `docs: …`.
5. Open a pull request. Squash merge into `main`.

## Rules that keep Zoocall trustworthy

- **No internet.** No analytics, crash upload, remote fonts, update checks or link previews.
- **No new crypto.** Only compose audited libsodium primitives. Protocol changes update `protocol/` (schema, spec, vectors) in the same pull request.
- **Untrusted input.** Everything from the network is size-capped before allocation and parsed only by generated decoders.
- **Accessibility.** Every control has a label; state is never shown by color alone; strings live in resources.
- **Plain language.** Follow the UX writing rules in [docs/05-ux-ui-design.md](docs/05-ux-ui-design.md) §8.

Architectural decisions go in `docs/adr/`.
