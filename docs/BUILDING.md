# Building SmartTube VOX

The public [README](../README.md) covers installation and use. These build notes preserve the project-specific instructions from the previous README.

## Requirements

- JDK 17
- Android SDK with Build-Tools 30.0.3 and SDK Platform 34
- Git

Build a debug APK with `./gradlew assembleStvotDebug`. The result is written to `smarttubetv/build/outputs/apk/stvot/debug/`.

With a local `keystore.properties` configured, build a signed release with `./gradlew assembleStvotRelease`.

The `stvot` package ID is `io.github.kiryuhak.smarttubevot.stable`. Its versionCode follows `baseVersionCode * 1000 + votPatch`, with the patch in the range 1–999.

The project's GitHub Actions workflow checks pull requests and builds debug variants. Maintainers review upstream changes and control release publication.
