package com.pcl.app

/**
 * Everything PCL uses to recognise WhatsApp calls, in one place.
 *
 * WhatsApp has no public call-state API, so detection relies on the labels of notification
 * actions and in-app buttons. When a WhatsApp update or a manufacturer changes the wording,
 * these lists are the only thing that needs updating.
 */
object WhatsAppCalls {
    const val IDLE = "IDLE"
    const val RINGING = "RINGING"
    const val ACTIVE = "ACTIVE"
    val STATES = setOf(IDLE, RINGING, ACTIVE)

    /** WhatsApp and WhatsApp Business. */
    val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** Notification actions that answer a ringing call. */
    val ANSWER_LABELS = listOf(
        "answer", "accept", "pick up", "rispondi", "contestar", "responder",
        "décrocher", "annehmen", "接听", "接聽", "応答"
    )

    /** Notification actions that decline a ringing call. */
    val DECLINE_LABELS = listOf(
        "decline", "reject", "ignore", "rifiuta", "rechazar", "refuser",
        "ablehnen", "拒绝", "拒接", "拒否"
    )

    /** Notification actions that end a connected call. */
    val END_LABELS = listOf(
        "hang up", "end call", "disconnect", "terminate", "riaggancia", "colgar",
        "raccrocher", "auflegen", "終了", "切断"
    )

    /** In-app WhatsApp buttons (seen by the accessibility helper) that start or answer a call. */
    val CALL_BUTTON_LABELS = listOf(
        "video call", "voice call", "start call", "answer", "accept", "join call",
        "appel vidéo", "appel video", "appel vocal", "répondre", "repondre", "décrocher", "decrocher",
        "llamada de video", "llamada de voz", "responder", "videochiamata", "chiamata vocale",
        "rispondi", "contestar", "接听", "接聽", "応答"
    )

    fun isWhatsApp(pkg: String?): Boolean = pkg in PACKAGES

    fun isAnswer(label: String): Boolean = containsAny(label, ANSWER_LABELS)
    fun isDecline(label: String): Boolean = containsAny(label, DECLINE_LABELS)
    fun isEnd(label: String): Boolean = containsAny(label, END_LABELS)
    fun isCallButton(label: String): Boolean = containsAny(label, CALL_BUTTON_LABELS)

    /**
     * The call state a notification's action labels show, or null when they can't tell
     * (PCL then keeps the previous state rather than guessing).
     */
    fun stateFromActionLabels(labels: List<String>): String? = when {
        labels.any(::isEnd) -> ACTIVE
        labels.any(::isAnswer) && labels.any(::isDecline) -> RINGING
        else -> null
    }

    fun containsAny(label: String, words: List<String>): Boolean {
        val lower = label.trim().lowercase()
        return lower.isNotEmpty() && words.any(lower::contains)
    }
}
