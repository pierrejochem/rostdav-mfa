#[derive(Debug, PartialEq, Eq)]
pub enum ContractError {
    InvalidField(&'static str),
    InvalidExpiresAt,
    BadSignatureEncoding,
    BadPublicKey,
    VerifyFailed,
}
