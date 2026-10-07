package com.tttt.subtitler

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * (v118) يوتيوب نفسه جوه تبويب يوتيوب: موقع m.youtube.com في WebView (بالفيد والبحث والاشتراكات بتاعت حسابك لو سجّلت دخول).
 * أي ضغطة على فيديو بتتمسك قبل ما يوتيوب يفتحه في صفحته، وبتروح للمشغّل بتاعنا (ترجمة + فرجة) بدل مشغّل يوتيوب المحمي.
 */
@SuppressLint("SetJavaScriptEnabled")
class YtWebPane(private val act: Activity, private val onPlay: (String) -> Unit) {
    val root = FrameLayout(act)
    private var wv: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastPlay = 0L
    private var lastErr = ""

    companion object {
        const val HOME = "https://m.youtube.com/"
        val LOGIN = "https://accounts.google.com/ServiceLogin?service=youtube&continue=" + Uri.encode("https://m.youtube.com/")
        private const val FALLBACK_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /** بيمسك أي ضغطة على لينك فيديو/شورتس قبل ما يوتيوب يتصرف فيها، ويبعت المعرّف للتطبيق */
        private const val CLICK_JS = "(function(){if(window.__svYt)return;window.__svYt=1;" +
            "function vid(h){var m=/[?&]v=([A-Za-z0-9_-]{11})/.exec(h||'')||/\\/shorts\\/([A-Za-z0-9_-]{11})/.exec(h||'');return m?m[1]:null}" +
            "document.addEventListener('click',function(e){try{" +
            "var a=e.target&&e.target.closest?e.target.closest('a[href]'):null;if(!a)return;" +
            "var id=vid(a.href);if(!id)return;" +
            "e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();SvYt.play(id)" +
            "}catch(x){}},true)})()"

        /** نفس يوزر-إيجنت الـ WebView من غير علامة wv — جوجل بيرفض تسجيل الدخول لو لقاها */
        fun chromeUa(ua: String?): String {
            if (ua.isNullOrBlank()) return FALLBACK_UA
            return ua.replace("; wv)", ")").replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun play(id: String) { main.post { fire(id) } }
    }

    private fun fire(id: String) {
        if (id.length != 11) return
        val now = System.currentTimeMillis()
        if (now - lastPlay < 1500) return
        lastPlay = now
        try { wv?.evaluateJavascript("document.querySelectorAll('video').forEach(function(x){try{x.pause()}catch(e){}})", null) } catch (_: Throwable) {}
        onPlay(id)
    }

    private fun build(): WebView {
        val w = WebView(act)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
            userAgentString = chromeUa(userAgentString)
        }
        // بيشيل هيدر X-Requested-With (اسم التطبيق) عشان تسجيل دخول جوجل يعدّي — لو الجهاز مش داعمه بيتجاهل
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(w.settings, emptySet<String>())
        } catch (e: Throwable) { LogStore.err("YtWeb:xrw", e) }
        try {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        } catch (_: Throwable) {}
        w.setBackgroundColor(android.graphics.Color.BLACK)
        w.addJavascriptInterface(Bridge(), "SvYt")
        w.webChromeClient = WebChromeClient()
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                val id = YtExtract.videoId(u)
                if (id != null && r.isForMainFrame) { fire(id); return true }
                return !u.startsWith("http")      // intent:// وأمثالها بتتمنع
            }
            override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) { v.evaluateJavascript(CLICK_JS, null) }
            override fun onPageFinished(v: WebView, url: String?) { v.evaluateJavascript(CLICK_JS, null) }
            /** يوتيوب موبايل بيتنقل من غير ما يحمّل صفحة (SPA): لو الرابط بقى فيديو ومسكناهوش بالضغطة، نمسكه هنا ونرجّع الصفحة */
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                val id = YtExtract.videoId(url ?: "") ?: return
                if (System.currentTimeMillis() - lastPlay < 1500) return
                fire(id)
                if (v.canGoBack()) v.goBack()
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (!r.isForMainFrame) return
                val u = r.url.toString()
                if (u.startsWith("data:") || lastErr == u) return
                lastErr = u
                v.loadDataWithBaseURL(u, "<html dir='rtl'><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                    "<body style='font-family:sans-serif;background:#16161a;color:#eee;text-align:center;padding:48px 18px'>" +
                    "<h3>⚠ الصفحة ما فتحتش — اتأكد من النت</h3>" +
                    "<button style='padding:12px 28px;font-size:16px;border:0;border-radius:10px;background:#4f8cff;color:#fff' onclick='location.replace(\"$HOME\")'>جرّب تاني</button></body></html>",
                    "text/html", "utf-8", u)
            }
        }
        return w
    }

    /** بيبني الـ WebView أول مرة (كسول — مفيش نت بيتصرف قبل ما تفتح التبويب) ويحمّل يوتيوب */
    fun show() {
        if (wv == null) {
            val w = build(); wv = w
            root.addView(w, FrameLayout.LayoutParams(-1, -1))
            w.loadUrl(HOME)
        } else {
            try { wv?.onResume() } catch (_: Throwable) {}
        }
    }
    fun pause() { try { wv?.onPause() } catch (_: Throwable) {} }
    fun home() { lastErr = ""; wv?.loadUrl(HOME) ?: show() }
    fun reload() { lastErr = ""; try { wv?.reload() } catch (_: Throwable) {} }
    fun login() { lastErr = ""; if (wv == null) show(); wv?.loadUrl(LOGIN) }
    fun back(): Boolean { val w = wv ?: return false; return if (w.canGoBack()) { w.goBack(); true } else false }
    fun loadUrl(u: String) { if (wv == null) show(); lastErr = ""; wv?.loadUrl(u) }
    fun isLoggedIn(): Boolean = try { (CookieManager.getInstance().getCookie("https://www.youtube.com") ?: "").contains("SAPISID") } catch (_: Throwable) { false }
    fun destroy() { try { wv?.let { root.removeView(it); it.destroy() } } catch (_: Throwable) {}; wv = null }
}

class YtChan(val id: String, val name: String)
class YtVid(val id: String, val title: String, val chan: String, val ts: Long)

/**
 * اشتراكاتي من غير تسجيل دخول: قايمة قنوات (من ملف Takeout أو لينكات) + أحدث فيديوهات كل قناة من الـ RSS الرسمي بتاع يوتيوب.
 * الـ RSS مفتوح ومش بيتحمي ومش محتاج مفتاح.
 */
object YtSubs {
    @Volatile var cache: List<YtVid> = emptyList()
    private val UC = Regex("UC[A-Za-z0-9_-]{22}")
    private fun file(ctx: Context) = File(ctx.filesDir, "yt_subs.json")

    fun load(ctx: Context): List<YtChan> = try {
        val a = JSONArray(file(ctx).readText().ifEmpty { "[]" })
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { YtChan(it.optString("id"), it.optString("n")) }.filter { it.id.length == 24 }
    } catch (_: Exception) { emptyList() }

    fun save(ctx: Context, l: List<YtChan>) {
        try {
            val a = JSONArray()
            for (c in l) a.put(JSONObject().put("id", c.id).put("n", c.name))
            file(ctx).writeText(a.toString())
        } catch (e: Exception) { LogStore.err("YtSubs:save", e) }
        cache = emptyList()
    }

    /** يضيف قنوات جديدة على الموجودة (من غير تكرار) — بيرجّع عدد الجديد */
    fun merge(ctx: Context, add: List<YtChan>): Int {
        val cur = LinkedHashMap<String, YtChan>()
        for (c in load(ctx)) cur[c.id] = c
        var n = 0
        for (c in add) { if (!cur.containsKey(c.id)) n++; if (!cur.containsKey(c.id) || c.name.isNotBlank()) cur[c.id] = c }
        save(ctx, cur.values.toList()); return n
    }
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) {}; cache = emptyList() }

    /** ملف subscriptions.csv بتاع Takeout: أول عمود معرّف القناة (UC…) وآخر عمود الاسم. بيرجّع فاضي لو الملف مش بالشكل ده */
    fun parseCsv(text: String): List<YtChan> {
        val out = LinkedHashMap<String, YtChan>()
        var rows = 0
        for (line in text.lines()) {
            if (line.isBlank()) continue
            rows++
            val p = line.split(",", limit = 3)
            val id = p[0].trim().trim('"').trim('\uFEFF')
            if (id.length == 24 && UC.matches(id)) out[id] = YtChan(id, p.getOrNull(2)?.trim()?.trim('"') ?: "")
        }
        return if (out.isNotEmpty() && out.size * 10 >= (rows - 1) * 8) out.values.toList() else emptyList()
    }

    /** لصق حر: أي معرّف قناة UC… أو لينك /channel/UC… في أي مكان في النص */
    fun scanIds(text: String): List<YtChan> = UC.findAll(text).map { it.value }.distinct().map { YtChan(it, "") }.toList()

    /** ملف CSV أو ZIP بتاع Takeout (بيدوّر جواه على أي CSV بشكل الاشتراكات حتى لو اسمه بالعربي) */
    fun parseFile(ctx: Context, uri: Uri): List<YtChan> {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
        if (bytes.size > 4 && bytes[0].toInt() == 0x50 && bytes[1].toInt() == 0x4B) {
            val out = LinkedHashMap<String, YtChan>()
            java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.endsWith(".csv", true)) {
                        for (c in parseCsv(z.readBytes().toString(Charsets.UTF_8))) out[c.id] = c
                    }
                    e = z.nextEntry
                }
            }
            return out.values.toList()
        }
        val t = bytes.toString(Charsets.UTF_8)
        return parseCsv(t).ifEmpty { scanIds(t) }
    }

    /** @handle → معرّف القناة (من صفحة القناة نفسها) — على thread خلفي */
    fun resolveHandle(handle: String): YtChan? {
        return try {
            val con = URL("https://www.youtube.com/@" + handle.trim().trimStart('@')).openConnection() as HttpURLConnection
            con.connectTimeout = 8000; con.readTimeout = 8000
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
            con.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
            con.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            val html = try { con.inputStream.bufferedReader().use { it.readText() } } finally { con.disconnect() }
            val id = Regex("channel_id=(UC[A-Za-z0-9_-]{22})").find(html)?.groupValues?.get(1)
                ?: Regex("\"channelId\":\"(UC[A-Za-z0-9_-]{22})\"").find(html)?.groupValues?.get(1) ?: return null
            val name = Regex("<meta property=\"og:title\" content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)?.let { unesc(it) } ?: handle
            YtChan(id, name)
        } catch (e: Throwable) { LogStore.err("YtSubs:handle", e); null }
    }

    private fun unesc(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    private fun time(s: String?): Long = try {
        if (s == null) 0L else java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse(s)?.time ?: 0L
    } catch (_: Exception) { 0L }

    private fun one(c: YtChan): List<YtVid> {
        val con = URL("https://www.youtube.com/feeds/videos.xml?channel_id=" + c.id).openConnection() as HttpURLConnection
        con.connectTimeout = 8000; con.readTimeout = 8000
        con.setRequestProperty("User-Agent", "Mozilla/5.0")
        val xml = try { con.inputStream.bufferedReader().use { it.readText() } } finally { con.disconnect() }
        val chName = Regex("<author>\\s*<name>([^<]*)</name>").find(xml)?.groupValues?.get(1)?.let { unesc(it) } ?: c.name
        val out = ArrayList<YtVid>()
        for (m in Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).take(5)) {
            val e = m.groupValues[1]
            val id = Regex("<yt:videoId>([^<]{11})</yt:videoId>").find(e)?.groupValues?.get(1) ?: continue
            val t = Regex("<title>([^<]*)</title>").find(e)?.groupValues?.get(1)?.let { unesc(it) } ?: ""
            out.add(YtVid(id, t, chName, time(Regex("<published>([^<]*)</published>").find(e)?.groupValues?.get(1))))
        }
        return out
    }

    /** بيشتغل على thread خلفي: أحدث فيديوهات كل القنوات (6 طلبات في نفس الوقت) مرتبة من الأحدث */
    fun fetchAll(chans: List<YtChan>): List<YtVid> {
        val out = java.util.concurrent.ConcurrentLinkedQueue<YtVid>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        for (c in chans.take(80)) pool.execute { try { out.addAll(one(c)) } catch (e: Throwable) { LogStore.err("YtSubs:rss", e) } }
        pool.shutdown()
        try { pool.awaitTermination(40, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        return out.sortedByDescending { it.ts }.take(120)
    }
}
