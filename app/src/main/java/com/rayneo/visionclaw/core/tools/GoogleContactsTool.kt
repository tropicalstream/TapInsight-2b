package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log

class GoogleContactsTool(private val context: Context) : AiTapTool {
    override val name = "google_contacts"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "search"
        val name = args["name"] ?: ""

        Log.d("GoogleContactsTool", "action=$action name=$name")

        // TODO: Implement People API or local ContactsProvider
        return Result.success("Contacts not yet configured. Please set up in the TapInsight companion app.")
    }
}
