# Security policy

Zoocall protects calls and messages with end-to-end encryption on the local network. We take vulnerabilities seriously.

## Reporting a vulnerability

Please **don't open a public issue**. Report privately through [GitHub Security Advisories](https://github.com/FlyToRakib/zoocall/security/advisories/new).

Include what you found, how to reproduce it, and the affected versions. You'll get a reply within 7 days. We follow a 90-day disclosure policy and credit reporters who want credit.

## Scope

In scope: the Noise implementation (`shared/core/crypto`), transport and framing (`shared/core/transport`, `shared/core/protocol`), key storage, media binding (DTLS fingerprints signaled over Noise), discovery parsing, and the Android and desktop apps.

Out of scope: attacks that need a compromised operating system, root/admin access, or physical access to an unlocked device. See the threat model in [docs/04-security-privacy.md](docs/04-security-privacy.md).

## Changes to security-critical code

Changes to `shared/core/crypto`, `shared/core/transport`, `shared/core/protocol` and `protocol/` need review by two maintainers and must keep the protocol test vectors passing.
