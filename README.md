# Arya — AI ka Arya

Arya ek on-device AI assistant hai (Google AI Edge Gallery jaisa idea) —
Jetpack Compose UI, MediaPipe LiteRT-based on-device LLM inference, aur ~110
device/data tools (weather se lekar streaming/reminders/RAG tak).

**History note:** ye project shuru me do independently-built Arya codebases
ka merge tha — ek Jetpack Compose build aur ek classic ViewBinding/XML build.
Dono ko kuch waqt saath rakha gaya tha, lekin us duplication ki wajah se hi
ek real bug bhi aaya (API Keys screen sirf widget se, aur wo bhi sirf pehli
session se pehle, reachable thi). Isliye **Phase 1 refactor** (see
`FIXES_LOG.md` #8) me classic build poori tarah hata di gayi — ab sirf ek hi
UI (Compose) aur ek hi on-device inference path (`inference/`) hai. Classic
build jo bhi karta tha (API Keys, Chat History/Sessions) uska Compose
replacement bana diya gaya, ab NavHost ka actual route hai, kisi widget/entry
point pe depend nahi karta.

## Entry point

- **Launcher activity** = `.MainActivity` (Compose) — poori app isi ek
  Activity ke andar NavHost routes ke through chalti hai.
- Home-screen **widget** (`QuickChatWidgetProvider`) `.MainActivity` open
  karta hai — jo bhi session widget se banta hai wo Chat History me bhi
  dikhega.
- Menu → **Use Cases** se 7-tile gallery (Ask Image, Audio Scribe, AI Chat,
  Agent Skills, Prompt Lab, Tiny Garden, Mobile Actions) khulti hai.

## Project structure

```
app/src/main/java/com/arya/ai/
├── MainActivity.kt              (Compose nav host — launcher, saara NavHost yahi hai)
├── AryaApp.kt                   (Application: theme + periodic sync/worker bootstrap)
├── ui/                          (Compose screens: Home/Gallery/Chat/Sessions/ApiKeys/Persona/Stats/... )
│   └── theme/                   (Compose Color/Type/Theme)
├── data/                        (ModelInfo/ModelRepository/CuratedModels/StatsRepository/... + Room: AppDatabase/ChatDao/entities)
├── viewmodel/                   (Compose ViewModels: Gallery, Chat, AgentSkills, ...)
├── inference/                   (InferenceEngine, ToolCallParser — the one on-device inference path)
├── network/                     (HfDownloader, ModelDownloadWorker)
├── util/                        (PreferencesManager, ModelDownloader, VoiceHelper, SimpleRagHelper, ExportHelper, ApiKeyManager, OnlineChatHelper)
├── worker/                      (CurrentInfoWorker/Scheduler, UpdateCheckWorker/Scheduler, ReminderTools, MorningBriefingWorker)
├── widget/                       (QuickChatWidgetProvider)
└── tools/                        (Arya's ~110 device/data tools — see "Arya's device & data tools" section below)
```

## Arya's device & data tools (new)

`com.arya.ai.tools/` is a ~50-tool set ported from an earlier Python/Termux
assistant project's tool library, reimplemented **natively for Android — no
Termux, no Python backend, no server**. Everything that used to shell out to
a `termux-*` CLI command now calls a plain Android API or opens a standard
Intent instead; everything that was a free public API call now goes straight
from OkHttp on the phone to that API.

| File | Tools | What it does |
|---|---|---|
| `UtilityTools.kt` | calculate, convert_units, generate_qr, generate_password, text_analyzer, get_random_quote, system_info | Local, no network |
| `InfoApiTools.kt` | get_weather, get_crypto_price, convert_currency, get_country_info, get_ip_info, get_dictionary, translate_text, get_wikipedia_summary, get_sunrise_sunset, get_public_holidays, get_spacex_launches, NASA (apod/iss/asteroids/mars), ask_wolfram | Free public APIs (Open-Meteo, CoinGecko, restcountries.com, ipapi.co, dictionaryapi.dev, MyMemory, Wikipedia, sunrise-sunset.org, date.nager.at, spacexdata, NASA) |
| `WebTools.kt` | web_search, scrape_webpage, smart_search | DuckDuckGo HTML scrape + Jsoup, run straight from the phone |
| `DeviceExtraTools.kt` | vibrate, get_battery_status, send_notification, set_alarm, make_call, send_sms, open_app, get_location | Native Android APIs / standard Intents — see safety note below |
| `ExpandedDeviceTools.kt` (new) | find_contact_number, call_contact_by_name, read_clipboard, write_clipboard, create_calendar_event | Contacts (`READ_CONTACTS`), Clipboard API, Calendar `ACTION_INSERT` intent |
| `MemoryStore.kt` | remember, recall, list_memories, forget, add_todo, list_todos, complete_todo, delete_todo | SharedPreferences JSON — not worth a Room table for this small a store |
| `PersonaStore.kt` | activate_persona, deactivate_persona, get_current_persona, list_saved_personas, switch_to_saved_persona | SharedPreferences JSON |
| `AryaToolRegistry.kt` | (dispatch logic) | Single `ALL_TOOLS` list + `execute()` dispatcher, wired into the **Agent Skills** screen |

**Not ported:** the earlier project's self-modifying-code module has no
Android equivalent — an installed APK can't rewrite its own compiled code at
runtime, so that one just doesn't exist here.

**Safety note on device tools:** `make_call` opens the dialer pre-filled
instead of calling directly, and `send_sms` opens the messaging app
pre-filled instead of sending directly — same as `DeviceActions.kt`'s
existing stance, this avoids requesting `CALL_PHONE`/`SEND_SMS` (dangerous
permissions) for a from-scratch sample app. `get_location` needs
`ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` granted at runtime (the
manifest declares them, but Android still needs the in-app runtime prompt —
not wired up yet, see caveats below).

**New optional API keys:** the API Keys screen's `ApiProvider` enum now also
has `NASA` and `WOLFRAM` — both optional. NASA tools work out of the box on
the public rate-limited `DEMO_KEY`; add a free personal key to raise the
limit. `ask_wolfram` needs a free Wolfram Alpha AppID or it just says so.

## "Hey Arya" background wake word (new)

`service/WakeWordService.kt` + `service/BootReceiver.kt` — toggle it on from
Settings. Once on, saying **"Hello Arya"**, **"Hi Arya"**, **"Hii Arya"**, or
**"Hey Arya"** (any one of them, case-insensitive) wakes it up even if the
app's UI isn't open — it replies "Haan, bolo", listens for your command, runs
it exactly like the Agent Skills screen does (tool-call system prompt → the
already-loaded on-device model → [`AryaToolRegistry`](#aryas-device--data-tools-new)
if it's a tool call), and speaks the answer back with TextToSpeech.

**How it stays "open" in the background:** it's an Android foreground
service (`android:foregroundServiceType="microphone"`), which is the
standard, allowed way to keep a mic-using component alive after the user
leaves the app — it shows a persistent notification (Android requires this)
and restarts itself after a reboot if it was left on (`BootReceiver`).
`inference/AryaEngine.kt` is what makes this useful rather than just
listening: it turns `InferenceEngine` into a process-wide singleton so the
model you already loaded in the UI is the same instance the service replies
with — no separate model load in the background.

**Honestly, how this differs from "OK Google":** Google's hotword runs on a
tiny dedicated always-on DSP chip, so it costs almost no battery and reacts
instantly. This uses Android's regular `SpeechRecognizer` in a restart loop
instead — no custom wake-word model or paid SDK needed, but:
- It's noticeably less battery-efficient than a real hotword chip.
- Most Android OEMs (Xiaomi/MIUI, OnePlus/OxygenOS, Vivo, etc. — very common
  in India) aggressively kill background services to save battery. Settings
  now shows a warning + button to open the app's battery-optimization
  setting; the user has to manually set it to "Unrestricted" or the
  wake-word service will get killed within minutes to hours depending on
  the phone.
- It needs a model **already loaded** to actually answer — if none is
  loaded it says so out loud and opens the app instead of trying to reply.
- Recognition quality depends on the phone's on-device speech engine
  (usually Google's); accented/noisy audio can miscatch the wake phrase or
  the command.

**Works with the phone locked/screen off** — it's a pure audio interaction
(no UI needed to hear you or speak back), and the service now holds a
partial CPU wake lock while running so Doze mode doesn't pause it just
because the screen is off. Two caveats: (1) this only holds the CPU awake,
not the screen — the phone stays locked/dark the whole time, which is the
point; (2) if a command needs to actually open the app (e.g. "no model
loaded"), that screen will sit behind the lock screen until the phone is
unlocked — the spoken response plays regardless, only the visual follow-up
waits.

## What's new in this pass (v1.2.0)

Ported over from the earlier Python/Termux Jarvis project (`jarvis_v11_final.zip`) — everything
below is real, wired-in code added to [`AryaToolRegistry`](#aryas-device--data-tools-new), but
**this pass has the same not-actually-compiled caveat as every pass before it** (see the honest
notes section below) — first build will likely need small fixes, especially around the new
Media3/WorkManager wiring.

**Follow-up fixes on top of the first v1.2.0 pass** (closing gaps a user comparison caught):
`reverse_geocode` now tries BigDataCloud (free, no key, no Nominatim usage-policy rate limits)
before falling back to Nominatim; `get_location` now appends a human-readable address via
`reverse_geocode` instead of returning raw lat/lon only; `search_place_osm` now includes a
Google Maps deep link per result, not just coordinates; a new `set_reminder`/`list_reminders`/
`cancel_reminder` tool set (`worker/ReminderTools.kt`) covers custom one-time/repeating
reminders via WorkManager — deliberately separate from `set_alarm`, which only opens the system
Clock app's alarm screen and can't repeat on an arbitrary interval; and `PersonalityStore`'s
mood/closeness prompt is now actually folded into the system prompt (+ interaction logged) in
the two screens that are real open-ended chat with Arya — Agent Skills and the "Hey Arya" wake
word service — not just written and left unused. (Mobile Actions and Tiny Garden keep their
fixed task-specific personas as-is; folding mood into a single-purpose command parser didn't
seem worth the prompt-noise.)

- **Streaming subsystem** (`tools/StreamTools.kt`, `player/StreamPlayerManager.kt`) — `search_radio`
  (Radio Browser API, gives a direct playable URL), `play_stream`/`pause_stream`/`resume_stream`/
  `stop_stream`/`stream_status` (Media3 ExoPlayer, has built-in HLS support), `find_and_play`,
  saved streams (`save_stream`/`list_saved_streams`/`delete_saved_stream`/`play_saved_stream`),
  and quality preference tools. **Scope limit, stated plainly**: `search_youtube`/`search_videos`
  can only search and return links — there's no Android equivalent of `yt-dlp` to resolve a
  YouTube page into a raw stream URL, so those don't auto-play. Playback is also in-process only
  (no foreground media service yet), so it stops when the app is backgrounded long enough for
  Android to reclaim it — a `MediaSessionService` would be the natural next step.
- **Image search + generation** (`tools/ImageTools.kt`) — `search_images` (Openverse, free/no-key),
  `generate_image` (Pollinations.ai, free/no-key text-to-image), `fetch_image_from_url`,
  `test_image_source`. The original's paid-key providers (Bing/Serper/SerpApi/HF FLUX) were left
  out in favor of the keyless ones, same stance as the rest of Arya's tools.
- **Saved sites + page-watch** (`tools/SiteTools.kt`, `worker/PageWatchWorker.kt`) — `save_site`/
  `list_saved_sites`/`delete_saved_site`/`play_saved_site` (opens in the browser via Intent —
  Arya has no in-app WebView), `get_page_media`, and `watch_page`/`stop_watch`/`list_page_watches`
  (WorkManager periodic content-hash diff check, ~30 min interval — Android enforces a 15-minute
  floor on periodic work, so finer polling isn't possible in the background without a foreground
  service).
- **News + morning briefing** (`tools/BriefingTools.kt`, `worker/MorningBriefingWorker.kt`,
  `worker/AryaScheduler.kt`) — `get_news` (Google News RSS, free/no-key), `morning_briefing`
  (weather + headlines + quote + time). `AryaScheduler` is a small general-purpose named-job
  wrapper around WorkManager periodic work (ported from `scheduler.py`'s job registry idea).
  `MorningBriefingWorker` runs hourly and fires once/day near a target hour — WorkManager can't
  pin an exact wall-clock time, so this is a "close enough" trade-off, stated plainly rather than
  silently swept under the rug.
- **Location** (`InfoApiTools.kt` additions) — `reverse_geocode` (on-device `Geocoder` first,
  Nominatim fallback), `search_place_osm` (Nominatim search, free/no-key, same provider the
  original used).
- **API key tools** (`tools/ApiKeyTools.kt`) — `list_api_keys` (masked values only) and
  `delete_api_key`, so the model itself can answer "which keys are configured" / remove one,
  on top of the existing manual API Keys screen.
- **Personality/mood system** (`tools/PersonalityStore.kt`) — closeness tracking, a lightweight
  recency/frequency mood heuristic, self-set "moments" to bring up later
  (`remember_moment`/`resolve_moment`/`get_pending_moments`), feedback logging, and an opt-in
  "surprise mode" for occasional proactive check-in notifications. This sits alongside
  `PersonaStore` (named roleplay characters) rather than replacing it — this is Arya's own
  baseline personality state, not a character she's playing.
- **RAG maintenance** (`util/SimpleRagHelper.kt` additions) — `getRagStats()` and
  `summarizeOldTurns()` (collapses old chunks past a keep-recent window into one condensed
  chunk, bounding index growth) — not yet wired into `AryaToolRegistry` as callable tools since
  `SimpleRagHelper` is owned per-screen (e.g. by a ViewModel), not a global singleton; call them
  directly wherever your RAG helper instance already lives, or add tool wrappers once you decide
  which screen should own that.

**Deliberately not ported in this pass**: `self_evolve.py` (still impossible — an installed APK
can't rewrite its own compiled code), `twilio_call.py` (irrelevant — Arya already runs on the
phone that would be answering), and the Flask/Render server-ops files (`keepalive.py`,
`memory_guard.py`, `logger.py`, `Procfile`, `render.yaml`, `phone_bridge.py`/`phone_agent.py`) —
none of these apply to a standalone APK.

## What's new in this pass (v1.1.0)

Added on top of the merged v1.0.0 codebase — all real, wired-in code, but a few
pieces need one-time manual setup from you before they do anything (called out below).
**Deliberately NOT attempted** in this pass: native MediaPipe function-calling (the API
surface is still unstable across `tasks-genai` versions — the JSON-prompt approach in
`ToolCallParser` stays), a real embeddings-based RAG rewrite (needs you to pick/ship an
actual on-device embedding model file — TF-IDF in `SimpleRagHelper.kt` is unchanged), and
consolidating the two inference paths (`model/` vs `inference/`) — too large/risky a
refactor to do blind without a real compiler in the loop; do this one deliberately, with
Android Studio open, when you're ready.

- **Always-current date/time** (`util/DateTimeContext.kt`) — on-device LLMs only know
  whatever date their training data stopped at (e.g. "2024"), so left alone they'll
  confidently state a stale date/year. Fixed by pulling the phone's actual system clock
  into every system prompt instead of letting the model guess: wired into
  `ToolCallParser.buildSystemPrompt` (covers all 4 Compose-build call sites — Agent
  Skills, Mobile Actions, Tiny Garden, "Hey Arya" wake word — from one place), into the
  plain Compose "AI Chat" screen (`ChatViewModel.send`), into both classic-build
  system-prompt sites in `ChatActivity` (offline model load + online mode), and into
  `CurrentInfoWorker`'s prompt (it now *tells* the online model the real date instead of
  *asking* it to guess). As long as the phone's own clock/timezone is correct, Arya's
  sense of "today" always is too.
- **Encrypted storage** (`util/SecurePrefs.kt`) — `ApiKeyManager`, `MemoryStore`, and
  `PersonaStore` now write through `EncryptedSharedPreferences` (Keystore-backed
  AES256-GCM/SIV) instead of plaintext SharedPreferences. Falls back to an unencrypted
  file only if the device's Keystore itself is broken, so a flaky OEM Keystore can't
  hard-crash the app. No action needed — existing plaintext prefs just won't be picked up
  automatically on upgrade (fresh install recommended, or you'll effectively start those
  three stores empty again).
- **New tools** (`tools/ExpandedDeviceTools.kt`, wired into `AryaToolRegistry`):
  `find_contact_number` / `call_contact_by_name` (needs `READ_CONTACTS`, now in the
  upfront runtime-permission batch in `MainActivity`), `read_clipboard` / `write_clipboard`,
  and `create_calendar_event` (opens the calendar app pre-filled via `ACTION_INSERT` —
  same "open a system UI, don't act silently" stance as `make_call`/`send_sms`, so no
  `WRITE_CALENDAR` permission needed).
- **Markdown export** — `ChatActivity`'s menu now has "Export chat (.md)" alongside the
  existing `.txt` export (`ExportHelper.exportAsMarkdown`).
- **"Hey Arya" barge-in** (`WakeWordService.kt`) — while Arya is speaking, a second VAD
  instance listens in the background; if it hears you start talking, it cuts TTS off and
  jumps straight into command listening instead of making you wait for her to finish.
  Heads-up: without echo cancellation this can occasionally false-trigger off Arya's own
  voice through the speaker (worse at high volume/speakerphone) — a false trigger just
  means it starts listening a little early, nothing breaks.
- **Release signing** — `app/build.gradle.kts` now has a `release` signing config that
  reads **only** from environment variables (`ARYA_KEYSTORE_PATH`,
  `ARYA_KEYSTORE_PASSWORD`, `ARYA_KEY_ALIAS`, `ARYA_KEY_PASSWORD`) — nothing is ever
  hardcoded or committed. **You need to do this yourself, once:**
  1. Generate a keystore locally: `keytool -genkeypair -v -keystore release.jks -alias arya -keyalg RSA -keysize 2048 -validity 10000`
  2. For CI signed releases: base64-encode it (`base64 -w0 release.jks`) and add four
     repo secrets — `ARYA_KEYSTORE_BASE64`, `ARYA_KEYSTORE_PASSWORD`, `ARYA_KEY_ALIAS`,
     `ARYA_KEY_PASSWORD` — in **Settings → Secrets and variables → Actions**.
  3. Without those secrets set, the new `release-signed` CI job is skipped entirely and
     your existing debug-APK build keeps working exactly as before — nothing breaks if
     you don't set this up.
- **Unit tests** (`app/src/test/java/.../AryaToolRegistryTest.kt`) — JVM-only sanity
  checks on `AryaToolRegistry.ALL_TOOLS` (no duplicate/blank names, snake_case
  convention, param completeness). Runs in CI now (`gradle testDebugUnitTest`) before
  the APK build. These catch copy-paste slips when adding tools, not full correctness —
  Kotlin doesn't check that every `ALL_TOOLS` entry has a matching `execute()` branch,
  a human/code-review still needs to catch that.

## Themes

- `Theme.Arya` — the only theme now (Compose owns all Material 3 theming in
  `ui/theme/`, applied via `AryaTheme` in `MainActivity`). The old
  `Theme.Arya.Classic` (classic build's separate blue palette) was removed
  along with the classic Activities in Phase 1.

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
`FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
(background/lock-screen streaming — Phase 2), `REQUEST_INSTALL_PACKAGES`
(in-app update installer — Phase 9), `CAMERA`, `RECORD_AUDIO`,
`READ_EXTERNAL_STORAGE` (maxSdk 32), `VIBRATE`, `ACCESS_FINE_LOCATION`,
`ACCESS_COARSE_LOCATION`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`,
`READ_CONTACTS` (for `find_contact_number`/`call_contact_by_name`).

## Dependencies

Compose BOM + Material3 + Navigation-Compose, AppCompat (kept only for the
dark-mode toggle — `AppCompatDelegate`), Room (+ `kotlin-kapt`), Markwon,
Glide, WorkManager, PDFBox-Android, OkHttp/Okio, Media3 ExoPlayer +
ExoPlayer-HLS + Session (streaming), security-crypto (encrypted prefs),
zxing (QR), jsoup (HTML parsing), Picovoice Porcupine (optional wake word).
`tasks-genai:0.10.24` + `tasks-vision:0.10.26.1` for on-device inference —
see `FIXES_LOG.md` #2 for why both are needed and why the versions don't
match each other (every MediaPipe module releases on its own version
number). The classic build's own dependencies (Material Components,
ConstraintLayout, RecyclerView, Preference, `viewBinding`) were removed in
Phase 1 along with the code that used them.

## Firebase setup (Community stats + optional chat sync)

`ui/CommunityScreen.kt` (Menu -> "Community") shows total installs and how many are online
right now; `util/FirebaseSync.kt` also optionally syncs chat content (only from users who tap
"Haan" on the first-launch consent dialog) so you can review real conversations to decide what
to improve. Chosen specifically because it's **just Firebase — no Python/Termux server, no
separate machine to keep running**; the phone talks straight to Google's hosted backend over
HTTPS, same as it already does for Groq/weather/etc.

**One-time setup, on your side** (this sandbox has no network to do this for you):
1. Go to the [Firebase Console](https://console.firebase.google.com), create a project (free
   Spark plan is enough for this).
2. Add an Android app to it with package name **`com.arya.ai`**.
3. Download the `google-services.json` it gives you and put it at **`app/google-services.json`**
   in this repo (same folder as `app/build.gradle.kts`) — the build picks it up automatically
   the moment it's there (see the `if (file("google-services.json").exists())` check in
   `app/build.gradle.kts`; without this file the app still builds and runs exactly as before,
   just without Community stats/chat sync).
4. In the Firebase Console, enable **Realtime Database** (not Firestore — this project uses the
   Realtime Database specifically for its `onDisconnect()` presence feature) and set rules like:
   ```json
   {
     "rules": {
       "meta": { ".read": true, ".write": true },
       "presence": { "$uid": { ".read": true, ".write": true } },
       "users": { "$uid": { ".read": false, ".write": true } },
       "chats": { "$uid": { ".read": false, ".write": true } }
     }
   }
   ```
   (Open write, no read of other users' data — good enough for a hobby app shared with friends;
   tighten further with Firebase App Check if this ever gets wider distribution.)
5. **Analytics** is enabled by default when you create the Firebase project — no extra setup.
   Firebase Console -> Analytics -> Demographics shows the "kaha kaha se log jude hain"
   (country/city) breakdown automatically, purely from the SDK being present.
6. **For GitHub Actions builds** to also have this: base64-encode your `google-services.json`
   (`base64 -w0 app/google-services.json`) and add it as a repo secret named
   `ARYA_GOOGLE_SERVICES_JSON_BASE64` (Settings -> Secrets and variables -> Actions) — same
   "skipped entirely if not set" pattern as the release-signing secrets above.

**Where to actually look at the data**: total-users/online-now numbers are in the app itself
(Menu -> Community); everything else — geography, and the actual chat content under
`/chats/{installId}` from users who consented — is in the Firebase Console directly (Realtime
Database tab to browse `/chats`, Analytics tab for demographics/engagement). No separate admin
app was built for this — the Console already is one.

## Building — GitHub Actions (Android Studio ki zaroorat nahi)

```bash
git init
git add .
git commit -m "Arya: merged build"
git branch -M main
git remote add origin https://github.com/<aapka-username>/arya.git
git push -u origin main
```

Push hote hi Actions tab me "Build Arya APK" workflow chalega, aur
~5-10 min me `arya-debug-apk` artifact download ke liye mil jaayega.
Ek `v*` tag push karoge (`git tag v1.0.0 && git push origin v1.0.0`) toh
seedha GitHub Release me signed-debug APK attach ho jaayega.

## Building — Android Studio (local)

1. Poora folder Android Studio me open karo, Gradle sync hone do (Compose,
   Room, Markwon, Glide, WorkManager, Preference, PDFBox — sab pehli baar
   download honge, internet chahiye).
2. `Run ▶` (min SDK 26, Android 8.0+; on-device LLM ke liye physical device
   strongly preferred over emulator).

## ⚠️ Honest notes — pehli build se pehle zaroor padho

- **This sandbox still has no Android SDK/Gradle/network access**, so every
  pass (including this one) is verified by careful `grep`-based
  cross-referencing — every tool-call site checked against its target
  function's real signature, every nav route checked against every
  `navigate()` call, every import checked against a real declared symbol —
  not by an actual compile. That catches "cannot resolve symbol"-class
  bugs; it does not replace a real build. Run it via GitHub Actions or
  Android Studio before trusting a pass fully.
- The classic-build merge friction that used to be documented here (two
  `tasks-genai` versions, two inference paths, `LlmInferenceHelper.kt`
  method-not-found errors) no longer applies — that whole build was removed
  in Phase 1 (`FIXES_LOG.md` #8). `inference/InferenceEngine.kt` is the only
  on-device inference path now, against `tasks-genai:0.10.24`.
- **All runtime permissions are now requested upfront**, the moment
  `MainActivity` opens (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`,
  `CAMERA`, `RECORD_AUDIO`, and `POST_NOTIFICATIONS` on Android 13+) — one
  system dialog batch on first launch, so `get_location`/`send_notification`
  and the other tools work right away instead of needing a per-screen
  prompt. If the user denies any of them, the affected tool(s) just return
  their existing "permission nahi di gayi" message — nothing crashes, and
  the user can still grant it later from Android's app-info settings screen
  (there's no in-app re-prompt/settings-deeplink button yet).
- `web_search`/`scrape_webpage` scrape DuckDuckGo's/a target site's HTML
  directly — like any scraper, this can break if the target changes its page
  markup; there's no error beyond a generic "❌ result nahi mila" if that
  happens.
- Dono builds ke apne-apne model catalogs hain (`model/ModelManager.kt`'s
  use-case tagged list vs `data/CuratedModels.kt`) — inhe merge nahi kiya
  gaya hai, dono jaise the waise hi hain.
- Baaki sab honest caveats jo dono original README me likhe the (Audio
  Scribe seedha raw audio LLM me feed nahi karta, Mobile Actions
  Accessibility Service use nahi karta, RAG TF-IDF based hai na ki real
  embeddings, curated model URLs verify karke hi use karna, etc.) — wo sab
  as-is apply hote hain.
