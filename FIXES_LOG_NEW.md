# Hardening pass — 13 Aug 2026

## Fixed

1. **Voice/Live routing policy bug** — `forceGeminiOnly=true` now wins before coding intent
   classification. A voice sentence containing words like `code` or `bug` can no longer
   accidentally jump to OpenRouter.
2. **Vision model drift** — `VisionRelay` no longer hardcodes retired `gemini-2.0-flash`;
   it uses the current first Gemini catalog entry with a safe `gemini-flash-latest` fallback.
3. **Relay key exhaustion** — provider keys receiving 401/403/429/5xx responses are temporarily
   cooled down so round-robin selection skips known-bad keys.
4. **Relay abuse/oversized requests** — chat endpoints now enforce per-client request limits,
   prompt/system/history limits and an overall request body limit.
5. **Diagnostics** — relay responses include a short request ID and `/healthz` reports key
   cooldown counts and configured limits.
6. **Build/CI** — added a Gradle bootstrap script and a GitHub Actions workflow pinned to
   JDK 21 + Gradle 8.7. The archive's original wrapper properties are preserved.
7. **Regression tests** — added relay hardening tests.

## Still requires real-device/provider verification

- Gemini Live WebSocket stability on the actual Render deployment/device.
- Gemini Live native function calling + Android `AryaToolRegistry` bridge. The current Live
  protocol remains intentionally voice/audio-only until that bridge is tested against the
  live API, because an incorrect tool schema can break the entire Live session.
- Video generation is still provider-operation/poll based and should be migrated to a durable
  job queue if long-running production reliability is required.
- A real Android/Gradle build must be run in an environment with Android SDK + network access;
  this analysis environment does not contain the Gradle distribution or Android SDK.

## Step 1 complete — Gradle bootstrap

The Android project now includes a real `gradle/wrapper/gradle-wrapper.jar` and launcher scripts. The bundled wrapper reads `gradle-wrapper.properties`, downloads the pinned Gradle 8.7 distribution when it is not cached, and delegates to Gradle. This environment has no outbound network access, so the actual Gradle distribution could not be downloaded here; on a normal development machine/CI with internet access, `./gradlew --version` will bootstrap Gradle 8.7.

This is a self-contained bootstrap wrapper rather than the stock Gradle-generated wrapper JAR.

## Step 5 — Provider/API contract audit
- Relay provider pools and Android endpoint paths cross-checked.
- Gemini image generation default moved to `gemini-3.1-flash-image`.
- Added media/TTS/search input bounds and provider key cooldown handling.
- Added bounded provider-key rotation for normal chat requests.
