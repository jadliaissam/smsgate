package com.jassam.smsgate.journal

import androidx.room.TypeConverter
import com.jassam.smsgate.api.Status

/** Room converters: the reported-status set is stored as comma-separated wire names. */
class JournalConverters {
    @TypeConverter
    fun fromStatuses(statuses: Set<Status>): String =
        statuses.sortedBy { it.ordinal }.joinToString(",") { it.wire }

    @TypeConverter
    fun toStatuses(raw: String): Set<Status> {
        if (raw.isEmpty()) return emptySet()
        val tokens = raw.split(',')
        return Status.entries.filterTo(LinkedHashSet()) { it.wire in tokens }
    }
}
