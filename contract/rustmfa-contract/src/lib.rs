mod canonical;
mod error;
mod request;
mod sig;
pub use canonical::{canonical_message, ChallengeMessage};
pub use error::ContractError;
pub use request::request_signing_string;
pub use sig::{check_spki, der_to_raw, verify_es256_raw};

/// The OpenAPI 3.1 contract of the device API as JSON. A contract test keeps it equal to
/// `openapi/rustmfa-v1.yaml`, so dependent crates need no YAML parser.
pub const OPENAPI_JSON: &str = include_str!("../../openapi/rustmfa-v1.json");
