# Step 9 — Memory + RAG

## Fixed
- RAG index now persists deterministic metadata and rebuilds from source documents.
- Unicode-aware tokenisation supports Hindi/Devanagari and English/Hinglish terms.
- Retrieval uses TF-IDF, phrase matches, exact-query bonus and per-document diversity.
- Retrieved chunks include their source document name.
- Chunk count is bounded to avoid unbounded in-memory growth.
- `summarizeOldTurns()` no longer mutates the source corpus and then loses the change on restart.
- ChatViewModel now retrieves relevant local document context before online chat and wraps it in `[RAG_CONTEXT]` markers.
- Relay validates RAG context size so a local document index cannot create an oversized provider request.
- MemoryStore now migrates the old key/value format to timestamped structured records while preserving compatibility.
- Added `search_memories` tool for relevant long-term memory retrieval.

## Privacy design
Documents and saved memories remain on-device. The relay only receives retrieved context for a turn when the app includes it in the chat system prompt; it does not store a document index or memory database.

## Verification
- Relay `app.py`: Python bytecode compilation passed.
- Android Gradle task could not be completed in this environment because Gradle dependency distribution/network access is unavailable; no false build-pass claim is made.
