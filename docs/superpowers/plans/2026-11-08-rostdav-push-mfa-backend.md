# Rostdav Push-MFA Backend (Plan 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the push-MFA device and challenge service to the Rostdav server: device enrollment with key attestation, device-signed request authentication, number-matched challenges with signed approve/deny, device management, audit, and the `/api/v1` HTTP API from the contract.

**Architecture:** Follows Rostdav's modular monolith. Tables and repositories go in `rostdav-meta`. All logic goes in a new `push_mfa` module of `rostdav-app`, reachable only through `AppState` methods. A new protocol crate `rostdav-mfa-api` exposes the 10 device endpoints and never touches the database. Signing formats come from the `rustmfa-contract` crate (Plan 1). The login page, push delivery (FCM), device UI, and the enroll-token web route are Plan 2b.

**Tech Stack:** Rust 2024 (Rostdav toolchain), Turso via `rostdav_meta::sql`, axum 0.8, `rustmfa-contract`, `x509-cert` and `der` for attestation, `rcgen` (dev) for synthetic attestation chains.

**Spec:** `docs/superpowers/specs/2026-11-08-rustmfa-android-design.md` (sections 2.2, 4, 6)
**Contract:** `contract/openapi/rustmfa-v1.yaml`, `contract/vectors/v1.json`
**Target repo:** `/home/jochem/Repositories/rostdav`, branch `feat/push-mfa`. Task 0 runs in `/home/jochem/Repositories/rostdav-mfa`.

## Global Constraints

Rostdav rules (from its `AGENTS.md`, binding):
- Change to authentication needs the owner's approval (approval point 3). Task 1 ends with it. No code before ADR 0010 is **Accepted**.
- Protocol crates never access the database. Authorization only through `authorize()` in `rostdav-app`. No `unwrap`/`expect` on runtime input. Typed errors with context.
- Tokens stored only hashed (`secrets::hash_token`), compared in constant time. No tokens, signatures, push tokens, or keys in logs.
- Parameterised queries only. Every foreign key column has a plain index (guarded by `every_foreign_key_has_a_plain_index_for_cascades`).
- Do not leak whether a device, challenge, or user exists to someone who may not know: another user's challenge is `404 RESOURCE_NOT_FOUND`.
- Negative tests for authorization are mandatory. A bug fix starts with a failing test.
- Every change updates `CHANGELOG.md`, `docs/` (`api.md`, `security.md`, `architecture.md`, `admin.md`), `rostdav.example.toml`, and `CLAUDE.md` current state in the same commit. New settings have defaults.
- Before each commit: `just check`. Before the last commit of the plan: `just verify` (or `just check` + `just smoke` and say Docker was unavailable).

Contract values (verbatim from the spec):
- Challenge lifetime 90 s default; enroll token 5 min, single use; request skew 60 s; nonce window 5 min.
- Canonical message and request signing string exactly as in `rustmfa-contract`. Signatures raw R‖S base64url; DER rejected.
- Error body `{"error":{"code","message"}}`. Codes: `INVALID_REQUEST` 400, `UNAUTHENTICATED` 401, `DEVICE_NOT_AUTHORIZED` 403, `DEVICE_REVOKED` 403, `RESOURCE_NOT_FOUND` 404, `CHALLENGE_ALREADY_CONSUMED` 409, `CHALLENGE_NUMBER_MISMATCH` 409, `CHALLENGE_EXPIRED` 410, `RATE_LIMITED` 429, `INTERNAL_ERROR` 500.
- Public keys: base64 (standard) X.509 SPKI DER. Attestation challenge: SHA-256 of the enroll token. Default policy: hardware-backed (TEE or StrongBox) attestation required, software-only rejected.

**Rulings this plan makes on top of the spec (Task 0 writes them into it):**
- The spec said PostgreSQL and SQLx. Rostdav uses Turso (ADR 0008). All storage uses `rostdav_meta::sql`.
- `POST /mfa/challenges` is not an HTTP endpoint. The login flow lives in the same process and calls `AppState::mfa_create_challenge`. It is removed from the OpenAPI file, leaving 11 operations.
- The enroll-token HTTP route needs the web session and CSRF, so it belongs to `rostdav-web` in Plan 2b. Here only `AppState::mfa_create_enroll_token` exists.
- A challenge is created for one explicit device. Plan 2b chooses the device (most recently seen active one).

## Review Focus

- Two concurrent approves of one challenge: exactly one gets 200, the other `CHALLENGE_ALREADY_CONSUMED`; the login is released once.
- Same nonce used twice within 5 minutes, and a nonce older than the window being purged: replay gives `UNAUTHENTICATED`, never a second success.
- Device B of the same user approving device A's challenge gets `DEVICE_NOT_AUTHORIZED`; another user's challenge gets `RESOURCE_NOT_FOUND`.
- Device revoked while a challenge is pending: approve gives `DEVICE_REVOKED` and the challenge never becomes `approved`.
- Enroll token reused, expired, or issued to another user, and a software-only or wrong-challenge attestation: all rejected, no device row created.

---

### Task 0: Align contract and spec with Rostdav (repo `rostdav-mfa`)

**Files:**
- Modify: `contract/openapi/rustmfa-v1.yaml`, `contract/rustmfa-contract/tests/openapi.rs`, `docs/superpowers/specs/2026-11-08-rustmfa-android-design.md`

**Interfaces:**
- Produces: OpenAPI with 11 operations (no `createChallenge`, no `InternalService`, no `CreateChallengeRequest/Response`).

- [ ] **Step 1: Update the test first.** In `tests/openapi.rs` remove `("post", "/api/v1/mfa/challenges")` from `ENDPOINTS` (now 11, change the array type), drop the createChallenge exemption in `device_endpoints_declare_signature_security`, and add `createChallenge_is_not_public`: `paths["/api/v1/mfa/challenges"]["post"]` is null.
- [ ] **Step 2: Run** `cd contract && cargo test -p rustmfa-contract --test openapi`. Expected: `createChallenge_is_not_public` FAILS.
- [ ] **Step 3: Edit the YAML:** delete the `post` operation of `/api/v1/mfa/challenges`, the `InternalService` scheme, and the two schemas. Keep `number` out of every device-facing schema.
- [ ] **Step 4: Edit the spec:** section 2.2 says Turso via `rostdav_meta::sql` instead of SQLx/PostgreSQL; section 4.5 marks challenge creation as an in-process call; add the two rulings about enroll-token and explicit device choice.
- [ ] **Step 5: Run** `cargo test -p rustmfa-contract`. Expected: all PASS.
- [ ] **Step 6: Commit** `git commit -am "docs(contract): challenge creation is in-process; Turso"`.

### Task 1: ADR 0010 and owner approval (repo `rostdav`)

**Files:**
- Create: `docs/adr/0010-push-mfa.md`
- Modify: `docs/plan.md` (new milestone M14 open, approval point 3 pending)

**Interfaces:**
- Produces: an accepted ADR that every later task cites.

- [ ] **Step 1: `git switch -c feat/push-mfa`** in `/home/jochem/Repositories/rostdav`. Run `just check`. Expected: green. If red, stop and report.
- [ ] **Step 2: Write ADR 0010** in the style of ADR 0007: Context (the owner's own authenticator app, spec path), Decision (device keys, request auth, challenges, number matching, attestation default, `rostdav-mfa-api` crate, in-process challenge creation, how it joins the sign-in as an alternative second factor next to TOTP, left to Plan 2b), Storage (the four tables of Task 3), Rules (all contract values above), Dependencies with justification (`rustmfa-contract`, `x509-cert`, `der`, `rcgen` dev-only), Out of scope (FCM, UnifiedPush, web pages, key rotation UI), Consequences (acceptance-test list = this plan's Review Focus). Record the contract-crate decision with these options, recommending (a): (a) git dependency on the `rostdav-mfa` repo pinned by revision, with a local `[patch]` during development; (b) move the crate into Rostdav; (c) vendored copy. Option (a) needs that repo on GitHub first, which is the owner's call.
- [ ] **Step 3: Commit** the ADR with status `Proposed`.
- [ ] **Step 4: STOP.** Ask the owner to accept ADR 0010 (approval point 3) and to answer the contract-crate question. Continue only after status is `Accepted`.

### Task 2: Wire `rustmfa-contract` and the `[mfa]` configuration

**Files:**
- Modify: `Cargo.toml` (workspace dependency), `crates/rostdav-app/Cargo.toml`, `crates/rostdav/src/config.rs`, `rostdav.example.toml`, `docs/admin.md`
- Create: `crates/rostdav-app/src/push_mfa/mod.rs`
- Test: `crates/rostdav/src/config.rs` (existing test module style)

**Interfaces:**
- Produces in `push_mfa/mod.rs`:
  - `pub struct MfaConfig { pub challenge_ttl_secs: i64, pub enroll_ttl_secs: i64, pub audience: String, pub attestation: AttestationPolicy }` with `Default` (90, 300, `"rostdav"`, `Required`)
  - `pub enum AttestationPolicy { Required, Off }`
  - `AppState::mfa_cfg(&self) -> &MfaConfig`

- [ ] **Step 1: Write the failing tests:** `mfa_defaults_match_spec` (default config gives 90, 300, `"rostdav"`, `Required`), `mfa_section_overrides_defaults` (TOML `[mfa] challenge_ttl_seconds = 60` gives 60), `mfa_rejects_ttl_below_10_or_above_300` (config load error naming the key).
- [ ] **Step 2: Run** `cargo test -p rostdav mfa_ -- --nocapture`. Expected: FAIL, no `mfa` field.
- [ ] **Step 3: Implement** the config section `[mfa]` (`challenge_ttl_seconds`, `enroll_ttl_seconds`, `audience`, `attestation = "required" | "off"`), validation, and pass it into `AppState` the way the existing `AuditOptions` is passed. Add the dependency per ADR 0010 and a smoke compile `use rustmfa_contract::canonical_message` in `mod.rs`.
- [ ] **Step 4: Document** the section in `rostdav.example.toml` and `docs/admin.md`.
- [ ] **Step 5: Run** `just check`. Expected: PASS.
- [ ] **Step 6: Commit** `feat(mfa): configuration and contract dependency`.

### Task 3: Migration 0013 and the meta repository

**Files:**
- Create: `crates/rostdav-meta/migrations/0013_push_mfa.sql`, `crates/rostdav-meta/src/push_mfa.rs`
- Modify: `crates/rostdav-meta/src/lib.rs` (register migration, `mod push_mfa`)
- Test: `crates/rostdav-meta/src/push_mfa.rs` (`#[cfg(test)]`)

**Interfaces:**
- Produces on `Db` (all `async`, return `rostdav_core::Result`):
  - `mfa_enroll_token_put(&self, hash: &str, user_id: i64, expires_at: i64) -> Result<()>`
  - `mfa_enroll_token_consume(&self, hash: &str, now: i64) -> Result<Option<i64>>` (user id; marks used only if unused and unexpired, one statement)
  - `mfa_device_insert(&self, d: &NewDevice) -> Result<()>`, `mfa_device_get(&self, id: &str) -> Result<Option<DeviceRow>>`, `mfa_devices_of(&self, user_id: i64) -> Result<Vec<DeviceRow>>`, `mfa_device_revoke(&self, id: &str, user_id: i64, now: i64) -> Result<bool>`, `mfa_device_set_push(&self, id: &str, channel: &str, token: &str) -> Result<bool>`, `mfa_device_set_keys(&self, id: &str, request_pub: &str, approval_pub: &str) -> Result<bool>`, `mfa_device_touch(&self, id: &str, now: i64) -> Result<()>`
  - `mfa_nonce_insert(&self, device_id: &str, nonce: &str, expires_at: i64) -> Result<bool>` (false when already present), `mfa_nonces_purge(&self, now: i64) -> Result<u64>`
  - `mfa_challenge_insert(&self, c: &NewChallenge) -> Result<()>`, `mfa_challenge_get(&self, id: &str) -> Result<Option<ChallengeRow>>`, `mfa_challenges_pending(&self, device_id: &str, now: i64) -> Result<Vec<ChallengeRow>>`, `mfa_challenge_resolve(&self, id: &str, to: &str, now: i64) -> Result<bool>` (`UPDATE … SET status = ?, consumed_at = ? WHERE id = ? AND status = 'pending' AND expires_at > ?`, true only when one row changed)
  - structs `NewDevice`, `DeviceRow`, `NewChallenge`, `ChallengeRow` with the columns below
- Columns: `mfa_devices(id TEXT PRIMARY KEY, user_id → users ON DELETE CASCADE, name, status CHECK ('active','revoked'), request_pub, approval_pub, push_channel, push_token, hardware_backed INTEGER, strongbox INTEGER, created_at, last_seen_at, revoked_at)`; `mfa_enroll_tokens(token_hash PRIMARY KEY, user_id → users CASCADE, expires_at, used_at)`; `mfa_challenges(id PRIMARY KEY, user_id → users CASCADE, device_id → mfa_devices CASCADE, login_ref, nonce, number, candidates TEXT JSON, audience, created_at, expires_at, status CHECK ('pending','approved','denied','expired'), consumed_at)`; `mfa_nonces(device_id → mfa_devices CASCADE, nonce, expires_at, PRIMARY KEY (device_id, nonce))`. All STRICT, plain index on every FK column.

- [ ] **Step 1: Write the failing tests:** `enroll_token_is_single_use_and_expires` (second consume returns None; consume after `expires_at` returns None), `nonce_insert_detects_replay` (second insert returns false), `challenge_resolve_is_atomic` (two sequential resolves: true then false; resolve after expiry false), `deleting_user_cascades_devices_challenges_nonces`, `revoke_requires_matching_user` (other user id returns false).
- [ ] **Step 2: Run** `cargo test -p rostdav-meta push_mfa`. Expected: FAIL, tables missing.
- [ ] **Step 3: Write the migration and implement the methods** in the style of `two_factor.rs` (`sql::query(...).bind(...)`, `err()` mapping). Concurrency note: use `Db::writer`/transaction as the existing code does for multi-statement changes; each method here is one statement.
- [ ] **Step 4: Run** `cargo test -p rostdav-meta`. Expected: PASS including `every_foreign_key_has_a_plain_index_for_cascades`. Check `EXPLAIN QUERY PLAN` for the pending-challenges query and note the result in a comment if the planner ignores an index.
- [ ] **Step 5: Commit** `feat(mfa): schema and repository (ADR 0010)`.

### Task 4: Attestation verification

**Files:**
- Create: `crates/rostdav-app/src/push_mfa/error.rs`, `crates/rostdav-app/src/push_mfa/attestation.rs`, `crates/rostdav-app/src/push_mfa/google_roots.rs`
- Modify: `crates/rostdav-app/Cargo.toml` (`x509-cert`, `der`; dev: `rcgen`)
- Test: `crates/rostdav-app/src/push_mfa/attestation.rs` (`#[cfg(test)]`)

**Interfaces:**
- Produces:
  - `pub struct AttestationResult { pub hardware_backed: bool, pub strongbox: bool }`
  - `pub fn verify_attestation(chain_b64: &[String], approval_spki_b64: &str, expected_challenge: &[u8], trust_roots: &[Vec<u8>], now: i64) -> Result<AttestationResult, MfaError>` (roots as DER)
  - `pub fn google_roots() -> Vec<Vec<u8>>` in `google_roots.rs`
  - `MfaError` lives in `push_mfa/error.rs`, created by this task with the full variant list given in Task 5's Interfaces (attestation failures map to `MfaError::InvalidRequest`).

- [ ] **Step 1: Write the failing tests** using `rcgen` to build synthetic chains (root, intermediate, leaf) with the Android key-description extension (OID `1.3.6.1.4.1.11129.2.1.17`, DER sequence: attestationVersion INTEGER, attestationSecurityLevel ENUMERATED, keymasterVersion INTEGER, keymasterSecurityLevel ENUMERATED, attestationChallenge OCTET STRING, uniqueId OCTET STRING, empty softwareEnforced and teeEnforced SEQUENCEs): `accepts_tee_chain` (level 1 gives `hardware_backed`, not `strongbox`), `accepts_strongbox_chain` (level 2 gives both), `rejects_software_level` (level 0), `rejects_wrong_challenge`, `rejects_leaf_key_mismatch` (leaf SPKI differs from `approval_spki_b64`), `rejects_unknown_root`, `rejects_broken_signature_in_chain`, `rejects_expired_certificate`, `rejects_missing_extension`, `rejects_chain_longer_than_8`.
- [ ] **Step 2: Run** `cargo test -p rostdav-app attestation`. Expected: FAIL, function missing.
- [ ] **Step 3: Implement `verify_attestation`.** Decode each certificate, check validity at `now`, verify each certificate's signature with its issuer's key (ECDSA P-256/P-384 and RSA as Android chains use both; use the smallest RustCrypto set already in the lock file and justify new ones in the ADR), require the last certificate to equal or be signed by a trust root, parse the extension, enforce level and challenge, compare the leaf SPKI. Constant-time comparison for the challenge.
- [ ] **Step 4: `google_roots()`:** embed the current Google hardware attestation root certificates. Fetch them from Google's published key-attestation documentation at implementation time, record the source URL and each SHA-256 fingerprint in ADR 0010 and in a comment, and add the test `google_roots_parse_and_are_self_signed`. Never paste certificates from memory.
- [ ] **Step 5: Run** `just check`. Expected: PASS.
- [ ] **Step 6: Commit** `feat(mfa): key attestation verification`.

### Task 5: Enrollment service

**Files:**
- Create: `crates/rostdav-app/src/push_mfa/enroll.rs`
- Modify: `crates/rostdav-app/src/push_mfa/mod.rs`, `crates/rostdav-app/src/lib.rs` (`mod push_mfa`)
- Test: `crates/rostdav-app/src/push_mfa/enroll_tests.rs`

**Interfaces:**
- Consumes: Task 3 repository, Task 4 `verify_attestation`, `secrets::{random_token, hash_token}`, `RateLimiter`.
- Produces:
  - (defined in Task 4's `error.rs`, listed here because Tasks 5 to 9 use it) `pub enum MfaError { InvalidRequest(&'static str), Unauthenticated, DeviceNotAuthorized, DeviceRevoked, NotFound, AlreadyConsumed, NumberMismatch, Expired, RateLimited { retry_after_secs: u64 }, Internal(rostdav_core::Error) }` with `fn code(&self) -> &'static str`, `fn status(&self) -> u16`, `fn message(&self) -> &'static str`, and `From<rostdav_core::Error>`
  - `pub struct EnrollToken { pub token: String, pub expires_in_seconds: i64 }`
  - `pub struct RegisterInput { pub enroll_token: String, pub device_name: String, pub request_public_key: String, pub approval_public_key: String, pub key_algorithm: String, pub attestation_chain: Vec<String>, pub push_channel: String, pub push_token: String }`
  - `pub struct Registered { pub device_id: String, pub status: &'static str }`
  - `AppState::mfa_create_enroll_token(&self, p: &Principal) -> Result<EnrollToken, MfaError>`
  - `AppState::mfa_register_device(&self, input: RegisterInput, ip: IpAddr) -> Result<Registered, MfaError>`
  - test hook `AppState::set_attestation_roots_for_tests(&self, roots: Vec<Vec<u8>>)`

- [ ] **Step 1: Write the failing tests:** `enrollment_happy_path_creates_active_device` (token, synthetic TEE chain whose challenge is `sha256(token)`, device row active, `hardware_backed` true), `token_is_single_use` (second register fails `InvalidRequest`/`Expired` per Task 3 semantics, no second device), `expired_token_is_rejected`, `token_belongs_to_creating_user` (device is stored for that user), `software_attestation_rejected_and_no_device_created`, `policy_off_skips_attestation` (config `Off`, empty chain accepted), `rejects_unknown_key_algorithm_and_bad_spki` (`key_algorithm != "ES256"`), `rejects_unknown_push_channel` (only `fcm`, `unifiedpush`), `register_is_rate_limited_per_ip_after_failures`, `enroll_token_is_stored_only_hashed` (read the table, assert the plain token is absent), `register_writes_audit_event_without_secrets`.
- [ ] **Step 2: Run** `cargo test -p rostdav-app enroll`. Expected: FAIL.
- [ ] **Step 3: Implement.** Token: `random_token`, stored with `hash_token`, TTL `mfa_cfg().enroll_ttl_secs`. Register: validate inputs, consume the token first (atomic), verify attestation with expected challenge `sha256(token bytes)` unless policy `Off`, validate both SPKIs by parsing them with `rustmfa_contract::verify_es256_raw`-compatible P-256 parsing (add `pub fn check_spki(spki_b64) -> Result<(), ContractError>` to the contract crate in a follow-up commit in `rostdav-mfa` if absent), generate id `dev_<ulid lowercase>`, insert, audit `mfa.device_registered`. A failed attestation after consuming the token burns the token; ledger this as intended.
- [ ] **Step 4: Run** `just check`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mfa): device enrollment`.

### Task 6: Device request authentication

**Files:**
- Create: `crates/rostdav-app/src/push_mfa/devauth.rs`
- Test: `crates/rostdav-app/src/push_mfa/devauth_tests.rs`

**Interfaces:**
- Consumes: Task 3 nonce and device methods, `rustmfa_contract::{request_signing_string, verify_es256_raw}`.
- Produces:
  - `pub struct DevicePrincipal { pub device_id: String, pub user_id: i64 }`
  - `pub struct ParsedAuth { pub device_id: String, pub ts: i64, pub nonce: String, pub sig: String }`
  - `pub fn parse_authorization(header: &str) -> Result<ParsedAuth, MfaError>` (strict grammar `RustMFA-Sig device_id="…",ts="…",nonce="…",sig="…"`; each value limited to `[A-Za-z0-9_-]` and 128 bytes, `ts` decimal digits only; anything else `Unauthenticated`)
  - `AppState::mfa_authenticate_device(&self, header: &str, method: &str, path_and_query: &str, body: &[u8]) -> Result<DevicePrincipal, MfaError>` delegating to `mfa_authenticate_device_at(…, now: i64)`

- [ ] **Step 1: Write the failing tests:** `valid_signed_request_authenticates` (signature built with `rustmfa_contract::request_signing_string` and a test key), `skew_over_60_seconds_is_rejected` (past and future), `replayed_nonce_is_rejected`, `nonce_after_window_is_accepted_again_and_old_nonces_purged` (clock moved 301 s), `revoked_device_gives_device_revoked`, `unknown_device_gives_unauthenticated`, `tampered_body_or_query_is_rejected`, `signature_by_approval_key_is_rejected` (wrong key), `grammar_rejects_quotes_commas_newlines_and_oversize`, `failed_auth_is_rate_limited_per_device`, `last_seen_is_updated_on_success`, `no_secret_or_signature_in_logs` (capture tracing output).
- [ ] **Step 2: Run** `cargo test -p rostdav-app devauth`. Expected: FAIL.
- [ ] **Step 3: Implement.** Order: parse, skew, device lookup (active), verify signature with `request_pub`, then insert nonce (so a bad signature cannot burn a nonce), `mfa_nonces_purge` opportunistically, touch `last_seen_at`. Failures use the limiter key `mfa-auth:<device_id>` (5 failures, 15 min, like TOTP). All failures return `Unauthenticated` except revoked (`DeviceRevoked`).
- [ ] **Step 4: Run** `just check`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mfa): device request authentication`.

### Task 7: Challenges, number matching, approve and deny

**Files:**
- Create: `crates/rostdav-app/src/push_mfa/challenges.rs`, `crates/rostdav-app/src/push_mfa/push.rs`
- Test: `crates/rostdav-app/src/push_mfa/challenges_tests.rs`

**Interfaces:**
- Consumes: `DevicePrincipal`, Task 3 methods, `rustmfa_contract::{canonical_message, ChallengeMessage, verify_es256_raw}`.
- Produces:
  - `pub trait PushChannel: Send + Sync { async fn notify(&self, channel: &str, token: &str, challenge_id: &str) -> Result<(), String>; }` and `pub struct NoopPush` (default, logs `challenge_id` only)
  - `pub struct NewChallenge { pub challenge_id: String, pub number: i64, pub expires_in_seconds: i64 }`
  - `pub struct ChallengeDetails { pub challenge_id: String, pub service_name: String, pub login_device: String, pub approximate_location: Option<String>, pub created_at: String, pub expires_at: String, pub status: ChallengeStatus, pub candidate_numbers: [i64; 3], pub nonce: String, pub login_id: String, pub audience: String }`
  - `pub enum ChallengeStatus { Pending, Approved, Denied, Expired }`
  - `AppState::mfa_create_challenge(&self, user_id: i64, device_id: &str, login_ref: &str, login_device: &str, ip: IpAddr) -> Result<NewChallenge, MfaError>`
  - `AppState::mfa_challenge_status(&self, challenge_id: &str) -> Result<ChallengeStatus, MfaError>` (for the login page, used by Plan 2b; lazily maps past-expiry pending to `Expired`)
  - `AppState::mfa_get_challenge(&self, d: &DevicePrincipal, id: &str) -> Result<ChallengeDetails, MfaError>`
  - `AppState::mfa_pending_challenges(&self, d: &DevicePrincipal) -> Result<Vec<ChallengeDetails>, MfaError>`
  - `AppState::mfa_approve(&self, d: &DevicePrincipal, id: &str, selected_number: i64, signature: &str) -> Result<ChallengeStatus, MfaError>`
  - `AppState::mfa_deny(&self, d: &DevicePrincipal, id: &str, signature: &str) -> Result<ChallengeStatus, MfaError>`
  - `AppState::set_push_channel_for_tests(&self, p: Arc<dyn PushChannel>)`

- [ ] **Step 1: Write the failing tests:** `create_makes_distinct_two_digit_number_and_two_decoys` (10..99, three distinct candidates, correct one among them, order random over 200 runs, not always first), `get_hides_the_correct_number` (the `ChallengeDetails` JSON never contains a field marking which candidate is right), `approve_with_valid_signature_consumes_once`, `approve_wrong_number_denies_and_audits` (status `Denied`, `NumberMismatch`), `approve_after_expiry_gives_expired`, `approve_twice_gives_already_consumed`, `concurrent_approves_one_wins` (two tasks, `tokio::join!`, exactly one `Ok`), `approve_by_other_device_of_same_user_gives_not_authorized`, `challenge_of_other_user_gives_not_found`, `revoked_device_cannot_approve_and_challenge_stays_pending` (`DeviceRevoked`), `signature_over_wrong_login_or_device_or_nonce_is_rejected` (use the contract vectors' altered-field idea), `der_signature_is_rejected`, `deny_requires_valid_signature_and_never_approves`, `pending_list_excludes_resolved_and_expired`, `push_failure_does_not_fail_creation_and_is_logged_without_token`, `create_is_rate_limited_per_user`, `audit_events_written_for_create_approve_deny_mismatch_expire`.
- [ ] **Step 2: Run** `cargo test -p rostdav-app challenges`. Expected: FAIL.
- [ ] **Step 3: Implement.** Challenge id `ch_<ulid lowercase>`, nonce 128-bit `random_token`, number and decoys from the OS CSPRNG, `expires_at` formatted exactly `YYYY-MM-DDTHH:MM:SSZ` (reuse `rostdav_core::time` if it already produces that form, else add `format_utc_z(secs: i64) -> String` there with a test). Approve order: authorize ownership (404/403 rules), status and expiry check, number check (mismatch resolves to `denied`), verify signature over `canonical_message` using `approval_pub`, then `mfa_challenge_resolve(id, "approved", now)`; if it returns false map to `AlreadyConsumed` or `Expired` by re-reading. Verify signature **before** resolving so a bad signature never consumes the challenge. Deny: same without number. Push is spawned after commit and its error only logged.
- [ ] **Step 4: Run** `just check`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mfa): challenges with number matching`.

### Task 8: Device management and audit view

**Files:**
- Create: `crates/rostdav-app/src/push_mfa/devices.rs`
- Test: `crates/rostdav-app/src/push_mfa/devices_tests.rs`

**Interfaces:**
- Produces:
  - `pub struct DeviceInfo { pub device_id: String, pub device_name: String, pub status: String, pub push_channel: String, pub hardware_backed: bool, pub strongbox: bool, pub created_at: String, pub last_seen_at: Option<String> }`
  - `AppState::mfa_list_devices(&self, user_id: i64) -> Result<Vec<DeviceInfo>, MfaError>`
  - `AppState::mfa_revoke_device(&self, user_id: i64, device_id: &str, actor: &str) -> Result<(), MfaError>` (used by the device-signed endpoint and by the web session in Plan 2b; closes the "no device left" gap)
  - `AppState::mfa_update_push_token(&self, d: &DevicePrincipal, device_id: &str, channel: &str, token: &str) -> Result<(), MfaError>`
  - `AppState::mfa_rotate_keys(&self, d: &DevicePrincipal, device_id: &str, request_pub: &str, approval_pub: &str, chain: &[String]) -> Result<(), MfaError>` (attestation challenge for rotation is the SHA-256 of the current `nonce`-free string `"rotate:" + device_id + ":" + request_pub`; ledger this ruling because the spec left rotation unattested)
  - `AppState::mfa_audit_events(&self, user_id: i64) -> Result<Vec<MfaAuditEvent>, MfaError>` reading the existing audit store filtered to `mfa.*` actions of that user

- [ ] **Step 1: Write the failing tests:** `list_shows_only_own_devices`, `revoke_marks_revoked_and_pending_challenges_expire`, `revoke_other_users_device_gives_not_found`, `revoked_device_fails_authentication` (uses Task 6), `device_may_revoke_only_devices_of_its_own_user`, `update_push_token_for_other_device_is_forbidden`, `rotate_keys_replaces_both_keys_and_old_signatures_stop_working`, `rotate_requires_valid_attestation_when_policy_required`, `audit_view_contains_only_own_mfa_events_and_no_secrets`.
- [ ] **Step 2: Run** `cargo test -p rostdav-app devices`. Expected: FAIL.
- [ ] **Step 3: Implement** the methods. Revoke sets status and `revoked_at`, resolves the device's pending challenges to `expired`, writes `mfa.device_revoked`.
- [ ] **Step 4: Run** `just check`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mfa): device management and audit view`.

### Task 9: HTTP API crate `rostdav-mfa-api`

**Files:**
- Create: `crates/rostdav-mfa-api/Cargo.toml`, `crates/rostdav-mfa-api/src/lib.rs`, `crates/rostdav-mfa-api/src/error.rs`, `crates/rostdav-mfa-api/tests/flow.rs`
- Modify: `Cargo.toml` (workspace member and dependency), `crates/rostdav/src/http.rs` (mount router), `docs/architecture.md` (crate table and diagram)

**Interfaces:**
- Consumes: all `AppState::mfa_*` methods. Dependency direction: `rostdav-mfa-api` → `rostdav-app`, `rostdav-core`; never `rostdav-meta`.
- Produces: `pub fn router(state: AppState) -> axum::Router` with these 10 routes under `/api/v1`: `POST /devices/register`, `GET /devices`, `DELETE /devices/{device_id}`, `PUT /devices/{device_id}/push-token`, `POST /devices/{device_id}/rotate-key`, `GET /mfa/challenges`, `GET /mfa/challenges/{challenge_id}`, `POST /mfa/challenges/{challenge_id}/approve`, `POST /mfa/challenges/{challenge_id}/deny`, `GET /audit`. (Eleventh contract operation, enroll-token, is Plan 2b.)
- Error mapping `impl IntoResponse for ApiError` producing `{"error":{"code","message"}}` with the status table above; `RateLimited` adds `Retry-After`.

- [ ] **Step 1: Write the failing integration tests** in `tests/flow.rs` using `tower::ServiceExt::oneshot` against `router`, a fake phone that signs with `rustmfa_contract` and test keys, and the synthetic attestation chain helper (share through a `#[cfg(test)]` module exported behind a `test-util` feature of `rostdav-app`): `full_happy_path` (enroll token, register, create challenge via `AppState`, device GETs details, approves with the right number, status `approved`), `register_response_matches_openapi_schema` (validate JSON field names and types against `contract/openapi/rustmfa-v1.yaml` loaded by path, fail with the field name), `unauthenticated_requests_get_401_with_error_body` for every authenticated route, `error_codes_and_statuses_match_the_contract` (table-driven: expired 410, consumed 409, mismatch 409, revoked 403, not found 404, rate limited 429 with `Retry-After`), `body_over_64_kib_is_rejected_413`, `unknown_json_fields_are_rejected`, `query_string_is_part_of_the_signature` (tamper `status=pending` to `status=approved`), `responses_contain_no_candidate_marker_and_no_internal_ids_of_other_users`.
- [ ] **Step 2: Run** `cargo test -p rostdav-mfa-api`. Expected: FAIL, crate missing.
- [ ] **Step 3: Implement** the crate. A small helper reads the body with a 64 KiB limit, calls `mfa_authenticate_device` with the raw `Authorization` header, the method, and the raw path-and-query exactly as received, then parses JSON with `deny_unknown_fields`. Mount the router in `http.rs` without the cookie/CSRF layers of the web crate. Update `docs/architecture.md` and `docs/api.md` (route table, error table, signing description linking the contract).
- [ ] **Step 4: Run** `just check`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(mfa): device HTTP API`.

### Task 10: Documentation, changelog, and the container check

**Files:**
- Modify: `CHANGELOG.md`, `CLAUDE.md` (current state), `docs/security.md`, `docs/admin.md`, `docs/plan.md`, `docs/adr/0010-push-mfa.md` (status stays Accepted, add implementation notes)

- [ ] **Step 1: Write the documentation tests first:** if the repo has a docs consistency check (search `just --list` and `scripts/`), run it and expect it to flag the missing `[mfa]` and `mfa-api` mentions; otherwise skip this step and say so.
- [ ] **Step 2: Update the docs.** `docs/security.md`: threat notes (push is only a hint, number matching, attestation default, hashed enroll tokens, nonce window). `docs/admin.md`: `[mfa]` settings, attestation policy `off` for custom ROMs with the trade-off. `CLAUDE.md` current state: M14 backend implemented, login integration and FCM pending in Plan 2b. `CHANGELOG.md` under Unreleased.
- [ ] **Step 3: Run** `just verify`. Expected: PASS. Without Docker, run `just check` and `just smoke` and state that the container check was not run.
- [ ] **Step 4: Container flow** (only with Docker): build, start with `compose.yaml`, run the fake-phone flow from `tests/flow.rs` against the container over HTTP using `curl` and the signing helper, and record the result in the final report.
- [ ] **Step 5: Commit** `docs(mfa): changelog, security, admin, state`.

---

## Self-Review

- **Spec coverage:** 2.2 and 4.1 to 4.7 map to Tasks 2 to 9. Section 4.8 push: trait and `NoopPush` here, FCM adapter in Plan 2b. Sections 3 (UI), 5 (TOTP vault), 8 phases 2 to 8 belong to Plan 3. Web enroll-token route, login integration, device UI in the web interface: Plan 2b. Rate limits are concrete here (5 failures per 15 min per device, per-IP register limit, per-user challenge limit set by Task 7's implementer in the limiter config, documented in `docs/admin.md`).
- **Conflicts handled:** PostgreSQL/SQLx versus Turso (Task 0, Task 3). Contract's public `POST /mfa/challenges` versus the monolith (Task 0). Contract crate location (Task 1 owner decision).
- **Type consistency:** `MfaError` defined in Task 4/5 and used unchanged in Tasks 6 to 9. `DevicePrincipal` defined in Task 6 and used in 7 to 9. `ChallengeStatus` defined in Task 7 and used in 9.
- **Review Focus:** concurrent approve (Task 7), nonce replay (Task 6), wrong device and wrong user (Task 7), revoked device with pending challenge (Task 7 and 8), enroll token and attestation misuse (Tasks 4 and 5).
- **Known soft spot:** Task 4 is the largest and least certain (real Android chains). Synthetic chains prove the logic only. A real-device chain fixture is added during Plan 3 end-to-end work; until then the policy `required` is verified against synthetic chains only.
