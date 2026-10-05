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
            } catch (_: Throwable) {}
        }
        return out
    }
}

/**
 * شاشة صيد الفيديو (زي متصفح 1DM): متصفح جوه البرنامج بيلقط أي فيديو بيتحمّل في الصفحة ويحطه في قايمة،
 * وكل فيديو ليه «ترجمة وفرجة» و«فرجة بس». لو جاي بلينك من الرئيسية (extra "start") بيفتح الصفحة
 * ويفتح قايمة الصيد لوحده أول ما يلقط حاجة. لينكات يوتيوب بتتجاب لها روابط مباشرة كمان.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserActivity : Activity() {
    lateinit var wv: WebView
    lateinit var badge: Button
    lateinit var hint: TextView
    lateinit var th: Theme
    lateinit var ui: Ui
    val h = Handler(Looper.getMainLooper())
    val items = ArrayList<Found>()
    private val keys = HashSet<String>()
    private var dlg: Dialog? = null
    private var listBox: LinearLayout? = null
    private var statusTv: TextView? = null
    private var autoOpen = false
    private var autoShown = false
    private var uaWeb = ""
    @Volatile private var dead = false
    private var adOn = true
    private var blockedN = 0
    private lateinit var ctl: LinearLayout
    private lateinit var shield: TextView
    private lateinit var frame: FrameLayout
    private lateinit var customBox: FrameLayout
    private lateinit var content: LinearLayout
    private var customView: View? = null
    private var customCb: CustomViewCallback? = null
    private var oldOrient = -1
    private var addrRef: android.widget.EditText? = null
    private fun showUrl(u: String?) { val a = addrRef ?: return; if (!u.isNullOrEmpty() && u != "about:blank" && !a.hasFocus()) a.setText(u) }

    private val JS = "(function(){var o=[];function a(x){try{if(x)o.push(new URL(x,location.href).href)}catch(e){}}" +
        "document.querySelectorAll('video,source').forEach(function(v){a(v.currentSrc);a(v.src)});" +
        "document.querySelectorAll('meta[property^=\"og:video\"],meta[name=\"twitter:player:stream\"]').forEach(function(m){a(m.content)});" +
        "document.querySelectorAll('a[href]').forEach(function(l){if(/\\.(mp4|m3u8|webm|mkv|mov|m4v)([?#]|\$)/i.test(l.href))a(l.href)});" +
        "return JSON.stringify(o)})()"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        th = Themes.byId(try { getSharedPreferences("p", 0).getString("theme", "mx") } catch (_: Exception) { "mx" })
        ui = Ui(this, th)
        applyBars(th)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg); setPadding(0, ui.dp(28), 0, 0) }
        val addr = ui.input("رابط الموقع / يوتيوب / كلمة بحث", "").apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f); layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val go = ui.button("اذهب", true) { }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2) }
        val copy = ui.button("📋") {
            try { ClipWatch.markSeen(this, addr.text.toString()); (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", addr.text.toString())); Toast.makeText(this, "اتنسخ لينك الصفحة", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2) }
        addrRef = addr
        addr.setSelectAllOnFocus(true)
        val row = LinearLayout(this).apply { addView(addr); addView(copy); addView(go) }
        hint = ui.text("شغّل الفيديو في الصفحة وهلقطه تلقائي · أو الصق لينك يوتيوب فوق", 12f, th.muted).apply { setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(4)) }
        wv = WebView(this)
        badge = Button(this).apply {
            isAllCaps = false; textSize = 15f; visibility = View.GONE
            setTextColor(if (th.isLight) Color.WHITE else Color.BLACK); background = ui.box(th.primary, th.primary, 8)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(6)) }
            setOnClickListener { showList() }
        }
        root.addView(row); root.addView(hint); root.addView(wv, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(badge)
        content = root
        frame = FrameLayout(this); frame.setBackgroundColor(th.bg)
        customBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        adOn = try { getSharedPreferences("p", 0).getBoolean("adblock", true) } catch (_: Exception) { true }
        ctl = buildControls()
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        frame.addView(customBox, FrameLayout.LayoutParams(-1, -1))
        frame.addView(ctl, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        setContentView(frame)
        wv.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW }
        uaWeb = wv.settings.userAgentString ?: ""
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.settings.setSupportMultipleWindows(true); wv.settings.javaScriptCanOpenWindowsAutomatically = false
        wv.webChromeClient = object : WebChromeClient() {
            // نوافذ البوب-أب: بتتقفل (لو مانع الإعلانات شغال)
            override fun onCreateWindow(v: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = adOn
            override fun onShowCustomView(view: View?, cb: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) { cb?.onCustomViewHidden(); return }
                customView = view; customCb = cb
                customBox.addView(view, FrameLayout.LayoutParams(-1, -1)); customBox.visibility = View.VISIBLE; content.visibility = View.GONE
                oldOrient = requestedOrientation; requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
            override fun onHideCustomView() { leaveCustom() }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                Sniff.accept(u)?.let { a -> h.post { if (!dead) addUrl(a) } }
                if (adOn && AdBlock.blocked(u)) { blockedN++; h.post { if (!dead) shield.text = "🛡 $blockedN" }; return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) }
                return null
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                if (!u.startsWith("http")) return adOn          // intent:// / market:// وأمثالها: بتتمنع (الإعلانات بتفتح تطبيقات)
                if (adOn && AdBlock.blocked(u)) { blockedN++; shield.text = "🛡 $blockedN"; return true }
                return false
            }
            override fun onPageStarted(v: WebView, url: String?, f: android.graphics.Bitmap?) { showUrl(url); if (adOn) v.evaluateJavascript(AdBlock.JS, null) }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) { showUrl(url) }
            override fun onPageFinished(v: WebView, url: String?) {
                showUrl(url)
                if (adOn) v.evaluateJavascript(AdBlock.JS, null)
                scanOnce()
            }
        }
        fun load(raw: String) {
            var u = raw.trim(); if (u.isEmpty()) return
            if (!u.startsWith("http")) u = if (u.contains(".") && !u.contains(" ")) "https://$u" else "https://www.google.com/search?q=" + Uri.encode(u)
            addr.setText(u); addr.clearFocus()
            if (Sniff.isDirect(u)) { Sniff.accept(u)?.let { addUrl(it) }; return }   // لينك فيديو مباشر: مفيش صفحة تتحمّل
            YtExtract.videoId(u)?.let { ytFetch(it) }
            wv.loadUrl(u)
        }
        go.setOnClickListener { load(addr.text.toString()) }
        addr.setOnEditorActionListener { _, _, _ -> load(addr.text.toString()); true }
        val start = intent?.getStringExtra("start")?.trim() ?: ""
        if (start.isNotEmpty()) { autoOpen = true; load(start) } else wv.loadUrl("https://www.google.com")
        scanLoop()
    }

    private fun scanOnce() {
        if (dead) return
        try { wv.evaluateJavascript("(function(){return document.querySelector('video')?1:0})()") { r -> if (!dead) ctl.visibility = if (r?.trim() == "1" || customView != null) View.VISIBLE else View.GONE } } catch (_: Throwable) {}
        try {
            wv.evaluateJavascript(JS) { r ->
                try {
                    val s = JSONTokener(r ?: "").nextValue() as? String
                    if (!s.isNullOrEmpty()) { val arr = JSONArray(s); for (i in 0 until arr.length()) Sniff.accept(arr.getString(i))?.let { addUrl(it) } }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
    private fun scanLoop() { if (dead) return; scanOnce(); h.postDelayed({ scanLoop() }, 2000) }

    private fun ytFetch(id: String) {
        hint.text = "⏳ بجيب روابط يوتيوب…"
        Thread {
            val r = try { YtExtract.fetch(id) } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                if (dead) return@runOnUiThread
                r.forEach { addFound(it) }
                if (r.isEmpty() && items.isEmpty()) hint.text = "يوتيوب ما رضيش يدّي لينك مباشر — شغّل الفيديو في الصفحة وأنا هحاول ألقطه"
            }
        }.apply { isDaemon = true }.start()
    }

    fun addUrl(u: String) = addFound(Found(u, Sniff.kindOf(u), "", wv.url ?: "", ""))
    fun addFound(f: Found) {
        if (!keys.add(Sniff.key(f.url))) return
        items.add(f)
        badge.text = "🎬 لقيت ${items.size} فيديو — اضغط للاختيار"; badge.visibility = View.VISIBLE; hint.visibility = View.GONE
        probe(f)
        if (autoOpen && !autoShown) { autoShown = true; showList() }
        else if (dlg?.isShowing == true) renderList()
        else if (items.size == 1) Toast.makeText(this, "🎬 لقيت فيديو", Toast.LENGTH_SHORT).show()
    }

    /** حجم ونوع الملف (طلب صغير بـ Range) عشان يظهر في القايمة */
    private fun probe(f: Found) {
        if (f.kind == "HLS" || f.size > 0) return
        val ck = try { CookieManager.getInstance().getCookie(f.url) ?: "" } catch (_: Throwable) { "" }
        Thread {
            try {
                val c = URL(f.url).openConnection() as HttpURLConnection
                c.requestMethod = "GET"; c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = true
                c.setRequestProperty("Range", "bytes=0-0")
                c.setRequestProperty("User-Agent", f.ua.ifEmpty { uaWeb })
                if (f.ref.isNotEmpty()) c.setRequestProperty("Referer", f.ref)
                if (ck.isNotEmpty()) c.setRequestProperty("Cookie", ck)
                c.connect()
                val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: c.contentLengthLong
                f.size = total; f.mime = c.contentType ?: ""
                c.disconnect()
            } catch (_: Throwable) {}
            runOnUiThread { if (!dead && dlg?.isShowing == true) renderList() }
        }.apply { isDaemon = true }.start()
    }

    fun showList() {
        if (dlg == null) {
            val lb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val st = ui.text("", 13f, th.muted).apply { setPadding(0, 0, 0, ui.dp(6)) }
            listBox = lb; statusTv = st
            dlg = ui.sheet(this, "🎬 صيد الفيديو", listOf<View>(st, lb), false)
        }
        renderList(); dlg?.show()
    }
    private fun renderList() {
        val box = listBox ?: return
        box.removeAllViews()
        statusTv?.text = if (items.isEmpty()) "⏳ بدوّر على فيديوهات… شغّل الفيديو في الصفحة لو ما ظهرش" else "اختار الفيديو اللي عايزه (${items.size})"
        for (f in items.asReversed()) box.addView(rowFor(f))
    }
    private fun rowFor(f: Found): View {
        val c = ui.card()
        val sz = if (f.size > 0) " · " + Sniff.fmtSize(f.size) else ""
        c.addView(ui.text(f.kind + sz, 15f, th.primary, true))
        c.addView(ui.text(f.title.ifEmpty { Sniff.nameOf(f.url) }, 12f, th.muted).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR
        })
        val br = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        br.addView(ui.button("🌐 ترجمة وفرجة", true) { dlg?.dismiss(); openPlayer(f, false) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1.3f).apply { marginEnd = ui.dp(4) } })
        br.addView(ui.button("▶ فرجة بس") { dlg?.dismiss(); openPlayer(f, true) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) } })
        br.addView(ui.button("📋") {
            try { ClipWatch.markSeen(this, f.url); (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("video", f.url)); Toast.makeText(this, "اتنسخ اللينك", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
        }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2) })
        c.addView(br)
        return c
    }

    private fun leaveCustom() {
        val v = customView ?: return
        customBox.removeView(v); customBox.visibility = View.GONE; content.visibility = View.VISIBLE
        customView = null; try { customCb?.onCustomViewHidden() } catch (_: Throwable) {}; customCb = null
        if (oldOrient != -1) requestedOrientation = oldOrient
        @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        applyBars(th)
    }
    /** أزرار تحكم فوق أي فيديو في صفحة المتصفح: بتشتغل على الـ <video> نفسه (تكبير / تمديد / سرعة / تقديم) */
    private fun vjs(body: String, toast: Boolean = true) {
        val js = "(function(){var vs=[].slice.call(document.querySelectorAll('video'));vs.sort(function(a,b){return b.videoWidth*b.videoHeight-a.videoWidth*a.videoHeight});" +
            "var v=vs[0];if(!v)return 'مفيش فيديو';var s=window.__sv=window.__sv||{fit:0,z:1,sp:1};$body})()"
        wv.evaluateJavascript(js) { r ->
            if (!toast) return@evaluateJavascript
            val t = try { JSONTokener(r ?: "").nextValue() as? String ?: "" } catch (_: Throwable) { "" }
            if (t.isNotEmpty()) Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
        }
    }
    private fun buildControls(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
        fun chip(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            background = ui.box(0xB0000000.toInt(), 0x66FFFFFF, 10); setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(ui.dp(46), -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) }
        }
        col.addView(chip("⏪") { vjs("v.currentTime=Math.max(0,v.currentTime-10);return ''", false) })
        col.addView(chip("⏯") { vjs("if(v.paused){v.play()}else{v.pause()};return ''", false) })
        col.addView(chip("⏩") { vjs("v.currentTime=v.currentTime+10;return ''", false) })
        col.addView(chip("⤢") { vjs("s.fit=(s.fit+1)%3;v.style.setProperty('object-fit',['contain','fill','cover'][s.fit],'important');return ['يلائم','تمديد','قص'][s.fit]") })
        col.addView(chip("🔍＋") { vjs("s.z=Math.min(4,s.z+0.25);v.style.setProperty('transform','scale('+s.z+')','important');return 'تكبير x'+s.z.toFixed(2)") })
        col.addView(chip("🔍－") { vjs("s.z=Math.max(1,s.z-0.25);v.style.setProperty('transform','scale('+s.z+')','important');return 'تكبير x'+s.z.toFixed(2)") })
        col.addView(chip("1x") { vjs("var a=[0.75,1,1.25,1.5,2];var i=a.indexOf(s.sp);s.sp=a[(i+1)%a.length];v.playbackRate=s.sp;return 'السرعة '+s.sp+'x'") })
        col.addView(chip("🌐") {   // نقل الفيديو لمشغّلنا (للترجمة): لو الصفحة فيها رابط مباشر هيتلقط ويظهر في القايمة
            wv.evaluateJavascript("(function(){var v=document.querySelector('video');return v?(v.currentSrc||v.src||''):''})()") { r ->
                val u = try { JSONTokener(r ?: "").nextValue() as? String ?: "" } catch (_: Throwable) { "" }
                if (u.startsWith("http")) { Sniff.accept(u)?.let { addUrl(it) } }
                if (items.isEmpty()) Toast.makeText(this, "مفيش رابط مباشر للفيديو ده (محمي أو blob) — كمّل فرجة هنا بالأزرار", Toast.LENGTH_LONG).show() else showList()
            }
        })
        shield = chip("🛡") {
            adOn = !adOn; try { getSharedPreferences("p", 0).edit().putBoolean("adblock", adOn).apply() } catch (_: Exception) {}
            shield.text = if (adOn) "🛡 $blockedN" else "🛡✗"; shield.alpha = if (adOn) 1f else 0.5f
            Toast.makeText(this, if (adOn) "مانع الإعلانات شغال" else "مانع الإعلانات اتقفل", Toast.LENGTH_SHORT).show()
            try { wv.reload() } catch (_: Throwable) {}
        }
        shield.text = if (adOn) "🛡" else "🛡✗"; shield.alpha = if (adOn) 1f else 0.5f; shield.textSize = 12f
        col.addView(shield)
        return col
    }

    fun openPlayer(f: Found, noSub: Boolean) { if (noSub) openPlayerNow(f, true) else ensureKeys { openPlayerNow(f, false) } }
    fun openPlayerNow(f: Found, noSub: Boolean) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("url", f.url); putExtra("ref", f.ref)
            putExtra("cookie", try { CookieManager.getInstance().getCookie(f.url) ?: "" } catch (_: Throwable) { "" })
            putExtra("ua", f.ua.ifEmpty { uaWeb }); putExtra("nosub", noSub); putExtra("autotr", !noSub)
        })
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (customView != null) leaveCustom() else if (wv.canGoBack()) wv.goBack() else super.onBackPressed() }
    override fun onDestroy() {
        dead = true; h.removeCallbacksAndMessages(null)
        try { dlg?.dismiss() } catch (_: Throwable) {}
        try { wv.destroy() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
