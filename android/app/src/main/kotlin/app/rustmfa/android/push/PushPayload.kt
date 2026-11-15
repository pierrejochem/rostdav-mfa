package app.rustmfa.android.push

/**
 * What a push message may carry: only a challenge id. The phone fetches everything else through the
 * signed GET, so a forged or leaked push cannot show a number or a name.
 */
object PushPayload {
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")

    fun challengeId(data: Map<String, String>): String? = data["challenge_id"]?.takeIf { ID.matches(it) }
}
