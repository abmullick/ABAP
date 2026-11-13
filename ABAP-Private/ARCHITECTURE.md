# ABAP Private — Technical Architecture

## Product constraints
- Exactly two mutually paired Android devices.
- Strictly serverless: no backend, signalling server, STUN/TURN, relay, Firebase, cloud message store or central database.
- QR-based mutual pairing; direct encrypted networking only.
- User-enabled foreground service; temporary message retention.
- Offline sender queue only; no delivery while recipient is unreachable.

## Current prototype implementation

The current branch contains a functional implementation slice, not the complete target architecture:
- Direct TCP listener on port 47831 and manual host/port outgoing connection.
- Mutual authentication using the two QR-pinned P-256 identity keys, ephemeral P-256 ECDH, a signed transcript, HKDF-SHA256 and directional AES-256-GCM keys.
- Length-bounded encrypted frames with per-direction sequence counters.
- Text chat over the encrypted TCP stream and encrypted local message history (up to 2,000 messages).
- Basic call request/accept/reject/end controls and 16 kHz mono Opus speech frames over the encrypted stream.
- Bounded 12-packet audio playout queue, Android audio focus, and speaker/earpiece switching.
- A started `PeerConnectionService` foreground service owns the listener, secure channel, receive loop and audio session.
- Local interface address enumeration and Android NSD advertisement/discovery for same-LAN peers.

The transport's custom handshake is an initial prototype and has not been independently audited. It must receive cryptographic review or be replaced with a maintained Noise implementation before production use. Opus currently runs over TCP, so head-of-line blocking remains; the bounded playout queue is not a network jitter buffer. NSD only discovers peers on the same LAN. There is no internet rendezvous, UDP/NAT traversal, external endpoint discovery, offline delivery queue, message receipt protocol, adaptive bitrate, or packet-loss concealment.

## Proposed Android stack
- Kotlin, Jetpack Compose, Material 3, MVVM and clean module boundaries.
- Kotlin Coroutines/Flow and Hilt.
- UDP sockets using Android/JVM networking APIs, ConnectivityManager and NetworkCallback.
- Noise Protocol Framework through a vetted maintained implementation; candidate suite `Noise_XX_25519_ChaChaPoly_SHA256`, subject to library compatibility and security review.
- Android Keystore-backed identity/key protection.
- AudioRecord/AudioTrack and Opus (current prototype uses the pure-Java Concentus codec; validate performance and licence before release).
- Room with SQLCipher-compatible encrypted SQLite.
- CameraX/ML Kit for QR scanning; a maintained QR encoder.
- JUnit, coroutine tests and AndroidX instrumentation tests.

Pin dependency versions in a Gradle version catalog after checking maintenance, Android compatibility, security history and licences. Do not implement cryptographic primitives or codecs from scratch.

## Suggested Gradle modules

```text
ABAP-Private/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── app/
├── core/
│   ├── common/
│   ├── identity/
│   ├── crypto/
│   ├── protocol/
│   ├── network/
│   ├── storage/
│   └── security/
├── feature/
│   ├── pairing/
│   ├── chat/
│   ├── calling/
│   ├── connection/
│   ├── diagnostics/
│   └── settings/
├── service/
│   ├── foreground/
│   └── audio/
└── test-support/
    ├── protocol-testkit/
    └── network-simulator/
```

| Module | Responsibility |
|---|---|
| app | Composition, navigation and dependency wiring |
| core:common | Shared types and error model |
| core:identity | Device identity lifecycle |
| core:crypto | Noise handshake, session keys and AEAD integration |
| core:protocol | Versioned packet schema, framing and validation |
| core:network | UDP sockets, IPv4/IPv6 candidates and direct connectivity |
| core:storage | Encrypted database, attachment store and expiry |
| core:security | Peer pinning, trust state and replay policy |
| feature:pairing | QR pairing and mutual verification |
| feature:chat | Messaging UI and delivery state |
| feature:calling | Call UI and state |
| feature:connection | Connection state and recovery controls |
| feature:diagnostics | Network capability and failure explanation |
| feature:settings | Retention, privacy and foreground preferences |
| service:foreground | User-visible persistent connection service |
| service:audio | Audio capture, playback and routing |

## Device identity and remote QR pairing

Each device creates a random device ID and long-term identity key. Protect private material with Android Keystore-backed storage. Keep identity keys separate from ephemeral handshake keys and traffic keys.

Pairing is a one-time trust operation and must work remotely. The invitation and response are QR images that users can save and exchange through an independent channel such as email or an existing messaging app. Each recipient imports the image from the Android document picker; camera scanning remains an optional convenience, not a physical-presence requirement.

1. Phone A creates a short-lived, single-use signed invitation and persists its encoded payload in encrypted local pairing state.
2. Phone A exports the QR as a PNG and sends it through an independent channel.
3. Phone B imports the image, decodes and validates its structure, signature and expiry.
4. Phone B creates a response bound to A's challenge, pins A's identity after fingerprint confirmation, and persists the signed response QR encrypted.
5. Phone B exports and sends the response image to A.
6. Phone A imports the response, verifies the signature and invitation binding, confirms B's fingerprint, and pins B.
7. Both devices retain the peer trust record across app restarts and network changes. Re-pairing is not part of ordinary reconnection.

Use a versioned CBOR (or equally strict binary) schema containing protocol version, device ID, public identity key, nonce, expiry, capabilities and signature. Bind the challenge and all relevant transcript fields into authentication. QR data must never include private keys or reusable secrets.

The current invitation/response format and encrypted local peer store implement identity trust, not endpoint rendezvous. QR images do not themselves reveal a reachable internet address or notify a peer about later IP changes. The direct-connection milestone must separately define endpoint discovery/recovery without introducing a server, signalling service, STUN/TURN or relay.

## Direct network connectivity

**Current prototype:** a TCP listener binds to the wildcard local address on port 47831. The user can manually enter the other phone's reachable IP address or hostname. Android NSD advertises and discovers listeners on the same LAN/Wi-Fi; interface enumeration displays local and global-IPv6 candidates without querying an external IP service. The channel is mutually authenticated before any chat/call frame is accepted.

Same-LAN NSD discovery works only where multicast DNS is allowed. Internet connection still works only where a direct TCP route already exists: globally reachable IPv6 with permissive firewall rules, or public IPv4 with appropriate router port forwarding. Address enumeration is not proof of internet reachability. This does not solve NAT traversal, carrier-grade NAT or endpoint rendezvous.

**Planned connectivity work — no relay fallback:**

Priority:
1. Global IPv6 direct connection, where both endpoints are globally reachable and firewall rules allow traffic.
2. Explicitly configured public IPv4 endpoint, normally requiring a stable public address and router port forwarding.
3. Best-effort UDP hole punching when endpoint observations and coordinated attempts are available.
4. Otherwise report offline/unreachable. No relay fallback.

CGNAT, symmetric NAT, restrictive firewalls and network address changes can make direct connectivity impossible. Do not promise universal internet reachability. Distinguish loopback, link-local, private and global addresses. Re-evaluate candidates when Android reports network changes.

Connection states: STOPPED, STARTING, DISCOVERING, CONNECTING, AUTHENTICATING, CONNECTED, IDLE_UNREACHABLE, RECONNECTING, FAILED. A peer is CONNECTED only after a cryptographic handshake verifies the pinned identity.

## Secure transport

**Current prototype:** ephemeral P-256 ECDH, ECDSA signatures from the QR-pinned device identities, transcript binding, HKDF-SHA256 and directional AES-256-GCM. Each direction uses an independent key and monotonically increasing frame counter; frame type and counter are authenticated as AAD. The listener bounds handshake duration and concurrent handshakes. This is a custom protocol prototype and is not independently audited.

**Production direction:** integrate a vetted Noise implementation. `Noise_XX_25519_ChaChaPoly_SHA256` is an initial candidate only; confirm library support and bind paired long-term identity authentication into the handshake transcript. Derive independent directional traffic keys. Enforce nonce uniqueness, replay rejection, bounded packet sizes, sequence windows, authenticated headers and rekeying according to the chosen implementation. Erase transient session secrets at teardown.

Versioned logical channels:

| Channel | Content | Delivery |
|---|---|---|
| Control | Handshake completion, ping, close | Reliable |
| Chat | Messages and receipts | Reliable |
| Sync | Message reconciliation | Reliable |
| Voice | Opus frames | Low latency/loss tolerant |
| Media | Encrypted attachment chunks | Reliable |

A UDP reliability layer requires acknowledgements, bounded retransmission, duplicate suppression, congestion control and path-MTU handling. Do not create an unbounded home-grown retransmission loop. Evaluate a mature QUIC implementation only if it fits the strict direct topology and identity model; QUIC does not solve rendezvous/NAT discovery.

## Messaging

**Current prototype:** UTF-8 text is sent in encrypted transport frames. The last 2,000 local messages are stored inside the AES-GCM encrypted pairing-state record. There are no delivery receipts, sender-side offline queue, attachment transfer, expiry or duplicate reconciliation yet.

**Target protocol:** message envelope: UUID, conversation ID, sender device ID, sender sequence, type, creation/expiry metadata, schema version and encrypted content. States: PENDING, SENDING, DELIVERED, READ, EXPIRED, FAILED. Use idempotent receive handling and bounded retries. When peer is offline, queue locally on sender; there is no server queue, so delivery waits until direct connectivity returns.

## Voice

**Current prototype:** 16 kHz mono PCM16 is captured in 20 ms blocks, encoded/decoded with Concentus Opus, and sent inside encrypted TCP frames. A bounded 12-packet (240 ms) playout queue drops stale frames rather than allowing unbounded latency. Call request, accept, reject and end controls are encrypted. Audio focus and speaker/earpiece routing are managed. The same TCP stream carries text and audio, so head-of-line blocking can affect call quality; this is not a production real-time media transport.

**Target pipeline:** AudioRecord → audio processing → Opus encode → encrypted low-latency voice channel → decrypt → Opus decode → adaptive jitter buffer → AudioTrack.

Initial benchmark settings: 48 kHz mono, 20 ms frames, 24–32 kbps starting bitrate with adaptive adjustment, packet-loss concealment and echo/noise processing where supported. These are tuning targets, not guarantees. Handle audio focus, speaker/earpiece and Bluetooth routing. Keep voice independent from bulk file transfer. Call recording disabled by default.

## Foreground service

The user-started `PeerConnectionService` displays a persistent notification and owns the TCP listener, peer channel, encrypted receive loop and audio session independently of the Activity. It declares connected-device and microphone foreground-service types, requests microphone foreground mode when a call is initiated/accepted, and manages audio focus. This supports ordinary backgrounding during an established call, subject to OS restrictions. It does not bypass force-stop, Doze, device shutdown, carrier NAT or background microphone-start restrictions. There is no push/rendezvous path to guarantee an incoming-call alert while the app is suspended. A foreground service does not prevent force-stop, shutdown, carrier NAT or network unreachability. Without push/rendezvous infrastructure, incoming calls cannot be guaranteed when app/peer is unavailable.

## Storage and retention

Use SQLCipher-backed Room and encrypted attachment files. Initial configurable defaults: text/images/documents 24 hours, call metadata 24 hours, call recordings disabled. Enforce expiry on read and during cleanup. Clean attachments, thumbnails, temporary copies and relevant caches. Run cleanup at startup and periodically while active; WorkManager is supplementary, not an exact-time deletion guarantee. Disable Android cloud backup and redact notifications/logs. Consider SQLite WAL/journal files in the threat model. Deletion cannot remove screenshots or copies exported by the other device.

## Permissions

Request only those required by implemented features: INTERNET, ACCESS_NETWORK_STATE, applicable FOREGROUND_SERVICE and service-type permissions, RECORD_AUDIO, CAMERA for QR scanning, POST_NOTIFICATIONS, and WAKE_LOCK only if justified. Verify exact declarations against target SDK. No contacts, SMS, call logs, phone state, location or broad storage access.

## Not in scope

Group chat, more than two identities, account registration, public directory, server-side storage, cloud push, TURN/media relay, cloud backup or guaranteed reachability on every carrier.
