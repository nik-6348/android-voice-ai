package com.nik.voiceassistant.security

import com.nik.voiceassistant.security.AgentPolicy.ConfirmLevel

/**
 * Decides which agent actions need the user's yes first. Text on screen (a chat message, a web page,
 * a notification) reaches the model too, so it could talk the model into calling, sending or paying;
 * these checks run on the phone, outside the model, and only the user's own words count as asking.
 * Pure logic (no Android), so it is unit-tested.
 */
object ActionGuard {

    enum class Risk(val what: String) {
        // Always asked, at every level
        PAYMENT("make a payment"),
        WIPE("erase data"),
        DIAL_CODE("dial a phone code"),
        // Asked depending on the level and on what the user said
        CALL("place a call"),
        SEND("send"),
        DELETE("delete")
    }

    private val PAYMENT = Regex(
        """(?i)\bpay\s*(now|₹|rs\.?|inr|\d)|^\s*pay\s*$|proceed to pay|pay securely|place (your )?order|buy now|confirm (and pay|payment|order|purchase)|complete (purchase|payment|order)|transfer (funds|money|now)|send money|पे करें|भुगतान करें|पैसे भेजें"""
    )
    private val WIPE = Regex("""(?i)factory (data )?reset|erase (all|everything|phone|data)|delete (my |your |this )?account|\bwipe\b|reset phone|format (sd|storage|card)""")
    private val CALL = Regex("""(?i)^\s*((voice|video|audio) )?call( now| back)?\s*$|^\s*dial\s*$|^\s*कॉल( करें)?\s*$""")
    private val SEND = Regex("""(?i)^\s*(send|post|publish|tweet)\b|\bsend (message|now|sms|email)\b|भेजें|भेजो|पोस्ट करें""")
    private val SEND_ID = Regex("""(?i)(^|[_\s])send([_\s]|$)""")
    private val DELETE = Regex("""(?i)^\s*(delete|uninstall|erase|clear (data|storage))\b|delete for everyone|move to (trash|bin)|डिलीट|हटाएं""")

    // The user's own words asking for this kind of action (Hindi, Hinglish, English)
    private val ASKED_CALL = Regex("""(?i)\b(call|phone|dial|ring|baat)\w*|कॉल|फ़ोन|फोन|बात|लगा""")
    private val ASKED_SEND = Regex("""(?i)\b(send|bhej|message|msg|text|reply|whatsapp|sms|mail|email|post|share|forward|tweet|likh|bol\s*do|keh\s*do|bata\s*do)\w*|भेज|मैसेज|संदेश|लिख""")
    private val ASKED_DELETE = Regex("""(?i)\b(delete|remove|hata|mita|clear|uninstall|erase)\w*|डिलीट|हटा|मिटा""")

    /** The risk of pressing a button with this label (and view id), or null. Payment wins over send. */
    fun classifyTap(label: String?, viewId: String? = null): Risk? {
        val l = label.orEmpty()
        val id = viewId?.substringAfterLast('/').orEmpty()
        return when {
            PAYMENT.containsMatchIn(l) -> Risk.PAYMENT
            WIPE.containsMatchIn(l) -> Risk.WIPE
            CALL.containsMatchIn(l) -> Risk.CALL
            SEND.containsMatchIn(l) || SEND_ID.containsMatchIn(id) -> Risk.SEND
            DELETE.containsMatchIn(l) -> Risk.DELETE
            else -> null
        }
    }

    /** A number with * or # runs a network code (call forwarding, USSD), not a call to a person. */
    fun isDialCode(number: String?): Boolean = number?.any { it == '*' || it == '#' } == true

    /** Whether the user's own recent words ask for this kind of action. */
    fun userAsked(risk: Risk, userText: String): Boolean = when (risk) {
        Risk.CALL -> ASKED_CALL.containsMatchIn(userText)
        Risk.SEND -> ASKED_SEND.containsMatchIn(userText)
        Risk.DELETE -> ASKED_DELETE.containsMatchIn(userText)
        else -> false
    }

    /**
     * Whether the action must wait for the user's yes. trusted: the user started it themselves
     * (an automation they ran), which counts as asking.
     */
    fun needsYes(risk: Risk, level: ConfirmLevel, userText: String, trusted: Boolean = false): Boolean = when (risk) {
        Risk.PAYMENT, Risk.WIPE, Risk.DIAL_CODE -> true
        Risk.CALL, Risk.SEND, Risk.DELETE -> when (level) {
            ConfirmLevel.RELAXED -> false
            ConfirmLevel.STRICT -> true
            ConfirmLevel.BALANCED -> !trusted && !userAsked(risk, userText)
        }
    }
}
