package com.arya.ai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ChatSessionEntity::class, ChatMessageEntity::class, NoteEntity::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "arya.db"
                )
                    // Bumped 1 -> 2 for NoteEntity (Notebook feature, FIXES_LOG.md Phase 26).
                    // No prior release depends on preserving old data across this bump yet, so
                    // destructive fallback is fine here rather than writing a real Migration.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
