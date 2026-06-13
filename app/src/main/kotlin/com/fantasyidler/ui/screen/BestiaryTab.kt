package com.fantasyidler.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fantasyidler.R
import com.fantasyidler.data.json.AlwaysDrop
import com.fantasyidler.data.json.BossData
import com.fantasyidler.data.json.DropEntry
import com.fantasyidler.data.json.EnemyData
import com.fantasyidler.ui.theme.GoldPrimary
import com.fantasyidler.ui.viewmodel.BestiaryEntry
import com.fantasyidler.ui.viewmodel.BestiarySort
import com.fantasyidler.ui.viewmodel.BestiaryViewModel
import com.fantasyidler.util.GameStrings
import com.fantasyidler.util.formatCoins
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BestiaryTab(viewModel: BestiaryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var selectedSubTab by remember { mutableIntStateOf(0) }
    var selectedEntry by remember { mutableStateOf<BestiaryEntry?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedSubTab) {
            Tab(
                selected = selectedSubTab == 0,
                onClick  = { selectedSubTab = 0 },
                text     = { Text(stringResource(R.string.label_enemies)) },
            )
            Tab(
                selected = selectedSubTab == 1,
                onClick  = { selectedSubTab = 1 },
                text     = { Text(stringResource(R.string.label_bosses)) },
            )
        }

        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.sort == BestiarySort.ALPHABETICAL,
                onClick  = { viewModel.setSort(BestiarySort.ALPHABETICAL) },
                label    = { Text("A–Z") },
            )
            FilterChip(
                selected = state.sort == BestiarySort.BY_LOCATION,
                onClick  = { viewModel.setSort(BestiarySort.BY_LOCATION) },
                label    = { Text(stringResource(R.string.label_by_location)) },
            )
        }

        val entries = if (selectedSubTab == 0) state.enemies else state.bosses
        BestiaryList(
            entries = entries,
            sort    = state.sort,
            onEntryClick = { selectedEntry = it },
        )
    }

    selectedEntry?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { selectedEntry = null },
            sheetState       = sheetState,
        ) {
            val context = LocalContext.current
            if (entry.enemy != null) {
                EnemyDetailContent(entry = entry, enemy = entry.enemy, context = context)
            } else if (entry.boss != null) {
                BossDetailContent(entry = entry, boss = entry.boss, context = context)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------

@Composable
private fun BestiaryList(
    entries: List<BestiaryEntry>,
    sort: BestiarySort,
    onEntryClick: (BestiaryEntry) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (sort == BestiarySort.BY_LOCATION) {
            val grouped = buildLocationGroups(entries)
            grouped.forEach { (groupName, groupEntries) ->
                item(key = "header_$groupName") {
                    Text(
                        text     = groupName,
                        style    = MaterialTheme.typography.labelMedium,
                        color    = GoldPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    HorizontalDivider()
                }
                items(groupEntries, key = { "${groupName}_${it.key}" }) { entry ->
                    BestiaryRow(entry = entry, onClick = { onEntryClick(entry) })
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        } else {
            items(entries, key = { it.key }) { entry ->
                BestiaryRow(entry = entry, onClick = { onEntryClick(entry) })
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun buildLocationGroups(
    entries: List<BestiaryEntry>,
): List<Pair<String, List<BestiaryEntry>>> {
    val grouped = mutableMapOf<String, MutableList<BestiaryEntry>>()
    entries.forEach { entry ->
        val locs = entry.locations.ifEmpty { listOf("Other") }
        locs.forEach { loc -> grouped.getOrPut(loc) { mutableListOf() }.add(entry) }
    }
    val sorted = grouped.entries
        .sortedWith(compareBy({ it.key == "Other" }, { it.key }))
        .map { it.key to it.value.sortedBy { e -> e.displayName } }
    return sorted
}

@Composable
private fun BestiaryRow(entry: BestiaryEntry, onClick: () -> Unit) {
    val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val dimSub   = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text  = entry.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.encountered) MaterialTheme.colorScheme.onSurface else dimColor,
            )
            if (entry.locations.isNotEmpty()) {
                Text(
                    text  = entry.locations.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.encountered) MaterialTheme.colorScheme.onSurfaceVariant else dimSub,
                )
            }
        }
        if (entry.killCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text  = "×${entry.killCount}",
                style = MaterialTheme.typography.labelMedium,
                color = GoldPrimary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Enemy detail sheet
// ---------------------------------------------------------------------------

@Composable
private fun EnemyDetailContent(
    entry: BestiaryEntry,
    enemy: EnemyData,
    context: android.content.Context,
) {
    val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text       = enemy.displayName,
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color      = if (entry.encountered) MaterialTheme.colorScheme.onSurface else dimColor,
                )
                if (entry.killCount > 0) {
                    Text(
                        text  = "${entry.killCount} kills",
                        style = MaterialTheme.typography.labelMedium,
                        color = GoldPrimary,
                    )
                }
            }
            if (!entry.encountered) {
                Text(
                    text  = stringResource(R.string.bestiary_not_encountered),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                DetailRow(stringResource(R.string.label_stats), "???")
                DetailRow(stringResource(R.string.label_drops), "???")
                DetailRow(stringResource(R.string.label_locations), "???")
                Spacer(Modifier.height(24.dp))
                return@item
            }
            Spacer(Modifier.height(8.dp))
        }

        if (!entry.encountered) return@LazyColumn

        item {
            SectionHeader(stringResource(R.string.label_stats))
            StatGrid(
                listOf(
                    "HP"  to enemy.hp.toString(),
                    "Atk" to enemy.combatStats.attackLevel.toString(),
                    "Str" to enemy.combatStats.strengthLevel.toString(),
                    "Def" to enemy.combatStats.defenseLevel.toString(),
                ),
            )
            Spacer(Modifier.height(4.dp))
            StatGrid(
                listOf(
                    stringResource(R.string.bestiary_atk_def)  to enemy.defensiveStats.attackDefense.toString(),
                    stringResource(R.string.bestiary_str_def)  to enemy.defensiveStats.strengthDefense.toString(),
                    stringResource(R.string.bestiary_rng_def)  to enemy.defensiveStats.rangedDefense.toString(),
                    stringResource(R.string.bestiary_mag_def)  to enemy.defensiveStats.magicDefense.toString(),
                ),
            )
            val xp = enemy.xpDrops.values.sum()
            if (xp > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "XP: $xp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (enemy.alwaysDrops.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.bestiary_always_drops)) }
            items(enemy.alwaysDrops) { drop ->
                DropRow("${GameStrings.itemName(context, drop.item)} ×${drop.quantity}", null)
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        if (enemy.dropTable.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.label_drops)) }
            items(enemy.dropTable) { drop ->
                val qty = if (drop.quantityMin == drop.quantityMax) "×${drop.quantityMin}"
                          else "×${drop.quantityMin}–${drop.quantityMax}"
                DropRow("${GameStrings.itemName(context, drop.item)} $qty", formatChance(drop.chance))
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        if (entry.locations.isNotEmpty()) {
            item {
                SectionHeader(stringResource(R.string.label_locations))
                entry.locations.forEach { loc ->
                    Text(
                        text     = "• $loc",
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        } else {
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Boss detail sheet
// ---------------------------------------------------------------------------

@Composable
private fun BossDetailContent(
    entry: BestiaryEntry,
    boss: BossData,
    context: android.content.Context,
) {
    val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text       = "${boss.emoji}  ${boss.displayName}",
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color      = if (entry.encountered) MaterialTheme.colorScheme.onSurface else dimColor,
                )
                if (entry.killCount > 0) {
                    Text(
                        text  = "${entry.killCount} kills",
                        style = MaterialTheme.typography.labelMedium,
                        color = GoldPrimary,
                    )
                }
            }
            if (!entry.encountered) {
                Text(
                    text  = stringResource(R.string.bestiary_not_encountered),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                DetailRow(stringResource(R.string.label_stats), "???")
                DetailRow(stringResource(R.string.label_drops), "???")
                Spacer(Modifier.height(24.dp))
                return@item
            }
            Spacer(Modifier.height(8.dp))
        }

        if (!entry.encountered) return@LazyColumn

        item {
            SectionHeader(stringResource(R.string.label_stats))
            StatGrid(
                listOf(
                    "HP"       to boss.hp.toString(),
                    "Req. Lv"  to boss.combatLevelRequired.toString(),
                    "Duration" to "${boss.durationMinutes} min",
                    "Atk"      to boss.combatStats.attackLevel.toString(),
                    "Str"      to boss.combatStats.strengthLevel.toString(),
                    "Def"      to boss.combatStats.defenseLevel.toString(),
                ),
            )
            Spacer(Modifier.height(4.dp))
            StatGrid(
                listOf(
                    stringResource(R.string.bestiary_atk_def) to boss.defensiveStats.attackDefense.toString(),
                    stringResource(R.string.bestiary_str_def) to boss.defensiveStats.strengthDefense.toString(),
                    stringResource(R.string.bestiary_rng_def) to boss.defensiveStats.rangedDefense.toString(),
                    stringResource(R.string.bestiary_mag_def) to boss.defensiveStats.magicDefense.toString(),
                ),
            )
            val xp = boss.xpRewards.values.sum()
            if (xp > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "XP: $xp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        item {
            SectionHeader(stringResource(R.string.bestiary_common_loot))
            val coins = boss.commonLoot
            Text(
                text     = "• ${stringResource(R.string.label_coins)}: ${coins.coinsMin.toLong().formatCoins()}–${coins.coinsMax.toLong().formatCoins()}",
                style    = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
            boss.commonLoot.items.forEach { (itemKey, range) ->
                val qty = if (range.min == range.max) "×${range.min}" else "×${range.min}–${range.max}"
                Text(
                    text     = "• ${GameStrings.itemName(context, itemKey)} $qty",
                    style    = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (boss.rareDrops.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.bestiary_rare_drops)) }
            items(boss.rareDrops) { drop ->
                DropRow(GameStrings.itemName(context, drop.item), formatChance(drop.chance))
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        boss.pet?.let { pet ->
            item {
                SectionHeader(stringResource(R.string.bestiary_pet_drop))
                DropRow("${pet.emoji} ${pet.displayName}", formatChance(pet.chance))
                Spacer(Modifier.height(12.dp))
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Shared UI helpers
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text       = text,
        style      = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color      = GoldPrimary,
        modifier   = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatGrid(stats: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text  = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text  = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DropRow(name: String, chance: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = "• $name",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (chance != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text  = chance,
                style = MaterialTheme.typography.bodySmall,
                color = GoldPrimary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------

private fun formatChance(chance: Double): String {
    if (chance >= 1.0) return "Always"
    val thousandths = (chance * 1000).roundToInt()
    if (thousandths <= 0) return "<0.1%"
    fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    val g = gcd(thousandths, 1000)
    return "${thousandths / g}/${1000 / g}"
}
