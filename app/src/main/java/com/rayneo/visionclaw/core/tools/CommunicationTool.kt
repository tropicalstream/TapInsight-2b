package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class CommunicationTool(private val context: Context) : AiTapTool {
    override val name = "send_message"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"] ?: "send_sms"
        val recipient = args["recipient"] ?: ""
        val message = args["message"] ?: ""

        Log.d("CommunicationTool", "action=$action recipient=$recipient")

        return when (action) {
            "call", "place_call" -> {
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$recipient"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Result.success("Calling $recipient...")
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            else -> {
                try {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$recipient"))
                    intent.putExtra("sms_body", message)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Result.success("Message sent to $recipient")
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }
    }
}
