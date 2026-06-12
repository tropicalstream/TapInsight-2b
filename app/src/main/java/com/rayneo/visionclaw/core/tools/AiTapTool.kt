package com.rayneo.visionclaw.core.tools

/**
 * Interface for all TapInsight native tool implementations.
 * Each tool is declared to Gemini and executed locally on the device.
 */
interface AiTapTool {
    /** Tool name as declared to Gemini (e.g. "google_calendar") */
    val name: String

    /** Execute the tool with parsed arguments. Returns result text for Gemini. */
    suspend fun execute(args: Map<String, String>): Result<String>
}
