# core/network

Initial implementation:
- Direct TCP listener on port 47831 and manually configured outgoing endpoint.
- Peer authentication against the public identity key pinned during QR pairing.
- Ephemeral P-256 ECDH, signed transcript, HKDF-SHA256 key derivation and directional AES-256-GCM.
- Bounded encrypted frames, monotonically increasing per-direction counters and replay/out-of-order rejection.
- Bounded handshake timeout and one concurrent handshake at a time.

Limitations:
- No automatic endpoint discovery, global IPv6 enumeration, UDP transport, NAT traversal, rendezvous, STUN/TURN or relay.
- The custom handshake is an initial prototype and requires independent cryptographic review; it is not a substitute for a vetted Noise implementation in a production release.
- A direct route must already exist. Carrier-grade NAT or restrictive firewall policies may make the peer unreachable.

Unit tests exercise pinned-peer authentication and encrypted bidirectional frame exchange. Physical-device testing remains pending.
