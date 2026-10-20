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

    /**
     * Notification actions that answer a ringing call. Some of these also mean "Reply" on a
     * message, so a ringing call needs an answer AND a decline label (see [stateFromActionLabels]).
     */
    val ANSWER_LABELS = listOf(
        "answer", "accept", "pick up", "rispondi", "contestar", "responder",
        "décrocher", "répondre", "repondre", "annehmen", "atender", "ответить", "cevapla",
        "接听", "接聽", "応答"
    )

    /** Notification actions that decline a ringing call. */
    val DECLINE_LABELS = listOf(
        "decline", "reject", "ignore", "rifiuta", "rechazar", "refuser",
        "ablehnen", "recusar", "rejeitar", "отклонить", "reddet", "拒绝", "拒接", "拒否"
    )

    /** Notification actions that end a connected call. */
    val END_LABELS = listOf(
        "hang up", "end call", "disconnect", "terminate", "riaggancia", "colgar",
        "raccrocher", "auflegen", "desligar", "encerrar", "завершить", "kapat", "終了", "切断"
    )

    /**
     * The voice-call button in a WhatsApp chat's top bar. Used on the Gateway to start a call
     * the Remote asked for. Video buttons are excluded.
     */
    val VOICE_CALL_LABELS = listOf(
        "voice call", "audio call", "appel vocal", "appel audio", "llamada de voz",
        "chiamata vocale", "sprachanruf", "chamada de voz", "голосовой звонок",
        "语音通话", "語音通話", "音声通話"
    )
    private val VIDEO_WORDS = listOf("video", "vidéo", "vídeo", "видео", "视频", "視訊", "ビデオ")

    /** The button in WhatsApp's "start this call?" confirmation. Matched exactly, not contained. */
    val CONFIRM_CALL_LABELS = setOf(
        "call", "voice call", "start call", "appeler", "llamar", "chiama", "anrufen", "ligar",
        "позвонить", "呼叫", "拨打", "撥打", "発信"
    )

    fun isWhatsApp(pkg: String?): Boolean = pkg in PACKAGES

    fun isVoiceCallButton(label: String): Boolean =
        containsAny(label, VOICE_CALL_LABELS) && !containsAny(label, VIDEO_WORDS)

    fun isConfirmCall(label: String): Boolean = label.trim().lowercase() in CONFIRM_CALL_LABELS

    fun isAnswer(label: String): Boolean = containsAny(label, ANSWER_LABELS)
    fun isDecline(label: String): Boolean = containsAny(label, DECLINE_LABELS)
    fun isEnd(label: String): Boolean = containsAny(label, END_LABELS)

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
