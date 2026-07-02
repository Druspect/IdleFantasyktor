package com.fantasyidler.automation

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.SessionRepository
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AgentSession(
    @SerialName("session_id") val sessionId: String,
    @SerialName("skill_name") val skillName: String,
    @SerialName("activity_key") val activityKey: String,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("ends_at") val endsAt: Long,
)

@Serializable
data class AgentAutomationState(
    val authority: String,
    @SerialName("native_queue_auto_advance")
    val nativeQueueAutoAdvance: Boolean,
    @SerialName("pending_settlement_count")
    val pendingSettlementCount: Int,
    @SerialName("dispatch_enabled")
    val dispatchEnabled: Boolean,
)

@Serializable
data class AgentStatus(
    @SerialName("api_version") val apiVersion: Int,
    @SerialName("device_id") val deviceId: String,
    val status: String,
    val battery: Int,
    val coins: Long,
    val stats: Map<String, Int>,
    val inventory: Map<String, Int>,
    @SerialName("native_queue_count") val nativeQueueCount: Int,
    @SerialName("native_queued_actions") val nativeQueuedActions: List<String>,
    @SerialName("active_session") val activeSession: AgentSession?,
    val automation: AgentAutomationState,
)

@Serializable
data class AgentHealth(
    val ok: Boolean,
    @SerialName("api_version") val apiVersion: Int,
    @SerialName("device_id") val deviceId: String,
    val automation: AgentAutomationState,
)

@Serializable
data class AgentRejection(
    val accepted: Boolean = false,
    val code: String,
    val message: String,
)

@Singleton
class AgentGameBridge @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val sessionRepository: SessionRepository,
    private val automationControl: AutomationControlRepository,
) {
    suspend fun snapshot(deviceId: String, battery: Int): AgentStatus {
        val active = sessionRepository.getActiveSession()?.takeUnless { it.completed }
        val pendingSettlementCount = sessionRepository.getAllCompletedSessions().size
        val queue = playerRepository.getQueue()
        val player = playerRepository.getOrCreatePlayer()

        val automation = automationState(pendingSettlementCount)

        return AgentStatus(
            apiVersion = 2,
            deviceId = deviceId,
            status = when {
                active != null -> "busy"
                pendingSettlementCount > 0 -> "pending_settlement"
                else -> "idle"
            },
            battery = battery,
            coins = player.coins,
            stats = playerRepository.getSkillLevels(),
            inventory = playerRepository.getInventory(),
            nativeQueueCount = queue.size,
            nativeQueuedActions = queue.map { "${it.skillName}:${it.activityKey}" },
            activeSession = active?.toAgentSession(),
            automation = automation,
        )
    }

    fun controlSnapshot(): AutomationControlSnapshot =
        automationControl.snapshot()

    fun setAuthority(rawAuthority: String): AutomationControlSnapshot? =
        automationControl.setAuthority(rawAuthority)

    fun healthAutomationState(): AgentAutomationState =
        automationState(pendingSettlementCount = 0)

    private fun automationState(pendingSettlementCount: Int): AgentAutomationState {
        val control = automationControl.snapshot()
        return AgentAutomationState(
            authority = control.authority,
            nativeQueueAutoAdvance = control.nativeQueueAutoAdvance,
            pendingSettlementCount = pendingSettlementCount,
            dispatchEnabled = false,
        )
    }

    private fun com.fantasyidler.data.model.SkillSession.toAgentSession() =
        AgentSession(
            sessionId = sessionId,
            skillName = skillName,
            activityKey = activityKey,
            startedAt = startedAt,
            endsAt = endsAt,
        )
}

object LocalControlServer {
    private const val TAG = "IdleFantasyAgent"
    private const val PORT = 8080

    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    @Synchronized
    fun start(context: Context, bridge: AgentGameBridge) {
        if (server != null) return

        val deviceId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.let { "idlefantasy_$it" }
                ?: "idlefantasy_${Build.MODEL}"

        try {
            server = embeddedServer(CIO, host = "127.0.0.1", port = PORT) {
                install(ContentNegotiation) {
                    json(Json {
                        prettyPrint = true
                        encodeDefaults = true
                    })
                }

                routing {
                    get("/") {
                        call.respondText("Idle Fantasy local control server online")
                    }

                    get("/health") {
                        call.respond(
                            AgentHealth(
                                ok = true,
                                apiVersion = 2,
                                deviceId = deviceId,
                                automation = bridge.healthAutomationState(),
                            )
                        )
                    }

                    get("/api/v1/status") {
                        call.respond(
                            bridge.snapshot(
                                deviceId = deviceId,
                                battery = batteryPercent(context),
                            )
                        )
                    }

                    get("/api/v1/automation/control") {
                        call.respond(bridge.controlSnapshot())
                    }

                    post("/api/v1/automation/authority/{authority}") {
                        val target = call.parameters["authority"] ?: ""
                        val result = bridge.setAuthority(target)

                        if (result == null) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                AgentRejection(
                                    code = "invalid_authority",
                                    message = "Authority must be native or external.",
                                )
                            )
                        } else {
                            call.respond(result)
                        }
                    }

                    post("/api/v1/session/start") {
                        call.respond(
                            HttpStatusCode.Conflict,
                            AgentRejection(
                                code = "native_queue_disabled_for_automation",
                                message = "The native queue is self-advancing and cannot be externally started. Explicit dispatch is not enabled yet.",
                            )
                        )
                    }
                }
            }.start(wait = false)

            Log.i(TAG, "Local control server started on 127.0.0.1:$PORT")
        } catch (error: Exception) {
            server = null
            Log.e(TAG, "Unable to start local control server", error)
        }
    }

    private fun batteryPercent(context: Context): Int =
        try {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            100
        }
}
