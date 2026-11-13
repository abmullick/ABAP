# Pairing

Milestone 1 foundation: Android Keystore device identity, signed and expiring invitation QR payloads, invitation-bound signed response payloads, exact fingerprint confirmation, QR bitmap rendering and encrypted peer-state persistence.

## Implemented building blocks
- P-256 ECDSA identity signing via Android Keystore.
- Versioned invitation and response JSON envelopes with canonical length-prefixed signature transcripts.
- Invitation expiry bounded to 1–10 minutes (default five minutes).
- SHA-256 public-key fingerprints.
- API gates require the exact displayed fingerprint string to match the scanned public key before response creation or peer acceptance.
- AES-256-GCM encrypted SharedPreferences state using an Android Keystore key.
- One-peer pin slot and a bounded consumed-invitation ID ledger. A different peer cannot replace an existing peer without explicit revocation.

## Remaining integration
The app currently displays its invitation QR. Camera scanning/import UI, reciprocal confirmation screens, the complete responder/inviter state machine, integration of EncryptedPairingStore into that flow, revoke/re-pair UX and physical-device acceptance tests remain open.

QR payloads contain no network address and do not provide rendezvous, discovery, NAT traversal or connectivity. Pairing does not imply that a direct network path exists.

This is an initial implementation, not an independent cryptographic audit.
