package com.jassam.smsgate.journal

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The local message journal (schema exported to android/app/schemas/). Building it
 * opens nothing: the file is opened on the first query, which Room runs off the
 * main thread (suspend DAO). Main-thread queries are never allowed.
 */
@Database(entities = [JournalEntry::class], version = 1, exportSchema = true)
@TypeConverters(JournalConverters::class)
abstract class JournalDb : RoomDatabase() {
    abstract fun journalDao(): JournalDao

    companion object {
        const val NAME = "journal.db"

        fun build(context: Context): JournalDb =
            Room.databaseBuilder(context.applicationContext, JournalDb::class.java, NAME).build()
    }
}
