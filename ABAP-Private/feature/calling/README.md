# feature/calling

Current implementation:
- Encrypted call request, accept, reject and end controls.
- Opus-compressed 16 kHz mono speech, 20 ms frames.
- Bounded audio playout queue.
- Foreground service owns the voice session independently of the Activity.
- Microphone foreground-service type is enabled for calls; audio focus and speaker/earpiece switching are supported.

Not yet implemented: UDP media transport, adaptive jitter buffering, packet-loss concealment/FEC, adaptive bitrate, mute, explicit Bluetooth route selection, call quality metrics and incoming-call push/notification wake-up. TCP head-of-line blocking remains. Incoming calls cannot be guaranteed while the app is suspended or the peer is unreachable.
