package nl.rogro82.pipup

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/**
 * Fires every ~15 minutes (and after every reboot, because the job is persisted)
 * and makes sure the service is up. [PiPupService.onStartCommand] also re-binds
 * the webserver if the socket died, so this doubles as a port watchdog.
 */
class WatchdogJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(LOG_TAG, "watchdog tick (server alive: ${PiPupService.isServerAlive()})")
        ServiceStarter.start(applicationContext)
        return false // work is done synchronously
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        const val LOG_TAG = "PiPupWatchdog"
    }
}
