package com.fantasyidler.automation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AutomationControlSnapshot(
    val authority: String,
    @SerialName("native_queue_auto_advance")
    val nativeQueueAutoAdvance: Boolean,
    @SerialName("changed_at_epoch_ms")
    val changedAtEpochMs: Long,
    @SerialName("node_id")
    val nodeId: String? = null,
)

@Serializable
data class AutomationEnrollmentResult(
    val accepted: Boolean,
    val code: String,
    val message: String,
    val control: AutomationControlSnapshot,
)

@Singleton
class AutomationControlRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences("idlefantasy_automation_control", Context.MODE_PRIVATE)

    fun allowsNativeQueueAutoAdvance(): Boolean =
        preferences.getString(KEY_AUTHORITY, AUTHORITY_NATIVE) == AUTHORITY_NATIVE

    fun nodeId(): String? =
        preferences.getString(KEY_NODE_ID, null)

    fun snapshot(): AutomationControlSnapshot {
        val authority = preferences.getString(KEY_AUTHORITY, AUTHORITY_NATIVE) ?: AUTHORITY_NATIVE

        return AutomationControlSnapshot(
            authority = authority,
            nativeQueueAutoAdvance = authority == AUTHORITY_NATIVE,
            changedAtEpochMs = preferences.getLong(KEY_CHANGED_AT, 0L),
            nodeId = nodeId(),
        )
    }

    fun setAuthority(rawAuthority: String): AutomationControlSnapshot? {
        val authority = when (rawAuthority.trim().lowercase()) {
            AUTHORITY_NATIVE -> AUTHORITY_NATIVE
            AUTHORITY_EXTERNAL -> AUTHORITY_EXTERNAL
            else -> return null
        }

        preferences.edit()
            .putString(KEY_AUTHORITY, authority)
            .putLong(KEY_CHANGED_AT, System.currentTimeMillis())
            .commit()

        return snapshot()
    }

    fun enrollNode(rawNodeId: String): AutomationEnrollmentResult {
        val requestedNodeId = rawNodeId.trim().lowercase()

        if (!NODE_ID_PATTERN.matches(requestedNodeId)) {
            return AutomationEnrollmentResult(
                accepted = false,
                code = "invalid_node_id",
                message = "Node ID must use lowercase letters, numbers, underscores, or hyphens.",
                control = snapshot(),
            )
        }

        val currentNodeId = nodeId()

        if (currentNodeId != null && currentNodeId != requestedNodeId) {
            return AutomationEnrollmentResult(
                accepted = false,
                code = "node_already_enrolled",
                message = "This app instance is already enrolled as $currentNodeId.",
                control = snapshot(),
            )
        }

        preferences.edit()
            .putString(KEY_NODE_ID, requestedNodeId)
            .putLong(KEY_CHANGED_AT, System.currentTimeMillis())
            .commit()

        return AutomationEnrollmentResult(
            accepted = true,
            code = "enrolled",
            message = "Node identity is bound to $requestedNodeId.",
            control = snapshot(),
        )
    }

    companion object {
        const val AUTHORITY_NATIVE = "native"
        const val AUTHORITY_EXTERNAL = "external"

        private const val KEY_AUTHORITY = "authority"
        private const val KEY_CHANGED_AT = "changed_at_epoch_ms"
        private const val KEY_NODE_ID = "node_id"

        private val NODE_ID_PATTERN = Regex("[a-z][a-z0-9_-]{2,63}")
    }
}
