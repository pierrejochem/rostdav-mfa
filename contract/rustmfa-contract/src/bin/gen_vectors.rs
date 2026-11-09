//! Regenerates contract/vectors/v1.json from a fixed PUBLIC test key.
//! Run: cargo run -p rustmfa-contract --bin gen_vectors
use base64::{
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
    Engine,
};
use p256::ecdsa::{signature::Signer, Signature, SigningKey};
use p256::pkcs8::{EncodePrivateKey, EncodePublicKey};
use rustmfa_contract::*;
use serde_json::{json, Value};

const TEST_SCALAR: [u8; 32] = [0x11; 32]; // public test key, never for production

fn raw(sig: &Signature) -> String {
    URL_SAFE_NO_PAD.encode(sig.to_bytes())
}

fn fields(login: &str, device: &str, nonce: &str, exp: &str) -> Value {
    json!({"challenge_id":"ch_8a31","nonce":nonce,"login_id":login,"device_id":device,
           "expires_at":exp,"audience":"rostdav"})
}

fn msg_of(f: &Value) -> Result<String, ContractError> {
    let g = |k: &str| f[k].as_str().unwrap().to_owned();
    let (c, n, l, d, e, a) = (
        g("challenge_id"),
        g("nonce"),
        g("login_id"),
        g("device_id"),
        g("expires_at"),
        g("audience"),
    );
    canonical_message(&ChallengeMessage {
        challenge_id: &c,
        nonce: &n,
        login_id: &l,
        device_id: &d,
        expires_at: &e,
        audience: &a,
    })
}

fn main() {
    let sk = SigningKey::from_slice(&TEST_SCALAR).unwrap();
    let good = fields("login_7f21", "dev_7f21", "n0nce", "2026-10-28T15:03:30Z");
    let good_msg = msg_of(&good).unwrap();
    let good_sig: Signature = sk.sign(good_msg.as_bytes());
    let good_raw = raw(&good_sig);
    let good_der = URL_SAFE_NO_PAD.encode(good_sig.to_der().as_bytes());

    let signed_but_altered = |name: &str, f: Value| json!({"name":name,"fields":f,"message":msg_of(&f).unwrap(),"signature_b64url":good_raw,"expect":"VerifyFailed"});
    let signed_ok = |name: &str, f: Value| {
        let m = msg_of(&f).unwrap();
        let sig: Signature = sk.sign(m.as_bytes());
        json!({"name":name,"fields":f,"message":m,"signature_b64url":raw(&sig),"expect":"ok"})
    };
    let rejected = |name: &str, f: Value, expect: &str| json!({"name":name,"fields":f,"message":"","signature_b64url":"","expect":expect});

    let challenge_cases = vec![
        json!({"name":"valid_challenge","fields":good,"message":good_msg,"signature_b64url":good_raw,
               "signature_der_b64url":good_der,"expect":"ok"}),
        rejected(
            "newline_in_nonce",
            fields("login_7f21", "dev_7f21", "n0\nnce", "2026-10-28T15:03:30Z"),
            "InvalidField",
        ),
        rejected(
            "offset_expiry",
            fields(
                "login_7f21",
                "dev_7f21",
                "n0nce",
                "2026-10-28T16:03:30+01:00",
            ),
            "InvalidExpiresAt",
        ),
        json!({"name":"der_signature_instead_of_raw","fields":good,"message":good_msg,
               "signature_b64url":good_der,"expect":"BadSignatureEncoding"}),
        rejected(
            "expiry_fraction",
            fields(
                "login_7f21",
                "dev_7f21",
                "n0nce",
                "2026-10-28T15:03:30.123Z",
            ),
            "InvalidExpiresAt",
        ),
        rejected(
            "expiry_feb_31",
            fields("login_7f21", "dev_7f21", "n0nce", "2026-02-31T00:00:00Z"),
            "InvalidExpiresAt",
        ),
        rejected(
            "expiry_leap_second",
            fields("login_7f21", "dev_7f21", "n0nce", "2026-10-28T15:03:60Z"),
            "InvalidExpiresAt",
        ),
        rejected(
            "expiry_lowercase_z",
            fields("login_7f21", "dev_7f21", "n0nce", "2026-10-28T15:03:30z"),
            "InvalidExpiresAt",
        ),
        signed_ok(
            "expiry_leap_day_ok",
            fields("login_7f21", "dev_7f21", "n0nce", "2028-02-29T00:00:00Z"),
        ),
        signed_but_altered(
            "wrong_login_id",
            fields("login_OTHER", "dev_7f21", "n0nce", "2026-10-28T15:03:30Z"),
        ),
        signed_but_altered(
            "wrong_device_id",
            fields("login_7f21", "dev_OTHER", "n0nce", "2026-10-28T15:03:30Z"),
        ),
    ];

    let req = |name: &str, method: &str, path: &str, body: &[u8]| {
        let ts = 1793200000i64;
        let ss = request_signing_string(method, path, ts, "req-nonce-1", body).unwrap();
        let sig: Signature = sk.sign(ss.as_bytes());
        json!({"name":name,"method":method,"path":path,"ts":ts,"nonce":"req-nonce-1",
               "body_b64":STANDARD.encode(body),"signing_string":ss,"signature_b64url":raw(&sig),"expect":"ok"})
    };
    let request_cases = vec![
        req(
            "request_with_query",
            "GET",
            "/api/v1/mfa/challenges?status=pending",
            b"",
        ),
        req(
            "request_with_body",
            "POST",
            "/api/v1/mfa/challenges/ch_8a31/approve",
            br#"{"device_id":"dev_7f21","selected_number":47,"signature":"x"}"#,
        ),
    ];

    let out = json!({
        "version": 1,
        "key": {
            "spki_der_b64": STANDARD.encode(sk.verifying_key().to_public_key_der().unwrap().as_bytes()),
            "pkcs8_der_b64": STANDARD.encode(sk.to_pkcs8_der().unwrap().as_bytes()),
        },
        "challenge_cases": challenge_cases,
        "request_cases": request_cases,
    });
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../vectors/v1.json");
    std::fs::write(path, serde_json::to_string_pretty(&out).unwrap() + "\n").unwrap();
    println!("wrote {path}");
}
