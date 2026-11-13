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
- [x] Add QR PNG export and gallery/document-picker import for remote invitation/response exchange.
- [x] Add reciprocal invitation/response review and fingerprint-confirmation screens.
- [x] Integrate encrypted single-peer storage and invitation consumption.
- [x] Persist the active invitation and responder's signed response QR encrypted so remote pairing can resume after app restart.
- [x] Restore an existing pinned peer at startup without asking for re-pairing; remove routine in-app unpair/re-pair controls.
- [x] Add JVM protocol tests for invitation/response round-trip, signature tampering, expiry, wrong fingerprint and invitation binding.
- [ ] Add store replay tests, malformed-payload fuzzing and third-device rejection tests.
- [ ] Complete two-physical-device acceptance tests, including app restart during pairing.

**Exit:** two physical devices pair and mutually authenticate; an unrelated third device is rejected. UI and storage integration are implemented, but this exit criterion remains pending until automated and physical-device tests pass.

## Milestone 2: Direct encrypted connectivity proof
- [x] Implement a direct TCP listener and manual host/port connection (port 47831).
- [x] Authenticate both peers against their QR-pinned public identity keys.
- [x] Use ephemeral P-256 ECDH, signed handshake transcript, HKDF-SHA256 and directional AES-256-GCM keys.
- [x] Add bounded encrypted frames, per-direction counters and replay/out-of-order rejection.
- [x] Add unit tests for peer authentication and encrypted bidirectional frames.
- [ ] Replace or independently review the initial custom handshake; evaluate a maintained Noise implementation before production.
- [x] Enumerate local interface IPv4/IPv6 candidates without an external IP lookup service.
- [x] Add Android NSD advertisement and discovery for same-LAN peers.
- [ ] Add persisted manual internet endpoint sharing and endpoint change workflow.
- [ ] Validate global IPv6 candidates and configured public IPv4 endpoints on physical networks.
- [ ] Evaluate direct UDP and best-effort NAT traversal without any rendezvous/relay.
- [ ] Test IPv6, configured IPv4, Wi-Fi and mobile combinations on two physical devices.
- [ ] Test NSD peer discovery and endpoint loss/recovery on two physical devices.

**Exit:** two physical devices establish authenticated encrypted connectivity on documented supported networks, with packet capture evidence and no server/relay traffic. This exit criterion remains pending physical-device testing.

## Milestone 3: Reliable messaging
- [x] Add direct encrypted text frames.
- [x] Add encrypted local message history, bounded to 2,000 entries.
- [ ] Implement versioned message envelope, message IDs and sequence tracking.
- [ ] Add delivery/read acknowledgements.
- [ ] Add bounded sender-side offline queue and retry.
- [ ] Add duplicate suppression and reconnect reconciliation.
- [ ] Implement message expiry and cleanup.

**Exit:** text delivery, acknowledgement, offline queueing, reconnection and expiry tests pass.

## Milestone 4: Voice calling
- [x] Add AudioRecord/AudioTrack capture and playback.
- [x] Integrate Opus encode/decode using 16 kHz mono 20 ms speech frames.
- [x] Add bounded audio playout queue to cap stale buffered frames.
- [x] Add encrypted call request, accept, reject and end control frames.
- [x] Add Android audio focus and speaker/earpiece switching.
- [x] Add foreground peer service ownership of sockets, receive loop and audio session.
- [x] Promote active calls to the Android microphone foreground-service type.
- [ ] Move voice to a low-latency datagram channel with a proper jitter buffer, packet-loss concealment and adaptive bitrate.
- [ ] Add Bluetooth route selection, mute and call quality diagnostics.

**Exit:** stable two-way calls on supported paths; measure latency, packet loss and battery use on real devices.

## Milestone 5: Android lifecycle and UX
- [x] Implement foreground service and persistent notification.
- [x] Keep the peer listener, encrypted channel and active call audio independent of the Activity.
- [ ] Add user-controlled always-connected mode.
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
11. Complete a 30-minute Opus voice test on a supported path, including app backgrounding.
12. Test voice packet delay, TCP head-of-line blocking, playout queue drops and audio focus changes.
12. Test Wi-Fi loss, mobile handoff and address change.
13. Verify foreground service and notification behaviour.
14. Verify no app backend, signalling, STUN/TURN or relay traffic.
15. Verify no message/key content in logs, notification previews or cloud backup.

## Scope discipline
Do not implement voice before direct encrypted connection proof succeeds. Do not add server fallback to make a test pass. If a network combination cannot connect directly, mark it unsupported or require user-assisted endpoint configuration.
