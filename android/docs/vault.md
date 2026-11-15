# Vault core

The vault keeps the user's own TOTP and HOTP accounts (the seeds of other services) on the phone. This document covers the core (the storage, the crypto and the session) and, at the end, the screens built on it.

## Module layout

`:vault` is a plain JVM module, package `app.rustmfa.vault`, with no Android dependency. Its only crypto dependency is Bouncy Castle `bcprov-jdk18on`, used for Argon2id. AES-GCM comes from the JDK.

- `OtpAccount`, `OtpType`, `OtpAlgorithm`: the account model. The secret is a byte array, `toString` redacts it. Checks: secret not empty, digits 6 or 8, period 1 to 3600, counter not negative.
- `Base32`, `Otp` (RFC 6238 and RFC 4226), `OtpAuthUri` (parse and build `otpauth://` URIs, errors as `OtpAuthException`).
- `OtpAuthUri` decoding: in query values a `+` is a space (as Android's `Uri.getQueryParameter` and PHP `urlencode` treat it; a real plus is `%2B`); in the path only percent escapes are decoded, so a `+` stays a plus. Spaces after the issuer colon are dropped from the account name. `build` writes a space as `%20` and a plus as `%2B`. One loss: an account name that starts with a space, behind an issuer (or containing a colon), comes back from `build` then `parse` without those spaces.
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

The associated data is the six magic bytes. The plaintext is the account JSON. A new random nonce is used on every save. A blob shorter than 6 + 12 + 16 bytes, with another magic, with a failing tag or with invalid JSON raises `VaultCorruptException`. A damaged DataStore file raises it too: the store's corruption handler replaces the file by preferences that hold only a marker key (not empty preferences, which would read as "nothing stored" and let a first run overwrite the vault silently), and `DataStoreBlobStore.read()` throws `VaultCorruptException` while the marker is there or when the stored value is not base64. `clear()` and `write()` remove the marker, so `reset()` works. A damaged `vault_key` file means the wrapped key cannot be read back: `KeystoreKeyWrapper.hasKey()` is false and `unwrap()` throws `VaultKeyLostException`.

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
- Argon2 allocates all its memory up front: export and import allocate up to 136 MiB and block for seconds, so the UI must run them off the main thread. If the allocation fails, the `OutOfMemoryError` becomes a `BackupFormatException` with the message `not enough memory for the backup key derivation`.
- Export needs a passphrase of at least 8 characters (code points, after normalisation); import does not check the length.
- A wrong passphrase and a damaged file both give `BackupDecryptException`, since they cannot be told apart. Valid decryption with unusable contents gives `BackupFormatException`.
- Import into a session skips accounts that equal an existing one (issuer, label, algorithm, digits, period and secret) and gives new ids to the rest. The result is an `ImportResult(added, skipped)`. HOTP counters are not compared.

## Unlock and session rules

- `VaultRepository.unlock()` returns at once if the session is unlocked. Otherwise it calls the authenticator (the prompt, which opens the Keystore window for 10 seconds) and then `VaultSession.open()`, which calls `EncryptedSeedStore.load()`. `unlock()` and `reset()` share a mutex: a second `unlock()` that waited behind the first finds the session open and does not prompt, and a `reset()` never runs between an unlock's load and its install (that would leave the session open under a key reset had destroyed). `lock()` does not take it.
- `EncryptedSeedStore.load()` calls are serialised by a private mutex, so two first-run loads on one store never both create a key. `lock()` and `reset()` do not take it and never wait for a load; a load in flight when they run fails with `VaultLockedException`.
- `load()` on a first run (no blob, no wrapped key) makes a random data key and wraps it at once, while the window is open, and returns an empty list. With a wrapped key it unwraps it and decrypts the blob. A blob without a key raises `VaultKeyLostException`. A failed first-run wrap destroys the key again.
- A closed window gives `VaultAuthRequiredException`. A key that the Keystore invalidated (`KeyPermanentlyInvalidatedException`, or on keystore2 an `UnrecoverableKeyException` from `getKey`, anywhere in the cause chain), or a wrapped key that fails its tag, gives `VaultKeyLostException`.
- Auto-lock: `appLeft()` stores `SystemClock.elapsedRealtime()` when the session is unlocked. `appReturned()` locks the session if at least 60 000 ms passed since then, and does nothing if the app was not left. The clock is monotonic, so changing the wall clock does not keep the vault open.
- `lock()` zeroes the in-memory accounts and the data key. A mutation (add, update, remove, import, next HOTP) saves first and commits to memory only if the save succeeded and `lock()` did not run meanwhile; otherwise it throws `VaultLockedException`. Mutations are serialised by a mutex. The session copies secrets in and out and zeroes its own arrays on lock.
- `reset()` (`VaultRepository.reset()`, `VaultSession.reset()`, `SeedStore.reset()`) deletes the stored blob and the wrapped Keystore key and zeroes the in-memory key. It works from the locked state and after a failed load (key lost, corrupt blob, authentication required). The session locks first and takes no mutation lock, so a mutation in flight or queued fails with `VaultLockedException` and reset is never stuck behind a whole mutation. The store orders blob writes against the clear with a private mutex: a write that is already inside the blob store completes before `clear()` runs, and a save that has not started writing re-checks the lock generation and does not write. So after `reset()` returns, no old blob can reappear. If deleting the blob or the key fails, the other is still attempted and the first failure is rethrown with the second suppressed. After `reset()` the session is locked; the next `unlock()` authenticates (opening the Keystore window, which creating the new wrapped key needs) and opens an empty vault. Both a restore and a new vault go through this.
- `code(id, epochSeconds)` is for TOTP accounts, `nextHotp(id)` saves the incremented counter before it returns the code.

## Accepted risks

1. A new fingerprint does not invalidate the key (`setInvalidatedByBiometricEnrollment(false)`, chosen so that adding a finger does not destroy the vault). Someone who knows the device PIN can therefore enrol a fingerprint of their own and unlock the vault with it. The PIN alone already unlocks the vault through the device credential, so the vault is no stronger than the device lock.
2. Removing the lock screen (or resetting it) makes Android delete Keystore keys that need authentication. The wrapped data key is then useless and the vault is lost. `load()` reports this as `VaultKeyLostException`. The only recovery is a backup.

## Checks done and not done

Unit tests cover the JVM module (including the RFC vectors, the zeroing, the lock races and the backup limits) and the Android parts that need no prompt. Instrumented tests on the emulator cover the Keystore wrapper.

Manual unlock check (emulator-5554, API 35, device PIN set): a throwaway instrumented test started `MainActivity`, called `BiometricUnlocker.authenticate()` and the PIN was typed with `input text`. Creating the key before authentication raised `VaultAuthRequiredException`. After the prompt succeeded, `KeystoreKeyWrapper.create` and `unwrap` worked at once (32 bytes back), and `unwrap` 15 seconds after the start of the window raised `VaultAuthRequiredException`. That test was deleted. It ran on the unlocker and the wrapper, not on the `AppContainer` wiring; the screens and their by-hand checks are in "The screens" below.

Not verified: API 28 and 29 (the `setUserAuthenticationValidityDurationSeconds` and `setDeviceCredentialAllowed` branches), a real fingerprint sensor, StrongBox hardware, and a physical phone. The emulator has no StrongBox, so the fallback to the normal Keystore is what ran.

## Release build

`./gradlew :app:assembleNopushRelease` builds with R8 and reports no missing class for Bouncy Castle, so `app/proguard-rules.pro` needs no rule for it. The unsigned APK is 5,652,525 bytes. The same build with `bcprov` removed and `VaultKdf` stubbed out is 5,635,903 bytes, so Bouncy Castle adds about 16 KiB after shrinking (R8 keeps only the Argon2 classes the code reaches). The release build was made on 2026-11-15 with the signing config absent (the output is `app-nopush-release-unsigned.apk`); the shrunken APK was not run on a device.

## The screens

The vault UI lives in `app.rustmfa.android.ui.vault` (the composables) and `app.rustmfa.android.vault` (`VaultViewModel`, `BackupViewModel`, `RestoreFlow`, the clipboard, the prefs and the file access). The design is in `docs/superpowers/specs/2026-11-15-vault-ui-design.md`.

- **Accounts section on the Overview.** One card per state: no vault yet ("Add your first account"), locked ("Unlock"), empty, the account list, and "The vault cannot be opened". A TOTP row shows the code in two groups and a countdown ring (danger colour in the last 5 seconds); a HOTP row shows "Tap to generate" and then the code with a refresh button. One ticker updates the codes every second while the app is in the foreground and the vault is open, on any screen (not only the Overview); it stops when the app is left. "Lock now" locks at once.
- **Copy.** A tap copies the code as a sensitive clip (hidden from the clipboard preview on Android 13 and newer). The app clears the clip after 30 seconds if it is still its own. From the background, Android 10 and newer do not let the app read the clipboard, so the clip may stay; this is best effort.
- **Long-press sheet.** Copy, Edit (service and account name only; the secret and settings of a saved account cannot be changed) and Delete (asks first). If the vault locked meanwhile, saving asks for the fingerprint or PIN once more; a cancelled prompt shows "Unlock to save".
- **Add account.** The `+` unlocks first (the first unlock creates the vault). Three choices: Scan QR code (an `otpauth://` URI, then the Check the account screen with the names, the settings and the first code), Enter by hand (secret as dots with a show toggle, TOTP or HOTP, Advanced for algorithm, digits, period or counter), and Import a backup. An account that is already there is skipped with "This account is already in the vault".
- **Backup.** From the Settings row "Backup" (subtitle "Last backup: date" or "No backup yet") or the "No backup yet" banner on the Overview (Later hides it for 7 days). The screen first says that removing the screen lock deletes the vault. Create: a passphrase twice (at least 8 characters), then the system file picker; the key derivation runs off the main thread behind a progress indicator. Restore: pick a file, type its passphrase; the accounts are added and existing ones skipped ("Added 2, skipped 0").
- **The vault cannot be opened.** On `VaultKeyLostException` or `VaultCorruptException` the card offers "Restore from a backup" and "Start a new vault". Restore decrypts the file first, asks "This replaces the unreadable vault", then resets and unlocks; if that unlock is cancelled, the screen offers "Finish restoring". Start a new vault asks first and then resets.
- **Screen capture.** `FLAG_SECURE` is set while the vault is unlocked (on the whole window) and on the scan, by-hand, Check the account, backup and restore screens whatever the state. Holders are counted per window (`SecureWhile`), so the Approve screen and the vault do not clear each other's flag.
- **Auto-lock.** `MainActivity` calls `appLeft()` in `onStop` and `appReturned()` in `onStart`; 60 seconds away locks the vault, a rotation does not.

Not built (non-goals of this version): search, sorting or folders, service logos (the letter tile stays), editing the secret or settings of a saved account, import formats of other authenticator apps, tablet layouts and widgets.

### Obligations from the core reviews

All six obligations the core reviews listed for the UI are met:

- `BiometricUnlocker` runs on the main thread: `MainActivity`'s authenticator switches to `Dispatchers.Main`.
- The authenticator is set in `onStart` and set back to `AppContainer.NO_AUTHENTICATOR` in `onStop` (only if it is still this activity's, so a restarted activity keeps its own). The Activity does not leak.
- All unlocks and resets go through `VaultRepository` (via `VaultViewModel`), never `VaultSession.open()` or `reset()` directly. Reset is offered only from the "cannot be opened" card, after the failed unlock's prompt has finished.
- `appLeft()` and `appReturned()` are called from `MainActivity`'s lifecycle. `MainActivityLifecycleTest` checks only the authenticator (set in `onStart`, set back in `onStop`); the 60-second auto-lock and its 59 999 / 60 000 ms boundary are JVM tests (`VaultViewModelTest`, `VaultRepositoryTest`, `VaultSessionTest`).
- Users are nudged to make a backup: the Overview banner, the Settings subtitle and the note on the Backup screen.
- `VaultKeyLostException` and `VaultCorruptException` lead to the restore or new-vault flow, both confirmed first; the backup is decrypted before `reset()`, so a wrong passphrase costs nothing. `VaultAuthRequiredException` is treated as a cancelled prompt and not reported as an error.

Remaining, known and accepted for now (from the task reviews): cancelling the unlock or a failed write after the save picker can leave an empty or partial backup file; `VaultLockedException` from an unlock is shown as the generic error; the clipboard clear in the background as above. The release build with the screens has not been run on a device.

### Checks done for the screens

Instrumented tests (emulator-5554, API 35) cover the Accounts section, the sheet and dialogs, the add flow, the Backup and Restore screens, the `FLAG_SECURE` holders and the lifecycle; the JVM tests cover the view models, the form validation and the restore flow. By hand on the same emulator, against a test Rostdav: an account added by hand showed codes equal to an independent RFC 6238 calculation; a duplicate was refused; screencap was black while the vault was unlocked; a backup was saved to Downloads, the app data was cleared, and the backup was restored into a new vault (a wrong passphrase was refused first); after clearing and setting the screen lock again, the vault could not be opened, and "Restore from a backup" (with a cancelled prompt and "Finish restoring" on the way) brought the accounts back. The details are in `docs/e2e.md`.

Not verified: scanning a real QR code with the camera (the parsing is unit-tested), "Start a new vault" by hand (covered by JVM and dialog tests), API 28 and 29, StrongBox, a real fingerprint sensor, and a physical phone.
