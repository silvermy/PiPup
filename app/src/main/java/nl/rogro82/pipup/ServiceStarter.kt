package nl.rogro82.pipup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Everything related to getting (and keeping) [PiPupService] running.
 *
 * Relying on BOOT_COMPLETED alone is fragile on Android TV: the broadcast can be
 * missed entirely when the TV suspends instead of shutting down, the app can be
 * in the stopped state after a crash, and some OEM firmwares drop background
 * starts. The persisted periodic job below is the backstop -- JobScheduler keeps
 * it across reboots by itself, so even if every broadcast is missed the service
 * comes back within one period.
 */
object ServiceStarter {
    private const val LOG_TAG = "PiPupStarter"
    private const val WATCHDOG_JOB_ID = 7979

    /** JobScheduler clamps periodic jobs to 15 minutes anyway. */
    private const val WATCHDOG_PERIOD_MS = 15L * 60L * 1000L

    fun start(context: Context) {
        val intent = Intent(context.applicationContext, PiPupService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        } catch (ex: Throwable) {
            // Starting a foreground service from the background can be refused on
            // some firmwares; never let that take the caller down with it.
            Log.w(LOG_TAG, "could not start service: ${ex.message}")
        }
    }

    /** Idempotent: re-scheduling a periodic job would restart its period. */
    fun scheduleWatchdog(context: Context) {
        try {
            val scheduler = context.applicationContext
                .getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return

            if (scheduler.getPendingJob(WATCHDOG_JOB_ID) != null) return

            val job = JobInfo.Builder(
                WATCHDOG_JOB_ID,
                ComponentName(context.applicationContext, WatchdogJobService::class.java)
            )
                .setPersisted(true)
                .setPeriodic(WATCHDOG_PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()

            val result = scheduler.schedule(job)
            Log.d(LOG_TAG, "watchdog scheduled: $result")
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "could not schedule watchdog: ${ex.message}")
        }
    }
}
