package com.rayneo.visionclaw.core.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ChatMessageDao {

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC, id ASC")
    suspend fun getAllMessages(): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAllMessages()

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun count(): Int

    @Query(
        """
        DELETE FROM chat_messages
        WHERE id IN (
            SELECT id FROM chat_messages
            ORDER BY timestamp ASC, id ASC
            LIMIT :limit
        )
        """
    )
    suspend fun deleteOldest(limit: Int)

    @Transaction
    suspend fun insertAndTrim(message: ChatMessageEntity, maxItems: Int) {
        insert(message)
        val total = count()
        val overflow = total - maxItems
        if (overflow > 0) {
            deleteOldest(overflow)
        }
    }
}
