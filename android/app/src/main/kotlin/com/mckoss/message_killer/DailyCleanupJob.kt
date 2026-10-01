package com.mckoss.message_killer

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Runs about once a day: purges expired Spam folder entries, files new political
 * texts from the inbox, and posts a "ready to delete" notification. Deleting
 * itself needs a tap, because Android requires the user to approve the default
 * SMS app switch.
 */
class DailyCleanupJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                val result = Cleanup.scan(this)
                Log.i("MessageKiller", "Daily scan: ${result.scanned} scanned, ${result.newlyFiled} new, ${result.pending} pending")
                if (result.pending > 0) Notifications.showCleanupReady(this, result.pending)
            } catch (e: Exception) {
                Log.w("MessageKiller", "Daily scan failed", e)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4242

        fun schedule(context: Context, enabled: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!enabled) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, DailyCleanupJob::class.java))
                    .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(2))
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
