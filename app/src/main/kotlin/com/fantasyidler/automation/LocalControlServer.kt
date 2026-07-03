package com.fantasyidler.automation

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.fantasyidler.BuildConfig
import com.fantasyidler.data.model.Player
import com.fantasyidler.data.model.QueuedAction
import com.fantasyidler.data.model.SkillSession
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
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AgentSession(
    @SerialName("session_id") val sessionId: String,
    @SerialName("skill_name") val skillName: String,
    @SerialName("activity_key") val activityKey: String,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("ends_at") val endsAt: Long,
    val origin: String,
    @SerialName("command_id") val commandId: String? = null,
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
data class AgentReadiness(
    @SerialName("node_id") val nodeId: String?,
    @SerialName("state_fingerprint") val stateFingerprint: String,
    @SerialName("preconditions_met") val preconditionsMet: Boolean,
    val blockers: List<String>,
    @SerialName("observed_at_epoch_ms") val observedAtEpochMs: Long,
)

@Serializable
data class AgentCompletedSession(
    @SerialName("session_id") val sessionId: String,
    @SerialName("skill_name") val skillName: String,
    @SerialName("activity_key") val activityKey: String,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("ends_at") val endsAt: Long,
    val origin: String,
    @SerialName("command_id") val commandId: String? = null,
)

@Serializable
data class AgentExecutionTrace(
    @SerialName("node_id") val nodeId: String?,
    @SerialName("observed_at_epoch_ms") val observedAtEpochMs: Long,
    @SerialName("active_session") val activeSession: AgentSession?,
    @SerialName("completed_unsettled_sessions")
    val completedUnsettledSessions: List<AgentCompletedSession>,
    @SerialName("native_queue_count") val nativeQueueCount: Int,
    @SerialName("native_queued_actions") val nativeQueuedActions: List<String>,
    val readiness: AgentReadiness,
)

@Serializable
data class AgentStatus(
    @SerialName("api_version") val apiVersion: Int,
    @SerialName("node_id") val nodeId: String?,
    @SerialName("device_id") val deviceId: String,
    @SerialName("android_instance_id") val androidInstanceId: String,
    val status: String,
    val battery: Int,
    val coins: Long,
    val stats: Map<String, Int>,
    val inventory: Map<String, Int>,
    @SerialName("native_queue_count") val nativeQueueCount: Int,
    @SerialName("native_queued_actions") val nativeQueuedActions: List<String>,
    @SerialName("active_session") val activeSession: AgentSession?,
    val automation: AgentAutomationState,
    val readiness: AgentReadiness,
)

@Serializable
data class AgentHealth(
    val ok: Boolean,
    @SerialName("api_version") val apiVersion: Int,
    @SerialName("node_id") val nodeId: String?,
    @SerialName("device_id") val deviceId: String,
    @SerialName("android_instance_id") val androidInstanceId: String,
    val automation: AgentAutomationState,
)

@Serializable
data class AgentRejection(
    val accepted: Boolean = false,
    val code: String,
    val message: String,
)

@Serializable
data class AgentLifecycleTestResult(
    val accepted: Boolean,
    val code: String,
    val message: String,
    @SerialName("session_id") val sessionId: String? = null,
)

@Singleton
class AgentGameBridge @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val sessionRepository: SessionRepository,
    private val automationControl: AutomationControlRepository,
    private val settlementService: SessionSettlementService,
) {
    suspend fun snapshot(androidInstanceId: String, battery: Int): AgentStatus {
        val active = sessionRepository.getActiveSession()?.takeUnless { it.completed }
        val pending = sessionRepository.getAllCompletedSessions()
        val queue = playerRepository.getQueue()
        val player = playerRepository.getOrCreatePlayer()
        val control = automationControl.snapshot()
        val deviceId = control.nodeId ?: androidInstanceId
        val readiness = buildReadiness(player, active, pending, queue, control)

        return AgentStatus(
            apiVersion = 3,
            nodeId = control.nodeId,
            deviceId = deviceId,
            androidInstanceId = androidInstanceId,
            status = when {
                active != null -> "busy"
                pending.isNotEmpty() -> "pending_settlement"
                else -> "idle"
            },
            battery = battery,
            coins = player.coins,
            stats = playerRepository.getSkillLevels(),
            inventory = playerRepository.getInventory(),
            nativeQueueCount = queue.size,
            nativeQueuedActions = queue.map { "${it.skillName}:${it.activityKey}" },
            activeSession = active?.toAgentSession(),
            automation = automationState(pending.size),
            readiness = readiness,
        )
    }

    suspend fun settleWoodcuttingForLifecycleTest(
        sessionId: String,
    ): RangerSettlementResult =
        settlementService.settleCompletedWoodcutting(sessionId)

    suspend fun completeActiveSessionForLifecycleTest(): AgentLifecycleTestResult {
        val active = sessionRepository.getActiveSession()?.takeUnless { it.completed }

        if (active == null) {
            return AgentLifecycleTestResult(
                accepted = false,
                code = "no_active_session",
                message = "No active player session is available for lifecycle testing.",
            )
        }

        sessionRepository.markCompleted(active.sessionId)

        return AgentLifecycleTestResult(
            accepted = true,
            code = "session_marked_completed",
            message = "Session was marked completed. Rewards remain unsettled until normal collection.",
            sessionId = active.sessionId,
        )
    }

    suspend fun executionTrace(): AgentExecutionTrace {
        val active = sessionRepository.getActiveSession()?.takeUnless { it.completed }
        val pending = sessionRepository.getAllCompletedSessions()
        val queue = playerRepository.getQueue()
        val player = playerRepository.getOrCreatePlayer()
        val control = automationControl.snapshot()

        return AgentExecutionTrace(
            nodeId = control.nodeId,
            observedAtEpochMs = System.currentTimeMillis(),
            activeSession = active?.toAgentSession(),
            completedUnsettledSessions = pending.map { session ->
                AgentCompletedSession(
                    sessionId = session.sessionId,
                    skillName = session.skillName,
                    activityKey = session.activityKey,
                    startedAt = session.startedAt,
                    endsAt = session.endsAt,
                    origin = session.origin,
                    commandId = session.commandId,
                )
            },
            nativeQueueCount = queue.size,
            nativeQueuedActions = queue.map { "${it.skillName}:${it.activityKey}" },
            readiness = buildReadiness(player, active, pending, queue, control),
        )
    }

    suspend fun readiness(): AgentReadiness {
        val active = sessionRepository.getActiveSession()?.takeUnless { it.completed }
        val pending = sessionRepository.getAllCompletedSessions()
        val queue = playerRepository.getQueue()
        val player = playerRepository.getOrCreatePlayer()
        return buildReadiness(player, active, pending, queue, automationControl.snapshot())
    }

    fun health(androidInstanceId: String): AgentHealth {
        val control = automationControl.snapshot()
        val deviceId = control.nodeId ?: androidInstanceId

        return AgentHealth(
            ok = true,
            apiVersion = 3,
            nodeId = control.nodeId,
            deviceId = deviceId,
            androidInstanceId = androidInstanceId,
            automation = automationState(0),
        )
    }

    fun controlSnapshot(): AutomationControlSnapshot =
        automationControl.snapshot()

    fun setAuthority(rawAuthority: String): AutomationControlSnapshot? =
        automationControl.setAuthority(rawAuthority)

    fun enrollNode(nodeId: String): AutomationEnrollmentResult =
        automationControl.enrollNode(nodeId)

    private fun automationState(pendingSettlementCount: Int): AgentAutomationState {
        val control = automationControl.snapshot()

        return AgentAutomationState(
            authority = control.authority,
            nativeQueueAutoAdvance = control.nativeQueueAutoAdvance,
            pendingSettlementCount = pendingSettlementCount,
            dispatchEnabled = false,
        )
    }

    private fun buildReadiness(
        player: Player,
        active: SkillSession?,
        pending: List<SkillSession>,
        queue: List<QueuedAction>,
        control: AutomationControlSnapshot,
    ): AgentReadiness {
        val blockers = mutableListOf<String>()

        if (control.nodeId.isNullOrBlank()) blockers += "node_not_enrolled"
        if (control.authority != AutomationControlRepository.AUTHORITY_EXTERNAL) {
            blockers += "authority_not_external"
        }
        if (active != null) blockers += "active_session"
        if (pending.isNotEmpty()) blockers += "pending_settlement"
        if (queue.isNotEmpty()) blockers += "native_queue_not_empty"

        val payload = buildString {
            append(control.nodeId ?: "")
            append('|')
            append(control.authority)
            append('|')
            append(player.coins)
            append('|')
            append(player.skillLevels)
            append('|')
            append(player.skillXp)
            append('|')
            append(player.inventory)
            append('|')
            append(player.equipped)
            append('|')
            append(player.flags)
            append('|')
            append(active?.sessionId ?: "")
            append('|')
            append(active?.endsAt ?: 0L)
            append('|')
            append(pending.joinToString(",") { "${it.sessionId}:${it.endsAt}" })
            append('|')
            append(queue.joinToString(",") { "${it.skillName}:${it.activityKey}:${it.qty}" })
        }

        return AgentReadiness(
            nodeId = control.nodeId,
            stateFingerprint = sha256(payload),
            preconditionsMet = blockers.isEmpty(),
            blockers = blockers,
            observedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun SkillSession.toAgentSession() =
        AgentSession(
            sessionId = sessionId,
            skillName = skillName,
            activityKey = activityKey,
            startedAt = startedAt,
            endsAt = endsAt,
            origin = origin,
            commandId = commandId,
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

        val androidInstanceId =
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
                        call.respond(bridge.health(androidInstanceId))
                    }

                    get("/api/v1/status") {
                        call.respond(
                            bridge.snapshot(
                                androidInstanceId = androidInstanceId,
                                battery = batteryPercent(context),
                            )
                        )
                    }

                    get("/api/v1/automation/readiness") {
                        call.respond(bridge.readiness())
                    }

                    get("/api/v1/automation/execution-trace") {
                        call.respond(bridge.executionTrace())
                    }

                    post("/api/v1/debug/settle-woodcutting/{sessionId}") {
                        if (!BuildConfig.DEBUG) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                AgentRejection(
                                    code = "debug_endpoint_disabled",
                                    message = "Settlement test endpoints are available only in debug builds.",
                                )
                            )
                        } else {
                            val sessionId = call.parameters["sessionId"] ?: ""

                            if (sessionId.isBlank()) {
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    AgentRejection(
                                        code = "missing_session_id",
                                        message = "A session ID is required.",
                                    )
                                )
                            } else {
                                val result = bridge.settleWoodcuttingForLifecycleTest(sessionId)
                                val status = if (result.accepted) {
                                    HttpStatusCode.OK
                                } else {
                                    HttpStatusCode.Conflict
                                }

                                call.respond(status, result)
                            }
                        }
                    }

                    post("/api/v1/debug/complete-active-session") {
                        if (!BuildConfig.DEBUG) {
                            call.respond(
                                HttpStatusCode.Forbidden,
                                AgentRejection(
                                    code = "debug_endpoint_disabled",
                                    message = "Lifecycle test endpoints are available only in debug builds.",
                                )
                            )
                        } else {
                            val result = bridge.completeActiveSessionForLifecycleTest()
                            val status = if (result.accepted) {
                                HttpStatusCode.OK
                            } else {
                                HttpStatusCode.Conflict
                            }

                            call.respond(status, result)
                        }
                    }

                    get("/api/v1/automation/control") {
                        call.respond(bridge.controlSnapshot())
                    }

                    post("/api/v1/automation/enroll/{nodeId}") {
                        val requestedNodeId = call.parameters["nodeId"] ?: ""
                        val result = bridge.enrollNode(requestedNodeId)

                        val status = when {
                            result.accepted -> HttpStatusCode.OK
                            result.code == "invalid_node_id" -> HttpStatusCode.BadRequest
                            else -> HttpStatusCode.Conflict
                        }

                        call.respond(status, result)
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
