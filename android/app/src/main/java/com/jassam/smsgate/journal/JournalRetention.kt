package com.jassam.smsgate.journal

/**
 * Journal retention (Global Constraints, body privacy): bodies are dropped 24 h
 * after the claim, metadata is kept 30 days. Run by the periodic SyncWorker (A6).
 */
object JournalRetention {
    const val BODY_TTL_MS: Long = 24L * 60 * 60 * 1000
    const val ROW_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

    suspend fun run(dao: JournalDao, now: Long) {
        dao.purgeBodies(now - BODY_TTL_MS)
        dao.deleteOlderThan(now - ROW_TTL_MS)
    }
}
