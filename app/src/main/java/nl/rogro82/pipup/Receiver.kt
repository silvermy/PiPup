package nl.rogro82.pipup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class Receiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(LOG_TAG, "received ${intent.action}")

        ServiceStarter.scheduleWatchdog(context)
        ServiceStarter.start(context)
    }

    companion object {
        const val LOG_TAG = "PiPupReceiver"
    }
}
