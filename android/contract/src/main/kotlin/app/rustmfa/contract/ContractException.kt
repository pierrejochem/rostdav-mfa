package app.rustmfa.contract

/** The error kinds of the contract; the names match the `expect` values of the shared vectors. */
enum class ContractErrorKind { InvalidField, InvalidExpiresAt, BadSignatureEncoding, BadPublicKey, VerifyFailed }

class ContractException(val kind: ContractErrorKind) : Exception(kind.name)
