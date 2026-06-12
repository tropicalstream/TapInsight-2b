package com.rayneo.visionclaw.core.model

data class ChatMessage(
    val text: String,
    val fromUser: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

data class GeminiRouteRequest(
    val userText: String,
    val latestFrameBase64: String?
)

data class GeminiRouteResult(
    val assistantText: String,
    val toolCall: ToolCall? = null
)
