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
- [ ] **Backups.** `adb backup` or a cloud restore to another phone does not carry the enrollment (the keys cannot move); the new phone starts at onboarding.
