package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "registered_events")
data class RegisteredEvent(
    @PrimaryKey val id: String, // UUID string for offline compatibility
    val eventType: String,      // "BIRTH", "DEATH", "MARRIAGE"
    val primaryName: String,    // Child name, Deceased name, Husband name
    val secondaryName: String,  // Mother/Wife name
    val dateOfEvent: String,
    val locationOfEvent: String,
    val status: String,         // "PENDING_VERIFICATION", "APPROVED"
    val isSynced: Boolean,      // offline first status
    val createdAt: Long,
    val qrCodeData: String,
    val formDataJson: String,   // full dynamic payload
    val gpsLat: Double,
    val gpsLng: Double
)
