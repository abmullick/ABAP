# ABAP Private

**Two devices. One private connection.**

A native Android application for private, one-to-one text messaging and voice calls between exactly two paired Android phones.

## Requirements
- Exactly two authorised device identities; no accounts or public directory.
- Strictly serverless direct P2P over the internet: no backend, signalling, STUN/TURN, relay, Firebase, cloud message store or central database.
- Mutual QR pairing and identity verification.
- Authenticated encrypted direct transport.
- One-to-one text messaging and encrypted local conversation history.
- Direct voice calls with microphone capture and playback.
- User-enabled foreground service.
- Message acknowledgements, sender-side offline queue, Opus voice and configurable retention remain planned.
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
The first end-to-end local QR pairing workflow is now implemented:
- A P-256 ECDSA signing identity generated and retained by Android Keystore.
- Versioned signed invitation and response payloads, invitation expiry validation and response binding.
- CameraX QR preview with on-device ZXing decoding, plus gallery/document-picker import of QR images.
- Signed invitation and response QR codes can be exported as PNG files and exchanged remotely through an independent channel (email or an existing messaging app).
- Reciprocal inviter/responder fingerprint review and explicit confirmation screens.
- AES-256-GCM encrypted single-peer persistence, consumed-invitation tracking and encrypted persistence of the active invitation and responder's pending response.
- Existing peer trust is restored at startup; ordinary app restarts and network changes do not request pairing again. The saved response remains available for export after restart.
- Pairing reset is intentionally not exposed as a routine in-app action; clearing app data removes the local pairing record.
- Direct TCP listener and manual peer endpoint connection on port 47831.
- Pinned-peer authentication using an ephemeral P-256 ECDH exchange, signed transcript and HKDF-derived directional AES-256-GCM keys.
- Encrypted text frames and local encrypted message history (last 2,000 messages).
- Initial two-way voice-call request/accept/reject/end controls with 16 kHz mono PCM audio over the encrypted connection.
- Android CI builds the debug APK and runs unit tests, including direct transport authentication and encrypted-frame exchange tests.

This is an initial direct-connectivity prototype, not a production-ready messenger or audited cryptographic protocol. It has no automatic endpoint discovery, UDP/NAT traversal, delivery receipts, offline message queue, Opus codec, adaptive jitter buffer or foreground call service. Voice currently requires the app to remain active. Two-physical-device acceptance and independent security review are still required.

## Connectivity limitation
The current transport is direct TCP on port 47831. Both devices must be able to reach the listening endpoint. Global IPv6 with permissive firewalls or public IPv4 with router port forwarding can work; private LAN addresses work only on the same reachable LAN. Carrier-grade NAT, symmetric NAT and restrictive firewalls can prevent connection. There is no automatic endpoint discovery, UDP hole punching, relay or push service. QR pairing does not automatically update a peer when its address changes. Incoming calls while the app is suspended cannot be guaranteed.

If a direct path cannot be established, ABAP Private must report the limitation and remain offline. It must never silently use a relay or third-party service.
