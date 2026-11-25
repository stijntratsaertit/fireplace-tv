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
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Makes each scene its own smart plug in Google Home, so switching "Jazz at Home" on plays
 * that scene and switching it off stops it.
 *
 * Each plug is the Rust program in /matter (rs-matter), packaged as libfireplace_matter.so and
 * run as its own child process with its own storage, port, discriminator and passcode. Only
 * plugs that are paired run, plus one that is being paired. A plug prints
 * "FIREPLACE_ONOFF on/off" when Google Home switches it; we write "on"/"off" back when the
 * fire changes another way, so every switch in Google Home matches the TV.
 *
 * SPIKE: also serves a tiny HTTP endpoint on port 8765 (/ping, /launch?scene=, /stop, /log).
 */
class MatterService : Service() {

    /** One Matter plug for one scene. */
    inner class Node(val scene: Scene, val slot: Int) {
        val discriminator get() = 3840 + slot
        val passcode get() = PASSCODES[slot % PASSCODES.size]
        val preferredPort get() = 5541 + slot
        val host get() = "57484F4D45%02X".format(slot)
        val storage get() = File(filesDir, "matter/${scene.id}")

        @Volatile var process: Process? = null
        @Volatile var writer: PrintWriter? = null
        @Volatile var port: Int? = null
        @Volatile var commissioned = false
        @Volatile var on = false
        @Volatile var pairing = false
        @Volatile var qrPayload: String? = null
        @Volatile var pairingCode: String? = null
        @Volatile var restarts = 0
        @Volatile var wanted = false

        val running get() = process?.isAlive == true

        fun start(pair: Boolean) {
            stop()
            wanted = true
            pairing = pair
            qrPayload = null
            pairingCode = null
            storage.mkdirs()
            val binary = File(applicationInfo.nativeLibraryDir, "libfireplace_matter.so")
            val cmd = mutableListOf(
                binary.path, storage.path,
                "--name", scene.title,
                "--discriminator", discriminator.toString(),
                "--passcode", passcode.toString(),
                "--port", preferredPort.toString(),
                "--host", host,
            )
            if (pair) cmd += "--pair"
            log("${scene.id}: starting plug${if (pair) " for pairing" else ""}")
            val p = try {
                ProcessBuilder(cmd).redirectErrorStream(true)
                    .apply { environment()["RUST_LOG"] = "info" }
                    .start()
            } catch (e: Exception) {
                log("${scene.id}: could not start the plug: $e")
                return
            }
            process = p
            writer = PrintWriter(OutputStreamWriter(p.outputStream), true)
            Thread({ read(p) }, "matter-${scene.id}").start()
            Thread({
                val code = try { p.waitFor() } catch (e: InterruptedException) { return@Thread }
                if (process !== p) return@Thread
                log("${scene.id}: plug exited with code $code")
                process = null
                if (wanted && !stopping) {
                    restarts++
                    main.postDelayed({ if (wanted && process == null) start(pairing) }, (2_000L * restarts).coerceAtMost(60_000))
                }
            }, "matter-wait-${scene.id}").start()
        }

        fun stop() {
            wanted = false
            process?.let { it.destroy(); log("${scene.id}: plug stopped") }
            process = null
            writer = null
        }

        fun send(newOn: Boolean) {
            if (on == newOn) return
            on = newOn
            val w = writer ?: return
            Thread { runCatching { w.println(if (newOn) "on" else "off") } }.start()
        }

        /** Reads the plug's output. Ends quietly when the plug is stopped (never crashes the app). */
        private fun read(p: Process) {
            try {
                readLines(p)
            } catch (e: java.io.IOException) {
                if (process === p) log("${scene.id}: lost the plug's output: $e")
            } catch (e: Exception) {
                log("${scene.id}: error reading the plug's output: $e")
            }
        }

        private fun readLines(p: Process) {
            BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                for (line in lines) {
                    Log.i(TAG, "plug ${scene.id}: $line")
                    remember("${scene.id}: $line")
                    when {
                        line.startsWith("FIREPLACE_ONOFF on") -> main.post { on = true; onSwitched(this, true) }
                        line.startsWith("FIREPLACE_ONOFF off") -> main.post { on = false; onSwitched(this, false) }
                        line.startsWith("FIREPLACE_PORT ") -> port = line.substringAfter("FIREPLACE_PORT ").trim().toIntOrNull()
                        line.startsWith("FIREPLACE_COMMISSIONED yes") -> main.post { onCommissioned(this, true) }
                        line.startsWith("FIREPLACE_COMMISSIONED no") -> main.post { onCommissioned(this, false) }
                        "SetupQRCode: [" in line -> qrPayload = line.substringAfter("SetupQRCode: [").substringBefore("]")
                        "PairingCode: [" in line -> pairingCode = line.substringAfter("PairingCode: [").substringBefore("]")
                    }
                    listeners.forEach { main.post(it) }
                }
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val nodes = ConcurrentHashMap<String, Node>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var http: ServerSocket? = null
    @Volatile private var stopping = false
    private val startedAt = SystemClock.elapsedRealtime()
    private val prefs by lazy { getSharedPreferences("fireplace", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
        // Wi-Fi drivers drop multicast (mDNS) unless an app holds this lock; without it
        // Google Home can't find the plugs.
        multicastLock = getSystemService(WifiManager::class.java)
            .createMulticastLock("fireplace-matter").apply {
                setReferenceCounted(false)
                acquire()
            }
        migrateSinglePlug()
        for (scene in Scenes.load(this)) {
            if (scene.id in pairedIds()) node(scene).start(pair = false)
        }
        startHttp()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopping = true
        instance = null
        main.removeCallbacksAndMessages(null)
        nodes.values.forEach { it.stop() }
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

    /**
     * The first test build ran a single plug storing its state directly in filesDir/matter.
     * That plug becomes the "fireplace" scene's plug (same state, same codes), so it stays paired.
     */
    private fun migrateSinglePlug() {
        val old = File(filesDir, "matter")
        val files = old.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return
        val target = File(old, "fireplace").apply { mkdirs() }
        files.forEach { it.renameTo(File(target, it.name)) }
        prefs.edit()
            .putStringSet(PAIRED, pairedIds() + "fireplace")
            .putInt("matter_slot_fireplace", 0)
            .apply()
        log("Moved the existing Google Home plug to the Fire at Home scene")
    }

    private fun pairedIds(): Set<String> = prefs.getStringSet(PAIRED, emptySet()).orEmpty().toSet()

    /** Each scene keeps a fixed slot (codes, port) once it has one, even if scenes are reordered. */
    private fun slotFor(scene: Scene): Int {
        val key = "matter_slot_${scene.id}"
        prefs.getInt(key, -1).takeIf { it >= 0 }?.let { return it }
        val used = prefs.all.filterKeys { it.startsWith("matter_slot_") }.values.filterIsInstance<Int>().toSet()
        val slot = if (scene.id == "fireplace" && 0 !in used) 0 else (1..63).first { it !in used }
        prefs.edit().putInt(key, slot).apply()
        return slot
    }

    private fun node(scene: Scene): Node = nodes.getOrPut(scene.id) { Node(scene, slotFor(scene)) }

    private fun onCommissioned(node: Node, yes: Boolean) {
        val wasPaired = node.commissioned
        node.commissioned = yes
        if (yes) {
            node.pairing = false
            prefs.edit().putStringSet(PAIRED, pairedIds() + node.scene.id).apply()
            log("${node.scene.id}: paired with Google Home")
        } else if (!node.pairing && (wasPaired || node.scene.id in pairedIds())) {
            // Removed from Google Home: forget it completely (stop, delete its state), so it isn't
            // restarted on every launch. It can be added again from the menu.
            log("${node.scene.id}: removed from Google Home")
            forget(node.scene.id)
            return
        }
        listeners.forEach { main.post(it) }
    }

    /** Google Home switched one scene's plug. */
    private fun onSwitched(node: Node, on: Boolean) {
        log("Google Home switched ${node.scene.id} ${if (on) "on" else "off"}")
        if (on) {
            launchScene(node.scene.id)
        } else if (PlayerActivity.currentSceneId() == node.scene.id) {
            PlayerActivity.stopFromOutside()
        }
    }

    /**
     * Wakes the Streamer (and, through HDMI-CEC, the TV) if it is asleep. The player also asks to
     * turn the screen on; this wake lock is a fallback in case the activity start alone doesn't.
     */
    @Suppress("DEPRECATION")
    private fun wakeUp() {
        val power = getSystemService(android.os.PowerManager::class.java)
        log("Before waking: interactive=${power.isInteractive}")
        if (power.isInteractive) return
        runCatching {
            power.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "fireplace:wake",
            ).acquire(5_000)
        }.onFailure { log("Wake lock failed: $it") }
        main.postDelayed({ log("After waking: interactive=${power.isInteractive}") }, 1_500)
    }

    fun launchScene(sceneId: String?): Boolean {
        wakeUp()
        val scenes = Scenes.load(this)
        val id = sceneId ?: prefs.getString("last_scene", null)
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

    /** Makes every plug's switch match the TV: only the playing scene is on. */
    private fun syncSwitches(playing: String?) {
        nodes.values.forEach { it.send(it.scene.id == playing) }
    }

    // --- Pairing, used by the menu ----------------------------------------------------------------

    private fun beginPairing(sceneId: String) {
        val scene = Scenes.load(this).firstOrNull { it.id == sceneId } ?: return
        val node = node(scene)
        if (node.commissioned && node.running) return
        node.restarts = 0
        node.start(pair = true)
    }

    private fun endPairing(sceneId: String) {
        val node = nodes[sceneId] ?: return
        if (!node.commissioned) {
            node.stop()
            node.pairing = false
        }
    }

    private fun forget(sceneId: String) {
        val node = nodes.remove(sceneId)
        node?.stop()
        File(filesDir, "matter/$sceneId").deleteRecursively()
        prefs.edit().putStringSet(PAIRED, pairedIds() - sceneId).apply()
        log("$sceneId: removed from this TV")
        listeners.forEach { main.post(it) }
    }

    // --- SPIKE: HTTP test endpoint ----------------------------------------------------------------

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
            "/ping" -> buildString {
                append("ok uptime=${(SystemClock.elapsedRealtime() - startedAt) / 1000}s ")
                append("overlay=${Settings.canDrawOverlays(this@MatterService)} ")
                append("playing=${PlayerActivity.currentSceneId() ?: "-"}\n")
                for (scene in Scenes.load(this@MatterService)) {
                    val n = nodes[scene.id]
                    append("${scene.id}: ")
                    if (n == null) {
                        append("not added\n")
                    } else {
                        append("slot=${n.slot} port=${n.port ?: "-"} running=${n.running} ")
                        append("commissioned=${n.commissioned} pairing=${n.pairing} on=${n.on} ")
                        append("code=${n.pairingCode ?: "-"}\n")
                    }
                }
            }.trimEnd()
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

    // ----------------------------------------------------------------------------------------------

    private fun log(message: String) {
        Log.i(TAG, message)
        remember(message)
    }

    /** What the menu shows for one scene. */
    data class SceneStatus(
        val commissioned: Boolean,
        val pairing: Boolean,
        val running: Boolean,
        val qrPayload: String?,
        val pairingCode: String?,
    )

    companion object {
        const val TAG = "FireplaceSpike"
        private const val CHANNEL = "matter"
        private const val HTTP_PORT = 8765
        private const val PAIRED = "matter_paired"

        /**
         * Setup passcodes per slot. Slot 0 keeps the first test build's passcode, so the plug that
         * was already paired keeps working. None are on Matter's list of forbidden passcodes.
         */
        private val PASSCODES = intArrayOf(
            20202021, 20202028, 31415926, 27182818, 16180339, 14142135, 17320508, 22360679,
            26457513, 30000117, 24494897, 28284271, 33166247, 34641016, 36055512, 37416573,
        )

        @Volatile private var instance: MatterService? = null
        val recent = CopyOnWriteArrayList<String>()
        val listeners = CopyOnWriteArrayList<Runnable>()

        private fun remember(line: String) {
            recent.add(line)
            while (recent.size > 300) recent.removeAt(0)
        }

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, MatterService::class.java)) }
                .onFailure { Log.w(TAG, "Could not start MatterService", it) }
        }

        fun status(sceneId: String, pairedHint: Context): SceneStatus {
            val n = instance?.nodes?.get(sceneId)
            val paired = n?.commissioned
                ?: (sceneId in pairedHint.getSharedPreferences("fireplace", MODE_PRIVATE)
                    .getStringSet(PAIRED, emptySet()).orEmpty())
            return SceneStatus(paired, n?.pairing == true, n?.running == true, n?.qrPayload, n?.pairingCode)
        }

        fun beginPairing(sceneId: String) = instance?.let { it.main.post { it.beginPairing(sceneId) } }
        fun endPairing(sceneId: String) = instance?.let { it.main.post { it.endPairing(sceneId) } }
        fun forget(sceneId: String) = instance?.let { it.main.post { it.forget(sceneId) } }

        /** Called by the player: [playing] is the scene on screen, or null when the fire stops. */
        fun reportFire(playing: String?) {
            instance?.let { s -> s.main.post { s.syncSwitches(playing) } }
        }
    }
}

/** Starts the Google Home plugs after the TV reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MatterService.start(context)
    }
}
