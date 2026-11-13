# ABAP Private

**Two devices. One private connection.**

A native Android application for private, one-to-one text messaging and voice calls between exactly two paired Android phones.

## Requirements
- Exactly two authorised device identities; no accounts or public directory.
- Strictly serverless direct P2P over the internet: no backend, signalling, STUN/TURN, relay, Firebase, cloud message store or central database.
- Mutual QR pairing and identity verification.
- Authenticated encrypted transport.
- Text messaging with acknowledgements and sender-side offline queue.
- Direct voice calling using Opus.
- User-enabled foreground service.
- Configurable temporary message and attachment retention.
- Encrypted local storage and disabled cloud backup.

## Project structure
- app/ — Android application entry point and Compose UI.
- core/ — common types, identity, crypto, protocol, network, storage and security.
- feature/ — pairing, chat, calling, connection, diagnostics and settings.
- service/ — foreground connection and audio service boundaries.
- gradle/ — version catalog and build configuration.

See ARCHITECTURE.md, SECURITY.md and ROADMAP.md.

## Build setup
Open the ABAP-Private directory in Android Studio with JDK 17 and an Android SDK supporting compile SDK 35. The project pins Android Gradle Plugin 8.9.2 and Kotlin 2.1.20. A Gradle wrapper has not yet been committed.

## Current implementation status
The Milestone 1 foundation now includes:
- A P-256 ECDSA signing identity generated and retained by Android Keystore.
- Versioned signed invitation and response payloads.
- Invitation expiry validation and cryptographic response binding to the invitation.
- SHA-256 public-key fingerprints and explicit confirmation gates in the pairing API.
- QR bitmap generation and an initial screen displaying this device's fingerprint and invitation QR.

Still missing: camera scanning/import UI, reciprocal fingerprint confirmation screens, secure durable peer pinning, durable replay prevention, revoke/re-pair UX and physical-device tests. This is not yet a complete pairing workflow. No direct P2P networking, messaging or voice transport has been implemented. The app has not yet been compiled or tested in this environment.

## Connectivity limitation
Serverless connectivity cannot be guaranteed across arbitrary networks. IPv6 direct connectivity requires globally reachable IPv6 and permissive firewalls. IPv4 may require a stable public endpoint and port forwarding. UDP hole punching is best-effort and cannot be assumed to work across every carrier-grade NAT. QR pairing does not automatically update a peer when its address changes. Without rendezvous or push infrastructure, automatic discovery and incoming calls while suspended cannot be guaranteed.

If a direct path cannot be established, ABAP Private must report the limitation and remain offline. It must never silently use a relay or third-party service.
