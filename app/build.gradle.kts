plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

// Firebase (Community/usage stats + optional chat-data sync — see FIXES_LOG.md Phase 10).
// Applied conditionally: without a real app/google-services.json (downloaded from your own
// Firebase console project — this sandbox has no network to create one for you), applying
// this plugin unconditionally would hard-fail every build, including CI, for anyone who
// hasn't set it up yet. Drop your google-services.json in this folder and it activates
// automatically on the next build — no other change needed.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.arya.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arya.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"

        // Arya Relay — lets every install use online chat (Groq/Gemini/OpenRouter)
        // without any per-user API key setup. The actual provider keys live only on
        // the relay server (Render env vars); nothing secret is ever committed here.
        // Locally: export ARYA_RELAY_URL / ARYA_RELAY_APP_SECRET before building.
        // In CI: set the same as GitHub Actions secrets (see .github/workflows/build.yml).
        buildConfigField("String", "RELAY_URL", "\"${System.getenv("ARYA_RELAY_URL") ?: ""}\"")
        buildConfigField("String", "RELAY_APP_SECRET", "\"${System.getenv("ARYA_RELAY_APP_SECRET") ?: ""}\"")
    }

    signingConfigs {
        // Release signing reads from env vars only — nothing is ever hardcoded or committed.
        // Locally: export ARYA_KEYSTORE_PATH / ARYA_KEYSTORE_PASSWORD / ARYA_KEY_ALIAS / ARYA_KEY_PASSWORD
        // before running `./gradlew assembleRelease`. In CI: set the same as GitHub Actions secrets
        // (see .github/workflows/build.yml's release job).
        create("release") {
            val keystorePath = System.getenv("ARYA_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ARYA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ARYA_KEY_ALIAS")
                keyPassword = System.getenv("ARYA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only actually sign if the env vars above were present, so a plain local
            // `assembleRelease` without secrets still succeeds (just produces an unsigned APK)
            // instead of hard-failing the build.
            if (System.getenv("ARYA_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
        // Firebase's play-services-measurement (pulled in by firebase-analytics) ships
        // .kotlin_module metadata compiled with a newer Kotlin than this project's 1.9.24,
        // which otherwise hard-fails compileDebugKotlin with "Module was compiled with an
        // incompatible version of Kotlin". This flag tells the compiler to trust it anyway
        // (safe here — we only use Firebase's public Java/Kotlin-interop API surface).
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose (Arya UI)
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AppCompatDelegate for the dark-mode toggle — everything else classic-View-only was
    // removed in the Phase 1 refactor (see FIXES_LOG.md #8).
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Media3 ExoPlayer — backs the new streaming subsystem (play_stream/pause_stream/...),
    // has built-in HLS (.m3u8) support so no external player binary is needed.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    // MediaSessionService — gives streaming proper background/lock-screen playback with an
    // automatic system media notification (Phase 2 fix; was previously in-process-only,
    // stopping the moment the app backgrounded — see FIXES_LOG.md).
    implementation("androidx.media3:media3-session:1.4.1")

    // CameraX — live camera frames for the "Live Conversation" screen's vision mode
    // (see LiveConversationScreen.kt / CameraFrameCapture.kt, FIXES_LOG.md #11)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Networking — used by NetTools, UpdateInstaller's downloader, and update checks
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okio:okio:3.9.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Encrypted SharedPreferences (API keys, remembered facts, personas) — Keystore-backed
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Room — chat history / multiple sessions persistence
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Markwon — render Markdown (bold, code blocks, lists) in model responses
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")

    // Background downloads that survive app backgrounding/process death
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Glide — show picked images in chat (multimodal input)
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // PDF text extraction — lets RAG document import accept .pdf, not just .txt
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Arya's device/data tools: QR code generation (generate_qr) and HTML parsing (web_search, scrape_webpage)
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jsoup:jsoup:1.17.2")

    // Picovoice Porcupine — optional, dedicated "Hey Arya" wake-word engine (Option A,
    // far less battery than looping SpeechRecognizer). Only used if the user saves a free
    // Picovoice AccessKey (Settings -> API Keys) AND drops their trained hey-arya_android.ppn
    // into app/src/main/assets/ — otherwise WakeWordService falls back to the built-in VAD
    // approach (Option B), which needs neither.
    implementation("ai.picovoice:porcupine-android:3.0.3")

    // Unit tests (JVM-only, no Android framework needed for these)
    testImplementation("junit:junit:4.13.2")

    // Firebase — see FIXES_LOG.md Phase 10. BoM pins every Firebase library to mutually
    // compatible versions, so individual artifacts below don't need their own version number.
    implementation(platform("com.google.firebase:firebase-bom:34.15.0"))
    // Automatic install/DAU/geography counting — no code needed beyond the SDK being present;
    // Firebase Console's Analytics tab shows "kaha kaha se log jude hain" (country/city
    // breakdown) on its own. Also used for a few custom events (message_sent, tool used).
    implementation("com.google.firebase:firebase-analytics")
    // Realtime Database — backs the live "kitne online hain abhi" presence counter (Analytics'
    // DAU number has ~24h latency, not "right now") and the opt-in chat-content sync.
    implementation("com.google.firebase:firebase-database")
}
