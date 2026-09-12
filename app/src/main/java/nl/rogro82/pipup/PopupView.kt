package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

@OptIn(UnstableApi::class)
@SuppressLint("ViewConstructor")
sealed class PopupView(context: Context, val popup: PopupProps) : LinearLayout(context) {

    protected val uiHandler = Handler(Looper.getMainLooper())

    open fun create() {
        inflate(context, R.layout.popup, this)

        layoutParams = LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            orientation = VERTICAL
            minimumWidth = 240
        }

        setPadding(20, 20, 20, 20)

        val title = findViewById<TextView>(R.id.popup_title)
        val message = findViewById<TextView>(R.id.popup_message)
        val frame = findViewById<FrameLayout>(R.id.popup_frame)

        if (popup.media == null) {
            removeView(frame)
        }

        if (popup.title.isNullOrEmpty()) {
            removeView(title)
        } else {
            title.text = popup.title
            title.textSize = popup.titleSize
            title.setTextColor(parseColor(popup.titleColor, Color.WHITE))
        }

        if (popup.message.isNullOrEmpty()) {
            removeView(message)
        } else {
            message.text = popup.message
            message.textSize = popup.messageSize
            message.setTextColor(parseColor(popup.messageColor, Color.WHITE))
        }

        setBackgroundColor(parseColor(popup.backgroundColor, DEFAULT_BACKGROUND))
    }

    open fun destroy() {}

    protected fun frame(): FrameLayout = findViewById(R.id.popup_frame)

    /** Never let a typo in a colour string take the whole popup down. */
    private fun parseColor(value: String, fallback: Int): Int = try {
        Color.parseColor(value)
    } catch (ex: IllegalArgumentException) {
        Log.w(LOG_TAG, "invalid color '$value', using fallback")
        fallback
    }

    private class Default(context: Context, popup: PopupProps) : PopupView(context, popup)

    /**
     * Video playback via ExoPlayer rendering into a [TextureView].
     *
     * Two deliberate choices here, both aimed at "another app is already playing
     * video", which is the normal state of a TV:
     *
     *  - TextureView instead of VideoView/SurfaceView. A SurfaceView gets its own
     *    compositor layer punched through the window, which loses the z-fight
     *    against a fullscreen app's video surface -- the popup would be playing
     *    but invisible. A TextureView is drawn in the normal view hierarchy, so
     *    an overlay window draws above the app underneath it.
     *
     *  - The player is owned by the service and reused. Constructing an
     *    ExoPlayer and warming its codec list is a large part of the first-popup
     *    delay; keeping one idle instance around removes it. An idle player holds
     *    no decoder, so it costs nothing while nothing is showing.
     */
    @OptIn(UnstableApi::class)
    private class Video(
        context: Context,
        popup: PopupProps,
        private val media: PopupProps.Media.Video,
        private val player: ExoPlayer
    ) : PopupView(context, popup) {

        private var textureView: TextureView? = null

        private val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // Swap the placeholder for the real thing only once there is
                // something to show, so there is no black flash.
                textureView?.visibility = View.VISIBLE
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                resizeTo(videoSize)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(LOG_TAG, "playback error ${error.errorCodeName}: ${error.message}")
                // The popup itself (title/message) stays up; only the video area
                // collapses, which is far better than an empty box.
                uiHandler.post { frame().visibility = View.GONE }
            }
        }

        override fun create() {
            super.create()

            val frame = frame()

            // Reserve space immediately at a 16:9 guess so the popup can be shown
            // at once and does not jump when the real dimensions arrive.
            frame.layoutParams = frame.layoutParams.apply {
                width = media.width
                height = (media.width * 9) / 16
            }

            textureView = TextureView(context).apply {
                visibility = View.INVISIBLE
                frame.addView(
                    this,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply { gravity = Gravity.CENTER }
                )
            }

            with(player) {
                addListener(listener)

                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            when (popup.audioFocus) {
                                // USAGE_MEDIA asks for full focus, which pauses
                                // the running app; NOTIFICATION asks for
                                // transient-may-duck, which lowers it instead.
                                PopupProps.AudioFocus.Pause -> C.USAGE_MEDIA
                                PopupProps.AudioFocus.Duck -> C.USAGE_NOTIFICATION
                                PopupProps.AudioFocus.None -> C.USAGE_MEDIA
                            }
                        )
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ popup.audioFocus != PopupProps.AudioFocus.None
                )

                volume = popup.volume
                repeatMode = if (popup.loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                setVideoTextureView(textureView)

                setMediaItem(
                    MediaItem.Builder()
                        .setUri(media.uri)
                        // Only consulted for live streams; keeps HLS from buffering
                        // a conservative three segments before it shows anything.
                        .setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                                .build()
                        )
                        .build()
                )

                prepare()
                play()
            }
        }

        private fun resizeTo(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return

            val aspect =
                (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
            if (aspect <= 0f) return

            val frame = frame()
            frame.layoutParams = frame.layoutParams.apply {
                width = media.width
                height = (media.width / aspect).toInt().coerceAtLeast(1)
            }
            frame.requestLayout()
        }

        override fun destroy() {
            try {
                with(player) {
                    removeListener(listener)
                    stop()
                    clearMediaItems()
                    clearVideoSurface()
                }
            } catch (ex: Throwable) {
                Log.w(LOG_TAG, "error tearing down video: ${ex.message}")
            }
            textureView = null
        }
    }

    /** Live camera view that never touches a video decoder. See [MjpegStream]. */
    private class Mjpeg(
        context: Context,
        popup: PopupProps,
        private val media: PopupProps.Media.Mjpeg
    ) : PopupView(context, popup) {

        private var imageView: ImageView? = null
        private var stream: MjpegStream? = null

        override fun create() {
            super.create()

            val frame = frame()

            imageView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                frame.addView(
                    this,
                    FrameLayout.LayoutParams(
                        media.width,
                        (media.width * 9) / 16
                    ).apply { gravity = Gravity.CENTER }
                )
            }

            stream = MjpegStream(
                url = media.uri,
                onFrame = { bitmap ->
                    uiHandler.post {
                        val view = imageView ?: return@post
                        // Size to the first real frame, then leave it alone.
                        if (view.drawable == null && bitmap.width > 0) {
                            view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams)
                                .apply {
                                    width = media.width
                                    height =
                                        (media.width.toFloat() / bitmap.width * bitmap.height).toInt()
                                }
                        }
                        view.setImageBitmap(bitmap)
                    }
                },
                onError = {
                    uiHandler.post { frame().visibility = View.GONE }
                }
            ).also { it.start() }
        }

        override fun destroy() {
            stream?.stop()
            stream = null
            imageView?.setImageDrawable(null)
            imageView = null
        }
    }

    private class Image(
        context: Context,
        popup: PopupProps,
        private val media: PopupProps.Media.Image
    ) : PopupView(context, popup) {

        private var imageView: ImageView? = null

        override fun create() {
            super.create()

            val frame = frame()

            imageView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                frame.addView(
                    this,
                    FrameLayout.LayoutParams(
                        media.width,
                        WindowManager.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.CENTER }
                )
            }

            ImageLoader.load(
                url = media.uri,
                targetWidth = media.width,
                onLoaded = { bitmap -> uiHandler.post { imageView?.setImageBitmap(bitmap) } },
                onError = { uiHandler.post { frame().visibility = View.GONE } }
            )
        }

        override fun destroy() {
            imageView?.setImageDrawable(null)
            imageView = null
        }
    }

    /** Bitmap uploaded directly through the multipart/form-data endpoint. */
    private class Bitmap(
        context: Context,
        popup: PopupProps,
        private val media: PopupProps.Media.Bitmap
    ) : PopupView(context, popup) {

        private var imageView: ImageView? = null

        override fun create() {
            super.create()

            val frame = frame()
            imageView = ImageView(context).apply { setImageBitmap(media.image) }

            val scaledHeight = if (media.image.width > 0) {
                ((media.width.toFloat() / media.image.width) * media.image.height).toInt()
            } else {
                WindowManager.LayoutParams.WRAP_CONTENT
            }

            frame.addView(
                imageView,
                FrameLayout.LayoutParams(media.width, scaledHeight).apply {
                    gravity = Gravity.CENTER
                }
            )
        }

        override fun destroy() {
            imageView?.setImageDrawable(null)
            imageView = null
            // The bitmap was decoded for this popup only and is not shared.
            try {
                if (!media.image.isRecycled) media.image.recycle()
            } catch (ex: Throwable) {
                Log.w(LOG_TAG, "error recycling bitmap: ${ex.message}")
            }
        }
    }

    private class Web(
        context: Context,
        popup: PopupProps,
        private val media: PopupProps.Media.Web
    ) : PopupView(context, popup) {

        private var webView: WebView? = null

        override fun create() {
            super.create()

            val frame = frame()
            webView = WebView(context).apply {
                with(settings) {
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                loadUrl(media.uri)
                frame.addView(
                    this,
                    FrameLayout.LayoutParams(media.width, media.height).apply {
                        gravity = Gravity.CENTER
                    }
                )
            }
        }

        override fun destroy() {
            // WebViews leak their window and keep running timers unless destroyed.
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webView = null
        }
    }

    @OptIn(UnstableApi::class)
    companion object {
        const val LOG_TAG = "PopupView"
        private const val LIVE_TARGET_OFFSET_MS = 1000L
        private val DEFAULT_BACKGROUND = Color.parseColor(PopupProps.DEFAULT_BACKGROUND_COLOR)

        /**
         * @param player shared, already-configured player owned by the service;
         *               only needed for [PopupProps.Media.Video].
         */
        fun build(context: Context, popup: PopupProps, player: ExoPlayer?): PopupView {
            val view = when (val media = popup.media) {
                is PopupProps.Media.Web -> Web(context, popup, media)
                is PopupProps.Media.Video ->
                    if (player != null) {
                        Video(context, popup, media, player)
                    } else {
                        Log.e(LOG_TAG, "no player available, falling back to text popup")
                        Default(context, popup.copy(media = null))
                    }
                is PopupProps.Media.Mjpeg -> Mjpeg(context, popup, media)
                is PopupProps.Media.Image -> Image(context, popup, media)
                is PopupProps.Media.Bitmap -> Bitmap(context, popup, media)
                null -> Default(context, popup)
            }
            // create() is called here rather than from each subclass's init block
            // so that subclass fields are fully initialised first.
            view.create()
            return view
        }
    }
}
