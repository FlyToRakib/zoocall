# 03 — Zoocall Protocol v1

The normative spec will live in `protocol/spec/`, with schemas in `protocol/proto/zoocall/v1/`. This document is the design.

The protocol is deliberately independent of Kotlin. The Android and Desktop apps implement it in `shared/core`, and a future iOS or third-party client must pass the same test vectors.

## 1. Layers

| Layer | Technology | Purpose |
|---|---|---|
| L1 Discovery | mDNS / DNS-SD, QR, manual | Find `(fingerprint → ip:port)` |
| L2 Secure channel | TCP + Noise XX + length-prefixed frames | Mutual authentication, confidentiality, integrity |
| L3 Control messages | Protobuf `Envelope` | Hello, presence, calls, signaling, chat, files |
| L4 Media | WebRTC (ICE host candidates, DTLS-SRTP, RTP/RTCP) | Audio, video, screen share, data channels (PTT floor control) |
| L4b Bulk data | Secondary Noise-over-TCP stream | File transfer chunks (keeps the control channel responsive) |

## 2. Discovery (mDNS / DNS-SD)

- **Service type:** `_zoocall._tcp.local.`
- **Instance name:** random 12-char base32 string, regenerated at each app install. It is *not* the person's name, so names don't appear in router logs and mDNS browsers.
- **Port:** TCP control listener. Preferred `47474`, falls back to an OS-assigned port. The real port is always taken from the SRV record.
- **TXT record** (kept under 200 bytes):

| Key | Example | Meaning |
|---|---|---|
| `v` | `1` | Protocol major versions supported (comma list) |
| `fp` | `k3m9q2x7...` (first 20 base32 chars of fingerprint) | Lets contacts recognize the device before connecting |
| `n` | `Rakib` | Display name (omitted when visibility ≠ Everyone) |
| `r` | `Front desk` | Optional role |
| `d` | `phone` / `tablet` / `desktop` | Device class for icon |

- **Browse** continuously while the app is foregrounded. In the background, follow platform limits (see [06-platforms.md](06-platforms.md)).
- **Private discovery (P2):** in "Contacts only" mode, `fp` and `n` are omitted and `t=<tags>` carries `HMAC(pair_secret, 10-min epoch)[0..4]` per contact (max 16). Only contacts can match it.
- **Fallbacks:**
  - **QR code / deep link:** `zoocall://add?v=1&k=<base64url pubkey>&a=<ip:port>[,<ip:port>]&n=<name>`
  - **Manual:** user enters `ip[:port]`. The fingerprint is learned in the handshake and must be verified with the safety code.
  - **Subnet sweep (P2, opt-in):** TCP connect to `:47474` across the /24. Used only when Network Doctor detects multicast is blocked.

## 3. Secure channel

### 3.1 Identity
- Each device generates a **Curve25519 static keypair** on first launch. This key *is* the device identity.
- **Fingerprint** = `BLAKE2b-256("zoocall-fp-v1" ‖ public_key)`.
- Display forms:
  - **Safety code (SAS):** 6 digits, derived per session: `BLAKE2b(handshake_hash ‖ "zoocall-sas")` → 20 bits → `000000–999999` (P0). Use for in-person verification of a new contact.
  - **Safety number:** 60 digits from both fingerprints, sorted (like Signal), shown in contact details for later verification.
  - **QR:** full public key.

### 3.2 Handshake
- **Pattern:** `Noise_XX_25519_ChaChaPoly_BLAKE2b`. All three primitives come from libsodium, which is available on Android, the desktop JVM and (later) iOS. The Noise state machine is validated against the official Noise test vectors for this suite.
- **Prologue:** `"zoocall/1"`, which binds the protocol version and prevents cross-protocol replays.
- Handshake payloads (encrypted in messages 2 and 3) carry a `HandshakePayload { protocol_versions[], app_version, device_class }`.
- After the handshake: both sides check the remote static key against the contact list:
  - **Known contact, same key:** trusted.
  - **Known name/fp prefix but different key:** **key-change warning**, and the session is limited to "untrusted" until the user confirms.
  - **Unknown:** treated as a stranger. Allowed actions depend on the "Allow calls from" setting.
- **Rekey:** call `rekey()` every 1 GiB or 1 hour of traffic per direction.
- **(P2) Faster reconnect:** `Noise_IK` once the peer's static key is known, which saves one round trip.

### 3.3 Framing
```
+----------------+-------------------------------+
| u16 BE length  | Noise ciphertext (≤ 65535)    |
+----------------+-------------------------------+
```
- Noise messages are at most 65535 bytes. Larger logical messages (e.g. avatar) are split into `Fragment` messages. The maximum logical control message is **1 MiB**, and anything bigger is rejected and the connection closed.
- Plaintext inside each frame is one protobuf `Envelope`.

## 4. Envelope & messages

```protobuf
syntax = "proto3";
package zoocall.v1;

message Envelope {
  uint64 seq = 1;            // per-connection monotonic, detects replay/reorder bugs
  uint64 ack = 2;            // optional piggyback ack for reliable app messages
  oneof body {
    Hello hello = 10;
    Ping ping = 11;
    Pong pong = 12;
    Error error = 13;
    Fragment fragment = 14;

    ProfileRequest profile_request = 20;
    Profile profile = 21;
    Presence presence = 22;
    ContactRequest contact_request = 23;
    ContactResponse contact_response = 24;

    CallInvite call_invite = 40;
    CallRinging call_ringing = 41;
    CallAccept call_accept = 42;
    CallDecline call_decline = 43;
    CallCancel call_cancel = 44;
    CallEnd call_end = 45;
    CallRoster call_roster = 46;
    SessionDescription sdp = 47;
    IceCandidate ice = 48;
    MediaState media_state = 49;
    Knock knock = 50;

    ChatMessage chat = 60;
    ChatReceipt receipt = 61;
    Typing typing = 62;
    Reaction reaction = 63;

    FileOffer file_offer = 80;
    FileResponse file_response = 81;
  }
}
```

### 4.1 Session
| Message | Fields | Notes |
|---|---|---|
| `Hello` | `app_version`, `capabilities[]`, `profile_hash`, `presence` | First message after handshake, both directions |
| `Ping`/`Pong` | `nonce` | Every 25 s idle. Peer is dead after 60 s with no traffic |
| `Error` | `code`, `message` | `UNSUPPORTED`, `RATE_LIMITED`, `NOT_ALLOWED`, `TOO_LARGE`, `BAD_STATE` |

### 4.2 Calls
| Message | Fields |
|---|---|
| `CallInvite` | `call_id` (UUIDv7), `kind` (AUDIO/VIDEO), `group` (optional roster), `caps` (codecs, max_resolution) |
| `CallRinging` | `call_id` |
| `CallAccept` | `call_id`, `accepted_kind` (callee may downgrade video→audio) |
| `CallDecline` | `call_id`, `reason` (DECLINED/BUSY/DND/NOT_ALLOWED), `quick_reply` (optional text) |
| `CallCancel` | `call_id` (caller hung up before answer) |
| `CallEnd` | `call_id`, `reason` |
| `CallRoster` | `call_id`, `host_fp`, `members[] {fp, addresses, joined_at}` |
| `SessionDescription` | `call_id`, `peer_fp` (leg), `type` (OFFER/ANSWER), `sdp` |
| `IceCandidate` | `call_id`, `peer_fp`, `candidate`, `sdp_mid`, `sdp_mline_index`, `end_of_candidates` |
| `MediaState` | `call_id`, `mic_muted`, `camera_on`, `screen_sharing`, `speaking` (optional) |
| `Knock` | `id`, `text` (optional), `reply_options[]` |

**Signaling rules**
- **Offerer:** in 1:1 the caller is the offerer. In group legs the lower fingerprint is the offerer.
- **ICE:** host candidates only (IPv4 private + IPv6 link-local/ULA). No STUN/TURN servers configured, and mDNS-obfuscated candidates are disabled (the channel is already private). Candidates from public IP ranges are dropped.
- **DTLS binding:** each side MUST verify that the `a=fingerprint` in the received SDP came through the authenticated Noise session. The media engine is configured to accept only that certificate.
- **Codec preferences:** audio Opus (48 kHz, in-band FEC on, DTX on for voice). Video in order H.264 (hardware) → VP8 → VP9 → AV1 (only if both sides report HW decode, P2).
- **Timeouts:** invite unanswered by transport 10 s → `unreachable`. Ringing 45 s → `missed`. Accept → connected 15 s → `failed_media`. Reconnect grace 30 s.

**Call collision:** A invites B while B invites A (both `OutgoingInviting` with each other) → the invite with the **lexicographically smaller `call_id`** survives. The other side silently cancels its own invite and treats the surviving invite as auto-accepted, because both parties intended to call.

**Busy:** a callee already in a call gets call-waiting UI if supported (P1). Otherwise it replies `CallDecline{BUSY}` automatically.

### 4.3 Chat
| Message | Fields |
|---|---|
| `ChatMessage` | `id` (ULID), `conversation_id`, `sent_at`, `text` \| `attachment{file_id, name, mime, size, thumb}` \| `voice_note{file_id, duration_ms, waveform}`, `reply_to`, `group_members[]` (group chat) |
| `ChatReceipt` | `ids[]`, `state` (DELIVERED/READ) |
| `Typing` | `conversation_id`, `active` |
| `Reaction` | `message_id`, `emoji`, `remove` |

- **Delivery guarantee:** at-least-once. The receiver dedups by `id`, and the sender keeps items in `outbox` until `DELIVERED` arrives.
- **Ordering:** by `sent_at` then `id`. Clock skew is tolerated because the UI shows receive order for incoming bursts.

### 4.4 File transfer
1. Sender → `FileOffer{file_id, name, size, mime, blake2b_256, chunk_size=256 KiB}` over control channel
2. Receiver → `FileResponse{file_id, accept, resume_from_offset}`
3. Sender opens a **new TCP + Noise connection** tagged `purpose=FILE file_id` in the handshake payload, and streams raw chunks (each Noise frame ≤ 65 KiB).
4. Receiver verifies the BLAKE2b-256 hash at the end. On mismatch, the file is deleted and the error reported.
5. Resume after interruption from the last fully written offset.

Filenames are sanitized (path separators, control characters, reserved Windows names). Files are never auto-opened.

### 4.5 Push-to-talk (P1)
- A WebRTC audio-only session is pre-established with the PTT target(s) when PTT is armed, with the mic track disabled.
- **Floor control** runs over a WebRTC data channel `ptt` using `FloorRequest` / `FloorGranted` / `FloorRelease`. First come wins, and the holder has a 60 s max.
- For a group PTT this is a mesh up to 8 people (P1), or a relay host later.

## 5. Versioning & compatibility

- **Protocol major version** is in the Noise prologue (`zoocall/1`). An incompatible change means `zoocall/2`, and clients support N and N-1 for at least 12 months.
- **Minor evolution** uses protobuf field additions + `capabilities` strings. Unknown fields and unknown `oneof` cases are ignored and never cause errors.
- **Capability registry:** `protocol/capabilities.md` (e.g. `call.video`, `call.group`, `call.screen`, `chat.v1`, `chat.reactions`, `file.v1`, `ptt.v1`, `knock.v1`).
- **Test vectors:** handshake transcripts, SAS derivation, fingerprint, frame encoding and message round-trips. Every client must pass them in CI.

## 6. Rate limits & resource caps (per remote peer)

| Resource | Limit |
|---|---|
| Handshakes from one IP | 10 / minute |
| Concurrent connections from one fingerprint | 3 (control + 2 file) |
| Call invites from a non-contact | 3 / minute, then auto-decline for 10 min |
| Chat messages | 30 / 10 s burst |
| Knocks from a non-contact | 1 / minute |
| Control message size | 1 MiB |
| Pending file offers | 10 |
