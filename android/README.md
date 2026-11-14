# RustMFA Android app

Native Android app for RustMFA push approval (spec phases 2 to 4). The app talks to a Rostdav server over the contract in `../contract`.

## Modules

- `:contract` is a plain JVM module with the byte-exact logic (canonical strings, request signing, signature encoding). Its tests read the shared vectors `../contract/vectors/v1.json`, the same file the Rust tests use.
- `:app` is the Android application, package `app.rustmfa.android`, with the flavors `nopush` (default) and `fcm`.

## Build

Use a JDK that is not GraalVM. The `jlink` of GraalVM CE fails inside the Android Gradle plugin with `Module jdk.internal.vm.ci not found`. Temurin 21 works:

    export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.13.0+9-tem
    cd android
    ./gradlew :contract:test
    ./gradlew :app:assembleNopushDebug

`local.properties` (git-ignored) must set `sdk.dir`.

## Push

The default `nopush` build needs no Google file and no Google service. It registers with the channel `none` and an empty token, and finds new requests by polling while the app is open (every 15 seconds, every 3 seconds while a login is awaited). Notifications are posted for requests the poller sees.

The `fcm` flavor adds Firebase Cloud Messaging. It builds only against your own Firebase project: put its `google-services.json` in `android/app/` (the file is git-ignored and never committed), then run `./gradlew :app:assembleFcmDebug`. A push carries only a challenge id; the phone fetches the details through the signed request. If the phone gets no token, it registers with `none` and polls.

## Versions

Chosen from the registries on 2026-11-10: Gradle 9.9.1, Android Gradle plugin 9.5.1, Kotlin 2.4.21, Compose BOM 2026.09.00, compileSdk and targetSdk 37, JVM target 17.
