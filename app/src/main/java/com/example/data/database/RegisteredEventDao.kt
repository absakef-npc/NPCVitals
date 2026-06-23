package com.example.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RegisteredEventDao {
    @Query("SELECT * FROM registered_events ORDER BY createdAt DESC")
    fun getAllEvents(): Flow<List<RegisteredEvent>>

    @Query("SELECT * FROM registered_events WHERE eventType = :type ORDER BY createdAt DESC")
    fun getEventsByType(type: String): Flow<List<RegisteredEvent>>

    @Query("SELECT * FROM registered_events WHERE isSynced = 0")
    suspend fun getUnsyncedEvents(): List<RegisteredEvent>

    @Query("SELECT * FROM registered_events WHERE id = :id LIMIT 1")
    suspend fun getEventById(id: String): RegisteredEvent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: RegisteredEvent)

    @Update
    suspend fun updateEvent(event: RegisteredEvent)

    @Query("UPDATE registered_events SET isSynced = :isSynced, status = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, isSynced: Boolean, status: String)

    @Delete
    suspend fun deleteEvent(event: RegisteredEvent)

    @Query("DELETE FROM registered_events")
    suspend fun clearAll()
}
