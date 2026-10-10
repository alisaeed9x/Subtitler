package com.tttt.subtitler

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** (v197) لينك لأغنية: exact = لينك مباشر لأقرب نتيجة · غير كده = صفحة بحث في الخدمة */
class SongLink(val label: String, val url: String, val exact: Boolean = false, val kind: String = "web")

/**
 * (v197) بيجيب لينكات تشغيل لأغنية (اسم + فنان) من غير أي مفاتيح:
 *  • quick(): صفحات بحث جاهزة فورًا (Spotify · YouTube Music · أنغامي · SoundCloud · جوجل)
 *  • exact(): لينك مباشر لأقرب نتيجة من YouTube (من صفحة نتائج البحث) و Deezer و iTunes (ومعاهم مقطع 30ث)، على خيط خلفية
 */
object SongLinks {
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun query(title: String, artist: String) = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")

    fun quick(title: String, artist: String): List<SongLink> {
        val q = enc(query(title, artist))
        return listOf(
            SongLink("🟢 Spotify", "https://open.spotify.com/search/$q", false, "spotify"),
            SongLink("🔴 YouTube Music", "https://music.youtube.com/search?q=$q", false, "ytm"),
            SongLink("🟣 أنغامي", "https://play.anghami.com/search/$q", false, "anghami"),
            SongLink("🟠 SoundCloud", "https://soundcloud.com/search?q=$q", false, "sc"),
            SongLink("🔎 جوجل", "https://www.google.com/search?q=" + enc(query(title, artist) + " listen"), false, "web")
        )
    }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 9000
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** على خيط خلفية. onLink بيتنادى من الخيط ده (حوّله للـ main بنفسك). onDone بعد الكل. */
    fun exact(title: String, artist: String, onLink: (SongLink) -> Unit, onDone: () -> Unit) {
        val q = enc(query(title, artist))
        Thread {
            // YouTube: أول فيديو في نتائج البحث
            try {
                val html = get("https://www.youtube.com/results?search_query=$q")
                val m = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)
                if (m != null) onLink(SongLink("▶️ YouTube (أقرب نتيجة)", "https://www.youtube.com/watch?v=" + m.groupValues[1], true, "yt"))
            } catch (e: Throwable) { LogStore.err("songlinks-yt", e) }
            // Deezer
            try {
                val t = JSONObject(get("https://api.deezer.com/search?limit=1&q=$q")).optJSONArray("data")?.optJSONObject(0)
                if (t != null) {
                    val link = t.optString("link"); val pv = t.optString("preview")
                    if (link.isNotBlank()) onLink(SongLink("🎶 Deezer: " + t.optString("title_short").take(24), link, true, "deezer"))
                    if (pv.isNotBlank()) onLink(SongLink("🔊 مقطع 30ث", pv, true, "preview"))
                }
            } catch (e: Throwable) { LogStore.err("songlinks-deezer", e) }
            // iTunes
            try {
                val t = JSONObject(get("https://itunes.apple.com/search?media=music&limit=1&term=$q")).optJSONArray("results")?.optJSONObject(0)
                val v = t?.optString("trackViewUrl").orEmpty()
                if (v.isNotBlank()) onLink(SongLink("🍎 Apple Music", v, true, "apple"))
            } catch (e: Throwable) { LogStore.err("songlinks-itunes", e) }
            onDone()
        }.apply { isDaemon = true }.start()
    }
}
