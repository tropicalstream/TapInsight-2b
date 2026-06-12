package com.rayneo.visionclaw.core.model

data class ToolCall(
    val tool: String,
    val args: Map<String, String> = emptyMap()
)
