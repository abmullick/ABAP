# feature/chat

The application currently exposes a basic one-to-one chat surface over the authenticated direct peer channel:
- UTF-8 text messages are sent only inside AES-GCM protected frames.
- Local conversation history is persisted in the encrypted pairing-state store, bounded to the most recent 2,000 messages.

Not yet implemented: delivery/read receipts, offline queue, retry/reconciliation, attachment transfer, message expiry and multi-device sync. Messages cannot be delivered while the peer is unreachable because there is no server queue.
