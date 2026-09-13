package nl.rogro82.pipup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import org.json.JSONObject
import java.io.File
import java.util.Locale

@OptIn(UnstableApi::class)
class PiPupService : Service(), WebServer.Handler {

    private val mHandler: Handler = Handler(Looper.getMainLooper())
    private var mOverlay: OverlayView? = null

    /** Whether the overlay window currently holds focus, so flags only change when needed. */
    private var mOverlayInteractive: Boolean = false
    private var mPopup: PopupView? = null
    private var mWebServer: WebServer? = null

    /** Shared, kept warm across popups. See [player]. */
    private var mPlayer: ExoPlayer? = null

    /**
     * Read by the codec selector at prepare() time, so it can follow the most
     * recent request without rebuilding the player.
     */
    @Volatile
    private var mPreferSoftwareDecoder: Boolean = PopupProps.DEFAULT_PREFER_SOFTWARE_DECODER

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // The old manifest listened for CONNECTIVITY_CHANGE, which Android 8
            // stopped delivering to manifest receivers. This is the replacement,
            // and it exists to re-bind the socket if the server died while the
            // network was down.
            mHandler.post { ensureWebServer() }
        }
    }

    override fun onCreate() {
        super.onCreate()

        initNotificationChannel(CHANNEL_ID, "PiPup service", "Keeps the PiPup server running")
        startForeground(ONGOING_NOTIFICATION_ID, buildNotification())

        ensureWebServer()
        registerNetworkCallback()

        // Build the player up front so the first notification does not pay for it.
        // An idle ExoPlayer holds no codec, so this is free while nothing is shown.
        mHandler.post { player() }

        ServiceStarter.scheduleWatchdog(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "error unregistering network callback: ${ex.message}")
        }

        removePopup(removeOverlay = true)

        mPlayer?.release()
        mPlayer = null

        mWebServer?.stop()
        mWebServer = null
        serverAlive = false
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Called by the boot receiver and by every watchdog tick; cheap when the
        // server is already up, and the thing that revives it when it is not.
        ensureWebServer()
        return START_STICKY
    }

    // region server

    /**
     * Starts the webserver, retrying a few times. At boot the previous process's
     * socket can still be in TIME_WAIT, and the original code simply let the
     * bind exception escape -- leaving a running service with no listener and no
     * way to notice.
     */
    private fun ensureWebServer() {
        if (mWebServer?.isAlive == true) {
            serverAlive = true
            return
        }

        try {
            mWebServer?.stop()
        } catch (_: Throwable) {
        }
        mWebServer = null
        serverAlive = false

        for (attempt in 1..SERVER_START_ATTEMPTS) {
            try {
                mWebServer = WebServer(SERVER_PORT, this).apply {
                    start(NanoHTTPD.SOCKET_READ_TIMEOUT, /* daemon = */ false)
                }
                serverAlive = true
                Log.d(LOG_TAG, "WebServer started on port $SERVER_PORT (attempt $attempt)")
                return
            } catch (ex: Throwable) {
                Log.w(LOG_TAG, "WebServer start attempt $attempt failed: ${ex.message}")
                try {
                    Thread.sleep(SERVER_RETRY_DELAY_MS * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }

        Log.e(LOG_TAG, "WebServer could not be started; watchdog will retry")
    }

    private fun registerNetworkCallback() {
        try {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "could not register network callback: ${ex.message}")
        }
    }

    // endregion

    // region player

    /**
     * One player for the lifetime of the service.
     *
     * The decoder selector is the fix for "nothing plays while an app is already
     * playing": TV boxes typically expose a single hardware AVC/HEVC decoder
     * instance, and whichever app got there first keeps it. Preferring a software
     * decoder for the popup avoids the contention entirely, and decoder fallback
     * covers the case where the preferred one still fails to configure.
     */
    private fun player(): ExoPlayer = mPlayer ?: buildPlayer().also { mPlayer = it }

    private fun buildPlayer(): ExoPlayer {
        val codecSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val decoders =
                MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            val ordered = if (mPreferSoftwareDecoder) {
                decoders.sortedByDescending { isSoftwareDecoder(it) }
            } else {
                decoders
            }
            Log.d(
                LOG_TAG,
                "codecs for $mimeType (preferSoftware=$mPreferSoftwareDecoder): " +
                    ordered.joinToString { "${it.name}${if (isSoftwareDecoder(it)) "(sw)" else ""}" }
            )
            ordered
        }

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector)

        // Small buffers: a notification wants to be on screen now, and a few
        // hundred milliseconds of jitter protection is plenty for a clip that
        // plays for a handful of seconds.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 500,
                /* maxBufferMs = */ 5000,
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpDataSourceFactory))

        return ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    /**
     * media3's own [MediaCodecInfo.softwareOnly] is derived from the platform's
     * isSoftwareOnly() on API 29+, and this Sony/MediaTek firmware reports false
     * for OMX.google.h264.decoder -- which made preferSoftwareDecoder a no-op.
     * Fall back to the name convention media3 itself uses on older API levels.
     */
    private fun isSoftwareDecoder(info: MediaCodecInfo): Boolean {
        if (info.softwareOnly) return true
        val name = info.name.lowercase(Locale.US)
        return SOFTWARE_DECODER_PREFIXES.any { name.startsWith(it) }
    }

    // endregion

    // region popup

    private fun buildNotification(): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PiPup")
            .setContentText("Listening on port $SERVER_PORT")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
    }

    private fun initNotificationChannel(id: String, name: String, description: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // IMPORTANCE_LOW: the old channel used DEFAULT, which makes some TV
        // firmwares play a sound when the service starts.
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply {
            this.description = description
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun removePopup(removeOverlay: Boolean = false) {
        mHandler.removeCallbacksAndMessages(null)

        mPopup?.destroy()
        mPopup = null

        mOverlay?.apply {
            removeAllViews()
            if (removeOverlay) {
                try {
                    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    windowManager.removeViewImmediate(this)
                } catch (ex: Throwable) {
                    Log.w(LOG_TAG, "error removing overlay: ${ex.message}")
                }
                mOverlay = null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createPopup(popup: PopupProps) {
        try {
            Log.d(LOG_TAG, "Create popup: $popup")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                // Previously this failed silently somewhere inside WindowManager.
                Log.e(
                    LOG_TAG,
                    "SYSTEM_ALERT_WINDOW not granted -- run: " +
                        "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
                )
                return
            }

            mPreferSoftwareDecoder = popup.preferSoftwareDecoder

            removePopup()

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            mOverlay = (mOverlay ?: OverlayView(this).apply {
                setPadding(20, 20, 20, 20)
                windowManager.addView(this, overlayParams(popup.interactive))
                mOverlayInteractive = popup.interactive
            }).also { overlay ->

                // The overlay is reused between popups, so focusability has to
                // follow whichever popup is showing now.
                if (mOverlayInteractive != popup.interactive) {
                    windowManager.updateViewLayout(overlay, overlayParams(popup.interactive))
                    mOverlayInteractive = popup.interactive
                }

                overlay.onKey = if (!popup.interactive) null else { event ->
                    val action = actionFor(popup, event.keyCode)
                    if (action == null) false else {
                        // Consume both down and up so no stray event escapes,
                        // but act once, on release.
                        if (event.action == KeyEvent.ACTION_UP) {
                            mHandler.post { runKeyAction(popup, action) }
                        }
                        true
                    }
                }

                if (popup.interactive) {
                    overlay.isFocusableInTouchMode = true
                    overlay.isFocusable = true
                    overlay.requestFocus()
                }

                overlay.visibility = View.VISIBLE

                mPopup = PopupView.build(this, popup, player())

                overlay.addView(
                    mPopup,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = when (popup.position) {
                            PopupProps.Position.TopRight -> Gravity.TOP or Gravity.END
                            PopupProps.Position.TopLeft -> Gravity.TOP or Gravity.START
                            PopupProps.Position.BottomRight -> Gravity.BOTTOM or Gravity.END
                            PopupProps.Position.BottomLeft -> Gravity.BOTTOM or Gravity.START
                            PopupProps.Position.Center -> Gravity.CENTER
                        }
                    }
                )
            }

            if (popup.duration > 0) {
                mHandler.postDelayed({ removePopup(true) }, popup.duration * 1000L)
            }
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "error creating popup: ${ex.message}", ex)
        }
    }

    @Suppress("DEPRECATION")
    private fun overlayParams(interactive: Boolean): WindowManager.LayoutParams {
        val layoutFlags = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> WindowManager.LayoutParams.TYPE_TOAST
        }

        // NOT_TOUCHABLE always: the overlay must never swallow touches meant for
        // the app underneath. NOT_FOCUSABLE only for non-interactive popups --
        // dropping it is what lets remote keys reach the window, at the cost of
        // taking the remote away from that app while the popup is up.
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (!interactive) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlags,
            flags,
            PixelFormat.TRANSLUCENT
        )
    }

    /** Accepts either "BACK" or "KEYCODE_BACK" in the payload. */
    private fun actionFor(popup: PopupProps, keyCode: Int): PopupProps.KeyAction? {
        val name = KeyEvent.keyCodeToString(keyCode)
        val short = name.removePrefix("KEYCODE_")

        popup.keys[name]?.let { return it }
        popup.keys[short]?.let { return it }

        val dismissAll = popup.dismissKeys.contains(PopupProps.DISMISS_ANY)
        if (dismissAll || popup.dismissKeys.contains(name) || popup.dismissKeys.contains(short)) {
            return PopupProps.KeyAction.Dismiss
        }
        return null
    }

    private fun runKeyAction(popup: PopupProps, action: PopupProps.KeyAction) {
        Log.d(LOG_TAG, "key action: $action")
        when (action) {
            is PopupProps.KeyAction.Dismiss -> removePopup(true)

            is PopupProps.KeyAction.ReleaseFocus -> releaseOverlayFocus()

            is PopupProps.KeyAction.Launch -> {
                launchApp(action.packageName)
                // The popup would otherwise sit on top of the app just opened.
                removePopup(true)
            }

            is PopupProps.KeyAction.Fetch -> fetchAsync(action.url, action.method)

            // Rebuild through the normal path so sizing, the shared player and
            // the duration timer all behave exactly as for a fresh popup.
            is PopupProps.KeyAction.ShowMedia -> createPopup(popup.copy(media = action.media))
        }
    }

    /** Keeps the popup up but hands the remote back to the app underneath. */
    private fun releaseOverlayFocus() {
        val overlay = mOverlay ?: return
        if (!mOverlayInteractive) return
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.updateViewLayout(overlay, overlayParams(interactive = false))
            mOverlayInteractive = false
            overlay.onKey = null
            Log.d(LOG_TAG, "overlay focus released")
        } catch (ex: Throwable) {
            Log.w(LOG_TAG, "could not release focus: ${ex.message}")
        }
    }

    /**
     * Background activity starts are blocked from API 29, but holding
     * SYSTEM_ALERT_WINDOW is an explicit exemption -- which PiPup needs anyway.
     */
    private fun launchApp(packageName: String) {
        try {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                Log.e(LOG_TAG, "no launch intent for '$packageName' (installed?)")
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "could not launch '$packageName': ${ex.message}")
        }
    }

    /** Fire-and-forget; the response body is irrelevant for a webhook. */
    private fun fetchAsync(url: String, method: String) {
        Thread({
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                    readTimeout = HTTP_READ_TIMEOUT_MS
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
        }, "pipup-key-fetch").apply { isDaemon = true }.start()
    }

    // endregion

    // region http

    override fun handleHttpRequest(session: NanoHTTPD.IHTTPSession?): NanoHTTPD.Response {
        session ?: return invalidRequest("no session")

        return when {
            session.uri == "/status" || session.uri == "/" -> status()

            session.uri == "/triggers" && session.method == NanoHTTPD.Method.GET ->
                newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK, APPLICATION_JSON,
                    JSONObject(Triggers.all(this) as Map<*, *>).toString()
                )

            session.uri == "/triggers" && session.method == NanoHTTPD.Method.POST -> try {
                Triggers.replaceAll(this, JSONObject(readBody(session)))
                ok(JSONObject(Triggers.all(this) as Map<*, *>).toString())
            } catch (ex: Throwable) {
                invalidRequest(ex.message ?: "bad trigger config")
            }

            session.uri == "/cancel" -> {
                mHandler.post { removePopup(true) }
                ok()
            }

            session.uri == "/notify" && session.method == NanoHTTPD.Method.POST -> notify(session)

            session.method != NanoHTTPD.Method.POST ->
                invalidRequest("invalid method: ${session.method}")

            else -> invalidRequest("unknown uri: ${session.uri}")
        }
    }

    private fun notify(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val contentType = session.headers["content-type"] ?: APPLICATION_JSON

            val popup = when {
                contentType.startsWith(APPLICATION_JSON) ->
                    PopupProps.fromJson(readBody(session))

                contentType.startsWith(MULTIPART_FORM_DATA) ->
                    parseMultipart(session)

                else -> throw IllegalArgumentException("invalid content-type: $contentType")
            }

            Log.d(LOG_TAG, "received popup: $popup")
            mHandler.post { createPopup(popup) }

            ok("$popup")
        } catch (ex: Throwable) {
            Log.e(LOG_TAG, "error handling /notify: ${ex.message}", ex)
            invalidRequest(ex.message ?: ex.javaClass.simpleName)
        }
    }

    /**
     * The original did a single `read()` for content-length bytes. A stream is
     * under no obligation to return them all at once, so larger payloads were
     * silently truncated and then failed to parse -- intermittently, which is the
     * worst kind.
     */
    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return "{}"

        val content = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(content, offset, length - offset)
            if (read <= 0) break
            offset += read
        }

        if (offset < length) {
            throw IllegalStateException("truncated body: got $offset of $length bytes")
        }

        return String(content, Charsets.UTF_8)
    }

    private fun parseMultipart(session: NanoHTTPD.IHTTPSession): PopupProps {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)

        val params = session.parameters.mapValues { it.value.firstOrNull() }

        val media = files["image"]?.let { path ->
            val bitmap = BitmapFactory.decodeStream(File(path).absoluteFile.inputStream())
                ?: throw IllegalStateException("could not decode uploaded image")
            PopupProps.Media.Bitmap(
                image = bitmap,
                width = params["imageWidth"]?.toIntOrNull() ?: PopupProps.DEFAULT_MEDIA_WIDTH
            )
        }

        return PopupProps(
            duration = params["duration"]?.toIntOrNull() ?: PopupProps.DEFAULT_DURATION,
            position = PopupProps.Position.parse(params["position"]),
            backgroundColor = params["backgroundColor"] ?: PopupProps.DEFAULT_BACKGROUND_COLOR,
            title = params["title"],
            titleSize = params["titleSize"]?.toFloatOrNull() ?: PopupProps.DEFAULT_TITLE_SIZE,
            titleColor = params["titleColor"] ?: PopupProps.DEFAULT_TITLE_COLOR,
            message = params["message"],
            // Was defaulting to the *title* size and colour; copy/paste bug.
            messageSize = params["messageSize"]?.toFloatOrNull() ?: PopupProps.DEFAULT_MESSAGE_SIZE,
            messageColor = params["messageColor"] ?: PopupProps.DEFAULT_MESSAGE_COLOR,
            media = media
        )
    }

    /** Cheap endpoint for Home Assistant availability / rest_command checks. */
    private fun status(): NanoHTTPD.Response {
        val body = JSONObject()
            .put("app", "PiPup")
            .put("version", BuildConfig.VERSION_NAME)
            .put("port", SERVER_PORT)
            .put("address", Utils.getIpAddress())
            .put("serverAlive", mWebServer?.isAlive == true)
            .put("popupVisible", mPopup != null)
            .put("triggers", JSONObject(Triggers.all(this) as Map<*, *>))
            .put(
                "canDrawOverlays",
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
            )
            .toString()

        return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, APPLICATION_JSON, body)
    }

    // endregion

    companion object {
        const val LOG_TAG = "PiPupService"
        const val SERVER_PORT = 7979
        const val ONGOING_NOTIFICATION_ID = 123
        const val CHANNEL_ID = "service_channel"
        const val MULTIPART_FORM_DATA = "multipart/form-data"
        const val APPLICATION_JSON = "application/json"
        private const val USER_AGENT = "PiPup"
        private const val HTTP_CONNECT_TIMEOUT_MS = 4000
        private const val HTTP_READ_TIMEOUT_MS = 8000
        private const val SERVER_START_ATTEMPTS = 5
        private const val SERVER_RETRY_DELAY_MS = 500L

        private val SOFTWARE_DECODER_PREFIXES = listOf(
            "omx.google.", "c2.android.", "c2.google.", "omx.ffmpeg.", "arm."
        )

        @Volatile
        private var serverAlive = false

        fun isServerAlive(): Boolean = serverAlive

        fun ok(message: String? = null): NanoHTTPD.Response =
            newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain", message ?: "ok")

        fun invalidRequest(message: String? = null): NanoHTTPD.Response =
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "invalid request: $message"
            )
    }
}
