package com.arya.ai.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val modelPath: String,
    val modelName: String,
    val createdAt: Long = System.currentTimeMillis()
)
