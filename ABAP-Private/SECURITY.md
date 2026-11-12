# ABAP Private — Security and Privacy Requirements

## Security objectives
- Only two mutually paired device identities can exchange application data.
- All application payloads are authenticated and encrypted in transit.
- Persistent messages and attachments are encrypted at rest.
- Expired content is made inaccessible and physically removed on a best-effort basis.
- No content is sent to analytics, crash reporting, cloud backup or external services.

## Identity and pairing
- Generate high-entropy identity keys on-device.
- Separate long-term identity keys from ephemeral session and traffic keys.
- Protect private material using Android Keystore-backed protection.
- Authenticate pairing challenges and bind both identities into the pairing transcript.
- Require explicit fingerprint comparison/confirmation.
- Expire single-use pairing invitations; reject replay.
- Pin the trusted peer identity and reject all other identities.
- Provide deliberate revoke-and-repair workflow.
- Never place private keys or reusable secrets in QR data.

## Transport
- Use a vetted Noise implementation and documented suite.
- Authenticate the pinned peer before accepting application data.
- Encrypt/authenticate every application packet.
- Enforce nonce uniqueness, replay protection and packet bounds.
- Validate lengths and versions before allocation/parsing.
- Use bounded queues and rate limits against resource exhaustion.
- Never log keys, plaintext messages, attachments, audio or complete QR payloads.
- No relay or plaintext fallback.

## At-rest data
- Encrypt database and attachment files.
- Protect database keys using Android Keystore.
- Exclude application data from Android cloud backup.
- Avoid plaintext notification previews and unnecessary clipboard use.
- Purge expired messages, attachments, thumbnails and temporary copies.
- Treat SQLite journals/WAL and crash artifacts as sensitive.
- Do not record calls by default.

## Threat model and limitations
Protections target network interception, peer impersonation, packet tampering/replay, casual local database extraction and accidental disclosure. The app cannot guarantee protection against a compromised/rooted OS, malware, malicious accessibility services, screenshots, external cameras, traffic analysis, denial of service, carrier metadata collection or content exported by the recipient.

IP addresses, timing and traffic volume remain visible to network operators. Strict serverlessness means no automatic rendezvous after arbitrary IP changes, no universal traversal of CGNAT/restrictive firewalls, no guaranteed incoming calls while the app is unavailable, and no delivery while the recipient is offline. Explain these constraints in the UI and documentation.

## Release security gates
- Independent review of cryptographic protocol integration.
- Verify dependency versions, integrity and licences.
- Static analysis and dependency vulnerability checks.
- Packet capture confirms no plaintext application payloads.
- Negative tests for invalid identity, replay, malformed frames and wrong keys.
- Test expiry and backup exclusion.
- Inspect logs, notifications and crash output for sensitive data.
- Verify no app traffic to external services beyond the paired peer and required local Android platform operations.
