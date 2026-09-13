package nl.rogro82.pipup

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * URLs fired by the launcher trigger entries (see [TriggerActivity]).
 *
 * A popup's camera URL carries a token that rotates, so a trigger cannot show a
 * popup by itself -- it calls back to whatever built the popup (in practice a
 * Home Assistant webhook), which then posts a fresh /notify.
 *
 * Configured over HTTP rather than on screen: entering a URL with a TV remote is
 * miserable, and whatever drives PiPup can set it at the same time it sets
 * everything else up.
 */
object Triggers {
    const val LOG_TAG = "PiPupTriggers"
    private const val PREFS = "pipup_triggers"
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 8000

    fun all(context: Context): Map<String, String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .all.entries.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun get(context: Context, slot: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(slot, null)

    /** Replaces the whole set; a null or empty value clears that slot. */
    fun replaceAll(context: Context, json: JSONObject) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        for (key in json.keys()) {
            val value = json.optString(key, "")
            if (value.isNotEmpty()) editor.putString(key.lowercase(), value)
        }
        editor.apply()
    }

    fun fire(url: String, method: String = "POST") {
        Thread({
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    if (method != "GET") {
                        doOutput = true
                        outputStream.use { it.write(ByteArray(0)) }
                    }
                }
                Log.d(LOG_TAG, "$method $url -> ${connection.responseCode}")
            } catch (ex: Throwable) {
                Log.e(LOG_TAG, "$method $url failed: ${ex.message}")
            } finally {
                try { connection?.disconnect() } catch (_: Throwable) {}
            }
        }, "pipup-trigger").apply { isDaemon = true }.start()
    }
}
