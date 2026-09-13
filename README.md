# Zoocall

**Free, open-source, end-to-end encrypted calling and messaging over your local Wi-Fi. No internet, no accounts, no servers.**

Audio calls, video calls, push-to-talk, chat and file sharing between **Android phones and Windows / macOS computers** on the same network. iOS and Linux come later.

> **Status:** planning complete, implementation not started. See the [plan](docs/00-zoocall-final-plan.md).

## Repository layout

| Folder | Contents |
|---|---|
| [`docs/`](docs/) | Plan, architecture, protocol, security, UX, roadmap, dev setup |
| [`protocol/`](protocol/) | Protocol schemas, spec and test vectors |
| [`shared/`](shared/) | Kotlin Multiplatform code shared by Android and Desktop: core logic, media, UI |
| [`android/`](android/) | Android app (Kotlin, Jetpack Compose) |
| [`desktop/`](desktop/) | Desktop app for Windows and macOS (Kotlin, Compose Multiplatform) |
| [`apple/`](apple/) | iOS / iPadOS app (later) |
| [`design/`](design/) | Design tokens, icons, brand, sounds, mockups |
| [`tools/`](tools/) | CLI test peer, network test lab, scripts |

## Development

Development happens on Windows. See [docs/11-dev-environment-windows.md](docs/11-dev-environment-windows.md).

## License

[MIT](LICENSE). Free forever.
