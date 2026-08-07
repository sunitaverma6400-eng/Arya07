package com.arya.ai.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A single Notebook entry — see [NoteDao] and [com.arya.ai.ui.NotebookScreen]. Added for the
 *  "+" attach menu's Notebook tile (see FIXES_LOG.md Phase 26) — plain local notes, no sync,
 *  no AI involved, just Room-backed CRUD. */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
