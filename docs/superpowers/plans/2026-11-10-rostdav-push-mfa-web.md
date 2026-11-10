# Rostdav Push-MFA Sign-in, Enrollment UI and FCM (Plan 2b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user sign in to Rostdav by approving on the RustMFA phone, enroll and revoke phones in the web interface, and wake the phone through FCM.

**Architecture:** Plan 2 built the service (`push_mfa` in `rostdav-app`, `rostdav-mfa-api`). This plan connects it. A phone counts as a second factor next to TOTP (ADR 0014, ADR 0007). The browser and the Login Flow v2 page get a "send to my phone" step on their existing second-step page. The step is bound to the password step's ticket, so a challenge can only be released to the browser that passed the password. Device pages go into the existing `/devices` page of `rostdav-web`. FCM is a `PushChannel` adapter in `rostdav-app` that calls the HTTP v1 API with `ureq`.

**Tech Stack:** Rust 2024, axum 0.8, askama-free string templates of `rostdav-web`, plain JS files under `/static` (CSP `script-src 'self'`), `ureq` 3 (already a dependency), `ring` (RS256 for the service-account JWT), Turso via `rostdav_meta::sql`.

**Spec:** `docs/superpowers/specs/2026-11-08-rustmfa-android-design.md` (sections 2.2, 3, 4.1, 4.4, 4.8). Rostdav side: `docs/adr/0014-push-mfa.md`.
**Previous plan:** `docs/superpowers/plans/2026-11-08-rostdav-push-mfa-backend.md` (Tasks 0 to 10 done, branch `feat/push-mfa`).
**Target repo:** `/home/jochem/Repositories/rostdav`, branch `feat/push-mfa`.

## Global Constraints

Everything in Plan 2's Global Constraints still applies (AGENTS.md rules, no `unwrap` on runtime input, hashed tokens, no secrets in logs, parameterised SQL, negative authorization tests, CHANGELOG, docs, `rostdav.example.toml`, `CLAUDE.md` state in the same commit, `cargo fmt`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace --no-fail-fast`). In addition:

- A change to how people sign in is an approval point. ADR 0014 was accepted with this design, so no new approval is needed, but Task 1 changes who must use app passwords on WebDAV: say so in its commit and in the changelog.
- Sign-in challenge: 90 s default, two-digit number, five challenges per user in 15 minutes (already enforced by `mfa_create_challenge`).
- Browser pages: CSRF on every POST (`login_csrf_check` / `csrf_ok`), same-origin check, `Cache-Control: no-store`, German and English texts through `t(lang, …)`, no inline script (CSP), no inline style.
- The number is shown only on the sign-in page. It never goes into a push message, a log line or an audit detail.
- Push payload: data message with `challenge_id` only, high priority.
- QR payload for enrollment (new, written into the spec by Task 5): `rustmfa://enroll?server=<percent-encoded base URL>&token=<token>`.
- Tests run in the background when they follow a `cargo clean -p`; do not clean unasked (disk quota).

**Rulings this plan makes:**
- A user has a second factor when TOTP is on **or** at least one phone is active. Then WebDAV and OCS reject the account password (app passwords still work) and the browser asks for a second step.
- A challenge is never created by a GET. The user presses "Send to my phone" (a POST with CSRF).
- The challenge goes to the most recently seen active phone. The user can still pick the code instead.
- Release of an approved challenge to the browser is single use and checked against the ticket (`released_at` column, migration 0013 amended in place; the branch is unmerged).
- The FCM credentials are a service-account JSON file, configured by path, never stored in the database.

## Review Focus

- A second browser tab or another user's ticket polls or releases a challenge it did not start: must get "not found", never the session (Task 2).
- Two tabs release the same approved challenge: exactly one gets a session (Task 2).
- The last phone is revoked and TOTP is off: password-only sign-in and WebDAV account password work again, and no stale challenge can still release (Tasks 1, 5).
- The ticket expires, or the user switches to the code, while the phone approves: the release fails and the code path is unharmed (Tasks 2, 3).
- A phone request page prefetched or reloaded: no second challenge is created by a GET or by a reload of the POST result (Task 3).
- FCM returns an error, a timeout, or `UNREGISTERED`: sign-in still works through polling, and no log line holds the push token or the access token (Task 6).

---

### Task 1: A phone is a second factor

**Files:**
- Modify: `crates/rostdav-meta/src/push_mfa.rs` (new `mfa_active_device_count`), `crates/rostdav-app/src/push_mfa/mod.rs`, call sites `crates/rostdav-app/src/web.rs:79`, `login_flow.rs:118`, `auth.rs:174`
- Test: `crates/rostdav-app/src/push_mfa/factor_tests.rs` (new, `#[cfg(test)] mod` in `mod.rs`), reuse `testkit`

**Interfaces:**
- Produces: `Db::mfa_active_device_count(user_id: i64) -> Result<i64>`; `AppState::has_second_factor(&self, user_id: i64) -> Result<bool>` (true if `totp_enabled` or the count is above 0).
- The three call sites replace `totp_enabled(...)` with `has_second_factor(...)`. The TOTP management calls in `two_factor.rs` stay unchanged.

- [ ] **Step 1: Write the failing tests** in `factor_tests.rs`, one per assertion: `a_user_with_only_a_phone_has_a_second_factor`; `revoking_the_last_phone_removes_it`; `a_phone_makes_web_sign_in_ask_for_the_second_step` (`web_sign_in` returns `SignIn::SecondFactor`); `a_phone_makes_webdav_reject_the_account_password` (`authenticate_with(..., allow_app_password = true)` returns `Unauthenticated`, an app password still works); `a_user_without_phone_or_totp_is_unchanged`; `a_revoked_phone_does_not_count`.
- [ ] **Step 2: Run** `cargo test -p rostdav-app --lib push_mfa::factor` . Expected: FAIL (`has_second_factor` missing).
- [ ] **Step 3: Implement** the repository method (`SELECT COUNT(*) FROM mfa_devices WHERE user_id = ? AND status = 'active'`), `has_second_factor`, and switch the three call sites.
- [ ] **Step 4: Run** the new tests and `cargo test -p rostdav-app`. Expected: PASS. Mutation check: make `has_second_factor` ignore the device count, the phone tests must fail.
- [ ] **Step 5: Docs and commit.** `docs/admin.md` (WebDAV account password is rejected once a phone is enrolled), CHANGELOG, ADR 0014 note. `git commit -m "feat(mfa): an enrolled phone is a second factor"`.

---

### Task 2: Bind a challenge to one sign-in and release it once

**Files:**
- Modify: `crates/rostdav-meta/migrations/0013_push_mfa.sql` (column `released_at INTEGER`), `crates/rostdav-meta/src/push_mfa.rs`, `crates/rostdav-app/src/push_mfa/mod.rs`
- Create: `crates/rostdav-app/src/push_mfa/login.rs`, `login_tests.rs`

**Interfaces:**
- Consumes: `second_factor_ticket(user_id, flow) -> String`, `second_factor_ticket_user(ticket, flow) -> Option<i64>` (`two_factor.rs`), `mfa_create_challenge`, `mfa_challenge_status`, `crate::secrets::{mac, mac_eq}`.
- Produces:
  - `Db::mfa_challenge_release(id: &str, login_ref: &str, now: i64) -> Result<bool>`: one conditional `UPDATE … SET released_at = ? WHERE id = ? AND login_ref = ? AND status = 'approved' AND released_at IS NULL`.
  - `AppState::mfa_login_start(&self, ticket: &str, flow: &str, login_device: &str, ip: IpAddr) -> Result<LoginChallenge, MfaError>` where `LoginChallenge { challenge_id: String, number: i64, expires_in_seconds: i64 }`. Picks the most recently seen active phone, `login_ref` is the first 43 characters of base64url of `mac(server_key, ["mfa-login", ticket])`. No active phone is `MfaError::NotFound`.
  - `AppState::mfa_login_status(&self, ticket: &str, flow: &str, challenge_id: &str) -> Result<ChallengeStatus, MfaError>`: `NotFound` if the ticket is invalid, expired, or the challenge has another `login_ref`.
  - `AppState::mfa_login_release(&self, ticket: &str, flow: &str, challenge_id: &str) -> Result<i64 /* user_id */, MfaError>`: `NotFound` under the same rule, `AlreadyConsumed` on a second call, `InvalidRequest("challenge is not approved")` while pending, `Expired` when expired, `InvalidRequest("challenge was denied")` when denied. No new `MfaError` variant. It audits `mfa.login_released`.

- [ ] **Step 1: Write the failing tests** in `login_tests.rs`: `start_creates_a_challenge_for_the_newest_active_phone`; `start_without_a_phone_is_not_found`; `status_of_another_tickets_challenge_is_not_found`; `an_expired_ticket_cannot_poll`; `release_after_approve_returns_the_user_once` (second call is `AlreadyConsumed`); `two_concurrent_releases_have_one_winner` (`tokio::join!`); `release_while_pending_fails`; `release_after_deny_fails`; `a_ticket_of_the_login_flow_cannot_release_a_web_challenge` (the `flow` argument is part of the MAC); `start_audits_without_the_number`.
- [ ] **Step 2: Run** `cargo test -p rostdav-app --lib push_mfa::login`. Expected: FAIL (compile error).
- [ ] **Step 3: Amend migration 0013** with the column, add `mfa_challenge_release`, implement the three methods in `login.rs`.
- [ ] **Step 4: Run** the tests plus `cargo test -p rostdav-meta`. Expected: PASS. Mutation checks (each must fail a test): drop the `login_ref` filter in the release statement; drop the `released_at IS NULL` condition; drop the `flow` from the MAC.
- [ ] **Step 5: Docs and commit** (`docs/architecture.md`, ADR 0014 note, CHANGELOG). `git commit -m "feat(mfa): bind a challenge to one sign-in and release it once"`.

---

### Task 3: Phone step in the browser sign-in

**Files:**
- Modify: `crates/rostdav-web/src/twofactor.rs` (`second_step_card`, `StepCard`), `crates/rostdav-web/src/lib.rs` (routes), `crates/rostdav-web/src/i18n.rs` (de texts)
- Create: `crates/rostdav-web/src/phone_step.rs`, `crates/rostdav-web/static/phone.js`
- Test: `crates/rostdav/tests/web.rs` (extend, uses the `testkit` fake phone)

**Interfaces:**
- Consumes: Task 2's `mfa_login_*`, `AppState::has_second_factor`, existing cookies `TWOFA_COOKIE` (the ticket) and `LOGIN_COOKIE` (CSRF).
- Produces routes (all under `/login/2fa`): `POST /login/2fa/phone` (starts a challenge, renders the number page), `GET /login/2fa/phone/status?id=<challenge_id>` (JSON `{"status": "pending|approved|denied|expired"}`, `no-store`), `POST /login/2fa/phone/finish` (form `csrf`, `id`; on success sets `SESSION_COOKIE` and clears the other two cookies exactly like `login_second_submit`). The second-step page offers "Send to my phone" when the user has an active phone and keeps the code form when TOTP is on.
- The number page shows the number, the remaining time, and the text that the phone shows three numbers. `phone.js` polls `status` every 2 s and submits the finish form when `approved`; on `denied` or `expired` it reloads the step page. Without JS, a "Continue" button submits the finish form.

- [ ] **Step 1: Write the failing tests** in `tests/web.rs`: `second_step_page_offers_the_phone_when_one_is_enrolled`; `a_get_never_creates_a_challenge`; `phone_post_without_csrf_is_403`; `approve_then_finish_opens_a_session`; `finish_before_approve_is_refused_and_creates_no_session`; `status_of_a_foreign_challenge_is_404`; `a_wrong_number_denies_and_the_page_shows_it`; `the_number_is_in_the_page_but_not_in_the_status_json`; `finish_twice_gives_one_session`; `a_user_with_only_a_phone_and_no_totp_gets_the_phone_step_only`.
- [ ] **Step 2: Run** `cargo test -p rostdav --test web phone`. Expected: FAIL.
- [ ] **Step 3: Implement** the three handlers in `phone_step.rs` and the card. The finish handler calls `mfa_login_release`, then opens the session the same way as `web_second_factor` (factor this into one `AppState::web_open_after_factor(user_id, ip, ua, via: "phone")` in `two_factor.rs`, audit `auth.login` with `{"second_factor": "phone"}`).
- [ ] **Step 4: Run** the tests; run the page once by hand against a real browser if available and state it if not. Add `phone.js` to the static file list and the sizes/CSP tests that enumerate it.
- [ ] **Step 5: Docs and commit** (`docs/ui.md` routes table, `docs/security.md` row S4 status, CHANGELOG, German strings). `git commit -m "feat(mfa): approve a browser sign-in on the phone"`.

---

### Task 4: Phone step in Login Flow v2

**Files:**
- Modify: `crates/rostdav-web/src/login_page.rs`, `crates/rostdav-app/src/login_flow.rs`
- Test: `crates/rostdav/tests/` (the file that tests Login Flow v2 with two-factor; find it with `grep -ln "flow_second_factor\|login/v2" crates/rostdav/tests/*.rs`)

**Interfaces:**
- Consumes: `FlowStep::SecondFactor { ticket }`, `mfa_login_start/status/release` with `flow = login_token`.
- Produces: `AppState::flow_phone_start(login_token, csrf, ticket, ip, ua) -> Result<LoginChallenge>`, `flow_phone_status(login_token, ticket, challenge_id)`, `flow_phone_finish(login_token, csrf, ticket, challenge_id, ip) -> Result<()>` (calls `mfa_login_release`, then `grant_flow_to`). The page keeps its state in the form (hidden `ticket`, no cookies), so the phone buttons are submit buttons of the same form with `action` values `phone`, `phone_finish`; the status poll is `GET /login/v2/flow/<token>/phone-status?ticket=…&id=…` and `phone.js` is reused.

- [ ] **Step 1: Write the failing tests:** `flow_with_a_phone_grants_the_app_password_after_approve`; `flow_finish_with_a_ticket_of_another_flow_is_refused`; `flow_phone_start_needs_csrf`; `flow_finish_twice_grants_once`; `a_denied_challenge_grants_nothing_and_the_poller_gets_no_password`.
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement** the three methods and the form actions.
- [ ] **Step 4: Run** the tests and the whole `-p rostdav` suite. Expected: PASS. Mutation check: skip `mfa_login_release` in `flow_phone_finish`, the grant tests must fail.
- [ ] **Step 5: Commit** `feat(mfa): approve a Login Flow v2 sign-in on the phone` (CHANGELOG, `docs/api.md` note).

---

### Task 5: Enrollment and phone list in the web interface

**Files:**
- Modify: `crates/rostdav-web/src/pages_more.rs` (the `/devices` page), `crates/rostdav-web/src/lib.rs`, `crates/rostdav-web/src/i18n.rs`, `/home/jochem/Repositories/rostdav-mfa/docs/superpowers/specs/2026-11-08-rustmfa-android-design.md` (section 4.1: QR payload)
- Create: `crates/rostdav-web/src/phones.rs`
- Test: `crates/rostdav/tests/web.rs`

**Interfaces:**
- Consumes: `mfa_create_enroll_token(&Principal) -> Result<EnrollToken, MfaError>`, `mfa_list_devices(user_id)`, `mfa_revoke_device(...)` (check its signature in `devices.rs:77`), `mfa_audit_events(user_id)`, the QR helper used by `two_factor_begin`, the configured public base URL.
- Produces routes: `POST /devices/phones/enroll` (password re-entered, CSRF; shows the QR and a countdown of `expires_in_seconds`, `no-store`), `POST /devices/phones/revoke` (form `device_id`, CSRF). The `/devices` page gets a "Phones (RustMFA)" section: name, status, last seen, StrongBox badge, revoke button, and the last events from `mfa_audit_events`.
- Add `fn enroll_qr_payload(base_url: &str, token: &str) -> String` returning `rustmfa://enroll?server=<percent-encoded>&token=<token>`.

- [ ] **Step 1: Write the failing tests:** `enroll_needs_the_password_and_csrf`; `enroll_page_shows_a_qr_and_no_token_in_the_url_or_logs`; `enroll_without_a_session_redirects_to_login`; `the_sixth_token_in_15_minutes_is_429`; `revoke_removes_the_phone_and_ends_its_challenges`; `revoking_another_users_phone_is_404`; `revoke_needs_csrf`; `the_list_shows_only_own_phones`; unit test `enroll_qr_payload_escapes_the_server_url`.
- [ ] **Step 2: Run** `cargo test -p rostdav --test web phones` and the unit test. Expected: FAIL.
- [ ] **Step 3: Implement** `phones.rs` and the section. Add the payload format to spec 4.1 in the `rostdav-mfa` repo in the same step.
- [ ] **Step 4: Run** the tests. Expected: PASS. Mutation check: remove the user filter on revoke, the foreign-phone test must fail.
- [ ] **Step 5: Commit** in both repos: `docs(spec): enrollment QR payload` (rostdav-mfa) and `feat(mfa): enroll and revoke phones in the web interface` (Rostdav; `docs/ui.md`, `docs/admin.md`, CHANGELOG).

---

### Task 6: FCM push adapter

**Files:**
- Modify: `crates/rostdav/src/config.rs` (`[mfa.fcm]`: `project_id: Option<String>`, `service_account_file: Option<PathBuf>`), `rostdav.example.toml`, `crates/rostdav/src/lib.rs` (install the channel), `crates/rostdav-app/Cargo.toml` if a base64 or JSON helper is missing
- Create: `crates/rostdav-app/src/push_mfa/fcm.rs`, `fcm_tests.rs`

**Interfaces:**
- Consumes: `PushChannel::notify(&self, channel: &str, token: &str, challenge_id: &str) -> PushFuture<'_>`.
- Produces: `pub struct FcmPush` with `FcmPush::from_service_account(project_id: &str, json: &str) -> Result<FcmPush, String>` and `FcmPush::with_endpoints(self, token_url: String, send_base: String) -> FcmPush` (for tests). `notify` for channel `fcm` does: get or refresh an OAuth2 access token (JWT RS256 signed with `ring`, scope `https://www.googleapis.com/auth/firebase.messaging`, cached until 60 s before expiry), then `POST {send_base}/v1/projects/{project}/messages:send` with `{"message":{"token":…,"data":{"challenge_id":…},"android":{"priority":"HIGH"}}}`. Other channels return `Ok(())` after a debug log (UnifiedPush is a later phase). All `ureq` calls run in `tokio::task::spawn_blocking` with a 5 s timeout. Error strings contain the HTTP status and Google's `error.status`, never a token, key or response body.
- `main`/`lib` installs `FcmPush` when both settings are present and fails start-up with a message naming the key when the file is unreadable or not a service account.

- [ ] **Step 1: Write the failing tests** in `fcm_tests.rs` with a local `std::net::TcpListener` fake serving both endpoints: `sends_a_data_message_with_the_challenge_id_only` (body has no `number`, no other data key); `high_priority_is_set`; `the_jwt_is_rs256_with_the_right_claims` (verify with the public key); `the_access_token_is_reused_until_it_expires` (one token request for two sends); `an_error_response_gives_a_short_error_without_secrets` (assert the returned `String` contains neither the push token nor the access token); `unregistered_is_reported_as_an_error_text_not_a_panic`; `a_timeout_returns_an_error`; `unifiedpush_channel_is_a_noop`. Config tests: `fcm_settings_need_both_keys_or_none`; `an_unreadable_service_account_stops_start_up`.
- [ ] **Step 2: Run** `cargo test -p rostdav-app --lib push_mfa::fcm`. Expected: FAIL.
- [ ] **Step 3: Implement** `fcm.rs` and the config. Generate the test RSA key at test time with `ring`'s `RsaKeyPair` only if a PKCS#8 fixture is checked in under `crates/rostdav-app/src/push_mfa/testdata/` (generate once with `openssl genpkey`, note it in the file header as a throwaway test key).
- [ ] **Step 4: Run** the tests and `cargo clippy`. Expected: PASS. Mutation check: add `number` to the data message, the first test must fail; log the push token in the error path, the secrets test must fail.
- [ ] **Step 5: Docs and commit** (`docs/admin.md` `[mfa.fcm]` and how to create a Firebase service account, `docs/security.md` note that Google sees a "request pending" signal, CHANGELOG, `rostdav.example.toml`). `git commit -m "feat(mfa): FCM push adapter"`.

---

### Task 7: Lockouts page, final docs, verification

**Files:**
- Modify: the admin lockouts view (`grep -n "lockout" crates/rostdav-app/src/admin.rs`), `docs/admin.md`, `docs/plan.md`, `CLAUDE.md`, `CHANGELOG.md`, `docs/adr/0014-push-mfa.md`

**Interfaces:**
- Consumes: `inner.mfa_limiter` keys `mfa-enroll:<user>`, `mfa-register:<ip>`, device failure keys, challenge key.
- Produces: the lockouts page lists blocks of `mfa_limiter` next to the existing ones, with a label per kind; "Unlock" clears the key.

- [ ] **Step 1: Write the failing tests** (next to the existing lockout tests): `mfa_blocks_are_listed`; `unlocking_clears_an_mfa_block`; `a_non_admin_cannot_list_them`.
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement** the listing, remove the two "not listed on the lockouts page yet" sentences from `docs/admin.md`, update plan.md (M14 web part done), CLAUDE.md state, ADR notes.
- [ ] **Step 4: Run** `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace --no-fail-fast` (background). If Docker is available also `scripts/smoke.sh ./target/debug/rostdav` and the container check from `ci.yml`; otherwise state they were not run.
- [ ] **Step 5: Commit** `docs(mfa): lockouts page, state` and report the deferred items.

---

## Self-Review

- **Spec coverage:** 2.2 login binding (Tasks 1, 2, 3, 4); 3 and 4.1 enrollment UI (Task 5); 4.4 number shown in the login UI (Tasks 3, 4); 4.8 FCM data message, high priority, `challenge_id` only (Task 6). Out of this plan: UnifiedPush adapter (Android phase 7), real-device attestation fixture (Plan 3), the CI path dependency (owner decision, before merge).
- **Type consistency:** `LoginChallenge`, `mfa_login_*` defined in Task 2 and used unchanged in Tasks 3 and 4. `has_second_factor` defined in Task 1 and used in Tasks 3 and 4. `enroll_qr_payload` defined in Task 5 only.
- **Review Focus mapping:** foreign ticket and double release (Task 2 tests), last phone revoked (Tasks 1, 5), ticket expiry (Tasks 2, 3), GET creates nothing (Task 3), FCM failure and secrets (Task 6).
- **Known soft spots:** the Login Flow v2 page keeps state in the form, so Task 4 has the most template work. The real FCM service has not been called; the fake server proves the request shape only until a staging project exists.
- **Open before execution:** (1) whether the browser step should also offer the phone when TOTP is on (the plan says yes, both are shown). (2) the JWT test key fixture. (3) the public base URL setting's name, which Task 5 must look up.
