package com.carrier.entitlement.validator.data.repository

import com.carrier.entitlement.validator.data.model.TraceEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TraceRepository {
    private val _traces = MutableStateFlow<List<TraceEntry>>(emptyList())
    val traces: StateFlow<List<TraceEntry>> = _traces.asStateFlow()

    fun addTrace(entry: TraceEntry) {
        _traces.update { current ->
            // Prepend new trace (most recent first)
            listOf(entry) + current
        }
    }

    fun clear() {
        _traces.value = emptyList()
    }
}
