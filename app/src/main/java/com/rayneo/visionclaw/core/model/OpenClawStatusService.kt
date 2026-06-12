package com.rayneo.visionclaw.core.model

/**
 * Process-wide snapshot of the most recent TapClaw/OpenClaw gateway status.
 *
 * MainActivity owns the authoritative state (connection label, heartbeat
 * text, current task label, last activity timestamp, gateway health) and
 * pushes updates into this singleton whenever those fields change. Tools
 * invoked by Gemini (notably `StatusTool`) read from here so the assistant
 * can answer "status" queries without reaching back into the Activity.
 *
 * All fields are @Volatile so a read from the IO dispatcher sees the
 * latest write from the main thread without extra synchronization.
 */
object OpenClawStatusService {

    /** Most recent raw heartbeat delta text from the gateway (trimmed). */
    @Volatile var lastHeartbeat: String? = null

    /** Human-readable label describing what TapClaw is doing right now. */
    @Volatile var lastTaskLabel: String? = null

    /** Whether the most recent gateway ping reported healthy. */
    @Volatile var gatewayHealthy: Boolean = false

    /** Current ticker/idle label shown on the HUD (e.g. "OpenClaw connected"). */
    @Volatile var connectionLabel: String = "OpenClaw checking..."

    /** uptimeMillis() of the most recent heartbeat/task event. 0 if none. */
    @Volatile var lastActivityUptimeMs: Long = 0L

    /** Wall-clock time (ms) of the most recent status update, for staleness checks. */
    @Volatile var lastUpdatedWallClockMs: Long = 0L

    /** Push a heartbeat + task update. Call from the main thread. */
    fun updateHeartbeat(
        heartbeat: String?,
        taskLabel: String?,
        gatewayHealthy: Boolean,
        activityUptimeMs: Long
    ) {
        this.lastHeartbeat = heartbeat
        this.lastTaskLabel = taskLabel
        this.gatewayHealthy = gatewayHealthy
        this.lastActivityUptimeMs = activityUptimeMs
        this.lastUpdatedWallClockMs = System.currentTimeMillis()
    }

    /** Update the ticker/idle connection label (e.g. "OpenClaw connected"). */
    fun updateConnection(label: String, healthy: Boolean) {
        this.connectionLabel = label
        this.gatewayHealthy = healthy
        this.lastUpdatedWallClockMs = System.currentTimeMillis()
    }

    /** Clear the active task label when a run completes or goes idle. */
    fun clearTaskLabel() {
        this.lastTaskLabel = null
        this.lastUpdatedWallClockMs = System.currentTimeMillis()
    }

    /** Snapshot suitable for rendering into a one-line status summary. */
    fun snapshotLine(): String {
        val task = lastTaskLabel?.takeIf { it.isNotBlank() }
        val heartbeat = lastHeartbeat?.takeIf { it.isNotBlank() }
        val conn = connectionLabel
        val healthStr = if (gatewayHealthy) "healthy" else "unreachable"
        return when {
            task != null && heartbeat != null -> "$conn ($healthStr) — $task: $heartbeat"
            task != null -> "$conn ($healthStr) — $task"
            heartbeat != null -> "$conn ($healthStr) — $heartbeat"
            else -> "$conn ($healthStr)"
        }
    }
}
