# Arya — Step 1: Build System Fix

## Fixed
- Added `gradle/wrapper/gradle-wrapper.jar`.
- Replaced the previous placeholder `gradlew` bootstrap with a functional wrapper launcher.
- Added a matching Windows launcher in `gradlew.bat`.
- The wrapper reads the pinned Gradle version from `gradle/wrapper/gradle-wrapper.properties`.
- Gradle 8.7 is cached under the user's Gradle directory after the first successful download.

## Verification
The wrapper JAR was compiled and its main class was executed successfully up to the distribution-download stage. The sandbox has no outbound network access, so the Gradle 8.7 distribution could not be downloaded here. This is an environment limitation, not an Android source-code build result.

## How to verify on a normal machine
From `arya-app`:

```bash
./gradlew --version
./gradlew test
./gradlew assembleDebug
```

On Windows use `gradlew.bat`.

## Important
This project uses a small self-contained bootstrap wrapper because the original ZIP did not contain Gradle's standard wrapper JAR. It is intentionally pinned to the distribution URL already present in `gradle-wrapper.properties`.
