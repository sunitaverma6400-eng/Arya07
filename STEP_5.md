# Arya Step 5 — Provider/API Contract Audit

## Relay
- Added bounded provider-key rotation for normal chat requests.
- Added safer public upstream error messages (no raw provider response bodies).
- Added validation for image MIME types and media prompt sizes.
- Updated Gemini image generation default to `gemini-3.1-flash-image`, the current recommended native image model.
- Added key cooldown handling for ElevenLabs, Fish Audio, Whisper, image/video/music generation failures.
- Whisper default is now `whisper-large-v3-turbo`, configurable with `GROQ_WHISPER_MODEL`.
- Added bounds for Tavily/news search queries and media/TTS text.
- Added Step 5 relay contract tests.

## Android app
- Existing App -> Relay endpoint paths were cross-checked against the Step 5 relay routes.
- Existing Live/chat authentication headers remain compatible.
- No provider API keys were moved into the APK.

## Still intentionally deferred
- Video is still synchronous and will be converted to job/status polling in the next dedicated step.
- Full real-device Android build and live provider calls require an Android/Gradle + provider-key test environment.
