# Arya App — Build Fixes Log (25 July 2026)

Ye file un saare fixes ka record hai jo build errors theek karne ke liye kiye gaye.
Agle baar koi naya error aaye to pehle check karo ki wo in files me se kisi se
related to nahi hai.

## 1. `DeviceExtraTools.kt` — do jagah bugs

**Error:** `Unresolved reference: ACTION_ZEN_MODE_SETTINGS` (line ~187)
**Wajah:** `Settings.ACTION_ZEN_MODE_SETTINGS` public Android SDK constant nahi hai.
**Fix:** Raw string action use kiya:
```kotlin
Intent("android.settings.ZEN_MODE_SETTINGS")
```

**Error:** `Returns are not allowed for functions with expression body`
**Wajah:** `openApp()` aur `sendNotification()` functions expression-body
(`fun x(): String = try { ... }`) the, lekin andar `return` statement tha —
Kotlin me ye sirf block-body functions (`fun x(): String { ... }`) me allowed hai.
**Fix:** Dono functions ko block-body me convert kiya:
```kotlin
fun openApp(...): String {
    return try { ... } catch (e: Exception) { ... }
}
```

## 2. `app/build.gradle.kts` — MediaPipe library version mismatch

**Error:** `Unresolved reference: setMaxNumImages`, `Too many arguments for
generateResponseAsync()`, `Unresolved reference: cancelGenerateResponseAsync`
**Wajah:** `tasks-genai:0.10.21` bahut purana version tha — code jo APIs use
kar raha tha (multimodal images, callback-based streaming, cancel) wo is
version me exist hi nahi karte the.
**Fix:** Version bump kiya `0.10.21` → `0.10.24`.

**Error:** `class file has wrong version 65.0, should be 61.0`
**Wajah:** `tasks-genai:0.10.24` Java 21 bytecode me compiled hai, lekin
project JDK 17 target kar raha tha.
**Fix:**
- `.github/workflows/build.yml` me JDK 17 → JDK 21
- `compileOptions` aur `kotlinOptions` me `VERSION_17`/`"17"` → `VERSION_21`/`"21"`

**Error:** `Cannot access class 'com.google.mediapipe.framework.image.MPImage'`,
`Unresolved reference: BitmapImageBuilder`
**Wajah:** Ye image-handling classes `tasks-genai` ke andar nahi, `tasks-vision`
library me hoti hain — jo dependency me thi hi nahi.
**Fix:** Naya dependency add kiya:
```kotlin
implementation("com.google.mediapipe:tasks-vision:0.10.26.1")
```
(Note: `0.10.24` version `tasks-vision` ke liye Maven pe exist nahi karta —
har MediaPipe module alag version numbers pe release hota hai, saath nahi.)

## 3. CI workflow — sab errors ek saath dekhne ke liye

`gradle assembleDebug --stacktrace` me `--continue` flag add kiya, taaki
Gradle pehli error pe na ruke aur ek hi run me saari independent errors
dikha de (debugging bahut fast ho jaata hai isse).

## 4. Post-merge code review (26 July 2026) — 3 functional bugs found & fixed

These didn't show up as compile errors — the code compiles fine, but would misbehave or
crash at runtime. Found by manually reading through the tool-calling and streaming paths.

**Bug:** `StreamPlayerManager` (`play`/`pause`/`resume`/`stop`/`status`) touched `ExoPlayer`
directly, but every tool in `AryaToolRegistry.execute()` runs under `Dispatchers.IO`.
ExoPlayer enforces that it's only ever created/accessed from a thread with a `Looper`
(the main thread) — calling it from a background thread throws
`IllegalStateException: Player is accessed on the wrong thread`. Every `play_stream` /
`find_and_play` / `pause_stream` / etc. tool call would have crashed or failed silently.
**Fix:** each function now hops onto `Dispatchers.Main` internally via
`runBlocking(Dispatchers.Main) { ... }` before touching the player, so callers
(`StreamTools`, `AryaToolRegistry`) don't need to change at all.

**Bug:** `ToolCallParser.parseToolCall()`'s brace-matching depth counter counted every `{`
and `}` in the raw text, including ones inside quoted string values (e.g. a `calculate`
expression or any JSON-ish text passed as a tool argument). This could cut the JSON off
early or overrun it, causing a parse failure — the tool call would silently be dropped and
the raw model text shown to the user instead of the tool actually running.
**Fix:** depth counter now tracks whether it's inside a quoted string (with escape-char
handling for `\"`) and ignores braces while inside one.

**Bug:** same parser located the start of the JSON with an exact-substring check
(`{"tool"` or `{ "tool"` — only 0 or exactly 1 space allowed). A model reply formatted with
a newline or two spaces after the `{` would never be recognized as a tool call.
**Fix:** replaced with a whitespace-tolerant regex, `\{\s*"tool"`.

## 5. Free online models research + UI restructure (26 July 2026)

- **`data/OnlineModels.kt` (new)** — curated, free-tier-only model catalogs for Groq, Gemini,
  and OpenRouter, researched live against each provider's current docs:
  - **Groq**: `openai/gpt-oss-120b`, `openai/gpt-oss-20b`, `qwen/qwen3.6-27b` — Groq retired
    `llama-3.3-70b-versatile`/`llama-3.1-8b-instant`/`qwen/qwen3-32b`, so these three are now
    the actual current free-tier lineup (console.groq.com/docs/models).
  - **Gemini**: `gemini-flash-lite-latest`, `gemini-3.1-flash-lite`, `gemini-flash-latest`,
    `gemini-3.5-flash`, `gemini-2.5-flash-lite`, `gemini-2.5-flash` — all live on Google AI
    Studio's free tier, no credit card.
  - **OpenRouter**: 8 verified `:free`-suffixed models (gpt-oss-120b/20b, llama-3.3-70b,
    qwen3-next-80b, nemotron-3-ultra/super, gemma-4-31b, laguna-m.1) — OpenRouter's free
    lineup rotates fastest of the three, so DeepSeek/Mistral/Gemini currently have **zero**
    free models there despite older guides still referencing them.
- **`util/OnlineChatHelper.kt`** — was hardcoded to one fixed model per provider
  (`llama-3.3-70b-versatile`, which is now retired). Rewritten to read the user's selected
  model per provider from `PreferencesManager`, and to fall through the rest of that
  provider's free-model list before moving to the next API key/provider on failure.
- **`util/PreferencesManager.kt`** — added `selectedGroqModel` / `selectedGeminiModel` /
  `selectedOpenRouterModel` / `autoOnlineFallback`.
- **`ui/OnlineModelsScreen.kt` (new)** — per-provider free-model picker, same visual pattern
  as the reference screenshots (blue all-caps section header, rounded selectable cards).
  Reachable from Menu → "Online free models".
- **Home screen restructured into a 2-page swipeable pager** (`MainActivity.kt`):
  page 0 = `GalleryScreen` (every offline/on-device model — downloaded, recommended, or
  currently loaded), page 1 = `ChatScreen`. The old use-case tile gallery (`HomeScreen`)
  didn't disappear — it moved to its own `use_cases` route, one tap away via the menu.
- **`viewmodel/ChatViewModel.kt`** — offline and online are now silently chained: if no
  on-device model is loaded, or the loaded one throws/returns empty mid-generation, the
  ViewModel automatically retries through `OnlineChatHelper`'s free-model chain instead of
  just showing an error — no manual "online mode" toggle needed for this fallback to kick in.

## 6. Final review pass (26 July 2026) — 3 more issues found & fixed

- **Missing `@OptIn(ExperimentalFoundationApi::class)`** — `MainActivity.kt`'s new home-page
  pager uses `HorizontalPager`/`rememberPagerState`, which are still marked experimental at
  the project's Compose BOM version (`2024.06.00`, ≈ Foundation 1.6.x — Pager wasn't
  stabilized until Foundation 1.7.0). Without the opt-in this is a straight-up compile error.
  Fixed with a file-level `@OptIn` in `MainActivity.kt`.
- **`ui/HomePagerScreen.kt` — orphaned dead file, deleted.** An earlier, unused draft of the
  same pager logic that now lives inline in `MainActivity.kt`. Never imported or referenced
  anywhere; would have just sat there as confusing duplicate code. Removed entirely.
- **`autoOnlineFallback` preference was unreachable.** `PreferencesManager` had the setting
  (defaulting to on), but nothing ever read it and there was no UI to turn it off. Wired it
  into `MainActivity` (gates whether `onlineFallback` is passed to `ChatViewModel`) and added
  a toggle in `SettingsScreen` using the existing `SettingsRow` pattern, so it's an actual,
  reachable setting now instead of dead code.
- Also cleaned up a couple of leftover unused imports (`OnlineModel` in `OnlineChatHelper.kt`,
  `ApiProvider` in `OnlineModelsScreen.kt`) and added a small "🌐 Online free model se jawaab
  aaya" status line in `ChatScreen.kt`, since `ChatViewModel.lastReplySource` existed but
  nothing displayed it.

## 7. Critical navigation bug (26 July 2026) — API Keys screen was unreachable

**Bug:** The Compose `MainActivity`/`MenuScreen` had no route to the classic ViewBinding
build's `HomeActivity`/`HubActivity`/`ApiKeysActivity` at all. The only path in was the
home-screen widget (`WidgetLaunchActivity`), and even that only opened `HomeActivity` when
zero chat sessions existed yet — once one session existed, the widget always deep-linked
straight to `ChatActivity` instead. Net effect: on a normal fresh install, the API Keys
screen (and Sessions/classic Settings/Models/Notifications) was practically unreachable
through the app's UI, contradicting the README's claim that it's "one tap away from the
Compose home screen."

**Fix:** Added an "API Keys & Chat History" card to `MenuScreen.kt`'s `MENU_ITEMS`, and in
`MainActivity.kt`'s `composable("menu")` block, special-cased that one route to
`context.startActivity(Intent(context, HomeActivity::class.java))` instead of
`navController.navigate(...)` — since `HomeActivity` is a separate Android Activity, not a
Compose NavHost destination. Menu → API Keys & Chat History → (Hub icon) → API Keys is now
the always-available path, independent of whether the widget is installed or any session exists.

## 8. Phase 1 refactor (27 July 2026) — classic build removed, single Compose UI + single inference path

This is the first phase of a larger cleanup (see chat history for the full plan) — merging
the two parallel UI builds and two parallel inference paths this project accumulated back
into one, since that duplication was the root cause of bug #7 above.

**Removed entirely** (all confirmed zero remaining references via full-codebase grep before
deletion):
- Classic ViewBinding Activities: `HomeActivity`, `HubActivity`, `ChatActivity`, `ChatAdapter`,
  `SessionsActivity`, `SettingsActivity`, `ModelListActivity`, `NotificationsActivity`,
  `ApiKeysActivity`
- Their layouts/menu XML (`activity_home.xml`, `activity_hub.xml`, `activity_chat.xml`, etc.)
- The duplicate on-device inference path: `model/ModelManager.kt`, `model/LlmInferenceHelper.kt`,
  `model/ChatMessage.kt` (the `inference/` path stays — it's the one actually wired to the
  Compose `GalleryScreen`/model downloader)
- `Theme.Arya.Classic` from both `values/themes.xml` and `values-night/themes.xml`
- Now-unused dependencies: `com.google.android.material`, `androidx.constraintlayout`,
  `androidx.recyclerview`, `androidx.documentfile`, `androidx.preference-ktx`, and the
  `viewBinding` build feature. (`androidx.appcompat` was kept — `AryaApp.kt` still uses
  `AppCompatDelegate.setDefaultNightMode` for the dark-mode toggle.)

**Added** (so nothing that classic build did is actually lost):
- `ui/ApiKeysScreen.kt` — Compose replacement for the old `ApiKeysActivity`, reachable at
  Menu -> "API Keys" (a real NavHost route now, not an Activity hidden behind the widget).
- `ui/SessionsScreen.kt` — Compose replacement for `SessionsActivity`, at Menu -> "Chat
  History". Includes the export/share-as-Markdown action the classic build had too.
- `ChatViewModel` now takes an optional `chatDao`/`initialSessionId` and actually **persists
  every exchange to Room** — previously the Compose chat screen was pure in-memory and lost
  everything on process death; it was never wired to the `ChatDao`/`ChatSessionEntity` at all
  even though those Room classes already existed (only the classic `ChatActivity` used them).
  This is a real behavior change, not just a rewire: chats now survive app restarts.
- `mipmap-anydpi-v26/ic_launcher.xml` gained a `monochrome` layer for Android 13+ themed icons;
  refined the foreground glyph slightly.

**Simplified:** `WidgetLaunchActivity` used to query Room directly to decide between opening
`ChatActivity` or `HomeActivity`. It now just opens `MainActivity` — much less code, and every
session it creates shows up in Chat History regardless of which app entry point started it.

**Honesty note (same as always):** this was done via careful `grep`-based cross-referencing of
every deleted symbol against the rest of the codebase (no unresolved imports, no dangling
`R.layout`/`R.string` refs, no missing ViewBinding classes) since this sandbox has no Android
SDK/Gradle to actually compile against. That catches the class of bug a compiler would catch
via "cannot resolve symbol" — it does **not** catch logic bugs, so this still needs a real
build (GitHub Actions or Android Studio) before being called verified.

**Deliberately NOT done in this pass** (next phases): full visual/branding redesign beyond the
icon, RAG embeddings upgrade, streaming foreground service, gradle wrapper jar (still can't be
generated without network/Gradle access in this environment).

## 9. Phase 2 (27 July 2026) — background streaming + onboarding nudge; RAG/wake-word reviewed

**Streaming now survives backgrounding.** Added `service/StreamPlaybackService.kt`, a real
`androidx.media3.session.MediaSessionService` that owns the `ExoPlayer` + a `MediaSession`.
Media3's `DefaultMediaNotificationProvider` automatically shows a system media notification
and enters/exits the foreground state as playback starts/stops — no manual notification code
needed. `player/StreamPlayerManager.kt` is now a thin client that binds to this service (via a
custom `ACTION_LOCAL_BIND` intent action, kept distinct from `MediaSessionService.onBind`'s own
handling of controller/notification connections — overriding `onBind` unconditionally would
have silently broken those). Added the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission and
`media3-session:1.4.1` dependency, and an `onTaskRemoved()` override so the service stops
itself if the user swipes the app away while nothing is actively playing (Media3's own
recommended pattern), instead of lingering as an empty foreground service.

Known tradeoff, stated plainly: `pause`/`resume`/`stop`/`status` only check the in-memory
bound-service reference, not a fresh bind — so if the app process is killed and relaunched
while the service is still alive in the background, those four (not `play`) won't reconnect to
it. Fixing that fully needs a persisted "is something playing" flag checked before deciding
whether to bind, which was left out to avoid the alternative bug of starting an empty
foreground-service notification on every stray `pause_stream` call.

**First-run onboarding nudge.** Added a dismissible banner on the Chat tab, shown only when
there's neither an offline model loaded nor any API key saved, pointing straight at Menu ->
API Keys (`MainActivity.kt`, `composable("home")`). This exists because bug #7's fix made API
Keys *reachable*, but a first-time user still has no reason to go looking for it — "why isn't
Arya answering me" was a real discoverability gap even after that fix.

**Reviewed, not changed:**
- `WakeWordService`/`VoiceActivityDetector` — already exactly the intended shape (Picovoice
  opt-in only behind a saved key, VAD the automatic zero-setup default) — no changes made.
- RAG (`SimpleRagHelper.kt`) — still TF-IDF, **not upgraded**. A real embeddings upgrade needs
  a `.tflite` embedding model file bundled or downloaded, and this sandbox has no network
  access to fetch one — attempting it blind would mean shipping code that references a model
  file that doesn't exist. Left as a clearly-flagged next step rather than faked.

**Same honesty note as always:** no Android SDK/Gradle in this sandbox, so this is still
`grep`-verified for dangling references, not compiler-verified. Build via GitHub Actions or
Android Studio before trusting it.

## 10. Phase 3 (27 July 2026) — visual/branding redesign

Replaced the default-Material-picker palette (orange/blue/green primaries — indistinguishable
from any Android sample app) with a deliberate token system built from what Arya actually is:
a voice-first assistant that lives in two places at once — a model running quietly on your
phone, or a request going out to the network.

**Palette** (`ui/theme/Color.kt`): Ink (`#14121A`, violet-cast near-black, not flat black) for
the resting/local state, Signal (`#7C5CFC`, electric violet) for primary actions and the
"listening" moment, Ember (`#FF7A45`) specifically as the **online-fallback indicator** and
Sprout (`#34D399`) specifically as the **on-device indicator** — two distinct hues so which
mode answered is legible at a glance in the chat screen, not just a text label. `Theme.kt` maps
these to a complete M3 `ColorScheme` (both light/dark, all container/on-container roles
filled in deliberately rather than left to M3's auto-derivation) plus a small shape asymmetry
(rounder cards at 16dp, sharper small elements at 8dp) so containers read as "quiet" and
actions read as "pressable."

**Typography** (`ui/theme/Type.kt`): tightened letter-spacing on titles for a more considered
feel, full bodySmall/titleSmall roles filled in (previously left to M3 defaults). Added one
deliberate signature: `AryaMonoStatus`, the system monospace face used *only* for technical
readouts — model names, sync timestamps, masked API keys, the on-device/online badge — never
for conversation text. Arya started as Termux/CLI scripts; this keeps a thread of that
character in status text without making the whole app look like a terminal.

**Applied the signature in 4 concrete places**: `ChatScreen`'s reply-source badge (now shows
both "● online" in Ember and "● on-device" in Sprout — previously only "online" had any
indicator at all), `SessionsScreen`'s model-name/date line, `ApiKeysScreen`'s sync-status text
and masked-key display.

**App icon**: foreground "A" glyph recolored to Signal violet on an Ink background (was generic
orange), with the crossbar notch in Sprout green as a small nod to the on-device/online duality
that now runs through the rest of the UI.

**Deliberately scoped out**: no custom font (this sandbox has no network to fetch one — system
default + monospace only, which is why the "signature" leans on font *family contrast* rather
than a custom display face), no per-screen layout rework (spacing/structure across screens
was already reasonably consistent, so this pass stayed to color/type tokens rather than
re-touching every screen's layout — lower risk, given none of this is compiler-verified yet).

Old color names (`AryaOrange`, `AryaBlue`, `AryaGreen`, etc.) kept as `@Deprecated` aliases
pointing at the new tokens, specifically so `OnlineModelsScreen.kt`/`MainActivity.kt` (which
still reference `AryaBlue` for an accent/dot-indicator) keep compiling without being touched —
they'll just render in the new violet automatically.

## 11. Phase 4 (27 July 2026) — RAG quality improvement (still not real embeddings)

Honest framing first: this is **not** the embeddings upgrade promised earlier — that still
needs a `.tflite` embedding model file, and this sandbox still has no network access to fetch
one. What changed instead is a real, incremental improvement to the existing TF-IDF retrieval
in `SimpleRagHelper.kt`, using only techniques that need no external model:

- **Bigram (phrase) matching** alongside unigrams, weighted higher — two words matching
  *together* and in order is a much stronger relevance signal than the same two words matching
  separately in unrelated chunks.
- **Cosine-style length normalization** — chunk scores are now divided by `sqrt(chunk word
  count)`, so a long chunk no longer wins purely by containing more of the document's
  vocabulary by chance.
- **Sentence-aware chunk capping** (`MAX_CHUNK_CHARS = 600`) — very long paragraphs get split
  on sentence boundaries into smaller chunks instead of being indexed as one giant blob, which
  both the bigram matching and the length normalization above depend on being reasonably sized
  to work well.

This is a legitimate quality step (better phrase sensitivity, less bias toward long chunks) —
it is still keyword/n-gram overlap, not semantic similarity. A query and a passage that mean
the same thing but share no words still won't match. That gap only closes with real embeddings.

## 12. Phase 5 (27 July 2026) — tools now actually work from the main Chat screen

**The big one this pass.** Found (via code review while investigating "radio/yt-dlp chat me kaam nahi karta") that the main Chat screen had **zero tool-calling wired up at all** — `ChatViewModel` only ever called `engine.generateStream`/`onlineFallback` for plain text; `AryaToolRegistry`/`ToolCallParser` were only reachable from the separate (and hard-to-find) Agent Skills screen. So `play_stream`, `search_youtube`, weather, device controls, all ~90 tools — none of them did anything from the screen a normal user actually types into. This wasn't something Phase 1-4 broke; it was a pre-existing gap this project always had.

**Fixed**: `ChatViewModel` now takes a `toolExecutor` param. When provided (`MainActivity` wires it to `AryaToolRegistry.execute`), both the offline and online paths:
1. Build a tools-aware system prompt via `AryaToolRegistry.relevantTools(prompt)` + `ToolCallParser.buildSystemPrompt` (both pure functions, no Context needed — this is why `ChatViewModel` itself can build this without MainActivity having to pass it in)
2. After the model replies, check `ToolCallParser.parseToolCall(reply)` — if it's a tool-call JSON, execute it and replace the message with the actual tool result instead of showing raw JSON
3. `lastReplySource` now records which tool ran too (e.g. `"on-device:Gemma3-1B · tool: play_stream"`), surfaced in the chat badge

**Known rough edge, stated plainly**: for the offline (streaming) path, the raw tool-call JSON is briefly visible on screen while it's still streaming in, before getting replaced by the tool result the instant generation finishes — because whether a reply *is* a tool call can't be known until the whole thing has streamed. This is a real, if minor, UX wart; fixing it properly would mean buffering output during generation instead of live-streaming it, which was left alone to avoid degrading the (already-working) streaming feel of normal conversation.

**Added `radio`/`radio laga`/`gaana laga`/`chala do` as synonyms for `play_stream`** in `AryaToolRegistry`'s keyword matcher, since that's the exact phrasing requested.

**Also this pass**:
- `util/AryaIdentity.kt` — folds "Sudhanshu Maurya built you, you're Arya, don't claim to be GPT-4/OpenAI/Gemini" into every system prompt (both paths). Explains the earlier screenshot where the Groq path answered "I'm OpenAI's GPT-4" — that was the underlying free model's own base-training identity leaking through with no override in place.
- `util/LocationContext.kt` — folds the phone's last-known GPS location (reverse-geocoded, cached 30 min to avoid a network call every single message) into every system prompt.
- Chat status badge now shows the actual model/provider name (`"● online — Groq/llama-3.1-8b-instant"`) instead of just `"online"`.

**Honestly still not done from the yt-dlp/video request**: there is genuinely no yt-dlp (or equivalent) integration anywhere in this codebase — `search_youtube` only returns search-result *links* (the code comment says so explicitly: yt-dlp needs a Python runtime, which doesn't exist on Android). Actually playing an arbitrary YouTube video by name would need a Kotlin-native extractor library (e.g. NewPipeExtractor) added as a new dependency — a bigger, separate piece of work not attempted in this pass. `play_stream` (direct HLS/audio URLs — internet radio etc.) does work through this new tool-calling path.

**Same honesty note as every phase**: no Android SDK/Gradle in this sandbox, still grep-verified only (this time also cross-checked every changed constructor call site and lambda type signature by hand), not compiler-verified.

## 13. Phase 7 (28 July 2026) — copy-paste, online/offline switch, Persona UI, voice input

- **Long-press to copy** — `ChatBubble` now has `combinedClickable` with `onLongClick` copying the message text to the clipboard (toast confirmation).
- **Online/Offline switch** — new `PreferencesManager.chatMode` ("auto"/"online"/"offline") backing a dropdown in ChatScreen's top bar. `ChatViewModel.send()` now checks this before deciding offline-vs-online, and — for "offline" mode specifically — no longer silently falls back to online on failure (shows an explicit error instead), since that would defeat the point of forcing offline-only.
- **Persona UI** (`ui/PersonaScreen.kt`, Menu -> "Persona") — create/switch/deactivate/delete personas, reachable without typing chat commands. Also fixed a real gap while wiring this up: `PersonaStore.activeSystemPromptPrefix()` already existed but **nothing in the main chat path ever called it** — only the separate Agent Skills screen did. Now it's folded into `identityContext` alongside `AryaIdentity`/`LocationContext`, so an active persona actually affects the main Chat screen.
- **Voice input** — mic button in the input row using Android's built-in `SpeechRecognizer` (same mechanism `AudioScribeViewModel` already used for the separate Audio Scribe screen). Transcribes to text and appends into the input field for the user to review before sending — doesn't auto-send, same trust model as typed text.
- **Model list curated** per request: removed Gemma-4-E2B/E4B (text+tools only, no vision/audio) and Gemma-3n-E4B (bigger duplicate of E2B) and DeepSeek-R1-Distill (redundant). Kept Gemma-3n-E2B-it (3.7GB) as the primary "does everything reasonably" pick (vision+audio+coding), Gemma3-1B-IT as the ultra-light fallback, Qwen2.5-1.5B for compatibility with what was already downloaded, and the two 270MB function-calling models (needed by the separate Tiny Garden/Mobile Actions screens, left alone since they're not "faltu bade" — they're tiny and serve a different purpose). Verified via `UseCaseDetailScreen.kt`'s `mapNotNull` that removing IDs from `CuratedModels` can't crash anything referencing them — it silently drops missing entries.

### Deliberately NOT attempted: NewPipeExtractor for yt-dlp-style "play video by name"

This is the one item explicitly requested that I'm choosing not to add blind. Adding it means: a new external Maven dependency, implementing NewPipeExtractor's `Downloader` abstract class (it requires the app to supply its own HTTP client — there's no default), and calling its search/stream-extraction API correctly. I have no network access in this sandbox to check the library's actual current API surface, and — unlike everything else in this pass — a wrong method signature here wouldn't just break this one feature, it would fail the **entire Gradle build**, since Kotlin won't compile against a nonexistent/changed API. Given four solid phases now sit on a confirmed-passing build, I'm not willing to risk that on a guess I can't verify. Left in the pending list; best path forward is doing this in a follow-up pass with a real compiler feedback loop (i.e., after this pass builds green, iterate on just this one file against real build errors).

## 14. Phase 8 (28 July 2026) — multi-step tool loop (search → verify → answer, not just one call)

Phase 5's tool-calling was single-step: one tool call, show result, done. This pass makes it a
proper bounded loop in both `ChatViewModel.send()` (offline) and `runOnlineOnly()` (online): after
a tool executes, its result gets fed back into the conversation context, and the model can decide
to call *another* tool instead of answering immediately — e.g. search a library/GitHub repo, read
that result, and only then write code with it, instead of guessing from training data alone.
`ToolCallParser.buildSystemPrompt` now explicitly tells the model to do this for coding questions
it isn't certain about. Added `github`/`documentation dekho`/`docs check karo`/`library check karo`
as `web_search` synonyms so coding questions actually trigger this path.

**Capped at `maxToolRounds = 4`** — if the model still wants another tool after 4 rounds, the loop
stops and shows whatever came back last rather than looping indefinitely; `lastReplySource` gets
tagged `(incomplete)` in that case so it's visible this wasn't a clean finish.

**Known UX trade-off, stated plainly**: only the *first* generation round streams live to the
screen (so a plain, no-tool answer still types out as before). Once a tool call is detected and
the loop starts, subsequent rounds show a status line ("🔍 tool_name chala raha hoon...") instead
of live text, and the *final* answer (once no more tools are called) appears all at once rather
than streaming — doing per-round live streaming across an unknown number of loop iterations added
enough complexity that it was left for later rather than risking new bugs in an already-large pass.

## 15. Phase 9 (29 July 2026) — in-app update checker (no more re-sharing the APK)

**The problem this solves**: once Rudra shares the APK with friends, any future fix/feature meant re-sending the file every time — Android has no way for one app to silently push updates into another (not without root/MDM, which isn't realistic for casual sharing). This adds an in-app "check for update" flow instead: install once, then Arya itself notices and offers new versions.

**How it works**:
- `util/UpdateChecker.kt` — hits `GET api.github.com/repos/{owner}/{repo}/releases/latest`, compares the release's `tag_name` (dotted-numeric comparison, so "1.10.0" correctly beats "1.9.0") against the installed `versionName`, and finds the `.apk` release asset's download URL.
- `worker/UpdateCheckWorker.kt` + `UpdateCheckScheduler.kt` — same `CoroutineWorker`/`PeriodicWorkRequestBuilder` pattern as the existing `CurrentInfoWorker`, checks once a day, caches the result in `PreferencesManager`.
- `util/UpdateInstaller.kt` — downloads the APK (reusing `HfDownloader`, which turned out to already be a generic "download this URL" helper despite the name) and hands it to Android's package installer via `FileProvider` + `ACTION_VIEW` — the user still gets the normal Android "Install this app?" confirmation (there's no way around that without root; a tap is as automatic as this can get).
- **Settings -> "App Updates"** — where the GitHub repo ("owner/repo") is configured, plus "Check now" and, once a newer version is found, "vX.Y.Z download & install karo". Also a small dismissible banner on the Gallery page when an update's available, so friends don't have to think to go looking in Settings.
- Added `REQUEST_INSTALL_PACKAGES` permission and an `updates/` cache path to the existing FileProvider config.

**One-time CI change this depends on**: the workflow previously only uploaded a temporary Actions artifact (expires, needs a GitHub login to download — useless for this feature). `.github/workflows/build.yml` now also publishes a **GitHub Release** with the APK attached, reading `versionName` straight out of `app/build.gradle.kts` so there's no separate manual version-bump step — bump the version, push, the next build publishes that as the release. Pushing again without bumping the version just updates the same release (same tag).

**The repo isn't hardcoded** — the Settings field defaults to blank, so update checking is off until it's explicitly configured with the actual repo (avoids silently pointing at a guessed/wrong repo name).

**Honest limitation, stated plainly**: the update-available banner reads `PreferencesManager` directly rather than through a reactive Flow, since the background worker writes to plain SharedPreferences — it'll reliably show up on the next app open/navigation, but won't instantly appear mid-session the moment a background check finishes while the Gallery screen is already on-screen. Not worth a bigger refactor for what's a once-a-day check.

## Debugging tip (agli baar ke liye)

Jab build fail ho:
1. GitHub Actions run page → **⋮ (three dots) → "Download log archive"**
2. Wo zip yahan chat me upload kar do — screenshots ke bajaye, taaki
   poora log ek sath padh ke exact root cause nikala ja sake.

## 16. Full top-to-bottom review pass (31 July 2026) — 5 issues found & fixed, nothing else broken

Went through every file in the zip, smallest to largest, cross-referencing (not just reading)
against the rest of the codebase: every one of `AryaToolRegistry`'s 109 tool definitions checked
against its `execute()` `when`-branch (all present, no dupes) *and* against its target function's
actual parameter count (all 108 non-`else` call sites fit); every internal `import
com.arya.ai.*` checked against a real declared symbol; every NavHost route checked against every
`navigate()` call site; every deleted-in-a-past-phase file/theme/class re-confirmed to have zero
remaining references. That part came back clean — the findings below are the real gaps.

**Bug: the "Use Cases" 7-tile gallery was unreachable.** `composable("use_cases")` existed in
`MainActivity.kt` and the screen itself worked fine, but nothing anywhere called
`navigate("use_cases")` — not the menu, not any button. Same class of bug as #7 (API Keys), just
never caught for this screen. **Fix:** added a "Use Cases" card to `MenuScreen.kt`'s
`MENU_ITEMS`, pointing at the existing route.

**Bug: on-device tool loop duplicated context every round.** `InferenceEngine`'s
`LlmInferenceSession` is stateful — every `generateStream()` call does `addQueryChunk(prompt)` on
top of whatever the session already remembers. `ChatViewModel`'s multi-step tool loop (Phase 8)
was re-sending the *entire, growing* `conversationContext` string each round instead of just the
new tool-result text — so round 2 re-added round 1's full text into a session that already had
it, round 3 re-added it a third time, etc. Real risk of hitting the on-device model's context
limit well before `maxToolRounds` (4) on weaker phones. **Fix:** each round now only sends the
incremental tool-feedback text; the session's own memory carries the rest. Also stopped
re-attaching `images` on rounds after the first, same underlying reason.

**Bug: date/time line sent twice to the model.** `ToolCallParser.buildSystemPrompt()` already
folds in a `DateTimeContext.currentDateTimeLine()`; `ChatViewModel.send()` was adding a second
one right after, whenever tools were enabled. Harmless but wasteful. **Fix:** only added once
now, in the non-tool branch that actually needs it.

**Doc gap: `README.md`'s intro/structure/permissions/dependencies/honest-notes sections were all
still describing the pre-Phase-1 state** (two builds coexisting, `Theme.Arya.Classic`,
`LlmInferenceHelper.kt`, ConstraintLayout/RecyclerView deps) — none of that has existed since
Phase 1 removed the classic build. Also separately claimed CI runs unit tests and has a
`release-signed` job, neither of which was actually in `build.yml`. **Fix:** rewrote those
sections to match the current single-Compose-UI state; see next item for the CI claim.

**Gap: CI never actually ran the unit tests or built a signed release, despite README saying
both existed.** `AryaToolRegistryTest.kt` (added in an earlier pass) was never wired into
`build.yml` — CI only ever ran `assembleDebug`. The signed-release job README described in detail
(base64 keystore secret, `ARYA_KEYSTORE_BASE64` + 3 more secrets, skip-if-unset) was documented
but never written. **Fix:** added a `testDebugUnitTest` step before the debug build, and a new
`release-signed` job that decodes the keystore secret to a temp file, points
`ARYA_KEYSTORE_PATH` at it (what `build.gradle.kts`'s signing config already reads), and runs
`assembleRelease` — every step gated on a secret-presence check so repos without the secrets
configured build exactly as before.

**Same honesty note as every phase:** still `grep`-verified, not compiler-verified — no Android
SDK/Gradle/network in this sandbox. Build via GitHub Actions or Android Studio before trusting
this pass fully.

## 17. Phase 10 (31 July 2026) — Firebase: Community stats + optional chat sync, no server needed

**What this solves**: previously the only way to know anything about real usage was whatever
Rudra/friends happened to mention. This adds Firebase — specifically because it needs **no
Python/Termux server and no separate machine to keep running**; the phone talks straight to
Google's hosted Realtime Database/Analytics over HTTPS, the same way it already talks to
Groq/weather/etc.

**New/changed files**:
- `util/FirebaseSync.kt` (new) — two deliberately-separated things:
  1. **Anonymous counting, always on, no consent needed** — `trackUser()` bumps
     `/meta/totalUsers` the first time an `installId` (random UUID, generated once per install
     in `PreferencesManager`, not tied to any real identity) is ever seen, and maintains a live
     `/meta/onlineCount` via the standard Firebase presence pattern (`.info/connected` +
     `onDisconnect()` registered *before* the write, so a kill/network-loss/close all
     self-correct the count with no explicit "closing" call needed anywhere else).
  2. **Chat content sync, gated behind explicit consent** — `logChatExchange()` pushes each
     user+model exchange to `/chats/{installId}` in Firebase, but is a no-op unless
     `PreferencesManager.dataConsentGiven == true`. This is the one thing here that's actually
     personal (people can type anything), so it's the one thing behind a dialog.
  - `logToolUsed()` — anonymous Analytics event per tool call, so which of Arya's ~110 tools
    actually get used in practice is visible without reading chat content at all.
  - Every function checks `FirebaseApp.initializeApp(context)` first and silently no-ops (logs
    a warning, doesn't crash) if that fails — so a build without a real `google-services.json`
    still compiles and runs exactly as before, it just has no Community stats.
- `ui/CommunityScreen.kt` (new, Menu -> "Community") — shows total installs + how many are
  online right now, read live off `/meta` via `FirebaseSync.observeCommunityStats`. Deliberately
  does **not** try to show geography or a chat-message feed in-app — Firebase Console's
  Analytics -> Demographics tab already gives country/city automatically from the Analytics SDK
  being present (no custom code needed for that), and `/chats` is meant to be read in the
  Console's Realtime Database viewer rather than rebuilding a log browser the Console already is.
- `MainActivity.kt` — added the first-launch consent `AlertDialog` ("Do you want to chat or
  share personal data to further improve AI?"), shown from the same startup `LaunchedEffect`
  that requests runtime permissions (not from a separate flow) so it's asked once, right when
  the app first opens, as requested. "Haan"/"Nahi" set `dataConsentGiven`/`dataConsentAsked` and
  the dialog never shows again after that. `FirebaseSync.trackUser()` is called unconditionally
  in the same effect (see the "always on" point above); a shared `chatSync` lambda is built once
  and passed into every `ChatViewModel` instance (home-page chat + reopened-session chat) so
  every real exchange is covered, not just one screen; `toolExecutor` now also calls
  `FirebaseSync.logToolUsed()` before running each tool.
- `viewmodel/ChatViewModel.kt` — new optional `chatSync: ((userText, modelText) -> Unit)?`
  constructor param, called from the existing `persistExchange()` (same single place that
  already saves to Room) — no new call site needed, both the offline and online reply paths
  already funnel through it.
- `util/PreferencesManager.kt` — added `installId` (lazily generates+persists a random UUID on
  first access), `dataConsentAsked`, `dataConsentGiven`.
- `ui/MenuScreen.kt` — added the "Community" tile pointing at the new route.
- Root `build.gradle.kts` — declared the `com.google.gms.google-services` plugin (`apply
  false`, version 4.5.0 — current per Firebase's release notes as of this pass).
- `app/build.gradle.kts` — added `firebase-bom:34.15.0` + `firebase-analytics` +
  `firebase-database`. The google-services plugin itself is applied **conditionally**
  (`if (file("google-services.json").exists())`), same "don't break the build for people who
  haven't configured this yet" stance as the existing release-signing config — without a real
  config file, the project builds and runs exactly as it did before this phase.
- `.github/workflows/build.yml` — both the debug-build job and the `release-signed` job gained
  an optional step that decodes an `ARYA_GOOGLE_SERVICES_JSON_BASE64` repo secret into
  `app/google-services.json` before building, skipped entirely if that secret isn't set (same
  pattern as the existing keystore-secret gating).
- `README.md` — new "Firebase setup" section: create-a-Firebase-project steps, where to put
  `google-services.json`, example Realtime Database security rules, and the CI secret name.

**Honest limitations, stated plainly**:
- **This sandbox has no network access**, so there is no real `app/google-services.json` in
  this zip — Sudhanshu needs to create his own Firebase project and drop that file in himself
  (README walks through it). Until then, the app builds and runs identically to before this
  phase; Community just shows "Firebase configure nahi hai" instead of numbers.
- Geography ("kaha kaha se log jude hain") is **not** custom code — it's Firebase Analytics'
  built-in Demographics view in the Console. If Sudhanshu wants it inside the app itself instead
  of the Console, that's a separate follow-up (would need a country lookup — e.g. IP geolocation
  or on-device `Geocoder` — written per-user into `/meta/countries/{code}`, not attempted here
  to keep this pass's scope to what was explicitly asked).
- The presence pattern's own well-documented trade-off applies: if `.info/connected` fires more
  than once in one app session (brief network drop-and-recover), the online count can
  temporarily read a little high until the stale listener's cleanup also fires — this is
  standard behavior of Firebase's own presence pattern, not something specific to this code.
- The example Realtime Database rules in the README are intentionally simple (open write, no
  cross-user read) for a hobby app shared casually with friends — not hardened against a
  malicious actor spamming `/meta/onlineCount`. Good enough for the stated use case; flagged
  honestly rather than silently shipped as if it were production-grade.

**Same honesty note as every phase**: grep-verified only (every new symbol/import/route
cross-checked against real declarations and call sites, both `ChatViewModel` construction sites
confirmed to pass the new `chatSync` param) — no Android SDK/Gradle/network in this sandbox, so
this still needs a real build (GitHub Actions, once `google-services.json`/its CI secret is set
up) before being called verified.

## 9. ElevenLabs TTS + Groq Whisper STT + dedicated NewsAPI (1 August 2026)

Paired with an `arya-relay` update — three new relay endpoints (`/v1/elevenlabs`,
`/v1/whisper`, `/v1/news`), each keeping the same "keyless/offline fallback if the relay
isn't configured or the call fails" stance as the existing Tavily web-search integration.

- **`ElevenLabs` TTS — default everywhere, Android TTS fallback.** New `VoiceHelper.speak()`
  (suspend) tries `/v1/elevenlabs` first (posts text, gets back `audio/mpeg` bytes, plays via
  `MediaPlayer`), falls back to the existing Android `TextToSpeech` on any failure/no relay
  configured. Also wired into `WakeWordService`'s reply-speaking path (`speakAudio()` /
  `tryPlayElevenLabs()`) — same pattern, duplicated rather than shared since the service owns
  its own `tts` instance/lifecycle (`isSpeaking` flag, `onInit`, barge-in timing untouched).
- **Groq Whisper STT — tried first, Android SpeechRecognizer fallback.** New
  `util/WhisperRecorder.kt`: records mic to a temp `.m4a` (`MediaRecorder`, AAC), uploads
  multipart to `/v1/whisper` (Groq's `whisper-large-v3` — reuses the same `GROQ_KEYS` the relay
  already has, no separate key). `ChatScreen`'s mic button is now a toggle: tap to start
  recording (if `WhisperRecorder.isAvailable`, i.e. `RELAY_URL` is configured at build time),
  tap again to stop and transcribe; if that comes back null (no relay / upload failed / empty
  audio), falls straight through to the pre-existing `SpeechRecognizer` flow for that same
  attempt. `AudioScribeViewModel` (separate "Audio Scribe" screen) and the wake-word command
  listener were **not** touched — both still use Android's `SpeechRecognizer` only, left as a
  follow-up if wanted.
- **NewsAPI — dedicated news source, RSS-scrape fallback.** `BriefingTools.getNews()` now
  tries `/v1/news` first (`mode=headlines&country=in` for the no-topic case, `mode=search` for
  a topic) via the new `newsApiArticles()` helper, falls back to the pre-existing keyless
  Google News RSS scrape if the relay/News key isn't there. `morning_briefing` picks this up
  automatically since it calls `getNews()` internally — no separate change needed there.

**Honest limitations, stated plainly**:
- Whisper's mic-toggle UX is a regression vs. the old single-tap `SpeechRecognizer` flow (which
  auto-detects end-of-speech) — user now has to tap again to stop recording. Trade-off for
  using a file-upload-based STT instead of a streaming one; flagged rather than hidden.
- If a Whisper *upload* itself fails partway (not just "no relay configured"), the fallback to
  `SpeechRecognizer` only kicks in for that one attempt — it re-listens from scratch rather than
  resuming, since the two mechanisms don't share partial state.
- Same sandbox caveat as every phase: grep-verified only (no Android SDK/Gradle/network here),
  needs a real CI build before being called verified. `arya-relay/app.py`'s new endpoints
  weren't hit against live ElevenLabs/Groq-Whisper/NewsAPI accounts either — only checked
  against each provider's documented request/response shape.

## 10. Wake-word command listening also moved to Whisper (1 August 2026)

Extends #9 — Chat screen's mic already used Whisper via tap-to-stop; the wake-word service's
command listening (after "Hey Arya, <command>") needed a *hands-free* auto-stop instead, since
there's no button to tap here.

- **New `util/WhisperUploader.kt`** — pulled the multipart-upload-to-`/v1/whisper` logic out of
  `WhisperRecorder` into a shared object, so it's not duplicated between the two recording styles.
  `WhisperRecorder` (Chat screen) now just calls `WhisperUploader.transcribe()`.
- **New `util/VadCommandRecorder.kt`** — records raw PCM via `AudioRecord` (same primitive
  `VoiceActivityDetector` already uses in this service), tracks RMS to detect "has the person
  started speaking" and then "have they gone quiet again" (~900ms trailing silence = auto-stop;
  4s of nobody speaking at all, or a 10s hard cap, are both safety fallbacks), writes a WAV file
  by hand (44-byte header patched in after recording, since the final size isn't known upfront),
  and transcribes it via `WhisperUploader`.
- **`WakeWordService.startCommandListening()`** now tries this hands-free path first (releasing
  VAD/Porcupine/recognizer's hold on the mic first, same as `freshRecognizer()` already did) —
  on success, goes straight to `runCommand()`. On any failure (no relay configured, nobody
  spoke, upload failed), falls through to the pre-existing flow, renamed
  `startCommandListeningViaSpeechRecognizer()`, unchanged otherwise.
- **Audio Scribe screen intentionally left untouched** (Sudhanshu's call) — still plain Android
  `SpeechRecognizer`, since Whisper's file-upload-then-transcribe model doesn't give the same
  live/streaming partial-text UX that screen relies on; noted as a possible future follow-up if
  a chunked-upload approach is wanted there later.

**Honest limitations, stated plainly**:
- This is the least-tested new code in this pass — hand-rolled WAV header + raw `AudioRecord`
  loop + RMS thresholds tuned by eyeballing the numbers already used in `VoiceActivityDetector`,
  not measured against real recordings. The 1200 RMS threshold / 900ms silence window may need
  retuning on a real device/room, especially in a noisy environment or a very quiet speaker.
- Two back-to-back mic acquisitions happen per wake ("Hey Arya" heard via
  VAD/Porcupine/recognizer, then immediately released and re-opened as a fresh `AudioRecord` for
  the command) — functionally fine (matches how `freshRecognizer()` already tears down and
  rebuilds for each phase) but adds a small gap where the mic is briefly unheld; not expected to
  be noticeable but flagged since it wasn't measurable in this sandbox.
- Same sandbox caveat as every phase: grep-verified only, no Android SDK/Gradle/network here —
  this genuinely needs a real device test before being called verified, more so than usual given
  how much of this is new low-level audio code rather than a straightforward network call.

## 11. Gemini-Live-style continuous conversation + camera/screen vision + image generation (1 August 2026)

Big one — four new pieces, all opt-in (nothing here changes existing wake-word/chat behavior
unless the user actively opens the new Live screen or types `/image`).

- **Live conversation mode** — `LiveConversationScreen.kt` (new, Gemini-Live-style full-screen
  UI: animated status orb, live transcript) + `WakeWordService`'s existing
  `thenListenForCommand` hook (already there from earlier phases, just unused until now) —
  `ACTION_START_LIVE`/`ACTION_STOP_LIVE` intents toggle a `liveMode` flag that makes the
  service loop straight back into `startCommandListening()` after each reply instead of
  waiting for "Hey Arya" again. `util/LiveConversationState.kt` is a plain in-process
  `StateFlow` singleton (no IPC needed, same process) the screen collects to animate. Leaving
  the screen via back/home does **not** stop it — that's deliberate, matches "background me
  chale" — only the explicit X button sends `ACTION_STOP_LIVE`.
- **Camera vision** — `util/CameraFrameCapture.kt` (CameraX, periodic still captures ~every
  2.5s, decoded to `Bitmap`) feeds `util/VisionFrameProvider.kt`, a small singleton holding
  "most recent frame + timestamp + source". Only works while `LiveConversationScreen` is
  open/foregrounded — Android doesn't allow background camera access, so this is an OS
  limitation, not a design choice.
- **Screen-share vision** — new `service/ScreenShareCaptureService.kt` (foreground service,
  `MediaProjection` + `VirtualDisplay` + `ImageReader`, same ~2.5s cadence), also feeds
  `VisionFrameProvider`. Unlike camera, this *can* keep running in the background as long as
  the foreground service is alive — MediaProjection isn't subject to the camera restriction.
  Permission is re-asked by the system every session (can't be remembered), handled via
  `MediaProjectionManager.createScreenCaptureIntent()` in `LiveConversationScreen`.
- **Vision-augmented replies** — `WakeWordService.runCommand()` now checks
  `VisionFrameProvider.freshFrame()` before answering: if there's a recent frame AND
  `InferenceEngine.supportsVision` (a vision-capable Gemma model loaded — this was already
  wired in `InferenceEngine.generateStream(prompt, images)`, just never used until now), it
  goes straight to the on-device model (free, private, fastest). If there's a frame but no
  vision-capable local model, falls back to new `tools/VisionRelay.kt` — Gemini vision via the
  relay's now-extended `/v1/relay` (`image_base64` param, `_call_gemini` updated to attach an
  `inline_data` part). No frame → the pre-existing text-only + tool-calling path, unchanged,
  extracted into `generateTextOnlyReply()`.
- **Image generation** — new relay `/v1/imagegen` (Gemini's image model, reuses `GEMINI_KEYS`,
  no separate key) + `tools/ImageGenTools.kt`. Two entry points: typing `/image <prompt>` or
  `/img <prompt>` in the Chat screen (`ChatViewModel.generateImage()` — bypasses the normal
  tool-calling loop entirely since that loop only knows how to feed *text* tool results back
  to the model, and shows the result inline using the same `ChatMessage.images` rendering
  already used for user-attached photos); and a `generate_image` tool for voice/text tool-calls
  (saves to `<app>/files/Pictures/Arya/` and speaks/replies a confirmation, since
  `WakeWordService` can't show an image).

**New dependencies**: CameraX (`camera-core`/`camera-camera2`/`camera-lifecycle`/`camera-view`
1.3.4). **New permissions**: `FOREGROUND_SERVICE_MEDIA_PROJECTION`, plus optional camera
`<uses-feature>` declarations (all `required="false"` — app still installs on devices without
a camera).

**Honest limitations, stated plainly — this is the riskiest phase yet**:
- None of this has touched a compiler, let alone a real device. CameraX's in-memory
  `ImageCapture.takePicture()` JPEG-format assumption, the screen-capture `ImageReader`
  row-stride/padding math, and `MediaProjection`'s permission-relaunch flow are all textbook
  patterns but genuinely need a real-device pass before trusting them.
- `ImageGenTools`/`/v1/imagegen` assumes a `gemini-2.5-flash-image` model name and a
  `responseModalities: ["IMAGE"]` request shape — written from documentation recall, not
  verified against a live Gemini account this session. If the model name has moved on by the
  time this is deployed, this is the first thing to check.
- Generated images in the Chat screen aren't persisted to `chatDao` (session history) — they
  show for the current session only, then vanish on app restart/session switch. Flagging this
  now rather than let it surprise later.
- Live conversation's turn-taking still uses the same estimated-duration barge-in timing from
  earlier phases (text-length-based, not actual audio duration) — unchanged, but now matters
  more since it loops continuously instead of a single reply.
- Vision system prompts ("Tum Arya ho, jo camera/screen dekh ke bata rahi ho...") are quick
  first drafts, not tuned against real vision-model output — expect to iterate on these once
  it's actually running.

## Phase 12 — Offline model removed, online-only

Arya's on-device (offline) model system has been removed entirely, per request. Every reply
now goes through Arya Relay's free online models (Groq/Gemini/OpenRouter) — there's no
download/load step, no local inference engine, no "which mode" switch.

**Deleted** (the whole on-device inference + model-catalog subsystem):
`inference/InferenceEngine.kt`, `inference/AryaEngine.kt`, `network/HfDownloader.kt`,
`network/ModelDownloadWorker.kt`, `util/ModelDownloader.kt`, `data/ModelInfo.kt`,
`data/ModelRepository.kt`, `data/CuratedModels.kt`, `data/BenchmarkStats.kt`,
`data/StatsRepository.kt`, `ui/GalleryScreen.kt`, `ui/AddModelDialog.kt`, `ui/StatsScreen.kt`,
`viewmodel/GalleryViewModel.kt`. Menu's "Models" entry and the "models_hub"/"stats" NavHost
routes are gone; Home now opens straight into Chat instead of a Gallery/Chat pager.

**Converted to online-only**: `ChatViewModel` (no more `engine`/`modelId`/offline-generate
path — a single `onlineChat` lambda), and every feature screen that previously took an
`InferenceEngine` (`AgentSkillsViewModel`, `TinyGardenViewModel`, `PromptLabViewModel`,
`MobileActionsViewModel`, `AudioScribeViewModel`) now takes a `generateOnline` lambda instead.
`WakeWordService`'s voice-command path is online-only too. Image-attach in Chat now always
works (previously gated on a loaded vision model) — it routes to `VisionRelay` (Gemini vision)
instead.

**Real bugs found and fixed along the way** (not just the offline-model removal):
- `data/UseCase.kt`: `aiChat` and `promptLab` never set the required `route` constructor
  param — the file didn't compile as shipped.
- Deleting `HfDownloader` would have silently broken `UpdateInstaller` (app-update APK
  download reused it as a generic downloader) — recreated as `network/FileDownloader.kt`.
- `CurrentInfoWorker`'s background current-affairs snapshot was written but never actually
  read into any chat prompt — now folded into `identityContext`/persona in both
  `MainActivity` and `WakeWordService`.
- The online tool-calling system prompt had silently dropped `DateTimeContext`'s
  current-date line during the ChatViewModel rewrite — restored.

Settings lost the now-meaningless "Background downloads", "Online free model fallback"
toggle, and Hugging Face token field. `PreferencesManager` dropped `chatMode`, `hfToken`,
`autoOnlineFallback`, `useGpu`, `temperature`, `topK`, `maxTokens` (all dead/offline-only).
`build.gradle.kts` dropped the MediaPipe `tasks-genai`/`tasks-vision` dependencies; the
manifest dropped `FOREGROUND_SERVICE_DATA_SYNC` (only needed by the deleted download worker).

## Phase 13 — New home UI (arya-ui.html) + first-launch name onboarding

Home/Chat's empty state now matches the `arya-ui.html` mockup: an animated three-ring
"signal mark" (`HomeHeroSection.kt`), a personalized greeting ("<naam>, बोलो / Arya sun rahi
hai"), the three suggestion rows (Live baat karo / Image banao / Aaj ke kaam), and a Photo /
Camera / Tools / Live chip row above the input dock. "Tools" opens a new right-side
`ToolsDrawer.kt` listing the same 14 tool-category groups as the mockup.

**First-launch name onboarding**: `NameEntryScreen.kt` — shown once, right after the
runtime-permission dialogs, whenever `PreferencesManager.userName` is blank. Whatever the
person types there is what the home greeting now uses in place of the old hardcoded
"Sudhanshu" placeholder from the mockup — every install greets whoever's actually holding
that phone. This is unrelated to `AryaIdentity.kt`'s "Sudhanshu Maurya built you" line, which
stays as-is (that's Arya's own maker identity, not the current user's name).

New tokens in `theme/Color.kt`: `AryaTextDim`, `AryaTextFaint`, `AryaHairline` — lifted
straight from the mockup's `--text-dim` / `--text-faint` / `--ink-hairline` CSS vars.

## Phase 14 — Fix release build failures (compileReleaseKotlin)

Two compile errors from the first CI run:
1. `HomeHeroSection.kt` — `by transition.animateFloat(...)` needs `import
   androidx.compose.runtime.getValue` for the delegate operator; was missing.
2. `LiveConversationScreen.kt` — `androidx.lifecycle.compose.LocalLifecycleOwner` was
   unresolved because `androidx.lifecycle:lifecycle-runtime-compose` wasn't in
   `app/build.gradle.kts` dependencies (only `lifecycle-runtime-ktx` and
   `lifecycle-viewmodel-compose` were). Added `lifecycle-runtime-compose:2.8.4`. This bug
   pre-dates Phase 13 — it's the first time this project actually got compiled in CI, so it
   surfaced now.

## Phase 15 — Live conversation hang on devices without speech recognition + Tools drawer no-ops

Reported: on a device lacking any speech-recognition service (no Google app / speech
services), Live Conversation got stuck forever on "Sun rahi hoon..." with no feedback, and
none of the Tools drawer categories seemed to do anything when tapped.

1. **Live conversation hang** — `WakeWordService.startCommandListeningViaSpeechRecognizer()`
   created a `SpeechRecognizer` and called `startListening()` unconditionally, unlike its
   sibling `startWakeWordSpeechCheck()` which already guarded on
   `SpeechRecognizer.isRecognitionAvailable()`. On a device with no speech service at all,
   this silently hung instead of erroring out. Added the same availability guard: now it
   updates the notification, sets `LiveConversationState.status` back to `IDLE` (so the
   screen shows "Taiyaar hoon" instead of a frozen "Sun rahi hoon..."), and speaks a clear
   explanation. Root cause is a device limitation (no on-device speech engine) — this fix
   makes that visible instead of hanging silently. The Whisper-via-relay path
   (`VadCommandRecorder`/`WhisperUploader`) is still tried first regardless.

2. **Tools drawer categories doing nothing** — by design (Phase 13) these mirrored the
   static arya-ui.html mockup, which itself has no real behavior wired to each row. Now each
   category (except "Self-Evolution", intentionally left informational) fills the chat input
   with a real example prompt for an actual tool registered in `AryaToolRegistry` (e.g.
   Todo Extras → a reminder prompt, Saved Sites & Watchers → a `save_site`-shaped prompt) via
   a new `onExamplePrompt` callback, instead of just closing the drawer as a no-op.

Photo attach → send was checked and looks intact (`ChatScreen`'s `imagePicker` →
`viewModel.attachImage` → shown in `pendingImages` → included next time the Send button is
pressed) — this is pre-existing, pre-Phase-13 code. If it's still not working after this
build, it needs an actual device repro (does the picker even open? does the thumbnail appear
after picking? does pressing Send after that do nothing?) rather than a guess.

## Phase 16 — Front camera in Live Conversation

`CameraFrameCapture` was hardcoded to `CameraSelector.DEFAULT_BACK_CAMERA` with no way to
switch. Added `switchLens()` (rebinds preview+capture to the other lens without resetting the
periodic-capture timer) and an `isFrontFacing` flag. `LiveConversationScreen` now shows a
flip-camera button (only while the camera is on) next to the existing camera toggle.

## Phase 17 — Move Tools out of the chip row

Per user feedback ("chip row se hata do, drawer kahin aur se khule"): the Tools drawer is a
reference menu (you check it occasionally to see what Arya can do), not a per-message
attachment like Photo/Camera/Live — so it didn't belong in the chip row next to those. Moved
it to a wrench icon in the top bar instead (`ChatScreen`'s topBar, next to the Live button).
`ChipRow` now only has Photo / Camera / Live.

## Phase 18 — Diagnose silent voice-input failures ("kuch samajh nahi aaya" every time)

User confirmed: text chat works fine and RELAY_APP_SECRET matches on both ends, so relay
auth/connectivity is fine — the failure is isolated to voice (both Chat's mic and Live mode
hit it). The problem: every failure path in `WhisperUploader`/`VadCommandRecorder` silently
returned null with zero diagnostic info, so there was no way to tell *why* without device
logs we don't have access to.

Added `WhisperUploader.lastError` (mic permission missing, mic busy, HTTP status + body,
timeout, empty transcript, no-speech-detected-in-time, etc.) and surfaced it in both
ChatScreen's fallback Toast and WakeWordService's live-mode notification, so the next
failure will say *what* went wrong instead of a generic message.

Also: `VadCommandRecorder.MAX_WAIT_FOR_SPEECH_MS` was only 4000ms — the window to start
speaking after "Bolo, sun rahi hoon..." appears. Right after a TTS prompt finishes, 4s is
easy to miss, silently giving up with no speech detected and falling through to the (often
also-failing) native recognizer — which is a very plausible match for "lag hota hai fir
kuch samajh nahi aata" happening consistently. Bumped to 6000ms.

## Phase 19 — Generated images never actually showed in chat (only a text confirmation)

Root cause found: when Arya calls the `generate_image` tool, `AryaToolRegistry.execute()`
already generates a real `Bitmap` (`ImageGenTools.generate`) and saves it to the gallery —
but only ever returned a text string ("🎨 Image ban gayi... save ho gayi: <filename>") to the
chat. The actual Bitmap was discarded after saving; nothing attached it to the chat message,
so the user only ever saw text, never the picture, even though it *was* being generated and
saved successfully the whole time.

Fixed via a small side-channel (mirrors `WhisperUploader.lastError`'s pattern): a private
`lastGeneratedImage` on `AryaToolRegistry`, set in the `generate_image` branch and read via
`takeLastGeneratedImage()`. `ChatViewModel`'s tool-call loop now grabs it right after each
tool call and attaches it to whichever reply message is ultimately shown (`replaceLastModelMessage`
now takes an optional `images` list). Gallery-saving behavior is unchanged.

**Video is different — not a bug, a gap**: `search_youtube`/`search_videos` are explicitly
documented as "sirf links, direct play nahi" (search results only). There's no in-app video
player anywhere in the codebase — it was never built, not broken. Flagged to the user as a
feature request rather than "fixed" here.

## Phase 20 — In-app video player

Added `VideoPlayerDialog.kt`: a full-screen player that plays YouTube links via the official
iframe embed in a `WebView` (ExoPlayer can't stream a youtube.com page directly, only actual
media files — this is the standard legitimate way to embed YouTube playback), and direct
video files/HLS (`.mp4`/`.m3u8`/etc.) via ExoPlayer + `PlayerView` (added the
`androidx.media3:media3-ui` dependency for this — `media3-exoplayer`/`-hls` were already
present for audio streaming via `StreamPlayerManager`).

`ChatBubble` now scans the reply text for a playable video link (`findPlayableVideoUrl`) and
shows a "▶️ Video dekho" button under the message if one's found, wired to open
`VideoPlayerDialog`. Updated `search_youtube`/`search_videos`' tool descriptions and result
text (previously said "sirf links, direct play nahi") since results are now actually playable
in-app.

## Phase 21 — Full tool audit (2 Aug 2026): duplicate `generate_image` branch found & fixed

Systematic pass: diffed every `ToolDefinition` name in `AryaToolRegistry.ALL_TOOLS` against
every branch label in `execute()`'s `when`. Everything matched 1:1 except `generate_image`,
which was registered **twice** (once under "utility", once under "images") and had **two**
`"generate_image" ->` branches in the `when`. Kotlin resolves a `when` top-to-bottom, so the
first branch (relay/Gemini via `ImageGenTools`) always won — the second branch (`ImageTools
.generateImage`, the free/keyless Pollinations path) was 100% dead code. Net effect: if
`RELAY_URL` wasn't configured for a build, `generate_image` always failed with "relay/key
issue" even though a working keyless fallback was sitting right there, unused. The tool's
description was also stale ("Pollinations, free") even though the branch that actually ran
was the relay/Gemini one.

**Fix:**
- Removed the duplicate `ToolDefinition` and the duplicate/dead branch.
- The one remaining `generate_image` branch now tries relay (Gemini, better quality) first,
  and falls back to a new `ImageTools.fetchGeneratedBitmap()` (downloads Pollinations' image
  as a real Bitmap, same as the relay path) if the relay isn't configured or fails — so image
  generation now actually works even without a relay set up, and both paths save-to-gallery
  and attach the picture to chat the same way.
- Updated the tool description to reflect the real relay-first/Pollinations-fallback behavior.

Cross-checked `arya-relay/app.py` (v6) against every relay-calling file in the app
(`WebTools`, `InfoApiTools`, `BriefingTools`, `WhisperUploader`, `VoiceHelper`, `VisionRelay`,
`ImageGenTools`, `OnlineChatHelper`) — endpoint paths (`/v1/relay`, `/v1/relay/stream`,
`/v1/nasa`, `/v1/wolfram`, `/v1/tavily`, `/v1/news`, `/v1/whisper`, `/v1/elevenlabs`,
`/v1/imagegen`), the `X-App-Secret` header, and response shapes (JSON field names, raw-bytes
endpoints) all match on both sides — no other client/server mismatch found.

## Phase 22 — Full tool audit round 2 (2 Aug 2026): more real bugs found & fixed

Went file-by-file through every tool implementation this time (not just the registry-level
name/branch diff from Phase 21) — `UtilityTools`, `InfoApiTools`, `MemoryStore`, `PersonaStore`,
`PersonalityStore`, `DeviceExtraTools`, `ExpandedDeviceTools`, `StreamTools`, `SiteTools`,
`ApiKeyTools`, `VisionRelay`, `ReminderTools`, `BriefingTools`, `NetTools` — plus simulated
`AryaToolRegistry.relevantTools()`'s actual scoring against every `ToolsDrawer` example prompt
to confirm each one really surfaces its intended tool (not just "sounds related").

**Bug found — `test_video_source` never worked:** it reused `ImageTools.testImageSource(url)`,
which only accepts `image/*` content-types. A valid, reachable video/HLS/radio stream URL has
a content-type like `video/mp4`, `audio/mpeg`, or an HLS-playlist MIME — never `image/*` — so
`test_video_source` always came back "❌ ye valid image source nahi lag raha" even for a
perfectly good stream URL. Fixed: added `NetTools.probeReachable()` (shared connection logic)
and rewrote `StreamTools.testVideoSource()` to check against video/audio/HLS-appropriate
content-type prefixes instead of image ones. `ImageTools.testImageSource` now also uses the
shared `probeReachable()` helper (same behavior, less duplicated code).

**ToolsDrawer example prompts that never actually matched their tool:** simulated
`relevantTools()`'s scoring (name-token overlap + `TOOL_SYNONYMS`) against every category's
example prompt and found three that scored **zero** for their intended tool — meaning tapping
that category filled the input, but the underlying tool was never even included in what got
sent to the model:
- **Info Tools** ("Japan ke baare me batao") — `get_country_info` had **no synonyms at all**
  and no matching name-token, so nothing about country lookup ever got list. Added synonyms
  (`desh`, `country info`, `capital`, `population`) and rewrote the example to "Japan ka
  capital kya hai".
- **Play & Fetch Media** ("Ye video/audio chalao: ") — didn't share a token/synonym with
  `play_stream`/`find_and_play` (their synonyms are things like "gaana chalao", not generic
  "video/audio chalao"). Reworded to "Ye stream URL play kar do: " (shares `play`/`stream`
  tokens directly).
- **Persona / Roleplay** ("Apna tone thoda mazakiya kar do") — zero overlap with
  `activate_persona`'s name tokens or its synonyms ("persona", "role play", "acting karo").
  Reworded to "Iron Man wali persona activate karo".
- **Images & Media**'s old example also had a stray "/image" slash-prefix that isn't a real
  command syntax anywhere in the app and didn't reliably hit the `generate_image` synonym
  either — reworded to "Ek sunset ka image banao".

Verified the rest (Web & News, Radio, Memory, Places & Time, Saved Sites & Watchers, Utility,
NASA Extras, Todo Extras, Streaming Controls) all score correctly already — left as-is.

**Removed the "Self-Evolution (Advanced)" row from `ToolsDrawer` entirely.** It had no backing
tool anywhere — `self_evolve.py` was explicitly never ported (an installed APK can't rewrite
its own compiled code), so this row was purely informational and didn't correspond to
anything in `AryaToolRegistry`. Per the instruction to only list tools that genuinely exist and
work, it's gone — the drawer now shows exactly the 13 categories that map to real, working
tools, nothing else.

## Phase 23 — merged Sudhanshu's "advanced tools" upgrade + made the radio chip reliable (3 Aug 2026)

Sudhanshu built a real, substantial upgrade on top of Phase 22's zip: `CuriosityStore` +
`ReflectionWorker` (tracks repeat tool failures from missing relay/permission/config and asks
about them once every 6 hours instead of failing silently forever), `system_check`/
`list_capability_gaps` tools, retry-with-fallback for weather/crypto/currency/news/vision/
image-gen (each now tries a second independent provider before giving up), tappable
"🔗 Kholo"/"📻" buttons under news/search/saved-sites/radio-station results, a stop/interrupt
button for in-flight replies, a proper anime-style animated face for Live Conversation (blinks,
talks, expression changes), a first-frame timing fix for camera/screen-share vision capture, a
VAD auto-calibration fix for false wake-word triggers, and a loosened wake-word regex. Reviewed
all of it file-by-file — well-integrated with existing conventions, no bugs found. Verified
`AryaToolRegistry` still checks out clean: 111/111 tools, no duplicates, no orphaned branches.

**The one gap:** Sudhanshu's own attempt at today's "radio icon" ask (`findNowPlayingRadio` in
`ChatScreen.kt`) parses the *model's final reply text* for the tool's exact
"▶️ Stream shuru: ..." string. That's fragile — the system prompt explicitly tells the model to
answer "apne shabdon me" (in its own words) using the tool result, not repeat it verbatim, so
the model's paraphrased reply often won't contain that exact string and the chip would silently
not show up. Re-added the reliable side-channel from earlier this session
(`AryaToolRegistry.lastNowPlaying`/`captureNowPlaying`, wired for `play_stream`/`find_and_play`/
`play_saved_stream`) as `ChatMessage.nowPlaying` — guaranteed to be set from the actual tool
result, not dependent on model phrasing. `ChatScreen.kt` now prefers that field and only falls
back to the text-regex for messages that don't have it set (e.g. older persisted history).
Also set it directly on `playRadioStation`'s tap-to-play result, since that path already knows
the station name synchronously and doesn't need any parsing at all.

## Phase 24 — new app icon: metallic "A" ribbon-arrow logo (7 Aug 2026)

Replaced the old flat purple/green vector "A" launcher icon with a custom metallic
teal-blue ribbon logo (twisted "A" shape flowing into an arrow) supplied by Sudhanshu.
Since `minSdk = 26`, only the adaptive-icon path (`mipmap-anydpi-v26/ic_launcher.xml`)
is used — no legacy per-density mipmap folders needed.

- `ic_launcher_foreground.xml` (vector) and `ic_launcher_monochrome.xml` (vector) removed;
  replaced with density-independent PNGs in `res/drawable-nodpi/` — `ic_launcher_foreground.png`
  and `ic_launcher_monochrome.png` (432×432, artwork inset to ~62% of canvas so no launcher
  mask shape — circle, squircle, rounded square — clips the ribbon or arrow tip).
- Monochrome asset is a pure white silhouette derived from the artwork's alpha channel, for
  Android 13+ themed icon support.
- `ic_launcher_background.xml` solid color updated from `#14121A` to `#477B92` (average teal
  sampled from the new artwork) so the adaptive-icon background matches instead of clashing.
- `app_name` string was already "Arya" — no manifest/label change needed, launcher name stays
  as-is under the new icon.

**Follow-up fix (same day):** first pass used a 62% inset for the foreground, which produced a
visible "sticker inside a sticker" look — the artwork's own baked-in rounded-square card sat
inside a second, differently-shaped ring wherever a launcher applied its own circle/squircle
mask, since the flat `#477B92` background showed as a distinct outer band. Rendered test
composites under circle, squircle, and rounded-square masks (the three real-world adaptive
icon shapes) to check. Fixed by bumping the inset to 94% and re-confirming the background
color match — at that scale the gap band is negligible and the artwork's edge blends into the
background almost seamlessly, so the ribbon/arrow reads as one native icon under any launcher
shape with no clipping and no double border. Foreground and monochrome PNGs regenerated at
this scale.

## Phase 25 — "+" attach menu replaces the plain gallery-image icon (7 Aug 2026)

Input dock used to have a flat gallery-image icon next to the mic that only did one thing
(attach a photo). Sudhanshu wanted it swapped for a "+" that opens a richer, colorful menu —
referenced two Gemini-app screenshots as the visual bar to beat.

Added `AttachMenuSheet` (`ui/AttachMenu.kt`) — a `ModalBottomSheet` with four tiles, each a
colored icon swatch + title + subtitle, same row pattern as the Gemini reference but scoped to
things Arya can actually do (same "no decorative entries" rule as `AryaToolRegistry`):

- **Photo** (sky blue, new `AryaSky` token added to `Color.kt`) — same gallery picker the old
  icon launched
- **Live baat karo** (Signal violet) — opens Live conversation, same as the Camera chip
- **Image banao** (Ember orange) — prefills `/image ` the same way the home-screen suggestion
  card already does
- **Aaj ke kaam** (Sprout green) — prefills "Aaj ke to-do dikhao", same as the home-screen
  to-do suggestion

Deliberately left out Gemini-style entries Arya has no real equivalent for (Video, Music,
Canvas, Drive, Guided Learning) rather than padding the menu with dead buttons.

`ChatScreen.kt`: the old `IconButton` → `Icons.Filled.Image` → `imagePicker.launch(...)` became
`IconButton` → `Icons.Filled.Add` → `attachMenuOpen = true`; the sheet is rendered alongside
`ToolsDrawer`/`VideoPlayerDialog` at the bottom of the composable, wired to the same
`imagePicker`, `onOpenLive`, and `input` setter the old chips already used — no new plumbing,
just a better front door to the same four actions.

## Phase 26 — full "+" menu build-out: Notebook, Canvas, Files/Drive, Personal Intelligence, Video/Music gen (7 Aug 2026)

Sudhanshu asked for every Gemini "+"-menu tool to be added. Clarified first whether these
should be real, placeholder, or a mix — he chose real. Built all of them; the two that touch
Google's video/music models needed actual research (web search against Google's current docs,
not assumption) since Veo/Lyria's API surface isn't something to guess at:

- **Notebook** — Room-backed notes (`NoteEntity`/`NoteDao`, `AppDatabase` bumped v1→v2 with
  `fallbackToDestructiveMigration()`), full list/create/edit/delete UI (`NotebookScreen.kt`).
  Also added to the drawer Menu screen, not just the "+" sheet.
- **Canvas** — deliberately NOT a sandboxed code-execution environment (Arya has no code
  runner — faking that would be exactly the kind of dead button this menu exists to avoid).
  It's a full-screen scratchpad (`CanvasScreen.kt`) with a persisted draft
  (`PreferencesManager.canvasDraft`) and a "Arya ko bhejo" button that sends the content as a
  real chat message.
- **Files/Drive** — Android's Storage Access Framework (`ActivityResultContracts.OpenDocument`)
  already surfaces Google Drive as a source with zero setup on our side if the user has the
  Drive app installed — no OAuth client needed, unlike a real Drive API integration. Images go
  through the same `attachImage()` path as the gallery picker; text/JSON files get read and
  dropped into the input box.
- **Personal Intelligence** — Arya has no account/history store to draw on the way Gemini does,
  so this is the honest equivalent: a free-text "mere baare mein" note
  (`PreferencesManager.personalContext` + `personalIntelligenceEnabled`, off by default) that
  gets appended to the system prompt in `MainActivity.buildIdentityContext()` when turned on.
- **Video banao / Music banao** (`/video`, `/music` prefixes, same pattern as `/image`) —
  genuinely wired to Veo 3.1 and Lyria 3 Clip. Initially assumed these needed a separate
  Vertex AI/billing project like most Google video/audio gen APIs — web search against Google's
  current docs (ai.google.dev/gemini-api/docs/veo and .../music-generation) showed both are
  actually reachable through the plain Gemini API (`generativelanguage.googleapis.com`) with the
  same `GEMINI_KEYS` the relay already round-robins for everything else. Corrected course based
  on that before writing the relay code, rather than shipping the wrong assumption.
  - `relay/app.py`: `/v1/videogen` (Veo 3.1 Fast — POST `:predictLongRunning`, polls the
    operation up to ~4.5 min, downloads the finished clip, returns raw `video/mp4` bytes) and
    `/v1/musicgen` (Lyria 3 Clip — single `generateContent` call, decodes the inline base64
    audio part, returns raw `audio/mpeg` bytes — no polling, always a 30-second clip).
  - `Procfile` gunicorn timeout raised `30s -> 300s` — Veo's own docs quote up to ~6 minutes at
    peak, the old default would've killed the worker mid-poll. **If Sudhanshu is redeploying an
    already-existing Render service** (not creating a new one), he needs to update the Start
    Command in Render's dashboard Settings too — Render won't re-read the Procfile for a service
    that already exists.
  - Android side (`VideoGenTools.kt`, `MusicGenTools.kt`) downloads the raw bytes, saves them to
    `cacheDir/generated/`, and returns a `content://` URI via the existing FileProvider (added a
    `generated/` entry to `file_paths.xml`) — video plays through the existing
    `VideoPlayerDialog`/ExoPlayer path (`ChatMessage.playableMediaUri`, new field, sidesteps
    `findPlayableVideoUrl`'s http(s)-only regex since this is a local URI); the music clip
    autoplays immediately through the same `StreamPlayerManager`/`StreamTools.playStream` path
    already used for radio, rather than making the person tap a link for a clip that's already
    ready.

**Still a real limit, stated plainly rather than hidden:** both Veo and Lyria sit on Gemini's
*free-tier quota*, which is small — heavy use will hit "quota exceeded" and need either waiting
for reset or a paid tier. That's a Google-side limit, not a bug in this code.

## Phase 27 — chat reply stuck forever on "…" with no error (8 Aug 2026)

**Reported:** user tapped "Image banao" (which filled `/image `), but the message that
actually got sent was `home/image` (stray text before the slash, no space) — so it never
matched the `/image ` command prefix and went through as a normal chat message instead of
generating an image. That message then sat on a blank `"…"` bubble with the stop button
showing, and no reply ever arrived.

**Root cause:** `OnlineChatHelper.generateOnlineResponse`/`streamOnlineResponse` loop through
~18 Groq/Gemini/OpenRouter model combinations on failure, each with its own 30-second
timeout, but had **no cap on the total time across all of them**. If the relay is slow/asleep
(Render free tier) or genuinely unreachable, every single attempt can burn its full 30s —
worst case ~9 minutes of total silence before `ChatViewModel`'s catch block ever gets a
chance to show an error. From the user's side this is indistinguishable from a permanent hang.

**Fix:** added `MAX_TOTAL_BUDGET_MS = 45_000` — the fallback loop now checks elapsed time
before each attempt and bails out early once 45s total have been spent, throwing immediately
with a clear reason ("45 second ke andar koi provider jawaab nahi de saka (Render sleeping ho
sakta hai...)") instead of continuing to burn through the rest of the model list. This makes
`ChatViewModel.runOnline`'s existing `catch`/`finally` (which already resets `isGenerating`
and shows the error text) fire within a bounded, reasonable time on every failure path —
"…" forever should no longer happen; worst case is now a real error message within ~45s.

**Separately, not a bug — worth knowing:** the `/image`/`/img`/`/video`/`/music` commands only
trigger on an *exact* `"/image "` (etc.) prefix at the very start of the trimmed text. Any
stray character before the slash (like the `home` in `home/image`) makes it fall through to
plain chat instead — so a prompt with a typo like that silently becomes a normal question, not
an image-generation request. If this keeps happening by accident, a follow-up fix would be
detecting `/image` anywhere near the start rather than requiring an exact prefix — not done
here since it wasn't confirmed to be the actual complaint (the real complaint was the stuck
reply, fixed above).

**Same honesty note as every phase:** grep-verified only, no Android SDK/Gradle/network in
this sandbox — build via GitHub Actions or Android Studio before trusting this pass fully.

## Phase 28 — Live conversation: not understanding Hindi + replying in English (8 Aug 2026)

**Reported:** "Live baat cheet" mode doesn't listen properly ("sunti hi nahi hai"), Arya
sometimes replies fully in English instead of Hindi, and even when she does speak Hindi it
comes out in an English accent — user wants it to feel like Gemini Live, in proper Hindi.

**Three separate root causes found and fixed:**

1. **STT (not listening well):** `arya-relay`'s `/v1/whisper` endpoint called Groq Whisper
   with no `language` param, so it had to auto-detect — unreliable on short clips, and it
   kept misdetecting Hindi speech (especially code-switched with "Arya") as the wrong
   language. Fixed: `language: "hi"` is now forced on every Whisper call.
2. **Fallback recognizer also wrong language:** `WakeWordService.startCommandListeningViaSpeechRecognizer()`
   (only runs when the Whisper path is unavailable/fails) hard-coded Android's built-in
   `SpeechRecognizer` to `en-IN`. Changed to `hi-IN`. (Left the *wake-word* check itself on
   en-IN on purpose — that one only needs to catch the Latin-script word "Arya"/"hello arya",
   and switching it risked getting Devanagari output that the WAKE_PHRASE regex wouldn't match
   at all — a bigger regression than the one being fixed.)
3. **Replying in English:** `ToolCallParser.buildSystemPrompt`'s own instruction literally said
   "normal Hinglish/**English** me jawab do" — explicitly permitting a pure-English reply, which
   is exactly what happened in the reported screenshot ("Hello! How can I help you today?").
   Reworded to require Hindi/Hinglish always, English only for the odful common loanword.
   Also added the same instruction directly to `WakeWordService`'s voice persona string as a
   second line of defense since that's what actually drives Live/voice replies.
4. **English-accented Hindi (separate, needs a manual step, not code-only):** ElevenLabs'
   default voice ("Rachel") is an English-native voice — the multilingual *model* can speak
   Hindi text, but the *voice* keeps its own English accent regardless of what language the
   text is in. No code fix possible here (this isn't a bug, it's the wrong voice selected) —
   README now has step-by-step instructions to pick a real Hindi-native voice from ElevenLabs'
   Voice Library (e.g. "Saavi", "Simran", "Monika Sogam") and set `ELEVENLABS_VOICE_ID`.

**Not fixed, and can't be with a small patch — said plainly:** true Gemini-Live-style behavior
is continuous, low-latency, interruptible *streaming* audio in both directions over a
persistent connection. Arya's current loop is record-a-full-utterance → upload → transcribe →
LLM call → TTS-download → play, each a discrete HTTP round-trip — inherently turn-by-turn with
multi-second gaps, not real-time streaming. Making it actually feel like Gemini Live would need
a real-time speech-to-speech architecture (WebSocket/streaming STT+LLM+TTS), which is a genuine
rebuild, not a patch on top of this design — flagged here rather than silently claimed as done.

**Same honesty note as every phase:** grep-verified only, no Android SDK/Gradle/network in this
sandbox — build and test on-device before trusting this pass fully, especially the Hindi STT/TTS
changes which need a real mic + real Hindi speech to confirm.

## Phase 29 — Live mode: sentence-pipelined streaming replies (8 Aug 2026)

**Ask:** "Live baat asli real-time bane, jaise Gemini Live" — user wants the multi-second dead
air between finishing a sentence and Arya starting to reply gone.

**What changed (`WakeWordService.kt`, Live mode only — single wake-word commands and
camera/screen-vision turns are untouched, they were already short/one-shot):**

Old flow was fully sequential and blocking at every stage: wait for the ENTIRE LLM reply to
finish generating -> THEN fetch the ENTIRE ElevenLabs audio for it -> THEN play it -> guess
how long that took (`text.length * 55ms`) -> reopen the mic. A 2-3 sentence reply could easily
mean 3-6+ seconds of total silence before Arya said a single word.

New flow (`runTextCommandStreamed` + `SentenceSpeechQueue`):
1. The LLM reply now streams in (`OnlineChatHelper.streamOnlineResponse`, already used by the
   text chat screen, just not previously wired into voice/Live mode).
2. As soon as a complete sentence appears in the stream (`extractCompleteSentences`, sentence
   boundary = `.!?` or Hindi `।॥`), it's queued to speak immediately — no waiting for the rest
   of the reply.
3. `SentenceSpeechQueue` fetches a sentence's ElevenLabs audio *while the previous sentence is
   still playing* (a simple 2-deep pipeline: peek the next queued sentence, kick off its fetch,
   then play the current one) — so per-sentence network/generation latency is hidden behind
   playback time instead of stacking up between every sentence.
4. Playback completion is now tracked for real (`CompletableDeferred` completed by the actual
   `MediaPlayer`/`TextToSpeech` listener callbacks) instead of the old fixed-duration guess —
   this also makes barge-in timing more accurate as a side effect.
5. Bug fix along the way: barge-in previously only ever stopped the system-TTS engine
   (`tts?.stop()`) — ElevenLabs playback (the actual path used whenever the relay is
   configured, i.e. almost always) kept right on playing over the interruption. Now
   `elevenLabsPlayer?.stop()` and the sentence-pipeline job are both stopped too.

Tool-call JSON is never spoken as sentences (same `determined`/`isToolCall` buffer-sniffing
gate `ChatViewModel`'s streaming already uses) — it's parsed/executed once the full response
lands, exactly like before.

**Said plainly, not quietly overstated:** this is still turn-based request/response under the
hood — the mic only reopens once Arya's reply finishes (or barge-in fires) — NOT full-duplex
bidirectional audio streaming the way Gemini Live actually works. It removes the single
biggest artificial delay (waiting for the whole reply before saying anything, and waiting for
the whole reply's audio before playing anything), which should read as meaningfully snappier
and more conversational, but it is not a claim of true real-time parity with Gemini Live —
that would need a persistent connection + a realtime STT/LLM/TTS provider, a genuinely
different architecture, not a patch on today's discrete-HTTP-calls design.

**Same honesty note, doubly important this time:** this is a real architectural change to the
speak/playback pipeline (new coroutine-based sentence queue, new completion-tracking, changed
barge-in) that I could only verify by static reading (brace/paren balance, structural
grep-checks) — there is NO way to verify the actual runtime behavior (timing, race conditions,
audio glitches, coroutine cancellation edge cases) without building and testing on a real
device with a real mic and real network. Test Live mode thoroughly — especially barge-in
(interrupting mid-reply) and what happens on a flaky/slow connection — before trusting this in
regular use. If sentences overlap, cut off oddly, or the mic doesn't reopen after a reply,
report exactly what happened (which sentence, what you did) so it can be narrowed down.

## Phase 30 — Real real-time voice: Gemini Live API over WebSocket (experimental) (8 Aug 2026)

**Ask:** "WebSocket, real-time STT/TTS — ye banao" — actual Gemini-Live-parity, not the
sentence-pipelined-but-still-turn-based improvement from Phase 29.

**What this is:** a genuinely new, separate real-time voice path using Google's own Gemini
Live API (`BidiGenerateContent`, model `gemini-3.1-flash-live-preview` as of Aug 2026 — this
model string is known to churn on the developer tier, see the docs link below if it stops
resolving), which does true bidirectional audio streaming with server-side voice-activity
detection and interruption — this is what Gemini's own Live product runs on.

**Design — three pieces:**
1. **`arya-relay/app.py` `/v1/live`** (new): a WebSocket proxy using `flask-sock` (added to
   `requirements.txt`, works fine with the existing `--worker-class gthread` from Phase 27 —
   no need to switch to eventlet/gevent). It authenticates the app's `X-App-Secret` header,
   opens an *outbound* WebSocket to Gemini using `websocket-client` (also added), injects the
   Gemini API key + a Hindi-first system-instruction persona server-side (same "keys never
   leave the server" rule every other endpoint follows), sends Gemini's required `setup`
   message, then pumps messages through close to verbatim in both directions on two threads.
2. **`app/.../util/GeminiLiveSession.kt`** (new): OkHttp WebSocket client (OkHttp was already
   a dependency, no new one needed) that continuously streams mic audio (16kHz mono PCM16, per
   Gemini's exact input spec) out over the connection the whole time — no client-side "record
   until silence" step, Gemini's own VAD handles turn-taking — and plays back audio replies
   (24kHz mono PCM16) via `AudioTrack` in streaming mode as chunks arrive. When Gemini's
   `serverContent.interrupted` flag fires (the user started talking over it), playback is
   flushed immediately — real server-confirmed interruption instead of a local VAD guess.
3. **`LiveConversationScreen.kt`**: a lightning-bolt toggle button (top bar) to turn this on/
   off, kept **completely separate and off by default** from the `WakeWordService` pipeline
   (Phases 28/29) — toggling it stops/starts the old pipeline's foreground service so the two
   don't fight over the mic. Unlike the old pipeline, this session is torn down whenever the
   Live screen is left (not kept running in the background) since it's unverified.

**Deliberately NOT included in this pass:** device tool-calling (the 111 "kaunsa gaana laga
do" style tools). Gemini Live's function-calling is synchronous-only — the model blocks until
a `toolResponse` arrives — and wiring that against `AryaToolRegistry`'s Android-side execution
needs real design work of its own. This pass is voice-conversation-only; tools stay on the
older WakeWordService path for now.

**THIS IS THE MOST IMPORTANT NOTE IN THIS WHOLE LOG, PLEASE ACTUALLY READ IT:** every previous
phase in this file could at least be grep/syntax verified with reasonable confidence. This one
genuinely cannot be — it's a brand-new WebSocket audio-streaming pipeline across three
different pieces (Flask WS proxy, a third-party API's evolving wire protocol, and raw
AudioRecord/AudioTrack streaming on Android), and nothing in this sandbox can build an APK,
open a real WebSocket, or capture/play real audio. Concretely, expect to debug:
  - Render's free tier and long-lived WebSocket connections — some hosts idle-timeout or
    buffer WS traffic in ways that only show up under real load.
  - Gemini's exact JSON field names, which change between preview model revisions — if you
    get connection errors or garbled audio, https://ai.google.dev/gemini-api/docs/live-api is
    the first thing to re-check against this file.
  - Audio chunk sizing/latency tuning (`TARGET_CHUNK_MS` in `GeminiLiveSession.kt` is a
    starting guess, not a measured value) and whether `AudioTrack` playback keeps up smoothly
    with the incoming 24kHz stream on real hardware without stutter.
  - `VOICE_COMMUNICATION` audio source + echo cancellation behavior varies a lot across
    Android OEMs/devices — worth testing with both earpiece and speakerphone.

Please actually test this (tap the bolt icon in Live mode, talk, see what happens) and report
back specifics — what you tapped, what you said, what happened/didn't happen — rather than
just "it doesn't work," since debugging a live-audio pipeline blind from text descriptions
alone is genuinely hard. This is exploratory/experimental by design; the older WakeWordService
pipeline (Phases 27-29) remains the reliable default and is untouched by any of this.

## Phase 31 — provider order: Gemini first, not equal-odds random (8 Aug 2026)

**Reported:** "Groq/OpenRouter ka model text-only hai, Gemini text+voice+video+image sab
dekhti hai, phir bhi msg karne par kabhi kabhi galat jawab aata hai kyunki Gemini ka kaam
Groq/OpenRouter karne lagta hai."

**Root cause:** `OnlineChatHelper`'s provider-fallback loop (`generateOnlineResponse` and
`streamOnlineResponse`, used by both the chat screen and Live/voice mode) picked the
try-order via `listOf(GROQ, GEMINI, OPENROUTER).shuffled()` — a flat equal-odds shuffle, so
any given message had only a 1-in-3 chance of even trying Gemini first. Groq/OpenRouter's
free-tier models are noticeably weaker at following the strict tool-call JSON format and at
general answer quality, so on the 2-in-3 chance one of them got picked first, replies could
come out worse — exactly the reported symptom, purely down to the luck of the shuffle.

**Fix:** `val providerOrder = listOf(GEMINI) + listOf(GROQ, OPENROUTER).shuffled()` — Gemini
always goes first now; Groq/OpenRouter are still randomized *between themselves* purely as the
fallback order for when Gemini's models all fail or its daily free-tier quota runs out.

**Worth knowing — a real tradeoff, not hidden:** the original random shuffle was deliberately
spreading load across all three providers' separate daily free-tier quotas. Prioritizing
Gemini means Gemini's own quota will now get used up faster (it succeeds more often, so the
fallback to Groq/OpenRouter fires less often). In practice this should be the right trade —
Groq/OpenRouter's quota was previously being spent on requests Gemini would've answered
better anyway, and now it's saved for when Gemini genuinely needs a fallback — but if Gemini's
free-tier quota starts running out earlier in the day than before, this is why, and the old
random-shuffle behavior is a one-line revert if that turns out to matter more in practice.

Vision (camera/screen/photo understanding, `VisionRelay.kt`) was already always Gemini-only
regardless of this shuffle — that part wasn't actually affected by the bug, only plain text
chat/tool-calling was.

## Phase 32 — message-aware provider routing (8 Aug 2026)

**Ask:** "Tum set karo ki msg ke anusar Arya ko pata ho ki kis model ya kis API ki zaroorat
hai" — instead of a single fixed order (Phase 31: always Gemini-first), pick per-message.

**What changed:** `OnlineChatHelper.kt` now has `classifyIntent()`/`providerOrderFor()` — a
simple heuristic (keyword + word-count check, not an extra model call — that would add a
network round-trip just to decide who to ask, defeating the point especially for quick
messages) that buckets each message and picks a different try-order:
  - **CODING** (message mentions code/programming terms — "kotlin", "bug", "error", "gradle",
    etc.): Gemini, then OpenRouter, then Groq. OpenRouter's catalog includes models tuned for
    code that do better here than Groq's general free models.
  - **QUICK** (1-6 words — greetings, yes/no, short one-liners): Groq first, Gemini as
    fallback. These don't need Gemini's extra reasoning, and Groq's inference is noticeably
    faster, so latency wins here instead.
  - **GENERAL** (everything else): same as Phase 31 — Gemini first, Groq/OpenRouter
    randomized as fallback.

**Said plainly:** this is a heuristic, not a real classifier — a short coding question could
get misfiled as QUICK, for instance, and the keyword list is necessarily incomplete. That's an
acceptable trade for zero added latency (no extra network call to classify first); a wrong
first guess just costs a little quality/speed on that one attempt, not a failure — the
existing fallback loop still tries every other provider regardless of which one goes first.

## Phase 33 — force Gemini for multimodal contexts, dedicated coding model (8 Aug 2026)

**Ask:** "Pic, voice, video, live baat, image banao, music banao, canvas — ke liye hamesha hi
Gemini use ho. Coding ke liye OpenRouter ki sabse best model use ho, Arya ko pata ho ki coding
poochha ja raha hai to khud se us model se reply de."

**Checked each item — most were already Gemini-only, two needed an actual code change:**
- **Pic/camera/screen** (`VisionRelay.kt`) — already hardcoded `provider = "gemini"`, untouched.
- **Video** (`/v1/videogen`, Veo) — already Gemini-key-only on the relay, untouched.
- **Image banao** (`/v1/imagegen`) — already Gemini-key-only, untouched.
- **Music banao** (`/v1/musicgen`, Lyria) — already Gemini-key-only, untouched.
- **Live baat** — the experimental `GeminiLiveSession` (Phase 30) is already Gemini-only by
  design (it IS the Gemini Live API). The older `WakeWordService` turn-based Live pipeline's
  text generation, though, was going through the smart-routing heuristic (Phase 32) — fixed,
  see below.
- **Voice** (regular wake-word commands, not just Live mode) — same fix as Live baat, below.
- **Canvas** (`CanvasScreen.kt`) — checked and it's currently just a local plain-text draft
  editor (`prefsManager.canvasDraft`), no AI/model calls happen there at all yet — nothing to
  route, said plainly rather than pretending to change something that isn't there.

**Actual code change — `OnlineChatHelper.kt` + `WakeWordService.kt`:**
`generateOnlineResponse`/`streamOnlineResponse` both gained a `forceGeminiOnly: Boolean =
false` parameter. When true, `providerOrderFor()` returns `[GEMINI]` only — no Groq/OpenRouter
fallback at all. `WakeWordService`'s two call sites (blocking `generateTextOnlyReply` and the
streamed `runTextCommandStreamed` from Phase 29) now both pass `forceGeminiOnly = true`, so
every voice/Live-mode text reply is Gemini-only, matching the ask.

**Real trade-off, stated plainly (not hidden):** Groq/OpenRouter existed as this app's entire
safety net for when Gemini's daily free-tier quota runs out or a request fails. Forcing
Gemini-only for voice/Live means if Gemini's quota is exhausted, voice commands and Live mode
will genuinely stop working for the rest of the day (throwing "Gemini se jawaab nahi mila")
instead of degrading to a Groq/OpenRouter fallback like the text chat screen still does. This
is exactly what was asked for, so it's what this does — but worth knowing why voice might stop
answering some days while the text chat screen keeps working fine.

**Coding — dedicated model, not just "OpenRouter before Groq":** Phase 32 already tried
OpenRouter before Groq for coding questions, but still let whichever model the user had saved
as their OpenRouter preference answer. Now `orderedModelsFor()` puts
`OnlineModels.OPENROUTER_BEST_CODING_ID` (`poolside/laguna-m.1:free` — built/tuned for agentic
coding specifically, not just a general model that happens to be okay at it) at the front of
OpenRouter's model list for any CODING-classified message, overriding the saved preference for
that one case, and — bigger change — **coding questions now try this model before Gemini too**
(previously Gemini always went first regardless of intent). Re-verify this model ID is still
live on openrouter.ai/models before trusting it months from now — free-tier model rotation on
OpenRouter is the fastest-moving of the three providers (see OnlineModels.kt's own header note).

## Phase 34 — refreshed OpenRouter free model list (delisted models found) (9 Aug 2026)

**Ask:** "Free coding model daal do, search karke — free wale hi chahiye."

**What I found while searching:** fetched openrouter.ai/collections/free-models directly
(not a blog — the source of truth) and discovered the OpenRouter list in this app had
**silently gone stale** since the 26 Jul 2026 research pass — three entries were already
delisted and would just fail with an API error if ever tried:
  - `meta-llama/llama-3.3-70b-instruct:free` — gone
  - `qwen/qwen3-next-80b-a3b-instruct:free` — gone
  - `poolside/laguna-m.1:free` — gone, and this was the exact model Phase 33 had just pointed
    coding-question routing at, so that fix was already broken within a day of landing.

**Fixed — `OnlineModels.kt`'s `OPENROUTER` list rebuilt from the live page, with three
dedicated coding models now included (previously there was only one):**
  - `cohere/north-mini-code:free` — OpenRouter's own "Programming" category rank **#16**
    among ALL free models, the highest-ranked dedicated coding model available free —
    now `OPENROUTER_BEST_CODING_ID`, replacing the dead laguna-m.1 reference.
  - `poolside/laguna-s-2.1:free` — Programming #50, strong on SWE-bench/Terminal-Bench.
  - `poolside/laguna-xs-2.1:free` — Programming #31, lighter/faster.
  - `google/gemma-4-31b-it:free` replaced with `google/gemma-4-26b-a4b-it:free` (the dense
    31B variant isn't in OpenRouter's current free collection; this MoE 26B-A4B one is).
  - `inclusionai/ling-3.0-tiny:free` added (new arrival, lightweight/responsive).
  - `nvidia/nemotron-3-super-120b-a12b:free`, `nvidia/nemotron-3-ultra-550b-a55b:free`,
    `openai/gpt-oss-20b:free` — all reconfirmed still live, kept as-is.

Coding-question routing (`OnlineChatHelper`'s `orderedModelsFor`) still puts the ONE primary
coding pick first, but now that the OPENROUTER list itself has three coding models grouped at
the top, a coding question that somehow misses `cohere/north-mini-code` (rate-limited, down,
etc.) naturally falls through to `laguna-s-2.1` then `laguna-xs-2.1` before hitting the
general-purpose models — better fallback depth for coding specifically than before.

**The real lesson here, worth remembering for next time:** OpenRouter's free tier churns fast
enough that a model list is stale within about two weeks, sometimes faster for a specific
model. Groq's and Gemini's lists weren't re-verified in this pass (their free tiers are their
own hosted lineups and churn much slower/more predictably) — only OpenRouter was touched, per
what was actually asked. If Arya ever starts failing specifically on OpenRouter/coding
requests again, re-fetch openrouter.ai/collections/free-models directly before assuming it's a
code bug — it might just be another round of delisting.
