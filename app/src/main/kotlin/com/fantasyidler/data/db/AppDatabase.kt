package com.fantasyidler.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fantasyidler.data.db.dao.*
import com.fantasyidler.data.model.*

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE skill_sessions ADD COLUMN is_worker_session INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE skill_sessions ADD COLUMN efficiency_multiplier REAL NOT NULL DEFAULT 1.0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE skill_sessions ADD COLUMN worker_slot INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE skill_sessions SET worker_slot = 1 WHERE is_worker_session = 1")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE skill_sessions ADD COLUMN origin TEXT NOT NULL DEFAULT 'native'")
        db.execSQL("ALTER TABLE skill_sessions ADD COLUMN command_id TEXT")
    }
}


val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS session_settlement_receipts (
                session_id TEXT NOT NULL,
                origin TEXT NOT NULL,
                command_id TEXT,
                settled_at_epoch_ms INTEGER NOT NULL,
                settlement_version INTEGER NOT NULL,
                outcome_json TEXT NOT NULL,
                PRIMARY KEY(session_id)
            )
            """.trimIndent()
        )
    }
}

@Database(
    entities = [
        Player::class,
        SkillSession::class,
        SessionSettlementReceipt::class,
        QuestProgress::class,
        FarmingPatch::class,
        GlobalState::class,
        ArenaRecord::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playerDao(): PlayerDao
    abstract fun skillSessionDao(): SkillSessionDao
    abstract fun sessionSettlementReceiptDao(): SessionSettlementReceiptDao
    abstract fun questProgressDao(): QuestProgressDao
    abstract fun farmingPatchDao(): FarmingPatchDao
    abstract fun globalStateDao(): GlobalStateDao
    abstract fun arenaRecordDao(): ArenaRecordDao
}
