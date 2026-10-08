package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * (v139) اختيار فيديو من قايمة التطبيق نفسه (نفس فحص الجهاز بتاع الصفحة الرئيسية) بدل مدير الملفات.
 * فيه بحث، والأحدث أولًا، وزرار احتياطي لمدير الملفات لو الفيديو مش ظاهر.
 */
object VideoPicker {
    private const val MAX_ROWS = 120

    fun show(act: Activity, title: String, onPick: (VideoItem) -> Unit, onFiles: () -> Unit) {
        val th = Themes.byId(Cfg.str("theme", "mx")); val ui = Ui(act, th)
        if (!VideoScan.hasPermission(act)) {
            Notice.show(act, "محتاج إذن الفيديوهات الأول — اسمح بيه وبعدين ادوس الأداة تاني", 3600L)
            try { act.requestPermissions(arrayOf(VideoScan.permission()), 11) } catch (_: Throwable) {}
            return
        }
        val d = GDialog(act)
        var all: List<VideoItem> = VideoScan.cache.orEmpty()
        var q = ""
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val info = ui.text("", 12f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        var scanning = all.isEmpty()

        fun row(v: VideoItem): View {
            val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1B1B1F.toInt()); tag = v.uri }
            val c = Thumbs.peek(v)
            if (c != null) iv.setImageBitmap(c) else Thumbs.load(act, v) { b -> if (b != null && iv.tag == v.uri) iv.setImageBitmap(b) }
            val txt = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            txt.addView(ui.text(v.title, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val meta = listOf(v.folderName, if (v.durMs > 0) VideoLib.fmtDur(v.durMs) else "", VideoLib.fmtSize(v.size)).filter { it.isNotEmpty() }.joinToString(" · ")
            txt.addView(ui.text(meta, 11f, th.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); background = ui.box(th.surface, th.border, 12)
            }
            r.addView(iv, LinearLayout.LayoutParams(ui.dp(96), ui.dp(54)))
            r.addView(txt, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(10) })
            Glass.pressable(r)
            r.setOnClickListener { d.dismiss(); onPick(v) }
            return r
        }

        fun render() {
            list.removeAllViews()
            if (all.isEmpty()) {
                info.text = if (scanning) "⏳ بدوّر على الفيديوهات…" else "مفيش فيديوهات اتلقت — جرّب «من مدير الملفات»"
                return
            }
            val src = if (q.isBlank()) VideoLib.sortVideos(all, VideoLib.SORT_NEW) else VideoLib.search(all, q)
            info.text = if (src.isEmpty()) "مفيش نتايج للبحث ده"
                else "${src.size} فيديو" + (if (src.size > MAX_ROWS) " — ظاهر أول $MAX_ROWS، اكتب في البحث عشان تلاقي الباقي" else "")
            for (v in src.take(MAX_ROWS)) list.addView(row(v), LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
        }

        val search = ui.input("ابحث باسم الفيديو أو الفولدر", "")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { q = s?.toString()?.trim() ?: ""; render() }
        })

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(12)); background = ui.box(th.card, th.border, 18)
        }
        box.addView(ui.text(title, 17f, th.primary, true))
        box.addView(search)
        box.addView(info)
        box.addView(ScrollView(act).apply { addView(list); overScrollMode = View.OVER_SCROLL_NEVER }, LinearLayout.LayoutParams(-1, 0, 1f))
        val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        btns.addView(ui.button("📂 من مدير الملفات") { d.dismiss(); onFiles() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        btns.addView(ui.button("إغلاق") { d.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(4) })
        box.addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })

        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        render()
        d.show()
        d.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.94f).toInt(), (act.resources.displayMetrics.heightPixels * 0.85f).toInt())

        // مفيش نتيجة فحص محفوظة في الذاكرة: افحص التخزين في الخلفية وحدّث القايمة
        if (scanning) Thread {
            val res = try { VideoScan.scan(act) } catch (_: Throwable) { emptyList<VideoItem>() }
            act.runOnUiThread { scanning = false; if (!act.isDestroyed && !act.isFinishing && d.isShowing) { all = res; render() } }
        }.apply { isDaemon = true }.start()
    }
}
