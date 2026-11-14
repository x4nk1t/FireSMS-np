package com.firesms.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesms.app.FireSmsApp
import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.domain.rules.NepalDefaultRules
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RulesViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as FireSmsApp).database.parserRuleDao()

    val rules: StateFlow<List<ParserRule>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** True when one or more Nepal default rules have been deleted and can be added back. */
    val defaultRulesMissing: StateFlow<Boolean> = dao.getAll()
        .map { list -> !list.map { it.id }.containsAll(NepalDefaultRules.ids) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun addMissingDefaultRules() {
        viewModelScope.launch {
            dao.insertMissing(NepalDefaultRules.rules)
        }
    }

    fun toggleEnabled(rule: ParserRule) {
        viewModelScope.launch {
            dao.upsert(rule.copy(enabled = !rule.enabled))
        }
    }

    fun deleteRule(rule: ParserRule) {
        viewModelScope.launch {
            dao.delete(rule)
        }
    }
}
