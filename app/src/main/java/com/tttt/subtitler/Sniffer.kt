package com.tttt.subtitler

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient.CustomViewCallback
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

/** فيديو اتلقط (زي «لينك التحميل» في 1DM): رابط + نوعه + مصدره (ref/ua) عشان المشغّل يفتحه بنفس الهيدرز */
class Found(val url: String, val kind: String, val title: String = "", val ref: String = "", val ua: String = "") {
    @Volatile var size = -1L
    @Volatile var mime = ""
}

/** منطق الصيد (من غير Android): إيه اللي يتحسب فيديو، وتنضيف الروابط، وأسماء العرض */
object Sniff {
    private val EXT = Regex("\\.(mp4|m3u8|webm|mkv|mov|m4v|flv|3gp)(?=[?#]|$)", RegexOption.IGNORE_CASE)
    private val JUNK = Regex("(doubleclick|googlesyndication|adservice|/ads?/|/preroll|analytics|\\.gif)", RegexOption.IGNORE_CASE)

    fun isDirect(u: String) = u.startsWith("http", true) && EXT.containsMatchIn(u)

    /** رابط الطلب → رابط فيديو صالح أو null */
    fun accept(raw: String): String? {
        if (!raw.startsWith("http", true) || JUNK.containsMatchIn(raw)) return null
        if (raw.contains("googlevideo.com/videoplayback")) {
            // بس الصيغ اللي فيها صوت وصورة مع بعض (18 = 360p، 22 = 720p)؛ باقي الـ itags صوت أو صورة لوحدها
            val itag = Regex("[?&]itag=(\\d+)").find(raw)?.groupValues?.get(1)
            if (itag != "18" && itag != "22") return null
            var u = raw.replace(Regex("[?&](range|rn|rbuf)=[^&]*"), "")
            if (!u.contains('?') && u.contains('&')) u = u.replaceFirst('&', '?')
            return u
        }
        return if (EXT.containsMatchIn(raw)) raw else null
    }
    fun kindOf(u: String): String {
        val p = u.substringBefore('?').lowercase()
        return when {
            p.endsWith(".m3u8") -> "HLS"
            p.endsWith(".webm") -> "WEBM"
            p.endsWith(".mkv") -> "MKV"
            u.contains("videoplayback") -> "YT"
            else -> "MP4"
        }
    }
    /** مفتاح منع التكرار */
    fun key(u: String) = if (u.contains("videoplayback")) u else u.substringBefore('?').substringBefore('#')
    fun nameOf(u: String): String {
        val n = u.substringBefore('?').substringBefore('#').substringAfterLast('/')
        return if (n.isNotEmpty() && !n.contains(':')) n else u.substringAfter("://").substringBefore('/')
    }
    /** أقل حجم يتحسب فيديو: أصغر من كده غالبًا إعلان */
    const val MIN_BYTES = 3L * 1024 * 1024
    private val SEP = Regex("\\s+[|\\-–—»·•]\\s+")
    private val LEAD = Regex("^(watch|download|stream|مشاهدة|تحميل|تنزيل|شاهد)\\s+", RegexOption.IGNORE_CASE)
    /** عنوان صفحة الموقع → اسم نضيف (بيشيل اسم الموقع اللي بعد | أو - وكلمات زي مشاهدة/Watch) */
    fun cleanTitle(t: String?): String {
        var x = (t ?: "").trim()
        if (x.isEmpty() || x.startsWith("http", true) || x.equals("about:blank", true)) return ""
        val parts = x.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > 1) x = parts.maxByOrNull { it.length } ?: x
        x = x.replace(LEAD, "").trim()
        return if (x.length > 2) x.take(120) else ""
    }
    fun fmtSize(b: Long): String = if (b >= 1_048_576L * 1024) String.format("%.2f GB", b / 1073741824.0) else String.format("%.1f MB", b / 1048576.0)
}

/** مانع إعلانات مدمج: بيقطع طلبات شبكات الإعلانات والبوب-أب، وبيخبّي عناصر الإعلان في الصفحة */
object AdBlock {
    private val HOSTS = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "google-analytics.com",
        "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com", "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "juicyads.com",
        "trafficjunky.net", "clickadu.com", "adsterra.com", "hilltopads.net", "onclickads.net", "mgid.com", "revcontent.com", "criteo.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "amazon-adsystem.com", "adform.net", "smartadserver.com", "yieldmo.com",
        "bidswitch.net", "2mdn.net", "moatads.com", "scorecardresearch.com", "admaven.com", "ad-maven.com", "a-ads.com", "adcash.com",
        "monetag.com", "galaksion.com", "trafficstars.com", "adskeeper.com", "zedo.com", "yllix.com", "popunder.net", "adspyglass.com",
        "ero-advertising.com", "tsyndicate.com", "realsrv.com", "syndication.exdynsrv.com", "exdynsrv.com", "pushails.com", "richpush.co")
    private val PATH = Regex("(/popunder|/pop\\.js|/adserver|/banner_ads|/prebid|/vast\\.xml|/vpaid)", RegexOption.IGNORE_CASE)
    fun blocked(url: String): Boolean {
        val host = try { Uri.parse(url).host?.lowercase() } catch (_: Throwable) { null } ?: return false
        val parts = host.split('.')
        for (i in 0 until parts.size - 1) if (parts.subList(i, parts.size).joinToString(".") in HOSTS) return true
        return PATH.containsMatchIn(url)
    }
    const val CSS = "ins.adsbygoogle,.adsbygoogle,[id^=div-gpt-ad],[id*=google_ads],[class*=ad-banner],[class*=adbanner],[class*=popup-ad],[id*=popup-ad]," +
        "[class*=sponsored-],[id^=aswift],iframe[src*=doubleclick],iframe[src*=googlesyndication],iframe[src*=adsterra],iframe[src*=exoclick]," +
        "iframe[src*=propeller],a[href*=popads],a[href*=exoclick],a[href*=adsterra]{display:none!important;visibility:hidden!important}"
    val JS = "(function(){try{window.open=function(){return null};" +
        "if(!document.getElementById('__svab')){var st=document.createElement('style');st.id='__svab';st.textContent='" + CSS + "';(document.head||document.documentElement).appendChild(st)}" +
        "}catch(e){}})()"
}

/**
 * استخراج روابط يوتيوب المباشرة (صيغ فيها صوت وصورة + HLS لو موجود) من نفس واجهة تطبيق يوتيوب.
 * ملحوظة: يوتيوب بيغيّر الواجهة دي كل شوية، فلو فشلت البرنامج بيرجع لصيد المتصفح.
 */
object YtExtract {
    private val ID = Regex("(?:youtu\\.be/|youtube(?:-nocookie)?\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|embed/|live/|v/))([A-Za-z0-9_-]{11})")
    fun videoId(u: String): String? = ID.find(u)?.groupValues?.get(1)

    private class Cl(val name: String, val ver: String, val id: Int, val ua: String, val extra: String)
    private val clients = listOf(
        Cl("ANDROID_VR", "1.60.19", 28, "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            "\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\",\"osName\":\"Android\",\"osVersion\":\"12L\",\"androidSdkVersion\":32"),
        Cl("ANDROID", "19.44.38", 3, "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip",
            "\"osName\":\"Android\",\"osVersion\":\"14\",\"androidSdkVersion\":34"),
        Cl("IOS", "19.45.4", 5, "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            "\"deviceMake\":\"Apple\",\"deviceModel\":\"iPhone16,2\",\"osName\":\"iPhone\",\"osVersion\":\"18.1.0.22B83\"")
    )

    /** بيشتغل على thread خلفي */
    fun fetch(id: String): List<Found> {
        val out = ArrayList<Found>(); val seen = HashSet<String>()
        for (c in clients) {
            try {
                val body = "{\"context\":{\"client\":{\"clientName\":\"${c.name}\",\"clientVersion\":\"${c.ver}\",${c.extra},\"hl\":\"en\",\"gl\":\"US\"}},\"videoId\":\"$id\",\"contentCheckOk\":true,\"racyCheckOk\":true}"
                val con = URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection() as HttpURLConnection
                con.requestMethod = "POST"; con.connectTimeout = 10000; con.readTimeout = 15000; con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json"); con.setRequestProperty("User-Agent", c.ua)
                con.setRequestProperty("X-YouTube-Client-Name", c.id.toString()); con.setRequestProperty("X-YouTube-Client-Version", c.ver)
                con.setRequestProperty("Origin", "https://www.youtube.com")
                con.outputStream.use { it.write(body.toByteArray()) }
                if (con.responseCode != 200) { con.disconnect(); continue }
                val txt = con.inputStream.bufferedReader().use { it.readText() }
                con.disconnect()
                val j = JSONObject(txt)
                if (j.optJSONObject("playabilityStatus")?.optString("status") != "OK") continue
                val title = j.optJSONObject("videoDetails")?.optString("title") ?: ""
                val sd = j.optJSONObject("streamingData") ?: continue
                val fm = sd.optJSONArray("formats")
                if (fm != null) for (i in 0 until fm.length()) {
                    val f = fm.getJSONObject(i); val u = f.optString("url")
                    if (u.isEmpty() || !seen.add(u)) continue
                    val q = f.optString("qualityLabel").ifEmpty { f.optInt("height").toString() + "p" }
                    val fo = Found(u, "YT $q", title, "https://www.youtube.com/", c.ua)
                    fo.mime = f.optString("mimeType").substringBefore(';')
                    f.optString("contentLength").toLongOrNull()?.let { fo.size = it }
                    out.add(fo)
                }
                val hls = sd.optString("hlsManifestUrl")
                if (hls.isNotEmpty() && seen.add(hls)) out.add(Found(hls, "HLS", title, "https://www.youtube.com/", c.ua))
                if (out.isNotEmpty()) break
            } catch (e: Throwable) { LogStore.err("Sniffer:169", e) }
        }
        return out
    }

    /** (v118) خيار جودة: رابط فيديو + (لو الصورة لوحدها) رابط صوت بيتدمج معاه في المشغّل — زي NewPipe/SnapTube */
    class Opt(val label: String, val url: String, val audio: String?, val h: Int)
    /** نتيجة الاستخراج: الخيار المختار + كل الجودات المتاحة (لزرار الجودة في المشغّل) */
    class Pick(val url: String, val audio: String?, val title: String, val ua: String, val label: String, val opts: List<Opt>) {
        fun optsJson(): String { val a = JSONArray(); for (o in opts) a.put(JSONObject().put("l", o.label).put("u", o.url).put("a", o.audio ?: "").put("h", o.h)); return a.toString() }
    }

    private fun playerJson(c: Cl, id: String): JSONObject? {
        val body = "{\"context\":{\"client\":{\"clientName\":\"${c.name}\",\"clientVersion\":\"${c.ver}\",${c.extra},\"hl\":\"en\",\"gl\":\"US\"}},\"videoId\":\"$id\",\"contentCheckOk\":true,\"racyCheckOk\":true}"
        val con = URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"; con.connectTimeout = 10000; con.readTimeout = 15000; con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json"); con.setRequestProperty("User-Agent", c.ua)
            con.setRequestProperty("X-YouTube-Client-Name", c.id.toString()); con.setRequestProperty("X-YouTube-Client-Version", c.ver)
            con.setRequestProperty("Origin", "https://www.youtube.com")
            con.outputStream.use { it.write(body.toByteArray()) }
            if (con.responseCode != 200) return null
            val j = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            return if (j.optJSONObject("playabilityStatus")?.optString("status") == "OK") j else null
        } finally { con.disconnect() }
    }

    private fun mb(n: Long) = if (n > 0) " · " + (if (n >= 10_485_760L) (n / 1_048_576L).toString() else String.format("%.1f", n / 1048576.0)) + "MB" else ""

    /**
     * بيجرّب العملاء واحد ورا التاني ويرجّع كل الجودات المتاحة (144p…1080p بحجمها التقريبي).
     * maxH > 0: بيختار أعلى جودة لحد الارتفاع ده (للداتا القليلة)، غير كده أعلى جودة متاحة.
     */
    fun fetchPick(id: String, maxH: Int = 0): Pick? {
        for (c in clients) {
            try {
                val j = playerJson(c, id) ?: continue
                val title = j.optJSONObject("videoDetails")?.optString("title") ?: ""
                val sd = j.optJSONObject("streamingData") ?: continue
                val byH = HashMap<Int, Opt>()
                // صيغ فيها صوت وصورة (الأبسط): بتتفضّل لو نفس الجودة
                sd.optJSONArray("formats")?.let { a -> for (i in 0 until a.length()) {
                    val f = a.optJSONObject(i) ?: continue
                    val u = f.optString("url"); val h = f.optInt("height")
                    if (u.isEmpty() || h <= 0) continue
                    byH[h] = Opt(h.toString() + "p" + mb(f.optString("contentLength").toLongOrNull() ?: 0L), u, null, h)
                } }
                var ba: JSONObject? = null; var baR = 0
                val vids = ArrayList<JSONObject>()
                sd.optJSONArray("adaptiveFormats")?.let { a -> for (i in 0 until a.length()) {
                    val f = a.optJSONObject(i) ?: continue
                    if (f.optString("url").isEmpty()) continue
                    val m = f.optString("mimeType")
                    if (m.startsWith("video/mp4") && m.contains("avc1")) { val h = f.optInt("height"); if (h in 1..1080) vids.add(f) }
                    else if (m.startsWith("audio/mp4")) { val r = f.optInt("bitrate"); if (r > baR) { ba = f; baR = r } }
                } }
                val au = ba
                if (au != null) {
                    val aSize = au.optString("contentLength").toLongOrNull() ?: 0L
                    for (f in vids.sortedByDescending { it.optInt("bitrate") }) {
                        val h = f.optInt("height")
                        if (byH.containsKey(h)) continue
                        val vs = f.optString("contentLength").toLongOrNull() ?: 0L
                        byH[h] = Opt(h.toString() + "p" + mb(if (vs > 0) vs + aSize else 0L), f.optString("url"), au.optString("url"), h)
                    }
                }
                if (byH.isEmpty()) continue
                val opts = byH.values.sortedByDescending { it.h }
                val chosen = (if (maxH > 0) opts.firstOrNull { it.h <= maxH } ?: opts.last() else opts.first())
                return Pick(chosen.url, chosen.audio, title, c.ua, chosen.label, opts)
            } catch (e: Throwable) { LogStore.err("Sniffer:pick", e) }
        }
        return null
    }
}
