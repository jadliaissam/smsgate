package com.jassam.smsgate.journal

import androidx.room.Room
import org.robolectric.RuntimeEnvironment

internal fun inMemoryJournal(): JournalDb =
    Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), JournalDb::class.java).build()

internal fun claimedEntry(id: String, now: Long = 1_000, body: String? = "hello", simSlot: Int? = null) = JournalEntry(
    id = id, to = "+212600000001", body = body, simSlot = simSlot, expiresAt = now + 60_000, createdAt = now, updatedAt = now,
)

/** Test-only: put a row into an arbitrary state, bypassing the journal's rules. */
internal fun JournalDb.force(id: String, state: JournalState, parts: Int = 0, sent: Long = 0, delivered: Long = 0) {
    openHelper.writableDatabase.execSQL(
        "UPDATE journal SET state = ?, parts = ?, partsSent = ?, partsDelivered = ? WHERE id = ?",
        arrayOf<Any>(state.name, parts, sent, delivered, id),
    )
}
