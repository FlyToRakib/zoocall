# ADR 0002 — Implementation choices for M2 (everyday calling + chat)

Status: Accepted · 2026-09-14

| # | Decision | Why |
|---|---|---|
| 1 | **Attachment metadata travels in `ChatMessage.attachment`**; `FileOffer` carries only `file_id`, `size` and hash and is used to announce that a paused upload is available again. `file_id` equals the message id. | The file message then gets the outbox, receipts, replies and dedup for free. docs/03 §4.4 listed name/mime in `FileOffer`; carrying them twice would allow them to disagree. |
| 2 | **The receiver drives transfers** with `FileResponse{accept, offset}`; the sender opens the FILE connection (as in docs/03) and first sends a u64 start offset. | Pause, resume after reconnect and retry after a dropped FILE connection all reduce to "send `FileResponse` with my current offset". |
| 3 | **Files live unencrypted in the app-private directory** (Android `noBackupFilesDir`, desktop data dir), named by message id; the database keeps metadata and state. | Encrypted attachments at rest are an M4 item. App-private storage keeps them away from other apps meanwhile. Names from the network are never used as paths. |
| 4 | **New module `:shared:core:files`** (depends on transport + store; okio for file I/O). Chat stays independent of files through an `onAttachmentReceived` callback. | ADR 0001 #6 deferred it until there was code. okio is already on the classpath through Wire. |
| 5 | **Automatic download** only for contacts and files ≤ 100 MiB. Everything else waits for a tap. | Edge cases M7 / M10. |
| 6 | **Message search uses `LIKE` over message bodies and file names**, not FTS5. | Works identically on SQLCipher Android and sqlite-jdbc-crypt without dialect changes, and handles Bengali (FTS5's default tokenizer splits it poorly). Local conversation sizes keep it fast. Revisit if search gets slow. |
| 7 | **Reactions: one per person per message**, stored with a `pending` flag and flushed with the outbox. | Matches the 1:1 design and makes offline reactions reliable without extra protocol. |
| 8 | **Message requests are local UI**: strangers' messages are no longer auto-saved as contacts; the conversation shows Accept / Block. `ContactRequest`/`ContactResponse` (fields 23/24) stay reserved. | Edge case M10 without new wire messages. |
| 9 | **Custom status is `Hello.status_text`** (≤ 80 chars). `PresenceUpdate` stays unused. | Presence already travels in `Hello`, which is re-broadcast on every change. |
| 10 | **Video upgrade**: `CallUpgrade` request/accept/decline/cancel (field 51); after accept both sides add the camera track and the caller (offerer) renegotiates. Answerer tracks added before the new offer are matched to its video section (JSEP §5.10). | Keeps "the caller is always the offerer", which ICE restart already relies on, and never enables a camera without consent (C11). |
| 11 | **Hold** is `MediaState.on_hold` and disables local tracks; **call waiting** offers *End & accept* or *Decline*. *Hold & accept* (two live media sessions) is not implemented. | Two concurrent peer connections would double the call manager's state for a rare case. A waiting call that is left alone rings normally once the current call ends. |
| 12 | **Quick-reply decline** sends `CallDecline.quick_reply` and the same text as a chat message. | The caller sees it immediately, and it stays in the conversation. |
| 13 | **Windows firewall rule is added from the app, not the MSI.** The MSI is a per-user install without admin rights; the app offers "Allow through Windows Firewall", which runs an elevated `netsh advfirewall` for the app's executable on the Private profile only. | A per-user installer can't write firewall rules, and a machine-wide installer would require admin for every update. |
