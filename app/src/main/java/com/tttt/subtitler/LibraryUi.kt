package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/**
 * الشاشة الرئيسية: متصفح فيديوهات الجهاز زي MX Player.
 * المستوى الأول: فولدرات في عمود. المستوى التاني: فيديوهات الفولدر بصورة مصغّرة من الفيديو نفسه + ترتيب (الاسم/الأحدث/الأقدم/الحجم).
 */
class LibraryUi(
    private val act: Activity, private val ui: Ui, private val th: Theme,
    private val recents: () -> Map<String, Recent>,
    private val onPlay: (VideoItem) -> Unit
) {
    var onRefresh: () -> Unit = {}
    var onGrant: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var onLink: () -> Unit = {}
    var onPick: () -> Unit = {}

    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
    private var all: List<VideoItem> = emptyList()
    private var curFolder: String? = null
    private var mode = "scan"            // scan | perm | ready
    private var rows: List<Any> = emptyList()
    private var recMap: Map<String, Recent> = emptyMap()
    private var rootPos = 0

    private val backV = TextView(act).apply {
        text = "→"; textSize = 24f; setTextColor(th.primary); gravity = Gravity.CENTER; visibility = View.GONE
        setPadding(ui.dp(8), 0, ui.dp(12), 0); setOnClickListener { back() }
    }
    private val titleTv = ui.text("📁 الفيديوهات", 19f, th.primary, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    private val subTv = ui.text("", 11f, th.muted).apply { setSingleLine() }
    private val listV = ListView(act)
    private val stateBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28)) }
    private val stateTv = ui.text("", 14f, th.muted).apply { gravity = Gravity.CENTER }
    private val stateBar = ProgressBar(act)
    private val stateBtn = ui.button("", true) { onGrant() }
    private lateinit var refreshBtn: TextView

    private fun sortKey() = Cfg.str("lib_sort", VideoLib.SORT_NAME)

    private fun hbtn(t: String, f: () -> Unit): TextView = ui.circleBtn(t, false, f).apply {
        textSize = 17f; layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) }
    }

    init {
        // الهيدر: العنوان يمين، وأزرار (رابط · فتح من الملفات · إعدادات · ريفريش) — الريفريش في أقصى الشمال
        val head = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(4)) }
        head.addView(backV)
        val tcol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(titleTv); addView(subTv) }
        head.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(hbtn("🔗") { onLink() })
        head.addView(hbtn("📂") { onPick() })
        head.addView(hbtn("⚙️") { onSettings() })
        refreshBtn = hbtn("🔄") { refreshBtn.animate().rotationBy(360f).setDuration(600).start(); onRefresh() }
        head.addView(refreshBtn)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        val labels = VideoLib.sortLabels.map { it.second }
        val chips = ui.chips(labels, { VideoLib.sortLabelOf(sortKey()) }) { n -> Cfg.put("lib_sort", VideoLib.sortKeyOf(n)); render() }
        root.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(8), 0, ui.dp(8), 0); addView(chips) },
            LinearLayout.LayoutParams(-1, -2))

        listV.apply {
            divider = null; dividerHeight = 0; setSelector(android.R.color.transparent); isVerticalScrollBarEnabled = false
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16)); clipToPadding = false; cacheColorHint = Color.TRANSPARENT
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(i: Int): Any = rows[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getViewTypeCount() = 2
                override fun getItemViewType(i: Int) = if (rows[i] is FolderItem) 0 else 1
                override fun getView(i: Int, cv: View?, p: ViewGroup?): View {
                    val r = rows[i]
                    return if (r is FolderItem) (cv ?: newFolderRow()).also { bindFolder(it, r) }
                    else (cv ?: newVideoRow()).also { bindVideo(it, r as VideoItem) }
                }
            }
        }
        stateBox.addView(stateBar, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        stateBox.addView(stateTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(12) })
        stateBox.addView(stateBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(12) })
        val body = FrameLayout(act)
        body.addView(listV, FrameLayout.LayoutParams(-1, -1)); body.addView(stateBox, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    // ===== الحالات =====
    fun showScanning() { mode = "scan"; render() }
    fun showNoPermission() { mode = "perm"; render() }
    fun showVideos(list: List<VideoItem>) { all = list; mode = "ready"; render() }
    val hasData: Boolean get() = mode == "ready" && all.isNotEmpty()

    /** رجوع من فولدر للقايمة الرئيسية. بترجّع true لو استهلكت الضغطة */
    fun back(): Boolean {
        if (curFolder == null) return false
        curFolder = null; render(); listV.setSelection(rootPos); return true
    }

    fun render() {
        recMap = try { recents() } catch (_: Exception) { emptyMap() }
        val sort = sortKey()
        when (mode) {
            "scan" -> { setState(true, "⏳ جاري فحص التخزين…", null); return }
            "perm" -> { setState(false, "محتاج إذن الوصول للفيديوهات عشان أعرض فولدرات الجهاز.", "السماح بالوصول"); return }
        }
        if (all.isEmpty()) { setState(false, "مفيش فيديوهات اتلاقت على الجهاز.\nلو فيديوهاتك موجودة دوس 🔄 للفحص من جديد، أو افتح فيديو من 📂.", null); return }
        val cf = curFolder
        val folders = VideoLib.group(all)
        val folder = if (cf != null) folders.firstOrNull { it.key == cf } else null
        if (cf != null && folder == null) curFolder = null
        if (folder != null) {
            rows = VideoLib.sortVideos(folder.videos, sort)
            titleTv.text = "📂 " + folder.name; subTv.text = "${folder.videos.size} فيديو · ${VideoLib.fmtSize(folder.totalSize)}"; backV.visibility = View.VISIBLE
        } else {
            rows = VideoLib.sortFolders(folders, sort)
            titleTv.text = "📁 الفيديوهات"; subTv.text = "${folders.size} مجلد · ${all.size} فيديو"; backV.visibility = View.GONE
        }
        stateBox.visibility = View.GONE; listV.visibility = View.VISIBLE
        (listV.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun setState(busy: Boolean, msg: String, btn: String?) {
        titleTv.text = "📁 الفيديوهات"; subTv.text = ""; backV.visibility = View.GONE
        listV.visibility = View.GONE; stateBox.visibility = View.VISIBLE
        stateBar.visibility = if (busy) View.VISIBLE else View.GONE
        stateTv.text = msg
        stateBtn.visibility = if (btn != null) View.VISIBLE else View.GONE
        if (btn != null) stateBtn.text = btn
    }

    // ===== صف الفولدر =====
    private fun newFolderRow(): View {
        val icon = TextView(act).apply { text = "📁"; textSize = 24f; gravity = Gravity.CENTER; background = ui.box(th.surface, th.border, 12) }
        val name = ui.text("", 15f, th.text, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val info = ui.text("", 12f, th.primary)
        val path = ui.text("", 10f, th.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.MIDDLE }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(name); addView(info); addView(path) }
        val chev = TextView(act).apply { text = "‹"; textSize = 26f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(ui.dp(8), 0, ui.dp(4), 0) }
        val card = LinearLayout(act).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10)); background = ui.box(th.card, th.border, 14)
            addView(icon, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12) })
            addView(chev)
        }
        val wrap = FrameLayout(act).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)); addView(card, FrameLayout.LayoutParams(-1, -2)); tag = arrayOf(name, info, path, card) }
        return wrap
    }

    @Suppress("UNCHECKED_CAST")
    private fun bindFolder(v: View, f: FolderItem) {
        val t = v.tag as Array<View>
        (t[0] as TextView).text = f.name
        (t[1] as TextView).text = "${f.count} فيديو · ${VideoLib.fmtSize(f.totalSize)}"
        (t[2] as TextView).text = f.path
        t[3].setOnClickListener { rootPos = listV.firstVisiblePosition; curFolder = f.key; render(); listV.setSelection(0) }
    }
    private val FolderItem.count: Int get() = videos.size

    // ===== صف الفيديو =====
    private class VH(val iv: ImageView, val dur: TextView, val title: TextView, val meta: TextView, val state: TextView, val card: View)

    private fun newVideoRow(): View {
        val ph = TextView(act).apply { text = "🎞"; textSize = 24f; gravity = Gravity.CENTER; alpha = 0.45f }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val dur = TextView(act).apply {
            textSize = 10f; setTextColor(Color.WHITE); setPadding(ui.dp(5), ui.dp(1), ui.dp(5), ui.dp(1)); background = ui.box(0xCC000000.toInt(), Color.TRANSPARENT, 4)
        }
        val thumb = FrameLayout(act).apply {
            background = ui.box(0xFF000000.toInt(), th.border, 10); clipToOutline = true
            addView(ph, FrameLayout.LayoutParams(-1, -1)); addView(iv, FrameLayout.LayoutParams(-1, -1))
            addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
        }
        val title = ui.text("", 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val meta = ui.text("", 11f, th.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val state = ui.text("", 11f, th.primary).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(3) }); addView(state, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(2) }) }
        val card = LinearLayout(act).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(8), ui.dp(10), ui.dp(8)); background = ui.box(th.card, th.border, 12)
            addView(thumb, LinearLayout.LayoutParams(ui.dp(128), ui.dp(72)))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
        }
        return FrameLayout(act).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)); addView(card, FrameLayout.LayoutParams(-1, -2)); tag = VH(iv, dur, title, meta, state, card) }
    }

    private fun bindVideo(v: View, vi: VideoItem) {
        val h = v.tag as VH
        h.title.text = vi.title
        h.dur.text = VideoLib.fmtDur(vi.durMs); h.dur.visibility = if (vi.durMs > 0) View.VISIBLE else View.GONE
        h.meta.text = listOf(VideoLib.fmtSize(vi.size), vi.ext.uppercase(), VideoLib.fmtDate(vi.dateMs)).filter { it.isNotEmpty() }.joinToString(" · ")
        val r = recMap[vi.videoId]
        val parts = ArrayList<String>()
        if (r != null && r.posSec > 5) parts.add("▶ وقف عند " + PlayerLogic.clock((r.posSec * 1000).toLong()))
        if (r != null && r.subs > 0) parts.add("✓ مترجم ${r.percent}%")
        h.state.text = parts.joinToString(" · "); h.state.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        h.iv.tag = vi.uri; h.iv.setImageDrawable(null)
        val c = Thumbs.peek(vi)
        if (c != null) h.iv.setImageBitmap(c)
        else Thumbs.load(act, vi) { b -> if (b != null && h.iv.tag == vi.uri) h.iv.setImageBitmap(b) }
        h.card.setOnClickListener { onPlay(vi) }
    }
}
