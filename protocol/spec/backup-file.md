# Zoocall backup file (`.zcbackup`) — format 1

An encrypted export of one device's profile, contacts, groups and chats, used to move to a new device (docs/01 §9, edge cases D2/D3). It is a local file the user saves and carries themselves; nothing about it goes over the network.

## Contents

The plaintext is a protobuf `zoocall.v1.Backup` (`protocol/proto/zoocall/v1/backup.proto`):

- Profile name and role.
- Contacts with their public keys, verified / favorite / blocked flags and push-to-talk / intercom opt-ins.
- Contact groups (names and member fingerprints).
- Text and voice messages with their states and timestamps.

Never included: the device's own identity key (a restored device has a new key, and contacts see a key change), attachment files, disappearing messages, linked-device links, reactions and settings.

## Container

```
magic      4 bytes   "ZCB1"
salt      16 bytes   random, Argon2id salt
opslimit   4 bytes   unsigned, big-endian (writers use 3)
memlimit   4 bytes   unsigned, big-endian, bytes (writers use 64 MiB)
chunks     …         ciphertext
```

- `key = Argon2id13(passphrase, salt, opslimit, memlimit)`, 32 bytes (libsodium `crypto_pwhash`).
- The plaintext is split into chunks of 64 KiB (the last one may be shorter; an empty payload is one empty chunk). Each chunk is sealed with ChaCha20-Poly1305-IETF: associated data = the 28-byte header, nonce = 12 bytes with the chunk index big-endian in the last 8 bytes, and the first byte's top bit set on the **last** chunk only. A file cut off at a chunk boundary therefore fails to open.
- Readers reject opslimit outside 1..10, memlimit outside 8 KiB..256 MiB and plaintexts over 256 MiB.

## Restoring

- Contacts already on the device are left as they are. A contact whose fingerprint doesn't match its public key is skipped.
- Messages are inserted by id, so restoring twice doesn't duplicate them. Outgoing messages that were still waiting to send are restored as sent, because this device's new key can't deliver them as the old one.
- Before onboarding, the backup's name and role become the profile.
