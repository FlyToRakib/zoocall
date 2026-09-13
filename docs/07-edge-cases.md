# 07 — Edge Cases

Every case below needs a defined behaviour, a user-facing message and a test.

## 1. Network

| # | Situation | Behaviour | User sees |
|---|---|---|---|
| N1 | Not connected to Wi-Fi (mobile data only) | Stop advertising. Keep contacts list | Banner: "Connect to Wi-Fi to reach your team" |
| N2 | Phone is the **hotspot** host | Works normally. Bind to the hotspot interface | Nothing special |
| N3 | Guest Wi-Fi with **client isolation** | Discovery finds nobody, TCP to known peers times out | Network Doctor: explanation + ask IT / use another network |
| N4 | Multicast blocked but unicast OK | mDNS silent | Suggest QR/manual add. Remembered addresses still work |
| N5 | Devices on **different subnets/VLANs** (e.g. 2.4 GHz vs 5 GHz SSIDs bridged differently) | mDNS doesn't cross. Manual IP may work if routed | Network Doctor: "Others may be on a different network segment" |
| N6 | **VPN active** | Many VPNs capture LAN routes | Doctor detects VPN interface. "Allow local network access in your VPN" |
| N7 | IP changes (DHCP renew, roaming between APs) | Identity by key. Re-advertise, peers re-resolve. Active call: ICE restart | "Reconnecting…" (≤ 30 s) then back |
| N8 | Wi-Fi drops mid-call | ICE disconnected → 30 s grace → end with `failed_network` | "Reconnecting…", then "Call dropped, connection lost" + **Call again** |
| N9 | Wi-Fi switched to a *different* network mid-call | Peer unreachable → same as N8 | Same |
| N10 | High packet loss / weak signal | libwebrtc lowers bitrate → resolution → pauses video, audio kept (Opus FEC) | Quality bars drop. "Poor connection, video paused" |
| N11 | Router with AP roaming (mesh Wi-Fi) | Brief blip handled by ICE consent freshness | Maybe a short "Reconnecting…" |
| N12 | IPv6-only network | Link-local/ULA candidates and mDNS AAAA | Nothing special |
| N13 | Captive portal Wi-Fi (not yet logged in) | LAN may still work. We never try the portal | Nothing special |
| N14 | Firewall blocks inbound (Windows "Public" profile, ufw) | Outgoing calls work, incoming don't | Doctor: specific fix per OS |
| N15 | Large network (200+ Zoocall devices) | Nearby list virtualized, searchable. Connection cap 64. mDNS response jitter | Sorted: favorites → contacts → others alphabetical |
| N16 | Two apps/instances on the same PC (two users) | Different identities and ports. Port fallback if 47474 is taken | Both visible |
| N17 | Port 47474 already in use | Bind ephemeral port, advertise via SRV | Nothing |
| N18 | Device sleeps (desktop lid close) | Connections drop. Peers mark offline after 60 s | "Last seen" |
| N19 | PC on **Ethernet**, phone on Wi-Fi, same router | Works if both are on the same subnet (usual home/office case) | Nothing special |
| N20 | PC has multiple network adapters (Ethernet + Wi-Fi + VPN + Hyper-V/WSL/Docker virtual adapters) | Advertise and bind only on real LAN interfaces. Ignore virtual adapters (vEthernet, docker0, utun). ICE host candidates filtered the same way | Network Doctor lists the adapter in use and lets the user pick one |
| N21 | Windows network profile set to **Public** | Windows blocks discovery and inbound connections | Doctor: "Set this Wi-Fi to Private network" with steps |
| N22 | Android emulator used for testing | The emulator is behind its own NAT and can't be discovered on the LAN | Developer docs: test calls on real phones. The emulator is for UI only |

## 2. Calls

| # | Situation | Behaviour |
|---|---|---|
| C1 | **Both call each other at the same time** | Collision rule: smaller `call_id` wins, auto-connect (protocol §4.2) |
| C2 | Callee already in a call | Call waiting (P1): banner with *End & accept*, *Hold & accept* (P1), *Decline*. Pre-P1: auto `BUSY` |
| C3 | Callee in a regular **cellular call** | Telecom/CallKit reports busy → `BUSY` |
| C4 | Cellular call arrives during Zoocall call | OS handles it. Zoocall call goes on hold/muted via audio focus. Peer sees "On hold" |
| C5 | Callee has DND | Decline with `DND`. Favorites ring if "Allow favorites during DND" is set. Caller sees "Anika is in Do Not Disturb" + **Knock** / **Message** |
| C6 | Caller cancels while ringing | `CallCancel` → callee shows missed call |
| C7 | Nobody answers (45 s) | Missed call on callee, "No answer" on caller with **Message** option |
| C8 | Callee's app killed (Android, not reachable mode) | Invite fails to connect → "Anika isn't reachable right now" + queue message option |
| C9 | Callee is a PC with Zoocall closed (not in tray) or the PC is asleep | Same as C8: "Anika's computer isn't reachable right now" + queue message option |
| C9b | PC is locked (Windows lock screen / macOS screen saver) | Call rings through the tray app with sound. Accepting may require unlocking the PC first, depending on OS |
| C10 | Video call accepted as audio-only | Caller camera preview stops. The upgrade option remains |
| C11 | Mid-call audio→video upgrade | Request banner on peer: *Turn on video* / *Not now*. Never auto-enables the camera |
| C12 | Camera permission denied | Video call still possible as receive-only. Button explains how to enable |
| C13 | Mic permission denied | Cannot start or accept call. Explain + deep link to settings |
| C14 | Camera in use by another app | Fall back to audio. Toast "Camera is being used by another app" |
| C14b | PC has no camera | Video calls from a PC are receive-only. The camera button explains "No camera found" |
| C14c | PC mic/speaker unplugged or changed mid-call | Switch to the new default device and show which device is now in use |
| C15 | Bluetooth headset connects/disconnects mid-call | Auto-route to new headset. On disconnect return to previous route (earpiece for audio, speaker for video) |
| C16 | Wired headset plugged in | Route to wired |
| C17 | Proximity sensor during audio call | Screen off when near ear. Never during video/speaker |
| C18 | Device overheating | Reduce resolution/framerate progressively. Toast at severe level |
| C19 | Low battery (< 10%) | Offer "Switch to audio to save battery". Default to 480p |
| C20 | App backgrounded during video | Auto-PiP. If PiP disabled, camera pauses (OS rule) and the peer sees "Camera paused" |
| C21 | Screen locked during call | Audio continues. Video pauses outgoing camera on iOS (OS restriction) |
| C22 | Group call: host leaves | Call continues. Host role passes to earliest joiner |
| C23 | Group call: one member's network is poor | Only their legs degrade. Others unaffected |
| C24 | Group: 5th person joins a 4-person video call | Joins audio-only with a notice. Can see video when someone leaves |
| C25 | Key changed for verified contact calling in | Incoming screen shows ⚠️ "Security code changed". Accept requires an extra confirmation |
| C26 | Blocked person calls | Silently declined as `NOT_ALLOWED`. Logged in "Blocked calls" (optional) |
| C27 | Stranger calls with "Contacts only" setting | Declined `NOT_ALLOWED`. Optional "Call request" notification (rate limited) |
| C28 | Echo on desktop speakers | libwebrtc AEC3. If echo persists, suggest headphones via stats-based detection (P2) |
| C29 | Different app versions with different capabilities | Features negotiated via capabilities. Unsupported actions hidden per peer |
| C30 | Incompatible protocol major version | Handshake fails gracefully | "Anika needs to update Zoocall" |

## 3. Messaging & files

| # | Situation | Behaviour |
|---|---|---|
| M1 | Recipient offline | Queued in outbox ("Waiting…"), sent automatically when they reappear. No expiry by default, and the queue is visible |
| M2 | Sender goes offline before recipient returns | Stays queued on the sender. Delivered when both are online together. UI makes this clear ("Will send when you're both on the network") |
| M3 | Duplicate delivery after reconnect | Dedup by message ULID |
| M4 | Clock skew between devices | Display uses local receive time for incoming. Order is stable per conversation |
| M5 | File transfer interrupted | Resume from last offset when both online. Retry button |
| M6 | Not enough storage on receiver | Decline with reason before transfer starts ("Anika's device is full") |
| M7 | Very large file (e.g. 4 GB video) | Confirmation prompt above 100 MB (setting). Progress + pause |
| M8 | Dangerous file types (.apk, .exe, .sh) | Warning label and no auto-open |
| M9 | Malicious filename (`../../x`, `CON`, RTL override chars) | Sanitized. The original name is shown safely escaped |
| M10 | Messages from a stranger | Shown as request: *Accept*, *Block* |
| M11 | Group chat member offline | Per-member queue on the sender. Delivery ticks show "Delivered to 3 of 5" |

## 4. Identity, devices & data

| # | Situation | Behaviour |
|---|---|---|
| D1 | Two people with the same display name | Disambiguated by role / last 4 chars of safety number / verified badge |
| D2 | User reinstalls app | New key. Contacts see key-change warning. Encrypted export/import (P2) restores contacts & chats but **not** the old private key (by design) |
| D3 | User gets a new phone | Export/import (P2). Contacts re-verify |
| D4 | Same person on phone + laptop | P0/P1: two separate identities ("Rakib · Phone", "Rakib · Laptop"). P2: linked devices ring together |
| D5 | Device keystore unavailable/corrupted | Detect on start. Offer reset identity with clear warning. Never crash-loop |
| D6 | DB migration fails after update | Keep the backup copy made before migration, report, and offer export of the raw backup |
| D7 | Storage full | Messages still work (tiny). Media receive paused with notice |
| D8 | User changes name/avatar | Broadcast `Profile` update to connected peers. Others fetch on next connect |
| D9 | Hidden visibility mode | No mDNS. Contacts can still connect via last-known address and outgoing calls still work |
| D10 | Language/RTL change | Instant switch without data loss |

## 5. Human & safety

| # | Situation | Behaviour |
|---|---|---|
| H1 | Accidental call (pocket dial) | Outgoing calls require a deliberate tap (no swipe-to-call on list). Ringback gives audio cue |
| H2 | Accidental hang-up | End button placement away from mute. Recents "Call again" one tap |
| H3 | Someone tries to silently listen (desk intercom) | Intercom mode off by default, per-contact opt-in, always shows indicator + chime on both ends, audio-only |
| H4 | PTT stuck "talking" (button held by object) | 60 s max floor hold, then auto-release + haptic |
| H5 | Harassment on shared network | Block, contacts-only calls, stranger requests, rate limiting |
| H6 | Recording without consent | Not possible in-app: recording (P2) always announces to all participants |
| H7 | Kids / non-technical users | Onboarding with just name. Everything else has sensible defaults |
