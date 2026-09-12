package nl.rogro82.pipup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Replaces Glide. The previous code disabled both Glide's disk and memory cache,
 * so nothing was gained from the dependency -- this is the same behaviour in a
 * fraction of the bytes, with sub-sampling so a 4K camera snapshot does not get
 * decoded at full size just to be drawn 480px wide.
 */
object ImageLoader {
    const val LOG_TAG = "ImageLoader"
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 8000

    fun load(
        url: String,
        targetWidth: Int,
        headers: Map<String, String> = emptyMap(),
        onLoaded: (Bitmap) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        Thread({
            try {
                val bytes = fetch(url, headers)

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, targetWidth)
                }

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: throw IllegalStateException("could not decode image from $url")

                onLoaded(bitmap)
            } catch (ex: Throwable) {
                Log.w(LOG_TAG, "image load failed: ${ex.message}")
                onError(ex)
            }
        }, "image-loader").apply {
            isDaemon = true
            start()
        }
    }

    private fun fetch(url: String, headers: Map<String, String>): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode} from $url")
            }
            val out = ByteArrayOutputStream()
            connection.inputStream.use { it.copyTo(out) }
            return out.toByteArray()
        } finally {
            try {
                connection.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun sampleSizeFor(sourceWidth: Int, targetWidth: Int): Int {
        if (sourceWidth <= 0 || targetWidth <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }
}
