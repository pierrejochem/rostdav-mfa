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

/// Verifies a raw R||S ES256 signature (base64url, no padding) against a
/// base64 (standard) X.509 SubjectPublicKeyInfo DER public key.
pub fn verify_es256_raw(
    spki_der_b64: &str,
    message: &[u8],
    sig_b64url: &str,
) -> Result<(), ContractError> {
    let der = STANDARD
        .decode(spki_der_b64)
        .map_err(|_| ContractError::BadPublicKey)?;
    let key = VerifyingKey::from_public_key_der(&der).map_err(|_| ContractError::BadPublicKey)?;
    let raw = URL_SAFE_NO_PAD
        .decode(sig_b64url)
        .map_err(|_| ContractError::BadSignatureEncoding)?;
    if raw.len() != 64 {
        return Err(ContractError::BadSignatureEncoding);
    }
    let sig = Signature::from_slice(&raw).map_err(|_| ContractError::BadSignatureEncoding)?;
    key.verify(message, &sig).map_err(|_| ContractError::VerifyFailed)
}
