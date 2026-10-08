package com.tttt.subtitler

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** تخزين المتصفح: السجل · المفضلة · التبويبات المحفوظة (ملفات JSON صغيرة في filesDir) */
object BrowserStore {
    class Entry(val url: String, var title: String, var time: Long)
    private var hist: ArrayList<Entry>? = null
    private var marks: ArrayList<Entry>? = null

    private fun load(c: Context, n: String): ArrayList<Entry> {
        val out = ArrayList<Entry>()
        try {
            val a = JSONArray(java.io.File(c.filesDir, n).readText())
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(Entry(o.getString("u"), o.optString("t"), o.optLong("d"))) }
        } catch (_: Exception) {}
        return out
    }
    private fun save(c: Context, n: String, l: List<Entry>) {
        try {
            val a = JSONArray()
            for (e in l) a.put(JSONObject().put("u", e.url).put("t", e.title).put("d", e.time))
            java.io.File(c.filesDir, n).writeText(a.toString())
        } catch (e: Exception) { LogStore.err("BrowserStore", e) }
    }
    private fun skip(u: String) = u.isBlank() || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("javascript:")

    fun history(c: Context): ArrayList<Entry> = hist ?: load(c, "br_history.json").also { hist = it }
    fun addHistory(c: Context, url: String, title: String) {
        if (skip(url)) return
        val h = history(c)
        if (h.isNotEmpty() && h[0].url == url) { h[0].time = System.currentTimeMillis(); if (title.isNotBlank()) h[0].title = title }
        else { h.add(0, Entry(url, title, System.currentTimeMillis())); while (h.size > 1000) h.removeAt(h.size - 1) }
        save(c, "br_history.json", h)
    }
    fun setTitle(c: Context, url: String, title: String) {
        if (skip(url) || title.isBlank()) return
        val e = history(c).firstOrNull { it.url == url } ?: return
        if (e.title != title) { e.title = title; save(c, "br_history.json", history(c)) }
    }
    fun removeHistory(c: Context, e: Entry) { history(c).remove(e); save(c, "br_history.json", history(c)) }
    fun clearHistory(c: Context) { history(c).clear(); save(c, "br_history.json", history(c)) }

    fun bookmarks(c: Context): ArrayList<Entry> = marks ?: load(c, "br_bookmarks.json").also { marks = it }
    fun isMarked(c: Context, url: String) = bookmarks(c).any { it.url == url }
    /** بيرجّع true لو اتضافت، false لو اتشالت */
    fun toggleMark(c: Context, url: String, title: String): Boolean {
        val b = bookmarks(c)
        val ex = b.firstOrNull { it.url == url }
        val added = if (ex != null) { b.remove(ex); false } else { b.add(0, Entry(url, title, System.currentTimeMillis())); true }
        save(c, "br_bookmarks.json", b); return added
    }
    fun removeMark(c: Context, e: Entry) { bookmarks(c).remove(e); save(c, "br_bookmarks.json", bookmarks(c)) }

    class SavedTab(val url: String, val title: String, val desktop: Boolean)
    fun saveTabs(c: Context, tabs: List<SavedTab>, cur: Int) {
        try {
            val a = JSONArray()
            for (t in tabs) a.put(JSONObject().put("u", t.url).put("t", t.title).put("k", t.desktop))
            java.io.File(c.filesDir, "br_tabs.json").writeText(JSONObject().put("cur", cur).put("tabs", a).toString())
        } catch (e: Exception) { LogStore.err("BrowserStore.tabs", e) }
    }
    fun loadTabs(c: Context): Pair<List<SavedTab>, Int> {
        val out = ArrayList<SavedTab>(); var cur = 0
        try {
            val o = JSONObject(java.io.File(c.filesDir, "br_tabs.json").readText())
            cur = o.optInt("cur", 0); val a = o.getJSONArray("tabs")
            for (i in 0 until a.length()) { val t = a.getJSONObject(i); out.add(SavedTab(t.getString("u"), t.optString("t"), t.optBoolean("k", false))) }
        } catch (_: Exception) {}
        return out to cur
    }
}

class BTab(val id: Long) {
    var wv: WebView? = null
    var url = ""
    var title = ""
    var desktop = false
    var progress = 100
    var opener = -1L
    var errUrl = ""
    var loaded = false
    var priv = false                 // (v124) تبويب متخفي: من غير سجل ولا حفظ
    var thumb: android.graphics.Bitmap? = null
}

/**
 * متصفح كامل جوه البرنامج (v91): تبويبات (بتتحفظ وترجع لما تفتح تاني) · سجل · مفضلة · إعدادات · بحث في الصفحة ·
 * نسخة الكمبيوتر · تحميل ملفات · قايمة ضغطة طويلة على اللينكات والصور · وبيلقط الفيديوهات زي الأول (قايمة «لقيت فيديو» + أزرار التحكم على الفيديو).
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserActivity : Activity() {
    override fun finish() { super.finish(); try { overridePendingTransition(R.anim.act_stay, R.anim.act_exit) } catch (e: Exception) { LogStore.err("Browser:finish", e) } }

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
    private val pageTitles = HashMap<String, String>()

    // ===== التبويبات =====
    private val tabs = ArrayList<BTab>()
    private var curIdx = 0
    private var nextId = 1L
    private val tab: BTab get() = tabs[curIdx]
    private val wvOrNull: WebView? get() = tabs.getOrNull(curIdx)?.wv
    private lateinit var webHolder: FrameLayout
    private lateinit var addr: EditText
    private lateinit var backB: TextView
    private lateinit var fwdB: TextView
    private lateinit var reloadB: TextView
    private lateinit var tabsB: TextView
    private lateinit var progV: View
    private lateinit var progTrack: FrameLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findEt: EditText
    private lateinit var findCount: TextView
    private var tabsDlg: Dialog? = null
    private var tabsBox: LinearLayout? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val DESK_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun prefs() = getSharedPreferences("p", 0)
    private fun home() = prefs().getString("br_home", "")?.ifBlank { null } ?: "https://www.google.com"
    private fun searchUrl(q: String): String = when (prefs().getString("br_engine", "Google")) {
        "DuckDuckGo" -> "https://duckduckgo.com/?q="
        "Bing" -> "https://www.bing.com/search?q="
        else -> "https://www.google.com/search?q="
    } + Uri.encode(q)
    private fun uaOf(t: BTab) = if (t.desktop) DESK_UA else uaWeb

    private fun titleFor(f: Found): String = f.title.ifEmpty { Sniff.cleanTitle(pageTitles[f.ref] ?: if (f.ref == wvOrNull?.url) wvOrNull?.title else null) }

    private val JS = "(function(){var o=[];function a(x){try{if(x)o.push(new URL(x,location.href).href)}catch(e){}}" +
        "document.querySelectorAll('video,source').forEach(function(v){a(v.currentSrc);a(v.src)});" +
        "document.querySelectorAll('meta[property^=\"og:video\"],meta[name=\"twitter:player:stream\"]').forEach(function(m){a(m.content)});" +
        "document.querySelectorAll('a[href]').forEach(function(l){if(/\\.(mp4|m3u8|webm|mkv|mov|m4v)([?#]|\$)/i.test(l.href))a(l.href)});" +
        "return JSON.stringify(o)})()"

    // (v120) جودات أي موقع: عناصر <source> جوه نفس <video> (بـ label/size/res) = نفس الفيديو بجودات مختلفة
    private val JSQ = """(function(){var g=[];document.querySelectorAll('video').forEach(function(v){var m=[];v.querySelectorAll('source').forEach(function(s){var u=s.src;if(!u)return;var t=(s.getAttribute('label')||s.getAttribute('res')||s.getAttribute('size')||s.getAttribute('data-res')||s.getAttribute('title')||'');var mm=/([0-9]{3,4})/.exec(t);m.push({u:u,h:mm?parseInt(mm[1]):0})});if(m.length>1)g.push(m)});return JSON.stringify(g)})()"""
    private val webGroups = ArrayList<List<Pair<String, Int>>>()

    /** لو الفيديو اللي هيتفتح ليه جودات تانية في نفس الصفحة: قايمة الجودات (بحجمها الكلي) بنفس صيغة يوتيوب، وإلا null */
    private fun webQlist(f: Found): String? {
        val k = Sniff.key(f.url)
        val grp = webGroups.firstOrNull { g -> g.any { Sniff.key(it.first) == k } }
        val raw = ArrayList<Triple<String, Int, Long>>()   // url, height, size
        if (grp != null) {
            for (m in grp) { val it0 = items.firstOrNull { x -> Sniff.key(x.url) == Sniff.key(m.first) }
                raw.add(Triple(m.first, if (m.second > 0) m.second else Sniff.heightOf(m.first), it0?.size ?: -1L)) }
        } else {
            val sk = Sniff.skeleton(f.url)
            for (x in items) if (x.ref == f.ref && x.kind != "HLS" && Sniff.skeleton(x.url) == sk) raw.add(Triple(x.url, Sniff.heightOf(x.url), x.size))
            if (raw.map { it.second }.filter { it > 0 }.toSet().size < 2) return null
        }
        val uniq = raw.distinctBy { Sniff.key(it.first) }.sortedByDescending { it.second }
        if (uniq.size < 2) return null
        val a = JSONArray()
        for ((i, t) in uniq.withIndex()) a.put(JSONObject().put("l", Sniff.qLabel(t.second, i + 1, t.third)).put("u", t.first).put("a", "").put("h", t.second))
        return a.toString()
    }

    // ===== بناء الشاشة =====
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        th = Themes.byId(try { prefs().getString("theme", "mx") } catch (_: Exception) { "mx" })
        ui = Ui(this, th)
        applyBars(th)
        adOn = try { prefs().getBoolean("adblock", true) } catch (_: Exception) { true }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg); setPadding(0, ui.dp(28), 0, 0) }
        fun tbtn(t: String, size: Float = 20f, f: (View) -> Unit): TextView = IconTextView(this).apply {
            text = t; textSize = size; gravity = Gravity.CENTER; setTextColor(th.text)
            layoutParams = LinearLayout.LayoutParams(ui.dp(38), ui.dp(40)); setOnClickListener { f(it) }
        }
        backB = tbtn("‹", 26f) { wvOrNull?.let { if (it.canGoBack()) it.goBack() } }
        fwdB = tbtn("›", 26f) { wvOrNull?.let { if (it.canGoForward()) it.goForward() } }
        addr = ui.input("رابط الموقع / كلمة بحث", "").apply {
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(40), 1f).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) }
            layoutDirection = View.LAYOUT_DIRECTION_LTR; setSelectAllOnFocus(true); textSize = 13f
            imeOptions = EditorInfo.IME_ACTION_GO; inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }
        reloadB = tbtn("⟳", 20f) { val w = wvOrNull ?: return@tbtn; if (tab.progress < 100) w.stopLoading() else w.reload() }
        tabsB = IconTextView(this).apply {
            text = "1"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(th.text); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ui.box(0x00000000, th.text, 6); layoutParams = LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)).apply { setMargins(ui.dp(5), ui.dp(6), ui.dp(5), ui.dp(6)) }
            setOnClickListener { showTabs() }
        }
        val menuB = tbtn("⋮", 22f) { showMenu(it) }
        val bar = LinearLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(2), 0, ui.dp(2), 0)
            addView(backB); addView(fwdB); addView(addr); addView(reloadB); addView(tabsB); addView(menuB)
        }
        progV = View(this).apply { setBackgroundColor(th.primary) }
        progTrack = FrameLayout(this).apply { visibility = View.INVISIBLE; addView(progV, FrameLayout.LayoutParams(0, -1)) }

        findEt = ui.input("دوّر في الصفحة", "").apply { layoutParams = LinearLayout.LayoutParams(0, ui.dp(40), 1f); textSize = 13f }
        findCount = ui.text("", 12f, th.muted).apply { setPadding(ui.dp(6), 0, ui.dp(6), 0) }
        findBar = LinearLayout(this).apply {
            visibility = View.GONE; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(2))
            addView(findEt); addView(findCount)
            addView(tbtn("▲", 14f) { wvOrNull?.findNext(false) }); addView(tbtn("▼", 14f) { wvOrNull?.findNext(true) }); addView(tbtn("✕", 16f) { closeFind() })
        }
        findEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { val q = s?.toString() ?: ""; if (q.isEmpty()) { wvOrNull?.clearMatches(); findCount.text = "" } else wvOrNull?.findAllAsync(q) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
        })

        hint = ui.text("شغّل الفيديو في الصفحة وهلقطه تلقائي · أو الصق لينك يوتيوب فوق", 12f, th.muted).apply { setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(4)) }
        webHolder = FrameLayout(this)
        badge = IconButton(this).apply {
            isAllCaps = false; textSize = 15f; visibility = View.GONE
            setTextColor(if (th.isLight) Color.WHITE else Color.BLACK); background = ui.box(th.primary, th.primary, 8)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(6)) }
            setOnClickListener { showList() }
        }
        root.addView(bar); root.addView(progTrack, LinearLayout.LayoutParams(-1, ui.dp(3))); root.addView(findBar); root.addView(hint)
        root.addView(webHolder, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(badge)
        // (v117) شريط البوابات: الفيديوهات · يوتيوب · المتصفح (إحنا فيه دلوقتي)
        val gates = BottomNav(this, ui, th, 1) { i -> if (i == 0) goMain("videos") else if (i == 2) goMain("tasks") }
        root.addView(gates.view, LinearLayout.LayoutParams(-1, -2))
        content = root
        frame = FrameLayout(this); frame.setBackgroundColor(th.bg)
        customBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        ctl = buildControls()
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        frame.addView(customBox, FrameLayout.LayoutParams(-1, -1))
        frame.addView(ctl, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        setContentView(frame)

        // التبويبات: رجّع المحفوظة (لو مفعّل) وبعدها لو جاي بلينك افتحه في تبويب جديد
        val start = intent?.getStringExtra("start")?.trim() ?: ""
        val keep = prefs().getBoolean("br_save_tabs", true)
        val saved = if (keep) BrowserStore.loadTabs(this) else (emptyList<BrowserStore.SavedTab>() to 0)
        for (s in saved.first) tabs.add(BTab(nextId++).also { it.url = s.url; it.title = s.title; it.desktop = s.desktop })
        if (tabs.isNotEmpty()) curIdx = saved.second.coerceIn(0, tabs.size - 1)
        if (start.isNotEmpty()) { autoOpen = intent?.getBooleanExtra("noauto", false) != true; newTab(start, true) }
        else if (tabs.isEmpty()) newTab(home(), true)
        else select(curIdx)
        scanLoop()
    }

    /** (v117) رجوع للشاشة الرئيسية على بوابة معيّنة (videos / yt) */
    private fun goMain(tab: String) {
        persistTabs()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", tab))
        finish()
    }
    /** (v117) أي صفحة فيديو يوتيوب بتتفتح في المتصفح بتتسجل في سجل بوابة يوتيوب (المعرّف + الاسم) */
    private var lastYtId = ""
    private val ytIds = HashMap<String, String>()
    private fun noteYt(url: String?, title: String?) { /* (v124) سجل يوتيوب اتشال */ }

    // ===== إنشاء WebView لتبويب =====
    private fun makeWebView(t: BTab): WebView {
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = false
        }
        if (uaWeb.isEmpty()) uaWeb = w.settings.userAgentString ?: ""
        if (t.desktop) w.settings.userAgentString = DESK_UA
        if (t.priv) { w.settings.cacheMode = WebSettings.LOAD_NO_CACHE; @Suppress("DEPRECATION") w.settings.saveFormData = false }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setBackgroundColor(Color.WHITE)
        w.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                // نافذة جديدة بلمسة منك = تبويب جديد؛ من غير لمسة (بوب-أب) بتتمنع لو مانع الإعلانات شغال
                if (!isUserGesture && adOn) return true
                val nt = newTab(null, true, t.id, t.priv)
                val tr = resultMsg?.obj as? WebView.WebViewTransport
                if (tr == null || nt.wv == null) return false
                tr.webView = nt.wv; resultMsg?.sendToTarget(); return true
            }
            override fun onProgressChanged(v: WebView?, p: Int) { t.progress = p; if (t === tabs.getOrNull(curIdx)) updateProgress() }
            override fun onReceivedTitle(v: WebView?, title: String?) {
                if (title.isNullOrBlank()) return
                t.title = title; v?.url?.let { if (!t.priv) BrowserStore.setTitle(this@BrowserActivity, it, title); pageTitles[it] = title }
                noteYt(v?.url, title)
                if (tabsDlg?.isShowing == true) renderTabs()
            }
            override fun onShowCustomView(view: View?, cb: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) { cb?.onCustomViewHidden(); return }
                customView = view; customCb = cb
                customBox.addView(view, FrameLayout.LayoutParams(-1, -1)); customBox.visibility = View.VISIBLE; content.visibility = View.GONE
                oldOrient = requestedOrientation; requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
            override fun onHideCustomView() { leaveCustom() }
            override fun onShowFileChooser(v: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                return try { startActivityForResult(p?.createIntent(), 4711); true } catch (e: Throwable) { fileCb?.onReceiveValue(null); fileCb = null; false }
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                Sniff.accept(u)?.let { a -> h.post { if (!dead) addFound(Found(a, Sniff.kindOf(a), "", v.url ?: t.url, uaOf(t))) } }
                if (adOn && AdBlock.blocked(u)) { blockedN++; h.post { if (!dead) shield.text = "🛡 $blockedN" }; return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) }
                return null
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                if (!u.startsWith("http")) {
                    if (u.startsWith("tel:") || u.startsWith("mailto:") || u.startsWith("sms:") || u.startsWith("geo:")) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Throwable) {}
                        return true
                    }
                    return adOn          // intent:// / market:// وأمثالها: بتتمنع (الإعلانات بتفتح تطبيقات)
                }
                if (adOn && AdBlock.blocked(u)) { blockedN++; shield.text = "🛡 $blockedN"; return true }
                return false
            }
            override fun onPageStarted(v: WebView, url: String?, f: android.graphics.Bitmap?) {
                if (url != null && !url.startsWith("data:")) t.url = url
                if (t === tabs.getOrNull(curIdx)) { showUrl(url); updateNav() }
                if (adOn) v.evaluateJavascript(AdBlock.JS, null)
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                if (url != null && !url.startsWith("data:")) { t.url = url; noteYt(url, null) }
                if (t === tabs.getOrNull(curIdx)) { showUrl(url); updateNav() }
            }
            override fun onPageFinished(v: WebView, url: String?) {
                if (url != null && !url.startsWith("data:")) {
                    t.url = url
                    val ti = v.title?.takeIf { it.isNotBlank() } ?: ""
                    if (ti.isNotEmpty()) { t.title = ti; pageTitles[url] = ti }
                    if (!t.priv) BrowserStore.addHistory(this@BrowserActivity, url, ti)
                    noteYt(url, ti)
                }
                if (t === tabs.getOrNull(curIdx)) { showUrl(url); updateNav(); updateStar(); scanOnce() }
                if (adOn) v.evaluateJavascript(AdBlock.JS, null)
                persistTabs()
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                if (!r.isForMainFrame) return
                val u = r.url.toString()
                if (u.startsWith("data:") || t.errUrl == u) return
                t.errUrl = u
                v.loadDataWithBaseURL(u, errHtml(u, e.description?.toString() ?: ""), "text/html", "utf-8", u)
            }
        }
        w.setDownloadListener { url, ua, cd, mime, _ ->
            Sniff.accept(url)?.let { a -> addFound(Found(a, Sniff.kindOf(a), "", t.url, ua ?: uaOf(t))) }
            val name = URLUtil.guessFileName(url, cd, mime)
            Notice.ask(this, "تحميل «$name»؟", "تحميل", "لا", 6000L, { startDownload(url, ua, name, mime) }, {})
        }
        w.setOnLongClickListener { v -> longPress(v as WebView) }
        return w
    }

    private fun errHtml(url: String, msg: String): String =
        "<html dir='rtl'><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
        "<body style='font-family:sans-serif;background:#16161a;color:#eee;text-align:center;padding:48px 18px'>" +
        "<h2>⚠ الصفحة ما فتحتش</h2><p style='color:#bbb'>" + TextUtils.htmlEncode(msg) + "</p>" +
        "<p style='color:#888;word-break:break-all;direction:ltr;font-size:12px'>" + TextUtils.htmlEncode(url) + "</p>" +
        "<button style='padding:12px 28px;font-size:16px;border:0;border-radius:10px;background:#4f8cff;color:#fff' onclick='location.replace(" + JSONObject.quote(url) + ")'>جرّب تاني</button></body></html>"

    // ===== إدارة التبويبات =====
    /** url = null: تبويب فاضي بيستنى WebViewTransport (نافذة جديدة من صفحة) */
    private fun newTab(url: String?, switchTo: Boolean, opener: Long = -1L, priv: Boolean = false): BTab {
        val t = BTab(nextId++); t.opener = opener; t.priv = priv
        t.desktop = prefs().getBoolean("br_desktop_default", false)
        if (url == null) { t.wv = makeWebView(t); t.loaded = true } else t.url = url
        tabs.add(t)
        if (switchTo) select(tabs.size - 1)
        refreshTabsCount(); persistTabs()
        return t
    }
    private fun select(i: Int) {
        if (i !in tabs.indices) return
        tabs.getOrNull(curIdx)?.let { snapTab(it) }
        wvOrNull?.let { try { it.onPause() } catch (_: Throwable) {} }
        webHolder.removeAllViews()
        curIdx = i
        val t = tabs[i]
        val first = t.wv == null
        if (first) t.wv = makeWebView(t)
        val w = t.wv!!
        (w.parent as? ViewGroup)?.removeView(w)
        webHolder.addView(w, FrameLayout.LayoutParams(-1, -1))
        try { w.onResume() } catch (_: Throwable) {}
        if (!t.loaded) { t.loaded = true; if (t.url.isNotBlank()) loadIn(w, t, t.url) }
        showUrl(t.url, true); updateNav(); updateProgress(); updateStar(); refreshTabsCount(); closeFind()
        addr.hint = if (t.priv) "🕶 متخفي — رابط الموقع / كلمة بحث" else "رابط الموقع / كلمة بحث"
        tabsB.setTextColor(if (t.priv) 0xFFE53935.toInt() else th.text)
        ctl.visibility = View.GONE
        scanOnce(); persistTabs()
    }
    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs[i]
        val wasCur = i == curIdx
        val openerIdx = tabs.indexOfFirst { it.id == t.opener }
        try { t.wv?.let { (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); if (t.priv) { it.clearCache(true); it.clearHistory(); it.clearFormData() }; it.destroy() } } catch (_: Throwable) {}
        tabs.removeAt(i)
        if (tabs.isEmpty()) { newTab(home(), true); return }
        if (wasCur) select(if (openerIdx in tabs.indices) openerIdx else (i - 1).coerceAtLeast(0).coerceAtMost(tabs.size - 1))
        else { if (i < curIdx) curIdx--; refreshTabsCount(); persistTabs() }
    }
    private fun refreshTabsCount() { if (::tabsB.isInitialized) tabsB.text = tabs.size.toString() }
    private fun persistTabs() {
        if (!prefs().getBoolean("br_save_tabs", true)) return
        // (v124) التبويبات المتخفية عمرها ما بتتحفظ
        val keep = tabs.filter { !it.priv }
        val ci = keep.indexOf(tabs.getOrNull(curIdx)).let { if (it < 0) 0 else it }
        BrowserStore.saveTabs(this, keep.map { BrowserStore.SavedTab(it.wv?.url?.takeIf { u -> !u.startsWith("data:") } ?: it.url, it.title, it.desktop) }, ci)
    }

    // ===== تحميل لينك / بحث =====
    private fun load(raw: String) {
        var u = raw.trim(); if (u.isEmpty()) return
        if (!u.startsWith("http") && !u.startsWith("about:")) u = if (u.contains(".") && !u.contains(" ")) "https://$u" else searchUrl(u)
        addr.clearFocus()
        if (Sniff.isDirect(u)) { Sniff.accept(u)?.let { addFound(Found(it, Sniff.kindOf(it), "", tab.url, uaOf(tab))) }; return }   // لينك فيديو مباشر: مفيش صفحة تتحمّل
        YtExtract.videoId(u)?.let { ytFetch(it) }
        val t = tab; t.loaded = true; t.errUrl = ""
        loadIn(t.wv ?: return, t, u)
    }
    private fun loadIn(w: WebView, t: BTab, u: String) { t.url = u; t.errUrl = ""; w.loadUrl(u) }

    private fun showUrl(u: String?, force: Boolean = false) {
        if (!::addr.isInitialized) return
        if (!u.isNullOrEmpty() && u != "about:blank" && (force || !addr.hasFocus())) addr.setText(u)
    }
    private fun updateNav() {
        val w = wvOrNull
        backB.alpha = if (w?.canGoBack() == true) 1f else 0.35f
        fwdB.alpha = if (w?.canGoForward() == true) 1f else 0.35f
    }
    private fun updateProgress() {
        val p = tab.progress
        reloadB.text = if (p < 100) "✕" else "⟳"
        if (p >= 100) { progTrack.visibility = View.INVISIBLE; return }
        progTrack.visibility = View.VISIBLE
        val w = progTrack.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        progV.layoutParams = FrameLayout.LayoutParams((w * p / 100f).toInt().coerceAtLeast(ui.dp(12)), -1)
    }
    private fun updateStar() { /* حالة النجمة بتتحسب وقت فتح القايمة */ }

    // ===== القايمة ⋮ =====
    private fun popupMenu(anchor: View, entries: List<Pair<String, () -> Any?>>) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(th.card, th.border, 14); elevation = ui.dp(8).toFloat() }
        val sv = ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false }
        val width = ui.dp(240)
        val pw = PopupWindow(sv, width, -2, true)
        pw.setBackgroundDrawable(ColorDrawable(0))
        for ((t, f) in entries) col.addView(IconTextView(this).apply {
            text = t; textSize = 14f; setTextColor(th.text); minHeight = ui.dp(44); gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)); setOnClickListener { pw.dismiss(); f() }
        })
        col.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val maxH = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        pw.height = minOf(col.measuredHeight, maxH)
        try { pw.showAsDropDown(anchor, anchor.width - width, ui.dp(2)) } catch (_: Throwable) {}
    }
    private fun showMenu(anchor: View) {
        val url = tab.wv?.url ?: tab.url
        val marked = BrowserStore.isMarked(this, url)
        popupMenu(anchor, listOf(
            "➕ تبويب جديد" to { newTab(home(), true) },
            "🕶 تبويب متخفي جديد" to { newTab(home(), true, -1L, true) },
            (if (marked) "★ شيل من المفضلة" else "☆ ضيف للمفضلة") to {
                val added = BrowserStore.toggleMark(this, url, tab.title.ifBlank { Sniff.nameOf(url) })
                Notice.show(this, if (added) "اتضافت للمفضلة" else "اتشالت من المفضلة", 1800L)
            },
            "📚 المفضلة" to { showBookmarks() },
            "🕘 السجل" to { showHistory() },
            "🔍 بحث في الصفحة" to { openFind() },
            (if (tab.desktop) "🖥 نسخة الكمبيوتر: شغالة ✓" else "🖥 نسخة الكمبيوتر") to { toggleDesktop() },
            "🏠 الصفحة الرئيسية" to { load(home()) },
            "📋 نسخ الرابط" to { copyUrl(url) },
            "📤 مشاركة الصفحة" to { shareUrl(url) },
            "🎬 الفيديوهات الملقوطة (${items.size})" to { showList() },
            "⚙️ الإعدادات" to { showSettings() },
            "✕ خروج من المتصفح" to { finish() }
        ))
    }
    private fun copyUrl(u: String) {
        try { ClipWatch.markSeen(this, u); (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", u)); Notice.show(this, "اتنسخ اللينك", 1800L) } catch (e: Throwable) { LogStore.err("Browser:copy", e) }
    }
    private fun shareUrl(u: String) {
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), "مشاركة")) } catch (e: Throwable) { LogStore.err("Browser:share", e) }
    }
    private fun toggleDesktop() {
        val t = tab; val w = t.wv ?: return
        t.desktop = !t.desktop
        w.settings.userAgentString = if (t.desktop) DESK_UA else uaWeb.ifEmpty { null }
        Notice.show(this, if (t.desktop) "نسخة الكمبيوتر: شغالة" else "نسخة الموبايل", 1800L)
        w.reload(); persistTabs()
    }

    // ===== بحث في الصفحة =====
    private fun openFind() {
        findBar.visibility = View.VISIBLE; findEt.requestFocus()
        wvOrNull?.setFindListener { active, num, _ -> findCount.text = if (num == 0) "0" else "${active + 1}/$num" }
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(findEt, 0) } catch (_: Throwable) {}
    }
    private fun closeFind() {
        if (!::findBar.isInitialized || findBar.visibility != View.VISIBLE) return
        findBar.visibility = View.GONE; findEt.setText(""); findCount.text = ""
        wvOrNull?.clearMatches()
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(findEt.windowToken, 0) } catch (_: Throwable) {}
    }

    // ===== شاشات (sheets) =====
    private fun sheet(title: String, vararg views: View): Dialog = ui.sheet(this, title, views.toList(), false)

    // ===== (v124) شاشة التبويبات زي كروم: مربعات فيها لقطة من الصفحة + العنوان + ✕، وقسم منفصل للمتخفي =====
    private var tabsPrivView = false
    private fun snapTab(t: BTab) {
        val w = t.wv ?: return
        if (w.width <= 0 || w.height <= 0 || w.parent == null) return
        try {
            val tw = ui.dp(190); val th2 = (tw * 1.15f).toInt()
            val sc = tw.toFloat() / w.width
            val srcH = minOf(w.height, (th2 / sc).toInt())
            val bmp = android.graphics.Bitmap.createBitmap(tw, (srcH * sc).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.RGB_565)
            val c = android.graphics.Canvas(bmp); c.scale(sc, sc); w.draw(c)
            t.thumb = bmp
        } catch (_: Throwable) {}
    }
    private fun showTabs() {
        if (tabsDlg?.isShowing == true) return
        tabs.getOrNull(curIdx)?.let { snapTab(it) }
        tabsPrivView = tabs.getOrNull(curIdx)?.priv == true
        val d = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        tabsDlg = d
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(28), 0, 0) }
        val head = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)) }
        val modeNormal = ui.button("التبويبات") { tabsPrivView = false; renderTabs() }
        val modePriv = ui.button("🕶 متخفي") { tabsPrivView = true; renderTabs() }
        head.addView(modeNormal, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        head.addView(modePriv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        head.addView(IconTextView(this).apply { text = "✕"; textSize = 20f; setTextColor(th.text); setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)); setOnClickListener { d.dismiss() } })
        val actions = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(4)) }
        actions.addView(ui.button("➕ تبويب جديد", true) { d.dismiss(); newTab(home(), true, -1L, tabsPrivView) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) } })
        actions.addView(ui.button("🗑 اقفل الكل") { closeAllTabs(tabsPrivView) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(16)) }
        tabsBox = box
        root.addView(head); root.addView(actions)
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        d.setContentView(root)
        d.window?.apply { setBackgroundDrawable(ColorDrawable(th.bg)); setLayout(-1, -1) }
        d.setOnDismissListener { tabsDlg = null; tabsBox = null }
        renderTabs(); d.show()
    }
    private fun renderTabs() {
        val box = tabsBox ?: return
        box.removeAllViews()
        val list = tabs.withIndex().filter { it.value.priv == tabsPrivView }
        if (list.isEmpty()) box.addView(ui.text(if (tabsPrivView) "مفيش تبويبات متخفية — مفيش سجل ولا حفظ فيها" else "مفيش تبويبات", 13f, th.muted).apply { setPadding(ui.dp(12), ui.dp(16), ui.dp(12), 0) })
        val cardW = (resources.displayMetrics.widthPixels - ui.dp(12) * 3) / 2
        for (pair in list.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            for ((i, t) in pair) {
                val url = t.wv?.url ?: t.url
                val ttl = t.title.ifBlank { Sniff.nameOf(url).ifBlank { "تبويب جديد" } }
                val dark = t.priv
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = ui.box(if (dark) 0xFF2B2D31.toInt() else th.card, if (i == curIdx) th.primary else th.border, 14, if (i == curIdx) 3 else 1)
                    setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                    setOnClickListener { tabsDlg?.dismiss(); select(i) }
                }
                val bar = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
                bar.addView(ui.text((if (dark) "🕶 " else "") + ttl, 12f, if (dark) Color.WHITE else th.text, i == curIdx).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(ui.dp(6), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                bar.addView(IconTextView(this).apply { text = "✕"; textSize = 16f; setTextColor(if (dark) 0xFFBBBBBB.toInt() else th.muted); setPadding(ui.dp(10), ui.dp(6), ui.dp(8), ui.dp(6)); setOnClickListener { closeTab(i); if (tabsDlg?.isShowing == true) renderTabs() } })
                card.addView(bar)
                val thumbH = (cardW * 1.1f).toInt()
                val bmp = t.thumb
                if (bmp != null) {
                    card.addView(android.widget.ImageView(this).apply { setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; background = ui.box(Color.WHITE, 0x00000000, 10) }, LinearLayout.LayoutParams(-1, thumbH))
                } else {
                    card.addView(FrameLayout(this).apply {
                        background = ui.box(if (dark) 0xFF1B1C1F.toInt() else th.surface, 0x00000000, 10)
                        addView(ui.text(Sniff.nameOf(url).ifBlank { "🌐" }.take(24), 14f, if (dark) 0xFFBBBBBB.toInt() else th.muted).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                    }, LinearLayout.LayoutParams(-1, thumbH))
                }
                row.addView(card, LinearLayout.LayoutParams(cardW, -2).apply { setMargins(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)) })
            }
            box.addView(row)
        }
    }
    private fun closeAllTabs(priv: Boolean = false) {
        val victims = tabs.filter { it.priv == priv }
        val curT = tabs.getOrNull(curIdx)
        for (t in victims) try { t.wv?.let { (it.parent as? ViewGroup)?.removeView(it); if (t.priv) { it.clearCache(true); it.clearHistory(); it.clearFormData() }; it.destroy() } } catch (_: Throwable) {}
        tabs.removeAll(victims.toSet())
        tabsDlg?.dismiss()
        if (tabs.isEmpty()) { newTab(home(), true); return }
        if (curT != null && curT in victims) select(0) else { curIdx = tabs.indexOf(curT).coerceAtLeast(0); refreshTabsCount(); persistTabs() }
    }

    private fun entryRow(e: BrowserStore.Entry, onOpen: () -> Unit, onDel: () -> Unit): View {
        val c = ui.card()
        val row = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(ui.text(e.title.ifBlank { Sniff.nameOf(e.url) }, 14f, th.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(ui.text(e.url, 11f, th.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(IconTextView(this).apply { text = "✕"; textSize = 18f; setTextColor(th.muted); setPadding(ui.dp(12), ui.dp(6), ui.dp(4), ui.dp(6)); setOnClickListener { onDel() } })
        c.addView(row); c.setOnClickListener { onOpen() }
        return c
    }
    private fun showHistory() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val q = ui.input("دوّر في السجل", "")
        lateinit var d: Dialog
        fun render() {
            box.removeAllViews()
            val f = q.text.toString().trim().lowercase()
            val l = BrowserStore.history(this).filter { f.isEmpty() || it.url.lowercase().contains(f) || it.title.lowercase().contains(f) }.take(200)
            if (l.isEmpty()) box.addView(ui.text("مفيش حاجة في السجل", 13f, th.muted))
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
            var lastDay = ""
            for (e in l) {
                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(e.time))
                if (day != lastDay) { lastDay = day; box.addView(ui.text(fmt.format(java.util.Date(e.time)).substringBefore(' '), 12f, th.primary, true).apply { setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(2)) }) }
                box.addView(entryRow(e, { d.dismiss(); load(e.url) }, { BrowserStore.removeHistory(this, e); render() }))
            }
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { render() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val clear = ui.button("🗑 امسح السجل كله") { BrowserStore.clearHistory(this); render(); Notice.show(this, "السجل اتمسح", 1800L) }
        d = sheet("🕘 السجل", q, clear, box)
        render(); d.show()
    }
    private fun showBookmarks() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        lateinit var d: Dialog
        fun render() {
            box.removeAllViews()
            val l = BrowserStore.bookmarks(this)
            if (l.isEmpty()) box.addView(ui.text("مفيش مفضلة لسه — من ⋮ دوس «ضيف للمفضلة»", 13f, th.muted))
            for (e in l) box.addView(entryRow(e, { d.dismiss(); load(e.url) }, { BrowserStore.removeMark(this, e); render() }))
        }
        d = sheet("⭐ المفضلة", box)
        render(); d.show()
    }
    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        box.addView(ui.switchRow("🛡 مانع الإعلانات", adOn) { v ->
            adOn = v; prefs().edit().putBoolean("adblock", v).apply()
            shield.text = if (adOn) "🛡 $blockedN" else "🛡✗"; shield.alpha = if (adOn) 1f else 0.5f
        })
        box.addView(ui.switchRow("🖥 نسخة الكمبيوتر في التبويبات الجديدة", prefs().getBoolean("br_desktop_default", false)) { v -> prefs().edit().putBoolean("br_desktop_default", v).apply() })
        box.addView(ui.switchRow("💾 احفظ التبويبات لما أخرج وارجّعها", prefs().getBoolean("br_save_tabs", true)) { v ->
            prefs().edit().putBoolean("br_save_tabs", v).apply()
            if (v) persistTabs() else try { java.io.File(filesDir, "br_tabs.json").delete() } catch (_: Throwable) {}
        })
        box.addView(ui.text("محرك البحث", 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        box.addView(ui.chips(listOf("Google", "DuckDuckGo", "Bing"), { prefs().getString("br_engine", "Google") ?: "Google" }) { prefs().edit().putString("br_engine", it).apply() })
        box.addView(ui.text("الصفحة الرئيسية (فاضي = جوجل)", 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        val homeEt = ui.input("https://www.google.com", prefs().getString("br_home", "") ?: "").apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        homeEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { prefs().edit().putString("br_home", s?.toString()?.trim() ?: "").apply() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        box.addView(homeEt)
        box.addView(ui.button("🧹 امسح الكاش") { try { tabs.forEach { it.wv?.clearCache(true) } } catch (_: Throwable) {}; Notice.show(this, "الكاش اتمسح", 1800L) })
        box.addView(ui.button("🍪 امسح الكوكيز وبيانات المواقع (هتتسجّل خروج من المواقع)") {
            try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData() } catch (_: Throwable) {}
            Notice.show(this, "الكوكيز وبيانات المواقع اتمسحت", 2000L)
        })
        box.addView(ui.button("🕘 امسح السجل") { BrowserStore.clearHistory(this); Notice.show(this, "السجل اتمسح", 1800L) })
        sheet("⚙️ إعدادات المتصفح", box).show()
    }

    // ===== ضغطة طويلة على لينك/صورة =====
    private fun longPress(w: WebView): Boolean {
        val r = w.hitTestResult
        val extra = r.extra ?: return false
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> { linkSheet(extra, false); return true }
            WebView.HitTestResult.IMAGE_TYPE -> { linkSheet(extra, true); return true }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val hd = Handler(Looper.getMainLooper()) { m -> val u = m.data?.getString("url"); linkSheet(if (!u.isNullOrBlank()) u else extra, false); true }
                w.requestFocusNodeHref(hd.obtainMessage()); return true
            }
        }
        return false
    }
    private fun linkSheet(u: String, image: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        lateinit var d: Dialog
        box.addView(ui.text(u, 11f, th.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR })
        if (!image) {
            box.addView(ui.button("➕ افتح في تبويب جديد") { d.dismiss(); newTab(u, true, tab.id) })
            box.addView(ui.button("🫥 افتح في تبويب جديد (في الخلفية)") { d.dismiss(); newTab(u, false, tab.id); Notice.show(this, "اتفتح في تبويب جديد", 1500L) })
        } else {
            box.addView(ui.button("🖼 افتح الصورة في تبويب جديد") { d.dismiss(); newTab(u, true, tab.id) })
            box.addView(ui.button("⬇ احفظ الصورة") { d.dismiss(); startDownload(u, uaOf(tab), URLUtil.guessFileName(u, null, null), null) })
        }
        box.addView(ui.button("📋 انسخ اللينك") { d.dismiss(); copyUrl(u) })
        box.addView(ui.button("📤 شارك") { d.dismiss(); shareUrl(u) })
        d = sheet(if (image) "🖼 صورة" else "🔗 لينك", box); d.show()
    }

    private fun startDownload(url: String, ua: String?, name: String, mime: String?) {
        try {
            val rq = DownloadManager.Request(Uri.parse(url))
            CookieManager.getInstance().getCookie(url)?.let { rq.addRequestHeader("Cookie", it) }
            rq.addRequestHeader("User-Agent", ua ?: uaWeb)
            if (tab.url.isNotBlank()) rq.addRequestHeader("Referer", tab.url)
            if (!mime.isNullOrBlank()) rq.setMimeType(mime)
            rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(rq)
            Notice.show(this, "⬇ بدأ التحميل: $name", 2300L)
        } catch (e: Throwable) { LogStore.err("Browser:download", e); Notice.show(this, "التحميل فشل: " + (e.message ?: "").take(60), 3000L) }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 4711) { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCb = null; return }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ===== صيد الفيديو (زي الأول) =====
    private fun scanOnce() {
        if (dead) return
        val wv = wvOrNull ?: return
        try { wv.evaluateJavascript("(function(){return document.querySelector('video')?1:0})()") { r -> if (!dead) ctl.visibility = if (r?.trim() == "1" || customView != null) View.VISIBLE else View.GONE } } catch (e: Throwable) { LogStore.err("Browser:scan1", e) }
        try {
            wv.evaluateJavascript(JS) { r ->
                try {
                    val s = JSONTokener(r ?: "").nextValue() as? String
                    if (!s.isNullOrEmpty()) { val arr = JSONArray(s); for (i in 0 until arr.length()) Sniff.accept(arr.getString(i))?.let { addUrl(it) } }
                } catch (e: Throwable) { LogStore.err("Browser:scan2", e) }
            }
        } catch (e: Throwable) { LogStore.err("Browser:scan3", e) }
        scanGroups()
    }
    private fun scanGroups() {
        val wv = wvOrNull ?: return
        try {
            wv.evaluateJavascript(JSQ) { r ->
                try {
                    val s = JSONTokener(r ?: "").nextValue() as? String ?: return@evaluateJavascript
                    val arr = JSONArray(s)
                    for (i in 0 until arr.length()) {
                        val ga = arr.getJSONArray(i); val g = ArrayList<Pair<String, Int>>()
                        for (j in 0 until ga.length()) { val o = ga.getJSONObject(j); val u = Sniff.accept(o.optString("u")) ?: continue; g.add(u to o.optInt("h")); addUrl(u) }
                        if (g.size > 1 && webGroups.none { x -> x.any { y -> g.any { z -> Sniff.key(z.first) == Sniff.key(y.first) } } }) webGroups.add(g)
                    }
                } catch (e: Throwable) { LogStore.err("Browser:grp", e) }
            }
        } catch (e: Throwable) { LogStore.err("Browser:grp2", e) }
    }
    private fun scanLoop() { if (dead) return; scanOnce(); h.postDelayed({ scanLoop() }, 2000) }

    private val ytPicks = HashMap<String, YtExtract.Pick>()   // (v119) معرّف → أحسن اختيار (فيه الصوت المنفصل وقايمة الجودات)
    private fun ytFetch(id: String) {
        hint.text = "⏳ بجيب روابط يوتيوب…"
        Thread {
            val r = try { YtExtract.fetch(id) } catch (_: Throwable) { emptyList() }
            // لو الصيغ الجاهزة فشلت: نجرّب الصور+صوت المنفصلين / HLS (نفس طريقة بوابة يوتيوب)
            val pk = if (r.isEmpty()) try { YtExtract.fetchPick(id, Cfg.int("yt_maxh", 0)) } catch (_: Throwable) { null } else null
            runOnUiThread {
                if (dead) return@runOnUiThread
                r.forEach { ytIds[Sniff.key(it.url)] = id; addFound(it) }
                if (pk != null) {
                    ytPicks[id] = pk
                    val f = Found(pk.url, if (pk.label == "HLS") "HLS" else "YT " + pk.label.substringBefore(' '), pk.title, "https://www.youtube.com/", pk.ua)
                    ytIds[Sniff.key(f.url)] = id; addFound(f)
                }
                if (r.isEmpty() && pk == null && items.isEmpty()) hint.text = "يوتيوب ما رضيش يدّي لينك مباشر — شغّل الفيديو في الصفحة وأنا هحاول ألقطه\n" + YtExtract.lastWhy
            }
        }.apply { isDaemon = true }.start()
    }

    fun addUrl(u: String) = addFound(Found(u, Sniff.kindOf(u), "", wvOrNull?.url ?: "", if (tabs.isNotEmpty()) uaOf(tab) else uaWeb))
    fun addFound(f: Found) {
        if (!keys.add(Sniff.key(f.url))) return
        if (f.size > 0 || f.kind == "HLS") { accept(f); return }
        probe(f) { accept(f) }   // حجم مش معروف: نفحصه الأول، وأي فيديو أقل من 3 ميجا (إعلانات غالبًا) بيتتجاهل
    }
    private fun accept(f: Found) {
        if (dead) return
        if (f.size in 1 until Sniff.MIN_BYTES) return
        items.add(f)
        badge.text = "🎬 لقيت ${items.size} فيديو — اضغط للاختيار"; badge.visibility = View.VISIBLE; hint.visibility = View.GONE
        if (autoOpen && !autoShown) { autoShown = true; showList() }
        else if (dlg?.isShowing == true) renderList()
        else if (items.size == 1) Notice.show(this, "🎬 لقيت فيديو", 2300L)
    }

    private fun probe(f: Found, done: () -> Unit) {
        val ck = try { CookieManager.getInstance().getCookie(f.url) ?: "" } catch (_: Throwable) { "" }
        Thread {
            try {
                val c = java.net.URL(f.url).openConnection() as java.net.HttpURLConnection
                c.requestMethod = "GET"; c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = true
                c.setRequestProperty("Range", "bytes=0-0")
                c.setRequestProperty("User-Agent", f.ua.ifEmpty { uaWeb })
                if (f.ref.isNotEmpty()) c.setRequestProperty("Referer", f.ref)
                if (ck.isNotEmpty()) c.setRequestProperty("Cookie", ck)
                c.connect()
                val cr = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                f.size = cr ?: if (c.responseCode == 206) -1L else c.contentLengthLong
                f.mime = c.contentType ?: ""
                c.disconnect()
            } catch (e: Throwable) { LogStore.err("Browser:probe", e) }
            runOnUiThread { if (!dead) done() }
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
        c.addView(ui.text(titleFor(f).ifEmpty { Sniff.nameOf(f.url) }, 12f, th.muted).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR
        })
        val br = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        br.addView(ui.button("🌐 ترجمة وفرجة", true) { dlg?.dismiss(); openPlayer(f, false) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1.3f).apply { marginEnd = ui.dp(4) } })
        br.addView(ui.button("▶ فرجة بس") { dlg?.dismiss(); openPlayer(f, true) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) } })
        br.addView(ui.button("📋") { copyUrl(f.url) }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2) })
        c.addView(br)
        return c
    }

    private fun leaveCustom() {
        val v = customView ?: return
        customBox.removeView(v); customBox.visibility = View.GONE; content.visibility = View.VISIBLE
        customView = null; try { customCb?.onCustomViewHidden() } catch (e: Throwable) { LogStore.err("Browser:custom", e) }; customCb = null
        if (oldOrient != -1) requestedOrientation = oldOrient
        @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        applyBars(th)
    }
    private fun vjs(body: String, toast: Boolean = true) {
        val wv = wvOrNull ?: return
        val js = "(function(){var vs=[].slice.call(document.querySelectorAll('video'));vs.sort(function(a,b){return b.videoWidth*b.videoHeight-a.videoWidth*a.videoHeight});" +
            "var v=vs[0];if(!v)return 'مفيش فيديو';var s=window.__sv=window.__sv||{fit:0,z:1,sp:1};$body})()"
        wv.evaluateJavascript(js) { r ->
            if (!toast) return@evaluateJavascript
            val t = try { JSONTokener(r ?: "").nextValue() as? String ?: "" } catch (_: Throwable) { "" }
            if (t.isNotEmpty()) Notice.show(this, t, 2300L)
        }
    }
    private fun buildControls(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
        fun chip(t: String, f: () -> Unit) = IconTextView(this).apply {
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
            wvOrNull?.evaluateJavascript("(function(){var v=document.querySelector('video');return v?(v.currentSrc||v.src||''):''})()") { r ->
                val u = try { JSONTokener(r ?: "").nextValue() as? String ?: "" } catch (_: Throwable) { "" }
                if (u.startsWith("http")) { Sniff.accept(u)?.let { addUrl(it) } }
                if (items.isEmpty()) Notice.show(this, "مفيش رابط مباشر للفيديو ده (محمي أو blob) — كمّل فرجة هنا بالأزرار", 3600L) else showList()
            }
        })
        shield = chip("🛡") {
            adOn = !adOn; try { prefs().edit().putBoolean("adblock", adOn).apply() } catch (e: Exception) { LogStore.err("Browser:ad", e) }
            shield.text = if (adOn) "🛡 $blockedN" else "🛡✗"; shield.alpha = if (adOn) 1f else 0.5f
            Notice.show(this, if (adOn) "مانع الإعلانات شغال" else "مانع الإعلانات اتقفل", 2300L)
            try { wvOrNull?.reload() } catch (e: Throwable) { LogStore.err("Browser:reload", e) }
        }
        shield.text = if (adOn) "🛡" else "🛡✗"; shield.alpha = if (adOn) 1f else 0.5f; shield.textSize = 12f
        col.addView(shield)
        return col
    }

    fun openPlayer(f: Found, noSub: Boolean) { if (noSub) openPlayerNow(f, true) else ensureKeys { openPlayerNow(f, false) } }
    fun openPlayerNow(f: Found, noSub: Boolean) {
        // (v117) فيديو يوتيوب: بنعرف معرّفه (من لينك الصفحة) عشان يتسجل في بوابة يوتيوب ويتحفظ تقدمه وترجمته على معرّفه
        val isYtStream = f.kind.startsWith("YT") || f.url.contains("googlevideo") || f.url.contains("videoplayback")
        val yid = ytIds[Sniff.key(f.url)] ?: YtExtract.videoId(f.ref) ?: (if (isYtStream) YtExtract.videoId(wvOrNull?.url ?: "") else null) ?: ""
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            if (yid.isNotEmpty()) {
                putExtra("ytid", yid)
                ytPicks[yid]?.let { pk -> if (pk.url == f.url) { putExtra("aurl", pk.audio ?: ""); putExtra("qlist", pk.optsJson()) } }
            }
            if (yid.isEmpty()) try { webQlist(f)?.let { putExtra("qlist", it) } } catch (e: Throwable) { LogStore.err("Browser:wq", e) }
            putExtra("url", f.url); putExtra("ref", f.ref)
            putExtra("cookie", try { CookieManager.getInstance().getCookie(f.url) ?: "" } catch (_: Throwable) { "" })
            putExtra("ua", f.ua.ifEmpty { uaWeb }); putExtra("title", titleFor(f)); putExtra("nosub", noSub); putExtra("autotr", !noSub); putExtra("incognito", tabs.getOrNull(curIdx)?.priv == true)
        })
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) { leaveCustom(); return }
        if (findBar.visibility == View.VISIBLE) { closeFind(); return }
        val w = wvOrNull
        if (w != null && w.canGoBack()) { w.goBack(); return }
        // تبويب اتفتح من تبويب تاني: الرجوع يقفله ويرجّعك للأصلي
        if (tabs.size > 1 && tab.opener >= 0 && tabs.any { it.id == tab.opener }) { closeTab(curIdx); return }
        persistTabs()
        super.onBackPressed()
    }
    override fun onPause() { persistTabs(); try { wvOrNull?.onPause() } catch (_: Throwable) {}; super.onPause() }
    override fun onResume() { super.onResume(); try { wvOrNull?.onResume() } catch (_: Throwable) {} }
    override fun onDestroy() {
        dead = true; h.removeCallbacksAndMessages(null)
        try { dlg?.dismiss() } catch (e: Throwable) { LogStore.err("Browser:d1", e) }
        try { tabsDlg?.dismiss() } catch (e: Throwable) { LogStore.err("Browser:d2", e) }
        for (t in tabs) try { t.wv?.destroy() } catch (e: Throwable) { LogStore.err("Browser:d3", e) }
        super.onDestroy()
    }
}
