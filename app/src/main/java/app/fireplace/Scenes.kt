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
    /**
     * Words or phrases that rule a search result out: whole-word, case- and accent-insensitive
     * match on the title. Includes the global "exclude" list.
     */
    val exclude: List<String> = emptyList(),
    /** If set, a search result's title must mention at least one of these (e.g. "jazz"). */
    val require: List<String> = emptyList(),
) {
    /** Whether a video title fits this scene (none of [exclude], and one of [require] if set). */
    fun accepts(videoTitle: String): Boolean {
        val title = normalize(videoTitle)
        if (exclude.any { containsWord(title, normalize(it)) }) return false
        return require.isEmpty() || require.any { containsWord(title, normalize(it)) }
    }

    private companion object {
        /** Lower case without accents, so "Café" matches "cafe". */
        fun normalize(text: String): String =
            java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()

        fun containsWord(text: String, word: String): Boolean =
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(word) + "(?![\\p{L}\\p{N}])").containsMatchIn(text)
    }
}

private fun org.json.JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else List(length()) { getString(it).trim() }.filter { it.isNotEmpty() }

object Scenes {
    fun load(context: Context): List<Scene> {
        val json = context.assets.open("scenes.json").bufferedReader().use { JSONObject(it.readText()) }
        val global = json.optJSONArray("exclude").strings()
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
                exclude = (global + s.optJSONArray("exclude").strings()).distinct(),
                require = s.optJSONArray("require").strings(),
            )
        }
    }
}
