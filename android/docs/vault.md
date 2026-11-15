# Vault core

The vault keeps the user's own TOTP and HOTP accounts (the seeds of other services) on the phone. This document covers the core only: the storage, the crypto and the session. There is no screen yet (sub-project 3).

## Module layout

`:vault` is a plain JVM module, package `app.rustmfa.vault`, with no Android dependency. Its only crypto dependency is Bouncy Castle `bcprov-jdk18on`, used for Argon2id. AES-GCM comes from the JDK.

- `OtpAccount`, `OtpType`, `OtpAlgorithm`: the account model. The secret is a byte array, `toString` redacts it. Checks: secret not empty, digits 6 or 8, period 1 to 3600, counter not negative.
- `Base32`, `Otp` (RFC 6238 and RFC 4226), `OtpAuthUri` (parse and build `otpauth://` URIs, errors as `OtpAuthException`).
- `VaultKdf` (Argon2id, version 0x13), `VaultCipher` (AES-256-GCM, 128-bit tag, key 32 bytes, nonce 12 bytes).
- `VaultAccountsJson`: the account list as UTF-8 JSON `{"v":1,"accounts":[...]}` with base32 secrets. It is the plaintext of both the vault blob and the backup.
- `VaultBackup`: export and import of the passphrase-encrypted backup file.
- `SeedStore`, `KeyWrapper`, `BlobStore` (interfaces), `EncryptedSeedStore`, `VaultSession`.

The app package `app.rustmfa.android.vault` (in `:app`) holds the Android parts:

- `KeystoreKeyWrapper`: the `KeyWrapper` on an AES-256-GCM key in the Android Keystore.
- `DataStoreBlobStore`: the `BlobStore` on a Preferences DataStore file, bytes as base64 under one key.
- `VaultRepository`: `unlock()`, `appLeft()`, `appReturned()`, `lock()` and `reset()` around a `VaultSession`.
- `BiometricUnlocker`: the prompt that opens the Keystore window.
- `AppContainer.vault` wires these together with `SystemClock::elapsedRealtime` as the clock. `AppContainer.authenticator` is the suspend function the UI sets (it should call `BiometricUnlocker.authenticate`); until it is set, an unlock fails with `VaultAuthRequiredException`.

## Vault blob (on the device)

Seeds are encrypted under a random 32-byte data key. The blob is stored in the DataStore file `vault`:

    "RMFAV1" (6 ASCII bytes) | nonce (12 bytes) | AES-256-GCM ciphertext | tag (16 bytes)

The associated data is the six magic bytes. The plaintext is the account JSON. A new random nonce is used on every save. A blob shorter than 6 + 12 + 16 bytes, with another magic, with a failing tag or with invalid JSON raises `VaultCorruptException`.

The data key is wrapped by the Keystore key (alias `rostdav_mfa_vault_key`) and stored in the DataStore file `vault_key` as:

    iv (12 bytes) | AES-256-GCM ciphertext of the data key | tag (16 bytes)

The Keystore key is AES-256, GCM, no padding, StrongBox when the device has it (otherwise the normal Keystore), `setUserAuthenticationRequired(true)` with a window of 10 seconds. On API 30 and higher the window accepts `BIOMETRIC_STRONG` or `DEVICE_CREDENTIAL`; below 30 a positive validity duration allows a biometric or the credential. `setInvalidatedByBiometricEnrollment(false)`. The backup rules exclude the DataStore files, so the vault is never part of an Android backup.

## Backup file

A UTF-8 JSON object:

    {"app":"rostdav-mfa","v":1,"kdf":{"alg":"argon2id","m":65536,"t":3,"p":1,"salt":"<base64, 16 bytes>"},"enc":{"alg":"aes-256-gcm","nonce":"<base64, 12 bytes>"},"data":"<base64>"}

- Key: Argon2id version 0x13, 32-byte output, parameters from the header. Export writes memory 65536 KiB, 3 iterations, parallelism 1. The passphrase is NFKC-normalised and encoded as UTF-8.
- `data` is the AES-256-GCM ciphertext with the 16-byte tag at its end, over the account JSON.
- The associated data is the header object `{"app":...,"v":...,"kdf":{...},"enc":{...}}` (members in that order, no whitespace, `data` left out) as UTF-8. It is always rebuilt from the parsed values, so export and import use the same bytes.
- Base64 is the standard alphabet with padding.
- Limits on import, checked before anything is derived: `v` is 1; `kdf.alg` is `argon2id`; `enc.alg` is `aes-256-gcm`; `p` 1 to 4; `m` from 8 times `p` up to 131072 KiB; `t` 1 to 10; salt 16 bytes; nonce 12 bytes. Anything else is a `BackupFormatException`.
- Export needs a passphrase of at least 8 characters (code points, after normalisation); import does not check the length.
- A wrong passphrase and a damaged file both give `BackupDecryptException`, since they cannot be told apart. Valid decryption with unusable contents gives `BackupFormatException`.
- Import into a session skips accounts that equal an existing one (issuer, label, algorithm, digits, period and secret) and gives new ids to the rest. The result is an `ImportResult(added, skipped)`. HOTP counters are not compared.

## Unlock and session rules

- `VaultRepository.unlock()` returns at once if the session is unlocked. Otherwise it calls the authenticator (the prompt, which opens the Keystore window for 10 seconds) and then `VaultSession.open()`, which calls `EncryptedSeedStore.load()`.
- `load()` on a first run (no blob, no wrapped key) makes a random data key and wraps it at once, while the window is open, and returns an empty list. With a wrapped key it unwraps it and decrypts the blob. A blob without a key raises `VaultKeyLostException`. A failed first-run wrap destroys the key again.
- A closed window gives `VaultAuthRequiredException`. A key that the Keystore invalidated, or a wrapped key that fails its tag, gives `VaultKeyLostException`.
- Auto-lock: `appLeft()` stores `SystemClock.elapsedRealtime()` when the session is unlocked. `appReturned()` locks the session if at least 60 000 ms passed since then, and does nothing if the app was not left. The clock is monotonic, so changing the wall clock does not keep the vault open.
- `lock()` zeroes the in-memory accounts and the data key. A mutation (add, update, remove, import, next HOTP) saves first and commits to memory only if the save succeeded and `lock()` did not run meanwhile; otherwise it throws `VaultLockedException`. Mutations are serialised by a mutex. The session copies secrets in and out and zeroes its own arrays on lock.
- `reset()` (`VaultRepository.reset()`, `VaultSession.reset()`, `SeedStore.reset()`) deletes the stored blob and the wrapped Keystore key and zeroes the in-memory key. It works from the locked state and after a failed load (key lost, corrupt blob, authentication required). The session locks first and takes no mutation lock, so a mutation in flight or queued fails with `VaultLockedException` and reset is never stuck behind a whole mutation. The store orders blob writes against the clear with a private mutex: a write that is already inside the blob store completes before `clear()` runs, and a save that has not started writing re-checks the lock generation and does not write. So after `reset()` returns, no old blob can reappear. If deleting the blob or the key fails, the other is still attempted and the first failure is rethrown with the second suppressed. After `reset()` the session is locked; the next `unlock()` authenticates (opening the Keystore window, which creating the new wrapped key needs) and opens an empty vault. Both a restore and a new vault go through this.
- `code(id, epochSeconds)` is for TOTP accounts, `nextHotp(id)` saves the incremented counter before it returns the code.

## Accepted risks

1. A new fingerprint does not invalidate the key (`setInvalidatedByBiometricEnrollment(false)`, chosen so that adding a finger does not destroy the vault). Someone who knows the device PIN can therefore enrol a fingerprint of their own and unlock the vault with it. The PIN alone already unlocks the vault through the device credential, so the vault is no stronger than the device lock.
2. Removing the lock screen (or resetting it) makes Android delete Keystore keys that need authentication. The wrapped data key is then useless and the vault is lost. `load()` reports this as `VaultKeyLostException`. The only recovery is a backup.

## Checks done and not done

Unit tests cover the JVM module (including the RFC vectors, the zeroing, the lock races and the backup limits) and the Android parts that need no prompt. Instrumented tests on the emulator cover the Keystore wrapper.

Manual unlock check (emulator-5554, API 35, device PIN set): a throwaway instrumented test started `MainActivity`, called `BiometricUnlocker.authenticate()` and the PIN was typed with `input text`. Creating the key before authentication raised `VaultAuthRequiredException`. After the prompt succeeded, `KeystoreKeyWrapper.create` and `unwrap` worked at once (32 bytes back), and `unwrap` 15 seconds after the start of the window raised `VaultAuthRequiredException`. That test was deleted. It ran on the unlocker and the wrapper, not on the `AppContainer` wiring (no screen exists yet).

Not verified: API 28 and 29 (the `setUserAuthenticationValidityDurationSeconds` and `setDeviceCredentialAllowed` branches), a real fingerprint sensor, StrongBox hardware, and a physical phone. The emulator has no StrongBox, so the fallback to the normal Keystore is what ran.

## Release build

`./gradlew :app:assembleNopushRelease` builds with R8 and reports no missing class for Bouncy Castle, so `app/proguard-rules.pro` needs no rule for it. The unsigned APK is 5,652,525 bytes. The same build with `bcprov` removed and `VaultKdf` stubbed out is 5,635,903 bytes, so Bouncy Castle adds about 16 KiB after shrinking (R8 keeps only the Argon2 classes the code reaches). The release build was made on 2026-11-15 with the signing config absent (the output is `app-nopush-release-unsigned.apk`); the shrunken APK was not run on a device.

## For the vault UI (sub-project 3)

These are obligations the reviews found for the code that will call the vault core.

- Call `BiometricUnlocker` on the main thread (`BiometricPrompt` requires it).
- `AppContainer.authenticator` captures the Activity through `BiometricUnlocker`. Clear it (set it back to the throwing default) when the Activity goes away, or it leaks the Activity.
- Serialise `unlock()` calls. Two concurrent callers on a locked vault can both show a prompt.
- Call `appLeft()` when the app goes to the background and `appReturned()` when it comes back, from the lifecycle. Without them the vault never auto-locks.
- Nudge users to make a backup. Removing the lock screen destroys the vault, and a backup is the only way back.
- Handle `VaultKeyLostException` and `VaultCorruptException`: `unlock()` fails with them on every attempt while the old blob stays, so the only way out is `VaultRepository.reset()`. Offer a restore from a backup or an empty new vault (confirm first, since reset deletes what is left). Both work the same way: call `reset()`, then `unlock()` (the user authenticates again; this creates the new key and an empty vault), then for a restore `VaultSession.import(VaultBackup.import(bytes, passphrase))`. Decrypt the backup before calling `reset()`, so a wrong passphrase does not cost the user the old state. Treat `VaultAuthRequiredException` as "the user cancelled or failed", not as an error to report.
