# Arya Step 7 — Music Client

- `/v1/musicgen` client updated for the relay's Lyria 3 Interactions API implementation.
- Sends the installation `X-Client-Id` for relay rate limiting and diagnostics.
- Increased music read timeout to 180 seconds for generation latency.
- Validates that the relay response is audio and rejects oversized audio responses.
- Keeps the existing FileProvider/content URI playback contract.

Actual Android compilation/device playback could not be run in this environment because an Android SDK/Gradle distribution was not available locally.
