package app.rustmfa.android.net

/** The ten codes of the contract; anything else the server says is [UNKNOWN]. */
enum class ErrorCode {
    INVALID_REQUEST,
    UNAUTHENTICATED,
    DEVICE_NOT_AUTHORIZED,
    DEVICE_REVOKED,
    RESOURCE_NOT_FOUND,
    CHALLENGE_ALREADY_CONSUMED,
    CHALLENGE_NUMBER_MISMATCH,
    CHALLENGE_EXPIRED,
    RATE_LIMITED,
    INTERNAL_ERROR,
    UNKNOWN,
}

/**
 * An answer the server gave, or an answer that could not be used.
 * [clockSkewSuspected] is set on `UNAUTHENTICATED` after the retry with the corrected clock, when
 * the phone clock was far off: the screen then tells the user to check the date and time.
 */
class ApiError(
    val code: ErrorCode,
    val status: Int,
    message: String,
    val clockSkewSuspected: Boolean = false,
) : Exception(message)
