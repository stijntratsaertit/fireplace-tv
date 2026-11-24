package app.fireplace

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

/**
 * Plays a scene full screen. Picks a random long YouTube video for the scene, and when it ends
 * (or fails) moves on to another one, avoiding recently played videos.
 *
 * Remote: OK / play-pause toggles pause, right = another video, up / down = previous / next scene,
 * back = exit.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    private val tag = "Fireplace"
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("fireplace", MODE_PRIVATE) }

    private lateinit var scenes: List<Scene>
    private lateinit var scene: Scene
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var overlay: TextView

    private val pool = mutableListOf<Candidate>()
    private var current: Candidate? = null
    private var nowTitle = ""
    private var currentRetries = 0
    private var failuresInARow = 0
    /** Bumped on every scene or video switch, so results from older lookups are ignored. */
    private var generation = 0

    private val hideOverlay = Runnable { overlay.animate().alpha(0f).setDuration(600).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current_ = this
        MatterService.start(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scenes = Scenes.load(this)

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) failuresInARow = 0
                    if (state == Player.STATE_ENDED) playNext()
                }

                override fun onPlayerError(error: PlaybackException) = handleError(error)
            })
        }
        // Lets Google Home / Assistant and the TV's media controls pause, resume and change volume.
        session = MediaSession.Builder(this, player).build()

        val playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShutterBackgroundColor(Color.BLACK)
            keepScreenOn = true
            player = this@PlayerActivity.player
        }
        overlay = TextView(this).apply {
            setTextColor(Color.parseColor("#FFF7ED"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3140C08"))
                cornerRadius = dp(14).toFloat()
            }
        }
        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        )
        overlayParams.setMargins(dp(48), 0, dp(48), dp(40))
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(overlay, overlayParams)
        setContentView(root)

        startScene(sceneFrom(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startScene(sceneFrom(intent))
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (Home button, TV off, another app) stops the fire.
        finish()
    }

    override fun onDestroy() {
        if (current_ === this) current_ = null
        MatterService.reportFire(false)
        generation++
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        session.release()
        player.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player.playWhenReady = !player.playWhenReady
                show(if (player.playWhenReady) "Playing" else "Paused", sticky = !player.playWhenReady)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                show("Finding another video…", sticky = true)
                playNext()
            }
            KeyEvent.KEYCODE_DPAD_UP -> switchScene(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> switchScene(+1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> showNowPlaying(withHelp = true)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun sceneFrom(intent: Intent): Scene {
        val id = intent.getStringExtra(EXTRA_SCENE) ?: intent.component?.let { component ->
            runCatching {
                packageManager.getActivityInfo(component, PackageManager.GET_META_DATA).metaData?.getString("scene_id")
            }.getOrNull()
        }
        return scenes.firstOrNull { it.id == id } ?: scenes.first()
    }

    private fun switchScene(step: Int) {
        val i = scenes.indexOfFirst { it.id == scene.id }
        startScene(scenes[(i + step + scenes.size) % scenes.size])
    }

    private fun startScene(next: Scene) {
        scene = next
        MatterService.reportFire(true)
        prefs.edit().putString("last_scene", next.id).apply()
        pool.clear()
        failuresInARow = 0
        player.stop()
        show("${next.title}\nFinding a video…", sticky = true)
        playNext()
    }

    /** Picks another video for the current scene and starts it. Lookups run off the main thread. */
    private fun playNext() {
        val gen = ++generation
        val forScene = scene
        val alreadyPooled = pool.toList()
        val recent = recentIds()
        worker.execute {
            try {
                val candidates = alreadyPooled.ifEmpty { YouTube.candidates(forScene) }
                if (candidates.isEmpty()) throw IllegalStateException("No videos found for \"${forScene.search}\"")
                val fresh = candidates.filter { videoId(it.url) !in recent && it.url != current?.url }
                val pick = (fresh.ifEmpty { candidates.filter { it.url != current?.url }.ifEmpty { candidates } }).random()
                val playable = YouTube.resolve(pick)
                main.post {
                    if (gen != generation) return@post
                    if (pool.isEmpty()) pool.addAll(candidates)
                    start(pick, playable, startAtMs = 0)
                }
            } catch (e: Exception) {
                Log.w(tag, "Lookup failed", e)
                main.post { if (gen == generation) handleError(e) }
            }
        }
    }

    private fun start(candidate: Candidate, playable: Playable, startAtMs: Long) {
        if (current?.url != candidate.url) currentRetries = 0
        current = candidate
        nowTitle = playable.title
        rememberPlayed(candidate.url)
        player.setMediaSource(mediaSource(playable))
        if (startAtMs > 0) player.seekTo(startAtMs)
        player.prepare()
        player.playWhenReady = true
        show("${scene.title}\n${playable.title}")
    }

    private fun mediaSource(p: Playable): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(p.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val data = DefaultDataSource.Factory(this, http)
        val metadata = MediaMetadata.Builder().setTitle(p.title).setArtist(scene.title).build()
        val item = MediaItem.Builder().setUri(urlOf(p)).setMediaId(current?.url ?: "").setMediaMetadata(metadata).build()
        return when (p) {
            is Playable.Hls -> HlsMediaSource.Factory(data).createMediaSource(item)
            is Playable.Single -> ProgressiveMediaSource.Factory(data).createMediaSource(item)
            is Playable.Split -> MergingMediaSource(
                true,
                ProgressiveMediaSource.Factory(data).createMediaSource(item),
                ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(p.audio)),
            )
        }
    }

    private fun urlOf(p: Playable) = when (p) {
        is Playable.Hls -> p.url
        is Playable.Single -> p.url
        is Playable.Split -> p.video
    }

    /**
     * Stream links expire after a few hours, which breaks long videos part-way. Then the same
     * video is looked up again and resumed. Other failures move on to another video.
     */
    private fun handleError(error: Exception) {
        val video = current
        val position = player.currentPosition
        if (error is PlaybackException && video != null && position > 60_000 && currentRetries < 2) {
            currentRetries++
            val gen = ++generation
            worker.execute {
                val playable = runCatching { YouTube.resolve(video) }.getOrNull()
                main.post {
                    if (gen != generation) return@post
                    if (playable != null) start(video, playable, position) else playNext()
                }
            }
            return
        }
        failuresInARow++
        if (failuresInARow >= 5) {
            show("Couldn't play ${scene.title}.\n${error.message ?: error.javaClass.simpleName}\nPress → to try again.", sticky = true)
            failuresInARow = 0
            return
        }
        if (error !is PlaybackException) pool.clear() // a failed search; search again
        main.postDelayed({ playNext() }, 1_500L * failuresInARow)
    }

    private fun showNowPlaying(withHelp: Boolean) {
        val now = nowTitle
        val help = if (withHelp) "\nOK pause · → other video · ↑↓ other scene" else ""
        show("${scene.title}${if (now.isBlank()) "" else "\n$now"}$help")
    }

    private fun show(text: String, sticky: Boolean = false) {
        overlay.text = text
        overlay.animate().cancel()
        overlay.alpha = 1f
        main.removeCallbacks(hideOverlay)
        if (!sticky) main.postDelayed(hideOverlay, 6_000)
    }

    private fun recentIds(): Set<String> =
        prefs.getString("recent_${scene.id}", "").orEmpty().split(',').filter { it.isNotEmpty() }.toSet()

    private fun rememberPlayed(url: String) {
        val key = "recent_${scene.id}"
        val list = prefs.getString(key, "").orEmpty().split(',').filter { it.isNotEmpty() }.toMutableList()
        val id = videoId(url)
        list.remove(id)
        list.add(0, id)
        prefs.edit().putString(key, list.take(8).joinToString(",")).apply()
    }

    private fun videoId(url: String) = Regex("[?&]v=([\\w-]{6,})").find(url)?.groupValues?.get(1) ?: url

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** The player on screen, if any, so Google Home can stop it. */
        @Volatile private var current_: PlayerActivity? = null

        fun stopFromOutside() {
            current_?.finish()
        }

        const val EXTRA_SCENE = "scene_id"
    }
}
