# ABAP Private — Development Roadmap

## Milestone 0: Android foundation
- [x] Create native Kotlin Android project and Gradle version catalog.
- [x] Establish package namespace and module boundaries.
- [x] Add an Android build and unit-test CI workflow.
- [ ] Add formatting, static analysis and unit-test coverage.

**Exit:** clean debug build and baseline tests pass.

## Milestone 1: Identity and QR pairing
- [x] Generate a persistent P-256 device signing identity in Android Keystore.
- [x] Define versioned, signed invitation and response QR payloads.
- [x] Add bounded invitation expiry, signature validation and invitation-digest binding.
- [x] Add SHA-256 public-key fingerprints and exact-match confirmation gates.
- [x] Add QR bitmap generation and invitation display.
- [x] Add on-device CameraX QR scanning and ZXing decoding.
- [x] Add reciprocal invitation/response review and fingerprint-confirmation screens.
- [x] Integrate encrypted single-peer storage, invitation consumption and local peer revocation.
- [x] Persist the responder's signed response QR encrypted so pairing can be resumed after app restart.
- [x] Add JVM protocol tests for invitation/response round-trip, signature tampering, expiry, wrong fingerprint and invitation binding.
- [ ] Add store replay tests, malformed-payload fuzzing and third-device rejection tests.
- [ ] Complete two-physical-device acceptance tests, including app restart during pairing.

**Exit:** two physical devices pair and mutually authenticate; an unrelated third device is rejected. UI and storage integration are implemented, but this exit criterion remains pending until automated and physical-device tests pass.

## Milestone 2: Direct encrypted connectivity proof
- [ ] Implement network/interface discovery.
- [ ] Enumerate global IPv6 candidates and configured IPv4 endpoints.
- [ ] Implement direct UDP sockets.
- [ ] Integrate vetted Noise handshake and pinned peer authentication.
- [ ] Add packet framing, counters, replay rejection and diagnostics.
- [ ] Test IPv6, configured IPv4, Wi-Fi and mobile combinations.

**Exit:** two physical devices establish direct authenticated encrypted connectivity on documented supported networks, with packet capture evidence and no server/relay traffic.

## Milestone 3: Reliable messaging
- Implement versioned message schema.
- Add message IDs, sequence tracking and acknowledgements.
- Add bounded sender-side queue and retry.
- Add encrypted local persistence.
- Add duplicate suppression and reconnect reconciliation.
- Implement expiry and cleanup.

**Exit:** text delivery, acknowledgement, offline queueing, reconnection and expiry tests pass.

## Milestone 4: Voice calling
- Implement AudioRecord/AudioTrack pipeline.
- Integrate libopus.
- Add encrypted real-time voice channel.
- Add jitter buffer, loss concealment and adaptive bitrate.
- Add call state machine, mute, speaker, audio focus and Bluetooth routing.

**Exit:** stable two-way calls on supported paths; measure latency, packet loss and battery use on real devices.

## Milestone 5: Android lifecycle and UX
- Implement foreground service and persistent notification.
- Add user-controlled always-connected mode.
- Handle network changes and bounded reconnection.
- Implement incoming-call UI when peer is reachable.
- Add connection, security and retention diagnostics.

**Exit:** test screen lock, app navigation, network switches, Doze/battery policy and process recreation. Document OS restrictions.

## Milestone 6: Release hardening
- Independent cryptographic integration review.
- Dependency/licence and vulnerability review.
- Packet capture and privacy audit.
- Test malformed packets, replay, duplicates and resource exhaustion.
- Test expiry, journals, backup exclusion and notification privacy.
- Produce signed APK and installation/recovery documentation.

**Exit:** release checklist approved; do not claim universal connectivity or audited security without evidence.

## Mandatory acceptance tests
1. Pair exactly two devices.
2. Reject a third or untrusted device.
3. Reject QR replay and expired invitations.
4. Establish authenticated direct session.
5. Inspect packet captures for encrypted payloads.
6. Reject malformed, replayed and out-of-window packets.
7. Deliver a message exactly once at UI level.
8. Queue messages locally while peer offline and reconcile after reconnect.
9. Expire text, media and metadata per policy.
10. Remove expired attachments and temporary copies.
11. Complete a 30-minute voice test on a supported path.
12. Test Wi-Fi loss, mobile handoff and address change.
13. Verify foreground service and notification behaviour.
14. Verify no app backend, signalling, STUN/TURN or relay traffic.
15. Verify no message/key content in logs, notification previews or cloud backup.

## Scope discipline
Do not implement voice before direct encrypted connection proof succeeds. Do not add server fallback to make a test pass. If a network combination cannot connect directly, mark it unsupported or require user-assisted endpoint configuration.
