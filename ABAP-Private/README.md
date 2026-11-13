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
The first end-to-end local QR pairing workflow is now implemented:
- A P-256 ECDSA signing identity generated and retained by Android Keystore.
- Versioned signed invitation and response payloads, invitation expiry validation and response binding.
- CameraX QR preview with on-device ZXing decoding.
- Reciprocal inviter/responder fingerprint review and explicit confirmation screens.
- AES-256-GCM encrypted single-peer persistence, consumed-invitation tracking and local revoke/re-pair controls.
- The responder's signed response QR is stored encrypted so it can be shown again after app restart.
- Android CI builds the debug APK and runs unit-test tasks.

Still required: automated replay/tamper/expiry/third-device tests and two-physical-device acceptance testing. The pairing workflow has not yet been independently security-audited. No direct P2P networking, messaging or voice transport has been implemented.

## Connectivity limitation
Serverless connectivity cannot be guaranteed across arbitrary networks. IPv6 direct connectivity requires globally reachable IPv6 and permissive firewalls. IPv4 may require a stable public endpoint and port forwarding. UDP hole punching is best-effort and cannot be assumed to work across every carrier-grade NAT. QR pairing does not automatically update a peer when its address changes. Without rendezvous or push infrastructure, automatic discovery and incoming calls while suspended cannot be guaranteed.

If a direct path cannot be established, ABAP Private must report the limitation and remain offline. It must never silently use a relay or third-party service.
