package com.fantasyidler.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_settlement_receipts")
data class SessionSettlementReceipt(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "origin")
    val origin: String,

    @ColumnInfo(name = "command_id")
    val commandId: String? = null,

    @ColumnInfo(name = "settled_at_epoch_ms")
    val settledAtEpochMs: Long,

    @ColumnInfo(name = "settlement_version")
    val settlementVersion: Int,

    @ColumnInfo(name = "outcome_json")
    val outcomeJson: String,
)
