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
)

@Singleton
class AutomationControlRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences("idlefantasy_automation_control", Context.MODE_PRIVATE)

    fun allowsNativeQueueAutoAdvance(): Boolean =
        preferences.getString(KEY_AUTHORITY, AUTHORITY_NATIVE) == AUTHORITY_NATIVE

    fun snapshot(): AutomationControlSnapshot {
        val authority = preferences.getString(KEY_AUTHORITY, AUTHORITY_NATIVE) ?: AUTHORITY_NATIVE
        return AutomationControlSnapshot(
            authority = authority,
            nativeQueueAutoAdvance = authority == AUTHORITY_NATIVE,
            changedAtEpochMs = preferences.getLong(KEY_CHANGED_AT, 0L),
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

    companion object {
        const val AUTHORITY_NATIVE = "native"
        const val AUTHORITY_EXTERNAL = "external"
        private const val KEY_AUTHORITY = "authority"
        private const val KEY_CHANGED_AT = "changed_at_epoch_ms"
    }
}
