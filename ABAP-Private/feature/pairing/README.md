# Pairing

Milestone 1 now includes the device identity, QR protocol, camera scanning and the first complete local pairing workflow.

## Implemented
- P-256 ECDSA identity signing via Android Keystore.
- Versioned invitation and response JSON envelopes with canonical length-prefixed signature transcripts.
- Invitation expiry bounded to 1–10 minutes (default five minutes).
- SHA-256 public-key fingerprints and explicit confirmation gates.
- CameraX preview with on-device ZXing QR decoding; camera permission is requested only when the user starts a scan.
- Invitation scan, signature validation and inviter fingerprint review.
- Signed response creation, response QR display and encrypted pending-response persistence.
- Response scan, signature/invitation-binding validation and responder fingerprint review.
- AES-256-GCM encrypted local state using an Android Keystore key.
- Exactly one pinned peer per device, consumed-invitation tracking and local revoke/re-pair UI.
- Pending response QR survives app restart so the responder can show it again.

## Two-device exchange
1. Phone A displays its signed invitation QR.
2. Phone B scans it, validates the signature and expiry, and displays A's fingerprint.
3. Users compare A's fingerprint over a separate trusted channel. B confirms and displays its signed response QR.
4. Phone B pins A locally and stores the response QR encrypted.
5. Phone A scans the response, verifies the signature and invitation digest, and displays B's fingerprint.
6. Users compare B's fingerprint over a separate trusted channel. A confirms and pins B locally.

Revocation is local only; the other phone is not notified. A new pairing requires both devices to revoke the old peer locally and exchange fresh QR codes.

## Remaining validation
Automated protocol/store tests, replay/tamper/expiry/third-device tests, and two-physical-device tests remain. This code has not yet been independently security-audited. A successful pairing pins identity keys; it does not establish a network connection.

QR payloads contain no network address and do not provide rendezvous, discovery, NAT traversal or connectivity. The app does not use a backend, signalling, STUN/TURN or relay.
