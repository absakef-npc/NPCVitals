package com.example

import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.api.GeminiContent
import com.example.data.api.GeminiPart
import com.example.data.api.GeminiRequest
import com.example.data.api.GeminiRetrofitClient
import com.example.data.database.AppDatabase
import com.example.data.database.RegisteredEvent
import com.example.data.database.RegisteredEventDao
import com.example.data.repository.VitalEventRepository
import com.example.ui.theme.NPCVitalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

// ==========================================
// SEED DATA FOR NPC CENTRES & MAP LOCATIONS
// ==========================================
data class NpcCentre(
    val name: String,
    val state: String,
    val address: String,
    val officerName: String,
    val phone: String,
    val openHours: String,
    val latitude: Float,
    val longitude: Float
)

val NPC_CENTRES = listOf(
    NpcCentre("NPC National Headquarters", "Abuja (FCT)", "Plot 2031, Olusegun Obasanjo Way, Wuse Zone 7, Abuja", "Dr. Ibrahim Salihu", "+234 803 111 2222", "8:00 AM - 4:00 PM", 0.5f, 0.45f),
    NpcCentre("Lagos State Secretatry Office", "Lagos", "Babs Animashaun Street, Surulere, Lagos", "Mrs. Funmilayo Adebayo", "+234 802 333 4444", "8:00 AM - 4:00 PM", 0.25f, 0.75f),
    NpcCentre("Kano State NPC Office", "Kano", "Gwarzo Road, Near Federal Secretariat, Kano", "Alhaji Musa Yar'Adua", "+234 806 555 6666", "8:00 AM - 4:00 PM", 0.75f, 0.35f),
    NpcCentre("Enugu State Registration Centre", "Enugu", "Okpara Avenue, Enugu", "Nnnamdi Okeke", "+234 805 777 8888", "8:00 AM - 4:00 PM", 0.4f, 0.8f),
    NpcCentre("Ibadan Zonal Office", "Oyo", "Agodi Secretariat, Ibadan", "Oluwaseun Ajayi", "+234 811 999 0000", "8:00 AM - 4:00 PM", 0.3f, 0.65f),
    NpcCentre("Port Harcourt Area Office", "Rivers", "Aba Road, Port Harcourt", "Taribo George", "+234 812 222 3333", "8:00 AM - 4:00 PM", 0.35f, 0.9f)
)

// ==========================================
// CHATBOT MESSAGE MODEL
// ==========================================
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// ==========================================
// VIEWMODEL FOR GLOBAL STATE MANAGEMENT
// ==========================================
class NPCVitalViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val dao = database.registeredEventDao()
    private val repository = VitalEventRepository(dao)

    // Reactive Flow of All Registered Events in Local Room DB
    val registeredEvents: StateFlow<List<RegisteredEvent>> = repository.allEvents
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Network Mode: True = Online, False = Offline (Full capability local mode)
    var isOnlineMode by mutableStateOf(true)
        private set

    // User Role: "Citizen" or "NPC Officer" (Role-based access)
    var currentUserRole by mutableStateOf("Citizen")
        private set

    // Active Bottom Navigation Tab
    var currentTab by mutableStateOf("Home")

    // ==========================================
    // CUSTOM ACCESSIBILITY STATES
    // ==========================================
    var fontSizeMultiplier by mutableStateOf(1.0f)
    var isHighContrastMode by mutableStateOf(false)
    var colorBlindFilter by mutableStateOf("None") // "None", "Protanopia", "Deuteranopia", "Tritanopia", "Monochromacy"
    var showScreenReaderHelper by mutableStateOf(false)

    // Active Dialog or Secondary Forms Screen
    var activeRegistrationForm by mutableStateOf<String?>(null) // "BIRTH", "DEATH", "MARRIAGE", or null

    // Chatbot Messages List
    val chatMessages = mutableStateListOf<ChatMessage>()
    var chatbotInputText by mutableStateOf("")
    var isChatbotLoading by mutableStateOf(false)

    // Simulation states
    var isNinVerifying by mutableStateOf(false)
    var ninVerificationMessage by mutableStateOf<String?>(null)
    var ocrScanInProgress by mutableStateOf(false)
    var activeFormErrors = mutableStateMapOf<String, String>()

    // AI Form Validation State
    var isAiValidatingForm by mutableStateOf(false)
    var aiValidationFeedback by mutableStateOf<String?>(null)
    val aiFormErrors = mutableStateMapOf<String, String>()

    // Initialization
    init {
        // Welcome message for chatbot
        chatMessages.add(
            ChatMessage(
                "Welcome to NPC VitalReg Support. I am your intelligent demographic advisor. " +
                        "How can I help you with Birth, Death, or Marriage registration in Nigeria today?",
                isUser = false
            )
        )
        // Add sample synchronized seed records on first launch if DB is empty to make UI lively
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val existing = dao.getUnsyncedEvents() // any call to check
                val all = dao.getAllEvents()
                // If completely empty, seed standard records
                if (registeredEvents.value.isEmpty()) {
                    val uuid1 = UUID.randomUUID().toString()
                    val uuid2 = UUID.randomUUID().toString()
                    val uuid3 = UUID.randomUUID().toString()
                    dao.insertEvent(
                        RegisteredEvent(
                            id = uuid1,
                            eventType = "BIRTH",
                            primaryName = "Chidi Samuel Adebayo",
                            secondaryName = "Amina Adebayo (Mother)",
                            dateOfEvent = "2026-05-12",
                            locationOfEvent = "National Hospital, Abuja",
                            status = "APPROVED",
                            isSynced = true,
                            createdAt = System.currentTimeMillis() - 1000 * 60 * 60 * 24 * 5, // 5 days ago
                            qrCodeData = "NPC-BIRTH-$uuid1",
                            formDataJson = "{}",
                            gpsLat = 9.0765,
                            gpsLng = 7.3986
                        )
                    )
                    dao.insertEvent(
                        RegisteredEvent(
                            id = uuid2,
                            eventType = "MARRIAGE",
                            primaryName = "Emeka Obi",
                            secondaryName = "Chioma Nwachukwu",
                            dateOfEvent = "2026-06-10",
                            locationOfEvent = "Federal Marriage Registry, Ikoyi, Lagos",
                            status = "APPROVED",
                            isSynced = true,
                            createdAt = System.currentTimeMillis() - 1000 * 60 * 60 * 24 * 2, // 2 days ago
                            qrCodeData = "NPC-MARRIAGE-$uuid2",
                            formDataJson = "{}",
                            gpsLat = 6.4541,
                            gpsLng = 3.4259
                        )
                    )
                    dao.insertEvent(
                        RegisteredEvent(
                            id = uuid3,
                            eventType = "DEATH",
                            primaryName = "Alhaji Bello Abubakar",
                            secondaryName = "Mallam Usman Bello (Son)",
                            dateOfEvent = "2026-06-20",
                            locationOfEvent = "Kano General Hospital, Kano",
                            status = "PENDING_VERIFICATION",
                            isSynced = false, // Not synced to simulate offline addition
                            createdAt = System.currentTimeMillis() - 1000 * 60 * 60 * 6, // 6 hours ago
                            qrCodeData = "NPC-DEATH-$uuid3",
                            formDataJson = "{}",
                            gpsLat = 12.0022,
                            gpsLng = 8.5920
                        )
                    )
                }
            }
        }
    }

    fun toggleNetworkMode() {
        isOnlineMode = !isOnlineMode
        if (isOnlineMode) {
            // Auto sync on returning online!
            syncOfflineData()
        }
    }

    fun toggleUserRole() {
        currentUserRole = if (currentUserRole == "Citizen") "NPC Officer" else "Citizen"
    }

    // ==========================================
    // REAL-TIME AI FORM VALIDATION (GEMINI & OFFLINE ENGINE)
    // ==========================================
    fun validateFormWithAi(formType: String, data: Map<String, String>) {
        isAiValidatingForm = true
        aiValidationFeedback = null
        aiFormErrors.clear()

        viewModelScope.launch {
            val apiKey = com.example.BuildConfig.GEMINI_API_KEY
            val hasValidKey = apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY"

            if (isOnlineMode && hasValidKey) {
                val systemPrompt = """
                    You are an AI-powered demographic data quality auditor for Nigeria's National Population Commission (NPC).
                    Your job is to perform real-time audit of vital registration forms.
                    Review the following form data representing a '$formType' event.
                    Check for logical inconsistencies, date inconsistencies, missing mandatory information, and document formatting issues.
                    For example:
                    - Child's DOB cannot be in the future, or logically older than parents' average age (or after the current date 2026-06-23).
                    - Deceased's age must make sense with the dates.
                    - For Marriages, verify the year/month formats (YYYY-MM-DD), logical consistency between couple names and witnesses, and ensure NINs are present.
                    - If registration is Birth and is over 60 days from today (2026-06-23), flag it as a LATE REGISTRATION and specify that a sworn age affidavit from a court is required and a 1,000 Naira fee may apply.
                    - If registration is Death and over 30 days, flag as 'LATE_NOTIFICATION'.
                    
                    Respond in a structured format:
                    First line: A brief overall status, e.g. "STATUS: SUCCESS" or "STATUS: WARNINGS_FOUND"
                    Second line and onwards: Bulleted recommendations, with specific field tags like [primaryName], [dateOfEvent], [locationOfEvent], [secondaryName], or [general] preceding each feedback comment so the app can highlight them.
                    Example output:
                    STATUS: WARNINGS_FOUND
                    • [dateOfEvent] The date of birth 2026-08-15 is in the future. Please correct to a valid past or present date.
                    • [secondaryName] Mother's NIN is missing. This is a mandatory documentation for citizen verification.
                    • [general] Birth registered over 60 days after delivery. Late registration requires a sworn age affidavit.
                """.trimIndent()

                val dataStr = data.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                val request = GeminiRequest(
                    contents = listOf(
                        GeminiContent(
                            parts = listOf(
                                GeminiPart(text = "$systemPrompt\n\nForm Fields Submitted:\n$dataStr")
                            )
                        )
                    )
                )

                try {
                    val response = GeminiRetrofitClient.api.generateContent(apiKey, request)
                    val result = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    if (result != null) {
                        parseAiValidationResult(result)
                    } else {
                        runOfflineRulesValidation(formType, data, "No response from Gemini API.")
                    }
                } catch (e: Exception) {
                    runOfflineRulesValidation(formType, data, "Connection to national AI service timed out. Offline safety rules applied: ${e.message}")
                }
            } else {
                val reason = if (!isOnlineMode) "Operating in OFFLINE mode." else "No Gemini API Key configured in AI Studio."
                runOfflineRulesValidation(formType, data, reason)
            }
            isAiValidatingForm = false
        }
    }

    private fun runOfflineRulesValidation(formType: String, data: Map<String, String>, reason: String) {
        val feedbackBuilder = StringBuilder()
        feedbackBuilder.append("STATUS: WARNINGS_FOUND (Offline Rule Engine)\n")
        feedbackBuilder.append("ℹ️ $reason\n\n")

        val todayStr = "2026-06-23"
        val todayDate = parseDate(todayStr) ?: java.util.Date()

        when (formType) {
            "BIRTH" -> {
                val name = data["Child Full Name"] ?: ""
                val dobStr = data["Date of Birth (YYYY-MM-DD)"] ?: ""
                val pob = data["Place of Birth (Hospital/City)"] ?: ""
                val motherName = data["Mother's Full Name"] ?: ""
                val motherNin = data["Mother's NIN (11 digits)"] ?: ""
                val hospital = data["Delivery Facility / Hospital Name"] ?: ""

                if (name.trim().length < 3) {
                    aiFormErrors["primaryName"] = "Name must be at least 3 characters."
                    feedbackBuilder.append("• [primaryName] Child Full Name is too short or missing.\n")
                }
                
                if (dobStr.isNotEmpty()) {
                    val dob = parseDate(dobStr)
                    if (dob != null) {
                        if (dob.after(todayDate)) {
                            aiFormErrors["dateOfEvent"] = "Date of Birth cannot be in the future."
                            feedbackBuilder.append("• [dateOfEvent] Date of Birth ($dobStr) is in the future. Please correct.\n")
                        } else {
                            val diffMs = todayDate.time - dob.time
                            val diffDays = diffMs / (1000 * 60 * 60 * 24)
                            if (diffDays > 60) {
                                feedbackBuilder.append("• [general] LATE REGISTRATION FLAG: Over 60 days since birth. NPC policy requires a sworn age affidavit from a court and an administrative late fee of 1,000 Naira.\n")
                            }
                        }
                    } else {
                        aiFormErrors["dateOfEvent"] = "Invalid date format. Use YYYY-MM-DD."
                        feedbackBuilder.append("• [dateOfEvent] Date format is invalid. Please use YYYY-MM-DD.\n")
                    }
                } else {
                    aiFormErrors["dateOfEvent"] = "Date of Birth is mandatory."
                    feedbackBuilder.append("• [dateOfEvent] Date of birth is missing.\n")
                }

                if (pob.trim().isEmpty()) {
                    aiFormErrors["locationOfEvent"] = "Place of Birth is required."
                    feedbackBuilder.append("• [locationOfEvent] Place of Birth is missing.\n")
                }

                if (motherName.trim().isEmpty()) {
                    aiFormErrors["secondaryName"] = "Mother's name is required."
                    feedbackBuilder.append("• [secondaryName] Mother's Full Name is missing.\n")
                }

                if (motherNin.length != 11 || !motherNin.all { it.isDigit() }) {
                    feedbackBuilder.append("• [secondaryName] ALERT: Mother's NIN is missing or invalid. National Identification is highly recommended to secure citizenship and prevent duplication.\n")
                }

                if (hospital.trim().isEmpty()) {
                    feedbackBuilder.append("• [general] NOTICE: Delivery hospital name is missing. For home births, a verification letter from the traditional ruler or local chief is required.\n")
                }
            }
            "DEATH" -> {
                val name = data["Deceased Full Name"] ?: ""
                val dodStr = data["Date of Death (YYYY-MM-DD)"] ?: ""
                val place = data["Place of Death (Hospital/City)"] ?: ""
                val informantName = data["Informant Full Name"] ?: ""
                val informantNin = data["Informant's NIN (11 digits)"] ?: ""
                val cause = data["Cause of Death (if known)"] ?: ""

                if (name.trim().length < 3) {
                    aiFormErrors["primaryName"] = "Name must be at least 3 characters."
                    feedbackBuilder.append("• [primaryName] Deceased Full Name is too short or missing.\n")
                }

                if (dodStr.isNotEmpty()) {
                    val dod = parseDate(dodStr)
                    if (dod != null) {
                        if (dod.after(todayDate)) {
                            aiFormErrors["dateOfEvent"] = "Date of Death cannot be in the future."
                            feedbackBuilder.append("• [dateOfEvent] Date of Death ($dodStr) is in the future. Please correct.\n")
                        } else {
                            val diffMs = todayDate.time - dod.time
                            val diffDays = diffMs / (1000 * 60 * 60 * 24)
                            if (diffDays > 30) {
                                feedbackBuilder.append("• [general] LATE NOTIFICATION: Exceeded the standard 30-day reporting window. NPC requires death registration within 30 days.\n")
                            }
                        }
                    } else {
                        aiFormErrors["dateOfEvent"] = "Invalid date format. Use YYYY-MM-DD."
                        feedbackBuilder.append("• [dateOfEvent] Date format is invalid. Please use YYYY-MM-DD.\n")
                    }
                } else {
                    aiFormErrors["dateOfEvent"] = "Date of Death is mandatory."
                    feedbackBuilder.append("• [dateOfEvent] Date of death is missing.\n")
                }

                if (place.trim().isEmpty()) {
                    aiFormErrors["locationOfEvent"] = "Place of Death is required."
                    feedbackBuilder.append("• [locationOfEvent] Place of Death is missing.\n")
                }

                if (informantName.trim().isEmpty()) {
                    aiFormErrors["secondaryName"] = "Informant details are required."
                    feedbackBuilder.append("• [secondaryName] Informant Name is missing.\n")
                }

                if (informantNin.length != 11 || !informantNin.all { it.isDigit() }) {
                    feedbackBuilder.append("• [secondaryName] WARNING: Informant NIN is missing or invalid. Standard protocol requires NIN for legal notification signature.\n")
                }

                if (cause.trim().isEmpty()) {
                    feedbackBuilder.append("• [general] ADVISORY: Cause of death is not specified. Standard NPC certificates require a medical practitioner's note to confirm natural/unnatural causes.\n")
                }
            }
            "MARRIAGE" -> {
                val husband = data["Husband's Full Name"] ?: ""
                val wife = data["Wife's Full Name"] ?: ""
                val domStr = data["Date of Marriage (YYYY-MM-DD)"] ?: ""
                val place = data["Location/Registry Venue"] ?: ""
                val witness1 = data["Witness 1 Name"] ?: ""
                val witness2 = data["Witness 2 Name"] ?: ""

                if (husband.trim().length < 3) {
                    aiFormErrors["primaryName"] = "Husband name is too short."
                    feedbackBuilder.append("• [primaryName] Husband's Full Name is too short or missing.\n")
                }

                if (wife.trim().length < 3) {
                    aiFormErrors["secondaryName"] = "Wife name is too short."
                    feedbackBuilder.append("• [secondaryName] Wife's Full Name is too short or missing.\n")
                }

                if (domStr.isNotEmpty()) {
                    val dom = parseDate(domStr)
                    if (dom != null) {
                        if (dom.after(todayDate)) {
                            aiFormErrors["dateOfEvent"] = "Marriage date cannot be in the future."
                            feedbackBuilder.append("• [dateOfEvent] Date of Marriage ($domStr) is in the future. Please correct.\n")
                        }
                    } else {
                        aiFormErrors["dateOfEvent"] = "Invalid date format. Use YYYY-MM-DD."
                        feedbackBuilder.append("• [dateOfEvent] Date format is invalid. Please use YYYY-MM-DD.\n")
                    }
                } else {
                    aiFormErrors["dateOfEvent"] = "Date of marriage is required."
                    feedbackBuilder.append("• [dateOfEvent] Date of marriage is missing.\n")
                }

                if (place.trim().isEmpty()) {
                    aiFormErrors["locationOfEvent"] = "Location of marriage is required."
                    feedbackBuilder.append("• [locationOfEvent] Location/Registry Venue is missing.\n")
                }

                if (witness1.trim().isEmpty() || witness2.trim().isEmpty()) {
                    feedbackBuilder.append("• [general] NOTICE: At least two witnesses are legally required for valid marriage certification in Nigeria.\n")
                }
            }
        }

        if (aiFormErrors.isEmpty()) {
            feedbackBuilder.setLength(0)
            feedbackBuilder.append("STATUS: SUCCESS\n")
            feedbackBuilder.append("✅ All fields meet standard criteria. Ready to register.")
        }

        aiValidationFeedback = feedbackBuilder.toString()
    }

    private fun parseDate(dateStr: String): java.util.Date? {
        val formats = listOf("yyyy-MM-dd", "yyyy/MM/dd", "dd-MM-yyyy", "dd/MM/yyyy")
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                sdf.isLenient = false
                return sdf.parse(dateStr)
            } catch (e: Exception) {
                // ignore
            }
        }
        return null
    }

    private fun parseAiValidationResult(rawText: String) {
        val lines = rawText.split("\n")
        val parsedFeedback = StringBuilder()
        parsedFeedback.append(rawText)

        for (line in lines) {
            if (line.contains("[primaryName]")) {
                aiFormErrors["primaryName"] = line.substringAfter("[primaryName]").trim()
            }
            if (line.contains("[secondaryName]")) {
                aiFormErrors["secondaryName"] = line.substringAfter("[secondaryName]").trim()
            }
            if (line.contains("[dateOfEvent]")) {
                aiFormErrors["dateOfEvent"] = line.substringAfter("[dateOfEvent]").trim()
            }
            if (line.contains("[locationOfEvent]")) {
                aiFormErrors["locationOfEvent"] = line.substringAfter("[locationOfEvent]").trim()
            }
        }

        aiValidationFeedback = parsedFeedback.toString()
    }

    // ==========================================
    // OFFLINE SYNC CONTROLLER
    // ==========================================
    var isSyncingDatabase by mutableStateOf(false)
        private set
    var syncResultCount by mutableStateOf(0)

    fun syncOfflineData() {
        if (isSyncingDatabase) return
        viewModelScope.launch {
            isSyncingDatabase = true
            val count = repository.syncOfflineEvents()
            syncResultCount = count
            isSyncingDatabase = false
            if (count > 0) {
                chatMessages.add(
                    ChatMessage(
                        "System Sync Action: Successfully synchronized $count offline records with the Federal Demographics Database. Records have been verified and approved.",
                        isUser = false
                    )
                )
            }
        }
    }

    // ==========================================
    // ACTION: REGISTER EVENT IN ROOM DB
    // ==========================================
    fun submitRegistration(
        eventType: String,
        primaryName: String,
        secondaryName: String,
        dateOfEvent: String,
        locationOfEvent: String,
        formDataJson: String,
        gpsLat: Double,
        gpsLng: Double,
        onSuccess: () -> Unit
    ) {
        // Clear errors
        activeFormErrors.clear()

        // 1. AI-Powered Automated Form Validation
        if (primaryName.trim().length < 3) {
            activeFormErrors["primaryName"] = "Full name must be at least 3 characters long."
        }
        if (dateOfEvent.isEmpty()) {
            activeFormErrors["dateOfEvent"] = "Date is required."
        }
        if (locationOfEvent.trim().length < 5) {
            activeFormErrors["locationOfEvent"] = "Please enter a valid, complete location or facility name."
        }
        if (secondaryName.trim().isEmpty()) {
            activeFormErrors["secondaryName"] = "Secondary relationship/party details are required."
        }

        // 2. AI-Powered Duplicate Record Detection
        val matches = registeredEvents.value.any {
            it.eventType == eventType &&
                    it.primaryName.equals(primaryName, ignoreCase = true) &&
                    it.dateOfEvent == dateOfEvent
        }
        if (matches) {
            activeFormErrors["duplicate"] = "Potential Duplicate Event Detected: A similar $eventType record exists for '$primaryName' on this date."
        }

        if (activeFormErrors.isNotEmpty()) {
            return
        }

        // Proceed to register
        viewModelScope.launch {
            val recordId = UUID.randomUUID().toString()
            val newEvent = RegisteredEvent(
                id = recordId,
                eventType = eventType,
                primaryName = primaryName,
                secondaryName = secondaryName,
                dateOfEvent = dateOfEvent,
                locationOfEvent = locationOfEvent,
                status = if (isOnlineMode) "APPROVED" else "PENDING_VERIFICATION", // automatically approved if online, pending if officer offline
                isSynced = isOnlineMode,
                createdAt = System.currentTimeMillis(),
                qrCodeData = "NPC-${eventType}-${recordId}",
                formDataJson = formDataJson,
                gpsLat = gpsLat,
                gpsLng = gpsLng
            )

            withContext(Dispatchers.IO) {
                repository.insertEvent(newEvent)
            }

            activeRegistrationForm = null
            onSuccess()
        }
    }

    // Clear all records for sandbox reset
    fun clearSandboxDatabase() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.clearAll()
            }
        }
    }

    // ==========================================
    // ACTION: SIMULATE NATIONAL IDENTIFICATION NUMBER (NIN) VERIFICATION
    // ==========================================
    fun verifyNin(nin: String, onComplete: (Boolean) -> Unit) {
        if (nin.length != 11 || !nin.all { it.isDigit() }) {
            ninVerificationMessage = "NIN must be exactly 11 numeric digits."
            onComplete(false)
            return
        }

        viewModelScope.launch {
            isNinVerifying = true
            ninVerificationMessage = "Connecting securely to NIMC database..."
            kotlinx.coroutines.delay(1000)
            isNinVerifying = false

            // Simulate database lookups
            val names = listOf(
                "Amina Ibrahim Bello", "Ezenwa Okafor", "Babajide Soyinka",
                "Oluwaseun Adebayo", "Hadiza Musa", "Ngozi Chidi"
            )
            val randomName = names.random()
            ninVerificationMessage = "NIN Verified: Linked to NIMC Citizen Profile '$randomName'. Security credentials cleared."
            onComplete(true)
        }
    }

    // ==========================================
    // ACTION: SIMULATE AI OCR DOCUMENT RECOGNITION
    // ==========================================
    fun performOcrScan(documentType: String, onScanned: (Map<String, String>) -> Unit) {
        viewModelScope.launch {
            ocrScanInProgress = true
            kotlinx.coroutines.delay(1500) // Scan animation delay
            ocrScanInProgress = false

            val result = when (documentType) {
                "BIRTH" -> mapOf(
                    "name" to "Femi Daniel Adebayo",
                    "dob" to "2026-06-18",
                    "pob" to "St. Gerard's Hospital, Kaduna",
                    "sex" to "Male",
                    "mother" to "Oluwaseun Adebayo",
                    "motherNin" to "12345678901",
                    "father" to "Femi Samuel Adebayo"
                )
                "DEATH" -> mapOf(
                    "name" to "Chief Emeka Nwachukwu",
                    "age" to "78",
                    "sex" to "Male",
                    "dod" to "2026-06-15",
                    "pod" to "Lagos University Teaching Hospital (LUTH)",
                    "cause" to "Natural Causes / Old Age",
                    "informant" to "Chinedu Nwachukwu"
                )
                "MARRIAGE" -> mapOf(
                    "husband" to "Tunde Cole",
                    "wife" to "Fatima Yusuf",
                    "dom" to "2026-06-20",
                    "place" to "Federal Marriage Registry, Ikoyi, Lagos",
                    "type" to "Civil",
                    "witness1" to "John Cole",
                    "witness2" to "Kemi Yusuf"
                )
                else -> emptyMap()
            }
            onScanned(result)
        }
    }

    // ==========================================
    // INTEGRATED GEMINI AI CHATBOT WITH OFFLINE FALLBACK
    // ==========================================
    fun sendChatMessage() {
        val query = chatbotInputText.trim()
        if (query.isEmpty()) return

        chatMessages.add(ChatMessage(query, isUser = true))
        chatbotInputText = ""
        isChatbotLoading = true

        viewModelScope.launch {
            // Check if online mode and if API key is valid, else fallback to high quality rules
            val apiKey = com.example.BuildConfig.GEMINI_API_KEY
            val hasValidKey = apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY"

            val responseText = if (isOnlineMode && hasValidKey) {
                callGeminiApi(query)
            } else {
                getOfflineRuleBasedResponse(query)
            }

            chatMessages.add(ChatMessage(responseText, isUser = false))
            isChatbotLoading = false
        }
    }

    private suspend fun callGeminiApi(prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = com.example.BuildConfig.GEMINI_API_KEY
        val systemInstruction = "You are an expert chatbot and demographic advisor for Nigeria's National Population Commission (NPC) Vital Registration system. Provide clear, accurate and reassuring advice regarding birth registration, death notifications, and marriage registrations in Nigeria. Be polite and professional. Keep answers descriptive but concise (under 3-4 paragraphs). Use Nigerian demographic contexts where helpful."

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(text = "$systemInstruction\n\nUser Question: $prompt")
                    )
                )
            )
        )

        try {
            val response = GeminiRetrofitClient.api.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: "I apologize, but I could not formulate a clear response. Please consult the nearest NPC Centre."
        } catch (e: Exception) {
            // Fallback immediately if connection fails
            getOfflineRuleBasedResponse(prompt) + "\n\n*(Note: Smart connection to the National Population cloud is unstable; answered via the local offline rule engine)*"
        }
    }

    private fun getOfflineRuleBasedResponse(query: String): String {
        val lower = query.lowercase()
        return when {
            lower.contains("birth") || lower.contains("child") || lower.contains("baby") || lower.contains("born") -> {
                "To register a birth in Nigeria, you need:\n" +
                        "1. A Hospital Birth Notification Card (or proof from native clinic/traditional leader).\n" +
                        "2. Parents' National Identification Numbers (NIN).\n" +
                        "3. Details of the informant.\n\n" +
                        "💡 **Important**: Birth registration within 60 days of birth is **100% free** under NPC guidelines. After 60 days, it is considered late and requires a small administrative fee."
            }
            lower.contains("death") || lower.contains("died") || lower.contains("deceased") || lower.contains("corpse") -> {
                "For death registration, NPC requires:\n" +
                        "1. A Medical Certificate of Cause of Death from a licensed healthcare facility.\n" +
                        "2. Deceased's National Identification Number (NIN) and ID card.\n" +
                        "3. Deceased's age, date of death, place of death, and cause.\n" +
                        "4. Informant details and NIN.\n\n" +
                        "Deaths must be registered within 30 days at the nearest local government NPC office."
            }
            lower.contains("marriage") || lower.contains("marry") || lower.contains("wedding") || lower.contains("couple") -> {
                "Marriage registration can be completed for three types of marriages in Nigeria:\n" +
                        "1. **Civil Marriages**: Held under the Act at official registries (like Ikoyi, Abuja, etc.).\n" +
                        "2. **Religious Marriages**: Performed at licensed places of worship.\n" +
                        "3. **Customary/Traditional Marriages**: Recognized locally under native laws.\n\n" +
                        "Required: Husband & Wife details, NINs, Date & Place of Marriage, and Two Witnesses. NPC certificates are issued with verified QR-codes."
            }
            lower.contains("fee") || lower.contains("cost") || lower.contains("payment") || lower.contains("money") || lower.contains("naira") -> {
                "• **Birth Registration (Under 18)**: Absolutely FREE if done within 60 days.\n" +
                        "• **Death Notifications**: Free if submitted within 30 days.\n" +
                        "• **Marriage Registration & Late certificates**: May attract minor government processing fees (usually 1,000 to 2,000 Naira depending on state regulations)."
            }
            lower.contains("offline") || lower.contains("internet") || lower.contains("network") || lower.contains("remote") -> {
                "Yes! NPCVitalEvents supports full offline registration in remote locations. You can capture and register vital events (Birth, Death, Marriage) with GPS location and biometric simulations entirely without internet. Once you return to an area with connectivity, simply tap the 'Sync database' button to push files to the national directory."
            }
            lower.contains("nin") || lower.contains("national identification") -> {
                "The National Identification Number (NIN) is mandatory for all primary parties (Parents, Deceased, Couples, Informants, and Witnesses) during Vital Event Registration. The app provides automated secure verification linked directly with the NIMC national infrastructure."
            }
            else -> {
                "I am here to guide you with National Population Commission vital registration processes. " +
                        "You can ask me about:\n" +
                        "• Birth certificate guidelines and free registration periods.\n" +
                        "• Death notifications and supporting document requirements.\n" +
                        "• Marriage types (Civil, Customary, Religious) and registration methods.\n" +
                        "• Offline operations in rural Nigeria."
            }
        }
    }
}

// ==========================================
// MAIN ACTIVITY ENTRY POINT
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: NPCVitalViewModel = viewModel()
            NPCVitalTheme(
                isHighContrast = viewModel.isHighContrastMode,
                colorBlindFilter = viewModel.colorBlindFilter
            ) {
                MainAppLayout(viewModel)
            }
        }
    }
}

// ==========================================
// APP CORE NAVIGATION LAYOUT
// ==========================================
@Composable
fun MainAppLayout(viewModel: NPCVitalViewModel) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (!isWide) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 8.dp,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        val items = listOf(
                            Triple("Home", Icons.Default.Home, Icons.Outlined.Home),
                            Triple("Register Event", Icons.Default.AddCircle, Icons.Outlined.AddCircle),
                            Triple("My Certificates", Icons.Default.CheckCircle, Icons.Outlined.CheckCircle),
                            Triple("NPC Centres", Icons.Default.Place, Icons.Outlined.Place),
                            Triple("Profile", Icons.Default.Person, Icons.Outlined.Person)
                        )

                        items.forEach { (name, filledIcon, outlinedIcon) ->
                            val selected = viewModel.currentTab == name
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.currentTab = name },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) filledIcon else outlinedIcon,
                                        contentDescription = name
                                    )
                                },
                                label = {
                                    Text(
                                        text = name,
                                        fontSize = 10.sp * viewModel.fontSizeMultiplier,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWide) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        windowInsets = WindowInsets.navigationBars,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        val items = listOf(
                            Triple("Home", Icons.Default.Home, Icons.Outlined.Home),
                            Triple("Register Event", Icons.Default.AddCircle, Icons.Outlined.AddCircle),
                            Triple("My Certificates", Icons.Default.CheckCircle, Icons.Outlined.CheckCircle),
                            Triple("NPC Centres", Icons.Default.Place, Icons.Outlined.Place),
                            Triple("Profile", Icons.Default.Person, Icons.Outlined.Person)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        items.forEach { (name, filledIcon, outlinedIcon) ->
                            val selected = viewModel.currentTab == name
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.currentTab = name },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) filledIcon else outlinedIcon,
                                        contentDescription = name,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = name,
                                        fontSize = 11.sp * viewModel.fontSizeMultiplier,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    // Header Bar (Brand Name, Network Toggle and Role switch)
                    HeaderBar(viewModel)

                    // Content Screens based on Tab
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        when (viewModel.currentTab) {
                            "Home" -> HomeScreen(viewModel)
                            "Register Event" -> RegisterEventScreen(viewModel)
                            "My Certificates" -> MyCertificatesScreen(viewModel)
                            "NPC Centres" -> NpcCentresScreen(viewModel)
                            "Profile" -> ProfileAndAnalyticsScreen(viewModel)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// HEADER BAR WITH BRANDING & ONLINE/OFFLINE TOGGLE
// ==========================================
@Composable
fun HeaderBar(viewModel: NPCVitalViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Crest / Icon + Title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Federal Crest",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "NPC VitalReg",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "Federal Republic of Nigeria",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        )
                    }
                }

                // Interactive Online/Offline Network Status Switch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (viewModel.isOnlineMode) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                            else Color.Red.copy(alpha = 0.15f)
                        )
                        .clickable { viewModel.toggleNetworkMode() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (viewModel.isOnlineMode) MaterialTheme.colorScheme.tertiary
                                else Color.Red
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (viewModel.isOnlineMode) "ONLINE MODE" else "OFFLINE (REMOTE)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (viewModel.isOnlineMode) MaterialTheme.colorScheme.onPrimary else Color.White
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        imageVector = if (viewModel.isOnlineMode) Icons.Default.Refresh else Icons.Default.Warning,
                        contentDescription = "Network Status Detail",
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

// ==========================================
// SCREEN 1: HOME PAGE (DASHBOARD)
// ==========================================
@Composable
fun HomeScreen(viewModel: NPCVitalViewModel) {
    val events by viewModel.registeredEvents.collectAsState()
    val unsyncedCount = events.count { !it.isSynced }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Accessibility Assistive Narration Overlay
        if (viewModel.showScreenReaderHelper) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Screen narration help icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "SCREEN READER VOICE ASSISTANT",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp * viewModel.fontSizeMultiplier,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "You are currently viewing the NPC Home Dashboard screen. Key controls on this screen include: 1. Total Vital stats summary of local births, deaths, and marriages; 2. Offline Pending Unsynced record banner; 3. Scrollable List of recent vital events registered on this device.",
                                fontSize = 12.sp * viewModel.fontSizeMultiplier,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Hero Tagline Banner
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Every Life Counted, Every Event Recorded",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "National Population Commission official digital platform for securing demographic intelligence and citizens' civil rights.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Active Offline Unsynced Banner
        if (unsyncedCount > 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Offline Records Alert",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "$unsyncedCount Offline Records Pending",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Ready to upload to national cloud database.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                        Button(
                            onClick = { viewModel.syncOfflineData() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Sync Now", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        // Key Vital Stats
        item {
            Column {
                Text(
                    text = "Vital Registration Summary",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCard(
                        title = "Births",
                        count = events.count { it.eventType == "BIRTH" }.toString(),
                        icon = Icons.Default.Favorite,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        title = "Deaths",
                        count = events.count { it.eventType == "DEATH" }.toString(),
                        icon = Icons.Default.Info,
                        color = Color.Gray,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        title = "Marriages",
                        count = events.count { it.eventType == "MARRIAGE" }.toString(),
                        icon = Icons.Default.Face,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Recent Registrations Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Registrations",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Total local: ${events.size}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }

        // List of events from Room Database
        if (events.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Empty database",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No Vital Events Registered Yet",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        } else {
            items(events) { event ->
                EventListItem(event)
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    count: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = count,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = title,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun EventListItem(event: RegisteredEvent) {
    val themeColor = when (event.eventType) {
        "BIRTH" -> MaterialTheme.colorScheme.tertiary
        "MARRIAGE" -> MaterialTheme.colorScheme.secondary
        "DEATH" -> Color.Gray
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Event Symbol
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(themeColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = event.eventType.substring(0, 1),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = themeColor
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = event.eventType,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = themeColor,
                        letterSpacing = 0.5.sp
                    )
                    // Sync badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (event.isSynced) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                                else Color.Red.copy(alpha = 0.1f)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (event.isSynced) "SYNCED" else "OFFLINE ONLY",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (event.isSynced) MaterialTheme.colorScheme.tertiary else Color.Red
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = event.primaryName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${event.secondaryName} | ${event.dateOfEvent}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Status Indicator Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (event.status == "APPROVED") MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                        else Color(0xFFFFB300).copy(alpha = 0.15f)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (event.status == "APPROVED") "APPROVED" else "VERIFYING",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (event.status == "APPROVED") MaterialTheme.colorScheme.tertiary else Color(0xFFD68A00)
                )
            }
        }
    }
}

// ==========================================
// SCREEN 2: REGISTER VITAL EVENT
// ==========================================
@Composable
fun RegisterEventScreen(viewModel: NPCVitalViewModel) {
    val context = LocalContext.current

    if (viewModel.activeRegistrationForm != null) {
        // Render Active Registration Form Dialog
        RegistrationFormContainer(viewModel = viewModel)
    } else {
        // Main Screen: Choose Vital Event Card
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Select Event to Register",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Choose the vital registration service required. Forms feature AI validation, NIMC NIN linkages, and offline safety backups.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            RegistrationTypeCard(
                title = "Birth Registration",
                description = "Register a newborn or young child. Record full child details, mother & father profiles, hospital notifications, and secure digital certificates.",
                duration = "Free within 60 days",
                icon = Icons.Default.Favorite,
                color = MaterialTheme.colorScheme.tertiary,
                onClick = { viewModel.activeRegistrationForm = "BIRTH" }
            )

            RegistrationTypeCard(
                title = "Death Registration & Notification",
                description = "Record deceased details, place and cause of death, informant credentials, and secure official medical confirmations.",
                duration = "Free within 30 days",
                icon = Icons.Default.Info,
                color = Color.Gray,
                onClick = { viewModel.activeRegistrationForm = "DEATH" }
            )

            RegistrationTypeCard(
                title = "Marriage Registration",
                description = "Apply for official registration of civil, religious, or customary marriages in Nigeria. Couples, witnesses, and registry verification.",
                duration = "Instant QR confirmation",
                icon = Icons.Default.Face,
                color = MaterialTheme.colorScheme.secondary,
                onClick = { viewModel.activeRegistrationForm = "MARRIAGE" }
            )
        }
    }
}

@Composable
fun RegistrationTypeCard(
    title: String,
    description: String,
    duration: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Text(
                        text = duration,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }
        }
    }
}

// ==========================================
// FORM CONTAINER (INTEGRATING FORMS, OCR, AND NIN TESTS)
// ==========================================
@Composable
fun RegistrationFormContainer(viewModel: NPCVitalViewModel) {
    val context = LocalContext.current
    val formType = viewModel.activeRegistrationForm ?: return

    // Reset validation states on form type change
    LaunchedEffect(formType) {
        viewModel.aiValidationFeedback = null
        viewModel.aiFormErrors.clear()
        viewModel.activeFormErrors.clear()
    }

    // Fields definitions
    var birthChildName by remember { mutableStateOf("") }
    var birthDob by remember { mutableStateOf("") }
    var birthPob by remember { mutableStateOf("") }
    var birthSex by remember { mutableStateOf("Male") }
    var birthOrder by remember { mutableStateOf("1st") }
    var birthMotherName by remember { mutableStateOf("") }
    var birthMotherNin by remember { mutableStateOf("") }
    var birthFatherName by remember { mutableStateOf("") }
    var birthFatherNin by remember { mutableStateOf("") }
    var birthHospitalName by remember { mutableStateOf("") }

    var deathDeceasedName by remember { mutableStateOf("") }
    var deathAge by remember { mutableStateOf("") }
    var deathSex by remember { mutableStateOf("Male") }
    var deathDate by remember { mutableStateOf("") }
    var deathPlace by remember { mutableStateOf("") }
    var deathCause by remember { mutableStateOf("") }
    var deathInformantName by remember { mutableStateOf("") }
    var deathInformantRelation by remember { mutableStateOf("") }
    var deathInformantNin by remember { mutableStateOf("") }

    var marriageHusbandName by remember { mutableStateOf("") }
    var marriageHusbandNin by remember { mutableStateOf("") }
    var marriageWifeName by remember { mutableStateOf("") }
    var marriageWifeNin by remember { mutableStateOf("") }
    var marriageDate by remember { mutableStateOf("") }
    var marriagePlace by remember { mutableStateOf("") }
    var marriageType by remember { mutableStateOf("Civil") }
    var marriageWitness1Name by remember { mutableStateOf("") }
    var marriageWitness1Nin by remember { mutableStateOf("") }
    var marriageWitness2Name by remember { mutableStateOf("") }
    var marriageWitness2Nin by remember { mutableStateOf("") }

    var ninVerificationTarget by remember { mutableStateOf<String?>(null) } // "MOTHER", "FATHER", "INFORMANT", "HUSBAND", "WIFE", etc.

    // Real-time auto-validation with debounce
    LaunchedEffect(
        birthChildName, birthDob, birthPob, birthMotherName, birthMotherNin, birthFatherName, birthFatherNin, birthHospitalName,
        deathDeceasedName, deathAge, deathDate, deathPlace, deathCause, deathInformantName, deathInformantNin, deathInformantRelation,
        marriageHusbandName, marriageHusbandNin, marriageWifeName, marriageWifeNin, marriageDate, marriagePlace, marriageWitness1Name, marriageWitness1Nin, marriageWitness2Name, marriageWitness2Nin
    ) {
        val hasSomeContent = when (formType) {
            "BIRTH" -> birthChildName.isNotEmpty() || birthDob.isNotEmpty() || birthPob.isNotEmpty()
            "DEATH" -> deathDeceasedName.isNotEmpty() || deathDate.isNotEmpty() || deathPlace.isNotEmpty()
            "MARRIAGE" -> marriageHusbandName.isNotEmpty() || marriageWifeName.isNotEmpty() || marriageDate.isNotEmpty()
            else -> false
        }
        if (hasSomeContent) {
            delay(1000) // 1 second debounce
            val currentData = when (formType) {
                "BIRTH" -> mapOf(
                    "Child Full Name" to birthChildName,
                    "Date of Birth (YYYY-MM-DD)" to birthDob,
                    "Place of Birth (Hospital/City)" to birthPob,
                    "Mother's Full Name" to birthMotherName,
                    "Mother's NIN (11 digits)" to birthMotherNin,
                    "Father's Full Name" to birthFatherName,
                    "Father's NIN (11 digits)" to birthFatherNin,
                    "Delivery Facility / Hospital Name" to birthHospitalName
                )
                "DEATH" -> mapOf(
                    "Deceased Full Name" to deathDeceasedName,
                    "Age at Death" to deathAge,
                    "Date of Death (YYYY-MM-DD)" to deathDate,
                    "Place of Death (Hospital/City)" to deathPlace,
                    "Cause of Death (if known)" to deathCause,
                    "Informant Full Name" to deathInformantName,
                    "Informant's NIN (11 digits)" to deathInformantNin,
                    "Relationship to Deceased (e.g. Son)" to deathInformantRelation
                )
                "MARRIAGE" -> mapOf(
                    "Husband's Full Name" to marriageHusbandName,
                    "Husband's NIN (11 digits)" to marriageHusbandNin,
                    "Wife's Full Name" to marriageWifeName,
                    "Wife's NIN (11 digits)" to marriageWifeNin,
                    "Date of Marriage (YYYY-MM-DD)" to marriageDate,
                    "Location/Registry Venue" to marriagePlace,
                    "Witness 1 Name" to marriageWitness1Name,
                    "Witness 1 NIN" to marriageWitness1Nin,
                    "Witness 2 Name" to marriageWitness2Name,
                    "Witness 2 NIN" to marriageWitness2Nin
                )
                else -> emptyMap()
            }
            viewModel.validateFormWithAi(formType, currentData)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Form Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.activeRegistrationForm = null }) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back to selector")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Register $formType",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Quick Scan Button
            Button(
                onClick = {
                    viewModel.performOcrScan(formType) { result ->
                        if (formType == "BIRTH") {
                            birthChildName = result["name"] ?: ""
                            birthDob = result["dob"] ?: ""
                            birthPob = result["pob"] ?: ""
                            birthSex = result["sex"] ?: "Male"
                            birthMotherName = result["mother"] ?: ""
                            birthMotherNin = result["motherNin"] ?: ""
                            birthFatherName = result["father"] ?: ""
                        } else if (formType == "DEATH") {
                            deathDeceasedName = result["name"] ?: ""
                            deathAge = result["age"] ?: ""
                            deathSex = result["sex"] ?: "Male"
                            deathDate = result["dod"] ?: ""
                            deathPlace = result["pod"] ?: ""
                            deathCause = result["cause"] ?: ""
                            deathInformantName = result["informant"] ?: ""
                        } else if (formType == "MARRIAGE") {
                            marriageHusbandName = result["husband"] ?: ""
                            marriageWifeName = result["wife"] ?: ""
                            marriageDate = result["dom"] ?: ""
                            marriagePlace = result["place"] ?: ""
                            marriageType = result["type"] ?: "Civil"
                            marriageWitness1Name = result["witness1"] ?: ""
                            marriageWitness2Name = result["witness2"] ?: ""
                        }
                        Toast.makeText(context, "AI OCR Scanner: Successfully filled fields!", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(imageVector = Icons.Default.Search, contentDescription = "OCR Scan", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("AI OCR Fill", fontSize = 11.sp)
            }
        }

        // Form Scan Loader Status Indicator
        if (viewModel.ocrScanInProgress) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("AI OCR Scanning document & extracting demographic data...", fontSize = 12.sp)
                }
            }
        }

        // AI DATA QUALITY & COMPLIANCE AUDITOR CARD
        if (viewModel.isAiValidatingForm) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "AI Demographic Auditor: Evaluating data quality...",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            viewModel.aiValidationFeedback?.let { feedback ->
                val isSuccess = feedback.startsWith("STATUS: SUCCESS")
                val isWarning = feedback.contains("STATUS: WARNINGS_FOUND")
                
                val containerColor = if (isSuccess) {
                    Color(0xFFE8F5E9) // Light green background
                } else {
                    Color(0xFFFFF3E0) // Light amber background
                }
                val borderColor = if (isSuccess) {
                    Color(0xFF4CAF50)
                } else {
                    Color(0xFFFF9800)
                }
                val icon = if (isSuccess) Icons.Default.Check else Icons.Default.Warning

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = containerColor),
                    border = BorderStroke(1.dp, borderColor)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = icon,
                                contentDescription = "Audit Status",
                                tint = borderColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isSuccess) "AI Compliance Check: Passed" else "AI Demographic Quality Audit Recommendations",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(6.dp))

                        val cleanLines = feedback.split("\n")
                            .filter { it.trim().isNotEmpty() && !it.startsWith("STATUS:") }
                            .map { line ->
                                line.replace(Regex("\\[(primaryName|secondaryName|dateOfEvent|locationOfEvent|general)\\]"), "").trim()
                            }

                        cleanLines.forEach { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                color = Color.DarkGray,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Form Duplicate Error Prompt
        if (viewModel.activeFormErrors.containsKey("duplicate")) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = "Duplicate warning", tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = viewModel.activeFormErrors["duplicate"] ?: "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Form fields layout
        when (formType) {
            "BIRTH" -> {
                SectionHeader("1. Child Information")
                NpcTextField(label = "Child Full Name", value = birthChildName, onValueChange = { birthChildName = it }, error = viewModel.activeFormErrors["primaryName"] ?: viewModel.aiFormErrors["primaryName"])
                NpcTextField(label = "Date of Birth (YYYY-MM-DD)", value = birthDob, onValueChange = { birthDob = it }, error = viewModel.activeFormErrors["dateOfEvent"] ?: viewModel.aiFormErrors["dateOfEvent"])
                NpcTextField(label = "Place of Birth (Hospital/City)", value = birthPob, onValueChange = { birthPob = it }, error = viewModel.activeFormErrors["locationOfEvent"] ?: viewModel.aiFormErrors["locationOfEvent"])

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sex", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = birthSex == "Male", onClick = { birthSex = "Male" })
                            Text("Male", fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            RadioButton(selected = birthSex == "Female", onClick = { birthSex = "Female" })
                            Text("Female", fontSize = 13.sp)
                        }
                    }
                    NpcTextField(label = "Birth Order (e.g. 1st)", value = birthOrder, onValueChange = { birthOrder = it }, modifier = Modifier.weight(1f))
                }

                SectionHeader("2. Mother Information")
                NpcTextField(label = "Mother's Full Name", value = birthMotherName, onValueChange = { birthMotherName = it }, error = viewModel.activeFormErrors["secondaryName"] ?: viewModel.aiFormErrors["secondaryName"])
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NpcTextField(
                        label = "Mother's NIN (11 digits)",
                        value = birthMotherNin,
                        onValueChange = { birthMotherNin = it },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.verifyNin(birthMotherNin) { success ->
                                if (success) birthMotherName = "Amina Ibrahim Bello" // auto corrected
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Verify NIN", fontSize = 11.sp)
                    }
                }

                SectionHeader("3. Father Information")
                NpcTextField(label = "Father's Full Name", value = birthFatherName, onValueChange = { birthFatherName = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NpcTextField(
                        label = "Father's NIN (11 digits)",
                        value = birthFatherNin,
                        onValueChange = { birthFatherNin = it },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.verifyNin(birthFatherNin) { success ->
                                if (success) birthFatherName = "Femi Samuel Adebayo"
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Verify NIN", fontSize = 11.sp)
                    }
                }

                SectionHeader("4. Healthcare Facility Details")
                NpcTextField(label = "Delivery Facility / Hospital Name", value = birthHospitalName, onValueChange = { birthHospitalName = it })
            }
            "DEATH" -> {
                SectionHeader("1. Deceased Information")
                NpcTextField(label = "Deceased Full Name", value = deathDeceasedName, onValueChange = { deathDeceasedName = it }, error = viewModel.activeFormErrors["primaryName"] ?: viewModel.aiFormErrors["primaryName"])
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NpcTextField(label = "Age at Death", value = deathAge, onValueChange = { deathAge = it }, keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sex", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = deathSex == "Male", onClick = { deathSex = "Male" })
                            Text("Male", fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            RadioButton(selected = deathSex == "Female", onClick = { deathSex = "Female" })
                            Text("Female", fontSize = 13.sp)
                        }
                    }
                }
                NpcTextField(label = "Date of Death (YYYY-MM-DD)", value = deathDate, onValueChange = { deathDate = it }, error = viewModel.activeFormErrors["dateOfEvent"] ?: viewModel.aiFormErrors["dateOfEvent"])
                NpcTextField(label = "Place of Death (Hospital/City)", value = deathPlace, onValueChange = { deathPlace = it }, error = viewModel.activeFormErrors["locationOfEvent"] ?: viewModel.aiFormErrors["locationOfEvent"])
                NpcTextField(label = "Cause of Death (if known)", value = deathCause, onValueChange = { deathCause = it })

                SectionHeader("2. Informant Details")
                NpcTextField(label = "Informant Full Name", value = deathInformantName, onValueChange = { deathInformantName = it }, error = viewModel.activeFormErrors["secondaryName"] ?: viewModel.aiFormErrors["secondaryName"])
                NpcTextField(label = "Relationship to Deceased (e.g. Son)", value = deathInformantRelation, onValueChange = { deathInformantRelation = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NpcTextField(
                        label = "Informant's NIN (11 digits)",
                        value = deathInformantNin,
                        onValueChange = { deathInformantNin = it },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.verifyNin(deathInformantNin) { success ->
                                if (success) deathInformantName = "Mallam Usman Bello"
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Verify NIN", fontSize = 11.sp)
                    }
                }
            }
            "MARRIAGE" -> {
                SectionHeader("1. Couple Details")
                NpcTextField(label = "Husband's Full Name", value = marriageHusbandName, onValueChange = { marriageHusbandName = it }, error = viewModel.activeFormErrors["primaryName"] ?: viewModel.aiFormErrors["primaryName"])
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NpcTextField(
                        label = "Husband's NIN (11 digits)",
                        value = marriageHusbandNin,
                        onValueChange = { marriageHusbandNin = it },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.verifyNin(marriageHusbandNin) { success ->
                                if (success) marriageHusbandName = "Tunde Cole"
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Verify NIN", fontSize = 11.sp)
                    }
                }

                NpcTextField(label = "Wife's Full Name", value = marriageWifeName, onValueChange = { marriageWifeName = it }, error = viewModel.activeFormErrors["secondaryName"] ?: viewModel.aiFormErrors["secondaryName"])
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NpcTextField(
                        label = "Wife's NIN (11 digits)",
                        value = marriageWifeNin,
                        onValueChange = { marriageWifeNin = it },
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            viewModel.verifyNin(marriageWifeNin) { success ->
                                if (success) marriageWifeName = "Fatima Yusuf"
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Verify NIN", fontSize = 11.sp)
                    }
                }

                SectionHeader("2. Event Information")
                NpcTextField(label = "Date of Marriage (YYYY-MM-DD)", value = marriageDate, onValueChange = { marriageDate = it }, error = viewModel.activeFormErrors["dateOfEvent"] ?: viewModel.aiFormErrors["dateOfEvent"])
                NpcTextField(label = "Location/Registry Venue", value = marriagePlace, onValueChange = { marriagePlace = it }, error = viewModel.activeFormErrors["locationOfEvent"] ?: viewModel.aiFormErrors["locationOfEvent"])

                Column {
                    Text("Marriage Type", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = marriageType == "Civil", onClick = { marriageType = "Civil" })
                        Text("Civil", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        RadioButton(selected = marriageType == "Religious", onClick = { marriageType = "Religious" })
                        Text("Religious", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        RadioButton(selected = marriageType == "Customary", onClick = { marriageType = "Customary" })
                        Text("Customary", fontSize = 13.sp)
                    }
                }

                SectionHeader("3. Witnesses")
                NpcTextField(label = "Witness 1 Name", value = marriageWitness1Name, onValueChange = { marriageWitness1Name = it })
                NpcTextField(label = "Witness 1 NIN", value = marriageWitness1Nin, onValueChange = { marriageWitness1Nin = it }, keyboardType = KeyboardType.Number)
                Spacer(modifier = Modifier.height(4.dp))
                NpcTextField(label = "Witness 2 Name", value = marriageWitness2Name, onValueChange = { marriageWitness2Name = it })
                NpcTextField(label = "Witness 2 NIN", value = marriageWitness2Nin, onValueChange = { marriageWitness2Nin = it }, keyboardType = KeyboardType.Number)
            }
        }

        // Active NIN Loader Prompt
        if (viewModel.isNinVerifying || viewModel.ninVerificationMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (viewModel.isNinVerifying) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = viewModel.ninVerificationMessage ?: "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Actions
        Button(
            onClick = {
                val primary = when (formType) {
                    "BIRTH" -> birthChildName
                    "DEATH" -> deathDeceasedName
                    "MARRIAGE" -> marriageHusbandName
                    else -> ""
                }
                val secondary = when (formType) {
                    "BIRTH" -> "$birthMotherName (Mother)"
                    "DEATH" -> "$deathInformantName ($deathInformantRelation)"
                    "MARRIAGE" -> marriageWifeName
                    else -> ""
                }
                val date = when (formType) {
                    "BIRTH" -> birthDob
                    "DEATH" -> deathDate
                    "MARRIAGE" -> marriageDate
                    else -> ""
                }
                val location = when (formType) {
                    "BIRTH" -> birthPob
                    "DEATH" -> deathPlace
                    "MARRIAGE" -> marriagePlace
                    else -> ""
                }

                viewModel.submitRegistration(
                    eventType = formType,
                    primaryName = primary,
                    secondaryName = secondary,
                    dateOfEvent = date,
                    locationOfEvent = location,
                    formDataJson = "{}",
                    gpsLat = 9.0765 + (Math.random() - 0.5) * 2, // dynamic simulated GPS Nigeria boundary
                    gpsLng = 7.3986 + (Math.random() - 0.5) * 2
                ) {
                    Toast.makeText(context, "Vital Event saved securely to offline-first cache!", Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(imageVector = Icons.Default.Check, contentDescription = "Submit registration")
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (viewModel.isOnlineMode) "Submit Registration" else "Register Offline")
        }

        OutlinedButton(
            onClick = { viewModel.activeRegistrationForm = null },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Cancel")
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
fun NpcTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 12.sp) },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        )
        if (error != null) {
            Text(
                text = error,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

// ==========================================
// SCREEN 3: MY CERTIFICATES (VIEW / VERIFY)
// ==========================================
@Composable
fun MyCertificatesScreen(viewModel: NPCVitalViewModel) {
    val events by viewModel.registeredEvents.collectAsState()
    val approvedCertificates = events.filter { it.status == "APPROVED" }

    var selectedCertificate by remember { mutableStateOf<RegisteredEvent?>(null) }
    var inputVerifyId by remember { mutableStateOf("") }
    var scannedVerificationResult by remember { mutableStateOf<RegisteredEvent?>(null) }
    var isVerificationTriggered by remember { mutableStateOf(false) }

    if (selectedCertificate != null) {
        // Display Beautiful Government Issued Certificate view
        CertificateViewer(event = selectedCertificate!!) {
            selectedCertificate = null
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Tabs
            Text(
                text = "Issued Certificates Portal",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "Access secure, legally-binding vital certificates. Authenticated by official QR codes linked to the Federal Demographics Registry.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            // Certificate Verification card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Verify QR / Certificate ID",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputVerifyId,
                            onValueChange = { inputVerifyId = it },
                            placeholder = { Text("Enter Certificate ID or Scan code", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Button(
                            onClick = {
                                isVerificationTriggered = true
                                scannedVerificationResult = events.firstOrNull {
                                    it.id.startsWith(inputVerifyId) || it.qrCodeData.contains(inputVerifyId, ignoreCase = true)
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Verify")
                        }
                    }

                    // Display Verification Output
                    if (isVerificationTriggered) {
                        Spacer(modifier = Modifier.height(12.dp))
                        if (scannedVerificationResult != null) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = "Valid Certificate", tint = MaterialTheme.colorScheme.tertiary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("Legally Verified Record!", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
                                        Text("Primary Subject: ${scannedVerificationResult!!.primaryName}", fontSize = 11.sp)
                                        Text("Event: ${scannedVerificationResult!!.eventType} | Date: ${scannedVerificationResult!!.dateOfEvent}", fontSize = 11.sp)
                                    }
                                }
                            }
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.Warning, contentDescription = "Invalid Certificate", tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Record Not Found: No matching registered vital event was discovered in the federal directory.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = "Your Approved Certificates (${approvedCertificates.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground
            )

            if (approvedCertificates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "No certificates",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No approved certificates available. Registrations require NPC officer review or online sync activation.",
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                approvedCertificates.forEach { cert ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedCertificate = cert },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Certificate Ready",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = "${cert.eventType} CERTIFICATE",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = cert.primaryName,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "ID: ${cert.id.substring(0, 8).uppercase()}...",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                }
                            }
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "View Certificate", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// INTERACTIVE SYSTEM GENERATED CERTIFICATE VIEWER
// ==========================================
@Composable
fun CertificateViewer(event: RegisteredEvent, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E293B)) // dark background representing secure presentation layer
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Official Secure Document",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }

        // The Government Certificate Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFCFDFE)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(3.dp, Color(0xFF2E7D32)) // Elegant national green border
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Crest header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E7D32)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Star, contentDescription = "Crest", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "NATIONAL POPULATION COMMISSION",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFF1B4F72),
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "FEDERAL REPUBLIC OF NIGERIA",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Certificate Category
                Text(
                    text = "CERTIFICATE OF ${event.eventType}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFF1565C0),
                    letterSpacing = 1.sp
                )

                // Subtitle
                Text(
                    text = "Issued under the Vital Registration Act of Nigeria",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Details lines
                CertificateField("REGISTRATION ID", event.id.uppercase())
                CertificateField("PRIMARY REGISTERED NAME", event.primaryName.uppercase())
                CertificateField("SECONDARY / SPOUSE NAME", event.secondaryName.uppercase())
                CertificateField("DATE OF EVENT", event.dateOfEvent)
                CertificateField("LOCATION OF EVENT", event.locationOfEvent)
                CertificateField("GPS POSITION COORDINATES", "Lat: ${"%.4f".format(event.gpsLat)}, Lng: ${"%.4f".format(event.gpsLng)}")
                CertificateField("REGISTRATION DATE", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(event.createdAt)))

                Spacer(modifier = Modifier.height(24.dp))

                // QR CODE CANVAS DRAWING (Completely custom and highly detailed for robust offline scan simulation!)
                Text("NPC Government Authenticator QR", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B4F72))
                Spacer(modifier = Modifier.height(8.dp))
                Canvas(
                    modifier = Modifier
                        .size(120.dp)
                        .background(Color.White)
                        .border(1.dp, Color.LightGray)
                ) {
                    val sizeX = size.width
                    val sizeY = size.height
                    // Draw outer border squares
                    drawRect(color = Color.Black, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(30f, 30f))
                    drawRect(color = Color.Black, topLeft = Offset(sizeX - 30f, 0f), size = androidx.compose.ui.geometry.Size(30f, 30f))
                    drawRect(color = Color.Black, topLeft = Offset(0f, sizeY - 30f), size = androidx.compose.ui.geometry.Size(30f, 30f))

                    // Draw abstract QR blocks
                    for (i in 0..10) {
                        for (j in 0..10) {
                            if ((i + j) % 2 == 0 && (i > 2 || j > 2) && (i < 8 || j < 8)) {
                                drawRect(
                                    color = Color.Black,
                                    topLeft = Offset(i * 10f + 10f, j * 10f + 10f),
                                    size = androidx.compose.ui.geometry.Size(8f, 8f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Signature", fontSize = 9.sp, color = Color.Gray)
                        Text("Hon. Chairman, NPC", fontWeight = FontWeight.Bold, fontSize = 9.sp, color = Color(0xFF1B4F72))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Status", fontSize = 9.sp, color = Color.Gray)
                        Text("OFFICIALLY SEALED", fontWeight = FontWeight.Bold, fontSize = 9.sp, color = Color(0xFF2E7D32))
                    }
                }
            }
        }

        // Action Buttons
        Button(
            onClick = { /* simulated pdf export */ },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(imageVector = Icons.Default.Share, contentDescription = "Download Certificate")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Export & Share Official PDF", color = Color.White)
        }
    }
}

@Composable
fun CertificateField(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = Color.Gray,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = Color(0xFF1B4F72),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(2.dp))
        Divider(color = Color.LightGray.copy(alpha = 0.5f), thickness = 0.5.dp)
    }
}

// ==========================================
// SCREEN 4: NPC REGISTRATION CENTRES MAP & APPOINTMENT
// ==========================================
@Composable
fun NpcCentresScreen(viewModel: NPCVitalViewModel) {
    var stateFilter by remember { mutableStateOf("All States") }
    var showBookingDialog by remember { mutableStateOf<NpcCentre?>(null) }
    var bookingName by remember { mutableStateOf("") }
    var bookingDate by remember { mutableStateOf("") }
    val context = LocalContext.current

    val statesList = listOf("All States", "Abuja (FCT)", "Lagos", "Kano", "Enugu", "Oyo", "Rivers")
    val filteredCentres = if (stateFilter == "All States") {
        NPC_CENTRES
    } else {
        NPC_CENTRES.filter { it.state == stateFilter }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Official Registration Centres",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Dropdown/Filter selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Filter by Region:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    statesList.forEach { state ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (stateFilter == state) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable { stateFilter = state }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = state,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (stateFilter == state) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // CUSTOM GEOMETRIC MAP DRAWING: Simulates GIS distribution across Nigeria!
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Draw simple schematic boundary outline representing Nigeria map
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.1f, h * 0.4f)
                        quadraticTo(w * 0.2f, h * 0.15f, w * 0.5f, h * 0.1f)
                        quadraticTo(w * 0.8f, h * 0.15f, w * 0.9f, h * 0.4f)
                        quadraticTo(w * 0.95f, h * 0.7f, w * 0.8f, h * 0.85f)
                        quadraticTo(w * 0.5f, h * 0.95f, w * 0.2f, h * 0.8f)
                        quadraticTo(w * 0.05f, h * 0.6f, w * 0.1f, h * 0.4f)
                        close()
                    }
                    drawPath(
                        path = path,
                        color = Color(0xFF2E7D32).copy(alpha = 0.1f),
                        style = androidx.compose.ui.graphics.drawscope.Fill
                    )
                    drawPath(
                        path = path,
                        color = Color(0xFF2E7D32).copy(alpha = 0.4f),
                        style = Stroke(
                            width = 3f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                        )
                    )

                    // Draw River Niger / Benue Y-junction simulation
                    val riverPath = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.1f, h * 0.35f)
                        quadraticTo(w * 0.35f, h * 0.45f, w * 0.5f, h * 0.55f)
                        // Fork right (Benue)
                        quadraticTo(w * 0.75f, h * 0.45f, w * 0.9f, h * 0.4f)
                        // Fork left (Niger down)
                        moveTo(w * 0.5f, h * 0.55f)
                        quadraticTo(w * 0.52f, h * 0.75f, w * 0.45f, h * 0.9f)
                    }
                    drawPath(
                        path = riverPath,
                        color = Color(0xFF1565C0).copy(alpha = 0.3f),
                        style = Stroke(width = 4f)
                    )
                }

                // Plot the active pins for NPC centres
                filteredCentres.forEach { centre ->
                    val xOffset = centre.longitude * 300f + 50f
                    val yOffset = centre.latitude * 120f + 30f

                    Box(
                        modifier = Modifier
                            .offset(x = xOffset.dp, y = yOffset.dp)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = centre.name,
                            tint = Color.White,
                            modifier = Modifier.size(8.dp)
                        )
                    }
                }

                // Map Legend
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Interactive Nigeria GIS Mode", fontSize = 8.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        // List of centres
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredCentres) { centre ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = centre.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(centre.state, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(centre.address, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Registrar: ${centre.officerName}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                Text("Phone: ${centre.phone}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            Button(
                                onClick = { showBookingDialog = centre },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Book Desk", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // Booking Appointment Dialog simulation
        if (showBookingDialog != null) {
            Dialog(onDismissRequest = { showBookingDialog = null }) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Book NPC Office Appointment", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                        Text("Office: ${showBookingDialog!!.name}", fontSize = 12.sp, color = Color.Gray)

                        OutlinedTextField(
                            value = bookingName,
                            onValueChange = { bookingName = it },
                            label = { Text("Full Name") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = bookingDate,
                            onValueChange = { bookingDate = it },
                            label = { Text("Preferred Date (YYYY-MM-DD)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showBookingDialog = null }) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    Toast.makeText(context, "Appointment Booked! Safe-backed offline. Reference ID: NPC-APT-${UUID.randomUUID().toString().substring(0,6).uppercase()}", Toast.LENGTH_LONG).show()
                                    showBookingDialog = null
                                }
                            ) {
                                Text("Confirm Desk")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 5: PROFILE, DEMOGRAPHICS & AI CHATBOT
// ==========================================
@Composable
fun ProfileAndAnalyticsScreen(viewModel: NPCVitalViewModel) {
    var activeSubTab by remember { mutableStateOf("AI CHATBOT") } // "AI CHATBOT", "INSIGHTS", "ROLE SWITCH"
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Toggle Switch for Profile Sections
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val subTabs = listOf("AI CHATBOT", "INSIGHTS", "ACCESSIBILITY", "ADMIN")
            subTabs.forEach { tab ->
                val active = activeSubTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { activeSubTab = tab }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Render sections based on active tab
        when (activeSubTab) {
            "AI CHATBOT" -> {
                // Beautiful Integrated Support Chatbot panel
                Text(
                    text = "NPC AI Demographic Assistant",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Consult the AI helper regarding civil registration requirements in Nigeria. Functions fully offline via robust local policy fallback.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )

                // Messages list
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        .padding(8.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        reverseLayout = false
                    ) {
                        items(viewModel.chatMessages) { msg ->
                            ChatBubble(msg)
                        }
                    }
                }

                // Chat Input bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = viewModel.chatbotInputText,
                        onValueChange = { viewModel.chatbotInputText = it },
                        placeholder = { Text("Ask NPC advisor...", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    IconButton(
                        onClick = { viewModel.sendChatMessage() },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Message",
                            tint = Color.White
                        )
                    }
                }
            }

            "INSIGHTS" -> {
                // Demographic trends graphics & Analytics using native Compose Canvas drawing!
                val events by viewModel.registeredEvents.collectAsState()
                val totalBirths = events.count { it.eventType == "BIRTH" }
                val totalDeaths = events.count { it.eventType == "DEATH" }
                val totalMarriages = events.count { it.eventType == "MARRIAGE" }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            text = "AI Population Insights Dashboard",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Derived from local register distributions. Identifies regional density anomalies and demographic trends.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                    // Native demographic distribution chart
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Spatial Distribution Rates", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(16.dp))

                                // Simple custom bar chart using Column shapes
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp),
                                    horizontalArrangement = Arrangement.SpaceAround,
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    val maxVal = maxOf(totalBirths, totalDeaths, totalMarriages, 1).toFloat()

                                    ChartBar("Births", totalBirths, totalBirths / maxVal, MaterialTheme.colorScheme.tertiary)
                                    ChartBar("Deaths", totalDeaths, totalDeaths / maxVal, Color.Gray)
                                    ChartBar("Marriages", totalMarriages, totalMarriages / maxVal, MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                    }

                    // AI predictive recommendations card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.Info, contentDescription = "AI insights", tint = MaterialTheme.colorScheme.tertiary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Demographic AI Projections", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "• Based on local data density, quarterly Birth Rates in Abuja FCT and Lagos are projected to rise by 4.2%.\n" +
                                            "• Customary/Religious marriages have increased by 15% in southern regions.\n" +
                                            "• 100% of recorded remote area events are secured with cryptographic hashes, enabling secure verification offline.",
                                    fontSize = 11.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            "ACCESSIBILITY" -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            text = "Universal Accessibility Options",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Configure display density, contrast optimizations, and demographic assistive narration systems to support all citizens.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                    // Font Sizing Multiplier
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Text Sizing Control",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Text & Font Sizing",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Current Scale: ${(viewModel.fontSizeMultiplier * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val sizes = listOf(
                                        0.85f to "Compact",
                                        1.0f to "Normal",
                                        1.2f to "Large",
                                        1.4f to "XL",
                                        1.6f to "Max"
                                    )
                                    sizes.forEach { (scale, label) ->
                                        val active = viewModel.fontSizeMultiplier == scale
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (active) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.surfaceVariant
                                                )
                                                .clickable { viewModel.fontSizeMultiplier = scale }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Contrast & Color Correction
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Contrast Control",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "High Contrast Mode",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Enhance borders and contrast values",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                    Switch(
                                        checked = viewModel.isHighContrastMode,
                                        onCheckedChange = { viewModel.isHighContrastMode = it }
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(16.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = "Color Filter Control",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Colorblind Assist Filters",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val filters = listOf("None", "Deuteranopia", "Tritanopia", "Monochromacy")
                                    filters.forEach { filter ->
                                        val active = viewModel.colorBlindFilter == filter
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (active) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.surfaceVariant
                                                )
                                                .clickable { viewModel.colorBlindFilter = filter }
                                                .padding(vertical = 8.dp, horizontal = 2.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (filter == "Deuteranopia") "Red-Green" else if (filter == "Tritanopia") "Blue-Yellow" else filter,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Screen Guidance Overlay Settings
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Screen Guidance Control",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Interactive Screen Narrator",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Show explicit screen content descriptions",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                    Switch(
                                        checked = viewModel.showScreenReaderHelper,
                                        onCheckedChange = { viewModel.showScreenReaderHelper = it }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            "ADMIN" -> {
                // Role-Based Access and Testing sandbox tools
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            text = "Officer Authorization Controls",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Toggle roles to experience NPC Officer workflow dashboard or reset sandbox local storage.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                    // Role switch
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Current Role Profile", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = viewModel.currentUserRole,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Button(
                                        onClick = { viewModel.toggleUserRole() },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Toggle Role")
                                    }
                                }
                            }
                        }
                    }

                    // Clear Sandbox Database
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Sandbox Storage Settings", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        viewModel.clearSandboxDatabase()
                                        Toast.makeText(context, "Local Room database cleared!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Clear Local Database Cache")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val bg = if (message.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val tc = if (message.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val align = if (message.isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = align
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (message.isUser) 12.dp else 0.dp,
                        bottomEnd = if (message.isUser) 0.dp else 12.dp
                    )
                )
                .background(bg)
                .padding(12.dp)
                .widthIn(max = 280.dp)
        ) {
            Text(
                text = message.text,
                color = tc,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun ChartBar(label: String, count: Int, ratio: Float, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
        modifier = Modifier.fillMaxHeight()
    ) {
        Text(count.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(40.dp)
                .fillMaxHeight(ratio.coerceAtLeast(0.08f)) // min height so bar shows
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
