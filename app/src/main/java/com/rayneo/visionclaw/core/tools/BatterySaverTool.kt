package com.rayneo.visionclaw.core.tools

import android.util.Log

/**
 * BatterySaverTool — manages power-saving mode for the AR glasses.
 *
 * Triggered via voice commands like:
 *   "enable battery saver"
 *   "save battery"
 *   "low power mode"
 *   "battery status"
 *   "how much battery do I have"
 *
 * When activated, battery saver:
 *   - Reduces HUD refresh interval to 5 minutes
 *   - Disables proactive camera analysis
 *   - Switches to voice-only mode (no camera feed to Gemini)
 *   - Reduces TTS volume slightly
 *   - Pauses background polling (news, air quality)
 *
 * The tool communicates state changes back via its result text,
 * which the caller (MainActivity) interprets to toggle actual settings.
 */
class BatterySaverTool(
    private val batteryLevelProvider: () -> Int,
    private val isChargingProvider: () -> Boolean,
    private val batterySaverActiveProvider: () -> Boolean,
    private val toggleBatterySaver: (Boolean) -> Unit
) : AiTapTool {

    override val name = "battery_saver"

    companion object {
        private const val TAG = "BatterySaverTool"
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"]?.trim()?.lowercase().orEmpty()
        val batteryLevel = batteryLevelProvider()
        val isCharging = isChargingProvider()
        val isActive = batterySaverActiveProvider()

        Log.d(TAG, "Battery action=$action level=$batteryLevel% charging=$isCharging active=$isActive")

        return when {
            action == "check" || action == "level" || action == "battery_status" || action == "status" -> {
                val status = buildString {
                    append("Battery: $batteryLevel%")
                    if (isCharging) append(" (charging)")
                    append("\nBattery saver: ${if (isActive) "ON" else "OFF"}")
                    if (batteryLevel <= 20 && !isActive) {
                        append("\n⚠ Battery is low. Consider enabling battery saver mode.")
                    }
                    if (batteryLevel <= 10) {
                        append("\n🔴 Critical battery level! Enabling battery saver is strongly recommended.")
                    }
                }
                Result.success(status)
            }

            action == "enable" || action == "on" || action == "activate" || action == "save" -> {
                if (isActive) {
                    Result.success("Battery saver is already active. Current level: $batteryLevel%.")
                } else {
                    toggleBatterySaver(true)
                    Result.success(
                        buildString {
                            append("🔋 Battery saver enabled. Current level: $batteryLevel%.")
                            append("\n• HUD refresh reduced to every 5 minutes")
                            append("\n• Camera analysis paused")
                            append("\n• Background polling paused (news, air quality)")
                            append("\n• Voice-only mode active")
                            append("\nSay 'disable battery saver' to resume normal operation.")
                        }
                    )
                }
            }

            action == "disable" || action == "off" || action == "deactivate" -> {
                if (!isActive) {
                    Result.success("Battery saver is already off. Current level: $batteryLevel%.")
                } else {
                    toggleBatterySaver(false)
                    Result.success(
                        buildString {
                            append("Battery saver disabled. All features restored.")
                            append("\nCurrent level: $batteryLevel%")
                            if (batteryLevel <= 20) {
                                append("\n⚠ Battery is low — saver mode was helping extend it.")
                            }
                        }
                    )
                }
            }

            action.isBlank() -> {
                // No action specified — return status and suggestion
                val status = buildString {
                    append("Battery: $batteryLevel%")
                    if (isCharging) append(" (charging)")
                    append("\nBattery saver: ${if (isActive) "ON" else "OFF"}")
                    append("\n\nAvailable commands:")
                    append("\n• 'enable battery saver' — extend battery life")
                    append("\n• 'disable battery saver' — restore full features")
                    append("\n• 'battery status' — check current level")
                }
                Result.success(status)
            }

            else -> Result.failure(Exception("Unknown battery action: $action. Try 'enable', 'disable', or 'status'."))
        }
    }
}
