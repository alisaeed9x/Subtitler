package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** فيديو يوتيوب اتفتح قبل كده (v117): المعرّف (11 حرف) + الاسم + آخر مرة اتفتح */
class YtItem(val id: String, val title: String, val ts: Long)

/** سجل فيديوهات يوتيوب (yt_history.json): بيتسجل من المتصفح ومن بوابة يوتيوب، والمعرّف بيتحوّل للينك قابل للنسخ */
object YtHistory {
    private val lock = Any()
    private const val MAX = 300
    private fun file(ctx: Context) = File(ctx.filesDir, "yt_history.json")
    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun read(ctx: Context): List<YtItem> = try {
        val a = JSONArray(file(ctx).readText().ifEmpty { "[]" })
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .map { YtItem(it.optString("id"), it.optString("t"), it.optLong("ts", 0)) }
            .filter { it.id.length == 11 }
    } catch (_: Exception) { emptyList() }

    private fun write(ctx: Context, l: List<YtItem>) {
        try {
            val a = JSONArray()
            for (x in l.take(MAX)) a.put(JSONObject().put("id", x.id).put("t", x.title).put("ts", x.ts))
            file(ctx).writeText(a.toString())
        } catch (e: Exception) { LogStore.err("YtHistory", e) }
    }

    fun load(ctx: Context): List<YtItem> = synchronized(lock) { read(ctx).sortedByDescending { it.ts } }

    /** نفس الفيديو بيطلع فوق، وبيحتفظ باسمه القديم لو الاسم الجديد فاضي */
    fun add(ctx: Context, id: String, title: String) {
        if (id.length != 11) return
        synchronized(lock) {
            val l = read(ctx)
            val old = l.firstOrNull { it.id == id }
            val t = title.trim().ifEmpty { old?.title ?: "" }
            write(ctx, listOf(YtItem(id, t, System.currentTimeMillis())) + l.filter { it.id != id })
        }
    }

    fun remove(ctx: Context, id: String) { synchronized(lock) { write(ctx, read(ctx).filter { it.id != id }) } }
    fun clear(ctx: Context) { synchronized(lock) { try { file(ctx).delete() } catch (_: Exception) {} } }
}

/** صور مصغّرة ليوتيوب (بتتحمّل مرة وتتخزّن في الجهاز) */
object YtThumbs {
    private val cache = android.util.LruCache<String, Bitmap>(60)
    private val main = Handler(Looper.getMainLooper())
    private fun f(ctx: Context, id: String) = File(File(ctx.filesDir, "ytthumbs"), "$id.jpg")
    fun peek(id: String): Bitmap? = cache.get(id)
    fun load(ctx: Context, id: String, cb: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            var b: Bitmap? = null
            try {
                val file = f(app, id)
                if (file.exists()) b = BitmapFactory.decodeFile(file.path)
                if (b == null) {
                    val c = java.net.URL("https://i.ytimg.com/vi/$id/mqdefault.jpg").openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    val bytes = c.inputStream.use { it.readBytes() }
                    c.disconnect()
                    b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (b != null) { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
                }
            } catch (e: Throwable) { LogStore.err("YtThumbs", e) }
            val res = b
            if (res != null) cache.put(id, res)
            main.post { cb(res) }
        }.apply { isDaemon = true }.start()
    }
}

/**
 * بوابة يوتيوب (v118): ثلاث شاشات بشريط فوق —
 * 🏠 يوتيوب نفسه (فيد وبحث واشتراكات حسابك لو سجّلت دخول) والفيديو اللي تدوس عليه بيفتح في مشغّلنا ·
 * 📺 اشتراكاتي (استيراد من Takeout أو لينكات، من غير تسجيل دخول) · 🕘 السجل (اللي فتحته قبل كده + لصق لينك / بحث).
 */
class YoutubeUi(
    private val act: Activity, private val ui: Ui, private val th: Theme,
    private val recents: () -> Map<String, Recent>,
    private val onOpen: (String, String, Boolean) -> Unit,   // معرّف، اسم، بترجمة؟
    private val onBrowse: (String) -> Unit,
    private val onPickFile: () -> Unit                        // يفتح اختيار ملف الاشتراكات (Takeout)
) {
    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
    private val web = YtWebPane(act) { id -> onOpen(id, "", true) }
    private val histPane = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val subsPane = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val listBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16)) }
    private val subsBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16)) }
    private val subTv = ui.text("", 11f, th.muted)
    private val linkEt = ui.input("الصق لينك يوتيوب أو اكتب كلمة بحث", "").apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR; textSize = 13f
        imeOptions = EditorInfo.IME_ACTION_GO; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }
    private val chips = ArrayList<IconTextView>()
    private var clearB: View? = null
    private var mode = 0
    private var feedToken = 0
    private var lastClip = ""

    init {
        val head = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), ui.dp(10), ui.dp(10), ui.dp(4)) }
        val tcol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(ui.text("▶ يوتيوب", 19f, th.primary, true)); addView(subTv) }
        head.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f))
        val loginB = ui.circleBtn("👤", false) { }.apply { textSize = 16f; layoutParams = LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(6) } }
        loginB.setOnClickListener { accountMenu(loginB) }
        val cb = ui.circleBtn("🗑", false) { confirmClear() }.apply { textSize = 16f; layoutParams = LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)); visibility = View.GONE }
        clearB = cb
        head.addView(loginB); head.addView(cb)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        // شريط الشاشات
        val modes = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(6)) }
        listOf("🏠 يوتيوب", "📺 اشتراكاتي", "🕘 السجل").forEachIndexed { i, t ->
            val c = IconTextView(act).apply { text = t; textSize = 13f; gravity = Gravity.CENTER; setPadding(ui.dp(6), ui.dp(9), ui.dp(6), ui.dp(9)); setSingleLine(); setOnClickListener { setMode(i) } }
            chips.add(c)
            modes.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) })
        }
        root.addView(modes, LinearLayout.LayoutParams(-1, -2))

        // شاشة السجل (زي v117): لصق لينك / بحث + تصفّح + قايمة الفيديوهات اللي فتحتها
        val goB = ui.button("▶ افتح", true) { go() }
        val inRow = LinearLayout(act).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(12), ui.dp(2), ui.dp(12), 0)
            addView(linkEt, LinearLayout.LayoutParams(0, -2, 1f))
            addView(goB, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginStart = ui.dp(6) })
        }
        histPane.addView(inRow, LinearLayout.LayoutParams(-1, -2))
        linkEt.setOnEditorActionListener { _, _, _ -> go(); true }
        histPane.addView(ui.button("🌐 تصفّح يوتيوب في المتصفح الكامل") { onBrowse("https://m.youtube.com") },
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2)) })
        val hs = ScrollView(act).apply { addView(listBox); isVerticalScrollBarEnabled = false }
        histPane.addView(hs, LinearLayout.LayoutParams(-1, 0, 1f))

        // شاشة اشتراكاتي
        val tb = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(9), ui.dp(2), ui.dp(9), ui.dp(2)) }
        fun tbtn(t: String, f: () -> Unit): View = ui.button(t) { f() }.apply {
            textSize = 12f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) }
        }
        tb.addView(tbtn("📥 ملف") { onPickFile() })
        tb.addView(tbtn("🔗 لصق") { pasteSheet() })
        tb.addView(tbtn("🔄 تحديث") { reloadFeed() })
        tb.addView(tbtn("❓ إزاي") { howTo() })
        subsPane.addView(tb, LinearLayout.LayoutParams(-1, -2))
        val ss = ScrollView(act).apply { addView(subsBox); isVerticalScrollBarEnabled = false }
        subsPane.addView(ss, LinearLayout.LayoutParams(-1, 0, 1f))

        val body = FrameLayout(act)
        body.addView(web.root, FrameLayout.LayoutParams(-1, -1))
        body.addView(subsPane, FrameLayout.LayoutParams(-1, -1))
        body.addView(histPane, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setMode(0, false)
    }

    // ===== الشاشات =====
    private fun setMode(i: Int, load: Boolean = true) {
        mode = i
        web.root.visibility = if (i == 0) View.VISIBLE else View.GONE
        subsPane.visibility = if (i == 1) View.VISIBLE else View.GONE
        histPane.visibility = if (i == 2) View.VISIBLE else View.GONE
        clearB?.visibility = if (i == 2) View.VISIBLE else View.GONE
        val onP = if (th.isLight) Color.WHITE else Color.BLACK
        chips.forEachIndexed { k, c ->
            if (k == i) { c.background = ui.box(th.primary, th.primary, 18); c.setTextColor(onP) }
            else { c.background = ui.box(th.card, th.border, 18); c.setTextColor(th.text) }
        }
        if (i != 0) web.pause()
        if (!load) return
        when (i) { 0 -> { web.show(); webSub() }; 1 -> renderSubs(); else -> refreshHist() }
    }
    private fun webSub() { if (mode == 0) subTv.text = if (web.isLoggedIn()) "✓ داخل بحساب جوجل · اضغط على أي فيديو يشتغل في مشغّلك" else "اضغط على أي فيديو يشتغل في مشغّلك · 👤 للدخول بحساب جوجل" }

    /** بيتنادى لما التبويب يتفتح أو نرجع من المشغّل */
    fun refresh() { when (mode) { 0 -> { web.show(); webSub() }; 1 -> renderSubs(); else -> refreshHist() } }
    fun onHide() { web.pause() }
    /** زرار الرجوع: من السجل/الاشتراكات لشاشة يوتيوب، ومن صفحات يوتيوب لورا */
    fun back(): Boolean { if (mode != 0) { setMode(0); return true }; return web.back() }

    private fun accountMenu(anchor: View) {
        popup(anchor, listOf(
            "👤 تسجيل دخول بحساب جوجل" to { setMode(0); web.login() },
            "🏠 رئيسية يوتيوب" to { setMode(0); web.home() },
            "⟳ تحديث الصفحة" to { web.reload() },
            "🌐 افتح في المتصفح الكامل" to { onBrowse(YtWebPane.HOME) }
        ))
    }

    // ===== اشتراكاتي =====
    private fun renderSubs() {
        val ch = YtSubs.load(act)
        subsBox.removeAllViews()
        if (ch.isEmpty()) {
            subTv.text = "مفيش اشتراكات لسه"
            subsBox.addView(ui.text("مفيش قنوات لسه.\n\n📥 «ملف»: اختار ملف الاشتراكات من Takeout (csv أو zip)\n🔗 «لصق»: الصق لينكات قنوات أو @أسماءها\n❓ «إزاي»: الشرح بالخطوات\n\nأو دوس 👤 وسجّل دخول وهتلاقي اشتراكاتك جوه يوتيوب نفسه.", 13f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(24)) })
            return
        }
        subTv.text = "${ch.size} قناة · أحدث فيديوهاتها"
        val c = YtSubs.cache
        if (c.isNotEmpty()) showVids(c) else loadFeed(ch)
    }
    private fun reloadFeed() {
        val ch = YtSubs.load(act)
        if (ch.isEmpty()) { Notice.show(act, "ضيف قنوات الأول (📥 ملف أو 🔗 لصق)", 2500L); return }
        YtSubs.cache = emptyList(); loadFeed(ch)
    }
    private fun loadFeed(ch: List<YtChan>) {
        subsBox.removeAllViews()
        subsBox.addView(ui.text("⏳ بجيب أحدث فيديوهات ${minOf(ch.size, 80)} قناة…", 13f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(20), ui.dp(30), ui.dp(20), ui.dp(30)) })
        val token = ++feedToken
        Thread {
            val l = try { YtSubs.fetchAll(ch) } catch (e: Throwable) { LogStore.err("YoutubeUi:feed", e); emptyList<YtVid>() }
            act.runOnUiThread {
                if (token != feedToken || act.isFinishing || act.isDestroyed) return@runOnUiThread
                YtSubs.cache = l
                if (mode == 1) showVids(l)
            }
        }.apply { isDaemon = true }.start()
    }
    private fun showVids(l: List<YtVid>) {
        subsBox.removeAllViews()
        if (l.isEmpty()) { subsBox.addView(ui.text("ما قدرتش أجيب فيديوهات — اتأكد من النت وجرّب 🔄", 13f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(20), ui.dp(30), ui.dp(20), ui.dp(30)) }); return }
        for (v in l) subsBox.addView(vidRow(v))
    }
    private fun vidRow(v: YtVid): View {
        val ph = IconTextView(act).apply { text = "▶"; textSize = 24f; gravity = Gravity.CENTER; alpha = 0.45f }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val thumb = FrameLayout(act).apply {
            background = ui.box(0xFF000000.toInt(), th.border, 10); clipToOutline = true
            addView(ph, FrameLayout.LayoutParams(-1, -1)); addView(iv, FrameLayout.LayoutParams(-1, -1))
        }
        iv.tag = v.id
        val cached = YtThumbs.peek(v.id)
        if (cached != null) iv.setImageBitmap(cached)
        else YtThumbs.load(act, v.id) { b -> if (b != null && iv.tag == v.id) iv.setImageBitmap(b) }
        val title = ui.text(v.title.ifBlank { "youtu.be/" + v.id }, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val meta = ui.text(v.chan + (if (v.ts > 0) " · " + VideoLib.fmtDate(v.ts) else ""), 11f, th.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(3) }) }
        val dotsB = IconTextView(act).apply {
            text = "⋮"; textSize = 22f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            setOnClickListener { menu(this, YtItem(v.id, v.title, v.ts)) }
        }
        val card = LinearLayout(act).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(8), ui.dp(4), ui.dp(8)); background = ui.box(th.card, th.border, 12)
            addView(thumb, LinearLayout.LayoutParams(ui.dp(128), ui.dp(72)))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
            addView(dotsB)
        }
        Glass.pressable(card)
        card.setOnClickListener { onOpen(v.id, v.title, true) }
        return FrameLayout(act).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)); addView(card, FrameLayout.LayoutParams(-1, -2)) }
    }

    private fun howTo() {
        GAlert(act).setTitle("❓ إزاي أجيب اشتراكاتي؟")
            .setMessage("الطريقة 1 (الأسهل): دوس 👤 ← «تسجيل دخول» وسجّل بحساب جوجل. بعدها يوتيوب جوه البرنامج بيعرض الفيد والاشتراكات بتاعتك، وأي فيديو تدوس عليه بيشتغل في مشغّلك.\n\n" +
                "الطريقة 2 (من غير تسجيل دخول):\n1) افتح takeout.google.com\n2) اختار YouTube بس ← «اشتراكات» (CSV)\n3) نزّل الملف (zip أو csv)\n4) هنا دوس «📥 ملف» واختاره — هيجيب أحدث فيديوهات كل قنواتك.\n\n" +
                "الطريقة 3: «🔗 لصق» والصق لينكات قنوات أو @أسماءها.")
            .setPositiveButton("تمام", null)
            .setNeutralButton("🌐 افتح Takeout") { _, _ -> onBrowse("https://takeout.google.com/settings/takeout/custom/youtube") }
            .show()
    }

    private fun pasteSheet() {
        val et = ui.input("الصق لينكات قنوات أو @أسماءها — كل واحد في سطر", "", 6).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; textSize = 13f }
        lateinit var d: Dialog
        val add = ui.button("➕ ضيف القنوات", true) { val t = et.text.toString(); d.dismiss(); addFromText(t) }
        val wipe = ui.button("🗑 امسح كل الاشتراكات") { d.dismiss(); YtSubs.clear(act); renderSubs(); Notice.show(act, "الاشتراكات اتمسحت", 1800L) }
        d = ui.sheet(act, "🔗 إضافة قنوات", listOf<View>(et, add, wipe), false)
        d.show()
    }
    private fun addFromText(t: String) {
        Notice.show(act, "⏳ بضيف القنوات…", 2000L)
        Thread {
            val found = ArrayList<YtChan>(YtSubs.scanIds(t))
            val handles = Regex("@([A-Za-z0-9._-]{3,30})").findAll(t).map { it.groupValues[1] }.distinct().take(40).toList()
            for (h in handles) YtSubs.resolveHandle(h)?.let { found.add(it) }
            val n = if (found.isEmpty()) 0 else YtSubs.merge(act, found)
            act.runOnUiThread { done(found.size, n) }
        }.apply { isDaemon = true }.start()
    }
    /** (من Main) ملف الاشتراكات اتاختار */
    fun importUri(u: Uri) {
        Notice.show(act, "⏳ بقرا الملف…", 2000L)
        Thread {
            val l = try { YtSubs.parseFile(act, u) } catch (e: Throwable) { LogStore.err("YoutubeUi:import", e); emptyList<YtChan>() }
            val n = if (l.isEmpty()) 0 else YtSubs.merge(act, l)
            act.runOnUiThread { done(l.size, n) }
        }.apply { isDaemon = true }.start()
    }
    private fun done(total: Int, added: Int) {
        if (act.isFinishing || act.isDestroyed) return
        if (total == 0) { Notice.show(act, "ما لقيتش قنوات — جرّب ملف subscriptions.csv أو لينكات/أسماء القنوات", 4000L); return }
        Notice.show(act, "✓ اتضاف $added قناة جديدة (من $total)", 3000L)
        setMode(1)
    }

    // ===== السجل =====
    private fun go() {
        val q = linkEt.text.toString().trim()
        if (q.isEmpty()) { Notice.show(act, ("الصق لينك يوتيوب أو اكتب كلمة بحث").toString(), 2300L); return }
        val id = YtExtract.videoId(q)
        if (id != null) { linkEt.setText(""); onOpen(id, "", true); return }
        if (q.startsWith("http", true)) { onBrowse(q); return }
        web.loadUrl("https://m.youtube.com/results?search_query=" + Uri.encode(q)); setMode(0, false)
    }

    private fun confirmClear() {
        GAlert(act).setTitle("🗑 مسح سجل يوتيوب")
            .setMessage("هيتمسح سجل فيديوهات يوتيوب كله (الترجمة المحفوظة مش هتتمسح). تمام؟")
            .setPositiveButton("امسح") { _, _ -> YtHistory.clear(act); refreshHist() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun copyLink(id: String) {
        val u = YtHistory.watchUrl(id)
        try {
            try { ClipWatch.markSeen(act, u) } catch (_: Throwable) {}
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", u))
            Notice.show(act, ("📋 اتنسخ لينك الفيديو").toString(), 1800L)
        } catch (e: Throwable) { LogStore.err("YoutubeUi:copy", e) }
    }

    private fun popup(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val pm = PopupMenu(act, anchor)
        items.forEachIndexed { i, p -> pm.menu.add(0, i, i, Icons.convert(p.first)) }
        pm.setOnMenuItemClickListener { m -> items[m.itemId].second(); true }
        pm.show()
    }

    private fun menu(anchor: View, e: YtItem) {
        val ttl = e.title
        popup(anchor, listOf(
            "▶ تشغيل بالترجمة" to { onOpen(e.id, ttl, true) },
            "▶ فرجة بس (من غير ترجمة)" to { onOpen(e.id, ttl, false) },
            "📋 نسخ الرابط" to { copyLink(e.id) },
            "🌐 افتح في المتصفح" to { onBrowse(YtHistory.watchUrl(e.id)) },
            "🗑 مسح من السجل" to { YtHistory.remove(act, e.id); if (mode == 2) refreshHist() else renderSubs() }
        ))
    }

    private fun row(e: YtItem, r: Recent?): View {
        val ph = IconTextView(act).apply { text = "▶"; textSize = 24f; gravity = Gravity.CENTER; alpha = 0.45f }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val thumb = FrameLayout(act).apply {
            background = ui.box(0xFF000000.toInt(), th.border, 10); clipToOutline = true
            addView(ph, FrameLayout.LayoutParams(-1, -1)); addView(iv, FrameLayout.LayoutParams(-1, -1))
        }
        iv.tag = e.id
        val cached = YtThumbs.peek(e.id)
        if (cached != null) iv.setImageBitmap(cached)
        else YtThumbs.load(act, e.id) { b -> if (b != null && iv.tag == e.id) iv.setImageBitmap(b) }

        val title = ui.text(e.title.ifBlank { "youtu.be/" + e.id }, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val meta = ui.text("youtu.be/" + e.id + " · " + VideoLib.fmtDate(e.ts), 11f, th.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val parts = ArrayList<String>()
        if (r != null && r.posSec > 5) parts.add("▶ وقف عند " + PlayerLogic.clock((r.posSec * 1000).toLong()))
        if (r != null && r.subs > 0) parts.add("✓ مترجم ${r.percent}%")
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(title); addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(3) })
            if (parts.isNotEmpty()) addView(ui.text(parts.joinToString(" · "), 11f, th.primary).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(2) })
        }
        val copyB = IconTextView(act).apply {
            text = "📋"; textSize = 17f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            setOnClickListener { copyLink(e.id) }
        }
        val dotsB = IconTextView(act).apply {
            text = "⋮"; textSize = 22f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            setOnClickListener { menu(this, e) }
        }
        val card = LinearLayout(act).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(8), ui.dp(4), ui.dp(8)); background = ui.box(th.card, th.border, 12)
            addView(thumb, LinearLayout.LayoutParams(ui.dp(128), ui.dp(72)))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
            addView(copyB); addView(dotsB)
        }
        Glass.pressable(card)
        card.setOnClickListener { onOpen(e.id, e.title, true) }
        card.setOnLongClickListener { c -> c.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); menu(dotsB, e); true }
        return FrameLayout(act).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)); addView(card, FrameLayout.LayoutParams(-1, -2)) }
    }

    /** يعيد رسم السجل + يلصق لينك يوتيوب من الكليبورد لو لسه ماتلصقش */
    private fun refreshHist() {
        val rec = try { recents() } catch (_: Exception) { emptyMap<String, Recent>() }
        val l = YtHistory.load(act)
        listBox.removeAllViews()
        subTv.text = if (l.isEmpty()) "السجل فاضي" else "${l.size} فيديو في السجل · اضغط على أي فيديو يدخل على طول"
        if (l.isEmpty()) listBox.addView(ui.text("مفيش فيديوهات يوتيوب لسه.\nأي فيديو يوتيوب تفتحه من هنا أو من المتصفح بيتسجل هنا، وتقدر تنسخ لينكه أو ترجعله على طول.", 13f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(20), ui.dp(30), ui.dp(20), ui.dp(30)) })
        for (e in l.take(200)) listBox.addView(row(e, rec["yt:" + e.id]))
        try {
            val t = (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (t != lastClip) {
                lastClip = t
                if (linkEt.text.isNullOrBlank() && YtExtract.videoId(t) != null) Regex("https?://\\S+").find(t)?.let { linkEt.setText(it.value) }
            }
        } catch (_: Exception) {}
    }
}
