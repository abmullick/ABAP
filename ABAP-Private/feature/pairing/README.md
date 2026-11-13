# Pairing

Milestone 1 foundation: Android Keystore device identity, signed and expiring invitation QR payloads, invitation-bound signed response payloads, fingerprint calculation and QR bitmap rendering.

## Flow
1. Device A creates a five-minute invitation. Its P-256 private signing key remains in Android Keystore.
2. Device B imports/scans the invitation, validates its expiry and signature, and displays A's SHA-256 public-key fingerprint.
3. The user on B explicitly confirms that fingerprint, then B creates a signed response bound to the invitation digest.
4. Device A imports/scans the response, validates its signature and binding, and displays B's fingerprint.
5. The user on A explicitly confirms B. The returned PinnedPeer can then be persisted by the app's peer store.

The QR payload is bootstrap data only. It contains no network address and does not provide rendezvous, discovery, NAT traversal or connectivity. QR scanning/import UI, encrypted peer-record persistence, durable single-use/replay tracking, revocation UX and physical-device acceptance tests remain to be completed. Do not mark a peer trusted before the corresponding user confirmation.

## Security notes
- ECDSA P-256 / SHA-256 identity signatures via Android Keystore.
- Public keys use X.509 SubjectPublicKeyInfo encoding.
- Canonical length-prefixed binary fields are signed; JSON is only the QR envelope.
- Invitation lifetime is bounded to 1–10 minutes (default five minutes).
- Responses are cryptographically bound to the exact invitation.
- This is an initial implementation, not an independent cryptographic audit.
