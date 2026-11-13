# core/network

Current direct-network implementation:
- TCP listener on port 47831 and outgoing direct endpoint connection.
- Signed endpoint QR payloads (24-hour default expiry) bound to the already-pinned peer public key; supports multiple literal IPv4/IPv6 candidates and sequential direct connection attempts.
- Wildcard listener bind to accept traffic on local interfaces (dual-stack behaviour depends on Android/network configuration and needs device validation).
- Peer authentication against the public identity key pinned during QR pairing.
- Ephemeral P-256 ECDH, signed transcript, HKDF-SHA256 key derivation and directional AES-256-GCM.
- Bounded encrypted frames, monotonically increasing per-direction counters and replay/out-of-order rejection.
- Bounded handshake timeout and one concurrent handshake at a time.
- Local IPv4/IPv6 interface enumeration.
- Android NSD advertisement and discovery for listeners on the same LAN/Wi-Fi.

Limitations:
- NSD is local-network discovery only; it does not discover internet peers or coordinate across mobile networks.
- Internet peers exchange a signed endpoint QR image out-of-band (for example, using an existing messaging app). The QR includes current detected addresses and an optional user-entered public IPv4 address. A new QR is needed when the reachable address changes.
- No external IP lookup, rendezvous, STUN/TURN, relay, UDP hole punching or automatic endpoint update is used.
- Global IPv6 candidates may still be blocked by firewall policy. Public IPv4 generally needs router port forwarding for TCP 47831.
- Carrier-grade NAT or restrictive firewall policies can make the peer unreachable.
- The custom handshake is an initial prototype and requires independent cryptographic review; it is not a substitute for a vetted Noise implementation in a production release.

Unit tests exercise pinned-peer authentication and encrypted bidirectional frame exchange. Physical-device IPv4/IPv6, Wi-Fi/mobile and NSD acceptance tests remain pending.
