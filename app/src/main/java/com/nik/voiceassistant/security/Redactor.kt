package com.nik.voiceassistant.security

/**
 * Hides secrets in text: one-time codes and card numbers. Everything written to the phone (history,
 * logs, traces) goes through forStorage; screen text for the AI goes through forModel, which only
 * masks when the user turned that on (an agent that can't read an OTP can't fill it in).
 */
object Redactor {

    // 13-19 digits, optionally grouped by spaces or dashes (card numbers)
    private val CARD = Regex("""(?<![\d])(?:\d[ -]?){12,18}\d(?![\d])""")
    // A 4-8 digit code shortly after a word that says it is one
    private val OTP = Regex(
        """(?i)(otp|one[ -]?time|verification|verify|security code|auth\w*|login code|passcode|code|pin|कोड|ओटीपी)([^\d\n]{0,40}?)(?<![\d])(\d{4,8})(?![\d])"""
    )
    // The usual SMS the other way round: "482913 is your OTP for …"
    private val OTP_FIRST = Regex(
        """(?i)(?<![\d])(\d{4,8})(?![\d])(\s*(is|are|=)?\s*(your|the)?\s*(otp|one[ -]?time|verification code|login code|security code|passcode|कोड|ओटीपी))"""
    )
    private const val MASK = "••••"

    /** Screen text before it reaches the AI. */
    fun forModel(text: String): String = if (AgentPolicy.current.maskSensitiveScreen) mask(text) else text

    /** Text before it is stored on the phone or exported. */
    fun forStorage(text: String): String = mask(text)

    fun mask(text: String): String {
        if (text.none { it.isDigit() }) return text
        val noCards = CARD.replace(text) { m ->
            val digits = m.value.filter { it.isDigit() }
            "$MASK ${digits.takeLast(4)}"
        }
        val codesAfter = OTP.replace(noCards) { m -> m.groupValues[1] + m.groupValues[2] + MASK }
        return OTP_FIRST.replace(codesAfter) { m -> MASK + m.groupValues[2] }
    }

    /** Tool arguments as they may be stored: never a PIN, and no codes in typed text. */
    fun args(args: Map<String, Any?>, sensitive: Boolean = false): Map<String, Any?> = args.mapValues { (k, v) ->
        when {
            k == "pin" -> "***"
            k == "text" && sensitive -> "*** (hidden)"
            v is String -> mask(v)
            else -> v
        }
    }
}
