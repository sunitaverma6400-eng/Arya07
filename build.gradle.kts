// Top-level build file
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Firebase's Gradle plugin — reads app/google-services.json at build time and generates
    // the resources FirebaseApp.initializeApp() needs. Declared here (apply false) and only
    // actually applied in app/build.gradle.kts *if that file exists* — same "gated, don't
    // break the build for people who haven't set it up" stance as the release-signing config.
    id("com.google.gms.google-services") version "4.5.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
