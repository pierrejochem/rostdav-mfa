use base64::{
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
    Engine,
};
use rustmfa_contract::*;
use serde_json::Value;

fn load() -> Value {
    let p = concat!(env!("CARGO_MANIFEST_DIR"), "/../vectors/v1.json");
    serde_json::from_str(&std::fs::read_to_string(p).expect("vectors/v1.json missing")).unwrap()
}

fn s<'a>(v: &'a Value, k: &str) -> &'a str {
    v[k].as_str()
        .unwrap_or_else(|| panic!("missing string field {k}"))
}

fn outcome(r: Result<(), ContractError>) -> String {
    match r {
        Ok(()) => "ok".into(),
        Err(ContractError::InvalidField(_)) => "InvalidField".into(),
        Err(e) => format!("{e:?}"),
    }
}

#[test]
fn vectors_file_matches_implementation() {
    let v = load();
    let spki = s(&v["key"], "spki_der_b64");
    for c in v["challenge_cases"].as_array().unwrap() {
        let name = s(c, "name");
        let f = &c["fields"];
        let m = ChallengeMessage {
            challenge_id: s(f, "challenge_id"),
            nonce: s(f, "nonce"),
            login_id: s(f, "login_id"),
            device_id: s(f, "device_id"),
            expires_at: s(f, "expires_at"),
            audience: s(f, "audience"),
        };
        let expect = s(c, "expect");
        let msg = match canonical_message(&m) {
            Ok(msg) => msg,
            Err(e) => {
                assert_eq!(outcome(Err(e)), expect, "case {name}");
                continue;
            }
        };
        assert_eq!(msg, s(c, "message"), "case {name}: message");
        let sig = s(c, "signature_b64url");
        assert_eq!(
            outcome(verify_es256_raw(spki, msg.as_bytes(), sig)),
            expect,
            "case {name}"
        );
        if let Some(der) = c["signature_der_b64url"].as_str() {
            let der = URL_SAFE_NO_PAD.decode(der).unwrap();
            let raw = URL_SAFE_NO_PAD.decode(sig).unwrap();
            assert_eq!(
                der_to_raw(&der).unwrap().to_vec(),
                raw,
                "case {name}: der->raw"
            );
        }
    }
    for c in v["request_cases"].as_array().unwrap() {
        let name = s(c, "name");
        let body = STANDARD.decode(s(c, "body_b64")).unwrap();
        let ss = request_signing_string(
            s(c, "method"),
            s(c, "path"),
            c["ts"].as_i64().unwrap(),
            s(c, "nonce"),
            &body,
        )
        .unwrap();
        assert_eq!(ss, s(c, "signing_string"), "case {name}: signing string");
        let r = verify_es256_raw(spki, ss.as_bytes(), s(c, "signature_b64url"));
        assert_eq!(outcome(r), s(c, "expect"), "case {name}");
    }
}

#[test]
fn vectors_cover_required_cases() {
    let v = load();
    let names: Vec<&str> = v["challenge_cases"]
        .as_array()
        .unwrap()
        .iter()
        .chain(v["request_cases"].as_array().unwrap())
        .map(|c| s(c, "name"))
        .collect();
    for want in [
        "valid_challenge",
        "newline_in_nonce",
        "offset_expiry",
        "der_signature_instead_of_raw",
        "wrong_login_id",
        "wrong_device_id",
        "request_with_query",
        "request_with_body",
        "expiry_fraction",
        "expiry_feb_31",
        "expiry_leap_second",
        "expiry_lowercase_z",
        "expiry_leap_day_ok",
    ] {
        assert!(names.contains(&want), "missing case {want}");
    }
}
