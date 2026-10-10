package com.tttt.subtitler

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** فيديو اتلقط من لينك في الكليبورد. playUrl فاضي = مفيش رابط مباشر لسه (يوتيوب أو صفحة): الشغل بيتم بشاشة الصيد */
class ClipVideo(val url: String, val title: String, val thumb: Bitmap?, val playUrl: String, val kind: String, val ref: String = "", val ua: String = "")

/** فحص لينكات الكليبورد في الخلفية أول ما البرنامج يتفتح، وتحديد اللي فيه فيديو (من غير UI) */
object ClipWatch {
    private val URL_RX = Regex("https?://[^\\s<>\"']+")
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    @Volatile var busy = false

    fun extract(t: String): List<String> =
        URL_RX.findAll(t).map { it.value.trimEnd('.', ',', ')', ']', '}', '!', '؟', '،', ';') }.filter { it.length > 10 }.distinct().take(5).toList()

    fun markSeen(ctx: Context, text: String) { try { ctx.getSharedPreferences("p", 0).edit().putString("clip_seen", text.trim()).apply() } catch (_: Throwable) {} }
    fun isSeen(ctx: Context, text: String) = try { ctx.getSharedPreferences("p", 0).getString("clip_seen", "") == text.trim() } catch (_: Throwable) { false }

    private fun decodeEnt(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")
    private fun attr(tag: String, name: String): String? {
        val m = Regex("\\b" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag) ?: return null
        return decodeEnt(m.groupValues[1].ifEmpty { m.groupValues[2] })
    }
    private fun abs(base: String, x: String): String? = try { URL(URL(base), x).toString() } catch (_: Throwable) { null }

    private class Http(val ctype: String, val body: ByteArray?, val finalUrl: String)
    private fun http(u: String, maxBytes: Int): Http? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 7000; c.readTimeout = 8000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept-Language", "ar,en;q=0.8")
        c.connect()
        val ct = (c.contentType ?: "").lowercase(); val fin = c.url.toString()
        if (ct.startsWith("video/") || ct.contains("mpegurl")) { c.disconnect(); Http(ct, null, fin) }
        else {
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
            c.inputStream.use { ins -> while (out.size() < maxBytes) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) } }
            c.disconnect(); Http(ct, out.toByteArray(), fin)
        }
    } catch (_: Throwable) { null }

    private fun bitmap(u: String?): Bitmap? {
        if (u.isNullOrEmpty()) return null
        val h = http(u, 2_000_000) ?: return null
        val b = h.body ?: return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
            var s = 1; while (o.outWidth / (s * 2) >= 640) s *= 2
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (_: Throwable) { null }
    }
    /** لقطة من أول ثانية في ملف فيديو مباشر (بمهلة، عشان ما تعلّقش الفحص) */
    private fun frame(u: String): Bitmap? {
        if (Sniff.kindOf(u) == "HLS") return null
        var r: Bitmap? = null
        val t = Thread {
            try { val m = MediaMetadataRetriever(); m.setDataSource(u, HashMap<String, String>()); r = m.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC); m.release() } catch (_: Throwable) {}
        }.apply { isDaemon = true }
        t.start(); t.join(9000)
        return r
    }
    private fun niceName(u: String) = try { java.net.URLDecoder.decode(Sniff.nameOf(u), "UTF-8") } catch (_: Throwable) { Sniff.nameOf(u) }

    /** يشتغل على thread خلفي. بيرجّع null لو اللينك مفيهوش فيديو */
    fun resolve(act: Activity, u: String): ClipVideo? {
        if (Sniff.isDirect(u)) return ClipVideo(u, niceName(u), frame(u), u, Sniff.kindOf(u))
        YtExtract.videoId(u)?.let { id ->
            val h = http("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8"), 100_000)
            val j = try { JSONObject(String(h?.body ?: return null)) } catch (_: Throwable) { return null }   // خاص/محذوف/مش فيديو
            return ClipVideo(u, j.optString("title").ifEmpty { "فيديو يوتيوب" }, bitmap(j.optString("thumbnail_url").ifEmpty { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }), "", "YT")
        }
        val h = http(u, 400_000) ?: return null
        if (h.body == null) return ClipVideo(u, niceName(h.finalUrl), null, h.finalUrl, Sniff.kindOf(h.finalUrl))   // الرابط نفسه بيرجّع فيديو من غير امتداد
        val html = String(h.body, Charsets.UTF_8)
        val metas = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
        fun meta(vararg names: String): String? { for (m in metas) { val k = (attr(m, "property") ?: attr(m, "name") ?: "").lowercase(); if (k in names) attr(m, "content")?.takeIf { it.isNotBlank() }?.let { return it } }; return null }
        val title = meta("og:title", "twitter:title") ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.let { decodeEnt(it).trim() } ?: ""
        val img = meta("og:image", "og:image:url", "twitter:image")?.let { abs(h.finalUrl, it) }
        val cands = ArrayList<String>()
        for (n in listOf("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream")) meta(n)?.let { cands.add(it) }
        Regex("<(?:video|source)\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m -> attr(m.value, "src")?.let { cands.add(it) } }
        Regex("https?:\\\\?/\\\\?/[^\"'\\s<>]+?\\.(?:mp4|m3u8|webm)[^\"'\\s<>]*", RegexOption.IGNORE_CASE).findAll(html).take(8).forEach { cands.add(it.value.replace("\\/", "/").replace("\\u0026", "&")) }
        val direct = cands.mapNotNull { abs(h.finalUrl, decodeEnt(it)) }.firstNotNullOfOrNull { Sniff.accept(it) }
        val hasVid = direct != null || meta("og:video", "og:video:url", "og:video:secure_url", "twitter:player") != null || (meta("og:type") ?: "").startsWith("video")
        if (hasVid) return ClipVideo(u, title.ifEmpty { Sniff.nameOf(u) }, bitmap(img) ?: (direct?.let { frame(it) }), direct ?: "", if (direct != null) Sniff.kindOf(direct) else "WEB", if (direct != null) h.finalUrl else "", UA)
        return probe(act, u)   // مفيش دليل ثابت في الـ HTML: متصفح مخفي ياخد 9 ثواني يشوف الصفحة بتحمّل فيديو ولا لأ
    }

    /** متصفح مخفي: بيحمّل الصفحة ويستنى أي طلب فيديو (بيشتغل مع المواقع اللي بتشغّل الفيديو أوتوماتيك) */
    @SuppressLint("SetJavaScriptEnabled")
    private fun probe(act: Activity, u: String): ClipVideo? {
        val latch = CountDownLatch(1); val found = AtomicReference<String?>(null); val info = AtomicReference("")
        val ref = AtomicReference<WebView?>(null); val ua = AtomicReference(UA)
        val hh = Handler(Looper.getMainLooper())
        act.runOnUiThread {
            try {
                val wv = WebView(act); ref.set(wv); WebMute.register(wv); WebMute.mute(wv)
                wv.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false; mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW }
                ua.set(wv.settings.userAgentString ?: UA)
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                        val x = r.url.toString()
                        WebMute.mute(v)
                        Sniff.accept(x)?.let { a -> if (found.compareAndSet(null, a)) hh.post { grab(wv, info); } ; hh.postDelayed({ latch.countDown() }, 700) }
                        if (AdBlock.blocked(x)) return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                        return null
                    }
                    override fun onPageStarted(v: WebView, url: String?, f: android.graphics.Bitmap?) { WebMute.mute(v) }
                    override fun onPageFinished(v: WebView, url: String?) { WebMute.mute(v); grab(v, info) }
                }
                hh.postDelayed({ latch.countDown() }, 9000)
                wv.loadUrl(u)
            } catch (_: Throwable) { latch.countDown() }
        }
        latch.await(10, TimeUnit.SECONDS)
        val f = found.get()
        val j = try { JSONObject(info.get()) } catch (_: Throwable) { JSONObject() }
        act.runOnUiThread { try { ref.get()?.stopLoading(); ref.get()?.destroy() } catch (_: Throwable) {} }
        if (f == null) return null
        val img = j.optString("i").takeIf { it.startsWith("http") }
        return ClipVideo(u, j.optString("t").ifEmpty { Sniff.nameOf(u) }, bitmap(img) ?: frame(f), f, Sniff.kindOf(f), u, ua.get())
    }
    private fun grab(v: WebView, info: AtomicReference<String>) {
        try {
            v.evaluateJavascript("(function(){var m=document.querySelector('meta[property=\"og:image\"]');var t=document.querySelector('meta[property=\"og:title\"]');return JSON.stringify({t:(t&&t.content)||document.title||'',i:(m&&m.content)||''})})()") { r ->
                try { (JSONTokener(r ?: "").nextValue() as? String)?.let { if (it.isNotEmpty()) info.set(it) } } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}

/**
 * أول ما البرنامج يفتح (أو يرجع له الفوكس): لو في الكليبورد لينك جديد، بيظهر إشعار صغير فوق الشاشة:
 * «تم التقاط لينك — تروح تصطاد الفيديو اللي جواه؟» [نعم، روح] [لا] — ولو ما حدش ردّ في 5 ثواني بيختفي لوحده.
 * الفحص بيشتغل في الخلفية بالتوازي: لو «نعم» والفيديو اتعرف خلاص بيشتغل على طول، غير كده بتفتح شاشة الصيد على اللينك.
 * مفيش بوب-أب ولا توست.
 */
fun Activity.checkClipboard(onPlay: (ClipVideo) -> Unit) {
    if (ClipWatch.busy) return
    val text = try { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "" } catch (_: Throwable) { "" }
    if (text.isBlank() || ClipWatch.isSeen(this, text)) return
    ClipWatch.markSeen(this, text)   // مرة واحدة لكل نسخة: ما يسألش تاني على نفس اللينك
    val links = ClipWatch.extract(text)
    if (links.isEmpty()) return
    ClipWatch.busy = true
    val results = HashMap<String, ClipVideo?>()
    Thread {
        for (u in links) {
            val r = try { ClipWatch.resolve(this, u) } catch (_: Throwable) { null }
            synchronized(results) { results[u] = r }
        }
    }.apply { isDaemon = true }.start()

    fun host(u: String) = try { java.net.URL(u).host.removePrefix("www.") } catch (_: Throwable) { u.take(30) }
    fun next(i: Int) {
        if (i >= links.size || isFinishing || isDestroyed) { ClipWatch.busy = false; return }
        val u = links[i]
        val cnt = if (links.size > 1) "  (${i + 1}/${links.size})" else ""
        Notice.ask(this, "🔗 تم التقاط لينك من ${host(u)}$cnt\nتروح تصطاد الفيديو اللي جواه؟", "نعم، روح", "لا", 5000L,
            onYes = {
                ClipWatch.busy = false
                val r = synchronized(results) { results[u] }
                onPlay(r ?: ClipVideo(u, u, null, "", "WEB"))   // لسه مش معروف/مفيش فيديو ثابت: playClip بيفتح شاشة الصيد
            },
            onNo = { next(i + 1) })
    }
    next(0)
}
