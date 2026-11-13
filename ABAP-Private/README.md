# ABAP Private

**Two devices. One private connection.**

A native Android application for private, one-to-one text messaging and voice calls between exactly two paired Android phones.

## Requirements
- Exactly two authorised device identities; no accounts or public directory.
- Strictly serverless direct P2P over the internet: no backend, signalling, STUN/TURN, relay, Firebase, cloud message store or central database.
- Mutual QR pairing and identity verification.
- Authenticated encrypted direct transport.
- One-to-one text messaging and encrypted local conversation history.
- Direct voice calls using Opus-compressed speech frames.
- User-started foreground peer service with persistent notification and call microphone lifecycle.
- Message acknowledgements, sender-side offline queue, UDP media, adaptive voice quality and configurable retention remain planned.
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
- Direct TCP listener and outgoing endpoint connection on port 47831.
- Signed, expiring direct-endpoint QR codes for exchanging address candidates between already-paired devices; QR signatures are verified against the existing pinned identity, so endpoint changes do not require pairing again.
- Imported endpoint candidates are tried sequentially using authenticated direct TCP; the first valid pinned-peer connection wins.
- Local IPv4/IPv6 interface address enumeration without an external IP lookup service.
- Android NSD advertisement and one-tap discovery of ABAP Private listeners on the same LAN/Wi-Fi.
- Pinned-peer authentication using ephemeral P-256 ECDH, a signed transcript and HKDF-derived directional AES-256-GCM keys.
- Encrypted text frames and encrypted local message history (last 2,000 messages).
- Opus speech encoding/decoding: mono 16 kHz, 20 ms frames, with a bounded 12-frame playout queue.
- Foreground `PeerConnectionService` owns the TCP listener, secure channel, receive loop and audio session independently of the Activity.
- Active calls request the Android microphone foreground-service type, manage audio focus, and support speaker/earpiece switching.
- Android CI includes protocol, encrypted-frame and Opus codec unit tests.

This remains an early prototype, not a production-ready messenger or audited cryptographic protocol. Local NSD discovery works only on the same LAN; internet peers still require a manually supplied reachable endpoint. There is no UDP transport, STUN, NAT traversal, external endpoint rendezvous, delivery receipts, offline message queue, message expiry, Opus adaptive bitrate or packet-loss jitter buffer. Endpoint QR exchange is a signed manual hand-off of current literal IP address candidates; it does not discover public IPv4 addresses or bypass NAT. Opus currently travels over TCP, so head-of-line blocking can affect voice quality. The foreground service can keep an established connection and active call alive during ordinary app backgrounding, subject to Android lifecycle and microphone restrictions; it does not guarantee incoming-call alerts while the app is suspended. Two-physical-device acceptance and independent security review are still required.

## Connectivity limitation
The current transport is direct TCP on port 47831. Both devices must be able to reach the listening endpoint. Global IPv6 with permissive firewalls or public IPv4 with router port forwarding can work; private LAN addresses work only on the same reachable LAN. Carrier-grade NAT, symmetric NAT and restrictive firewalls can prevent connection. Local NSD can discover a peer only on the same local network. Internet connections still require a manually exchanged reachable address; QR pairing does not update endpoints when addresses change. There is no UDP hole punching, rendezvous, relay or push service. A foreground service can maintain an already established session, but cannot wake an unreachable peer or guarantee incoming-call alerts while the app is suspended.

If a direct path cannot be established, ABAP Private must report the limitation and remain offline. It must never silently use a relay or third-party service.
