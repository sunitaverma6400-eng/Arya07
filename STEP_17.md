# Step 17 — Gemini Live deep audit

- Wait for `setupComplete` before starting realtime microphone input.
- Gate realtime input while synchronous Gemini 3.1 Live function calls are pending.
- Execute local tools, then send `functionResponses` on the same Live WebSocket.
- Handle all model-turn parts in each server event, including audio and transcripts.
- Do not enable proactive audio or affective-dialog configuration for Gemini 3.1 Live.
- Keep Live tools limited to the existing safe allowlist.
