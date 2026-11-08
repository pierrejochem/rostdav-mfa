use base64::{engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD}, Engine};
use p256::ecdsa::{signature::Signer, Signature, SigningKey};
use p256::pkcs8::EncodePublicKey;
use rustmfa_contract::{der_to_raw, verify_es256_raw, ContractError};

fn key() -> (SigningKey, String) {
    let sk = SigningKey::random(&mut rand_core::OsRng);
    let spki = STANDARD.encode(sk.verifying_key().to_public_key_der().unwrap().as_bytes());
    (sk, spki)
}

fn b64(sig: &Signature) -> String {
    URL_SAFE_NO_PAD.encode(sig.to_bytes())
}

#[test]
fn valid_raw_signature_verifies() {
    let (sk, spki) = key();
    let sig: Signature = sk.sign(b"hello");
    assert_eq!(verify_es256_raw(&spki, b"hello", &b64(&sig)), Ok(()));
}

#[test]
fn der_signature_is_rejected() {
    let (sk, spki) = key();
    let sig: Signature = sk.sign(b"hello");
    let der = URL_SAFE_NO_PAD.encode(sig.to_der().as_bytes());
    assert_eq!(verify_es256_raw(&spki, b"hello", &der), Err(ContractError::BadSignatureEncoding));
}

#[test]
fn tampered_message_fails() {
    let (sk, spki) = key();
    let sig: Signature = sk.sign(b"hello");
    assert_eq!(verify_es256_raw(&spki, b"hellp", &b64(&sig)), Err(ContractError::VerifyFailed));
}

#[test]
fn bad_public_key_is_rejected() {
    let (sk, _) = key();
    let sig: Signature = sk.sign(b"hello");
    assert_eq!(verify_es256_raw("!!!", b"hello", &b64(&sig)), Err(ContractError::BadPublicKey));
    assert_eq!(verify_es256_raw("AAAA", b"hello", &b64(&sig)), Err(ContractError::BadPublicKey));
}

#[test]
fn der_to_raw_pads_short_integers() {
    let r: Vec<u8> = (1..=31).collect(); // 31 bytes, first byte < 0x80
    let mut s = vec![2u8; 32];
    s[1] = 9;
    let mut der = vec![0x30, 2 + 31 + 2 + 32, 0x02, 31];
    der.extend(&r);
    der.extend([0x02, 32]);
    der.extend(&s);
    let raw = der_to_raw(&der).unwrap();
    assert_eq!(raw[0], 0);
    assert_eq!(&raw[1..32], &r[..]);
    assert_eq!(&raw[32..], &s[..]);
}

#[test]
fn der_to_raw_rejects_garbage() {
    assert_eq!(der_to_raw(&[1, 2, 3]), Err(ContractError::BadSignatureEncoding));
}

#[test]
fn high_s_and_low_s_are_both_accepted() {
    let (sk, spki) = key();
    let sig: Signature = sk.sign(b"hello");
    let (r, s) = sig.split_scalars();
    let flipped = Signature::from_scalars(r.to_bytes(), (-*s).to_bytes()).unwrap();
    assert_eq!(verify_es256_raw(&spki, b"hello", &b64(&sig)), Ok(()));
    assert_eq!(verify_es256_raw(&spki, b"hello", &b64(&flipped)), Ok(()));
}
