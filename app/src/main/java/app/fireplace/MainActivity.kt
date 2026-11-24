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
    private val homeRows = mutableMapOf<String, Row>()
    private lateinit var permissionRow: Row
    private val refresh = Runnable { refreshGoogleHome() }
    private var pairingDialog: AlertDialog? = null

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

        list.addView(heading("Google Home", 22f).apply { setPadding(dp(8), dp(28), 0, dp(4)) })
        list.addView(TextView(this).apply {
            text = "Each scene can be its own switch in Google Home. Add the ones you want."
            setTextColor(Color.parseColor("#FED7AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(8), 0, 0, dp(16))
        })
        permissionRow = row("Allow Google Home to open the app", "") { openOverlaySettings() }
        list.addView(permissionRow.view)
        for (scene in scenes) {
            val r = row(scene.title, "") { onHomeRowClicked(scene) }
            homeRows[scene.id] = r
            list.addView(r.view)
        }

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
        for (scene in scenes) {
            val st = MatterService.status(scene.id, this)
            homeRows[scene.id]?.subtitle?.text = when {
                st.commissioned -> "In Google Home · press OK to remove"
                st.pairing -> "Adding… code ${st.pairingCode ?: "…"}"
                else -> "Not in Google Home · press OK to add"
            }
        }
        val allowed = Settings.canDrawOverlays(this)
        permissionRow.view.visibility = if (allowed) View.GONE else View.VISIBLE
        permissionRow.subtitle.text = "Needs \"Display over other apps\". Press OK, then turn it on."
        updatePairingDialog()
    }

    private var pairingScene: Scene? = null
    private var pairingContent: LinearLayout? = null

    private fun onHomeRowClicked(scene: Scene) {
        val st = MatterService.status(scene.id, this)
        if (st.commissioned) {
            AlertDialog.Builder(this)
                .setTitle("Remove ${scene.title}?")
                .setMessage("This removes it from this TV. Also remove it in the Google Home app.")
                .setPositiveButton("Remove") { _, _ -> MatterService.forget(scene.id) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        MatterService.beginPairing(scene.id)
        pairingScene = scene
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        pairingContent = content
        pairingDialog = AlertDialog.Builder(this)
            .setTitle("Add ${scene.title} to Google Home")
            .setView(content)
            .setPositiveButton("Done", null)
            .setOnDismissListener {
                pairingDialog = null
                pairingContent = null
                pairingScene = null
                MatterService.endPairing(scene.id)
            }
            .show()
        updatePairingDialog()
    }

    /** Fills the pairing dialog once the plug has printed its codes, and closes it once paired. */
    private fun updatePairingDialog() {
        val scene = pairingScene ?: return
        val content = pairingContent ?: return
        val st = MatterService.status(scene.id, this)
        if (st.commissioned) {
            Toast.makeText(this, "${scene.title} is now in Google Home", Toast.LENGTH_LONG).show()
            pairingDialog?.dismiss()
            return
        }
        val payload = st.qrPayload
        val code = st.pairingCode
        val shown = "$payload|$code"
        if (content.tag == shown) return
        content.tag = shown
        content.removeAllViews()
        if (payload == null || code == null) {
            content.addView(TextView(this).apply {
                text = "Preparing…"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            return
        }
        val size = dp(300)
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        content.addView(ImageView(this).apply { setImageBitmap(bitmap) })
        content.addView(TextView(this).apply {
            text = "In the Google Home app: + → Device → Matter-enabled device, then scan this code " +
                "or enter $code. Name it \"${scene.title}\"."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
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
