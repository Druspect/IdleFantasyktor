package com.fantasyidler.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fantasyidler.data.model.SessionSettlementReceipt

@Dao
interface SessionSettlementReceiptDao {
    @Query(
        """
        SELECT * FROM session_settlement_receipts
        WHERE session_id = :sessionId
        LIMIT 1
        """
    )
    suspend fun get(sessionId: String): SessionSettlementReceipt?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(receipt: SessionSettlementReceipt)

    @Query("SELECT COUNT(*) FROM session_settlement_receipts")
    suspend fun count(): Int
}
