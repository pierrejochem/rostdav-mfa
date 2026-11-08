# RustMFA Android: Design Spec

Date: 2026-11-08
Status: Draft for review
Inputs: `Microsoft Authenticator MFA API.pdf` (ChatGPT transcript, iOS-first API draft), `RustMFA UI_UX-Präsentation.png` (iOS mockups)

## 1. Goal and scope

RustMFA is a self-hosted authenticator app. The first release targets Android and serves one backend: the Rostdav Rust/Axum server.

The app has two functions:

1. **Push MFA.** Rostdav starts a challenge at login. The phone receives a push hint, the user confirms with biometrics, and the app returns a signed response. Rostdav grants access only after verifying the signature.
2. **TOTP/HOTP codes.** The app stores seeds for third-party accounts (GitHub, AWS, and others) and shows RFC 6238/4226 codes. Seeds stay on the phone.

Out of scope for v1:

- Approving logins at third-party services (Google, Microsoft). This is not possible: neither exposes a push API for external apps. The iOS mockup's "Google Anmeldeanforderung" screen is removed.
- A general MFA platform for other projects. The module is isolated so this can follow later.
- E2E-encrypted seed sync. Local storage is designed so it can be added later.
- iOS. The API and signing format are platform-neutral, so iOS can follow.

### Success criteria

- A login on Rostdav completes only after a valid, device-bound, biometric-gated signature.
- A replayed, expired, wrong-device, wrong-login, or revoked-device response is rejected.
- Android and Rust verify the same signature test vectors.
- TOTP codes match the RFC 6238 and RFC 4226 test vectors.
- A lost phone can be revoked server-side. Its push key becomes useless.

## 2. Architecture

```
Rostdav login (web)        Android app                    Push channel
        |                        |                              |
        |-- create challenge --> Rostdav `mfa/` module -- hint --> FCM (later: UnifiedPush)
        |                        ^                              |
        |                        |<-- signed approve/deny ------|  (app wakes, fetches details)
        |<-- login released -----|
```

Transport is HTTPS and JSON under `/api/v1`. Push carries only "a request is pending" plus a challenge id. It never authorizes anything.

### 2.1 Android app

Kotlin, Jetpack Compose, Material 3, min SDK 28 (StrongBox API level).

| Package | Responsibility |
|---|---|
| `ui/` | Home, Approve, Add account, Devices, Settings, Onboarding |
| `crypto/DeviceKey` | EC P-256 key in Android Keystore, StrongBox when available, `setUserAuthenticationRequired(true)`, used through `BiometricPrompt` + `CryptoObject`. Converts DER signatures to raw R‖S. |
| `crypto/TotpVault` | Seeds encrypted with a Keystore AES-GCM key. Access through a `SeedStore` interface so a synced store can replace it later. |
| `net/MfaApi` | Endpoint client, request signing, TLS only |
| `push/PushChannel` | Interface. `FcmChannel` in v1. `UnifiedPushChannel` in phase 7. |
| `domain/` | Challenge state machine, TOTP/HOTP generator |

### 2.2 Rostdav `mfa/` module

Axum, with storage through Rostdav's own database layer (`rostdav_meta::sql`, Turso, ADR 0008). The logic lives in a `push_mfa` module of `rostdav-app`, the HTTP API in a new protocol crate `rostdav-mfa-api`. Submodules: `devices`, `challenges`, `attestation`, `push` (`PushChannel` trait, FCM adapter), audit through the existing audit log.

Challenge creation is an in-process call from the Rostdav login flow, not an HTTP endpoint. The login flow picks one explicit device (the most recently seen active one) and creates a challenge for it.

The login flow must not issue a full access token until its challenge is `approved`. Challenge creation, signature verification, and login release are bound to the same `login_id`. They cannot run independently.

### 2.3 Boundary

The only contract between app and server is the OpenAPI 3.1 spec plus the canonical signing format in section 4, backed by shared test vectors. The two sides can be built independently.

## 3. UI

Material 3 with the Rostdav rust palette (accent `#D2691E`), light and dark. The iOS Liquid Glass look is not copied.

Screens (mockups were reviewed in the visual companion):

1. **Onboarding / enrollment.** Scan the enrollment QR, or enter the server URL manually.
2. **Home.** One list of TOTP accounts. A pending-request banner sits on top and disappears when none is pending. FAB adds an account. Bottom nav: Übersicht, Geräte, Einstellungen.
3. **Approve.** Full screen. Shows service name, login device, approximate location labelled as IP-based, time, and a countdown. Number matching: the user picks the number shown in the login window from three. A wrong pick auto-denies. A correct pick opens the biometric prompt, which unlocks the signature. Deny is always available.
4. **Add account.** Scan QR, enter key manually, import encrypted file.
5. **Devices.** Own devices with status (active, last seen, StrongBox), revoke action, and recent audit events.
6. **Dark mode.**

Settings contain security (biometric requirement, app lock), notifications, TOTP export/import, and general options. "Backup" covers TOTP seeds only (encrypted file). Push keys are non-exportable, so a new phone re-enrolls.

`FLAG_SECURE` is set on the Approve and code screens. Auto-backup of app data is disabled.

## 4. Protocol and security

### 4.1 Enrollment

1. A logged-in web session calls `POST /api/v1/devices/enroll-token`. The server returns a single-use token valid 5 minutes, rendered as a QR code.
2. The app scans the QR, creates two Keystore keys (see 4.2), and calls `POST /api/v1/devices/register` with `enroll_token`, `device_name`, `request_public_key`, `approval_public_key`, `key_algorithm: "ES256"`, `attestation_chain` (for the approval key), `push_channel` (`fcm` | `unifiedpush`), and `push_token`. Public keys are base64 (standard alphabet) of the X.509 SubjectPublicKeyInfo DER, which is what Android Keystore exports.
3. The server validates the token and the attestation chain, then sets the device to `active`. `register` is the only unauthenticated call, and only with a valid token.

**Attestation policy (default).** The key attestation challenge is the hash of the enroll token. The chain must verify to a Google attestation root and show a hardware-backed key (TEE or StrongBox). Software-only keys are rejected. The policy is configurable per deployment, for example to allow GrapheneOS or custom ROM attestation roots.

### 4.2 Request authentication

Every device call except `register` carries:

```
Authorization: RustMFA-Sig device_id="…",ts="…",nonce="…",sig="…"
```

`sig` is a raw ES256 signature, base64url, over the string:

```
METHOD \n PATH-with-query \n ts \n nonce \n base64url(sha256(body))
```

`ts` is Unix seconds as a decimal string. The server rejects timestamps more than 60 seconds off, and any nonce seen in the last 5 minutes for that device. This covers GET, approve, and deny.

The machine-readable contract is `contract/openapi/rustmfa-v1.yaml`. Challenge creation is an in-process call of the Rostdav login flow and has no HTTP endpoint. `POST /devices/enroll-token` is called by a logged-in web session (cookie and CSRF protection, served by the web crate). It is not a device call, so it does not use the device signature.

This signature is not biometric-gated, so the key used for request auth is a second, non-biometric Keystore key (`request_key`) registered alongside the biometric-gated `approval_key`. `register` therefore carries two public keys. Only `approval_key` signs challenge messages (4.3).

### 4.3 Canonical challenge message

Signed on approve and on deny, by `approval_key`:

```
v1 \n challenge_id \n nonce \n login_id \n device_id \n expires_at \n audience
```

Fixed field order, UTF-8, `\n` separators, no trailing newline, `expires_at` in exactly the form `YYYY-MM-DDTHH:MM:SSZ` (UTC, real calendar date, no fraction, no leap second). `audience` is the Rostdav service identifier. The signature is the raw 64-byte R‖S form, base64url-encoded. Android's DER output must be converted before sending, and the server rejects DER.

### 4.4 Number matching

The server generates a 2-digit number per challenge and shows it in the login UI. `GET /mfa/challenges/{id}` (device-authenticated) returns three candidate numbers: the correct one and two decoys. The app sends the chosen `selected_number` in the approve request. A mismatch denies the challenge with `CHALLENGE_NUMBER_MISMATCH` and writes an audit event. Deny does not need a number.

### 4.5 Endpoints

From the PDF, unchanged in meaning. Challenge creation (`POST /mfa/challenges` in the PDF) is not an HTTP endpoint, see 2.2:

| Method | Path | Function |
|---|---|---|
| POST | `/devices/register` | Register device (with enroll token) |
| PUT | `/devices/{device_id}/push-token` | Update push token |
| GET | `/mfa/challenges/{challenge_id}` | Challenge details |
| POST | `/mfa/challenges/{challenge_id}/approve` | Signed approval |
| POST | `/mfa/challenges/{challenge_id}/deny` | Signed denial |

Added (the contract has 11 operations in total):

| Method | Path | Function |
|---|---|---|
| POST | `/devices/enroll-token` | Web session creates enrollment token |
| GET | `/mfa/challenges?status=pending` | Polling fallback |
| GET | `/devices` | List own devices |
| DELETE | `/devices/{device_id}` | Revoke (server-side block, key revoked) |
| POST | `/devices/{device_id}/rotate-key` | Key rotation |
| GET | `/audit` | Own audit events |

### 4.6 Server checks on approve

1. Device exists, is `active`, and belongs to the challenge's user.
2. Challenge belongs to this device and login.
3. Challenge is `pending` and unexpired (default 90 s, configurable).
4. `selected_number` matches.
5. Signature verifies over the canonical message with the stored `approval_key`.
6. In one transaction, mark the challenge consumed, then release the login. A concurrent second approve fails with `CHALLENGE_ALREADY_CONSUMED`.

### 4.7 Errors

Uniform body `{"error":{"code":"…","message":"…"}}`. No secrets or key material in errors.

| HTTP | Code |
|---|---|
| 400 | `INVALID_REQUEST` |
| 401 | `UNAUTHENTICATED` |
| 403 | `DEVICE_NOT_AUTHORIZED`, `DEVICE_REVOKED` |
| 404 | `RESOURCE_NOT_FOUND` |
| 409 | `CHALLENGE_ALREADY_CONSUMED`, `CHALLENGE_NUMBER_MISMATCH` |
| 410 | `CHALLENGE_EXPIRED` |
| 429 | `RATE_LIMITED` |
| 500 | `INTERNAL_ERROR` |

Rate limits apply per device and per user on challenge creation and on approve/deny attempts. Concrete numbers are set during backend implementation and go in the OpenAPI spec.

### 4.8 Push

The push payload is a data message with `challenge_id` only, sent at high priority. The app fetches details through the authenticated GET. The `PushChannel` trait and the Android interface both exist from day one. UnifiedPush follows in phase 7. Android 13+ needs the `POST_NOTIFICATIONS` runtime permission, and the app handles a denial by relying on polling. FCM routes a "request pending" signal through Google. It carries no secret.

## 5. TOTP vault

- Algorithms: TOTP (RFC 6238) and HOTP (RFC 4226), SHA-1/256/512, 6 or 8 digits, configurable period.
- Seeds are encrypted at rest with an AES-GCM Keystore key and are never sent to the server.
- Import and export use an encrypted file (passphrase-derived key, Argon2id). Export is TOTP seeds only.
- `SeedStore` is an interface so a future E2E-synced implementation needs no UI change.

## 6. Error handling

| Situation | Behavior |
|---|---|
| Challenge expired (410) | Approve screen closes, "Abgelaufen", banner clears |
| Already consumed (409) | "Bereits bearbeitet", no retry |
| Network down during approve | Signed response held in memory until expiry, retried, never written to disk |
| Biometric enrollment changed | Keystore invalidates the key. App shows "Gerät neu verbinden" and starts enrollment |
| Device revoked | Wipe push keys, return to onboarding. TOTP seeds stay |
| Push late or missing | Poll pending challenges on foreground, and every few seconds while a login is awaited |

## 7. Testing

- **Shared test vectors** (JSON): canonical message, DER to raw conversion, valid and invalid signatures. The same file runs in Rust tests and Android unit tests.
- **Rust handler tests:** replay, expiry, wrong device, wrong login binding, revoked device, nonce reuse, number mismatch, concurrent approve.
- **Android:** TOTP and HOTP RFC vectors, challenge state machine, `DeviceKey` instrumented tests.
- **End-to-end:** a staging Rostdav plus a physical phone for biometrics, FCM, StrongBox and attestation. Emulators cannot prove these.

## 8. Phases

0. Spec: OpenAPI 3.1, canonical format, test vectors.
1. Rostdav `mfa/` module (devices, enroll, challenges, audit, FCM adapter).
2. Android skeleton, theme, navigation.
3. Enrollment and `DeviceKey`.
4. FCM, approve flow, number matching.
5. TOTP vault, QR/manual/import, encrypted export.
6. Hardening (`FLAG_SECURE`, backup exclusion, attestation policy, rate limits).
7. UnifiedPush adapter.
8. Release (F-Droid and/or Play).

## 9. Open items

- The OpenAPI JSON and scaffold ZIP mentioned in the PDF were not provided. Phase 0 recreates the spec from section 4.
- Rostdav's existing auth, session, and DB layout have not been inspected (`Cargo.toml` and the router were never shared). Phase 1 starts with an inventory of both and may adjust module naming.
- Distribution target (F-Droid, Play, or both) is undecided and only affects phase 8. Play requires Play Integrity considerations beyond key attestation.
