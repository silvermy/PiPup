package nl.rogro82.pipup

import org.json.JSONObject

data class PopupProps(
    val duration: Int = DEFAULT_DURATION,
    val position: Position = DEFAULT_POSITION,
    val backgroundColor: String = DEFAULT_BACKGROUND_COLOR,
    val title: String? = null,
    val titleSize: Float = DEFAULT_TITLE_SIZE,
    val titleColor: String = DEFAULT_TITLE_COLOR,
    val message: String? = null,
    val messageSize: Float = DEFAULT_MESSAGE_SIZE,
    val messageColor: String = DEFAULT_MESSAGE_COLOR,
    val media: Media? = null,

    /**
     * How the popup treats the audio of whatever is already playing.
     * Defaults to [AudioFocus.None] + muted, so a notification never interrupts
     * the show that is running.
     */
    val audioFocus: AudioFocus = DEFAULT_AUDIO_FOCUS,
    val volume: Float = DEFAULT_VOLUME,

    /**
     * Prefer a software video decoder for the popup. Most TV boxes expose only
     * one or two hardware AVC/HEVC decoder instances; when a streaming app holds
     * them, a hardware-decoded popup fails to start. A 480px popup decodes in
     * software for almost nothing.
     */
    val preferSoftwareDecoder: Boolean = DEFAULT_PREFER_SOFTWARE_DECODER,

    /** Loop short clips for the whole [duration] instead of freezing on the last frame. */
    val loop: Boolean = DEFAULT_LOOP,

    /**
     * Remote keys that dismiss this popup, as Android key names ("BACK",
     * "DPAD_CENTER", ...), or [DISMISS_ANY] for "any key". Empty means the
     * popup is not interactive at all, which is the default: receiving keys
     * requires taking focus, and a focused overlay stops the remote reaching
     * whatever app is playing underneath.
     */
    val dismissKeys: Set<String> = emptySet()
) {
    val interactive: Boolean get() = dismissKeys.isNotEmpty()

    sealed class Media {
        abstract val width: Int

        data class Video(val uri: String, override val width: Int = DEFAULT_MEDIA_WIDTH) : Media()
        data class Image(val uri: String, override val width: Int = DEFAULT_MEDIA_WIDTH) : Media()

        /** multipart/x-mixed-replace MJPEG: no video decoder involved at all. */
        data class Mjpeg(val uri: String, override val width: Int = DEFAULT_MEDIA_WIDTH) : Media()

        data class Web(
            val uri: String,
            override val width: Int = DEFAULT_WEB_WIDTH,
            val height: Int = DEFAULT_WEB_HEIGHT
        ) : Media()

        /** Produced by the multipart/form-data upload path, never by JSON. */
        data class Bitmap(
            val image: android.graphics.Bitmap,
            override val width: Int = DEFAULT_MEDIA_WIDTH
        ) : Media()
    }

    enum class Position {
        TopRight,
        TopLeft,
        BottomRight,
        BottomLeft,
        Center;

        companion object {
            fun fromIndex(index: Int?): Position =
                values().getOrNull(index ?: -1) ?: PopupProps.DEFAULT_POSITION

            fun parse(value: Any?): Position = when (value) {
                null -> PopupProps.DEFAULT_POSITION
                is Number -> fromIndex(value.toInt())
                else -> value.toString().let { s ->
                    s.toIntOrNull()?.let { fromIndex(it) }
                        ?: values().firstOrNull { it.name.equals(s, ignoreCase = true) }
                        ?: PopupProps.DEFAULT_POSITION
                }
            }
        }
    }

    enum class AudioFocus {
        /** Play silently alongside whatever is running (default). */
        None,

        /** Ask the running app to lower its volume while the popup plays. */
        Duck,

        /** Ask the running app to pause while the popup plays. */
        Pause;

        companion object {
            fun parse(value: String?): AudioFocus =
                values().firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: PopupProps.DEFAULT_AUDIO_FOCUS
        }
    }

    companion object {
        const val DEFAULT_DURATION: Int = 30
        const val DEFAULT_BACKGROUND_COLOR = "#CC000000"
        const val DEFAULT_TITLE_SIZE = 16f
        const val DEFAULT_TITLE_COLOR = "#ffffff"
        const val DEFAULT_MESSAGE_SIZE = 12f
        const val DEFAULT_MESSAGE_COLOR = "#ffffff"
        const val DEFAULT_MEDIA_WIDTH = 480
        const val DEFAULT_WEB_WIDTH = 640
        const val DEFAULT_WEB_HEIGHT = 480
        const val DEFAULT_VOLUME = 0f
        const val DEFAULT_PREFER_SOFTWARE_DECODER = true
        const val DEFAULT_LOOP = true

        /** Sentinel in [dismissKeys] meaning "any key dismisses". */
        const val DISMISS_ANY = "ANY"

        val DEFAULT_POSITION: Position = Position.TopRight
        val DEFAULT_AUDIO_FOCUS: AudioFocus = AudioFocus.None

        /**
         * Hand-rolled so the app carries no reflective JSON mapper. Unknown keys
         * are ignored and every field is optional, matching the previous
         * behaviour of the Jackson-based parser.
         */
        fun fromJson(body: String): PopupProps {
            val json = JSONObject(body)

            return PopupProps(
                duration = json.optIntOrNull("duration") ?: DEFAULT_DURATION,
                position = Position.parse(json.opt("position")),
                backgroundColor = json.optStringOrNull("backgroundColor") ?: DEFAULT_BACKGROUND_COLOR,
                title = json.optStringOrNull("title"),
                titleSize = json.optFloatOrNull("titleSize") ?: DEFAULT_TITLE_SIZE,
                titleColor = json.optStringOrNull("titleColor") ?: DEFAULT_TITLE_COLOR,
                message = json.optStringOrNull("message"),
                messageSize = json.optFloatOrNull("messageSize") ?: DEFAULT_MESSAGE_SIZE,
                messageColor = json.optStringOrNull("messageColor") ?: DEFAULT_MESSAGE_COLOR,
                media = parseMedia(json.optJSONObject("media")),
                audioFocus = AudioFocus.parse(json.optStringOrNull("audioFocus")),
                volume = (json.optFloatOrNull("volume") ?: DEFAULT_VOLUME).coerceIn(0f, 1f),
                preferSoftwareDecoder = json.optBooleanOrNull("preferSoftwareDecoder")
                    ?: DEFAULT_PREFER_SOFTWARE_DECODER,
                loop = json.optBooleanOrNull("loop") ?: DEFAULT_LOOP,
                dismissKeys = parseDismissKeys(json.opt("dismissOnKey"))
            )
        }

        /** Accepts `true` (any key) or a list of key names. */
        private fun parseDismissKeys(value: Any?): Set<String> = when (value) {
            null, false, JSONObject.NULL -> emptySet()
            true -> setOf(DISMISS_ANY)
            is org.json.JSONArray -> (0 until value.length())
                .mapNotNull { value.optString(it, "").takeIf { s -> s.isNotEmpty() } }
                .map { it.trim().uppercase(java.util.Locale.US) }
                .toSet()
            else -> value.toString().trim().uppercase(java.util.Locale.US)
                .takeIf { it.isNotEmpty() }?.let { setOf(it) } ?: emptySet()
        }

        private fun parseMedia(media: JSONObject?): Media? {
            if (media == null) return null

            media.optJSONObject("video")?.let {
                val uri = it.optStringOrNull("uri") ?: return null
                return Media.Video(uri, it.optIntOrNull("width") ?: DEFAULT_MEDIA_WIDTH)
            }
            media.optJSONObject("mjpeg")?.let {
                val uri = it.optStringOrNull("uri") ?: return null
                return Media.Mjpeg(uri, it.optIntOrNull("width") ?: DEFAULT_MEDIA_WIDTH)
            }
            media.optJSONObject("image")?.let {
                val uri = it.optStringOrNull("uri") ?: return null
                return Media.Image(uri, it.optIntOrNull("width") ?: DEFAULT_MEDIA_WIDTH)
            }
            media.optJSONObject("web")?.let {
                val uri = it.optStringOrNull("uri") ?: return null
                return Media.Web(
                    uri,
                    it.optIntOrNull("width") ?: DEFAULT_WEB_WIDTH,
                    it.optIntOrNull("height") ?: DEFAULT_WEB_HEIGHT
                )
            }
            return null
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (isNull(key)) null else opt(key)?.let {
                (it as? Number)?.toInt() ?: it.toString().toIntOrNull()
            }

        private fun JSONObject.optFloatOrNull(key: String): Float? =
            if (isNull(key)) null else opt(key)?.let {
                (it as? Number)?.toFloat() ?: it.toString().toFloatOrNull()
            }

        private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
            if (isNull(key)) null else opt(key)?.let {
                (it as? Boolean) ?: it.toString().toBooleanStrictOrNull()
            }
    }
}
