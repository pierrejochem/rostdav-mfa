# End-to-end run and physical-phone checklist

## Emulator run against a real Rostdav

Date: 2026-11-15. Emulator `emulator-5554` (AVD `nc-test`, API 35, one virtual fingerprint, PIN 1234). Rostdav 0.10.0 built from the branch `feat/push-mfa` of the Rostdav repository (commit `5a6b81e`). App: `nopush` debug build, so the phone registers with the channel `none` and polls.

### Set-up

Configuration file for the test server (the data directory is a scratch directory):

    [server]
    listen = "127.0.0.1:18080"
    public_url = "http://10.0.2.2:18080"

    [storage]
    data_dir = "<scratch>/data"

    [mfa]
    attestation = "off"
    challenge_ttl_seconds = 90

Commands, from the Rostdav repository:

    cargo build -p rostdav
    echo 'e2e-test-password-1' | rostdav --config <scratch>/rostdav.toml admin user add e2e
    rostdav --config <scratch>/rostdav.toml serve

`attestation = "off"` is needed because an emulator has no hardware-backed attestation. The emulator reaches the host's loopback through `10.0.2.2`. The debug build allows cleartext HTTP; the release build does not.

App, from `android/` (JDK: Temurin 21):

    ./gradlew :app:installNopushDebug
    adb shell pm clear app.rustmfa.android
    adb shell am start -n app.rustmfa.android/.MainActivity

### Steps and results

| Step | How | Result |
| --- | --- | --- |
| Enroll | Web: sign in, Devices & security, Add phone, password, copy the link under "Scanning does not work". App: Enter by hand, server `http://10.0.2.2:18080`, the token, Connect | Passed. The phone shows on the web page and in the app's Devices tab. |
| Sign-in found by the app | Web: sign in, "Send to my phone" (shows the number). Wait for the app's poller (up to 15 s) | Passed. The request appears on Overview. |
| Approve | App: open the request, pick the number the browser shows, then `adb -e emu finger touch 1` | Passed. The app says "Approved"; the browser went on to `/files`. |
| Deny | Same, tap Deny, then the fingerprint | Passed. The app says "Request denied"; the browser stayed on the second step. |
| Wrong number | Same, pick a number the browser does not show, then the fingerprint | Passed. The app says "Wrong number. The request was denied." |
| Expired | Send a request, leave it for more than 90 s | Passed. The poller drops it from Overview; with the Approve screen open the app says "Expired"; the audit list shows "Request expired". |
| Revoke from the web | Web: Devices & security, revoke the phone (password asked, it is the only second factor) | Passed. Within one poll the app returns to onboarding. |
| Revoke from the app | App: Devices, Remove on "This phone", confirm | Passed. The server call goes out first, then the keys and the enrollment go and the app returns to onboarding. |
| Revoke while Approve is open | Open a request in the app, revoke the phone on the web (password asked), wait over 15 s, tap Done | Passed (second run, after a crash fix). The Approve screen stays and says "This phone was removed on the server."; Done restarts at onboarding. |
| Screen capture block | `adb exec-out screencap -p` on the Approve screen | Passed. The picture is black (`FLAG_SECURE`). |
| Biometric prompt | The real `BiometricPrompt` with the emulator's fingerprint | Passed on the emulator. A real sensor is in the checklist below. |

The audit list in the app showed the run (newest first): request created, approved, denied, wrong number picked, expired.

### Problems the run found (fixed in `a44f17f`)

- The debug manifest allowing cleartext was in `src/main/debug`, which Gradle ignores: registration failed with "server could not be reached".
- After onboarding, Overview showed an empty server and the Approve screen stayed blank, because the enrollment was read once at start.
- After the phone was removed in the app, the restart came back to a blank Devices tab. It restored the old navigation state.
- The server limits how many sign-in requests one user can start. After about ten in a few minutes it answered "Too many requests"; restarting the test server cleared it.

### Found after the hold-the-restart change

The first version of the change held the restart but left the poller running. On its next poll it signed a request with the deleted key and the app crashed (`IllegalStateException: no key rustmfa.request`). The hold now cancels the session job and the Approve screen shows the removed-phone result at once.

### Run with the new design

Date: 2026-11-15. Same emulator (`nc-test`, API 35) and a fresh Rostdav 0.10.1 test server built from the Rostdav `feat/push-mfa` branch; the `nopush` debug build.

| Step | Result |
| --- | --- |
| Enroll by hand (address and token), light and dark | Passed. The phone shows on the Overview. |
| Request found by polling, shown on Overview and Requests with a count badge on the floating bar | Passed. |
| Approve: the browser showed 20, the app offered 36, 85 and 20, pick 20, emulator fingerprint | Passed. The app said "Approved" and the server reported `approved`. The last step, the browser page finishing the login, was not driven (the scripted client could not post the finish form); the earlier run in this document did drive it. |
| Settings, Devices, Requests tabs; dark and light | Passed. The Requests and Devices pictures in `media/` (06, 08) were taken here; the Settings picture (07) is now rendered by `VaultScreenshotsTest` (see below). |
| Font size 200% and German (`cmd locale set-app-locales`, `settings put system font_scale 2.0`) | Found two problems: the bar labels were clipped and a long server address squeezed the row title to one letter. Both fixed with tests; screens scroll, nothing else clips. |
| Dark mode text | Found that headings used black on the dark background (no default text colour outside a Surface). Fixed with a test. |
| API 28 and 29 (solid glass) | Not run: no API 28 system image is installed. Covered by the unit test of `glassStyle`. |

### Vault UI by hand

Date: 2026-11-15. Same emulator (API 35, PIN 1234, one virtual fingerprint), the `nopush` debug build, the test Rostdav on `127.0.0.1:18080` with fresh test users per run. Screens were driven with `input tap` and uiautomator dumps, the fingerprint with `adb -e emu finger touch 1`. Only public test secrets were used (`JBSWY3DPEHPK3PXP`, `GEZDGNBVGY3TQOJQ`, `MFRGGZDFMZTWQ2LK`).

| Step | Result |
| --- | --- |
| "Add your first account", fingerprint, Enter by hand, service "Example" with `JBSWY3DPEHPK3PXP` | Passed. The vault was created and the row shows. Three consecutive codes equalled an independent RFC 6238 calculation (Python standard library). |
| Add the same account again | Passed. "This account is already in the vault"; the form stays. The secret was not in the accessibility dump (dots). |
| `adb exec-out screencap -p` while the vault is unlocked | Passed. The picture is black (`FLAG_SECURE`). |
| Make a backup from the "No backup yet" banner: passphrase twice, the system picker in Downloads | Passed. "Backup saved." after about 6 s; the file is a 669-byte `rostdav-mfa` backup (Argon2id m=65536, t=3, p=1). The banner went away; Settings showed "Last backup: Nov 15, 2026". |
| `pm clear`, enroll again, new vault, Add account, Import a backup with a wrong passphrase, then the right one | Passed. "Wrong passphrase or damaged file.", then "Added 2, skipped 0"; the codes matched the RFC 6238 calculation. |
| Clear the screen lock (`locksettings clear`), set it again, enroll the fingerprint again, open the app, Unlock | Passed. "The vault cannot be opened" with "Restore from a backup" and "Start a new vault". |
| Restore from a backup: wrong passphrase, then the right one, Replace, cancel the prompt, Finish restoring | Passed. The wrong passphrase gave the error and no confirm dialog; after the cancelled prompt the screen offered "Finish restoring"; then "Added 2, skipped 0" and the old account was gone. The lock screen was put back (PIN 1234, one fingerprint). |

Not done by hand: scanning a real QR code with the camera (the parsing is unit-tested), "Start a new vault" (JVM and dialog tests), auto-lock after 60 seconds away (JVM tests only: `VaultViewModelTest`, `VaultRepositoryTest` and `VaultSessionTest` cover the 59 999 / 60 000 ms boundary; `MainActivityLifecycleTest` checks only the authenticator). Not verifiable on this emulator: the vault key on API 28 and 29 (the `setUserAuthenticationValidityDurationSeconds` branch), StrongBox (the emulator has none, so the normal Keystore ran), a real fingerprint sensor, and clearing the clipboard while the app is in the background (Android 10 and newer do not let a background app read the clipboard, so the 30-second clear is best effort there).

The vault screenshots in `media/` (04, 05, 07, 11, 12 and 13) are rendered by the opt-in `VaultScreenshotsTest`, because the window cannot be captured while the vault is open.

### Not covered by the emulator

A hostile QR code on a real camera, notification taps, and everything in the next section.

## Physical-phone checklist

Do these on a real phone before relying on the app. Tick and date each one.

- [ ] **Hardware attestation.** Set `[mfa] attestation = "required"` on the server. Enroll with a phone that has a hardware-backed keystore. Registration must succeed. Then try an emulator or a rooted phone with a software keystore: registration must be refused (`INVALID_REQUEST`).
- [ ] **StrongBox.** On a phone with StrongBox, the Devices tab shows the StrongBox badge for the phone, and the server's device list agrees.
- [ ] **Biometric prompt with a real sensor.** Approve and deny each ask for the fingerprint or face (class 3 / strong). Cancelling the prompt leaves the request open and the countdown running. Three wrong tries lock the prompt; the request can be answered after the lock ends or the PIN.
- [ ] **Key invalidated by a second fingerprint.** After enrolling another fingerprint in the system settings, open a request and approve: the app says the key is no longer valid and offers "Reconnect device". After it, the phone is enrolled again from a new QR code. The old phone stays in the web list until it is revoked there.
- [ ] **FCM delivery.** Put your Firebase `google-services.json` in `android/app/` (never commit it), set `[mfa.fcm]` on the server, build `./gradlew :app:assembleFcmDebug`, enroll, close the app, send a request: a notification must appear within seconds, with the service and the login device but no number. Tapping it opens the Approve screen. Check that the phone registered with the channel `fcm`.
- [ ] **No Google services.** Install the `nopush` build on a phone without Play services. It registers with `none`, shows the "notifications are off" hint only when the permission is denied, and finds requests by polling while the app is open.
- [ ] **Notification permission.** On Android 13 or newer the permission is asked once at the first start after enrolling. A denial shows the hint on Overview.
- [ ] **Android 9 and 10.** On API 28 or 29 the approval key is created with a validity of -1 (a fresh biometric each time). Enroll and approve once on such a phone.
- [ ] **Clock.** Set the phone clock a few minutes wrong with automatic time off. Requests still work (the app corrects by the server's `Date`); with a very wrong clock the app tells the user to check the date and time.
- [ ] **Recents and screenshots.** The Approve screen is blank in the recents preview and cannot be captured.
- [ ] **Vault unlock with a real sensor.** Unlock the vault with the fingerprint and with the PIN. Cancelling the prompt leaves it locked and shows no error.
- [ ] **Vault on Android 9 and 10.** Create the vault, add an account, lock and unlock again on API 28 or 29.
- [ ] **Vault key in StrongBox.** On a phone with StrongBox, the vault key is created there (and the vault works).
- [ ] **Clipboard.** Copy a code, leave the app at once, wait 30 seconds: note whether the clip is still there (best effort, expected to stay on Android 10 and newer). With the app open, the clip is cleared.
- [ ] **Screen lock removed.** Make a backup, remove the screen lock, set it again: the vault cannot be opened, and "Restore from a backup" brings the accounts back.
- [ ] **Vault recents and screenshots.** While the vault is open, and on the add and backup screens, the recents preview is blank and a screenshot is refused.
- [ ] **Backups.** `adb backup` or a cloud restore to another phone does not carry the enrollment (the keys cannot move); the new phone starts at onboarding.
