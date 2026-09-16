# Zoocall Protocol v1 — Specification (draft)

Status: **draft, implemented by Zoocall 0.1**. The key words MUST, SHOULD and MAY are used as in RFC 2119. Design rationale lives in [docs/03-protocol.md](../../docs/03-protocol.md); this document is the normative wire contract. Schemas: [`proto/zoocall/v1/zoocall.proto`](../proto/zoocall/v1/zoocall.proto).

## 1. Discovery

- Service type `_zoocall._tcp.local.`; the SRV port is the control listener (preferred 47474).
- The instance name MUST be random (not the user's name).
- TXT keys: `v` (comma list of major versions), `fp` (first 20 lowercase base32 chars of the fingerprint), `n` (display name, only when visible to everyone), `r` (role), `d` (`phone` | `tablet` | `desktop`).
- Receivers MUST treat TXT values as untrusted: cap lengths (64 chars for `n`/`r`) and strip control and bidirectional-override characters.
- **Contacts only** visibility: `fp`, `n` and `r` are omitted and `t` carries up to 16 comma-separated tags. A tag is the first 4 bytes, lowercase hex, of `BLAKE2b-128(key = pair_secret, "tag|" ‖ epoch)`, where `epoch = floor(unix_ms / 600000)` in decimal and `pair_secret = BLAKE2b-256("zoocall-discovery-v1" ‖ X25519(own static secret, contact's static public key))`. The instance name changes every epoch. Receivers compare tags for the previous, current and next epoch with their contacts' pair secrets, and MUST NOT show services whose tags match none.

## 2. Identity

- Identity key: X25519 static keypair generated on the device.
- Fingerprint: `BLAKE2b-256("zoocall-fp-v1" ‖ public_key)` (32 bytes). Displayed as lowercase hex internally and base32 prefixes in the UI.

## 3. Secure channel

### 3.1 Handshake
- Pattern `Noise_XX_25519_ChaChaPoly_BLAKE2b`, prologue = ASCII `zoocall/1`.
- Message 1 payload: empty. Messages 2 and 3 payload: encoded `HandshakePayload`.
- A peer MUST abort if `protocol_versions` doesn't contain `1`, if the payload doesn't decode, or if the remote static key equals its own.
- The handshake MUST complete within 10 s.
- Implementations MUST pass the vectors in [`test-vectors/noise-xx-25519-chachapoly-blake2b.json`](../test-vectors/noise-xx-25519-chachapoly-blake2b.json).

### 3.2 Safety codes
- **Session code (6 digits):** `d = BLAKE2b-32(handshake_hash ‖ "zoocall-sas")`; `code = BE32(d[0..4]) mod 1 000 000`, zero-padded to 6 digits. Equal on both ends of one Noise session; a man-in-the-middle yields two different sessions and therefore (with probability ≈ 1 − 10⁻⁶) different codes.
- **Safety number (60 digits):** sort the two fingerprints (lexicographic on hex). For each, split the first 30 bytes into 6 chunks of 5 bytes and output `BE40(chunk) mod 100 000`, zero-padded to 5 digits. Concatenate lower then higher.

### 3.3 Framing
- Each frame: `u16 big-endian length ‖ Noise ciphertext`. Length 0 is invalid.
- Frame plaintext is one encoded `Envelope`, at most 65 519 bytes.
- Larger envelopes are split into `Fragment` envelopes (chunks ≤ 60 000 bytes, `count` ≥ 2) sent consecutively on the same connection. A receiver MUST close the connection on interleaving, out-of-order indices, `count` above ⌈1 MiB / 60 000⌉ + 1, or reassembled size above 1 MiB.
- Rekey: each direction calls Noise `Rekey()` after every 2³⁰ plaintext bytes sent/received in that direction, at the frame boundary where the counter crosses the limit. Both sides count the same bytes, so no signaling is needed.

### 3.4 Session
- After the handshake both sides MUST send `Hello` first. A connection is usable once the peer's `Hello` arrived (10 s timeout).
- Later `Hello` messages update profile/presence.
- `Ping` is sent after 25 s without sending; the connection is closed after 60 s without receiving anything.
- Duplicate connections between the same two identities: keep the connection initiated by the lower fingerprint; if both were initiated by the same side, keep the newer.
- Receivers MUST ignore unknown fields and unknown `oneof` cases.

## 4. Calls (1:1)

| Step | Caller | Callee |
|---|---|---|
| 1 | `CallInvite{call_id, kind}` | replies `CallRinging` (or `CallDecline{BUSY/DND/NOT_ALLOWED}`) |
| 2 | — | user accepts → `CallAccept{accepted_kind}` (may downgrade video → audio) |
| 3 | creates media, sends `SessionDescription{OFFER}` | answers with `SessionDescription{ANSWER}` |
| 4 | `IceCandidate` both ways | |
| 5 | either side `CallEnd` / caller `CallCancel` before answer | |

- `call_id` is a lowercase canonical UUIDv7.
- The caller is the offerer. ICE restart offers are sent by the offerer as a new `SessionDescription{OFFER}`.
- Only `typ host` candidates with private IPv4 (10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10), IPv6 link-local or ULA addresses are allowed; others MUST be dropped on send and receive.
- DTLS fingerprints travel only inside the Noise channel; the media stack MUST reject a DTLS certificate that doesn't match the signaled `a=fingerprint`.
- SDP larger than 32 000 bytes MUST be ignored.
- Timeouts: no `CallRinging` within 10 s → unreachable; ringing 45 s → missed; accept → media connected 15 s → failed; reconnect grace 30 s.
- **Collision:** if a peer receives `CallInvite` from B while its own invite to B is outstanding, the invite with the lexicographically smaller `call_id` survives. The side whose invite lost MUST silently drop it and send `CallAccept` for the surviving invite.
- A callee already in a call MUST reply `CallDecline{BUSY}`, unless it supports `call.waiting` and the current call is past ringing: then it MAY reply `CallRinging` and show the second call as waiting. A waiting call that isn't answered within 45 s is declined `BUSY`.
- `CallDecline.quick_reply` is untrusted text: receivers strip control and bidi-override characters and cap it at 200 characters.

### 4.1 Hold (`call.hold`)
- `MediaState.on_hold = true` means the sender stopped sending audio and video. The receiver shows "On hold" and keeps the call up.

### 4.2 Video upgrade (`call.upgrade`)
- Only in a connected audio call. The requester sends `CallUpgrade{REQUEST}`; the other side answers `ACCEPT` or `DECLINE`; the requester MAY `CANCEL` (and SHOULD after 30 s without an answer).
- A receiver MUST NOT turn on its camera without the user's explicit consent.
- If both sides send `REQUEST` concurrently, each treats the other's request as consent and replies `ACCEPT`.
- After `ACCEPT` both sides add their video track; the offerer (the caller) sends a new `SessionDescription{OFFER}`.

### 4.3 Push-to-talk (`ptt.v1`)
- A session starts with `CallInvite{push_to_talk = true}` and is always audio. The callee MUST NOT ring: it replies `CallAccept` at once when the user allows push-to-talk from that contact, otherwise `CallDecline{NOT_ALLOWED}` (or `DND`, `BUSY`). A refused push-to-talk invite is not a missed call.
- Both sides start with the microphone off. `PttFloor{call_id, talking}` announces start and stop of transmitting; a side MUST NOT open its microphone while the other holds the floor.
- If both sides send `talking = true` before seeing the other's, the caller keeps the floor and the callee turns its microphone off. A sender releases the floor by itself after 60 s (edge case H4).
- Push-to-talk sessions are not recorded in Recents.

### 4.4 Group calls (`call.group`)
- A group call is a full mesh of 1:1 media legs sharing one `call_id`. Only the **host** adds people: the caller of the original 1:1 call, then the earliest joiner still in the call when the host leaves.
- The host sends `CallInvite{call_id, kind, group = true}` and a `CallRoster` to the invitee, who rings normally. After `CallAccept` the host opens the leg to the invitee (the host offers) and sends the updated `CallRoster` to every member.
- A member receiving a roster from the current host opens a leg to each listed member it has none with. For each pair the **lower fingerprint** (lowercase hex comparison) connects to the listed address if needed and sends the offer; the other side waits up to 15 s. Receivers MUST ignore rosters that don't come from the current host and MUST only accept legs from people in the roster.
- SDP, ICE and `MediaState` travel on each pair's own control connection with the group `call_id`. `CallEnd` means the sender left the call.
- When the leg to the person who called us ends, the call continues with the remaining members.
- Limits: 8 people. A video call keeps at most 4 people on video; later joiners get an audio invite and `RosterMember.video = false`. Hold and video upgrade are not used in group calls.

- **Host in a 1:1 call.** Either person may start a group by inviting someone; the inviter becomes the host and sends the `CallRoster`. The other person accepts a roster from their 1:1 partner while the call has no other members. Once there are members, only the current host's rosters count.
- **Transfer** is inviting someone and then leaving with `CallEnd` once their media leg is connected (host transfer then applies as usual). No extra message is needed.

### 4.5 Screen sharing (`call.screen`)
- The sharer sends `MediaState{screen_sharing = true}` to every participant; receivers show that person's video uncropped.
- In a video call the sharer puts the screen on its camera's video sender: no renegotiation. In an audio call it adds a video track and sends a new `SessionDescription{OFFER}` itself, even if it didn't place the call.
- After a leg's first offer/answer exchange, either side MAY renegotiate. A failed renegotiation MUST NOT end the call.
- Stopping sends `screen_sharing = false` and keeps the video section. Desktop clients share; every client with `call.screen` can view.

### 4.6 Linked devices (`device.link`)

- A device lists the fingerprints of the user's other devices (≤ 8) in `Hello.linked_devices`. A link counts only when **both** devices list each other; each claim arrives over that device's own Noise channel, so no extra signature is needed. Devices keep separate identity keys.
- A caller sends the same `CallInvite` (same `call_id`) to the callee and to every connected device mutually linked with it. Each device applies its own "Allow calls from", Do Not Disturb and busy rules.
- The first `CallAccept` takes the call; the caller sends `CallCancel{handled_elsewhere = true}` to the others, which end without a missed call. `CallDecline` with `REASON_DECLINED` from any device declines for the person the same way. `BUSY`, `DND` and `NOT_ALLOWED` only drop that device while others keep ringing.
- If nobody answers, every device gets a plain `CallCancel` and shows a missed call. Push-to-talk and group invitations are never forked.

### 4.7 Desk intercom (`call.intercom`)

- `CallInvite{intercom = true}` asks for an audio-only line that connects without ringing. `kind` is always audio; the invite is never forked to linked devices and the call can't become a group call or carry a screen share.
- The callee answers `CallAccept` at once only if the user turned on desk intercom (off by default) **and** allowed this contact; otherwise `CallDecline` with `REASON_NOT_ALLOWED`, or `REASON_DND` in Do Not Disturb, or `REASON_BUSY` during another call. No missed call is recorded for a refusal.
- Both ends play a chime when the line connects and show a persistent "microphone is on" indicator until it ends (docs/07 H3). Either side hangs up with `CallEnd` as usual.

### 4.8 Call recording (`call.record`)

- A device may record only when every other participant advertises `call.record`, so every app in the call can show it. Recording starts by sending `MediaState{recording = true}` to everyone before or together with the first captured audio, and stops with `recording = false`. A person joining a recorded call receives the recorder's `MediaState` when their media connects; a recorder never adds someone without `call.record`.
- Receivers show a persistent "This call is being recorded" indicator and play a chime while any participant's latest `MediaState` has `recording = true`.
- The recording is local only: mixed audio of all participants, encrypted at rest on the recording device like attachments. Nothing about it is sent to the others.

## 5. Chat

- `ChatMessage.id` is a 26-character ULID; receivers MUST deduplicate by id and MUST reply `ChatReceipt{DELIVERED}` for every received message, including duplicates.
- Senders retransmit undelivered messages whenever a connection to the peer is (re)established.
- `ChatReceipt.state` only moves forward: sent → delivered → read.
- Text is capped at 8 000 characters. Receivers SHOULD rate-limit to 30 messages per 10 s per peer.

### 5.1 Voice notes (`chat.voice`)

- A voice note is a `ChatMessage` with empty `text` and a `voice_note`. It uses the same ids, receipts and outbox as text.
- `codec` MUST be `opus/16000/1;zoocall-framing=1`: Opus at 16 kHz mono, 20 ms frames, concatenated as `u16 BE packet length ‖ packet`. Packet length MUST be 1..1275.
- Senders MUST keep `audio` ≤ 900 KiB and `duration_ms` ≤ 120 000 (so the envelope stays under the 1 MiB message cap). Receivers MUST drop notes with another codec, larger audio, duration outside 500–125 000 ms, or `waveform` longer than 64 bytes.
- `waveform` carries up to 48 amplitude bars (0..255) for display only.
- Voice notes are stored inside the encrypted database and are never auto-played.

### 5.2 Replies and reactions
- `ChatMessage.reply_to` is the id of an earlier message in the same conversation; receivers show it only if they have that message.
- `Reaction{message_id, emoji, remove}` (`chat.reactions`) sets, replaces or removes the sender's single reaction. `emoji` is at most 16 UTF-16 units. Receivers MUST ignore reactions to messages that aren't in the conversation with the sender. Senders queue reactions made while offline.

### 5.3 Search
- Search is local only and never sent to peers.

### 5.4 Knock (`knock.v1`)
- `Knock{id, text}` is a live "Are you free?" nudge. It is sent only over an established control connection and is never queued or stored as a message.
- `text` is untrusted: receivers strip control characters and cap it at 120 characters.
- A reply is `Knock{id, in_reply_to, reply}` with `reply` one of `CALL_ME`, `TWO_MINUTES`, `BUSY`. Receivers MUST ignore replies to knocks they didn't send to that peer, or that are older than 10 minutes.
- Receivers rate-limit knocks to 1 per minute from non-contacts and 5 per minute from contacts. In Do Not Disturb, knocks from non-favorites are shown without sound.

### 5.5 Disappearing messages (`chat.disappear`)

- Each conversation has a timer (off, 1 hour, 1 day or 1 week). Every `ChatMessage` sent while it's on carries `expires_in_s` (≤ 4 weeks). The sender deletes its copy `expires_in_s` after `sent_at_ms`; the receiver deletes its copy that long after it arrived, so a message that waited in an outbox isn't lost on arrival.
- A receiver whose peer advertises `chat.disappear` adopts a changed value (including 0) as the conversation's timer, so both sides converge on the most recent choice. Values from peers without the capability are ignored.
- Deletion is local and best effort: it can't stop screenshots or copies, and the UI says so.

### 5.6 Group chats (`chat.group`)

- A group chat has a ULID `id`, a `name` (≤ 40 characters) and a member list (≤ 32 fingerprints, including every member's own). There is no server: the sender sends each group message as a normal `ChatMessage` with `group` set, separately to every other member, and keeps its own outbox per member. Receipts (`ChatReceipt`) come back from each member as usual, so the sender can show "delivered to 3 of 5".
- A receiver files the message under `group.id`. It creates the group when it doesn't know it (only if both the sender and the receiver are listed), and replaces the name and members when `updated_at_ms` is newer than what it has. Only a sender who is already a member of the stored group can change it.
- A `ChatMessage` with only `group` set is a group change (e.g. someone left: the list without them). A receiver that is no longer listed marks the group as left and ignores its messages.
- Group chats carry text and voice notes. File attachments, reactions, typing indicators and disappearing timers are not used in groups. Older apps without `chat.group` see group messages in the 1:1 chat with the sender.

## 6. Files (`file.v1`)

1. The sender sends a `ChatMessage` with empty `text` and `attachment{file_id = message id, name, mime, size, blake2b_256}`. It uses the normal outbox and receipts.
2. Receivers MUST sanitize `name` (strip path components, control and bidi characters, Windows reserved names; ≤ 128 characters) and MUST NOT open files automatically. Sizes above 64 GiB and hashes other than 32 bytes invalidate the message.
3. To get the bytes the receiver sends `FileResponse{file_id, accept = true, resume_from_offset}` where the offset is the length of its verified partial file. `accept = false` asks the sender to stop.
4. The sender opens a new TCP connection to the receiver's listen port and runs the Noise XX handshake with `HandshakePayload{purpose = FILE, file_id}`. The receiver MUST close FILE connections from identities without an established control connection, for unknown `file_id`s, or beyond 2 concurrent FILE connections per peer, and MUST check the key matches the attachment's sender.
5. On the FILE connection the sender's first frame is 8 bytes, the start offset (u64 BE, ≤ the receiver's requested offset). Then raw file chunks of ≤ 60 000 bytes follow, one per Noise frame, until `size` bytes are delivered; then the sender closes.
6. The receiver verifies BLAKE2b-256 of the complete file. On mismatch it deletes the file and reports failure.
7. Resume: after an interruption the receiver sends a new `FileResponse` with its current offset (on reconnect, or after a short back-off). A sender that paused and resumes sends `FileOffer{file_id, size, blake2b_256}`; a receiver still wanting the file answers with `FileResponse`.
8. Receivers SHOULD download automatically only from contacts and only below 100 MiB.
