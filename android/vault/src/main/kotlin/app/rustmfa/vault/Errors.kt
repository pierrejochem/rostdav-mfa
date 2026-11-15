package app.rustmfa.vault

/** Thrown for an unusable otpauth URI. The message carries only the reason, never the URI or secret. */
class OtpAuthException(val reason: Reason) : Exception("invalid otpauth URI: $reason") {
    enum class Reason {
        NotOtpAuth,
        UnknownType,
        MissingSecret,
        BadSecret,
        BadDigits,
        BadPeriod,
        MissingCounter,
        BadCounter,
        BadAlgorithm,
    }
}

/** Not a rostdav-mfa backup, unknown version or algorithm, hostile parameters, or a passphrase too short on export. */
class BackupFormatException(message: String) : Exception(message)

/** Wrong passphrase or damaged file; the two cannot be told apart. */
class BackupDecryptException : Exception("wrong passphrase or damaged file")
