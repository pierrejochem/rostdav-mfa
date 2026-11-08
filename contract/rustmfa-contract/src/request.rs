use crate::ContractError;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use sha2::{Digest, Sha256};

pub fn request_signing_string(
    method: &str,
    path_and_query: &str,
    ts: i64,
    nonce: &str,
    body: &[u8],
) -> Result<String, ContractError> {
    if !matches!(method, "GET" | "POST" | "PUT" | "DELETE") {
        return Err(ContractError::InvalidField("method"));
    }
    if path_and_query.is_empty() || path_and_query.contains('\n') {
        return Err(ContractError::InvalidField("path"));
    }
    if nonce.is_empty() || nonce.contains('\n') {
        return Err(ContractError::InvalidField("nonce"));
    }
    let body_hash = URL_SAFE_NO_PAD.encode(Sha256::digest(body));
    Ok(format!("{method}\n{path_and_query}\n{ts}\n{nonce}\n{body_hash}"))
}
