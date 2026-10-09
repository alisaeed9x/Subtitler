package com.tttt.subtitler

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/** (v165) عنصر واحد في ويدجت المهام: ترجمة خلفية أو مهمة (قص / صوت / GIF / ضغط / تحميل / ترجمة ثابتة) */
class WItem(val head: String, val title: String, val pct: Int, val info: String, val indeterminate: Boolean)

/**
 * (v165) ويدجتس الشاشة الرئيسية — ويدجتين:
 *  1) LiveWidget: ضغطة تشغّل الترجمة الحية (طلب الإذن ← الشريط العايم)، ولو شغّالة الضغطة بتوقفها.
 *  2) TasksWidget: بيعرض ترجمة الخلفية + مهام الأدوات مع النسبة والوقت المتبقي، وأزرار ▶ ◀ للتبديل بين المهام.
 * التحديث بيتم من نفس العملية (BgJobs.notifyChange / TaskCenter.changed / LiveCaptionService) بتأخير أدنى 2ث عشان البطارية.
 */
object WidgetHub {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    @Volatile private var pending = false
    private var lastRun = 0L
    private const val MIN_GAP = 2000L

    private class Sample(val t0: Long, val p0: Int)
    private val samples = HashMap<Int, Sample>()

    private fun rid(c: Context, type: String, name: String): Int = c.resources.getIdentifier(name, type, c.packageName)

    /** بيتنادى من أي thread. c ممكن يبقى null لو الـ context معروف من قبل */
    fun poke(c: Context? = null) {
        if (c != null) app = c.applicationContext
        val a = app ?: return
        if (pending) return
        pending = true
        val wait = (MIN_GAP - (SystemClock.uptimeMillis() - lastRun)).coerceAtLeast(0L)
        main.postDelayed({
            pending = false
            lastRun = SystemClock.uptimeMillis()
            try { updateAll(a) } catch (e: Throwable) { LogStore.err("Widgets:poke", e) }
        }, wait)
    }

    /** تحديث فوري على الـ main thread (من الـ providers) */
    fun updateNow(c: Context) {
        app = c.applicationContext
        try { updateAll(c.applicationContext) } catch (e: Throwable) { LogStore.err("Widgets:now", e) }
    }

    private fun updateAll(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        val tIds = mgr.getAppWidgetIds(ComponentName(c, TasksWidget::class.java))
        if (tIds.isNotEmpty()) renderTasks(c, mgr, tIds)
        val lIds = mgr.getAppWidgetIds(ComponentName(c, LiveWidget::class.java))
        if (lIds.isNotEmpty()) renderLive(c, mgr, lIds)
    }

    // ===== المهام =====
    private fun taskEta(t: TaskItem, now: Long): Long {
        if (t.state != 1 || t.paused) { samples.remove(t.id); return -1 }
        val s = samples.getOrPut(t.id) { Sample(now, t.pct) }
        val dp = t.pct - s.p0
        val el = (now - s.t0) / 1000
        if (dp < 2 || el < 6) return -1
        return (el * (100 - t.pct) / dp).coerceAtLeast(0L)
    }

    private fun infoOf(pct: Int, etaSec: Long, waiting: Boolean, paused: Boolean, indeterminate: Boolean): String {
        if (paused) return "$pct% · متوقفة مؤقتًا"
        if (waiting) return "في الانتظار"
        if (indeterminate) return "بيجهّز…"
        val rem = BgJobs.fmtRemain(etaSec)
        return if (rem.isEmpty()) "$pct%" else "$pct% · باقي $rem"
    }

    fun items(): List<WItem> {
        val now = System.currentTimeMillis()
        val l = ArrayList<WItem>()
        for (j in BgJobs.jobs) {
            if (!j.active) continue
            val running = j.state == "running"
            val head = when { j.paused -> "⏸ ترجمة متوقفة مؤقتًا"; running -> "🌙 ترجمة في الخلفية"; else -> "⏳ ترجمة مستنية" }
            val eta = if (running && !j.paused) j.remainSec else -1L
            l.add(WItem(head, j.title, j.pct, infoOf(j.pct, eta, !running, j.paused, running && j.dur <= 0 && j.pct == 0), false))
        }
        val live = HashSet<Int>()
        for (t in TaskCenter.items) {
            if (t.state != 0 && t.state != 1 && t.state != 6) continue
            live.add(t.id)
            val paused = t.state == 6 || t.paused
            val head = when { paused -> "⏸ مهمة واقفة مؤقتًا"; t.state == 1 -> "⚙ مهمة شغّالة"; else -> "⏳ مهمة مستنية" }
            val eta = taskEta(t, now)
            l.add(WItem(head, t.title, t.pct, infoOf(t.pct, eta, t.state == 0, paused, t.state == 1 && t.pct == 0 && t.msg.isBlank()), false))
        }
        samples.keys.retainAll(live)
        return l
    }

    private fun prefs(c: Context) = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    /** ▶ ◀ : بيبدّل بين المهام (بيلف من الأول لو وصل للآخر) */
    fun step(c: Context, d: Int) {
        val n = items().size
        if (n > 0) {
            val cur = prefs(c).getInt("tidx", 0).coerceIn(0, n - 1)
            prefs(c).edit().putInt("tidx", ((cur + d) % n + n) % n).apply()
        }
        updateNow(c)
    }

    private fun bc(c: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(c, code, Intent(c, TasksWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun renderTasks(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        val list = items()
        val rv = RemoteViews(c.packageName, rid(c, "layout", "widget_tasks"))
        val iHead = rid(c, "id", "w_head"); val iTitle = rid(c, "id", "w_title"); val iBar = rid(c, "id", "w_bar"); val iInfo = rid(c, "id", "w_info")
        val iPrev = rid(c, "id", "w_prev"); val iNext = rid(c, "id", "w_next"); val iBody = rid(c, "id", "w_body")
        if (list.isEmpty()) {
            rv.setTextViewText(iHead, "📋 المهام")
            rv.setTextViewText(iTitle, "مفيش مهام شغّالة دلوقتي")
            rv.setTextViewText(iInfo, "")
            rv.setViewVisibility(iBar, View.GONE)
            rv.setViewVisibility(iPrev, View.INVISIBLE)
            rv.setViewVisibility(iNext, View.INVISIBLE)
        } else {
            val idx = prefs(c).getInt("tidx", 0).coerceIn(0, list.size - 1)
            val cur = list[idx]
            rv.setTextViewText(iHead, "${idx + 1}/${list.size} · ${cur.head}")
            rv.setTextViewText(iTitle, cur.title)
            rv.setTextViewText(iInfo, cur.info)
            rv.setViewVisibility(iBar, View.VISIBLE)
            rv.setProgressBar(iBar, 100, cur.pct.coerceIn(0, 100), cur.indeterminate)
            val nav = if (list.size > 1) View.VISIBLE else View.INVISIBLE
            rv.setViewVisibility(iPrev, nav)
            rv.setViewVisibility(iNext, nav)
        }
        rv.setOnClickPendingIntent(iPrev, bc(c, TasksWidget.PREV, 21))
        rv.setOnClickPendingIntent(iNext, bc(c, TasksWidget.NEXT, 22))
        val open = PendingIntent.getActivity(c, 23,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        rv.setOnClickPendingIntent(iBody, open)
        mgr.updateAppWidget(ids, rv)
    }

    // ===== الترجمة الحية =====
    private fun renderLive(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        val on = LiveCaptionService.running
        val rv = RemoteViews(c.packageName, rid(c, "layout", "widget_live"))
        val iRoot = rid(c, "id", "w_l_root")
        rv.setTextViewText(rid(c, "id", "w_l_title"), if (on) "🔴 الترجمة الحية شغّالة" else "🔴 ترجمة حية")
        rv.setTextViewText(rid(c, "id", "w_l_sub"), if (on) "دوس لإيقافها" else "دوس لتشغيلها فوق أي تطبيق")
        rv.setInt(iRoot, "setBackgroundResource", rid(c, "drawable", if (on) "widget_bg_on" else "widget_bg"))
        val pi = if (on)
            PendingIntent.getService(c, 11, Intent(c, LiveCaptionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        else
            PendingIntent.getActivity(c, 12, Intent(c, LiveRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        rv.setOnClickPendingIntent(iRoot, pi)
        mgr.updateAppWidget(ids, rv)
    }
}

/** ويدجت المهام (ترجمة الخلفية + الأدوات) */
class TasksWidget : AppWidgetProvider() {
    companion object {
        const val NEXT = "com.tttt.subtitler.WIDGET_NEXT"
        const val PREV = "com.tttt.subtitler.WIDGET_PREV"
    }
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) { WidgetHub.updateNow(context) }
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NEXT -> WidgetHub.step(context, 1)
            PREV -> WidgetHub.step(context, -1)
            else -> super.onReceive(context, intent)
        }
    }
}

/** ويدجت الترجمة الحية */
class LiveWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) { WidgetHub.updateNow(context) }
}
