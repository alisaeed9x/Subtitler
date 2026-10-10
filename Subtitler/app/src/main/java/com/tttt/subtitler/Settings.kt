package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*

/** مرجع للمحرك الشغّال حاليًا (بيستخدمه تبويب الشخصيات عشان يستورد المكتشفة تلقائيًا) */
object Live { @Volatile var engine: Engine? = null }

class TabDef(val id: String, val label: String, val content: List<View>, val preview: Boolean = false, val sub: String = "", val group: String = "")

/** إعدادات على طريقة MX Player: قايمة أقسام (أيقونة · عنوان · وصف) ← دوس على قسم يفتح شاشته ← سهم الرجوع يرجّعك للقايمة. preview = معاينة الترجمة بتظهر فوق المحتوى في الأقسام اللي preview=true */
class TabbedDialog(val act: Activity, val ui: Ui, val title: String, val tabs: List<TabDef>, val preview: View?, onClose: () -> Unit) {
    val dialog = GDialog(act)
    private val listPage = LinearLayout(act)
    private val bodies = ArrayList<LinearLayout>()
    private val previewBox = FrameLayout(act)
    private val titleTv: TextView
    private val backTv: TextView
    private var cur = -1

    private fun iconOf(l: String): String { val i = l.indexOf(' '); return if (i in 1..4) l.substring(0, i) else "⚙️" }
    private fun nameOf(l: String): String { val i = l.indexOf(' '); return if (i in 1..4) l.substring(i + 1) else l }

    init {
        val th = ui.th
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dm = act.resources.displayMetrics
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12)); background = ui.box(th.card, th.border, 18)
        }
        val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, 0, 0, ui.dp(6)) }
        backTv = IconTextView(act).apply {
            text = "→"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(th.primary); visibility = View.GONE
            setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(4)); background = ui.box(th.surface, th.border, 20)
            Glass.pressable(this)
            setOnClickListener { back() }
        }
        head.addView(backTv, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
        titleTv = ui.text(title, 17f, th.primary, true)
        head.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(IconTextView(act).apply { text = "✕"; textSize = 18f; setTextColor(th.muted); setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)); setOnClickListener { dialog.dismiss() } })
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        if (preview != null) { previewBox.addView(preview); previewBox.visibility = View.GONE; root.addView(previewBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) }) }

        // صفحة القايمة الرئيسية
        listPage.apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        var lastGroup = ""
        tabs.forEachIndexed { i, t ->
            // (v198) عنوان قسم لما المجموعة تتغيّر: عام (الاتنين) · مشغّل الفيديو · مشغّل الموسيقى
            if (t.group.isNotBlank() && t.group != lastGroup) {
                lastGroup = t.group
                listPage.addView(ui.text(t.group, 13f, th.muted, true).apply { setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(2)) }, LinearLayout.LayoutParams(-1, -2))
            }
            val row = LinearLayout(act).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); background = ui.box(th.surface, th.border, 12)
                Glass.pressable(this)
                setOnClickListener { select(i) }
            }
            row.addView(IconTextView(act).apply { text = iconOf(t.label); textSize = 22f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ui.dp(40), -2))
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            col.addView(ui.text(nameOf(t.label), 15f, th.text, true))
            if (t.sub.isNotBlank()) col.addView(ui.text(t.sub, 12f, th.muted))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(IconTextView(act).apply { text = "‹"; textSize = 22f; setTextColor(th.muted) })
            listPage.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) })
        }

        val frame = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        frame.addView(listPage, LinearLayout.LayoutParams(-1, -2))
        tabs.forEach { t ->
            val b = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE }
            t.content.forEach { b.addView(it) }
            bodies.add(b); frame.addView(b, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(MaxHeightScroll(act, (dm.heightPixels * 0.66f).toInt()).apply { addView(frame) }, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setLayout((dm.widthPixels * 0.95f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        // رجوع (زرار أو سويب): لو جوه قسم يرجع للقايمة، لو في القايمة يقفل الإعدادات
        dialog.onBack = { if (cur >= 0) { back(); true } else false }
        dialog.setOnDismissListener { onClose() }
        showList()
    }

    private fun swapIn(v: View, fromLeft: Boolean) {
        try {
            v.animate().cancel()
            v.alpha = 0f; v.translationX = (if (fromLeft) -1f else 1f) * ui.dp(28).toFloat()
            v.animate().alpha(1f).translationX(0f).setDuration(230).setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
        } catch (_: Exception) {}
    }

    private fun showList() {
        cur = -1
        listPage.visibility = View.VISIBLE
        swapIn(listPage, false)
        bodies.forEach { it.visibility = View.GONE }
        previewBox.visibility = View.GONE
        backTv.visibility = View.GONE
        titleTv.text = title
    }

    fun back() { if (cur >= 0) showList() else dialog.dismiss() }

    fun select(i: Int) {
        if (i !in tabs.indices) return
        cur = i
        listPage.visibility = View.GONE
        bodies.forEachIndexed { k, b -> b.visibility = if (k == i) View.VISIBLE else View.GONE }
        previewBox.visibility = if (preview != null && tabs[i].preview) View.VISIBLE else View.GONE
        backTv.visibility = View.VISIBLE
        swapIn(bodies[i], true)
        titleTv.text = tabs[i].label
    }

    fun show(tabId: String? = null) {
        if (tabId == null) showList() else tabs.indexOfFirst { it.id == tabId }.takeIf { it >= 0 }?.let { select(it) } ?: showList()
        dialog.show()
    }
}

