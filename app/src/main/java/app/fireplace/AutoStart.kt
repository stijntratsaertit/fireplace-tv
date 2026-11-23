package app.fireplace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.Calendar

/**
 * Starts a scene by itself when the TV wakes up, so a Google Home routine that turns the TV on
 * (or just pressing the remote's power button) lights the fire without any voice command.
 *
 * Android only lets an app open itself from the background when it has the
 * "Display over other apps" permission, so that has to be switched on once.
 */
object AutoStart {
    enum class Mode(val label: String) {
        OFF("Off"),
        EVENINGS("Evenings, 5 pm to 3 am"),
        ALWAYS("Every time"),
    }

    const val SCENE_LAST = "_last"
    const val SCENE_SEASONAL = "_seasonal"
    private const val CHRISTMAS_ID = "fireplace_christmas"

    private fun prefs(context: Context) = context.getSharedPreferences("fireplace", Context.MODE_PRIVATE)

    fun mode(context: Context): Mode =
        runCatching { Mode.valueOf(prefs(context).getString("auto_mode", null) ?: "OFF") }.getOrDefault(Mode.OFF)

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString("auto_mode", mode.name).apply()
        sync(context)
    }

    fun sceneChoice(context: Context): String = prefs(context).getString("auto_scene", SCENE_SEASONAL) ?: SCENE_SEASONAL

    fun setSceneChoice(context: Context, choice: String) = prefs(context).edit().putString("auto_scene", choice).apply()

    fun sceneChoiceLabel(choice: String, scenes: List<Scene>): String = when (choice) {
        SCENE_LAST -> "Last played"
        SCENE_SEASONAL -> "Seasonal (Christmas in December, otherwise last played)"
        else -> scenes.firstOrNull { it.id == choice }?.title ?: "Last played"
    }

    fun canOpenFromBackground(context: Context) = Settings.canDrawOverlays(context)

    /** Whether the fire should start right now, given the chosen mode and the time of day. */
    fun shouldStartNow(context: Context): Boolean = when (mode(context)) {
        Mode.OFF -> false
        Mode.ALWAYS -> true
        Mode.EVENINGS -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 17 || it < 3 }
    }

    fun sceneToStart(context: Context, scenes: List<Scene>): String? {
        val last = prefs(context).getString("last_scene", null)
        val choice = sceneChoice(context)
        return when (choice) {
            SCENE_LAST -> last
            SCENE_SEASONAL -> {
                val december = Calendar.getInstance().get(Calendar.MONTH) == Calendar.DECEMBER
                if (december && scenes.any { it.id == CHRISTMAS_ID }) CHRISTMAS_ID else last
            }
            else -> choice
        }
    }

    /** Runs the wake-up watcher while auto-start is on, and stops it when it's off. */
    fun sync(context: Context) {
        val intent = Intent(context, WakeWatcherService::class.java)
        if (mode(context) == Mode.OFF) {
            context.stopService(intent)
        } else {
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w("Fireplace", "Could not start wake watcher", it) }
        }
    }
}

/**
 * Keeps running in the background (Android requires a notification for that; TVs don't show it)
 * and opens the fire when the screen turns on. "Screen on" can only be heard by a running app,
 * which is why this service exists.
 */
class WakeWatcherService : Service() {
    private val main = Handler(Looper.getMainLooper())

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!AutoStart.shouldStartNow(context)) return
            if (!AutoStart.canOpenFromBackground(context)) {
                Log.w("Fireplace", "Auto-start needs 'Display over other apps'")
                return
            }
            // Give the TV a moment to finish waking and show its home screen, then open on top.
            main.removeCallbacksAndMessages(null)
            main.postDelayed({ launch() }, 4_000)
        }
    }

    private fun launch() {
        val scenes = Scenes.load(this)
        val sceneId = AutoStart.sceneToStart(this, scenes)
        val intent = Intent(this, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(PlayerActivity.EXTRA_AUTO, true)
        if (sceneId != null) intent.putExtra(PlayerActivity.EXTRA_SCENE, sceneId)
        runCatching { startActivity(intent) }.onFailure { Log.w("Fireplace", "Auto-start failed", it) }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Auto-start", NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Starts the fire when the TV turns on")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AutoStart.mode(this) == AutoStart.Mode.OFF) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenOn) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "autostart"
    }
}

/** Restarts the wake-up watcher after the TV reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutoStart.sync(context)
    }
}
