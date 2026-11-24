package app.fireplace

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps the Matter smart plug running so Google Home can switch the fire on and off.
 *
 * The plug itself is the Rust program in /matter (rs-matter), packaged as
 * libfireplace_matter.so and run as a child process. It prints "FIREPLACE_ONOFF on/off"
 * when Google Home switches it; we print "on"/"off" back when the fire is started or
 * stopped with the remote, so Google Home shows the right state.
 *
 * SPIKE: also serves a tiny HTTP endpoint on port 8765 (/ping, /launch?scene=, /stop) to test
 * opening the player from the background independently of Matter.
 */
class MatterService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private var toPlug: PrintWriter? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var http: ServerSocket? = null
    @Volatile private var stopping = false
    private var restarts = 0
    private val startedAt = SystemClock.elapsedRealtime()

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
        // Wi-Fi drivers drop multicast (mDNS) unless an app holds this lock; without it
        // Google Home can't find the plug.
        multicastLock = getSystemService(WifiManager::class.java)
            .createMulticastLock("fireplace-matter").apply {
                setReferenceCounted(false)
                acquire()
            }
        startPlug()
        startHttp()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopping = true
        instance = null
        main.removeCallbacksAndMessages(null)
        process?.destroy()
        runCatching { http?.close() }
        multicastLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Google Home", NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Available in Google Home")
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    private fun startPlug() {
        if (stopping) return
        val binary = java.io.File(applicationInfo.nativeLibraryDir, "libfireplace_matter.so")
        val storage = java.io.File(filesDir, "matter").apply { mkdirs() }
        log("Starting Matter plug: ${binary.path}")
        val p = try {
            ProcessBuilder(binary.path, storage.path)
                .redirectErrorStream(true)
                .apply { environment()["RUST_LOG"] = "info" }
                .start()
        } catch (e: Exception) {
            log("Could not start the Matter plug: $e")
            status = "Matter plug failed to start: ${e.message}"
            return
        }
        process = p
        toPlug = PrintWriter(OutputStreamWriter(p.outputStream), true)
        Thread({ readPlug(p) }, "matter-out").start()
        Thread({
            val code = p.waitFor()
            log("Matter plug exited with code $code")
            if (!stopping) {
                restarts++
                status = "Matter plug stopped (code $code), restarting…"
                main.postDelayed({ startPlug() }, (2_000L * restarts).coerceAtMost(60_000))
            }
        }, "matter-wait").start()
    }

    private fun readPlug(p: Process) {
        BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
            for (line in lines) {
                Log.i(TAG, "plug: $line")
                remember(line)
                when {
                    line.startsWith("FIREPLACE_ONOFF on") -> main.post { onPlugSwitched(true) }
                    line.startsWith("FIREPLACE_ONOFF off") -> main.post { onPlugSwitched(false) }
                    line.startsWith("FIREPLACE_COMMISSIONED yes") -> {
                        commissioned = true
                        status = "Paired with Google Home"
                    }
                    line.startsWith("FIREPLACE_COMMISSIONED no") -> {
                        commissioned = false
                        status = "Not paired yet"
                    }
                    "SetupQRCode: [" in line -> qrPayload = line.substringAfter("SetupQRCode: [").substringBefore("]")
                    "PairingCode: [" in line -> pairingCode = line.substringAfter("PairingCode: [").substringBefore("]")
                    "Fabric" in line && "added" in line -> {
                        commissioned = true
                        status = "Paired with Google Home"
                    }
                }
                listeners.forEach { main.post(it) }
            }
        }
    }

    /** Google Home switched the plug. */
    private fun onPlugSwitched(on: Boolean) {
        log("Google Home switched the fire ${if (on) "on" else "off"}")
        if (on) launchScene(null) else PlayerActivity.stopFromOutside()
    }

    fun launchScene(sceneId: String?): Boolean {
        val scenes = Scenes.load(this)
        val prefs = getSharedPreferences("fireplace", MODE_PRIVATE)
        val id = sceneId ?: prefs.getString("google_home_scene", null) ?: prefs.getString("last_scene", null)
        val scene = scenes.firstOrNull { it.id == id } ?: scenes.first()
        if (!Settings.canDrawOverlays(this)) {
            log("Can't open the player from the background: 'Display over other apps' is off")
        }
        return try {
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(PlayerActivity.EXTRA_SCENE, scene.id),
            )
            log("Opened ${scene.id}")
            true
        } catch (e: Exception) {
            log("Opening ${scene.id} failed: $e")
            false
        }
    }

    /** Tells the plug the fire was started or stopped some other way. */
    private fun reportToPlug(on: Boolean) {
        Thread { runCatching { toPlug?.println(if (on) "on" else "off") } }.start()
    }

    // --- SPIKE: HTTP test endpoint ------------------------------------------------------------

    private fun startHttp() {
        Thread({
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(HTTP_PORT))
                }
                http = server
                log("Test endpoint listening on port $HTTP_PORT")
                while (!stopping) {
                    val socket = server.accept()
                    Thread { socket.use { handleHttp(it) } }.start()
                }
            } catch (e: Exception) {
                if (!stopping) log("Test endpoint stopped: $e")
            }
        }, "spike-http").start()
    }

    private fun handleHttp(socket: java.net.Socket) {
        val requestLine = BufferedReader(InputStreamReader(socket.getInputStream())).readLine() ?: return
        val path = requestLine.split(" ").getOrNull(1) ?: "/"
        val query = path.substringAfter("?", "").split("&").filter { "=" in it }
            .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        val body = when (path.substringBefore("?")) {
            "/ping" -> "ok uptime=${(SystemClock.elapsedRealtime() - startedAt) / 1000}s " +
                "plug=${if (process?.isAlive == true) "running" else "down"} paired=$commissioned " +
                "overlay=${Settings.canDrawOverlays(this)}"
            "/launch" -> {
                var ok = false
                val latch = java.util.concurrent.CountDownLatch(1)
                main.post { ok = launchScene(query["scene"]); latch.countDown() }
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                "launch ${query["scene"] ?: "(default)"} -> $ok"
            }
            "/stop" -> {
                main.post { PlayerActivity.stopFromOutside() }
                "stopped"
            }
            "/log" -> recent.joinToString("\n")
            else -> "endpoints: /ping /launch?scene=<id> /stop /log"
        }
        log("HTTP $path -> ${body.lineSequence().first()}")
        val bytes = (body + "\n").toByteArray()
        socket.getOutputStream().apply {
            write("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun log(message: String) {
        Log.i(TAG, message)
        remember(message)
    }

    companion object {
        const val TAG = "FireplaceSpike"
        private const val CHANNEL = "matter"
        private const val HTTP_PORT = 8765

        @Volatile private var instance: MatterService? = null
        @Volatile var status: String = "Starting…"
        @Volatile var commissioned: Boolean = false
        @Volatile var pairingCode: String? = null
        @Volatile var qrPayload: String? = null
        val recent = CopyOnWriteArrayList<String>()
        val listeners = CopyOnWriteArrayList<Runnable>()

        private fun remember(line: String) {
            recent.add(line)
            while (recent.size > 200) recent.removeAt(0)
        }

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MatterService::class.java)) }
                .onFailure { Log.w(TAG, "Could not start MatterService", it) }
        }

        /** Called by the player so the Google Home switch matches what's on screen. */
        fun reportFire(on: Boolean) {
            instance?.reportToPlug(on)
        }
    }
}

/** Starts the Matter plug after the TV reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MatterService.start(context)
    }
}
