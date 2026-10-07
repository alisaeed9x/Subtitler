package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Backend adapter copied from the old Anime Witcher app's network model.
 *
 * The old APK (com.anime.witcher) uses:
 *  - Firebase Firestore project: animewitcher-1c66d
 *  - Firestore collection: anime_list
 *  - episodes summary: anime_list/{animeId}/episodes_summery/summery
 *  - episode servers: anime_list/{animeId}/episodes/{001}/servers
 *  - Algolia index: series
 *
 * No new private API/server is introduced here. The new app reads the same
 * backend directly and hands the selected media URL to the existing PlayerActivity.
 */
object WitcherApi {
    const val FIRESTORE_ROOT =
        "https://firestore.googleapis.com/v1/projects/animewitcher-1c66d/databases/(default)/documents"

    private const val ALGOLIA_ENDPOINT =
        "https://pm74amwqb7-dsn.algolia.net/1/indexes/series/query"
    private const val ALGOLIA_APP_ID = "PM74AMWQB7"
    private const val ALGOLIA_API_KEY = "637988febef474435052d8dc083b77be"

    private val pool = Executors.newCachedThreadPool()

    data class Series(
        val id: String,
        val name: String,
        val englishName: String = "",
        val type: String = "",
        val poster: String = "",
        val cover: String = "",
        val year: Int = 0,
        val story: String = "",
        val state: String = "",
        val epsNum: Int = 0,
        val updatedAt: String = ""
    )

    data class Episode(
        val id: String,
        val number: Int,
        val name: String,
        val thumb: String = "",
        val filler: Boolean = false
    )

    data class Server(
        val id: String,
        val name: String,
        val link: String,
        val quality: String,
        val visible: Boolean = false,
        val openBrowser: Boolean = false,
        val updateServer: String = ""
    )

    data class EpisodeWithServers(
        val episode: Episode,
        val servers: List<Server>
    )

    data class ItemDetails(
        val series: Series,
        val episodes: List<Episode>
    )

    data class Home(
        val newEpisodes: List<Pair<Series, Episode>>,
        val latest: List<Series>
    )

    fun home(): Home = pool.submit<Home> {
        // The old UI labels these data sets "new_episodes" and "new_added_anime".
        // Those names are kept as a first attempt; if the backend layout changes,
        // the fallback reads the same anime_list/series data instead of inventing data.
        val newSeries = safeListCollection("new_added_anime", 24)
            .mapNotNull { documentToSeries(it) }
        val latest = if (newSeries.isNotEmpty()) newSeries else algoliaSearch("").take(24)

        val newEpisodeDocs = safeListCollection("new_episodes", 24)
        val episodePairs = ArrayList<Pair<Series, Episode>>()
        for (d in newEpisodeDocs) {
            val f = d.optJSONObject("fields") ?: JSONObject()
            val id = f.optStringAny("anime_id", "animeId", "series_id", "seriesId") ?: continue
            val epNo = f.optIntAny("episode_no", "episode_number", "episodeNo") ?: continue
            val s = try { getAnime(id).series } catch (_: Throwable) { continue }
            val e = Episode(
                id = String.format(Locale.US, "%03d", epNo),
                number = epNo,
                name = f.optStringAny("name", "episode_name", "episode_title") ?: "الحلقة $epNo",
                thumb = f.optStringAny("thumb_uri", "thumb", "image") ?: ""
            )
            episodePairs += s to e
        }

        if (episodePairs.isNotEmpty()) return@submit Home(episodePairs, latest)

        // Stable fallback: take the first results from the old Algolia index and
        // resolve their episode summaries through the old Firestore collection.
        val pairs = ArrayList<Pair<Series, Episode>>()
        for (s in latest.take(12)) {
            val eps = try { getEpisodes(s.id) } catch (_: Throwable) { emptyList() }
            val ep = eps.maxByOrNull { it.number } ?: continue
            pairs += s to ep
        }
        Home(pairs, latest)
    }.get()

    fun search(query: String): List<Series> = pool.submit<List<Series>> {
        algoliaSearch(query.trim())
    }.get()

    fun getAnime(id: String): ItemDetails = pool.submit<ItemDetails> {
        val doc = getObject("/anime_list/${path(id)}")
        val series = parseSeries(doc, id)
        val episodes = getEpisodes(id)
        ItemDetails(series, episodes)
    }.get()

    fun getServers(animeId: String, episodeNumber: Int): List<Server> = pool.submit<List<Server>> {
        val padded = String.format(Locale.US, "%03d", episodeNumber)
        val obj = getObjectAllow404("/anime_list/${path(animeId)}/episodes/$padded/servers") ?: return@submit emptyList()
        val docs = obj.optJSONArray("documents") ?: JSONArray()
        val out = ArrayList<Server>()
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val f = d.optJSONObject("fields") ?: continue
            val name = f.optStringAny("name") ?: continue
            val link = f.optStringAny("link") ?: continue
            val quality = f.optStringAny("quality") ?: ""
            if (name.isBlank() || link.isBlank()) continue
            out += Server(
                id = d.optString("name").substringAfterLast('/'),
                name = name,
                link = link,
                quality = quality,
                visible = f.optBooleanAny("visible"),
                openBrowser = f.optBooleanAny("open_browser", "openBrowser"),
                updateServer = f.optStringAny("update_server", "updateServer") ?: ""
            )
        }
        out
    }.get()

    fun resolvePlayable(animeId: String, episodeNumber: Int): Server? {
        val servers = getServers(animeId, episodeNumber)
        if (servers.isEmpty()) return null

        val ranked = servers.sortedWith(
            compareByDescending<Server> { !it.openBrowser }
                .thenByDescending { it.visible }
                .thenByDescending { qualityScore(it.quality) }
                .thenByDescending { isLikelyDirectMedia(it.link) }
        )
        for (server in ranked) {
            val finalUrl = followRedirectIfUseful(server.link)
            if (finalUrl.isNotBlank()) return server.copy(link = finalUrl)
        }
        return ranked.first()
    }

    private fun algoliaSearch(query: String): List<Series> {
        val params = "attributesToRetrieve=" +
            URLEncoder.encode(
                "[\"objectID\",\"name\",\"poster_uri\",\"order\",\"path\",\"type\",\"poster\",\"tags\",\"details\",\"rating\"]",
                "UTF-8"
            ) +
            "&hitsPerPage=500&page=0&query=" + URLEncoder.encode(query, "UTF-8")

        val body = JSONObject().put("params", params).toString()
        val raw = requestRaw(ALGOLIA_ENDPOINT, "POST", body, mapOf(
            "Content-Type" to "application/json; charset=UTF-8",
            "Accept" to "application/json",
            "X-Algolia-Application-Id" to ALGOLIA_APP_ID,
            "X-Algolia-API-Key" to ALGOLIA_API_KEY,
            "User-Agent" to "Algolia for Android (3.27.0); Android (11)"
        ))
        val hits = raw.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { parseAlgoliaSeries(hits.optJSONObject(it)) }
    }

    private fun getEpisodes(animeId: String): List<Episode> {
        val summary = getObjectAllow404("/anime_list/${path(animeId)}/episodes_summery/summery")
        if (summary != null) {
            val values = summary.optJSONObject("fields")
                ?.optJSONObject("episodes")
                ?.optJSONObject("arrayValue")
                ?.optJSONArray("values")
            if (values != null) {
                val out = parseEpisodeArray(values)
                if (out.isNotEmpty()) return out.sortedBy { it.number }
            }
        }

        val docs = getObjectAllow404("/anime_list/${path(animeId)}/episodes")
        val arr = docs?.optJSONArray("documents") ?: return emptyList()
        val out = ArrayList<Episode>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val f = d.optJSONObject("fields") ?: continue
            val docId = f.optStringAny("doc_id") ?: d.optString("name").substringAfterLast('/')
            val number = docId.filter { it.isDigit() }.trimStart('0').toIntOrNull() ?: 1
            out += Episode(
                id = docId,
                number = number,
                name = f.optStringAny("name", "episode_name", "episode_title") ?: "الحلقة $number",
                thumb = f.optStringAny("thumb_uri", "thumb") ?: "",
                filler = f.optBooleanAny("filler")
            )
        }
        return out.sortedBy { it.number }
    }

    private fun parseEpisodeArray(values: JSONArray): List<Episode> {
        val out = ArrayList<Episode>()
        for (i in 0 until values.length()) {
            val fields = values.optJSONObject(i)?.optJSONObject("mapValue")?.optJSONObject("fields") ?: continue
            val docId = fields.optStringAny("doc_id") ?: ""
            val number = docId.filter { it.isDigit() }.trimStart('0').toIntOrNull() ?: continue
            out += Episode(
                id = docId,
                number = number,
                name = fields.optStringAny("name", "episode_name", "episode_title") ?: "الحلقة $number",
                thumb = fields.optStringAny("thumb_uri", "thumb") ?: "",
                filler = fields.optBooleanAny("filler")
            )
        }
        return out
    }

    private fun parseAlgoliaSeries(o: JSONObject?): Series? {
        if (o == null) return null
        val details = o.optJSONObject("details") ?: JSONObject()
        val poster = o.optJSONObject("poster") ?: JSONObject()
        return Series(
            id = o.optString("objectID"),
            name = o.optString("name"),
            englishName = details.optString("english_title"),
            type = o.optString("type"),
            poster = poster.optString("large").ifBlank { poster.optString("medium").ifBlank { o.optString("poster_uri") } },
            cover = poster.optString("large").ifBlank { poster.optString("medium") },
            year = details.optInt("year", 0),
            story = details.optString("story").ifBlank { o.optString("story") },
            state = details.optString("state"),
            epsNum = details.optInt("eps_num", 0)
        )
    }

    private fun parseSeries(doc: JSONObject, id: String): Series {
        val f = doc.optJSONObject("fields") ?: JSONObject()
        val d = f.optJSONObject("details")?.optJSONObject("mapValue")?.optJSONObject("fields") ?: JSONObject()
        val p = f.optJSONObject("poster")?.optJSONObject("mapValue")?.optJSONObject("fields") ?: JSONObject()
        return Series(
            id = id,
            name = f.optStringAny("name") ?: "",
            englishName = d.optStringAny("english_title") ?: "",
            type = f.optStringAny("type") ?: "",
            poster = p.optStringAny("large", "medium") ?: f.optStringAny("poster_uri") ?: "",
            cover = f.optStringAny("cover_uri") ?: p.optStringAny("large", "medium") ?: "",
            year = d.optStringAny("year")?.toIntOrNull() ?: 0,
            story = f.optStringAny("story") ?: "",
            state = d.optStringAny("state") ?: "",
            epsNum = d.optStringAny("eps_num")?.toIntOrNull() ?: 0,
            updatedAt = doc.optString("updateTime")
        )
    }

    private fun safeListCollection(collection: String, limit: Int): List<JSONObject> {
        val o = getObjectAllow404("/$collection?pageSize=$limit") ?: return emptyList()
        val arr = o.optJSONArray("documents") ?: return emptyList()
        return (0 until minOf(arr.length(), limit)).mapNotNull { arr.optJSONObject(it) }
    }

    private fun documentToSeries(d: JSONObject): Series? {
        val id = d.optString("name").substringAfterLast('/')
        if (id.isBlank()) return null
        return try { parseSeries(d, id) } catch (_: Throwable) { null }
    }

    private fun getObject(path: String): JSONObject = getObjectAllow404(path)
        ?: throw IllegalStateException("Anime Witcher API request returned 404: $path")

    private fun getObjectAllow404(path: String): JSONObject? = try {
        val o = requestRaw(FIRESTORE_ROOT + path, "GET", null, mapOf("Accept" to "application/json"))
        o
    } catch (_: HttpFailure) {
        null
    }

    private fun requestRaw(url: String, method: String, body: String?, headers: Map<String, String>): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = c.responseCode
        val input = if (code in 200..299) c.inputStream else c.errorStream
        val text = BufferedReader(InputStreamReader(input ?: error("Empty response"))).use { it.readText() }
        c.disconnect()
        if (code !in 200..299) throw HttpFailure(code, text)
        return JSONObject(text)
    }

    private fun followRedirectIfUseful(input: String): String {
        return try {
            val c = (URL(input).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
                connect()
            }
            val finalUrl = c.url?.toString().orEmpty().ifBlank { input }
            val type = c.contentType.orEmpty().lowercase(Locale.US)
            c.disconnect()
            if (type.contains("video") || type.contains("mpegurl") || type.contains("x-mpegurl") || isLikelyDirectMedia(finalUrl)) finalUrl else input
        } catch (_: Throwable) {
            input
        }
    }

    private fun isLikelyDirectMedia(url: String): Boolean {
        val u = url.lowercase(Locale.US)
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".mkv") ||
            u.contains(".webm") || u.contains("mime=video") || u.contains("type=video")
    }

    private fun qualityScore(q: String): Int = when (q.lowercase(Locale.US)) {
        "1080p" -> 5
        "720p" -> 4
        "480p" -> 3
        "360p" -> 2
        "240p" -> 1
        else -> 0
    }

    private fun path(v: String): String = URLEncoder.encode(v, "UTF-8")

    private class HttpFailure(val code: Int, val payload: String) : RuntimeException("HTTP $code")

    private fun JSONObject.optStringAny(vararg keys: String): String? {
        for (k in keys) {
            val v = optString(k, "").takeIf { it.isNotBlank() }
            if (v != null) return v
        }
        return null
    }

    private fun JSONObject.optIntAny(vararg keys: String): Int? {
        for (k in keys) {
            if (has(k)) {
                val n = optInt(k, Int.MIN_VALUE)
                if (n != Int.MIN_VALUE) return n
                optString(k).toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.optBooleanAny(vararg keys: String): Boolean {
        for (k in keys) if (has(k)) return optBoolean(k, false)
        return false
    }
}
