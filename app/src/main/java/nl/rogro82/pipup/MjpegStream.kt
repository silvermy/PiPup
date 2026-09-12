package nl.rogro82.pipup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal reader for multipart/x-mixed-replace MJPEG streams (what Home
 * Assistant's /api/camera_proxy_stream/<entity> serves).
 *
 * This exists because it sidesteps the two things that make a video popup slow
 * and unreliable on a TV: there is no hardware decoder to contend for, and there
 * is no container/manifest to probe -- the first JPEG that arrives is the first
 * frame shown, typically well under a second.
 *
 * Frames are delivered on the reader thread; callers marshal to the UI thread.
 */
class MjpegStream(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val onFrame: (Bitmap) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ read() }, "mjpeg-stream").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun read() {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                doInput = true
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            connection.connect()

            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode} from $url")
            }

            val options = BitmapFactory.Options().apply {
                // Popups are small; RGB_565 halves the per-frame allocation and
                // keeps the GC quiet at 10-25 fps.
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                while (running && !Thread.currentThread().isInterrupted) {
                    val jpeg = readNextJpeg(input) ?: break
                    if (!running) break
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
                        ?: continue
                    onFrame(bitmap)
                }
            }
        } catch (ex: Throwable) {
            if (running) {
                Log.w(LOG_TAG, "mjpeg stream failed: ${ex.message}")
                onError(ex)
            }
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Scans for the JPEG SOI/EOI markers rather than parsing the multipart
     * boundary; that works across the many slightly-different MJPEG servers out
     * there without needing their headers to be well formed.
     */
    private fun readNextJpeg(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream(INITIAL_FRAME_SIZE)

        var previous = -1
        while (true) {
            val current = input.read()
            if (current < 0) return null
            if (previous == 0xFF && current == 0xD8) {
                out.write(0xFF)
                out.write(0xD8)
                break
            }
            previous = current
        }

        previous = -1
        while (true) {
            val current = input.read()
            if (current < 0) return null
            out.write(current)
            if (previous == 0xFF && current == 0xD9) return out.toByteArray()
            previous = current

            if (out.size() > MAX_FRAME_SIZE) {
                throw IllegalStateException("mjpeg frame exceeded $MAX_FRAME_SIZE bytes")
            }
        }
    }

    companion object {
        const val LOG_TAG = "MjpegStream"
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val READ_TIMEOUT_MS = 15000
        private const val BUFFER_SIZE = 32 * 1024
        private const val INITIAL_FRAME_SIZE = 64 * 1024
        private const val MAX_FRAME_SIZE = 16 * 1024 * 1024
    }
}
