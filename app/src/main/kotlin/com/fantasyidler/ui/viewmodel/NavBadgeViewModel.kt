package com.fantasyidler.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.repository.GameDataRepository
import com.fantasyidler.repository.QuestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class NavBadgeViewModel @Inject constructor(
    questRepo: QuestRepository,
    gameData: GameDataRepository,
) : ViewModel() {

    val questsClaimableCount: StateFlow<Int> = questRepo.observeProgress()
        .map { progressList ->
            val progressMap = progressList.associateBy { it.questId }
            gameData.quests.count { (id, quest) ->
                val prog = progressMap[id]
                if (prog?.completed == true) return@count false
                val prereq = quest.requiresPrevious
                val prereqDone = prereq == null || progressMap[prereq]?.completed == true
                prereqDone && (prog?.progress ?: 0) >= quest.amount
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
