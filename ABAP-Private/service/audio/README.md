# service/audio

`AudioCallSession` provides an initial voice path:
- Captures mono PCM16 at 16 kHz using `AudioRecord` with VOICE_COMMUNICATION source.
- Streams 20 ms audio frames through the already authenticated and encrypted peer channel.
- Plays received PCM using streaming `AudioTrack`.
- Enables platform acoustic echo cancellation and noise suppression when available.

This is a prototype. Opus encoding, jitter buffering, adaptive bitrate, explicit audio-focus management, routing controls and foreground-service lifecycle are not yet implemented.
