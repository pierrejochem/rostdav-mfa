use crate::ContractError;
use base64::{
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
    Engine,
};
use p256::ecdsa::{signature::Verifier, Signature, VerifyingKey};
use p256::pkcs8::DecodePublicKey;

/// Converts an ASN.1 DER ECDSA signature to the raw 64-byte R||S form.
pub fn der_to_raw(der: &[u8]) -> Result<[u8; 64], ContractError> {
    let sig = Signature::from_der(der).map_err(|_| ContractError::BadSignatureEncoding)?;
    Ok(sig.to_bytes().into())
}

fn parse_spki(spki_der_b64: &str) -> Result<VerifyingKey, ContractError> {
    let der = STANDARD
        .decode(spki_der_b64)
        .map_err(|_| ContractError::BadPublicKey)?;
    VerifyingKey::from_public_key_der(&der).map_err(|_| ContractError::BadPublicKey)
}

/// Checks that a public key is a P-256 key in base64 (standard) X.509 SubjectPublicKeyInfo DER
/// form, with a point that is on the curve.
pub fn check_spki(spki_der_b64: &str) -> Result<(), ContractError> {
    parse_spki(spki_der_b64).map(|_| ())
}

/// Verifies a raw R||S ES256 signature (base64url, no padding) against a
/// base64 (standard) X.509 SubjectPublicKeyInfo DER public key.
pub fn verify_es256_raw(
    spki_der_b64: &str,
    message: &[u8],
    sig_b64url: &str,
) -> Result<(), ContractError> {
    let key = parse_spki(spki_der_b64)?;
    let raw = URL_SAFE_NO_PAD
        .decode(sig_b64url)
        .map_err(|_| ContractError::BadSignatureEncoding)?;
    if raw.len() != 64 {
        return Err(ContractError::BadSignatureEncoding);
    }
    let sig = Signature::from_slice(&raw).map_err(|_| ContractError::BadSignatureEncoding)?;
    key.verify(message, &sig).map_err(|_| ContractError::VerifyFailed)
}
