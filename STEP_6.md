# Step 6 — Android video job client

- `VideoGenTools.generate()` now calls POST `/v1/videogen` and receives a `job_id`.
- It polls GET `/v1/videogen/jobs/<job_id>` every 5 seconds for up to 12 minutes.
- When ready, it downloads the MP4 from the relay's job download endpoint.
- Every request sends the existing per-install `X-Client-Id`, matching relay job ownership checks.
- Individual HTTP timeouts are short; the app no longer waits on one multi-minute socket.
