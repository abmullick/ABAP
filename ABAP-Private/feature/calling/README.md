# feature/calling

The application currently supports basic peer call signalling over the encrypted direct channel:
- Call request, accept, reject and end control frames.
- Runtime microphone permission.
- Audio session starts after the peer accepts.

The audio implementation is a 16 kHz mono PCM prototype over the same TCP stream. It is not yet Opus, does not have a jitter buffer or adaptive bitrate, and is not suitable to claim production voice quality. Calls require the app to remain active; foreground-service ownership, audio focus and Bluetooth routing remain to be implemented.
