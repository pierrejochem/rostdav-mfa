# RustMFA Contract (Phase 0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce the shared contract between the Android app and the Rostdav `mfa/` module: a Rust reference crate for the signing formats, a JSON test-vector file both sides consume, and the OpenAPI 3.1 spec.

**Architecture:** A small Cargo workspace `contract/` holds `rustmfa-contract`, a pure library with no I/O. It implements the canonical challenge message, the request signing string, DER to raw signature conversion, and ES256 verification. A generator binary writes `contract/vectors/v1.json` from fixed test keys. Tests read that file back. The Rostdav module can later depend on the crate. Android reads the same JSON.

**Tech Stack:** Rust 2021, `p256` (ecdsa, pkcs8), `sha2`, `base64`, `serde`, `serde_json`, `serde_yaml`.

**Spec:** `docs/superpowers/specs/2026-11-08-rustmfa-android-design.md` (sections 4.1 to 4.7)

**Follow-up plans (not in this plan):** Plan 2: Rostdav `mfa/` module (needs an inventory of Rostdav's auth, session, and DB code first). Plan 3: Android app, phases 2 to 7.

## Global Constraints

- Canonical challenge message, in this order: `v1 \n challenge_id \n nonce \n login_id \n device_id \n expires_at \n audience`. UTF-8, `\n` separators, no trailing newline.
- `expires_at` is RFC 3339 UTC and must end with `Z`.
- Signatures are raw 64-byte R‖S, base64url without padding. DER is rejected.
- Request signing string: `METHOD \n PATH-with-query \n ts \n nonce \n base64url(sha256(body))`.
- Request header: `Authorization: RustMFA-Sig device_id="…",ts="…",nonce="…",sig="…"`.
- Skew limit 60 s. Nonce window 5 min. Challenge lifetime 90 s default.
- Error body: `{"error":{"code":"…","message":"…"}}`. Codes: `INVALID_REQUEST`, `UNAUTHENTICATED`, `DEVICE_NOT_AUTHORIZED`, `DEVICE_REVOKED`, `RESOURCE_NOT_FOUND`, `CHALLENGE_ALREADY_CONSUMED`, `CHALLENGE_NUMBER_MISMATCH`, `CHALLENGE_EXPIRED`, `RATE_LIMITED`, `INTERNAL_ERROR`.
- Base path `/api/v1`, HTTPS, JSON.

**Decisions this plan adds to the spec (add them to the spec in Task 5):**
- `ts` is Unix seconds as a decimal string.
- Public keys travel as base64 (standard alphabet) of the X.509 SubjectPublicKeyInfo DER, because that is what Android Keystore exports.

## Review Focus

- Field containing `\n` (for example `nonce`): must be rejected, or an attacker can shift fields in the signed message. Expect `ContractError::InvalidField`.
- `expires_at` with an offset (`+01:00`) or without `Z`: reject. Expect `ContractError::InvalidExpiresAt`.
- DER signature submitted instead of raw: reject. Expect `ContractError::BadSignatureEncoding`.
- Valid signature over a message with one changed field (wrong `login_id`, wrong `device_id`): verify must fail. Expect `ContractError::VerifyFailed`.
- Query string in the request path (`/api/v1/mfa/challenges?status=pending`): must be part of the signed string unchanged.

---

### Task 1: Workspace scaffold and canonical challenge message

**Files:**
- Create: `contract/Cargo.toml` (workspace), `contract/rustmfa-contract/Cargo.toml`, `contract/rustmfa-contract/src/lib.rs`, `contract/rustmfa-contract/src/error.rs`, `contract/rustmfa-contract/src/canonical.rs`, `.gitignore`
- Test: `contract/rustmfa-contract/tests/canonical.rs`

**Interfaces:**
- Produces:
  - `pub enum ContractError { InvalidField(&'static str), InvalidExpiresAt, BadSignatureEncoding, BadPublicKey, VerifyFailed }` in `error.rs`, derives `Debug, PartialEq, Eq`
  - `pub struct ChallengeMessage<'a> { pub challenge_id: &'a str, pub nonce: &'a str, pub login_id: &'a str, pub device_id: &'a str, pub expires_at: &'a str, pub audience: &'a str }`
  - `pub fn canonical_message(m: &ChallengeMessage) -> Result<String, ContractError>`
  - `lib.rs` re-exports all of the above.

- [ ] **Step 1: Initialise the repo.** Run `git init` in `/home/jochem/Repositories/rostdav-mfa`. Create `.gitignore` containing `.superpowers/` and `target/`. Create the workspace and crate manifests with the dependencies listed in the header (`p256` with features `ecdsa`, `pkcs8`; `serde` with `derive`).

- [ ] **Step 2: Write the failing tests** in `tests/canonical.rs`:

```rust
#[test]
fn builds_exact_message() {
    let m = ChallengeMessage { challenge_id: "ch_8a31", nonce: "n0nce", login_id: "login_7f21",
        device_id: "dev_7f21", expires_at: "2026-10-28T15:03:30Z", audience: "rostdav" };
    assert_eq!(canonical_message(&m).unwrap(),
        "v1\nch_8a31\nn0nce\nlogin_7f21\ndev_7f21\n2026-10-28T15:03:30Z\nrostdav");
}
#[test] fn rejects_newline_in_any_field() { /* one case per field, expect InvalidField("<field name>") */ }
#[test] fn rejects_empty_field() { /* expect InvalidField */ }
#[test] fn rejects_offset_expiry() { /* "2026-10-28T16:03:30+01:00" -> InvalidExpiresAt */ }
#[test] fn rejects_expiry_without_z() { /* "2026-10-28T15:03:30" -> InvalidExpiresAt */ }
```

- [ ] **Step 3: Run** `cargo test -p rustmfa-contract --test canonical`. Expected: compile error or FAIL, `canonical_message` not defined.

- [ ] **Step 4: Implement `canonical_message`** in `canonical.rs`. Validate each field is non-empty and contains no `\n`. Validate `expires_at` matches `YYYY-MM-DDTHH:MM:SS(.fff)?Z` with a hand-written check (no date crate). Join with `\n`, no trailing newline.

- [ ] **Step 5: Run** the same command. Expected: all 5 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add .gitignore contract
git commit -m "feat(contract): canonical challenge message"
```

### Task 2: Request signing string

**Files:**
- Create: `contract/rustmfa-contract/src/request.rs`
- Modify: `contract/rustmfa-contract/src/lib.rs` (re-export)
- Test: `contract/rustmfa-contract/tests/request.rs`

**Interfaces:**
- Consumes: `ContractError` (Task 1).
- Produces: `pub fn request_signing_string(method: &str, path_and_query: &str, ts: i64, nonce: &str, body: &[u8]) -> Result<String, ContractError>`

- [ ] **Step 1: Write the failing tests:**

```rust
#[test]
fn builds_signing_string_with_empty_body() {
    // sha256("") base64url no pad = "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU"
    assert_eq!(request_signing_string("GET", "/api/v1/devices", 1793200000, "abc", b"").unwrap(),
        "GET\n/api/v1/devices\n1793200000\nabc\n47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU");
}
#[test]
fn keeps_query_string_unchanged() {
    // path "/api/v1/mfa/challenges?status=pending" appears verbatim in the output
}
#[test] fn uppercases_nothing_and_rejects_lowercase_method() { /* "get" -> InvalidField("method") */ }
#[test] fn rejects_newline_in_path_or_nonce() { /* InvalidField("path") / InvalidField("nonce") */ }
```

- [ ] **Step 2: Run** `cargo test -p rustmfa-contract --test request`. Expected: FAIL, function not defined.

- [ ] **Step 3: Implement `request_signing_string`.** Method must be one of `GET POST PUT DELETE` in uppercase. Hash the body with `sha2::Sha256` and encode with `base64::engine::general_purpose::URL_SAFE_NO_PAD`.

- [ ] **Step 4: Run** the command again. Expected: PASS.

- [ ] **Step 5: Commit** `git commit -m "feat(contract): request signing string"`.

### Task 3: Signature encoding and ES256 verification

**Files:**
- Create: `contract/rustmfa-contract/src/sig.rs`
- Modify: `contract/rustmfa-contract/src/lib.rs` (re-export)
- Test: `contract/rustmfa-contract/tests/sig.rs`

**Interfaces:**
- Consumes: `ContractError` (Task 1).
- Produces:
  - `pub fn der_to_raw(der: &[u8]) -> Result<[u8; 64], ContractError>` (the conversion the Android side must implement identically)
  - `pub fn verify_es256_raw(spki_der_b64: &str, message: &[u8], sig_b64url: &str) -> Result<(), ContractError>`

- [ ] **Step 1: Write the failing tests.** Generate a key inside the test with `p256::ecdsa::SigningKey::random`. Tests:
  - `valid_raw_signature_verifies`: sign a message, take the raw 64 bytes, base64url, verify returns `Ok(())`.
  - `der_signature_is_rejected`: sign, encode as DER, base64url, verify returns `Err(BadSignatureEncoding)` (a DER blob is not 64 bytes).
  - `tampered_message_fails`: change one byte of the message, expect `Err(VerifyFailed)`.
  - `bad_public_key_is_rejected`: garbage base64 and valid base64 of non-SPKI bytes both give `Err(BadPublicKey)`.
  - `der_to_raw_pads_short_integers`: build a DER signature whose R is 31 bytes, assert the output is 64 bytes with R left-padded by one zero.
  - `high_s_signature_is_accepted`: flip S to `n - s`, expect `Ok(())` (decision: both forms accepted, Android does not guarantee low-S; replay safety comes from single-use challenges).

- [ ] **Step 2: Run** `cargo test -p rustmfa-contract --test sig`. Expected: FAIL.

- [ ] **Step 3: Implement both functions.** `verify_es256_raw` decodes the key with `VerifyingKey::from_public_key_der` after standard base64 decoding. It requires the decoded signature to be exactly 64 bytes, then builds a `Signature` with `from_slice` and verifies with `verify`. Do not call `normalize_s` before verifying. `der_to_raw` uses `Signature::from_der` and `to_bytes`.

- [ ] **Step 4: Run** the command again. Expected: all PASS.

- [ ] **Step 5: Commit** `git commit -m "feat(contract): ES256 raw signature verify and DER conversion"`.

### Task 4: Shared test vectors

**Files:**
- Create: `contract/rustmfa-contract/src/bin/gen_vectors.rs`, `contract/vectors/v1.json`, `contract/vectors/README.md`
- Test: `contract/rustmfa-contract/tests/vectors.rs`

**Interfaces:**
- Consumes: `canonical_message`, `request_signing_string`, `verify_es256_raw`, `der_to_raw`.
- Produces: `contract/vectors/v1.json` with this shape, consumed by the Android plan:

```json
{ "version": 1,
  "key": { "spki_der_b64": "…", "pkcs8_der_b64": "…" },
  "challenge_cases": [ { "name": "…", "fields": { "challenge_id": "…", "nonce": "…", "login_id": "…", "device_id": "…", "expires_at": "…", "audience": "…" }, "message": "…", "signature_raw_b64url": "…", "signature_der_b64url": "…", "expect": "ok" | "InvalidField" | "InvalidExpiresAt" | "BadSignatureEncoding" | "VerifyFailed" } ],
  "request_cases": [ { "name": "…", "method": "…", "path": "…", "ts": 0, "nonce": "…", "body_b64": "…", "signing_string": "…", "signature_raw_b64url": "…", "expect": "…" } ] }
```

- [ ] **Step 1: Write the failing test** `vectors_file_matches_implementation` in `tests/vectors.rs`. It loads `../vectors/v1.json` and for every case recomputes the message or signing string, compares it to the stored string, runs `verify_es256_raw` against the stored signature, and asserts the result equals `expect`. A second test, `vectors_cover_required_cases`, asserts the file contains cases named: `valid_challenge`, `newline_in_nonce`, `offset_expiry`, `der_signature_instead_of_raw`, `wrong_login_id`, `wrong_device_id`, `request_with_query`, `request_with_body`.

- [ ] **Step 2: Run** `cargo test -p rustmfa-contract --test vectors`. Expected: FAIL, file missing.

- [ ] **Step 3: Implement `gen_vectors.rs`.** Use a fixed 32-byte private scalar constant (documented in the README as a public test key, never used in production) so output is reproducible. For `wrong_login_id` and `wrong_device_id`, sign the correct message and store a stored message with the altered field, so `verify` expects `VerifyFailed`. For the two error-expectation cases (`newline_in_nonce`, `offset_expiry`) store no signature and expect the matching `ContractError` name from message building. Write pretty JSON to `contract/vectors/v1.json`. Run it once: `cargo run -p rustmfa-contract --bin gen_vectors`.

- [ ] **Step 4: Write `contract/vectors/README.md`:** field meanings, "test key only", and the rule that Android's unit tests must load this file and reproduce every `message` and `signing_string`, verify signatures, and apply the same `expect`.

- [ ] **Step 5: Run** `cargo test -p rustmfa-contract`. Expected: all tests across all files PASS.

- [ ] **Step 6: Commit** `git commit -m "feat(contract): shared v1 test vectors"`.

### Task 5: OpenAPI 3.1 spec and spec updates

**Files:**
- Create: `contract/openapi/rustmfa-v1.yaml`
- Modify: `docs/superpowers/specs/2026-11-08-rustmfa-android-design.md` (add the two decisions from this plan's header to section 4, and note the two-key `register` body under 4.1)
- Test: `contract/rustmfa-contract/tests/openapi.rs`

**Interfaces:**
- Consumes: error codes and endpoint list from the spec.
- Produces: `contract/openapi/rustmfa-v1.yaml`, the document Plan 2 and Plan 3 implement against.

- [ ] **Step 1: Write the failing tests** in `tests/openapi.rs`, parsing the YAML with `serde_yaml::Value`:
  - `openapi_version_is_3_1`: `openapi` field starts with `3.1`.
  - `all_endpoints_present`: a table of the 12 `(method, path)` pairs from spec section 4.5 (all under `/api/v1`), each must exist in `paths`.
  - `all_error_codes_listed`: every code in the Global Constraints list appears in the `ErrorCode` schema enum.
  - `register_requires_both_keys`: the `RegisterRequest` schema requires `enroll_token`, `device_name`, `request_public_key`, `approval_public_key`, `key_algorithm`, `attestation_chain`, `push_channel`, `push_token`.
  - `approve_requires_selected_number_and_signature`: `ApproveRequest` requires `device_id`, `selected_number`, `signature`.
  - `device_endpoints_declare_signature_security`: every operation except `register` references the `RustMFASig` security scheme.

- [ ] **Step 2: Run** `cargo test -p rustmfa-contract --test openapi`. Expected: FAIL, file missing.

- [ ] **Step 3: Write `rustmfa-v1.yaml`.** Cover all 12 endpoints with request and response schemas from the spec, the `ErrorResponse` and `ErrorCode` schemas, status codes per section 4.7, `securitySchemes.RustMFASig` as an `apiKey` in header `Authorization` described by the format in Global Constraints, and `RegisterRequest.attestation_chain` as an array of base64 DER certificates. Challenge details response includes `candidate_numbers` (array of three integers), `service_name`, `login_device`, `approximate_location`, `created_at`, `expires_at`, `status`. Rate-limit values stay out of the spec: document `429` only.

- [ ] **Step 4: Update the design spec.** Add the `ts` and public-key encoding decisions to section 4, and state in 4.1 that `register` carries `request_public_key` and `approval_public_key`.

- [ ] **Step 5: Run** `cargo test -p rustmfa-contract`. Expected: all tests PASS. Optionally open the YAML in Swagger Editor to confirm it renders.

- [ ] **Step 6: Commit** `git commit -m "feat(contract): OpenAPI v1 spec and spec updates"`.

---

## Self-Review

- **Spec coverage:** sections 4.2 (request auth), 4.3 (canonical message), 4.4 (number matching, in OpenAPI schemas), 4.5 (endpoints), 4.7 (errors) are covered. Sections 2, 3, 4.1 attestation logic, 4.6, 4.8, 5, 6, 8 belong to Plans 2 and 3 and are intentionally excluded. Attestation chain format is declared in OpenAPI only, verification is Plan 2.
- **Step scan:** every code step names a signature or test and the values the spec fixes.
- **Type consistency:** `ContractError`, `ChallengeMessage`, `canonical_message`, `request_signing_string`, `der_to_raw`, `verify_es256_raw` are used with identical names across tasks.
- **Review Focus:** newline injection (Task 1), offset expiry (Task 1, vector in Task 4), DER rejected (Task 3, vector in Task 4), altered fields fail (Task 4), query string kept (Task 2, vector in Task 4).
- **Proportion:** plan is shorter than the code it describes.
