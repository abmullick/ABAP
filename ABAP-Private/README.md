# ABAP Private

**Two devices. One private connection.**

A native Android project scaffold for private, one-to-one text messaging and voice calls between exactly two paired Android phones.

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

- `app/` — Android application entry point and Compose UI.
- `core/` — common types, identity, crypto, protocol, network, storage and security.
- `feature/` — pairing, chat, calling, connection, diagnostics and settings.
- `service/` — foreground connection and audio service boundaries.
- `gradle/` — version catalog and build configuration.

See the [technical architecture](./ARCHITECTURE.md), [security requirements](./SECURITY.md), and [development roadmap](./ROADMAP.md).

## Build setup

Open the `ABAP-Private` directory in Android Studio with JDK 17 and an Android SDK supporting compile SDK 35. Let Android Studio install the required Gradle distribution and SDK components if prompted.

The scaffold currently pins Android Gradle Plugin 8.9.2 and Kotlin 2.1.20 in `gradle/libs.versions.toml`. These are initial pinned versions and should be reviewed/updated deliberately as the project progresses.

## Important connectivity limitation

Serverless connectivity cannot be guaranteed across arbitrary networks. IPv6 direct connectivity requires globally reachable IPv6 and permissive firewalls. IPv4 may require a stable public endpoint and port forwarding. UDP hole punching is best-effort and cannot be assumed to work across every carrier-grade NAT. QR pairing does not automatically update a peer when its address changes. Without rendezvous or push infrastructure, automatic discovery and incoming calls while suspended cannot be guaranteed.

If a direct path cannot be established, ABAP Private must report the limitation and remain offline. It must never silently use a relay or third-party service.

## Status

**Android project scaffold created; P2P functionality is not implemented yet.** The repository contains Gradle configuration, module boundaries, an Android manifest and a minimal Compose launch screen. QR pairing, cryptography, direct networking, messaging, voice, foreground connection handling and retention remain implementation work.
