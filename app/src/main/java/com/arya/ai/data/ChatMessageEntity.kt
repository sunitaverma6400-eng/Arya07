package com.arya.ai.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val text: String,
    val isFromUser: Boolean,
    val imagePath: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
