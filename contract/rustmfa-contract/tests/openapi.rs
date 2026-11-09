use serde_yaml::Value;

fn doc() -> Value {
    let p = concat!(env!("CARGO_MANIFEST_DIR"), "/../openapi/rustmfa-v1.yaml");
    serde_yaml::from_str(&std::fs::read_to_string(p).expect("openapi/rustmfa-v1.yaml missing"))
        .unwrap()
}

const ENDPOINTS: [(&str, &str); 11] = [
    ("post", "/api/v1/devices/register"),
    ("put", "/api/v1/devices/{device_id}/push-token"),
    ("get", "/api/v1/mfa/challenges/{challenge_id}"),
    ("post", "/api/v1/mfa/challenges/{challenge_id}/approve"),
    ("post", "/api/v1/mfa/challenges/{challenge_id}/deny"),
    ("post", "/api/v1/devices/enroll-token"),
    ("get", "/api/v1/mfa/challenges"),
    ("get", "/api/v1/devices"),
    ("delete", "/api/v1/devices/{device_id}"),
    ("post", "/api/v1/devices/{device_id}/rotate-key"),
    ("get", "/api/v1/audit"),
];

fn required(v: &Value, schema: &str) -> Vec<String> {
    v["components"]["schemas"][schema]["required"]
        .as_sequence()
        .unwrap_or_else(|| panic!("schema {schema} has no required list"))
        .iter()
        .map(|x| x.as_str().unwrap().to_owned())
        .collect()
}

#[test]
fn openapi_version_is_3_1() {
    assert!(doc()["openapi"].as_str().unwrap().starts_with("3.1"));
}

#[test]
fn all_endpoints_present() {
    let d = doc();
    for (m, p) in ENDPOINTS {
        assert!(!d["paths"][p][m].is_null(), "missing {m} {p}");
    }
}

#[test]
fn all_error_codes_listed() {
    let d = doc();
    let codes: Vec<&str> = d["components"]["schemas"]["ErrorCode"]["enum"]
        .as_sequence()
        .unwrap()
        .iter()
        .map(|x| x.as_str().unwrap())
        .collect();
    for c in [
        "INVALID_REQUEST",
        "UNAUTHENTICATED",
        "DEVICE_NOT_AUTHORIZED",
        "DEVICE_REVOKED",
        "RESOURCE_NOT_FOUND",
        "CHALLENGE_ALREADY_CONSUMED",
        "CHALLENGE_NUMBER_MISMATCH",
        "CHALLENGE_EXPIRED",
        "RATE_LIMITED",
        "INTERNAL_ERROR",
    ] {
        assert!(codes.contains(&c), "missing error code {c}");
    }
}

#[test]
fn register_requires_both_keys() {
    let r = required(&doc(), "RegisterRequest");
    for f in [
        "enroll_token",
        "device_name",
        "request_public_key",
        "approval_public_key",
        "key_algorithm",
        "attestation_chain",
        "push_channel",
        "push_token",
    ] {
        assert!(r.iter().any(|x| x == f), "RegisterRequest lacks {f}");
    }
}

#[test]
fn approve_requires_selected_number_and_signature() {
    let r = required(&doc(), "ApproveRequest");
    for f in ["device_id", "selected_number", "signature"] {
        assert!(r.iter().any(|x| x == f), "ApproveRequest lacks {f}");
    }
}

#[test]
fn device_endpoints_declare_signature_security() {
    let d = doc();
    let non_device = ["/api/v1/devices/register", "/api/v1/devices/enroll-token"];
    for (m, p) in ENDPOINTS {
        let op = &d["paths"][p][m];
        let sec = op["security"]
            .as_sequence()
            .unwrap_or_else(|| panic!("{m} {p}: no security"));
        let names: Vec<String> = sec
            .iter()
            .flat_map(|e| e.as_mapping().unwrap().keys())
            .map(|k| k.as_str().unwrap().to_owned())
            .collect();
        if non_device.contains(&p) {
            assert!(
                !names.iter().any(|n| n == "RustMFASig"),
                "{m} {p} must not need device signature"
            );
        } else {
            assert_eq!(names, vec!["RustMFASig"], "{m} {p}");
        }
    }
}

#[test]
fn create_challenge_is_not_public() {
    let d = doc();
    assert!(d["paths"]["/api/v1/mfa/challenges"]["post"].is_null());
    assert!(d["components"]["securitySchemes"]["InternalService"].is_null());
    assert!(d["components"]["schemas"]["CreateChallengeRequest"].is_null());
    assert!(d["components"]["schemas"]["CreateChallengeResponse"].is_null());
}

/// Rostdav's tests read the contract as JSON, so they need no YAML parser. The mirror must say
/// the same as the YAML. Regenerate with `UPDATE_OPENAPI_JSON=1 cargo test -p rustmfa-contract
/// --test openapi json_mirror`.
#[test]
fn json_mirror_matches_the_yaml() {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../openapi/rustmfa-v1.json");
    let yaml: serde_json::Value = serde_yaml::from_value(doc()).unwrap();
    if std::env::var_os("UPDATE_OPENAPI_JSON").is_some() {
        let mut text = serde_json::to_string_pretty(&yaml).unwrap();
        text.push('\n');
        std::fs::write(path, text).unwrap();
    }
    let json: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(path)
            .expect("openapi/rustmfa-v1.json missing: see the doc comment"),
    )
    .unwrap();
    assert_eq!(json, yaml, "rustmfa-v1.json is out of date");
}
