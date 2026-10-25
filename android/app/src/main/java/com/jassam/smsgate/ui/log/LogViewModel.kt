package com.jassam.smsgate.ui.log

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jassam.smsgate.journal.JournalEntry
import com.jassam.smsgate.journal.JournalSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

@Immutable
data class LogUiState(
    val loading: Boolean = true,
    val items: List<LogItem> = emptyList(),
    /** More rows may exist beyond the current window ("Load more"). */
    val canLoadMore: Boolean = false,
)

/**
 * The journal, newest first, in a bounded window ([PAGE] rows, growing by [PAGE] on
 * [loadMore] up to [MAX]) straight from a Room flow; no paging library (D10). The
 * detail sheet observes one row with its body.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModel(
    private val observeRecent: (limit: Int) -> Flow<List<JournalSummary>>,
    private val observeEntry: (id: String) -> Flow<JournalEntry?>,
    private val log: (String) -> Unit = {},
) : ViewModel() {
    private val limit = MutableStateFlow(PAGE)
    private val selected = MutableStateFlow<String?>(null)

    val state: StateFlow<LogUiState> = limit
        .flatMapLatest { n ->
            observeRecent(n).map { rows -> LogUiState(loading = false, items = rows.map(LogFormat::item), canLoadMore = rows.size >= n && n < MAX) }
        }
        .catch { e ->
            log("log: journal unreadable: ${e.javaClass.simpleName}")
            emit(LogUiState(loading = false))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LogUiState())

    /** The open detail sheet's content; null when closed or the row is gone. */
    val detail: StateFlow<LogDetail?> = selected
        .flatMapLatest { id -> if (id == null) flowOf(null) else observeEntry(id).map { it?.let(LogFormat::detail) } }
        .catch { e ->
            log("log: entry unreadable: ${e.javaClass.simpleName}")
            emit(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The id of the open sheet (kept so a configuration change reopens it). */
    val selectedId: StateFlow<String?> get() = selected

    fun loadMore() {
        if (!state.value.canLoadMore) return
        limit.update { (it + PAGE).coerceAtMost(MAX) }
    }

    fun open(id: String) {
        selected.value = id
    }

    fun close() {
        selected.value = null
    }

    companion object {
        const val PAGE = 200
        const val MAX = 2_000
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
