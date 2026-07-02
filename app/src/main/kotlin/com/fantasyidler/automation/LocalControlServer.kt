package com.fantasyidler.automation

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.QueuedSessionStarter
import com.fantasyidler.repository.SessionRepository
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
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
data class AgentStatus(
    @SerialName("device_id") val deviceId: String,
    val status: String,
    val battery: Int,
    val stats: Map<String, Int>,
    val inventory: Map<String, Int>,
    @SerialName("queue_count") val queueCount: Int,
    @SerialName("queued_actions") val queuedActions: List<String>,
    @SerialName("active_session") val activeSession: AgentSession?,
)

@Serializable
data class AgentCommandResult(
    val accepted: Boolean,
    val code: String,
    val message: String,
    @SerialName("active_session") val activeSession: AgentSession? = null,
)

@Serializable
data class AgentHealth(
    val ok: Boolean,
    @SerialName("device_id") val deviceId: String,
)

@Singleton
class AgentGameBridge @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val sessionRepository: SessionRepository,
    private val queuedSessionStarter: QueuedSessionStarter,
) {
    suspend fun snapshot(deviceId: String, battery: Int): AgentStatus {
        val active = sessionRepository.getActiveSession()?.takeUnless { it.completed }
        val queue = playerRepository.getQueue()

        return AgentStatus(
            deviceId = deviceId,
            status = if (active == null) "idle" else "busy",
            battery = battery,
            stats = playerRepository.getSkillLevels(),
            inventory = playerRepository.getInventory(),
            queueCount = queue.size,
            queuedActions = queue.map { "${it.skillName}:${it.activityKey}" },
            activeSession = active?.toAgentSession(),
        )
    }

    suspend fun startQueuedSession(): AgentCommandResult {
        val current = sessionRepository.getActiveSession()?.takeUnless { it.completed }
        if (current != null) {
            return AgentCommandResult(
                accepted = false,
                code = "active_session",
                message = "A session is already running.",
                activeSession = current.toAgentSession(),
            )
        }

        if (playerRepository.getQueue().isEmpty()) {
            return AgentCommandResult(
                accepted = false,
                code = "queue_empty",
                message = "No queued game action is available.",
            )
        }

        val started = try {
            queuedSessionStarter.startNextQueued()
        } catch (_: Exception) {
            false
        }

        val active = sessionRepository.getActiveSession()?.takeUnless { it.completed }

        return if (started && active != null) {
            AgentCommandResult(
                accepted = true,
                code = "started",
                message = "Queued session started through the game engine.",
                activeSession = active.toAgentSession(),
            )
        } else {
            AgentCommandResult(
                accepted = false,
                code = "queue_rejected",
                message = "The queued action could not start; it may be invalid or lack required materials.",
            )
        }
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
                        call.respond(AgentHealth(ok = true, deviceId = deviceId))
                    }

                    get("/api/v1/status") {
                        call.respond(
                            bridge.snapshot(
                                deviceId = deviceId,
                                battery = batteryPercent(context),
                            )
                        )
                    }

                    post("/api/v1/session/start") {
                        val result = bridge.startQueuedSession()
                        val code = if (result.accepted) HttpStatusCode.OK else HttpStatusCode.Conflict
                        call.respond(code, result)
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
