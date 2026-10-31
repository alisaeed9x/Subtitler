package com.tttt.subtitler

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.widget.RemoteViews
import java.util.Calendar
import java.util.Locale

// (v665) ويدجت مواقيت الصلاة بتصميم جديد: خلفية صورة مسجد حسب الصلاة الحالية + هيدر + كارت «الصلاة القادمة والمتبقي» + شريط الخمس صلوات.
// بيترسم كله كصورة (Canvas) بمقاس الويدجت الفعلي، وبيتكيّف مع ثيم النظام فاتح/داكن. البيانات حقيقية من PrayerTimes (API + كاش).
class PrayerWidget : AppWidgetProvider() {
    companion object {
        const val ACT_REFRESH = "com.tttt.joyboy.PRAYER_REFRESH"
        const val ACT_ALARM = "com.tttt.joyboy.PRAYER_ALARM"

        fun renderAll(c: Context, mgr: AppWidgetManager, ids: IntArray, force: Boolean = false, done: (() -> Unit)? = null) {
            if (ids.isEmpty()) { done?.invoke(); return }
            PrayerTimes.loadData(c, force) { data ->
                val app = c.applicationContext
                for (id in ids) {
                    try {
                        renderOne(app, mgr, id, data)
                    } catch (e: Throwable) {
                        LogStore.err("PrayerWidget:render", e)
                    }
                }
                done?.invoke()
            }
        }

        private fun sizeOf(c: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            return try {
                val o = mgr.getAppWidgetOptions(id)
                val land = c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val w = if (land) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                val h = if (land) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                (if (w > 0) w else 250) to (if (h > 0) h else 120)
            } catch (_: Throwable) {
                250 to 120
            }
        }

        private fun bc(c: Context, action: String, code: Int): PendingIntent {
            return PendingIntent.getBroadcast(c, code, Intent(c, PrayerWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun renderOne(c: Context, mgr: AppWidgetManager, id: Int, data: PrayerTimes.Data?) {
            val rv = RemoteViews(c.packageName, WidgetHub.rid(c, "layout", "widget_prayer_card"))
            val root = WidgetHub.rid(c, "id", "w_p_root")
            rv.setOnClickPendingIntent(root, bc(c, ACT_REFRESH, 41))
            rv.setInt(root, "setBackgroundColor", 0)
            val (w, h) = sizeOf(c, mgr, id)
            val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val geo = PrayerCard.Geo()
            val bmp = PrayerCard.render(c, w, h, data, dark, System.currentTimeMillis(), geo)
            rv.setImageViewBitmap(WidgetHub.rid(c, "id", "w_p_img"), bmp)
            // (v679) عدّاد الصلاة القادمة: ساعات:دقايق:ثواني بيعدّه النظام كل ثانية (Chronometer تنازلي) فوق الكارت؛ الثواني بلون مختلف
            val cdWrap = WidgetHub.rid(c, "id", "w_p_cd_wrap")
            if (geo.ok) {
                val den = c.resources.displayMetrics.density
                val hrs = geo.leftMs / 3_600_000L
                val chars = if (hrs >= 10L) 8 else if (hrs >= 1L) 7 else 5
                val textW = chars * 10.8f                      // monospace 18dp ← حرف = 10.8dp
                val sx = w.toFloat() / geo.wDp; val sy = h.toFloat() / geo.hDp   // الكارت بيتمدّد fitXY على مقاس الويدجت الفعلي
                val left = ((geo.cx + textW / 2f - 88f) * sx).coerceAtLeast(0f)
                val top = (geo.top * sy).coerceAtLeast(0f)
                rv.setViewVisibility(cdWrap, android.view.View.VISIBLE)
                rv.setViewPadding(cdWrap, (left * den).toInt(), (top * den).toInt(), 0, 0)
                val base = SystemClock.elapsedRealtime() + geo.leftMs
                for (nm in arrayOf("w_p_cd_a", "w_p_cd_b")) {
                    val cid = WidgetHub.rid(c, "id", nm)
                    try { rv.setChronometerCountDown(cid, true) } catch (_: Throwable) {}
                    rv.setChronometer(cid, base, "%s", true)
                }
            } else rv.setViewVisibility(cdWrap, android.view.View.GONE)
            // (v666) مؤشر «الودجت شغّالة ولا فاصلة»: عدّاد ثواني بيعدّه النظام نفسه (مش تطبيقنا) من آخر تحديث. شغّالة = بيرجع لـ 00:00 كل دقيقة تقريبًا؛ لو عدّى دقيقتين ← التحديث واقف (دوس على الودجت)
            val age = WidgetHub.rid(c, "id", "w_p_age")
            rv.setViewVisibility(age, if (h >= 150) android.view.View.VISIBLE else android.view.View.GONE)
            rv.setChronometer(age, SystemClock.elapsedRealtime(), "آخر تحديث %s", true)
            rv.setOnClickPendingIntent(age, bc(c, ACT_REFRESH, 41))
            mgr.updateAppWidget(id, rv)
        }

        fun setAlarm(c: Context, on: Boolean) {
            try {
                val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(c, 42, Intent(c, PrayerWidget::class.java).setAction(ACT_ALARM), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                if (on) {
                    am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 60000L, 60000L, pi)
                } else {
                    am.cancel(pi)
                }
            } catch (e: Throwable) {
                LogStore.err("PrayerWidget:alarm", e)
            }
        }
    }

    // (v662) الرسم بيتم في خيط خلفي (شبكة/كاش) — من غير goAsync النظام كان بيقتل الـreceiver قبل ما الودجت يترسم
    private fun renderAsync(context: Context, ids: IntArray, force: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(context)
        val pr = try { goAsync() } catch (_: Throwable) { null }
        renderAll(context.applicationContext, mgr, ids, force) { try { pr?.finish() } catch (_: Throwable) {} }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        renderAsync(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        renderAsync(context, intArrayOf(appWidgetId))   // تغيير المقاس بيعيد رسم التصميم على المقاس الجديد
    }

    override fun onEnabled(context: Context) {
        setAlarm(context, true)
    }

    override fun onDisabled(context: Context) {
        setAlarm(context, false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACT_REFRESH, ACT_ALARM -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, PrayerWidget::class.java))
                if (ids.isNotEmpty()) renderAsync(context, ids, intent.action == ACT_REFRESH)   // الدوسة = تحديث من النت
            }
            else -> super.onReceive(context, intent)
        }
    }
}

/** (v665) رسام كارت مواقيت الصلاة. كل القياسات بالـdp (الكانفاس متكبّر بالكثافة). */
internal object PrayerCard {
    private const val RLM = "\u200F"
    private val DAYS = arrayOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
    private val MONTHS = arrayOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")
    private val BOLD: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GOLD = 0xFFFFD166.toInt()

    fun hm12(t: String): String {
        val hm = t.split(":")
        val h24 = hm.getOrNull(0)?.toIntOrNull() ?: return t
        val m = hm.getOrNull(1)?.toIntOrNull() ?: 0
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return String.format(Locale.US, "%02d:%02d", h12, m)
    }

    fun leftHM(ms: Long): String {
        val m = ((ms.coerceAtLeast(0L) + 59_999L) / 60_000L)
        return String.format(Locale.US, "%d:%02d", m / 60, m % 60)
    }

    fun bgFile(times: Map<String, String>, now: Long): String {
        return when (PrayerTimes.currentPrayer(times, now)) {
            "Fajr" -> {
                val sr = times["Sunrise"]
                if (sr != null && PrayerTimes.todayMillis(sr) <= now) "mosque_day.jpg" else "mosque_fajr.jpg"
            }
            "Dhuhr" -> "mosque_day.jpg"
            "Asr" -> "mosque_asr.jpg"
            "Maghrib" -> "mosque_sunset.jpg"
            else -> "mosque_night.jpg"
        }
    }

    private fun loadBg(c: Context, file: String): Bitmap? {
        return try {
            c.assets.open("widgets/prayer-times/backgrounds/$file").use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fill(col: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; style = Paint.Style.FILL }

    private fun txt(col: Int, sz: Float, bold: Boolean, al: Paint.Align): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col; textSize = sz; textAlign = al
            typeface = if (bold) BOLD else Typeface.DEFAULT
        }

    private fun fit(p: Paint, s: String, maxW: Float) {
        while (p.measureText(s) > maxW && p.textSize > 7f) p.textSize = p.textSize - 0.5f
    }

    private fun rrect(cv: Canvas, r: RectF, rad: Float, fillCol: Int, strokeCol: Int = 0, sw: Float = 1f) {
        cv.drawRoundRect(r, rad, rad, fill(fillCol))
        if (strokeCol != 0) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.color = strokeCol
            cv.drawRoundRect(r, rad, rad, p)
        }
    }

    private fun sun(cv: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        cv.drawCircle(cx, cy, r * 0.46f, fill(col))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = col; p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val c0 = Math.cos(a).toFloat(); val s0 = Math.sin(a).toFloat()
            cv.drawLine(cx + c0 * r * 0.70f, cy + s0 * r * 0.70f, cx + c0 * r * 0.98f, cy + s0 * r * 0.98f, p)
        }
    }

    private fun moon(cv: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        val a = Path(); a.addCircle(cx, cy, r * 0.85f, Path.Direction.CW)
        val b = Path(); b.addCircle(cx + r * 0.42f, cy - r * 0.28f, r * 0.72f, Path.Direction.CW)
        a.op(b, Path.Op.DIFFERENCE)
        cv.drawPath(a, fill(col))
    }

    private fun prayerIcon(cv: Canvas, key: String, cx: Float, cy: Float, r: Float, col: Int) {
        if (key == "Maghrib" || key == "Isha") moon(cv, cx, cy, r, col) else sun(cv, cx, cy, r, col)
    }

    private fun mosque(cv: Canvas, cx: Float, cy: Float, s: Float, col: Int) {
        val p = fill(col)
        cv.drawRect(cx - s * 0.30f, cy + s * 0.02f, cx + s * 0.30f, cy + s * 0.42f, p)
        cv.drawArc(RectF(cx - s * 0.28f, cy - s * 0.40f, cx + s * 0.28f, cy + s * 0.16f), 180f, 180f, true, p)
        cv.drawRect(cx - s * 0.02f, cy - s * 0.52f, cx + s * 0.02f, cy - s * 0.38f, p)
        for (sx in floatArrayOf(-1f, 1f)) {
            val mx = cx + sx * s * 0.44f
            cv.drawRect(mx - s * 0.05f, cy - s * 0.22f, mx + s * 0.05f, cy + s * 0.42f, p)
            val t = Path()
            t.moveTo(mx - s * 0.07f, cy - s * 0.22f); t.lineTo(mx, cy - s * 0.44f); t.lineTo(mx + s * 0.07f, cy - s * 0.22f); t.close()
            cv.drawPath(t, p)
        }
    }

    private fun gear(cv: Canvas, cx: Float, cy: Float, s: Float, col: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = col; p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.13f; p.strokeCap = Paint.Cap.ROUND
        cv.drawCircle(cx, cy, s * 0.2f, p)
        cv.drawCircle(cx, cy, s * 0.33f, p)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val c0 = Math.cos(a).toFloat(); val s0 = Math.sin(a).toFloat()
            cv.drawLine(cx + c0 * s * 0.36f, cy + s0 * s * 0.36f, cx + c0 * s * 0.48f, cy + s0 * s * 0.48f, p)
        }
    }

    private fun pin(cv: Canvas, cx: Float, cy: Float, s: Float, col: Int) {
        val a = Path(); a.addCircle(cx, cy - s * 0.12f, s * 0.34f, Path.Direction.CW)
        val t = Path()
        t.moveTo(cx - s * 0.28f, cy + s * 0.05f); t.lineTo(cx, cy + s * 0.5f); t.lineTo(cx + s * 0.28f, cy + s * 0.05f); t.close()
        a.op(t, Path.Op.UNION)
        val h = Path(); h.addCircle(cx, cy - s * 0.12f, s * 0.12f, Path.Direction.CW)
        a.op(h, Path.Op.DIFFERENCE)
        cv.drawPath(a, fill(col))
    }

    private fun drawBg(cv: Canvas, bmp: Bitmap?, w: Float, h: Float) {
        if (bmp == null) { cv.drawColor(0xFF14213D.toInt()); return }
        val iw = bmp.width.toFloat(); val ih = bmp.height.toFloat()
        val aspect = w / h
        var sw = iw; var sh = iw / aspect
        if (sh > ih) { sh = ih; sw = ih * aspect }
        val sx = (0.62f * iw - sw / 2f).coerceIn(0f, iw - sw)
        val sy = (ih - sh) * 0.35f
        cv.drawBitmap(bmp, Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** (v666) الخلفية: الجامع بيتحط كله في المنطقة الفاضية (يمين الكارت وفوق الشريط وتحت الهيدر) — الصورة بتتمدّد من حوافها (CLAMP) فالسما والمية بتكمّل الكارت كله */
    private fun drawBgZone(cv: Canvas, bmp: Bitmap?, w: Float, h: Float, zx0: Float, zx1: Float, zy0: Float, zy1: Float) {
        val zw = zx1 - zx0
        val zh = zy1 - zy0
        if (bmp == null || zw < 60f || zh < 50f) { drawBg(cv, bmp, w, h); return }
        val iw = bmp.width.toFloat(); val ih = bmp.height.toFloat()
        var mh = zh
        var mw = mh * 1.05f
        if (mw > zw) { mw = zw; mh = mw / 1.05f }
        val sc = mh / (0.613f * ih)
        val tx = (zx0 + zw / 2f) - sc * 0.625f * iw
        val ty = zy1 - sc * 0.778f * ih
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = Matrix(); m.setScale(sc, sc); m.postTranslate(tx, ty)
        shader.setLocalMatrix(m)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.shader = shader
        cv.drawRect(0f, 0f, w, h, p)
    }

    /** (v679) مكان العدّاد فوق الكارت (بالـdp على مقاس الصورة): المركز الأفقي + أعلى العدّاد + المتبقي بالمللي + مقاس الصورة */
    class Geo { var ok = false; var cx = 0f; var top = 0f; var leftMs = 0L; var wDp = 1f; var hDp = 1f }

    fun render(c: Context, wIn: Int, hIn: Int, data: PrayerTimes.Data?, dark: Boolean, now: Long, geo: Geo? = null): Bitmap {
        val wDp = wIn.coerceIn(110, 700).toFloat()
        val hDp = hIn.coerceIn(70, 700).toFloat()
        var d = c.resources.displayMetrics.density
        if (wDp * d > 1000f) d = 1000f / wDp
        if (hDp * d > 1000f) d = minOf(d, 1000f / hDp)
        val bmp = Bitmap.createBitmap(maxOf(1, (wDp * d).toInt()), maxOf(1, (hDp * d).toInt()), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(d, d)

        val times = data?.times
        val bgBmp = loadBg(c, if (times != null) bgFile(times, now) else "mosque_night.jpg")

        val clip = Path(); clip.addRoundRect(RectF(0f, 0f, wDp, hDp), 22f, 22f, Path.Direction.CW)
        cv.save(); cv.clipPath(clip)
        val fadeCol = if (dark) 0xFF0A1226.toInt() else 0xFFEEF0F4.toInt()
        val fadeRgb = fadeCol and 0x00FFFFFF
        val fadeA = if (dark) 0xE6000000.toInt() else 0xF2000000.toInt()

        if (data == null || times == null) {
            drawBg(cv, bgBmp, wDp, hDp)
            val tp0 = Paint(); tp0.shader = LinearGradient(0f, 0f, 0f, hDp, 0x80000000.toInt(), 0xB0000000.toInt(), Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, wDp, hDp, tp0)
            val p1 = txt(WHITE, 15f, true, Paint.Align.CENTER)
            val p2 = txt(0xFFE3E9F5.toInt(), 11.5f, false, Paint.Align.CENTER)
            cv.drawText(RLM + "⚠ ما قدرتش أجيب المواقيت", wDp / 2f, hDp / 2f - 2f, p1)
            cv.drawText(RLM + "اتأكد من النت ودوس على الويدجت", wDp / 2f, hDp / 2f + 16f, p2)
            cv.restore()
            return bmp
        }

        // ألوان الثيم
        val txtMain = if (dark) WHITE else 0xFF1E293B.toInt()
        val txtSub = if (dark) 0xFFB8C4D9.toInt() else 0xFF64748B.toInt()
        val iconCol = if (dark) 0xFFA9C4FF.toInt() else 0xFF475569.toInt()
        val panelFill = if (dark) 0xE60E1730.toInt() else 0xF5FFFFFF.toInt()
        val panelStroke = if (dark) 0x40FFFFFF else 0x22000000
        val tileFill = if (dark) 0xE67A5F2B.toInt() else 0xFFFCE7B6.toInt()
        val tileStroke = if (dark) 0x66FFD166 else 0xFFF2C96B.toInt()
        val tileTxt = if (dark) GOLD else 0xFF6B4A00.toInt()

        val next = PrayerTimes.nextPrayer(times, now)
        val left = next.second - now
        val tall = hDp >= 150f
        val wide = wDp >= 300f
        val pad = if (wDp < 280f) 10f else 12f
        val ib = if (wDp < 280f) 30f else 34f

        // ---------- الحسابات (من غير رسم) ----------
        val title = RLM + "مواقيت الصلاة"
        val tp = txt(WHITE, if (ib > 30f) 15f else 13.5f, true, Paint.Align.RIGHT)
        val cityS = RLM + data.city.ifBlank { "الموقع الحالي" }
        val cp = txt(0xFFFFE9B0.toInt(), 10.5f, false, Paint.Align.RIGHT)
        fit(cp, cityS, if (tall && wide) 170f else wDp * 0.42f)
        val textR = wDp - pad - ib - 8f
        val headW = maxOf(tp.measureText(title), cp.measureText(cityS) + 13f)
        val headLeft = textR - headW

        val cal = Calendar.getInstance()
        val dayName = RLM + DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val greg = RLM + String.format(Locale.US, "%d %s %d", cal.get(Calendar.DAY_OF_MONTH), MONTHS[cal.get(Calendar.MONTH)], cal.get(Calendar.YEAR))
        val hij = if (data.hijri.isNotBlank()) RLM + data.hijri + " هـ" else ""
        val pd = txt(WHITE, 12f, true, Paint.Align.RIGHT)
        val pg = txt(WHITE, 10.5f, false, Paint.Align.RIGHT)
        val ph2 = txt(0xFFFFE9B0.toInt(), 10.5f, false, Paint.Align.RIGHT)
        val maxLine = maxOf(pd.measureText(dayName), pg.measureText(greg), if (hij.isNotEmpty()) ph2.measureText(hij) else 0f)
        val dateXr = headLeft - 14f
        val dateBeside = tall && (dateXr - (pad + 30f) >= maxLine)
        val dateBelow = if (tall && !dateBeside) 15f else 0f

        val stripH = if (tall) (hDp * 0.28f).coerceIn(62f, 84f) else (hDp * 0.40f).coerceIn(44f, 62f)
        val sMargin = 8f
        val sTop = hDp - sMargin - stripH

        val py0 = if (tall) pad + ib + 8f + dateBelow else pad
        val py1 = sTop - 6f
        val ph = (py1 - py0).coerceAtLeast(36f)
        val pw = if (tall) (wDp * 0.46f).coerceIn(120f, 200f) else (headLeft - 8f - pad).coerceIn(96f, 175f)
        val px = pad

        // ---------- الخلفية: الجامع في المنطقة الفاضية ----------
        drawBgZone(cv, bgBmp, wDp, hDp, px + pw + 8f, wDp - pad, (if (tall) pad + ib + dateBelow + 2f else pad), sTop - 2f)
        val topP = Paint(); topP.shader = LinearGradient(0f, 0f, 0f, minOf(90f, hDp * 0.5f), 0x80000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, wDp, hDp, topP)
        val botP = Paint()
        botP.shader = LinearGradient(0f, sTop - 26f, 0f, hDp, fadeRgb, fadeRgb or fadeA, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, wDp, hDp, botP)

        // ---------- الهيدر ----------
        rrect(cv, RectF(wDp - pad - ib, pad, wDp - pad, pad + ib), 9f, 0xCC232D3F.toInt(), 0x33FFFFFF)
        mosque(cv, wDp - pad - ib / 2f, pad + ib / 2f + 1f, ib * 0.62f, WHITE)
        cv.drawText(title, textR, pad + ib * 0.46f, tp)
        pin(cv, textR - 4.5f, pad + ib * 0.80f, 10f, 0xFFFFE9B0.toInt())
        cv.drawText(cityS, textR - 13f, pad + ib * 0.90f, cp)

        // ---------- التاريخ + ⚙ (الوضع الطويل) ----------
        if (tall) {
            gear(cv, pad + 10f, pad + 11f, 19f, 0xCCFFFFFF.toInt())
            if (dateBeside) {
                cv.drawText(dayName, dateXr, pad + 12f, pd)
                cv.drawText(greg, dateXr, pad + 26f, pg)
                if (hij.isNotEmpty()) cv.drawText(hij, dateXr, pad + 40f, ph2)
            } else {
                val one = RLM + DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1] + "، " + String.format(Locale.US, "%d %s", cal.get(Calendar.DAY_OF_MONTH), MONTHS[cal.get(Calendar.MONTH)])
                val po = txt(WHITE, 10.5f, false, Paint.Align.RIGHT)
                fit(po, one, wDp - 2f * pad)
                cv.drawText(one, wDp - pad, pad + ib + 13f, po)
            }
        }

        // ---------- شريط الخمس صلوات ----------
        rrect(cv, RectF(sMargin, sTop, wDp - sMargin, hDp - sMargin), 14f, panelFill, panelStroke)
        val colW = (wDp - 2f * sMargin) / 5f
        val g = (stripH / 62f).coerceIn(0.78f, 1.25f)
        for (i in PrayerTimes.ORDER.indices) {
            val k = PrayerTimes.ORDER[i]
            val cx = wDp - sMargin - colW * (i + 0.5f)
            val isNext = k == next.first
            if (isNext) rrect(cv, RectF(cx - colW / 2f + 3f, sTop + 3f, cx + colW / 2f - 3f, hDp - sMargin - 3f), 10f, tileFill, tileStroke)
            prayerIcon(cv, k, cx, sTop + stripH * 0.28f, 7.5f * g, if (isNext) GOLD else iconCol)
            val np = txt(if (isNext) tileTxt else txtSub, 10.5f * g, isNext, Paint.Align.CENTER)
            val nm = RLM + PrayerTimes.ar(k)
            fit(np, nm, colW - 6f)
            cv.drawText(nm, cx, sTop + stripH * 0.64f, np)
            val tm = hm12(times[k] ?: "")
            val tmp = txt(if (isNext) tileTxt else txtMain, 11.5f * g, true, Paint.Align.CENTER)
            fit(tmp, tm, colW - 6f)
            cv.drawText(tm, cx, sTop + stripH * 0.90f, tmp)
        }

        // ---------- كارت الصلاة القادمة ----------
        val pr = RectF(px, py0, px + pw, py0 + ph)
        rrect(cv, pr, 13f, 0xCC1B2432.toInt(), 0x40FFFFFF)
        val pcx = px + pw / 2f
        val prx = px + pw - 10f
        val f = (ph / 66f).coerceIn(0.72f, 1.5f)
        val nameS = RLM + PrayerTimes.ar(next.first)
        val timeS = hm12(times[next.first] ?: "")
        val clkS = RLM + "الساعة " + timeS
        val showLabel = tall && ph >= 84f
        val labelH = if (showLabel) 13f else 0f
        val nameSz = (if (showLabel) 22f else 12.5f) * f
        val cdH = 22f                                   // (v679) العدّاد ثابت 18dp (Chronometer) ← ارتفاعه ~22
        val clkSz = 10.5f * f.coerceIn(0.9f, 1.3f)
        val showClk = ph >= 58f
        val total = labelH + nameSz * 1.1f + cdH + (if (showClk) clkSz * 1.35f else 0f)
        var y = py0 + (ph - total) / 2f
        if (showLabel) {
            val lp = txt(0xFFD5DEEE.toInt(), 10.5f, false, Paint.Align.RIGHT)
            cv.drawText(RLM + "الصلاة القادمة", prx, y + 10f, lp)
            y += labelH
        }
        y += nameSz * 0.95f
        val np2 = txt(if (showLabel) GOLD else WHITE, nameSz, true, Paint.Align.RIGHT)
        fit(np2, nameS, pw - 20f - nameSz * 1.1f)
        val iconR = nameSz * 0.52f
        prayerIcon(cv, next.first, prx - iconR, y - nameSz * 0.34f, iconR, GOLD)
        cv.drawText(nameS, prx - iconR * 2f - 5f, y, np2)
        // (v679) «المتبقي» بقى هو الرقم الكبير (عدّاد ساعات:دقايق:ثواني بيتحط فوق الصورة من PrayerWidget)، والساعة بقت سطر صغير تحته
        val cdTop = y + nameSz * 0.15f
        if (geo != null) { geo.ok = left > 0L; geo.cx = pcx; geo.top = cdTop; geo.leftMs = left; geo.wDp = wDp; geo.hDp = hDp }
        if (showClk) {
            val cp2 = txt(0xFFE3E9F5.toInt(), clkSz, false, Paint.Align.CENTER)
            fit(cp2, clkS, pw - 16f)
            cv.drawText(clkS, pcx, cdTop + cdH + clkSz * 1.05f, cp2)
        }

        cv.restore()
        return bmp
    }
}
