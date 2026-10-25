package com.jassam.smsgate.work

/** Message ids are UUIDs (the backend's `uuid.UUID`); anything else from a push or input is ignored. */
object MessageIds {
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** The canonical (lower-case) id, or null when [raw] is not a UUID. */
    fun normalize(raw: String?): String? {
        val id = raw?.trim()?.lowercase() ?: return null
        return id.takeIf { it.length == 36 && UUID.matches(it) }
    }
}
