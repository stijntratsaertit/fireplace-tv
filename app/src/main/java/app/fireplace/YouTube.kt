package app.fireplace

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

/** A video the scene could play. */
data class Candidate(val url: String, val title: String)

/** What the player needs to play one video. */
sealed class Playable {
    abstract val title: String
    abstract val userAgent: String

    data class Hls(override val title: String, override val userAgent: String, val url: String) : Playable()
    data class Split(override val title: String, override val userAgent: String, val video: String, val audio: String) : Playable()
    data class Single(override val title: String, override val userAgent: String, val url: String) : Playable()
}

/** YouTube search and stream lookup, done on the TV itself through NewPipeExtractor. */
object YouTube {
    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    private val service = ServiceList.YouTube
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                NewPipe.init(OkDownloader)
                ready = true
            }
        }
    }

    /** All videos the scene could play: its fixed links, plus long videos from its search. */
    fun candidates(scene: Scene): List<Candidate> {
        init()
        val found = LinkedHashMap<String, Candidate>()
        for (url in scene.urls) {
            if ("list=" in url) {
                runCatching { PlaylistInfo.getInfo(service, url) }.getOrNull()?.relatedItems
                    ?.filterIsInstance<StreamInfoItem>()
                    ?.forEach { found[it.url] = Candidate(it.url, it.name) }
            } else {
                found[url] = Candidate(url, "")
            }
        }
        if (scene.search.isNotBlank()) {
            val handler = service.searchQHFactory.fromQuery(scene.search, listOf("videos"), "")
            val first = SearchInfo.getInfo(service, handler)
            val items = first.relatedItems.filterIsInstance<StreamInfoItem>().toMutableList()
            // A few more pages, since the title filters below drop some results.
            var page = first.nextPage
            repeat(2) {
                if (page == null) return@repeat
                val more = runCatching { SearchInfo.getMoreItems(service, handler, page) }.getOrNull()
                more?.items?.filterIsInstance<StreamInfoItem>()?.let(items::addAll)
                page = more?.nextPage
            }
            val minSeconds = scene.minMinutes * 60L
            items.removeAll { !scene.accepts(it.name) }
            val long = items.filter { it.streamType == StreamType.LIVE_STREAM || it.duration >= minSeconds }
            // If nothing is long enough, fall back to the longest results rather than nothing.
            val chosen = long.ifEmpty { items.sortedByDescending { it.duration }.take(5) }
            chosen.forEach { found.putIfAbsent(it.url, Candidate(it.url, it.name)) }
        }
        return found.values.toList()
    }

    /** Looks up the playable streams for one video. */
    fun resolve(candidate: Candidate): Playable {
        init()
        val info = StreamInfo.getInfo(service, candidate.url)
        val title = info.name.ifBlank { candidate.title }
        val anyUrl = (info.videoOnlyStreams + info.videoStreams + info.audioStreams)
            .firstOrNull { it.isUrl }?.content.orEmpty()
        val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(anyUrl) || anyUrl.isEmpty()) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            BROWSER_UA
        }

        // HLS adapts quality to the connection and works for live streams too.
        if (info.hlsUrl.isNotEmpty()) return Playable.Hls(title, ua, info.hlsUrl)

        val video = info.videoOnlyStreams.bestVideo()
        val audio = info.audioStreams.bestAudio()
        if (video != null && audio != null) return Playable.Split(title, ua, video.content, audio.content)

        val muxed = info.videoStreams.bestVideo() ?: throw IllegalStateException("No playable stream for $title")
        return Playable.Single(title, ua, muxed.content)
    }

    private fun List<VideoStream>.bestVideo(): VideoStream? = this
        .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..2160 }
        // Highest resolution; at equal height prefer H.264, then VP9, then AV1 for the widest hardware support.
        .sortedWith(compareByDescending<VideoStream> { it.height }.thenBy { codecRank(it.codec) })
        .firstOrNull()

    private fun codecRank(codec: String?): Int = when {
        codec == null -> 3
        codec.startsWith("avc") -> 0
        codec.startsWith("vp9") || codec.startsWith("vp09") -> 1
        else -> 2
    }

    private fun List<AudioStream>.bestAudio(): AudioStream? = this
        .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        .maxByOrNull { it.averageBitrate }

    private object OkDownloader : Downloader() {
        override fun execute(request: NpRequest): NpResponse {
            val body = request.dataToSend()?.toRequestBody()
            val builder = Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), body)
                .header("User-Agent", BROWSER_UA)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            client.newCall(builder.build()).execute().use { r ->
                return NpResponse(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }
}
