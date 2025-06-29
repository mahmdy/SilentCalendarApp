package com.example.silentcalendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            // Schedule the SilentModeWorker every 5 minutes after boot
            val workRequest = PeriodicWorkRequestBuilder<SilentModeWorker>(
                5, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "SilentCalendarWorker",
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
        }
    }
}
