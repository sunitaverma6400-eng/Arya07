# Jarvis — Native Android App

Yeh tumhare Flask-based Jarvis (`jarvis_v11_cleaned.zip`) ka native Android
app version hai. Koi Termux, koi Render — sab kuch isi APK ke andar,
isi phone par chalta hai.

## Kaam kaise karta hai

- **Chaquopy** ek Python interpreter ko Android app ke andar embed karta hai.
- Tumhara `server.py` (Flask app) waise ka waisa hi hai — bas `android_start.py`
  (naya file) isko ek background thread mein `127.0.0.1:5000` par start karta hai.
- `MainActivity` ek `WebView` hai jo `http://127.0.0.1:5000` load karta hai —
  wahi purana chat UI, ab local se serve ho raha hai.
- `JarvisService` ek **foreground service** hai jo Python/Flask ko zinda
  rakhta hai. `BootReceiver` phone boot hone par, aur `NetworkReceiver`
  internet ON hone par, isi service ko start kar dete hain — matlab "net on
  karte hi Jarvis chalu" wali requirement poori.
- `PermissionsActivity` app khulte hi ek hi screen se saari permissions
  (calls, SMS, mic, camera, location, notifications, storage) maang leta hai.
- `native_android.py` (naya file) Termux:API commands (`termux-sms-send`,
  `termux-torch`, etc.) ki jagah seedha Android system APIs call karta hai
  (SmsManager, CameraManager, Vibrator, LocationManager, etc.) — `tools.py`
  mein `_dispatch_phone_tool()` ab pehle isi ko try karta hai.

## GitHub par push karke APK banwana (CI)

1. Is poore folder (`JarvisAndroid/`) ko apne GitHub repo mein push karo
   (root mein — `build.gradle`, `settings.gradle`, `app/` sab sath).
2. `main` ya `master` branch par push hote hi `.github/workflows/build-apk.yml`
   khud chalega — GitHub ke servers par build hoga (tumhare phone par kuch
   install nahi karna).
3. Repo ke **Actions** tab mein jaake latest run kholo → **Artifacts** mein
   `jarvis-debug-apk` milega, download karo.
4. Ya phir repo ke **Releases** tab mein bhi APK mil jayega (workflow khud
   ek pre-release bana deta hai har build ke baad).
5. APK ko phone par download karke install karo (Settings → "Install
   unknown apps" us browser/file-manager ke liye allow karna hoga).

Manually bhi chala sakte ho: Actions tab → "Build Jarvis APK" → **Run workflow**.

## API keys

App pehli baar chalne par tumhara Groq/Gemini/OpenRouter key maangega
(jaise pehle chat mein "Jarvis code api: groq gsk_xxxx" bolke deta tha) —
yeh `memory.py`/TinyDB mein app ke andar hi (`/data/data/com.jarvis.assistant/`)
save hota hai, koi cloud nahi.

## Known limitations (v1) — inko phase-2 mein dekhna hai

- **Mic recording** (`voice.py` ka `termux-microphone-record`) abhi native
  nahi hai — TTS (bolna) kaam karega, lekin "sun ke samajhna" (voice input)
  ke liye Android `AudioRecord`/`SpeechRecognizer` bridge alag se banana
  padega. Filhaal text chat + TTS output pura kaam karega.
- **SMS/Call/Torch/Vibrate/Battery/Location** — native bridge (`native_android.py`)
  ban chuka hai, lekin maine khud device par test nahi kiya (yahan Android
  device nahi hai) — pehli install ke baad in sabko ek-ek karke try karke
  bata dena, jo bhi permission-edge-case miss hui use fix kar denge.
- Kuch pip packages (`cloudscraper`, `yt-dlp`, `pydub`) Chaquopy ke build
  environment mein pure-Python hone ke bawajood kabhi kabhi compile-time
  dependency issues de sakte hain — agar CI build fail ho, error log mujhe
  bhejna, us package ko adjust kar denge.
- `pydub` ko असल mein audio convert karne ke liye `ffmpeg` binary chahiye
  hoti hai jo abhi bundle nahi kiya — sirf TTS (`edge-tts`) ke liye zaroori
  nahi hai, isliye v1 mein skip kiya hai.

## Project structure

```
JarvisAndroid/
├── build.gradle, settings.gradle, gradle.properties   # root Gradle config
├── app/
│   ├── build.gradle                 # Chaquopy pip packages yahan defined hain
│   ├── src/main/
│   │   ├── AndroidManifest.xml      # saari permissions + receivers/services
│   │   ├── java/com/jarvis/assistant/
│   │   │   ├── JarvisApp.kt         # notification channels
│   │   │   ├── PermissionsActivity.kt
│   │   │   ├── MainActivity.kt      # WebView (chat UI)
│   │   │   ├── JarvisService.kt     # foreground service, Python starter
│   │   │   ├── BootReceiver.kt      # boot par auto-start
│   │   │   └── NetworkReceiver.kt   # net-on par auto-start
│   │   ├── python/                  # tumhara poora Jarvis code (adapted)
│   │   │   ├── android_start.py     # NAYA — Flask ko thread mein start karta hai
│   │   │   ├── native_android.py    # NAYA — Termux ki jagah native Android APIs
│   │   │   ├── server.py, brain.py, tools.py, memory.py, ... (waise ke waise)
│   │   │   └── static/, templates/
│   │   └── res/                     # layouts, theme, icons
└── .github/workflows/build-apk.yml  # CI — auto APK build + release
```

## Agla step (agar chaho)

- Mic input (real-time voice command) ke liye native bridge
- App icon ko tumhare `static/icon.svg` (Iron Man design) se match karna
  (abhi ek simple placeholder arc-reactor icon hai)
- Release/signed APK (Play Store ya sideload ke liye permanent keystore)
