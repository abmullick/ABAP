# feature/connection

The current app provides:
- A TCP listener bound to all local interfaces on port 47831.
- A manual peer IP/hostname field for outgoing connections.
- Mutual authentication against the locally pinned peer key.
- Visible connection status and explicit failure reporting.

Automatic network discovery, endpoint persistence/sharing, network-change recovery, reconnection and UDP/NAT traversal remain pending. The connection does not silently fall back to a server or relay.
