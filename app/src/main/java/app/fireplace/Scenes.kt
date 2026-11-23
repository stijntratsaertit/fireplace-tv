package app.fireplace

import android.content.Context
import org.json.JSONObject

data class Scene(
    val id: String,
    val title: String,
    val subtitle: String,
    /** YouTube search; the app picks a random long result each time. */
    val search: String,
    /** Specific YouTube videos or playlists, used alongside the search. */
    val urls: List<String>,
    /** Search results shorter than this are skipped (live streams are always allowed). */
    val minMinutes: Int,
)

object Scenes {
    fun load(context: Context): List<Scene> {
        val json = context.assets.open("scenes.json").bufferedReader().use { JSONObject(it.readText()) }
        val list = json.getJSONArray("scenes")
        return List(list.length()) { i ->
            val s = list.getJSONObject(i)
            val urls = s.optJSONArray("urls")
            Scene(
                id = s.getString("id"),
                title = s.getString("title"),
                subtitle = s.optString("subtitle"),
                search = s.optString("search"),
                urls = if (urls == null) emptyList() else List(urls.length()) { urls.getString(it) },
                minMinutes = s.optInt("minMinutes", 60),
            )
        }
    }
}
