# rostdav-mfa Android app

Native Android app for rostdav-mfa push approval (spec phases 2 to 4). The app talks to a Rostdav server over the contract in `../contract`.

## Screenshots

Pictures 01 to 03, 06 and 08 were taken on the emulator against a real Rostdav. The other screens block screen capture (`FLAG_SECURE`: Approve, the Overview while the vault is unlocked, and the add, backup and restore screens), so their pictures are rendered from the real composables with sample data: Approve by `ScreenshotsTest`, the vault screens and Settings by `VaultScreenshotsTest`. The vault pictures use made-up services and public test secrets. `VaultScreenshotsTest` is opt-in (it is skipped unless the instrumentation argument `screenshots` is `true`); its class comment has the commands.

| Screen | Dark | Light |
| --- | --- | --- |
| Connect to your server | ![Connect to your server, dark](../media/01-onboarding-dark.png) | ![Connect to your server, light](../media/01-onboarding-light.png) |
| Enter by hand | ![Enter by hand, dark](../media/02-manual-entry-dark.png) | ![Enter by hand, light](../media/02-manual-entry-light.png) |
| Name this phone | ![Name this phone, dark](../media/03-name-phone-dark.png) | ![Name this phone, light](../media/03-name-phone-light.png) |
| Overview, no requests, vault locked | ![Overview, no requests, vault locked, dark](../media/04-overview-empty-dark.png) | ![Overview, no requests, vault locked, light](../media/04-overview-empty-light.png) |
| Overview with a request and accounts | ![Overview with a request and accounts, dark](../media/05-overview-request-dark.png) | ![Overview with a request and accounts, light](../media/05-overview-request-light.png) |
| Requests | ![Requests, dark](../media/06-requests-dark.png) | ![Requests, light](../media/06-requests-light.png) |
| Settings | ![Settings, dark](../media/07-settings-dark.png) | ![Settings, light](../media/07-settings-light.png) |
| Devices | ![Devices, dark](../media/08-devices-dark.png) | ![Devices, light](../media/08-devices-light.png) |
| Approve | ![Approve, dark](../media/09-approve-dark.png) | ![Approve, light](../media/09-approve-light.png) |
| Approved | ![Approved, dark](../media/10-approved-dark.png) | ![Approved, light](../media/10-approved-light.png) |
| Accounts with live codes and the backup reminder | ![Accounts with live codes and the backup reminder, dark](../media/11-accounts-dark.png) | ![Accounts with live codes and the backup reminder, light](../media/11-accounts-light.png) |
| Add account: check the names and the first code | ![Add account: check the names and the first code, dark](../media/12-add-account-dark.png) | ![Add account: check the names and the first code, light](../media/12-add-account-light.png) |
| Backup | ![Backup, dark](../media/13-backup-dark.png) | ![Backup, light](../media/13-backup-light.png) |

## Modules

- `:contract` is a plain JVM module with the byte-exact logic (canonical strings, request signing, signature encoding). Its tests read the shared vectors `../contract/vectors/v1.json`, the same file the Rust tests use.
- `:vault` is the vault core, a plain JVM module: TOTP and HOTP, otpauth URIs, the encrypted seed store, the unlocked session with auto-lock and the passphrase-encrypted backup. Argon2id comes from Bouncy Castle. `docs/vault.md` has the formats, the rules and the screens.
- `:app` is the Android application, package `app.rustmfa.android`, with the flavors `nopush` (default) and `fcm`. The vault screens are in `ui/vault`, the view models and Android parts of the vault in `vault`.

## Build

Use a JDK that is not GraalVM. The `jlink` of GraalVM CE fails inside the Android Gradle plugin with `Module jdk.internal.vm.ci not found`. Temurin 21 works:

    export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.13.0+9-tem
    cd android
    ./gradlew :contract:test
    ./gradlew :app:assembleNopushDebug

`local.properties` (git-ignored) must set `sdk.dir`.

## Test

    ./gradlew :contract:test :vault:test :app:testNopushDebugUnitTest :app:lintNopushDebug

The emulator tests need a running device (the debug build allows cleartext HTTP for the test server):

    ./gradlew :app:connectedNopushDebugAndroidTest

An emulator that restarted asks for its PIN again; until it is unlocked, instrumentation fails with "not encryption aware".

## Run on the emulator against a Rostdav

`docs/e2e.md` has the server configuration (`attestation = "off"`, `public_url = "http://10.0.2.2:<port>"`), the commands and the results of the run, and the checklist for a physical phone.

Continuous integration (`../.github/workflows/android.yml`) runs the unit tests, lint, the `nopush` build and the contract tests of both languages on every push. It does not run the emulator tests.

## Push

The default `nopush` build needs no Google file and no Google service. It registers with the channel `none` and an empty token, and finds new requests by polling while the app is open (every 15 seconds, every 3 seconds while a login is awaited). Notifications are posted for requests the poller sees.

The `fcm` flavor adds Firebase Cloud Messaging. It builds only against your own Firebase project: put its `google-services.json` in `android/app/` (the file is git-ignored and never committed), then run `./gradlew :app:assembleFcmDebug`. A push carries only a challenge id; the phone fetches the details through the signed request. If the phone gets no token, it registers with `none` and polls.

## Versions

Chosen from the registries on 2026-11-10: Gradle 9.9.1, Android Gradle plugin 9.5.1, Kotlin 2.4.21, Compose BOM 2026.09.00, compileSdk and targetSdk 37, JVM target 17.
