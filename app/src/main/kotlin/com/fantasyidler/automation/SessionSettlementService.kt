package com.fantasyidler.automation

import androidx.room.withTransaction
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.db.dao.SessionSettlementReceiptDao
import com.fantasyidler.data.db.dao.SkillSessionDao
import com.fantasyidler.data.model.EquipSlot
import com.fantasyidler.data.model.OwnedPet
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.RecentSession
import com.fantasyidler.data.model.SessionFrame
import com.fantasyidler.data.model.SessionSettlementReceipt
import com.fantasyidler.data.model.Skills
import com.fantasyidler.repository.GameDataRepository
import com.fantasyidler.repository.GuildRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.QuestRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RangerSettlementResult(
    val accepted: Boolean,
    val code: String,
    val message: String,
    val sessionId: String? = null,
    val xpGained: Long = 0L,
    val coinsGained: Long = 0L,
    val itemsGained: Map<String, Int> = emptyMap(),
    val awardedCapes: List<String> = emptyList(),
    val petFoundName: String? = null,
    val settledAtEpochMs: Long? = null,
)

@Serializable
private data class RangerSettlementReceiptPayload(
    val skillName: String,
    val activityKey: String,
    val xpGained: Long,
    val coinsGained: Long,
    val itemsGained: Map<String, Int>,
    val awardedCapes: List<String>,
    val petFoundName: String? = null,
)

@Singleton
class SessionSettlementService @Inject constructor(
    private val database: AppDatabase,
    private val sessionDao: SkillSessionDao,
    private val receiptDao: SessionSettlementReceiptDao,
    private val playerRepository: PlayerRepository,
    private val questRepository: QuestRepository,
    private val guildRepository: GuildRepository,
    private val gameData: GameDataRepository,
    private val json: Json,
) {
    suspend fun settleCompletedWoodcutting(sessionId: String): RangerSettlementResult =
        database.withTransaction {
            val existingReceipt = receiptDao.get(sessionId)

            if (existingReceipt != null) {
                return@withTransaction RangerSettlementResult(
                    accepted = true,
                    code = "already_settled",
                    message = "This session already has a durable settlement receipt.",
                    sessionId = sessionId,
                    settledAtEpochMs = existingReceipt.settledAtEpochMs,
                )
            }

            val session = sessionDao.getSession(sessionId)
                ?: return@withTransaction RangerSettlementResult(
                    accepted = false,
                    code = "session_not_found",
                    message = "No session exists with this ID.",
                    sessionId = sessionId,
                )

            if (!session.completed) {
                return@withTransaction RangerSettlementResult(
                    accepted = false,
                    code = "session_not_completed",
                    message = "The session must be completed before settlement.",
                    sessionId = sessionId,
                )
            }

            if (session.workerSlot != 0) {
                return@withTransaction RangerSettlementResult(
                    accepted = false,
                    code = "worker_session_not_supported",
                    message = "This settlement path supports only player sessions.",
                    sessionId = sessionId,
                )
            }

            if (session.skillName != Skills.WOODCUTTING) {
                return@withTransaction RangerSettlementResult(
                    accepted = false,
                    code = "skill_not_supported",
                    message = "This settlement path currently supports Woodcutting only.",
                    sessionId = sessionId,
                )
            }

            val frames = try {
                json.decodeFromString<List<SessionFrame>>(session.frames)
            } catch (_: Exception) {
                return@withTransaction RangerSettlementResult(
                    accepted = false,
                    code = "invalid_session_frames",
                    message = "The stored session frame data could not be decoded.",
                    sessionId = sessionId,
                )
            }

            guildRepository.ensureGuildDailiesRefreshed()

            val totalXp = frames.sumOf { it.xpGain.toLong() }
            val allItems = mutableMapOf<String, Int>()

            for (frame in frames) {
                for ((item, quantity) in frame.items) {
                    allItems[item] = (allItems[item] ?: 0) + quantity
                }
            }

            val coins = allItems.remove("coins")?.toLong() ?: 0L
            val petIds = gameData.pets.keys
            val pets = allItems.filterKeys { it in petIds }
            val rawRegular = allItems.filterKeys { it !in petIds }

            val player = playerRepository.getOrCreatePlayer()
            val flags: PlayerFlags = json.decodeFromString(player.flags)
            val equipped: Map<String, String?> = json.decodeFromString(player.equipped)
            val equippedCape = equipped[EquipSlot.CAPE]?.let { gameData.equipment[it] }
            val capeSkill = equippedCape?.capeSkill
            val capeBonus = equippedCape?.capeBonus ?: 0f
            val prestige = flags.skillPrestige[session.skillName] ?: 0

            val capeMultiplier = if (capeSkill == session.skillName) {
                1f + capeBonus * (prestige + 1)
            } else {
                1f
            }

            val effectiveXp = if (capeMultiplier > 1f && rawRegular.isEmpty()) {
                (totalXp * capeMultiplier).toLong()
            } else {
                totalXp
            }

            val regularItems = if (capeMultiplier > 1f && rawRegular.isNotEmpty()) {
                rawRegular.mapValues { (_, quantity) ->
                    (quantity * capeMultiplier).roundToInt()
                }
            } else {
                rawRegular
            }

            if (coins > 0L) {
                playerRepository.addCoins(coins)
            }

            val awardedCapes = playerRepository.applySessionResults(
                skillName = session.skillName,
                xpGained = effectiveXp,
                itemsGained = regularItems,
            )

            questRepository.recordGathering(session.skillName, regularItems)
            playerRepository.recordDailyGathering(regularItems)
            guildRepository.recordGuildGathering(session.skillName, regularItems)

            var petFoundName: String? = null

            for ((petId, _) in pets) {
                val petData = gameData.pets[petId] ?: continue

                if (playerRepository.addPetIfNew(petId, petData.boostPercent)) {
                    petFoundName = petData.displayName
                }
            }

            val activityDisplayName = session.activityKey
                .replace("_", " ")
                .split(" ")
                .joinToString(" ") { value ->
                    value.replaceFirstChar { char -> char.titlecase() }
                }

            val latestFlags = playerRepository.getFlags()

            playerRepository.updateFlags(
                latestFlags.copy(
                    recentSessions = (
                        listOf(
                            RecentSession(
                                skillName = session.skillName,
                                activityDisplayName = activityDisplayName,
                                activityKey = session.activityKey,
                            )
                        ) + latestFlags.recentSessions
                    ).take(10)
                )
            )

            val settledAt = System.currentTimeMillis()

            val receiptPayload = RangerSettlementReceiptPayload(
                skillName = session.skillName,
                activityKey = session.activityKey,
                xpGained = effectiveXp,
                coinsGained = coins,
                itemsGained = regularItems,
                awardedCapes = awardedCapes,
                petFoundName = petFoundName,
            )

            receiptDao.insert(
                SessionSettlementReceipt(
                    sessionId = session.sessionId,
                    origin = session.origin,
                    commandId = session.commandId,
                    settledAtEpochMs = settledAt,
                    settlementVersion = 1,
                    outcomeJson = json.encodeToString(receiptPayload),
                )
            )

            sessionDao.delete(session.sessionId)

            RangerSettlementResult(
                accepted = true,
                code = "settled",
                message = "Woodcutting results were applied and recorded atomically.",
                sessionId = session.sessionId,
                xpGained = effectiveXp,
                coinsGained = coins,
                itemsGained = regularItems,
                awardedCapes = awardedCapes,
                petFoundName = petFoundName,
                settledAtEpochMs = settledAt,
            )
        }
}
