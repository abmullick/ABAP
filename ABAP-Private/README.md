# ABAP Private

**Two devices. One private connection.**

A planned native Android application for private, one-to-one text messaging and voice calls between exactly two paired Android phones.

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

## Important connectivity limitation
Serverless connectivity cannot be guaranteed across arbitrary networks. IPv6 direct connectivity requires globally reachable IPv6 and permissive firewalls. IPv4 may require a stable public endpoint and port forwarding. UDP hole punching is best-effort and cannot be assumed to work across every carrier-grade NAT. QR pairing does not automatically update a peer when its address changes. Without rendezvous or push infrastructure, automatic discovery and incoming calls while suspended cannot be guaranteed.

If a direct path cannot be established, ABAP Private must report the limitation and remain offline. It must never silently use a relay or third-party service.

## Documentation
- [Technical architecture](./ARCHITECTURE.md)
- [Security and privacy](./SECURITY.md)
- [Development roadmap and acceptance tests](./ROADMAP.md)

## Status
**Planning / architecture specification.** This directory documents the agreed design; it does not yet contain a functioning Android application or validated networking implementation.
