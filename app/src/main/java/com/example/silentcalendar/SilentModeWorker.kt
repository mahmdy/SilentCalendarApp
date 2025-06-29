package com.example.silentcalendar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.CalendarContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.*

class SilentModeWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    private val CHANNEL_ID = "SilentCalendarChannel"
    private val TAG = "SilentModeWorker"

    override fun doWork(): Result {
        val context = applicationContext

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure DND access is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !notificationManager.isNotificationPolicyAccessGranted) {
            Log.w(TAG, "DND permission not granted")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val buffer = 10 * 60 * 1000 // 10 minutes in ms

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )

        val selection = "(${CalendarContract.Instances.END} >= ?) AND (${CalendarContract.Instances.BEGIN} <= ?)"
        val selectionArgs = arrayOf((now - buffer).toString(), (now + buffer).toString())

        val uri = CalendarContract.Instances.CONTENT_URI
        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)

        var shouldBeSilent = false
        var matchedTitle: String? = null

        cursor?.use {
            while (it.moveToNext()) {
                val title = it.getString(0)
                val begin = it.getLong(1) - buffer
                val end = it.getLong(2) + buffer
                val allDay = it.getInt(3)

                if (allDay == 0 && now in begin..end) {
                    shouldBeSilent = true
                    matchedTitle = title
                    break
                }
            }
        }

        val currentMode = audioManager.ringerMode
        Log.d(TAG, "Current mode: $currentMode, Should be silent: $shouldBeSilent")

        if (shouldBeSilent && currentMode != AudioManager.RINGER_MODE_SILENT) {
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            sendNotification(context, notificationManager, "Meeting Mode", "Phone silenced for \"$matchedTitle\"")
            Log.i(TAG, "Phone silenced for: $matchedTitle")
        } else if (!shouldBeSilent && currentMode == AudioManager.RINGER_MODE_SILENT) {
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            sendNotification(context, notificationManager, "Ringing Restored", "Phone is back to ringing mode.")
            Log.i(TAG, "Phone returned to ringing mode.")
        }

        return Result.success()
    }

    private fun sendNotification(context: Context, manager: NotificationManager, title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Silent Calendar Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify((0..9999).random(), notification)
    }
}
