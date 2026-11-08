use rustmfa_contract::{request_signing_string, ContractError};

const EMPTY_SHA: &str = "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU";

#[test]
fn builds_signing_string_with_empty_body() {
    assert_eq!(
        request_signing_string("GET", "/api/v1/devices", 1793200000, "abc", b"").unwrap(),
        format!("GET\n/api/v1/devices\n1793200000\nabc\n{EMPTY_SHA}")
    );
}

#[test]
fn keeps_query_string_unchanged() {
    let s = request_signing_string("GET", "/api/v1/mfa/challenges?status=pending", 1, "n", b"").unwrap();
    assert!(s.contains("\n/api/v1/mfa/challenges?status=pending\n"));
}

#[test]
fn hashes_nonempty_body() {
    // sha256("abc") base64url no pad
    let s = request_signing_string("POST", "/p", 1, "n", b"abc").unwrap();
    assert!(s.ends_with("\nungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0"));
}

#[test]
fn rejects_lowercase_method() {
    assert_eq!(
        request_signing_string("get", "/p", 1, "n", b""),
        Err(ContractError::InvalidField("method"))
    );
}

#[test]
fn rejects_newline_in_path_or_nonce() {
    assert_eq!(
        request_signing_string("GET", "/p\nx", 1, "n", b""),
        Err(ContractError::InvalidField("path"))
    );
    assert_eq!(
        request_signing_string("GET", "/p", 1, "n\nx", b""),
        Err(ContractError::InvalidField("nonce"))
    );
}
