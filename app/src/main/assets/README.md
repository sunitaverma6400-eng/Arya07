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
