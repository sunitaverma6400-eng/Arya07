# Step 15 — OpenRouter model capability routing

- Live OpenRouter catalog metadata is preserved on Android: context length, modalities, supported parameters, tags, tool support, and reasoning support.
- ModelRouter prefers actual capability metadata over description keywords.
- Known non-text OpenRouter models are strongly de-prioritized for normal chat.
- Tool-capable models receive a routing bonus for coding/agentic tasks.
- Reasoning-capable models receive a routing bonus for reasoning tasks.
- Large context length is used directly for long-document routing.
- Static model lists remain as a backward-compatible fallback when live discovery is unavailable.
