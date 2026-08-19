# Step 16 — Gemini normal chat/streaming + native function calling

This step adds a Gemini-specific provider adapter without changing Groq/OpenRouter behavior.

- Android sends the selected/relevant tool declarations to the relay for Gemini requests.
- Relay converts them to Gemini `function_declarations`.
- Non-streaming Gemini responses containing `functionCall` are normalized to Arya's existing `{tool,args}` contract.
- Gemini streaming `functionCall` parts are buffered and normalized as one complete tool JSON, so partial JSON is never shown in the chat UI.
- Tool declarations are capped at 20, each tool at 12 parameters.
- Gemini model metadata in the Android catalog marks current chat models as tool-capable.
- Gemini Live remains separate: it uses its own BidiGenerateContent function-calling protocol.

This is intentionally not a fake claim of device/API testing; real Gemini calls require a configured API key and Android/device runtime.
