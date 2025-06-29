package com.example.silentcalendar

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.text.Html
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var currentEventView: TextView
    private lateinit var pastEventsView: TextView
    private lateinit var upcomingEventsView: TextView
    private lateinit var labelCurrent: TextView
    private lateinit var labelPast: TextView
    private lateinit var labelUpcoming: TextView

    // NEW: Added views
    private lateinit var currentDateView: TextView
    private lateinit var footerNoteView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 10_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind UI elements
        statusText = findViewById(R.id.textStatus)
        currentEventView = findViewById(R.id.currentEventView)
        pastEventsView = findViewById(R.id.pastEventsView)
        upcomingEventsView = findViewById(R.id.upcomingEventsView)
        labelCurrent = findViewById(R.id.labelCurrent)
        labelPast = findViewById(R.id.labelPast)
        labelUpcoming = findViewById(R.id.labelUpcoming)

        // NEW: Bind new UI elements
        currentDateView = findViewById(R.id.currentDate)
        footerNoteView = findViewById(R.id.footerNote)

        // NEW: Set current date dynamically
        val today = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault()).format(Date())
        currentDateView.text = today

        // NEW: Bold TechnoHub and ChatGPT in footer
        footerNoteView.text = Html.fromHtml(
            "this application is AI generated for <b>TechnoHub</b> with support of <b>ChatGPT</b>",
            Html.FROM_HTML_MODE_LEGACY
        )

        // Request calendar permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALENDAR), 100)
        }

        // Request DND access
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Please allow Do Not Disturb access", Toast.LENGTH_LONG).show()
        }

        // Schedule worker every 5 min
        val workRequest = PeriodicWorkRequestBuilder<SilentModeWorker>(5, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueue(workRequest)
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateUIRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateUIRunnable)
    }

    private val updateUIRunnable = object : Runnable {
        override fun run() {
            updateRingerStatus()
            displayTodayEvents()
            handler.postDelayed(this, refreshInterval)
        }
    }

    private fun updateRingerStatus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            AudioManager.RINGER_MODE_NORMAL -> "Ringing"
            else -> "Unknown"
        }
        statusText.text = "Current Mode: $mode"
    }

    private fun displayTodayEvents() {
        val now = System.currentTimeMillis()

        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val endOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY
        )

        val selection = "(${CalendarContract.Events.DTSTART} >= ?) AND (${CalendarContract.Events.DTSTART} <= ?) AND (${CalendarContract.Events.ALL_DAY} != 1)"
        val selectionArgs = arrayOf(startOfDay.toString(), endOfDay.toString())

        val events = mutableListOf<CalendarEvent>()

        val cursor = contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC"
        )

        cursor?.use {
            val titleIndex = it.getColumnIndex(CalendarContract.Events.TITLE)
            val startIndex = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIndex = it.getColumnIndex(CalendarContract.Events.DTEND)

            while (it.moveToNext()) {
                val title = it.getString(titleIndex) ?: "Untitled"
                val start = it.getLong(startIndex)
                val end = it.getLong(endIndex)
                events.add(CalendarEvent(title, start, end))
            }
        }

        val currentTime = System.currentTimeMillis()
        val past = events.filter { it.end < currentTime }
        val current = events.filter { it.start <= currentTime && it.end >= currentTime }
        val upcoming = events.filter { it.start > currentTime }

        if (past.isNotEmpty()) {
            labelPast.visibility = TextView.VISIBLE
            pastEventsView.visibility = TextView.VISIBLE
            pastEventsView.text = past.joinToString("\n") {
                "• ${it.title} (${formatTime(it.start)}–${formatTime(it.end)})"
            }
        } else {
            labelPast.visibility = TextView.GONE
            pastEventsView.visibility = TextView.GONE
        }

        if (current.isNotEmpty()) {
            labelCurrent.visibility = TextView.VISIBLE
            currentEventView.visibility = TextView.VISIBLE
            currentEventView.text = current.joinToString("\n") {
                "• ${it.title} (${formatTime(it.start)}–${formatTime(it.end)})"
            }
            forceDNDIfNeeded()
        } else {
            labelCurrent.visibility = TextView.GONE
            currentEventView.visibility = TextView.GONE
        }

        if (upcoming.isNotEmpty()) {
            labelUpcoming.visibility = TextView.VISIBLE
            upcomingEventsView.visibility = TextView.VISIBLE
            upcomingEventsView.text = upcoming.joinToString("\n") {
                val timeLeft = it.start - currentTime
                val hours = timeLeft / 3600000
                val minutes = (timeLeft % 3600000) / 60000
                val timeStr = buildString {
                    if (hours > 0) append("${hours}h ")
                    append("${minutes}min")
                }
                "• ${it.title} (${formatTime(it.start)}–${formatTime(it.end)}) → in $timeStr"
            }
        } else {
            labelUpcoming.visibility = TextView.GONE
            upcomingEventsView.visibility = TextView.GONE
        }
    }

    private fun forceDNDIfNeeded() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.isNotificationPolicyAccessGranted) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(ms))
    }

    data class CalendarEvent(val title: String, val start: Long, val end: Long)
}
