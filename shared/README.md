# shared/

Kotlin Multiplatform modules used by **both** the Android and Desktop apps:

- `core/`: model, protocol, crypto (Noise), discovery, transport, presence, call, chat, files, store, app facade
- `media/`: `MediaEngine` interface + Android (libwebrtc) and Desktop (webrtc-java) implementations
- `ui/`: Compose design system, screens, view models, navigation

Design: [docs/02-architecture.md](../docs/02-architecture.md). Not implemented yet.
