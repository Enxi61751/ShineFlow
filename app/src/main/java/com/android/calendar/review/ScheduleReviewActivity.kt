package com.android.calendar.review

import android.Manifest
import android.app.DatePickerDialog
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract.Instances
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.android.calendar.review.network.AgentProfileRequest
import com.android.calendar.review.network.ReviewEventRequest
import com.android.calendar.review.network.ReviewGenerateRequest
import com.android.calendar.review.network.ReviewGenerateResponse
import com.android.calendar.review.network.ReviewMemoryDto
import com.android.calendar.review.network.ReviewNetworkModule
import com.android.calendar.settings.GeneralPreferences
import com.android.calendar.theme.applyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import ws.xsoh.etar.R
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID


class ScheduleReviewActivity : AppCompatActivity() {
    private data class CalendarReviewEvent(
        val eventId: String,
        val title: String,
        val begin: Long,
        val end: Long,
        val description: String,
        val location: String,
        val allDay: Boolean,
        val completedCheckBox: CheckBox
    )

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val events = mutableListOf<CalendarReviewEvent>()
    private var selectedDate: LocalDate = LocalDate.now()

    private lateinit var dateButton: Button
    private lateinit var eventsContainer: LinearLayout
    private lateinit var eventsHint: TextView
    private lateinit var agentNameInput: EditText
    private lateinit var personalitySpinner: Spinner
    private lateinit var goalsInput: EditText
    private lateinit var moodInput: EditText
    private lateinit var energyLabel: TextView
    private lateinit var energySeek: SeekBar
    private lateinit var satisfactionLabel: TextView
    private lateinit var satisfactionSeek: SeekBar
    private lateinit var reflectionInput: EditText
    private lateinit var generateButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var resultText: TextView
    private lateinit var memoryText: TextView
    private lateinit var memoryRefreshButton: Button
    private lateinit var memoryClearButton: Button

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadCalendarEvents()
        } else {
            renderCalendarPermissionMissing()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_review)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.schedule_review_title)

        bindViews()
        restoreProfile()
        setupControls()
        updateDateButton()
        ensureCalendarPermissionAndLoad()
        loadMemories()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun bindViews() {
        dateButton = findViewById(R.id.review_date_button)
        eventsContainer = findViewById(R.id.review_events_container)
        eventsHint = findViewById(R.id.review_events_hint)
        agentNameInput = findViewById(R.id.review_agent_name)
        personalitySpinner = findViewById(R.id.review_personality_spinner)
        goalsInput = findViewById(R.id.review_goals)
        moodInput = findViewById(R.id.review_mood)
        energyLabel = findViewById(R.id.review_energy_label)
        energySeek = findViewById(R.id.review_energy_seek)
        satisfactionLabel = findViewById(R.id.review_satisfaction_label)
        satisfactionSeek = findViewById(R.id.review_satisfaction_seek)
        reflectionInput = findViewById(R.id.review_reflection)
        generateButton = findViewById(R.id.review_generate_button)
        progress = findViewById(R.id.review_progress)
        resultText = findViewById(R.id.review_result)
        memoryText = findViewById(R.id.review_memory_list)
        memoryRefreshButton = findViewById(R.id.review_memory_refresh)
        memoryClearButton = findViewById(R.id.review_memory_clear)
    }

    private fun setupControls() {
        dateButton.setOnClickListener { showDatePicker() }
        generateButton.setOnClickListener { generateReview() }
        memoryRefreshButton.setOnClickListener { loadMemories() }
        memoryClearButton.setOnClickListener { confirmClearMemories() }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateScoreLabels()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        energySeek.setOnSeekBarChangeListener(seekListener)
        satisfactionSeek.setOnSeekBarChangeListener(seekListener)
        updateScoreLabels()
    }

    private fun restoreProfile() {
        val prefs = getSharedPreferences(GeneralPreferences.SHARED_PREFS_NAME, MODE_PRIVATE)
        agentNameInput.setText(prefs.getString(KEY_AGENT_NAME, DEFAULT_AGENT_NAME))
        goalsInput.setText(prefs.getString(KEY_AGENT_GOALS, ""))
        val selectedPersonality = prefs.getString(KEY_AGENT_PERSONALITY, DEFAULT_PERSONALITY)
        val values = resources.getStringArray(R.array.review_personality_values)
        val index = values.indexOf(selectedPersonality).takeIf { it >= 0 } ?: 0
        personalitySpinner.setSelection(index)
    }

    private fun persistProfile(agentName: String, personality: String, goals: String) {
        getSharedPreferences(GeneralPreferences.SHARED_PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_AGENT_NAME, agentName)
            .putString(KEY_AGENT_PERSONALITY, personality)
            .putString(KEY_AGENT_GOALS, goals)
            .apply()
    }

    private fun showDatePicker() {
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                updateDateButton()
                ensureCalendarPermissionAndLoad()
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth
        ).show()
    }

    private fun updateDateButton() {
        val formatter = DateTimeFormatter.ofPattern(
            getString(R.string.schedule_review_date_pattern),
            Locale.getDefault()
        )
        dateButton.text = getString(
            R.string.schedule_review_selected_date,
            selectedDate.format(formatter)
        )
    }

    private fun updateScoreLabels() {
        energyLabel.text = getString(R.string.schedule_review_energy, energySeek.progress + 1)
        satisfactionLabel.text = getString(
            R.string.schedule_review_satisfaction,
            satisfactionSeek.progress + 1
        )
    }

    private fun ensureCalendarPermissionAndLoad() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            loadCalendarEvents()
        } else {
            calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    private fun loadCalendarEvents() {
        events.clear()
        eventsContainer.removeAllViews()

        val zone = ZoneId.systemDefault()
        val startMillis = selectedDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = selectedDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uriBuilder = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, startMillis)
        ContentUris.appendId(uriBuilder, endMillis)

        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.DESCRIPTION,
            Instances.EVENT_LOCATION,
            Instances.ALL_DAY
        )

        try {
            contentResolver.query(
                uriBuilder.build(),
                projection,
                null,
                null,
                "${Instances.BEGIN} ASC"
            )?.use { cursor ->
                val eventIdIndex = cursor.getColumnIndexOrThrow(Instances.EVENT_ID)
                val titleIndex = cursor.getColumnIndexOrThrow(Instances.TITLE)
                val beginIndex = cursor.getColumnIndexOrThrow(Instances.BEGIN)
                val endIndex = cursor.getColumnIndexOrThrow(Instances.END)
                val descriptionIndex = cursor.getColumnIndexOrThrow(Instances.DESCRIPTION)
                val locationIndex = cursor.getColumnIndexOrThrow(Instances.EVENT_LOCATION)
                val allDayIndex = cursor.getColumnIndexOrThrow(Instances.ALL_DAY)

                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleIndex)?.trim().orEmpty().ifEmpty {
                        getString(R.string.schedule_review_untitled_event)
                    }
                    val begin = cursor.getLong(beginIndex)
                    val end = cursor.getLong(endIndex)
                    val allDay = cursor.getInt(allDayIndex) == 1
                    val checkBox = CheckBox(this).apply {
                        text = formatEventRow(title, begin, end, allDay)
                        setPadding(0, 6, 0, 6)
                    }
                    eventsContainer.addView(checkBox)
                    events += CalendarReviewEvent(
                        eventId = cursor.getLong(eventIdIndex).toString(),
                        title = title,
                        begin = begin,
                        end = end,
                        description = cursor.getString(descriptionIndex).orEmpty(),
                        location = cursor.getString(locationIndex).orEmpty(),
                        allDay = allDay,
                        completedCheckBox = checkBox
                    )
                }
            }
        } catch (securityException: SecurityException) {
            renderCalendarPermissionMissing()
            return
        }

        eventsHint.text = if (events.isEmpty()) {
            getString(R.string.schedule_review_no_events)
        } else {
            resources.getQuantityString(
                R.plurals.schedule_review_event_count,
                events.size,
                events.size
            )
        }
    }

    private fun formatEventRow(title: String, begin: Long, end: Long, allDay: Boolean): String {
        if (allDay) {
            return getString(R.string.schedule_review_event_all_day, title)
        }
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${formatter.format(Date(begin))}-${formatter.format(Date(end))}  $title"
    }

    private fun renderCalendarPermissionMissing() {
        events.clear()
        eventsContainer.removeAllViews()
        eventsHint.text = getString(R.string.schedule_review_calendar_permission)
    }

    private fun generateReview() {
        val agentName = agentNameInput.text.toString().trim().ifEmpty { DEFAULT_AGENT_NAME }
        val personalityValues = resources.getStringArray(R.array.review_personality_values)
        val personality = personalityValues.getOrElse(personalitySpinner.selectedItemPosition) {
            DEFAULT_PERSONALITY
        }
        val goalsText = goalsInput.text.toString().trim()
        persistProfile(agentName, personality, goalsText)

        val request = ReviewGenerateRequest(
            userId = getOrCreateUserId(),
            reviewDate = selectedDate.toString(),
            timezone = ZoneId.systemDefault().id,
            reflection = reflectionInput.text.toString().trim(),
            mood = moodInput.text.toString().trim(),
            energyLevel = energySeek.progress + 1,
            satisfactionScore = satisfactionSeek.progress + 1,
            events = events.map { event ->
                ReviewEventRequest(
                    eventId = event.eventId,
                    title = event.title,
                    startTime = event.begin.takeIf { !event.allDay }?.let(::toIsoTime),
                    endTime = event.end.takeIf { !event.allDay }?.let(::toIsoTime),
                    description = event.description,
                    location = event.location,
                    allDay = event.allDay,
                    completionStatus = if (event.completedCheckBox.isChecked) {
                        "completed"
                    } else {
                        "unknown"
                    }
                )
            },
            agentProfile = AgentProfileRequest(
                assistantName = agentName,
                personality = personality,
                tone = personalityTone(personality),
                goals = splitGoals(goalsText)
            )
        )

        setLoading(true)
        activityScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ReviewNetworkModule.api.generateReview(request)
                }
                renderReview(response)
                loadMemories()
            } catch (exception: Exception) {
                resultText.text = getString(
                    R.string.schedule_review_error,
                    readableError(exception)
                )
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        generateButton.isEnabled = !loading
        dateButton.isEnabled = !loading
    }

    private fun renderReview(response: ReviewGenerateResponse) {
        val result = response.result
        val builder = StringBuilder()
        builder.appendLine("${result.headline}  ·  ${result.completionScore}/100")
        builder.appendLine()
        builder.appendLine(result.summary)
        appendSection(builder, getString(R.string.schedule_review_result_achievements), result.achievements)
        appendSection(builder, getString(R.string.schedule_review_result_unfinished), result.unfinishedItems)
        appendSection(builder, getString(R.string.schedule_review_result_patterns), result.patterns)
        appendSection(builder, getString(R.string.schedule_review_result_suggestions), result.suggestions)
        appendSection(builder, getString(R.string.schedule_review_result_tomorrow), result.tomorrowFocus)
        if (result.encouragement.isNotBlank()) {
            builder.appendLine()
            builder.appendLine(result.encouragement)
        }
        builder.appendLine()
        builder.append(
            if (response.generatedBy == "llm") {
                getString(
                    R.string.schedule_review_generated_llm,
                    response.usedMemories.size,
                    response.savedMemories.size
                )
            } else {
                getString(R.string.schedule_review_generated_fallback)
            }
        )
        resultText.text = builder.toString().trim()
    }

    private fun appendSection(builder: StringBuilder, title: String, items: List<String>) {
        if (items.isEmpty()) return
        builder.appendLine()
        builder.appendLine(title)
        items.forEach { builder.appendLine("• $it") }
    }

    private fun loadMemories() {
        val userId = getOrCreateUserId()
        memoryText.text = getString(R.string.schedule_review_memory_loading)
        activityScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ReviewNetworkModule.api.getMemories(userId)
                }
                renderMemories(response.items)
            } catch (exception: Exception) {
                memoryText.text = getString(
                    R.string.schedule_review_memory_error,
                    readableError(exception)
                )
            }
        }
    }

    private fun renderMemories(memories: List<ReviewMemoryDto>) {
        if (memories.isEmpty()) {
            memoryText.text = getString(R.string.schedule_review_memory_empty)
            return
        }
        memoryText.text = memories.joinToString("\n\n") { memory ->
            "${memoryKindLabel(memory.kind)} · ${"★".repeat(memory.importance)}\n${memory.content}"
        }
    }

    private fun confirmClearMemories() {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_review_memory_clear_title)
            .setMessage(R.string.schedule_review_memory_clear_message)
            .setPositiveButton(R.string.schedule_review_memory_clear) { _, _ -> clearMemories() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearMemories() {
        activityScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ReviewNetworkModule.api.clearMemories(getOrCreateUserId())
                }
                Toast.makeText(
                    this@ScheduleReviewActivity,
                    getString(R.string.schedule_review_memory_cleared, response.deleted),
                    Toast.LENGTH_SHORT
                ).show()
                loadMemories()
            } catch (exception: Exception) {
                Toast.makeText(
                    this@ScheduleReviewActivity,
                    readableError(exception),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun getOrCreateUserId(): String {
        val prefs = getSharedPreferences(REVIEW_IDENTITY_PREFS, MODE_PRIVATE)
        val existing = prefs.getString(KEY_USER_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_USER_ID, created).apply()
        return created
    }

    private fun toIsoTime(millis: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return formatter.format(Date(millis))
    }

    private fun splitGoals(text: String): List<String> {
        return text.split('\n', ',', '，', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(10)
    }

    private fun personalityTone(personality: String): String {
        return when {
            personality.contains("理性") -> "理性、结构清晰、重视事实和下一步行动"
            personality.contains("直接") -> "直接、简洁、敢于指出问题但不羞辱用户"
            personality.contains("成长") -> "鼓励成长、关注长期规律、强调小步迭代"
            else -> "温暖、共情、轻柔且具体，不说教"
        }
    }

    private fun memoryKindLabel(kind: String): String {
        return when (kind) {
            "preference" -> getString(R.string.schedule_review_memory_preference)
            "habit" -> getString(R.string.schedule_review_memory_habit)
            "challenge" -> getString(R.string.schedule_review_memory_challenge)
            "effective_strategy" -> getString(R.string.schedule_review_memory_strategy)
            "goal" -> getString(R.string.schedule_review_memory_goal)
            "energy_pattern" -> getString(R.string.schedule_review_memory_energy)
            else -> getString(R.string.schedule_review_memory_pattern)
        }
    }

    private fun readableError(exception: Exception): String {
        return when (exception) {
            is HttpException -> "HTTP ${exception.code()} ${exception.message()}"
            else -> exception.localizedMessage ?: exception.javaClass.simpleName
        }
    }

    companion object {
        private const val REVIEW_IDENTITY_PREFS = "shineflow_review_identity"
        private const val KEY_USER_ID = "review_user_id"
        const val KEY_AGENT_NAME = "review_agent_name"
        const val KEY_AGENT_PERSONALITY = "review_agent_personality"
        const val KEY_AGENT_GOALS = "review_agent_goals"
        private const val DEFAULT_AGENT_NAME = "Shine"
        private const val DEFAULT_PERSONALITY = "温柔、有行动力的成长伙伴"
    }
}
