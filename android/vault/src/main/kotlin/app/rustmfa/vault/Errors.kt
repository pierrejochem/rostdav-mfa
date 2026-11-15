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
