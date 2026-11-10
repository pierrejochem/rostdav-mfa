# RustMFA Android App, Part 1 (Plan 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Android app that enrolls with a Rostdav server by QR code, receives sign-in requests (push or polling), and approves or denies them with a biometric-gated, hardware-backed key, using number matching.

**Architecture:** One Gradle project in `android/` of the `rostdav-mfa` repository. A pure Kotlin/JVM module `:contract` holds everything that must match the Rust contract byte for byte (canonical message, request signing string, DER to raw, vector tests). The `:app` module (Kotlin, Compose, Material 3) holds the Keystore keys, the API client, the challenge state machine, the push channel and the UI. The server side exists (Rostdav PR 13).

**Tech Stack:** Kotlin, Jetpack Compose with Material 3, min SDK 28, Gradle with a version catalog, OkHttp and kotlinx.serialization, AndroidX Biometric, DataStore, CameraX with zxing-core for the QR scan, MockWebServer and JUnit for tests. Firebase Cloud Messaging only in an optional product flavor.

**Spec:** `docs/superpowers/specs/2026-11-08-rustmfa-android-design.md` (sections 2.1, 3, 4, 6, 7, phases 2 to 4)
**Contract:** `contract/openapi/rustmfa-v1.json` (same content as the YAML), `contract/vectors/v1.json` and its README
**Server:** Rostdav, `github.com/pierrejochem/rostdav`, branch `feat/push-mfa` (PR 13): `docs/api.md`, `docs/adr/0014-push-mfa.md`
**Out of this plan (later plans):** TOTP vault, QR/manual/import of third-party accounts and the encrypted export (spec phase 5), hardening and attestation-policy work beyond the items named below (phase 6), UnifiedPush (phase 7), release and store work (phase 8).

## Global Constraints

Values copied from the spec and the contract; every task includes them.

- Kotlin, Jetpack Compose, Material 3, **min SDK 28**. Rost palette: accent `#D2691E`, light and dark. App texts in English (default) and German (`values-de`); the spec's German strings are the German texts: "Abgelaufen", "Bereits bearbeitet", "Gerät neu verbinden".
- Two Keystore keys, both EC P-256: `request_key` (not user-authenticated, signs every request) and `approval_key` (`setUserAuthenticationRequired(true)`, used only through `BiometricPrompt` with a `CryptoObject`, invalidated by new biometric enrollment, StrongBox when available). Public keys travel as base64 (standard) of X.509 SPKI DER.
- Signatures are raw 64-byte R‖S, base64url without padding. DER is never sent; Android's DER output is converted.
- Request header: `Authorization: RustMFA-Sig device_id="…",ts="…",nonce="…",sig="…"`. `sig` signs `METHOD \n PATH-with-query \n ts \n nonce \n base64url(sha256(body))`. `ts` is Unix seconds, accepted within 60 s of the server clock. A fresh nonce per request.
- Canonical challenge message, signed by `approval_key` on approve and on deny: `v1 \n challenge_id \n nonce \n login_id \n device_id \n expires_at \n audience`. `expires_at` is exactly `YYYY-MM-DDTHH:MM:SSZ`, signed verbatim as the server sent it.
- Attestation: the chain is for `approval_key`, leaf first, base64 DER; the attestation challenge is the SHA-256 of the enroll token (the token's UTF-8 bytes).
- QR payload: `rustmfa://enroll?server=<percent-encoded base URL>&token=<token>`.
- Errors: `{"error":{"code","message"}}` with the codes `INVALID_REQUEST`, `UNAUTHENTICATED`, `DEVICE_NOT_AUTHORIZED`, `DEVICE_REVOKED`, `RESOURCE_NOT_FOUND`, `CHALLENGE_ALREADY_CONSUMED`, `CHALLENGE_NUMBER_MISMATCH`, `CHALLENGE_EXPIRED`, `RATE_LIMITED`, `INTERNAL_ERROR`.
- Number matching: `GET` returns three candidate numbers; the pick goes in `selected_number` of the approve request; a wrong pick comes back as `CHALLENGE_NUMBER_MISMATCH` and the challenge is denied. Deny needs no number.
- Release builds talk to `https://` servers only. Debug builds may use `http://` for the emulator's `10.0.2.2`.
- No keys, tokens, signatures, push tokens or challenge numbers in logs. `android:allowBackup="false"` and data-extraction rules that exclude everything. `FLAG_SECURE` on the Approve screen. A signed answer that could not be sent is kept in memory only and never written to disk.
- Push messages carry a `challenge_id` only; the app always fetches the details through the signed GET. A push never authorizes anything.
- Work in `android/` in `/home/jochem/Repositories/rostdav-mfa`. The tools on this machine: JDK 25 (GraalVM) via SDKMAN, Android SDK in `~/Android/Sdk` with platform `android-37.0`, build-tools 36.0.0, emulator and a `android-35` google_apis system image, no Gradle on the path (the wrapper comes with Task 1).

**Rulings this plan makes on top of the spec:**
- The app lives in `android/` of the contract repository so the vectors run in Kotlin tests straight from `contract/vectors/v1.json`.
- The app name and package are `RustMFA` and `app.rustmfa.android`.
- A pure JVM module `:contract` holds the byte-exact logic, so its tests run without an emulator.
- A wrong number is found out by the server, not by the app (the app never knows the right number): the sequence is pick, biometric, signed approve, answer `CHALLENGE_NUMBER_MISMATCH`, screen "Anfrage abgelehnt".
- The QR scanner uses CameraX and zxing-core (no Google ML Kit), so a store-free flavor stays possible.
- Two product flavors: `nopush` (default, polling only) and `fcm` (Firebase; needs a `google-services.json` that is never committed). The default build must work without any Google service.
- The phone clock is corrected with the `Date` header of the server's answers, because a request more than 60 s off is rejected as `UNAUTHENTICATED`.

## Review Focus

- The biometric key is invalidated (new fingerprint enrolled) before or during an approval: no crash, no stale approve, the "Gerät neu verbinden" path (Tasks 3, 6, 8).
- Approve sent twice (double tap, or a retry after a network error): the second answer is `CHALLENGE_ALREADY_CONSUMED`, shown as "Bereits bearbeitet", never a retry loop; a signed answer is not retried after `expires_at` (Task 6).
- The phone clock is wrong by minutes: requests fail as `UNAUTHENTICATED`; the app corrects its clock from the server's `Date` header and tries once more, then shows a clear hint (Task 2).
- A push for a challenge that is unknown, expired or already answered: no crash, no notification left behind (Task 7).
- A hostile QR code (`http://` in a release build, `javascript:` or another scheme, a token with control characters or 10 000 characters, a server address with credentials): refused before any network call (Task 4).
- The network fails while the signed approve is in flight: the answer stays in memory only, and killing the app leaves nothing on disk (Task 6).

---

### Task 1: Project skeleton and the vectors in the test classpath

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle/libs.versions.toml`, `android/gradle.properties`, `android/contract/build.gradle.kts`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/kotlin/app/rustmfa/android/MainActivity.kt`, `android/README.md`, the Gradle wrapper files, `android/.gitignore`
- Test: `android/contract/src/test/kotlin/app/rustmfa/contract/VectorsLoadTest.kt`

**Interfaces:**
- Produces: modules `:contract` (JVM library, package `app.rustmfa.contract`) and `:app` (Android application, package `app.rustmfa.android`, flavors `nopush` and `fcm`); a test helper `Vectors.load(): VectorFile` in `:contract` tests that reads `../../contract/vectors/v1.json` (path from the Gradle property or `System.getProperty("vectors.path")` set in `build.gradle.kts`); `VectorFile(version: Int, key: Key, challengeCases: List<ChallengeCase>, requestCases: List<RequestCase>)`.

- [ ] **Step 1: Write the failing test** `VectorsLoadTest`: `Vectors.load().version == 1`, 11 challenge cases, 2 request cases, `key.spkiDerB64` not empty.
- [ ] **Step 2: Create the project.** Pick the newest stable Gradle, Android Gradle plugin and Kotlin versions that work with the installed JDK 25 and platform `android-37.0`; check their release notes and compatibility tables instead of guessing, record the choice and why in `android/README.md`. `compileSdk` is the installed platform, `minSdk = 28`, `targetSdk` the same as `compileSdk`. Add the wrapper with `gradle wrapper`. The `:app` manifest sets `allowBackup=false` and a `dataExtractionRules` file that excludes everything.
- [ ] **Step 3: Run** `cd android && ./gradlew :contract:test --tests '*VectorsLoadTest'`. Expected: FAIL first (no `Vectors`), then PASS after the helper exists.
- [ ] **Step 4: Run** `./gradlew :app:assembleNopushDebug`. Expected: BUILD SUCCESSFUL, with an activity that shows the text "RustMFA".
- [ ] **Step 5: Commit** `feat(android): project skeleton with the contract vectors in the test classpath`.

---

### Task 2: Byte-exact contract logic in Kotlin

**Files:**
- Create: `android/contract/src/main/kotlin/app/rustmfa/contract/{Canonical,RequestSigning,Signature,Base64Url}.kt`
- Test: `android/contract/src/test/kotlin/app/rustmfa/contract/{ChallengeVectorsTest,RequestVectorsTest}.kt`

**Interfaces:**
- Produces:
  - `object Canonical { fun message(f: ChallengeFields): String }` throws `ContractException(kind)` with `kind` in `InvalidField`, `InvalidExpiresAt`; `ChallengeFields(challengeId, nonce, loginId, deviceId, expiresAt, audience)`.
  - `object RequestSigning { fun signingString(method: String, path: String, ts: Long, nonce: String, body: ByteArray): String }`.
  - `object Signature { fun derToRaw(der: ByteArray): ByteArray; fun verifyEs256Raw(spkiDer: ByteArray, message: ByteArray, raw: ByteArray) }` throws `ContractException` with `BadSignatureEncoding`, `BadPublicKey`, `VerifyFailed`.
  - `object Base64Url { fun encode(b: ByteArray): String; fun decode(s: String): ByteArray }` (no padding).

- [ ] **Step 1: Write the failing tests.** `ChallengeVectorsTest` runs every `challenge_cases` entry: rebuild `message` (building errors are checked before verification and must equal `expect`), check `derToRaw(signature_der_b64url) == signature_b64url` where present, verify, compare the outcome with `expect`. `RequestVectorsTest` does the same for `request_cases`. Add one test per rule in the vector README that the vectors might not name: a DER blob given as the signature is `BadSignatureEncoding`; `expires_at` with a fraction or an offset is `InvalidExpiresAt`.
- [ ] **Step 2: Run** `./gradlew :contract:test`. Expected: FAIL (classes missing).
- [ ] **Step 3: Implement** the four objects. Field validation and the calendar check of `expires_at` follow the Rust crate (`contract/rustmfa-contract/src/canonical.rs`): read it, do not reinvent it. Verification uses `java.security` (`SHA256withECDSA`; convert raw R‖S to DER for the JDK).
- [ ] **Step 4: Run** `./gradlew :contract:test`. Expected: PASS, with one test per vector case in the report.
- [ ] **Step 5: Mutation checks:** change one separator in `Canonical.message`, break the base64url alphabet, drop the length check of the raw signature: each must fail a test. Commit `feat(android): canonical message, request signing and signature conversion`.

---

### Task 3: Keystore keys (`DeviceKey`)

**Files:**
- Create: `android/app/src/main/kotlin/app/rustmfa/android/crypto/{DeviceKey,KeyStoreDeviceKey,AttestationChain}.kt`
- Test: `android/app/src/androidTest/kotlin/app/rustmfa/android/crypto/DeviceKeyInstrumentedTest.kt`, `android/app/src/test/kotlin/app/rustmfa/android/crypto/FakeDeviceKeyTest.kt`

**Interfaces:**
- Consumes: `Signature.derToRaw`, `Base64Url` (Task 2).
- Produces:
  - `interface DeviceKey { fun exists(): Boolean; fun createKeys(enrollToken: String): CreatedKeys; fun requestPublicKeyB64(): String; fun approvalPublicKeyB64(): String; fun signRequest(message: ByteArray): ByteArray /* raw R‖S */; fun approvalSigner(): ApprovalSigner; fun delete() }`
  - `data class CreatedKeys(requestPublicKey: String, approvalPublicKey: String, attestationChain: List<String>, strongBox: Boolean)`.
  - `interface ApprovalSigner { fun cryptoObject(): BiometricPrompt.CryptoObject; fun finish(co: BiometricPrompt.CryptoObject, message: ByteArray): ByteArray /* raw R‖S */ }`; `class KeyInvalidatedException : Exception`.
  - `class KeyStoreDeviceKey(context)` is the real one, `class FakeDeviceKey` (test source set shared through `testFixtures` or `src/commonTest`) signs with software keys for the JVM tests of later tasks.

- [ ] **Step 1: Write the failing tests.** JVM: `FakeDeviceKeyTest` (the fake signs and `Signature.verifyEs256Raw` accepts; DER never leaves). Instrumented on the emulator: `requestKeySignsWithoutAuthentication`, `createKeysReturnsTwoSpkiKeysAndAChainWhoseChallengeIsTheTokenHash` (parse the leaf's key-description extension, challenge equals `SHA-256(token)`), `deleteRemovesBothKeys`, `approvalKeyIsAuthenticationBound` (reading `KeyInfo.isUserAuthenticationRequired`).
- [ ] **Step 2: Run** `./gradlew :app:testNopushDebugUnitTest` and, with an emulator running, `./gradlew :app:connectedNopushDebugAndroidTest`. Expected: FAIL (types missing).
- [ ] **Step 3: Implement** `KeyStoreDeviceKey`: both keys are EC secp256r1, `SHA256withECDSA`; `approval_key` gets `setUserAuthenticationRequired(true)`, `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`, `setInvalidatedByBiometricEnrollment(true)`, `setAttestationChallenge(sha256(token))`; try `setIsStrongBoxBacked(true)` and fall back on `StrongBoxUnavailableException`. `finish` catches `KeyPermanentlyInvalidatedException` and throws `KeyInvalidatedException`. Aliases are constants `rustmfa.request`, `rustmfa.approval`.
- [ ] **Step 4: Run** the JVM and the emulator tests. Expected: PASS. Say in the commit message that the emulator cannot prove hardware backing, StrongBox or the biometric prompt; those go on the physical-phone checklist of Task 9.
- [ ] **Step 5: Commit** `feat(android): Keystore request and approval keys`.

---

### Task 4: API client, enrollment link and enrollment

**Files:**
- Create: `android/app/src/main/kotlin/app/rustmfa/android/net/{MfaApi,OkHttpMfaApi,RequestSigner,ApiError,ClockSkew,Models}.kt`, `android/app/src/main/kotlin/app/rustmfa/android/enroll/{EnrollLink,EnrollmentRepository,DeviceStore}.kt`
- Test: `android/app/src/test/kotlin/app/rustmfa/android/net/{RequestSignerTest,OkHttpMfaApiTest,ClockSkewTest}.kt`, `android/app/src/test/kotlin/app/rustmfa/android/enroll/{EnrollLinkTest,EnrollmentRepositoryTest}.kt`

**Interfaces:**
- Consumes: `DeviceKey` and `FakeDeviceKey` (Task 3), `RequestSigning`, `Base64Url` (Task 2).
- Produces:
  - `interface MfaApi { suspend fun register(r: RegisterRequest): DeviceStatus; suspend fun listChallenges(): List<ChallengeSummary>; suspend fun getChallenge(id: String): ChallengeDetails; suspend fun approve(id: String, selectedNumber: Int, signature: String); suspend fun deny(id: String, signature: String); suspend fun listDevices(): List<Device>; suspend fun revokeDevice(id: String); suspend fun updatePushToken(id: String, channel: PushChannel, token: String); suspend fun listAudit(): List<AuditEvent> }` (models mirror the OpenAPI schemas, field names in snake_case through `@SerialName`).
  - `class ApiError(val code: ErrorCode, val status: Int, message: String): Exception`; `enum class ErrorCode` with the ten contract codes and `UNKNOWN`.
  - `class RequestSigner(deviceId, key: DeviceKey, clock: Clock, nonce: () -> String)` with `fun header(method, pathWithQuery, body): String`.
  - `class ClockSkew` keeps an offset from the `Date` header of answers; `Clock.now()` returns the corrected time.
  - `class EnrollLink private constructor(val server: HttpUrl, val token: String)` with `companion fun parse(text: String, allowHttp: Boolean): EnrollLink?`.
  - `class EnrollmentRepository(api, key, store, push)` with `suspend fun enroll(link: EnrollLink, deviceName: String): Result`; `interface DeviceStore { suspend fun save(e: Enrollment); suspend fun load(): Enrollment?; suspend fun clear() }` (DataStore; holds server, device id, push channel and token only; no keys).

- [ ] **Step 1: Write the failing tests.**
  - `RequestSignerTest`: the header has the four fields in order; the signing string matches `RequestSigning` and verifies against the request key; two calls have different nonces; the body hash is of the exact body bytes.
  - `OkHttpMfaApiTest` with `MockWebServer`: each operation's path, method, `Authorization` header and JSON; every contract error code maps to its `ErrorCode`; an unknown code maps to `UNKNOWN`; a 5xx without a body is an `ApiError(INTERNAL_ERROR)`; a response over 1 MiB is refused; `http://` server in a release configuration is refused before any request.
  - `ClockSkewTest`: with a `Date` header 5 minutes ahead the next request's `ts` is corrected; after an `UNAUTHENTICATED` the client retries once with the corrected clock and then gives up with a skew hint.
  - `EnrollLinkTest` (table): good link; `http://` refused unless `allowHttp`; `javascript:` and other schemes; missing `server` or `token`; token with control characters, spaces or longer than 128 characters; server with `user:pass@`; 10 000-character input; duplicate parameters.
  - `EnrollmentRepositoryTest` with fakes: creates keys with the token, sends both public keys, the chain, `key_algorithm: "ES256"`, the push channel and token, stores the result only on success; a failed registration deletes the keys it created and stores nothing.
- [ ] **Step 2: Run** `./gradlew :app:testNopushDebugUnitTest`. Expected: FAIL (types missing).
- [ ] **Step 3: Implement.** One `OkHttpClient` per server address with a 10 s timeout, no redirects, and no cookies. Parse `EnrollLink` with `HttpUrl`, never with regexes on the whole text. `DeviceStore` uses `preferencesDataStore`.
- [ ] **Step 4: Run** the tests. Expected: PASS. Mutation checks: let `EnrollLink` accept `http://` in release, drop the nonce, leave the keys behind after a failed registration; each must fail a test.
- [ ] **Step 5: Commit** `feat(android): API client, enrollment link and enrollment`.

---

### Task 5: Theme, navigation and the onboarding screens

**Files:**
- Create: `android/app/src/main/kotlin/app/rustmfa/android/ui/{Theme,Navigation,OnboardingScreen,ScanScreen,ManualEnrollScreen,HomeScreen,SettingsScreen}.kt`, `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values-de/strings.xml`
- Test: `android/app/src/test/kotlin/app/rustmfa/android/ui/OnboardingViewModelTest.kt`, `android/app/src/androidTest/kotlin/app/rustmfa/android/ui/OnboardingScreenTest.kt`

**Interfaces:**
- Consumes: `EnrollLink.parse`, `EnrollmentRepository` (Task 4).
- Produces: `class OnboardingViewModel(repo, allowHttp)` with state `Idle | Scanning | NeedsName(link) | Enrolling | Failed(reason) | Done`; navigation graph with the destinations Onboarding, Home, Approve(id), Devices, Settings; Material 3 theme with the Rost palette; the bottom bar (Übersicht, Geräte, Einstellungen) only after enrollment.

- [ ] **Step 1: Write the failing tests.** `OnboardingViewModelTest`: a scanned good link leads to `NeedsName`; a hostile link leads to `Failed` and the repository is never called; a server error code shows its own message (rate limited, token expired); success leads to `Done`. `OnboardingScreenTest` (Compose test): the manual entry form has server and token fields and a disabled button until both are plausible; the scan button asks for the camera permission.
- [ ] **Step 2: Run** the JVM tests and the Compose test on the emulator. Expected: FAIL.
- [ ] **Step 3: Implement** the screens. The QR scanner uses CameraX `ImageAnalysis` and zxing-core's `MultiFormatReader` restricted to QR; the camera permission is asked at the moment of scanning, and a denial leaves the manual entry usable.
- [ ] **Step 4: Run** the tests, then start the app on the emulator, take a screenshot of each screen in light and dark, and look at them (`adb exec-out screencap`). Expected: tests PASS, screens readable in both themes.
- [ ] **Step 5: Commit** `feat(android): theme, navigation and onboarding`.

---

### Task 6: Challenge state machine and the Approve screen

**Files:**
- Create: `android/app/src/main/kotlin/app/rustmfa/android/domain/{ChallengeFlow,ChallengeState}.kt`, `android/app/src/main/kotlin/app/rustmfa/android/ui/{ApproveScreen,ApproveViewModel}.kt`
- Test: `android/app/src/test/kotlin/app/rustmfa/android/domain/ChallengeFlowTest.kt`, `android/app/src/androidTest/kotlin/app/rustmfa/android/ui/ApproveScreenTest.kt`

**Interfaces:**
- Consumes: `MfaApi`, `ApiError` (Task 4), `DeviceKey`, `ApprovalSigner`, `KeyInvalidatedException` (Task 3), `Canonical.message` (Task 2), a `Clock` and a `Delayer` (injected, so tests need no real time).
- Produces: `sealed interface ChallengeState` with `Loading`, `Pending(details, secondsLeft)`, `Signing(selected)`, `Sending`, `Approved`, `Denied`, `NumberMismatch`, `Expired`, `AlreadyHandled`, `Offline(pendingAnswer)`, `KeyInvalidated`, `DeviceRevoked`, `Failed(code)`; `class ChallengeFlow(api, key, clock, delayer)` with `suspend fun load(id)`, `suspend fun approve(selected: Int, authenticate: suspend (ApprovalSigner) -> ByteArray)`, `suspend fun deny(authenticate)`, `val state: StateFlow<ChallengeState>`.

- [ ] **Step 1: Write the failing tests** (`ChallengeFlowTest`, fakes for api, key, clock): approve happy path sends the canonical message's signature and the picked number; deny sends no number; `CHALLENGE_NUMBER_MISMATCH` ends in `NumberMismatch`; `CHALLENGE_EXPIRED` in `Expired`; `CHALLENGE_ALREADY_CONSUMED` in `AlreadyHandled` and nothing is retried; a double `approve` call sends one request; `DEVICE_REVOKED` in `DeviceRevoked`; `KeyInvalidatedException` before or during signing in `KeyInvalidated` without a request; a network error in `Offline` and the signed answer is retried with back-off until `expires_at`, then `Expired`, and is never retried after it; the signed answer exists only in memory (a test that the flow class has no reference to a store or file API); the countdown reaches `Expired` at `expires_at` by the corrected clock; `ApproveScreenTest`: three number buttons, a deny button always present, the countdown shown, and `FLAG_SECURE` set on the window while the screen is shown.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement.** Order in `approve`: re-check the clock against `expires_at`, build the canonical message from the fields exactly as the server sent them, authenticate through the biometric prompt (the `authenticate` lambda wraps `BiometricPrompt`), convert and base64url the signature, send. Map `ApiError` codes as in spec section 6.
- [ ] **Step 4: Run** the tests, then on the emulator open the Approve screen with a fake challenge and check the layout, the countdown and the screen-capture block (a `screencap` of a `FLAG_SECURE` window is black). Expected: PASS.
- [ ] **Step 5: Mutation checks:** remove the double-send guard, retry after `AlreadyHandled`, persist the pending answer: each must fail a test. Commit `feat(android): challenge flow and approve screen`.

---

### Task 7: Push channel, polling and notifications

**Files:**
- Create: `android/app/src/main/kotlin/app/rustmfa/android/push/{PushChannel,PendingPoller,ChallengeNotifier}.kt`, `android/app/src/fcm/kotlin/app/rustmfa/android/push/{FcmChannel,RustMfaMessagingService}.kt`, `android/app/src/nopush/kotlin/app/rustmfa/android/push/NoPushChannel.kt`
- Test: `android/app/src/test/kotlin/app/rustmfa/android/push/{PendingPollerTest,ChallengeNotifierTest,PushPayloadTest}.kt`

**Interfaces:**
- Consumes: `MfaApi`, `DeviceStore` (Task 4), `ChallengeFlow` states (Task 6).
- Produces: `interface PushChannel { val id: String /* "fcm" | "unifiedpush" */; suspend fun register(): String? /* push token */ }`; `object PushPayload { fun challengeId(data: Map<String, String>): String? }` (accepts only a `challenge_id` of the contract's charset and length, ignores everything else); `class PendingPoller(api, clock, delayer)` polling `listChallenges` when the app is in the foreground and every few seconds while a login is awaited; `class ChallengeNotifier` posting one high-priority notification per pending challenge id and removing it when the challenge is answered, expired or unknown.

- [ ] **Step 1: Write the failing tests.** `PushPayloadTest`: a good id; extra keys ignored; missing, empty, control characters, over-long ids give `null`; a payload that carries a number or a user name is not forwarded. `PendingPollerTest` (fake clock): polls on foreground, polls faster while a login is awaited, stops in the background, survives `RATE_LIMITED` by backing off, drops challenges that turned `expired`. `ChallengeNotifierTest` (a fake notification manager): one notification per challenge, removed when answered, no notification for an unknown or expired id, no number in any notification text.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement.** `POST_NOTIFICATIONS` is requested at the first enrolled start on Android 13+; on a denial the poller is the only wake-up and the Home screen says so. `RustMfaMessagingService.onMessageReceived` takes `PushPayload.challengeId`, fetches the details through the signed GET and notifies; `onNewToken` calls `updatePushToken`. The `fcm` flavor applies the `google-services` plugin only if `app/google-services.json` exists; the default `nopush` build must pass `./gradlew :app:assembleNopushDebug` on a machine with no Google files.
- [ ] **Step 4: Run** the tests and `./gradlew :app:assembleNopushDebug`. Expected: PASS. The `fcm` flavor builds only with a Firebase file; note in `android/README.md` how to add one and that it is not committed.
- [ ] **Step 5: Commit** `feat(android): push channel, polling and notifications`.

---

### Task 8: Devices screen and the lifecycle edge cases

**Files:**
- Create: `android/app/src/main/kotlin/app/rustmfa/android/ui/{DevicesScreen,DevicesViewModel,ReconnectScreen}.kt`, `android/app/src/main/kotlin/app/rustmfa/android/domain/SessionGuard.kt`
- Test: `android/app/src/test/kotlin/app/rustmfa/android/domain/SessionGuardTest.kt`, `android/app/src/test/kotlin/app/rustmfa/android/ui/DevicesViewModelTest.kt`

**Interfaces:**
- Consumes: `MfaApi`, `DeviceStore`, `DeviceKey`, `ApiError` (Tasks 3, 4), `ChallengeState` (Task 6).
- Produces: `class SessionGuard(store, key)` with `suspend fun onDeviceRevoked()` (deletes both keys and the stored enrollment, so the app returns to onboarding) and `suspend fun onKeyInvalidated()` (marks the enrollment "needs reconnect" and keeps the server address); `DevicesViewModel` listing the account's phones with status, last seen and the StrongBox badge, revoking one (with a confirmation), and the last audit events.

- [ ] **Step 1: Write the failing tests.** `SessionGuardTest`: `DEVICE_REVOKED` from any call wipes both keys and the enrollment and sends the user to onboarding; a TOTP vault, if present later, is untouched (no code path touches it); `KeyInvalidated` keeps the server address and shows "Gerät neu verbinden" with a button that starts a fresh enrollment. `DevicesViewModelTest`: the list, revoking another phone, revoking this phone (the guard runs afterwards), an error code shows its message, the audit list shows only the contract's eight event kinds.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** the screens and wire `SessionGuard` into every place that sees `DEVICE_REVOKED` or `KeyInvalidatedException` (the API layer reports them through one callback; screens do not each handle them).
- [ ] **Step 4: Run** the tests. Expected: PASS. Mutation check: leave the keys in place after `DEVICE_REVOKED`; a test must fail.
- [ ] **Step 5: Commit** `feat(android): devices screen and lifecycle handling`.

---

### Task 9: End-to-end run on the emulator, checklist, CI and docs

**Files:**
- Create: `android/docs/e2e.md`, `.github/workflows/android.yml`
- Modify: `android/README.md`, `docs/superpowers/specs/2026-11-08-rustmfa-android-design.md` (phase status), `README.md` of the repository if it exists

**Interfaces:**
- Consumes: everything above, and a running Rostdav (`cargo run -p rostdav -- --config … serve` from the Rostdav repository, `attestation = "off"` and `public_url = "http://10.0.2.2:<port>"`).

- [ ] **Step 1: Run the whole app against a real Rostdav on the emulator.** Create a user, sign in, add a phone (the QR text from the page can be pasted into the manual entry), start a browser sign-in with "Send to my phone", let the app's poller find the request, pick the number, approve with the emulator's enrolled fingerprint (`adb -e emu finger touch 1`), and check that the browser signs in. Also check deny, a wrong number, an expired request, revoking the phone from the web, and revoking it from the app. Write each result into `android/docs/e2e.md` with the date and the exact commands.
- [ ] **Step 2: Write the physical-phone checklist** in `android/docs/e2e.md` for what an emulator cannot prove: real hardware or StrongBox attestation against the server's `attestation = "required"`, the biometric prompt with a real sensor, FCM delivery with a Firebase project, and a key invalidated by enrolling a second fingerprint.
- [ ] **Step 3: Add the workflow.** `android.yml` runs `./gradlew :contract:test :app:testNopushDebugUnitTest :app:lintNopushDebug :app:assembleNopushDebug` on every push and pull request. It does not run emulator tests. A second small job runs the Rust contract tests (`cd contract && cargo test`), because the repository has no workflow yet.
- [ ] **Step 4: Run** `./gradlew :contract:test :app:testNopushDebugUnitTest :app:lintNopushDebug :app:assembleNopushDebug` and `cd contract && cargo test`. Expected: all PASS.
- [ ] **Step 5: Update** the spec's phase list (phases 2 to 4 done, the rest open) and `android/README.md` (how to build, test, run on the emulator, and add Firebase). Commit `docs(android): end-to-end run, physical-phone checklist, CI`.

---

## Self-Review

- **Spec coverage:** 2.1 packages `crypto/` (Task 3), `net/` (Task 4), `push/` (Task 7), `domain/` (Tasks 6, 8), `ui/` (Tasks 5 to 8); section 3 screens 1 to 3 and 5 and 6 (Tasks 5, 6, 8; screen 4 "Add account" belongs to the TOTP plan); 4.1 enrollment (Tasks 3, 4); 4.2, 4.3 (Task 2); 4.4 (Task 6); 4.8 (Task 7); 6 error handling (Tasks 6, 8); 7 testing (the vectors in Task 2, the state machine in Task 6, instrumented tests in Tasks 3, 5, 6, the physical-phone items in Task 9). Not in this plan: section 5 (TOTP vault), UnifiedPush, hardening items beyond `FLAG_SECURE` and the backup rules, store release.
- **Type consistency:** `DeviceKey`, `ApprovalSigner` and `KeyInvalidatedException` come from Task 3 and are used unchanged in Tasks 4, 6 and 8; `MfaApi` and `ApiError` from Task 4 are used in Tasks 6 to 8; `ChallengeState` from Task 6 in Tasks 7 and 8.
- **Review Focus mapping:** key invalidation (Tasks 3, 6, 8), double approve and no retry loop (Task 6), clock skew (Task 4, `ClockSkewTest`; the Review Focus line says Task 2 and should say Task 4), stale push (Task 7), hostile QR (Task 4), offline approve in memory (Task 6).
- **Known soft spots:** the Gradle, AGP and Kotlin versions are chosen in Task 1 from release notes, because they could not be verified here; the biometric prompt, StrongBox and real attestation cannot be proven on an emulator; FCM cannot be tried without a Firebase project.
