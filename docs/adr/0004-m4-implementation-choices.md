# ADR 0004 — M4 implementation choices

Status: accepted · 2026-09-14

These refine the approved plan (docs/00–10) for M4 (hardening) where it left details open.

| # | Decision | Why |
|---|---|---|
| 1 | **Attachments are encrypted at rest with a key per file.** The key is a random 256-bit value in `attachment.file_key` (migration v5), inside the SQLCipher database whose key is in the OS keystore. A file is `"ZCA1"` + 64 KiB chunks sealed with ChaCha20-Poly1305 (IETF), nonce = chunk index. Partial downloads keep only complete chunks and resume exactly there; a failed download restarts with a new key. Files from before v5 have no key and stay readable as plain files. | Deleting the row makes the file unreadable (crypto-shredding). Independent chunks keep resumable downloads and uploads from any offset; the per-file key rules out nonce reuse. No new dependency: the same libsodium AEAD as the Noise channel. |
| 2 | **Opening or saving an attachment makes a plain copy** in `attachments/exports`, deleted at the next start. In-app image previews decrypt into memory only. | Another app can only open plain bytes, and the copy exists only when the user asks for it. |
| 3 | **The desktop shell's text comes from the shared strings** through a small public `ShellText` list (tray menu, window titles, system notifications, the "already running" dialog). Direction-dependent icons (call direction, volume, open in new, screen share) use the auto-mirrored Material variants; layouts use start/end only. | The generated `Res` stays internal to `:shared:ui`, while every visible string is translated once, in one place. Resolves ADR 0003 #7. |
| 4 | **macOS keys live in the login Keychain** as generic-password items (service `io.github.flytorakib.zoocall`, one account per data directory and key) through Security.framework `SecItem*` via the existing JNA dependency. Key files written before are moved in on first read. If the Keychain can't be used the owner-only file (0600) stays as the fallback. Verified to compile on Windows; needs a run on a Mac. | The plan's Keychain requirement (docs/06) without a new native library; the fallback keeps an unusual Mac setup from locking the user out of their identity. |
