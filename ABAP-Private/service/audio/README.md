# service/audio

`AudioCallSession` implements the current speech path:
- Captures mono PCM16 at 16 kHz using `AudioRecord` with VOICE_COMMUNICATION source.
- Encodes 20 ms frames with the pure-Java Concentus Opus encoder.
- Sends Opus packets only inside the authenticated, encrypted peer channel.
- Decodes incoming Opus packets and plays PCM using streaming `AudioTrack`.
- Uses a bounded 12-frame playout queue (240 ms maximum queued speech); stale packets are dropped under load.
- Requests transient voice-call audio focus and switches Android into communication mode.
- Enables platform acoustic echo cancellation and noise suppression when available.
- Supports speaker/earpiece switching and restores the prior audio mode and speaker setting on close.

Important limitations:
- Voice packets still share the ordered TCP stream with messages and call control. TCP retransmission/head-of-line blocking can cause audible stalls.
- The bounded queue is not an adaptive jitter buffer; there is no packet-loss concealment, FEC or adaptive bitrate yet.
- Bluetooth route selection, mute controls, call-quality metrics and device-specific audio tuning remain pending.
- Real-device audio quality, latency, battery and background-call acceptance tests have not been performed.
