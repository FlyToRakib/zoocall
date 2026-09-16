# Capability registry

Capabilities are exchanged in `Hello.capabilities`. A feature is used only when **both** peers list it. Unknown capabilities are ignored.

| Capability | Since | Meaning |
|---|---|---|
| `call.audio` | 0.1 | 1:1 audio calls (`CallInvite` … `CallEnd`, SDP/ICE signaling) |
| `call.video` | 0.1 | 1:1 video calls, `MediaState.camera_on` |
| `chat.v1` | 0.1 | `ChatMessage` text messages with at-least-once delivery |
| `chat.receipts` | 0.1 | `ChatReceipt` DELIVERED / READ |
| `chat.typing` | 0.1 | `Typing` indicator |
| `chat.voice` | 0.1 | `ChatMessage.voice_note`: inline Opus voice notes (≤ 120 s, ≤ 900 KiB) |
| `call.upgrade` | 0.2 | `CallUpgrade` request / accept / decline / cancel for audio → video with consent |
| `call.hold` | 0.2 | `MediaState.on_hold` |
| `call.waiting` | 0.2 | A callee in a call may ring a second invite (`CallRinging`) instead of answering `BUSY` |
| `chat.reactions` | 0.2 | `Reaction`: one emoji per person per message |
| `file.v1` | 0.2 | `ChatMessage.attachment`, `FileOffer`, `FileResponse` and FILE connections |
| `knock.v1` | 0.3 | `Knock`: live "Are you free?" nudge and its one-tap replies (Call me / 2 min / Busy) |
| `chat.announce` | 1.1 | `ChatMessage.announcement`: a group broadcast that alerts once. Older clients show it as a normal message, so senders don't need to check it |
| `chat.group` | 1.1 | `ChatMessage.group` (`GroupChatInfo`): small group chats up to 32 people, fanned out by the sender (spec §5.6) |
| `chat.disappear` | 1.1 | `ChatMessage.expires_in_s`: disappearing messages; receivers delete after the timer and adopt it as the chat's (spec §5.5) |
| `call.record` | 1.1 | `MediaState.recording`: call recording with everyone told; required from every participant before recording can start (spec §4.8) |
| `call.intercom` | 1.1 | `CallInvite.intercom`: desk intercom line, audio only, connects without ringing where the callee allowed it (spec §4.7) |
| `device.link` | 1.1 | `Hello.linked_devices` and `CallCancel.handled_elsewhere`: a person's mutually linked devices ring together (spec §4.6) |
| `call.group` | 0.3 | Group calls (full mesh): `CallInvite.group`, `CallRoster`; audio ≤ 8, video ≤ 4 |
| `call.screen` | 0.3 | Receive and render a shared screen (`MediaState.screen_sharing`); accept renegotiation offers from either side |
| `ptt.v1` | 0.3 | 1:1 push-to-talk: `CallInvite.push_to_talk` (auto-accepted, never rings, opt-in per contact) and `PttFloor` |

`Hello.status_text` (custom status) is a plain field and needs no capability: old clients ignore it.

Every planned M3 capability is now implemented.
