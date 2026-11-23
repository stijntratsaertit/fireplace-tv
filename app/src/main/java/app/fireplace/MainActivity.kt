package app.fireplace

import android.app.Activity
import android.content.Intent
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The main app tile: a remote-friendly list of every scene (including ones without a tile),
 * plus the auto-start settings.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("fireplace", MODE_PRIVATE) }
    private lateinit var scenes: List<Scene>
    private lateinit var modeRow: Row
    private lateinit var sceneRow: Row
    private lateinit var permissionRow: Row

    /** A focusable card with a title and an updatable subtitle. */
    private class Row(val view: View, val subtitle: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        list.addView(heading("Start when the TV turns on", 22f).apply { setPadding(dp(8), dp(28), 0, dp(12)) })
        modeRow = row("Auto-start", "") {
            val modes = AutoStart.Mode.entries
            AutoStart.setMode(this, modes[(AutoStart.mode(this).ordinal + 1) % modes.size])
            refreshSettings()
        }
        sceneRow = row("Scene to start", "") {
            val choices = listOf(AutoStart.SCENE_SEASONAL, AutoStart.SCENE_LAST) + scenes.map { it.id }
            val next = choices[(choices.indexOf(AutoStart.sceneChoice(this)) + 1) % choices.size]
            AutoStart.setSceneChoice(this, next)
            refreshSettings()
        }
        permissionRow = row("Allow auto-start", "") { openOverlaySettings() }
        list.addView(modeRow.view)
        list.addView(sceneRow.view)
        list.addView(permissionRow.view)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#140C08"))
            addView(list, MATCH_PARENT, WRAP_CONTENT)
        })
        focusTarget?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the permission screen, or after an update: show the current state.
        refreshSettings()
        AutoStart.sync(this)
    }

    private fun refreshSettings() {
        val mode = AutoStart.mode(this)
        modeRow.subtitle.text = mode.label + "  ·  press OK to change"
        sceneRow.subtitle.text = AutoStart.sceneChoiceLabel(AutoStart.sceneChoice(this), scenes)
        sceneRow.view.visibility = if (mode == AutoStart.Mode.OFF) View.GONE else View.VISIBLE
        val allowed = AutoStart.canOpenFromBackground(this)
        permissionRow.view.visibility = if (mode == AutoStart.Mode.OFF || allowed) View.GONE else View.VISIBLE
        permissionRow.subtitle.text =
            "Needs \"Display over other apps\". Press OK, then turn it on for \"${applicationInfo.loadLabel(packageManager)}\"."
    }

    private fun openOverlaySettings() {
        val direct = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        val list = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        val opened = runCatching { startActivity(direct) }.isSuccess || runCatching { startActivity(list) }.isSuccess
        if (!opened) {
            Toast.makeText(
                this,
                "Open Settings → Apps → Special app access → Display over other apps → ${applicationInfo.loadLabel(packageManager)}",
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
