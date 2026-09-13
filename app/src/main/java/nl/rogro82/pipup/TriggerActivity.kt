package nl.rogro82.pipup

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Invisible launcher entry: fires its configured URL and finishes immediately.
 *
 * It exists as several launcher aliases (TriggerDungeon, TriggerDoorbell, ...)
 * so a key-mapper app can bind an ordinary "launch app" action to each one --
 * that is the lowest common denominator those apps all support, and it needs no
 * intent extras. Which alias was launched selects the slot.
 */
class TriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val slot = componentName.className
            .substringAfterLast('.')
            .removePrefix("Trigger")
            .lowercase()

        when (val url = Triggers.get(this, slot)) {
            null -> Log.w(LOG_TAG, "trigger '$slot' has no URL; set it with POST /triggers")
            else -> {
                Log.d(LOG_TAG, "trigger '$slot' firing")
                Triggers.fire(url)
            }
        }

        // windowNoDisplay requires finishing before anything would be drawn.
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val LOG_TAG = "PiPupTrigger"
    }
}
