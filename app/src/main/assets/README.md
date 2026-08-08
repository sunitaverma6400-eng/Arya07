# assets/

Drop your trained "Hey Arya" Porcupine keyword file here to enable Option A
(the battery-efficient dedicated wake-word model).

## Steps

1. Go to https://console.picovoice.ai and sign in (free account).
2. Copy your **AccessKey** from the console and save it in the Arya app:
   Settings → API Keys → "Hey Arya wake word" card.
3. In the console, go to **Porcupine → Create Wake Word**, type `Hey Arya`,
   choose the **Android** platform, and train it (takes ~a minute).
4. Download the resulting `.ppn` file.
5. Rename it to exactly:

   ```
   hey-arya_android.ppn
   ```

6. Place it in this folder (`app/src/main/assets/`), replacing nothing else.
7. Rebuild the app.

Once both the AccessKey (step 2) and this file (steps 3-6) are present,
`WakeWordService` automatically switches from the built-in VAD-based
listening (Option B) to Porcupine (Option A) on the next service start —
no other code changes needed.

If either piece is missing, the app keeps working exactly as before, using
Option B, so this step is entirely optional.

## avatar/model.vrm — Arya's 3D face (video call / live conversation)

`avatar/index.html` + `avatar/avatar.js` render a VRM humanoid avatar (via
three.js + three-vrm, in a WebView) for the video-call screen. They need an
actual model file that isn't included, since it's licensed character art, not
something code can generate:

1. Get a VRM file — either:
   - Make your own free at https://vroid.com/en/studio (VRoid Studio), or
   - Download one from https://hub.vroid.com (VRoid Hub) — **check that
     specific model's license lets you redistribute it inside an app** before
     shipping it; licenses vary per model.
2. Rename it to exactly `model.vrm` and place it here:
   ```
   app/src/main/assets/avatar/model.vrm
   ```
3. Rebuild. `VrmAvatarView` loads it automatically; `LiveConversationScreen`
   falls back to the existing hand-drawn Canvas face if this file is missing
   or fails to load (see `AndroidBridge.onLoadError` in avatar.js).

The CDN `<script>` tags in `avatar/index.html` need internet on first load
(cached by WebView after). To make this fully offline, download those three
JS files and point the `src`s at a local `avatar/lib/` folder instead.

