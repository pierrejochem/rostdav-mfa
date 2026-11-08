use base64::{engine::general_purpose::STANDARD, Engine};
use rustmfa_contract::{check_spki, ContractError};

// Generated once with openssl: public keys only, DER SubjectPublicKeyInfo, base64.
const P256: &str = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEU2SXGBqxgODjMAi91QnRgvQv6Sre0vOFoyefMhfBc2QRwUrXE0OYQ8V1s9Q9H0U1XVxLgBnnhHL0IEQZPWn7vw==";
const P384: &str = "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEs3kJmKG0fTfeLZh/QlAQPXusV9Q8BTr0Lmez41G9jzAz3SNyq1rUjR/XKVswjCQcXRrInDIEASAXf51DUaNgsHogL+0ReDl58O0OgnbD5DumjZpe1RZA6TvzUDQVuaU9";
const RSA2048: &str = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmbScfoPw9/ZN9HS+FsplDeS5sCzj9SbVwnXaz/R4zgaAlfYB7PEiWDdYxKWeXR6f47/nNj/BBL0nDHOPDv34BrCMncUf5lgjCZriInfpJff67bLXu3B4K2zxhsB6XlimGQcSM5gf+gt/hIo+LFF5C+mQ8wKscromabkSyf5N59ykNAEnabfJTnmHmfNomP8HeZcynCxj0/e5YHUG8c8D9aisVnMeARVseLsjAVZBS2rV411CVwe+Hx+kRlO701LJuJvHVR1pw5lP/Bn356wEWaDZE9vAu7p+ln6+/RgKWQz2gYh0O7gmKYrABx/6N3Th9FeDG6t9I9RMkH2XOjzpPQIDAQAB";

#[test]
fn p256_spki_is_accepted() {
    assert_eq!(check_spki(P256), Ok(()));
}

#[test]
fn other_curves_and_rsa_are_rejected() {
    assert_eq!(check_spki(P384), Err(ContractError::BadPublicKey));
    assert_eq!(check_spki(RSA2048), Err(ContractError::BadPublicKey));
}

#[test]
fn garbage_is_rejected() {
    for bad in ["", "!!!", "AAAA", "not base64 at all"] {
        assert_eq!(check_spki(bad), Err(ContractError::BadPublicKey), "{bad}");
    }
}

#[test]
fn trailing_bytes_after_the_key_are_rejected() {
    let mut der = STANDARD.decode(P256).unwrap();
    der.extend([0, 0]);
    assert_eq!(check_spki(&STANDARD.encode(der)), Err(ContractError::BadPublicKey));
}

#[test]
fn urlsafe_alphabet_is_not_accepted_when_it_differs() {
    // standard base64 is the contract; a '-' or '_' where '+' or '/' belongs must fail
    let swapped = P256.replace('+', "-").replace('/', "_");
    if swapped != P256 {
        assert_eq!(check_spki(&swapped), Err(ContractError::BadPublicKey));
    }
}
