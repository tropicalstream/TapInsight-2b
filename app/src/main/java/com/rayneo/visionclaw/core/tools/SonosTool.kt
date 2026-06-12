package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log

class SonosTool(private val context: Context) : AiTapTool {
    override val name = "sonos_control"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "play"
        val room = args["room"] ?: ""

        Log.d("SonosTool", "action=$action room=$room")

        // TODO: Implement Sonos Control API
        return Result.success("Sonos not yet configured. Please connect Sonos in the TapInsight companion app.")
    }
}
