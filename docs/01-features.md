# 01 — Features

Priority legend:

| Priority | Meaning | Ships in |
|---|---|---|
| **P0** | Must have. The app is useless without it | MVP (0.1) |
| **P1** | Core experience of a polished product | v1.0 |
| **P2** | Stand-out and power features | v1.x |
| **P3** | Future / experimental, only if it stays simple | Later |

Rule: **a feature is added only if it fits the design without adding a new top-level screen or setup step.**

**Platforms:** priorities apply to **Android and Desktop (Windows + macOS) together**, because they share one codebase. Features marked *(desktop)* or *(mobile)* are form-factor specific. iOS gets the same feature set later.

---

## 1. Onboarding & identity

| Feature | P | Notes |
|---|---|---|
| No account, no phone number, no email | P0 | Identity = a device keypair generated on first launch |
| Set display name + avatar (initials, emoji or photo) | P0 | One screen. Avatar photo is compressed to 256 px and shared on request |
| Just-in-time permissions | P0 | Mic is requested on the first call, camera on the first video call. Each prompt has a one-line explanation |
| Personal QR code ("My code") | P0 | Contains public key + current address, used for verified adding |
| Optional role/department label | P1 | e.g. "Front desk", "Nurse station 3". Helps teams |
| Multiple devices per person (link phone + laptop, ring both) | P2 | Linked devices share a "person" identity through signed device lists |

## 2. Discovery & contacts

| Feature | P | Notes |
|---|---|---|
| **Nearby**: live list of Zoocall users on the network | P0 | mDNS/DNS-SD, appears in under 2 s |
| Search / filter nearby people | P0 | Instant local filter |
| Add to contacts with **safety code** confirmation | P0 | 6-digit code shown on both screens, derived from the handshake |
| Add by **scanning QR** | P0 | Automatically verified |
| Add by **manual IP:port** | P0 | For networks that block multicast |
| Verified badge + **key-change warning** | P0 | Like Signal safety numbers |
| Favorites (pinned at top) | P1 | |
| Block / unblock | P1 | Blocked peers can't call, message or see your presence |
| Contact groups ("Kitchen", "Security team") | P2 | Local groups, used for group calls and broadcast |
| Invite deep link `zoocall://add?...` | P2 | Can be shared through any channel (for example a printed QR on a wall) |
| Private discovery ("Contacts only") | P2 | Strangers can't see you. Contacts recognize you through rotating HMAC tags |

## 3. Calling

| Feature | P | Notes |
|---|---|---|
| 1:1 audio call | P0 | Opus, echo cancellation, noise suppression, AGC |
| 1:1 video call | P0 | Up to 1080p30 on good LAN, adaptive |
| Full-screen incoming call (locked screen too), ringtone, vibration | P0 | Android Core-Telecom + full-screen notification. Desktop: tray app + always-on-top incoming call window |
| Accept as audio only (for video calls) | P0 | |
| Decline, decline with quick message ("Call you back in 5") | P0 / P1 | |
| Mute, camera on/off, flip camera | P0 | |
| Audio route: earpiece / speaker / wired / Bluetooth | P0 | |
| Proximity sensor screen-off during audio calls | P0 | Mobile |
| Missed-call notification + Recents list | P0 | |
| Call timer + connection quality indicator (bars) | P0 / P1 | |
| Busy signal, call collision handling | P0 | See [07-edge-cases.md](07-edge-cases.md) |
| Auto-reconnect after Wi-Fi blips ("Reconnecting…") | P0 | ICE restart, 30 s grace |
| Picture-in-Picture video while using other apps | P1 | |
| Hold / call waiting (second incoming call) | P1 | |
| Upgrade audio → video mid-call (with consent) | P1 | |
| **Group calls**: audio ≤ 8, video ≤ 4 (mesh) | P1 | Add participants mid-call |
| Screen sharing (desktop) | P1 | Windows / macOS, viewable on Android and Desktop |
| Screen sharing (mobile) | P2 | Android MediaProjection (iOS later) |
| Mic / speaker / camera device selection | P0 | Desktop, with hot-plug handling |
| Call statistics panel (codec, bitrate, RTT, loss) | P1 | Hidden under "Call info". Useful for IT and debugging |
| Enhanced noise suppression (ML) | P2 | RNNoise-class model, on-device |
| Background blur | P2 | On-device segmentation (ML Kit / Vision / ONNX) |
| **Live captions** (on-device speech-to-text) | P2 | Also an accessibility feature. No audio leaves the device |
| Call transfer (attended) | P2 | Front-desk use case |
| Call recording with **mandatory consent** | P2 | Everyone sees a persistent "Recording" banner. Stored locally, encrypted |
| Larger groups via **LAN host relay** (a desktop acts as SFU) | P3 | Opt-in, still no internet |
| Live translation of captions | P3 | On-device models only |
| Low-light video enhancement | P3 | |

## 4. Team features (what makes Zoocall stand out)

| Feature | P | Notes |
|---|---|---|
| **Push-to-talk (walkie-talkie)** to a person or group | P1 | Hold a button to talk, no ringing. Opt-in per contact/group. Great for warehouses, events and security |
| **Knock / Ping** | P1 | A gentle "Are you free?" nudge with one-tap replies: *Call me*, *2 min*, *Busy* |
| **Voice notes** | P1 | Record and send when a live call isn't needed |
| Presence: Available / Busy / DND / Away + custom status text | P0 / P1 | DND silences calls except from favorites (configurable) |
| Auto-busy while in a call | P0 | |
| **Broadcast announcement** to a group ("Meeting in 5 min") | P2 | Audio or text, rings everyone once |
| **Desk intercom mode** (desktop) | P2 | Trusted contacts can auto-connect *audio-only*, with a clear on-screen indicator and a sound chime. Off by default |
| Emergency alert to group (loud, overrides DND) | P3 | Allowed only for contacts you marked as trusted |
| Shared whiteboard / annotate screen share | P3 | |

## 5. Messaging

| Feature | P | Notes |
|---|---|---|
| 1:1 text messages | P0 | Delivered instantly if peer online, otherwise queued and sent automatically when the peer reappears |
| Delivery states: sending / sent / delivered / read | P1 | Read receipts can be turned off |
| Typing indicator | P1 | |
| Photos & files (any type), with progress, pause/resume | P1 | Chunked, hash-verified, no size limit by default (setting) |
| Replies, reactions, copy, delete-for-me | P1 | |
| Edit / delete-for-everyone | P2 | Best effort, clearly labeled |
| Small group chats (≤ 32) | P2 | Mesh fan-out from sender, queued per member |
| Link previews | — | **Never.** They would require internet and leak data |
| Disappearing messages | P2 | |
| Message search | P1 | Local full-text search (SQLite FTS5) |

## 6. Network & reliability

| Feature | P | Notes |
|---|---|---|
| **Network Doctor** | P1 | One tap diagnoses: not on Wi-Fi, VPN blocking LAN, multicast blocked, client isolation (guest Wi-Fi), different subnet, firewall. Gives plain-language fixes |
| Works on phone **hotspot** networks | P0 | No infra needed in the field |
| IPv4 + IPv6 (link-local) | P0 | |
| Manual peer entry & "remember this address" | P0 | |
| Adaptive quality (bitrate/resolution/framerate) | P0 | libwebrtc congestion control + thermal/battery signals |
| Data/quality preference: Auto / Save battery / Best quality | P1 | |
| Wi-Fi Direct / Wi-Fi Aware (no router at all) | P3 | Android-first experiment |

## 7. Personalization & settings

| Feature | P | Notes |
|---|---|---|
| Theme: System / Light / Dark | P0 | |
| Dynamic color (Material You) on Android 12+ | P0 | Accent color picker elsewhere |
| Pure black (AMOLED) dark variant | P2 | |
| Ringtones & notification sounds | P1 | Built-in open-licensed set + system picker |
| Language selection (in-app) | P1 | English + Bengali first, more via community translation |
| Visibility: Everyone on network / Contacts only / Hidden | P0 (Everyone/Hidden) · P2 (Contacts only) | |
| Allow calls from: Everyone / Contacts / Favorites | P0 | |
| App lock (biometric / device PIN) | P2 | |
| Encrypted export / import of contacts & chats | P2 | Passphrase-protected file. Useful when changing phones |
| Start at login / run in tray (desktop) | P1 | |
| Keyboard shortcuts (desktop) | P1 | Global mute hotkey, answer/hang up |

## 8. Accessibility (all P0 unless noted)

- Full screen reader support (TalkBack on Android, Narrator/NVDA on Windows, VoiceOver on macOS), with meaningful labels and call-state announcements
- Dynamic type / font scaling up to 200% without broken layouts
- Minimum touch targets 48 dp / 44 pt, full keyboard navigation and visible focus on desktop
- Color contrast ≥ 4.5:1 (text) and ≥ 3:1 (UI). State is never shown by color alone
- Reduce motion respected. Haptic feedback on key actions (can be turned off)
- Live captions (P2), and flash-screen-on-incoming-call for deaf/hard-of-hearing users (P1)
- RTL layout support

## 9. Explicitly rejected features (to stay simple)

| Rejected | Why |
|---|---|
| Internet calling / relays | Changes the product into another messenger, needs servers |
| Accounts / login | Friction, privacy cost, server cost |
| Stories, feeds, stickers store, bots | Not a calling tool |
| Cloud backup | Needs cloud. Encrypted local export covers the need |
| In-app analytics / crash upload | Privacy. Users can export a diagnostics file manually instead |
| AI features that need the cloud | Only on-device models are allowed |
