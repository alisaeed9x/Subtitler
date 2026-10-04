package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import java.io.File
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

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
    var onPull: () -> Unit = {}
    /** ترجمة في الخلفية: من القايمة (⋮) على الفيديو أو الفولدر */
    var onBg: (VideoItem) -> Unit = {}
    var onBgStop: (VideoItem) -> Unit = {}
    var onBgFolder: (FolderItem) -> Unit = {}
    var onQueue: () -> Unit = {}
    private lateinit var queueBtn: TextView
    var onRename: (VideoItem) -> Unit = {}
    var onMove: (VideoItem) -> Unit = {}
    var onDetails: (VideoItem) -> Unit = {}
    var onShare: (VideoItem) -> Unit = {}
    var onDelete: (VideoItem) -> Unit = {}
    fun allFolders(): List<FolderItem> = VideoLib.group(visible())
    var bgJob: (VideoItem) -> BgJob? = { null }
    fun refreshRows() {
        // ظهور/اختفاء صف «مجلد الترجمة في الخلفية» في الرئيسية
        val hasBg = rows.firstOrNull() === bgFolder
        if (mode == "ready" && curFolder == null && !inHidden && query.isEmpty() && hasBg != BgJobs.jobs.isNotEmpty()) render()
        (listV.adapter as? BaseAdapter)?.notifyDataSetChanged()
        if (::queueBtn.isInitialized) { val n = BgJobs.jobs.count { it.active }; queueBtn.text = if (n > 0) "📋$n" else "📋"; queueBtn.textSize = if (n > 0) 13f else 17f }
    }
    private fun dots(f: (View) -> Unit) = TextView(act).apply {
        text = "⋮"; textSize = 22f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
        setOnClickListener { f(this) }
    }
    private fun popup(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val pm = PopupMenu(act, anchor)
        items.forEachIndexed { i, it -> pm.menu.add(0, i, i, it.first) }
        pm.setOnMenuItemClickListener { m -> items[m.itemId].second(); true }
        pm.show()
    }

    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
    private var all: List<VideoItem> = emptyList()
    private var curFolder: String? = null
    private var mode = "scan"            // scan | perm | ready
    private var rows: List<Any> = emptyList()
    private var recMap: Map<String, Recent> = emptyMap()
    private var rootPos = 0
    private var query = ""
    private val searchEt = EditText(act).apply {
        hint = "🔍 ابحث باسم الفيديو"; textSize = 14f; setSingleLine(); setTextColor(th.text); setHintTextColor(th.muted)
        layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)); background = ui.box(th.card, th.border, 14)
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { val q = e?.toString()?.trim() ?: ""; if (q != query) { query = q; render() } }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
    }

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

    // ===== ريفريش بالسحب + NEW + إشعار + زرار استكمال =====
    private val knownFile = File(act.filesDir, "known_videos.txt")
    private val newFile = File(act.filesDir, "new_videos.txt")
    private var newKeys: MutableSet<String> = try { newFile.readLines().filter { it.isNotEmpty() }.toMutableSet() } catch (_: Exception) { mutableSetOf() }
    private var userRefresh = false
    private var refreshing = false
    private var spinner: ObjectAnimator? = null
    private val ORANGE = 0xFFFF9F1C.toInt()
    // ===== المجلد المخفي: إخفاء منطقي بالـ videoId (الملف نفسه ما بيتحركش) =====
    private val hiddenFile = File(act.filesDir, "hidden_videos.txt")
    private val hidden: MutableSet<String> = try { hiddenFile.readLines().filter { it.isNotEmpty() }.toMutableSet() } catch (_: Exception) { mutableSetOf() }
    private var inHidden = false
    private fun saveHidden() { try { hiddenFile.writeText(hidden.joinToString("\n")) } catch (_: Exception) {} }
    private fun isHidden(v: VideoItem) = v.videoId in hidden
    private fun visible(): List<VideoItem> = all.filter { !isHidden(it) }
    /** فتح المجلد المخفي بيعدّي على البوابة دي (المرحلة 3 هتحط فيها النمط/البصمة) */
    var gate: (open: () -> Unit) -> Unit = { it() }
    fun hideVideo(v: VideoItem) {
        hidden.add(v.videoId); saveHidden(); render()
        toastMsg("🙈 اتخفى — اسحب لتحت وكمّل السحب لحد 🔒 وسيب عشان تفتح المخفي")
    }
    fun unhideVideo(v: VideoItem) { hidden.remove(v.videoId); saveHidden(); render(); toastMsg("👁 رجع للقايمة") }
    fun openHidden() { inHidden = true; secure(true); if (query.isNotEmpty()) searchEt.setText(""); render(); listV.setSelection(0) }
    /** قفل المخفي تاني (لما التطبيق يقعد في الخلفية فترة) */
    fun relock() { if (inHidden) { inHidden = false; secure(false); render() } }
    /** وهو المخفي مفتوح: ممنوع لقطة الشاشة وصورة الأخيرة بتبقى سودا */
    private fun secure(on: Boolean) { try { if (on) act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) } catch (_: Exception) {} }
    private val emptyTv = ui.text("", 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(30), 0, ui.dp(30), 0); visibility = View.GONE }
    private fun keyOf(v: VideoItem) = v.folderKey + "|" + v.name + "|" + v.size

    private val ptr = TextView(act).apply {
        text = "🔄"; textSize = 20f; gravity = Gravity.CENTER; background = ui.box(th.surface, th.border, 22)
        elevation = ui.dp(6).toFloat(); alpha = 0f; translationY = -ui.dp(50).toFloat()
    }
    private val toastTv = TextView(act).apply {
        textSize = 13f; setTextColor(th.text); setPadding(ui.dp(16), ui.dp(9), ui.dp(16), ui.dp(9))
        background = ui.box(th.surface, ORANGE, 14); elevation = ui.dp(8).toFloat(); translationY = -ui.dp(100).toFloat()
    }
    private val resumeBtn = TextView(act).apply {
        text = "▶"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; elevation = ui.dp(8).toFloat(); visibility = View.GONE
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3B82F6.toInt()) }
    }
    private fun newBadge() = TextView(act).apply {
        text = "NEW"; textSize = 10f; setTextColor(0xFF111111.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(ui.dp(7), ui.dp(1), ui.dp(7), ui.dp(1)); background = ui.box(ORANGE, Color.TRANSPARENT, 10); visibility = View.GONE
    }
    private var toastRun: Runnable? = null
    private fun toastMsg(m: String) {
        toastTv.text = m; toastRun?.let { toastTv.removeCallbacks(it) }
        toastTv.animate().translationY(ui.dp(10).toFloat()).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        val r = Runnable { toastTv.animate().translationY(-ui.dp(100).toFloat()).setDuration(300).start() }
        toastRun = r; toastTv.postDelayed(r, 3200)
    }
    /** مقارنة القايمة القديمة المحفوظة بالجديدة: بيرجّع عدد الفيديوهات المضافة */
    private fun diff(list: List<VideoItem>): Int {
        val keys = list.map { keyOf(it) }
        var added = 0
        try {
            if (knownFile.exists()) {
                val old = knownFile.readLines().toHashSet()
                val add = keys.filter { it !in old }
                added = add.size
                if (added > 0) { newKeys.addAll(add) }
            }
            newKeys.retainAll(keys.toHashSet())
            knownFile.writeText(keys.joinToString("\n")); newFile.writeText(newKeys.joinToString("\n"))
        } catch (_: Exception) {}
        return added
    }
    private fun endPull() {
        spinner?.cancel(); spinner = null; refreshing = false; ptr.text = "🔄"
        listV.animate().translationY(0f).setDuration(250).start()
        ptr.animate().alpha(0f).translationY(-ui.dp(50).toFloat()).setDuration(250).start()
    }
    private fun dropAnim() {
        listV.post {
            for (i in 0 until listV.childCount) {
                val c = listV.getChildAt(i)
                c.alpha = 0f; c.translationY = -ui.dp(28).toFloat()
                c.animate().alpha(1f).translationY(0f).setStartDelay(i * 45L).setDuration(380).setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }
    private fun markPlayed(v: VideoItem) {
        if (newKeys.remove(keyOf(v))) try { newFile.writeText(newKeys.joinToString("\n")) } catch (_: Exception) {}
        Cfg.put("lib_last", v.uri); Cfg.put("lib_last_f:" + v.folderKey, v.uri)
    }
    private fun updateResume() {
        val cf = curFolder
        val target = if (cf == null) Cfg.str("lib_last") else Cfg.str("lib_last_f:$cf")
        val v = if (inHidden || target.isEmpty()) null else visible().firstOrNull { it.uri == target && (cf == null || it.folderKey == cf) }
        if (v == null) { resumeBtn.visibility = View.GONE; return }
        resumeBtn.visibility = View.VISIBLE
        resumeBtn.setOnClickListener { markPlayed(v); onPlay(v) }
    }

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
        queueBtn = hbtn("📋") { onQueue() }
        head.addView(queueBtn)
        head.addView(hbtn("🔗") { onLink() })
        head.addView(hbtn("📂") { onPick() })
        head.addView(hbtn("⚙️") { onSettings() })
        refreshBtn = hbtn("🔄") { refreshBtn.animate().rotationBy(360f).setDuration(600).start(); userRefresh = true; onRefresh() }
        head.addView(refreshBtn)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        val labels = VideoLib.sortLabels.map { it.second }
        val chips = ui.chips(labels, { VideoLib.sortLabelOf(sortKey()) }) { n -> Cfg.put("lib_sort", VideoLib.sortKeyOf(n)); render() }
        root.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(8), 0, ui.dp(8), 0); addView(chips) },
            LinearLayout.LayoutParams(-1, -2))

        root.addView(searchEt, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(2)) })

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
        body.addView(emptyTv, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        body.addView(ptr, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        body.addView(toastTv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        body.addView(resumeBtn, FrameLayout.LayoutParams(ui.dp(58), ui.dp(58), Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(ui.dp(20), 0, 0, ui.dp(24)) })
        // سحب لتحت من أول القايمة = ريفريش
        var y0 = 0f; var pulling = false; var pd = 0f; var wasLock = false
        val thr = ui.dp(60).toFloat(); val lockThr = ui.dp(125).toFloat()
        listV.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; pulling = false; wasLock = false; false }
                MotionEvent.ACTION_MOVE -> {
                    if (refreshing) return@setOnTouchListener false
                    val dy = e.rawY - y0
                    val atTop = listV.firstVisiblePosition == 0 && (listV.childCount == 0 || listV.getChildAt(0).top >= listV.paddingTop)
                    if (!pulling && dy > ui.dp(10) && atTop) {
                        pulling = true; y0 = e.rawY
                        val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; listV.onTouchEvent(c); c.recycle()
                    }
                    if (pulling) {
                        val cap = if (inHidden) ui.dp(95).toFloat() else ui.dp(150).toFloat()
                        pd = ((e.rawY - y0) * 0.5f).coerceIn(0f, cap)
                        listV.translationY = pd; ptr.translationY = pd - ui.dp(50)
                        ptr.alpha = (pd / ui.dp(40)).coerceIn(0f, 1f)
                        val lock = !inHidden && pd >= lockThr
                        if (lock != wasLock) {
                            wasLock = lock; ptr.text = if (lock) "🔒" else "🔄"
                            if (lock) ptr.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }
                        ptr.rotation = if (lock) 0f else pd * 4f
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pulling) {
                        pulling = false
                        if (wasLock && e.actionMasked == MotionEvent.ACTION_UP) {
                            wasLock = false; endPull(); gate { openHidden() }
                        } else if (pd >= thr && e.actionMasked == MotionEvent.ACTION_UP) {
                            refreshing = true; userRefresh = true
                            listV.animate().translationY(ui.dp(56).toFloat()).setDuration(150).start()
                            ptr.animate().translationY(ui.dp(6).toFloat()).alpha(1f).setDuration(150).start()
                            spinner = ObjectAnimator.ofFloat(ptr, "rotation", ptr.rotation, ptr.rotation + 360f).apply {
                                duration = 700; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); start()
                            }
                            onPull()
                            ptr.postDelayed({ if (refreshing) { userRefresh = false; endPull() } }, 20000)
                        } else { wasLock = false; endPull() }
                        true
                    } else false
                }
                else -> false
            }
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    // ===== الحالات =====
    fun showScanning() { mode = "scan"; render() }
    fun showNoPermission() { mode = "perm"; render() }
    fun showVideos(list: List<VideoItem>) {
        val added = diff(list)
        all = list; mode = "ready"; render()
        if (userRefresh) {
            userRefresh = false; endPull(); dropAnim()
            toastMsg(if (added > 0) "✨ تم إضافة $added فيديو جديد" else "✅ مفيش فيديوهات جديدة")
        } else if (added > 0) { toastMsg("✨ تم إضافة $added فيديو جديد"); dropAnim() }
    }
    val hasData: Boolean get() = mode == "ready" && all.isNotEmpty()

    /** رجوع من فولدر للقايمة الرئيسية. بترجّع true لو استهلكت الضغطة */
    fun back(): Boolean {
        if (query.isNotEmpty()) { searchEt.setText(""); return true }
        if (inHidden) { inHidden = false; secure(false); render(); listV.setSelection(rootPos); return true }
        if (curFolder == null) return false
        curFolder = null; render(); listV.setSelection(rootPos); return true
    }

    fun render() {
        resumeBtn.visibility = View.GONE
        recMap = try { recents() } catch (_: Exception) { emptyMap() }
        val sort = sortKey()
        when (mode) {
            "scan" -> { setState(true, "⏳ جاري فحص التخزين…", null); return }
            "perm" -> { setState(false, "محتاج إذن الوصول للفيديوهات عشان أعرض فولدرات الجهاز.", "السماح بالوصول"); return }
        }
        if (all.isEmpty()) { emptyTv.visibility = View.GONE; setState(false, "مفيش فيديوهات اتلاقت على الجهاز.\nلو فيديوهاتك موجودة دوس 🔄 للفحص من جديد، أو افتح فيديو من 📂.", null); return }
        val src = if (inHidden) all.filter { isHidden(it) } else visible()
        val cf = curFolder
        val folders = VideoLib.group(src)
        val folder = if (!inHidden && cf != null) folders.firstOrNull { it.key == cf } else null
        if (!inHidden && cf != null && folder == null) curFolder = null
        if (query.isNotEmpty()) {
            val hits = VideoLib.search(src, query)
            rows = hits
            titleTv.text = "🔍 نتائج البحث"; subTv.text = "${hits.size} فيديو"; backV.visibility = if (inHidden) View.VISIBLE else View.GONE
        } else if (inHidden) {
            rows = VideoLib.sortVideos(src, sort)
            titleTv.text = "🙈 المخفي"; subTv.text = "${src.size} فيديو · ${VideoLib.fmtSize(src.sumOf { it.size })}"; backV.visibility = View.VISIBLE
        } else if (folder != null) {
            rows = VideoLib.sortVideos(folder.videos, sort)
            titleTv.text = "📂 " + folder.name; subTv.text = "${folder.videos.size} فيديو · ${VideoLib.fmtSize(folder.totalSize)}"; backV.visibility = View.VISIBLE
        } else {
            rows = (if (BgJobs.jobs.isNotEmpty()) listOf<Any>(bgFolder) else emptyList<Any>()) + VideoLib.sortFolders(folders, sort)
            titleTv.text = "📁 الفيديوهات"; subTv.text = "${folders.size} مجلد · ${src.size} فيديو"; backV.visibility = View.GONE
        }
        emptyTv.text = if (rows.isNotEmpty()) "" else if (inHidden && query.isEmpty()) "مفيش فيديوهات مخفية.\nدوس ⋮ أو اضغط ضغطة مطولة على أي فيديو واختار 🙈 إخفاء الفيديو." else if (query.isEmpty()) "كل الفيديوهات مخفية.\nاسحب لتحت وكمّل لحد 🔒 وسيب عشان تفتح المخفي." else "مفيش نتائج."
        emptyTv.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        stateBox.visibility = View.GONE; listV.visibility = View.VISIBLE
        (listV.adapter as BaseAdapter).notifyDataSetChanged()
        updateResume()
    }

    private fun setState(busy: Boolean, msg: String, btn: String?) {
        titleTv.text = "📁 الفيديوهات"; subTv.text = ""; backV.visibility = View.GONE
        emptyTv.visibility = View.GONE; listV.visibility = View.GONE; stateBox.visibility = View.VISIBLE
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
        val fBadge = newBadge()
        val nameRow = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; addView(name, LinearLayout.LayoutParams(-2, -2, 1f)); addView(fBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) }) }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(nameRow); addView(info); addView(path) }
        val chev = TextView(act).apply { text = "‹"; textSize = 26f; setTextColor(th.muted); gravity = Gravity.CENTER; setPadding(ui.dp(8), 0, ui.dp(4), 0) }
        val fDots = dots { }
        val card = LinearLayout(act).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(10), ui.dp(8), ui.dp(10)); background = ui.box(th.card, th.border, 14)
            addView(icon, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12) })
            addView(fDots)
            addView(chev)
        }
        val wrap = FrameLayout(act).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)); addView(card, FrameLayout.LayoutParams(-1, -2)); tag = arrayOf(name, info, path, card, fBadge, fDots) }
        return wrap
    }

    @Suppress("UNCHECKED_CAST")
    private fun bindFolder(v: View, f: FolderItem) {
        val t = v.tag as Array<View>
        if (f === bgFolder) {
            val act = BgJobs.jobs.count { it.active }; val run = BgJobs.jobs.firstOrNull { it.state == "running" }
            (t[0] as TextView).text = "🌙 مجلد الترجمة في الخلفية"
            (t[1] as TextView).text = "$act في الطابور" + (if (run != null) " · بيترجم: ${run.title} ${run.pct}%" else "")
            (t[2] as TextView).text = "اللي فوق بيترجم الأول · رتّبهم بـ ⬆ ⬇"
            (t[4] as TextView).visibility = View.GONE
            t[5].visibility = View.GONE
            t[3].setOnClickListener { onQueue() }
            return
        }
        t[5].visibility = View.VISIBLE
        (t[0] as TextView).text = f.name
        (t[1] as TextView).text = "${f.count} فيديو · ${VideoLib.fmtSize(f.totalSize)}"
        (t[2] as TextView).text = f.path
        val nn = f.videos.count { keyOf(it) in newKeys }
        (t[4] as TextView).apply { text = if (nn > 1) "NEW $nn" else "NEW"; visibility = if (nn > 0) View.VISIBLE else View.GONE }
        t[3].setOnClickListener { rootPos = listV.firstVisiblePosition; curFolder = f.key; render(); listV.setSelection(0) }
        t[5].setOnClickListener { v -> popup(v, listOf("🌙 ترجمة كل فيديوهات الفولدر في الخلفية (${f.videos.size})" to { onBgFolder(f) })) }
    }
    private val FolderItem.count: Int get() = videos.size
    private val bgFolder = FolderItem("__bg__", "مجلد الترجمة في الخلفية", "", emptyList())

    // ===== صف الفيديو =====
    private class VH(val iv: ImageView, val dur: TextView, val title: TextView, val meta: TextView, val state: TextView, val card: View, val badge: TextView, val fill: View, val rest: View, val tick: TextView, val dots: TextView)

    private fun newVideoRow(): View {
        val ph = TextView(act).apply { text = "🎞"; textSize = 24f; gravity = Gravity.CENTER; alpha = 0.45f }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val dur = TextView(act).apply {
            textSize = 10f; setTextColor(Color.WHITE); setPadding(ui.dp(5), ui.dp(1), ui.dp(5), ui.dp(1)); background = ui.box(0xCC000000.toInt(), Color.TRANSPARENT, 4)
        }
        val tick = TextView(act).apply { text = "✓"; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF3B82F6.toInt()) }; visibility = View.GONE }
        val thumb = FrameLayout(act).apply {
            background = ui.box(0xFF000000.toInt(), th.border, 10); clipToOutline = true
            addView(ph, FrameLayout.LayoutParams(-1, -1)); addView(iv, FrameLayout.LayoutParams(-1, -1))
            addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            addView(tick, FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(4), ui.dp(4), 0, 0) })
        }
        val title = ui.text("", 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val meta = ui.text("", 11f, th.muted).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val state = ui.text("", 11f, th.primary).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        val vBadge = newBadge()
        val titleRow = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; addView(title, LinearLayout.LayoutParams(0, -2, 1f)); addView(vBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) }) }
        val fill = View(act).apply { setBackgroundColor(th.primary) }
        val rest = View(act)
        val bar = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; background = ui.box(th.border, Color.TRANSPARENT, 2); clipToOutline = true
            addView(fill, LinearLayout.LayoutParams(0, -1, 0f)); addView(rest, LinearLayout.LayoutParams(0, -1, 1f)); visibility = View.GONE }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; addView(titleRow); addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(3) }); addView(state, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(2) })
            addView(bar, LinearLayout.LayoutParams(-1, ui.dp(3)).apply { topMargin = ui.dp(5) }) }
        val vDots = dots { }
        val card = LinearLayout(act).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(8), ui.dp(4), ui.dp(8)); background = ui.box(th.card, th.border, 12)
            addView(thumb, LinearLayout.LayoutParams(ui.dp(128), ui.dp(72)))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
            addView(vDots)
        }
        return FrameLayout(act).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)); addView(card, FrameLayout.LayoutParams(-1, -2)); tag = VH(iv, dur, title, meta, state, card, vBadge, fill, rest, tick, vDots) }
    }

    /** قايمة الفيديو: بتظهر من ⋮ ومن الضغطة المطولة على الفيديو */
    private fun videoMenu(anchor: View, vi: VideoItem) {
        val bj = bgJob(vi); val running = bj != null && bj.active
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += (if (running) "⏹ إيقاف الترجمة في الخلفية" else "🌙 نقل لمجلد الترجمة في الخلفية") to { if (running) onBgStop(vi) else onBg(vi) }
        items += "▶ تشغيل" to { markPlayed(vi); onPlay(vi) }
        if (inHidden) items += "👁 إظهار الفيديو (يرجع للقايمة)" to { unhideVideo(vi) }
        else {
            items += "🙈 إخفاء الفيديو" to { hideVideo(vi) }
            items += "✏ إعادة تسمية" to { onRename(vi) }
            items += "📁 نقل" to { onMove(vi) }
        }
        items += "ℹ تفاصيل" to { onDetails(vi) }
        items += "📤 مشاركة" to { onShare(vi) }
        items += "🗑 حذف" to { onDelete(vi) }
        popup(anchor, items)
    }

    private fun bindVideo(v: View, vi: VideoItem) {
        val h = v.tag as VH
        h.title.text = vi.title
        h.badge.visibility = if (keyOf(vi) in newKeys) View.VISIBLE else View.GONE
        h.dur.text = VideoLib.fmtDur(vi.durMs); h.dur.visibility = if (vi.durMs > 0) View.VISIBLE else View.GONE
        h.meta.text = listOf(if (inHidden) vi.folderName else "", VideoLib.fmtSize(vi.size), vi.ext.uppercase(), VideoLib.fmtDate(vi.dateMs)).filter { it.isNotEmpty() }.joinToString(" · ")
        val r = recMap[vi.videoId]
        val parts = ArrayList<String>()
        if (r != null && r.posSec > 5) parts.add("▶ وقف عند " + PlayerLogic.clock((r.posSec * 1000).toLong()))
        if (r != null && r.subs > 0) parts.add("✓ مترجم ${r.percent}%")
        val d = if (r != null && r.durSec > 0) r.durSec else vi.durMs / 1000.0
        val frac = if (r != null && d > 0) (r.posSec / d).coerceIn(0.0, 1.0) else 0.0
        val bar = h.fill.parent as View
        bar.visibility = if (frac > 0.01) View.VISIBLE else View.GONE
        (h.fill.layoutParams as LinearLayout.LayoutParams).weight = frac.toFloat(); (h.rest.layoutParams as LinearLayout.LayoutParams).weight = (1.0 - frac).toFloat(); bar.requestLayout()
        h.tick.visibility = if (r != null && r.percent >= 97) View.VISIBLE else View.GONE
        val bj = bgJob(vi)
        if (bj != null && bj.active) {
            parts.add(if (bj.state == "queued") "⏳ في طابور الترجمة بالخلفية" else "🌙 بيترجم في الخلفية ${bj.pct}%" + (BgJobs.fmtRemain(bj.remainSec).let { if (it.isEmpty()) "" else " · باقي $it" }))
        } else if (bj != null && bj.state == "failed") parts.add("⚠ ترجمة الخلفية وقفت: " + bj.err)
        h.state.text = parts.joinToString(" · "); h.state.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        h.dots.setOnClickListener { v -> videoMenu(v, vi) }
        h.card.setOnLongClickListener { c -> c.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); videoMenu(h.dots, vi); true }
        h.iv.tag = vi.uri; h.iv.setImageDrawable(null)
        val c = Thumbs.peek(vi)
        if (c != null) h.iv.setImageBitmap(c)
        else Thumbs.load(act, vi) { b -> if (b != null && h.iv.tag == vi.uri) h.iv.setImageBitmap(b) }
        h.card.setOnClickListener { markPlayed(vi); onPlay(vi) }
    }
}
