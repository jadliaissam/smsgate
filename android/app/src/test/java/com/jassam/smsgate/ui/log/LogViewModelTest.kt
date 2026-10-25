package com.jassam.smsgate.ui.log

import androidx.lifecycle.viewModelScope
import com.jassam.smsgate.journal.JournalEntry
import com.jassam.smsgate.journal.JournalState
import com.jassam.smsgate.journal.JournalSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModelTest {
    private val vms = mutableListOf<LogViewModel>()

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    /** A fake journal: newest first, bounded by the requested limit, like the DAO query. */
    private class FakeJournal(n: Int) {
        val rows = MutableStateFlow((0 until n).map { summary("m$it", createdAt = it.toLong()) }.sortedByDescending { it.createdAt })
        val entries = MutableStateFlow<Map<String, JournalEntry>>(emptyMap())
        val limits = mutableListOf<Int>()

        fun recent(limit: Int): Flow<List<JournalSummary>> {
            limits += limit
            return rows.map { it.take(limit) }
        }

        fun entry(id: String): Flow<JournalEntry?> = entries.map { it[id] }
    }

    private fun TestScope.vm(j: FakeJournal, recent: (Int) -> Flow<List<JournalSummary>> = j::recent): LogViewModel {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = LogViewModel(recent, j::entry)
        vms += vm
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.detail.collect {} }
        return vm
    }

    @Test
    fun emptyJournalIsAnEmptyStateNotLoading() = runTest {
        val s = vm(FakeJournal(0)).state.value
        assertFalse(s.loading)
        assertTrue(s.items.isEmpty())
        assertFalse(s.canLoadMore)
    }

    @Test
    fun keepsTheQueryOrderAndMasksNumbers() = runTest {
        val s = vm(FakeJournal(3)).state.value
        assertEquals(listOf("m2", "m1", "m0"), s.items.map { it.id })
        assertTrue(s.items.all { it.maskedTo == "+212 6** *** *01" })
    }

    @Test
    fun theListIsBoundedAndGrowsOnLoadMore() = runTest {
        val j = FakeJournal(450)
        val vm = vm(j)
        assertEquals(LogViewModel.PAGE, vm.state.value.items.size)
        assertTrue(vm.state.value.canLoadMore)
        vm.loadMore()
        assertEquals(400, vm.state.value.items.size)
        vm.loadMore()
        assertEquals(450, vm.state.value.items.size)
        assertFalse(vm.state.value.canLoadMore)
        vm.loadMore() // nothing more: the limit stays
        assertEquals(listOf(200, 400, 600), j.limits)
    }

    @Test
    fun theWindowStopsAtTheCap() = runTest {
        val j = FakeJournal(LogViewModel.MAX + 10)
        val vm = vm(j)
        repeat(20) { vm.loadMore() }
        assertEquals(LogViewModel.MAX, vm.state.value.items.size)
        assertFalse(vm.state.value.canLoadMore)
        assertEquals(LogViewModel.MAX, j.limits.last())
    }

    @Test
    fun aFailingQueryShowsTheEmptyState() = runTest {
        val j = FakeJournal(1)
        val s = vm(j) { flow { throw IllegalStateException("db") } }.state.value
        assertFalse(s.loading)
        assertTrue(s.items.isEmpty())
    }

    @Test
    fun theDetailFollowsTheSelectedRow() = runTest {
        val j = FakeJournal(1)
        val vm = vm(j)
        val e = JournalEntry(
            id = "m0", to = "+212612345689", body = "hello", simSlot = null, expiresAt = 2, state = JournalState.SENT,
            parts = 1, partsSent = 1, createdAt = 0, updatedAt = 1,
        )
        j.entries.value = mapOf("m0" to e)
        assertNull(vm.detail.value)
        vm.open("m0")
        assertEquals("hello", vm.detail.value?.body)
        j.entries.value = mapOf("m0" to e.copy(body = null))
        assertEquals(true, vm.detail.value?.bodyRemoved)
        vm.close()
        assertNull(vm.detail.value)
    }

    private companion object {
        fun summary(id: String, createdAt: Long) =
            JournalSummary(id, "+212600000001", JournalState.SENT, null, null, 1, 1, 0, createdAt, createdAt)
    }
}
