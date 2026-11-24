package app.fireplace

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * The main app tile: a remote-friendly list of every scene (including ones without a tile),
 * plus pairing with Google Home.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("fireplace", MODE_PRIVATE) }
    private lateinit var scenes: List<Scene>
    private lateinit var pairRow: Row
    private lateinit var homeSceneRow: Row
    private lateinit var permissionRow: Row
    private val refresh = Runnable { refreshGoogleHome() }

    private class Row(val view: View, val subtitle: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MatterService.start(this)
        scenes = Scenes.load(this)
        val lastId = prefs.getString("last_scene", null)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(40), dp(64), dp(40))
        }
        list.addView(heading(applicationInfo.loadLabel(packageManager).toString(), 34f))

        var focusTarget: View? = null
        for (scene in scenes) {
            val row = row(scene.title, scene.subtitle) {
                startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_SCENE, scene.id))
            }
            list.addView(row.view)
            if (focusTarget == null || scene.id == lastId) focusTarget = row.view
        }

        list.addView(heading("Google Home", 22f).apply { setPadding(dp(8), dp(28), 0, dp(12)) })
        pairRow = row("Pair with Google Home", "") { showPairing() }
        homeSceneRow = row("Scene Google Home starts", "") {
            val choices = listOf<String?>(null) + scenes.map { it.id }
            val now = prefs.getString("google_home_scene", null)
            val next = choices[(choices.indexOf(now) + 1) % choices.size]
            prefs.edit().putString("google_home_scene", next).apply()
            refreshGoogleHome()
        }
        permissionRow = row("Allow Google Home to open the app", "") { openOverlaySettings() }
        list.addView(pairRow.view)
        list.addView(homeSceneRow.view)
        list.addView(permissionRow.view)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#140C08"))
            addView(list, MATCH_PARENT, WRAP_CONTENT)
        })
        focusTarget?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        MatterService.listeners.add(refresh)
        refreshGoogleHome()
    }

    override fun onPause() {
        MatterService.listeners.remove(refresh)
        super.onPause()
    }

    private fun refreshGoogleHome() {
        val code = MatterService.pairingCode
        pairRow.subtitle.text = when {
            MatterService.commissioned -> "Paired. Find it in the Google Home app as a plug."
            code != null -> "Not paired yet · code $code · press OK for the QR code"
            else -> MatterService.status
        }
        val chosen = prefs.getString("google_home_scene", null)
        homeSceneRow.subtitle.text = scenes.firstOrNull { it.id == chosen }?.title ?: "Last played"
        val allowed = Settings.canDrawOverlays(this)
        permissionRow.view.visibility = if (allowed) View.GONE else View.VISIBLE
        permissionRow.subtitle.text = "Needs \"Display over other apps\". Press OK, then turn it on."
    }

    private fun showPairing() {
        val payload = MatterService.qrPayload
        val code = MatterService.pairingCode
        if (MatterService.commissioned) {
            Toast.makeText(this, "Already paired with Google Home", Toast.LENGTH_LONG).show()
            return
        }
        if (payload == null || code == null) {
            Toast.makeText(this, MatterService.status, Toast.LENGTH_LONG).show()
            return
        }
        val size = dp(320)
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(ImageView(context).apply { setImageBitmap(bitmap) })
            addView(TextView(context).apply {
                text = "In the Google Home app: + → Device → Matter-enabled device, then scan this code " +
                    "or enter $code"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            })
        }
        AlertDialog.Builder(this).setView(content).setPositiveButton("Done", null).show()
    }

    private fun openOverlaySettings() {
        val direct = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        val opened = runCatching { startActivity(direct) }.isSuccess ||
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }.isSuccess
        if (!opened) {
            Toast.makeText(
                this,
                "Settings → Apps → Special app access → Display over other apps → ${applicationInfo.loadLabel(packageManager)}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#FFF7ED"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8), 0, 0, dp(24))
    }

    private fun row(title: String, subtitle: String, onClick: () -> Unit): Row {
        val normal = rounded("#2A1A12")
        val focused = rounded("#C2410C")
        val sub = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#FED7AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            visibility = if (subtitle.isEmpty() && title.isEmpty()) View.GONE else View.VISIBLE
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            background = normal
            setPadding(dp(28), dp(18), dp(28), dp(18))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.parseColor("#FFF7ED"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(sub)
            setOnFocusChangeListener { v, hasFocus ->
                v.background = if (hasFocus) focused else normal
                v.animate().scaleX(if (hasFocus) 1.02f else 1f).scaleY(if (hasFocus) 1.02f else 1f).setDuration(120).start()
            }
            setOnClickListener { onClick() }
        }
        return Row(view, sub)
    }

    private fun rounded(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(14).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
