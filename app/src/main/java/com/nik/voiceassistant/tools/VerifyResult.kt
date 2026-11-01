package com.nik.voiceassistant.tools

/**
 * The outcome of verifying whether a UI action succeeded.
 * Produced by ElementVerifier (Tier-1) or ScreenVerifier (Tier-2).
 */
data class VerifyResult(
    /** "yes" | "no" | "unsure" */
    val verified: String,
    /** "elements" (tier-1) | "vision" (tier-2) | "timeout" | "text_only" */
    val via: String,
    /** One sentence: what was observed to support the verdict. */
    val evidence: String,
    /** "permission_dialog" | "login" | "popup" | "error_toast" | "loading" | null */
    val blocker: String?,
    val stepDone: Boolean,
    /** Suggested next action from the gateway, or null. */
    val nextAction: Map<String, Any?>?,
    /** Element text or IDs that proved the verdict. */
    val elementRefs: List<String>
)
