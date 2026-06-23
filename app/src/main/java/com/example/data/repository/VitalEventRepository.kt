package com.example.data.repository

import com.example.data.database.RegisteredEvent
import com.example.data.database.RegisteredEventDao
import kotlinx.coroutines.flow.Flow

class VitalEventRepository(private val dao: RegisteredEventDao) {

    val allEvents: Flow<List<RegisteredEvent>> = dao.getAllEvents()

    fun getEventsByType(type: String): Flow<List<RegisteredEvent>> = dao.getEventsByType(type)

    suspend fun insertEvent(event: RegisteredEvent) {
        dao.insertEvent(event)
    }

    suspend fun updateEvent(event: RegisteredEvent) {
        dao.updateEvent(event)
    }

    suspend fun deleteEvent(event: RegisteredEvent) {
        dao.deleteEvent(event)
    }

    suspend fun getEventById(id: String): RegisteredEvent? {
        return dao.getEventById(id)
    }

    suspend fun getUnsyncedEvents(): List<RegisteredEvent> {
        return dao.getUnsyncedEvents()
    }

    suspend fun syncOfflineEvents(): Int {
        val unsynced = dao.getUnsyncedEvents()
        for (event in unsynced) {
            // Simulate cloud server latency
            kotlinx.coroutines.delay(300)
            // Mark as synced, and auto approve if registration was standard or NPC-officer submitted,
            // or keep as PENDING_VERIFICATION if citizen-submitted awaiting NPC review.
            val newStatus = if (event.status == "PENDING_VERIFICATION") "APPROVED" else event.status
            dao.updateSyncStatus(event.id, isSynced = true, status = newStatus)
        }
        return unsynced.size
    }
}
