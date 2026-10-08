@file:OptIn(UnstableApi::class)
package com.tttt.subtitler

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.widget.Toast
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue

/** (v135) مهمة واحدة في توبيب «المهام»: قص · صوت · GIF · ضغط · هارد ساب */
class TaskItem(val id: Int, val kind: String, val title: String) {
    /** 0 مستني · 1 شغّال · 2 خلص · 3 فشل · 4 اتلغى · 5 اتوقف (⏹) · 6 واقف مؤقتًا من غير ما يكون شغّال (هيبدأ من الأول لما يكمّل) */
    @Volatile var state = 0
    @Volatile var pct = 0
    @Volatile var msg = ""
    @Volatile var outUri: Uri? = null
    @Volatile var outPath = ""
    @Volatile var outSize = 0L
    @Volatile var mime = ""
    @Volatile var cancelled = false
    @Volatile var onCancel: (() -> Unit)? = null
    @Volatile var work: ((TaskItem) -> Unit)? = null
    /** (v137) نسخة من الشغل للإعادة بعد فشل/إلغاء، وإيقاف مؤقت للتحميلات */
    @Volatile var workKeep: ((TaskItem) -> Unit)? = null
    @Volatile var paused = false
    @Volatile var onPause: ((Boolean) -> Unit)? = null
    /** (v147) إيقاف ⏹ (بيفضل في القايمة ويتعاد بعدين) · إيقاف مؤقت للي مالوش استكمال حقيقي · إلغاء بيشيله · إعادة وهو شغّال */
    @Volatile var stopReq = false
    @Volatile var pauseRestart = false
    @Volatile var removeOnEnd = false
    @Volatile var retryAfter = false
}

class TaskCancelled : RuntimeException("اتلغت")

/** طابور المهام: مهمة واحدة في المرة (الترميز تقيل)، والناتج بيتحفظ لوحده في Movies / Music / Pictures ← Subtitler */
object TaskCenter {
    val items = CopyOnWriteArrayList<TaskItem>()
    @Volatile var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val q = LinkedBlockingQueue<TaskItem>()
    private val qd = LinkedBlockingQueue<TaskItem>()   // (v137) التحميلات في طابور لوحدها عشان ما تستناش الترميز
    private var worker: Thread? = null
    private var workerD: Thread? = null
    private var appCtx: Context? = null
    private var seq = 0

    fun changed() { main.post { try { listener?.invoke() } catch (_: Throwable) {} } }
    fun active(): Int = items.count { it.state == 0 || it.state == 1 }

    @Synchronized fun add(app: Context, kind: String, title: String, work: (TaskItem) -> Unit): TaskItem {
        val t = TaskItem(++seq, kind, title); t.work = work; t.workKeep = work
        appCtx = app.applicationContext
        val dl = kind == "download"
        items.add(0, t); (if (dl) qd else q).add(t); ensureWorker(app.applicationContext, dl); changed()
        return t
    }

    private fun ensureWorker(app: Context, dl: Boolean) {
        if (dl) { if (workerD?.isAlive == true) return; workerD = Thread { loop(app, qd) }.apply { isDaemon = true; start() } }
        else { if (worker?.isAlive == true) return; worker = Thread { loop(app, q) }.apply { isDaemon = true; start() } }
    }

    private fun aborted(t: TaskItem) {
        t.state = when { t.pauseRestart -> 6; t.stopReq -> 5; else -> 4 }
        t.msg = when (t.state) { 6 -> "واقفة مؤقتًا — لما تكمّل هتبدأ من الأول"; 5 -> "اتوقفت — دوس 🔁 إعادة محاولة تبدأها تاني"; else -> "اتلغت" }
    }

    /** (v147) بعد ما المهمة تقف فعلاً: لو كانت «إلغاء» تتشال، ولو «إعادة محاولة» وهي شغّالة تتضاف من جديد */
    private fun afterEnd(t: TaskItem) {
        if (t.removeOnEnd) { items.remove(t); return }
        if (t.retryAfter) {
            t.retryAfter = false
            val w = t.workKeep; val a = appCtx
            if (w != null && a != null) { items.remove(t); add(a, t.kind, t.title, w) }
        }
    }

    private fun loop(app: Context, q: LinkedBlockingQueue<TaskItem>) {
        while (true) {
            val t = try { q.take() } catch (_: InterruptedException) { return }
            if (t.cancelled) { aborted(t); afterEnd(t); changed(); continue }
            t.state = 1; changed()
            try {
                t.work?.invoke(t)
                if (t.cancelled) aborted(t)
                else {
                    t.state = 2; t.pct = 100
                    main.post { try { Toast.makeText(app, "✅ خلصت: " + t.title, Toast.LENGTH_LONG).show() } catch (_: Throwable) {} }
                }
            } catch (_: TaskCancelled) { aborted(t)
            } catch (e: Throwable) {
                if (t.cancelled) aborted(t) else {
                    t.state = 3
                    t.msg = (e.message ?: e.javaClass.simpleName).take(160)
                    LogStore.err("Tasks:" + t.kind, e)
                }
            }
            t.work = null; t.onCancel = null; t.onPause = null; t.paused = false; afterEnd(t); changed()
        }
    }

    /** (v147) ✕ إلغاء: بيوقف المهمة وبيشيلها من القايمة */
    fun cancel(t: TaskItem) {
        when (t.state) {
            1 -> { t.stopReq = false; t.pauseRestart = false; t.removeOnEnd = true; t.cancelled = true; t.onPause?.invoke(false); t.onCancel?.invoke() }
            0 -> { t.cancelled = true; items.remove(t); changed() }
            else -> { items.remove(t); changed() }
        }
    }
    /** (v147) ⏹ إيقاف: بيوقف المهمة وبتفضل في القايمة «اتوقفت» وتقدر تعيدها بـ 🔁 */
    fun stop(t: TaskItem) {
        when (t.state) {
            1 -> { t.stopReq = true; t.pauseRestart = false; t.cancelled = true; t.onPause?.invoke(false); t.onCancel?.invoke() }
            0, 6 -> { t.cancelled = true; t.stopReq = true; t.pauseRestart = false; t.state = 5; t.msg = "اتوقفت — دوس 🔁 إعادة محاولة تبدأها تاني"; changed() }
        }
    }
    /** (v147) ⏸ إيقاف مؤقت / ▶ استكمال: التحميل بيستكمل من نفس المكان؛ باقي المهام (ترميز) مفيهاش استكمال فبتتوقف وتبدأ من الأول لما تكمّل */
    fun pause(t: TaskItem, p: Boolean) {
        if (t.state == 6) { if (!p) resumeSoft(t); return }
        if (t.state == 1 && t.onPause != null) { t.paused = p; t.onPause?.invoke(p); changed(); return }
        if (!p) return
        if (t.state == 1) { t.pauseRestart = true; t.stopReq = false; t.cancelled = true; t.onCancel?.invoke() }
        else if (t.state == 0) { t.cancelled = true; t.pauseRestart = true; t.state = 6; t.msg = "واقفة مؤقتًا — لما تكمّل هتبدأ من الأول"; changed() }
    }
    private fun resumeSoft(t: TaskItem) {
        val w = t.workKeep ?: return; val app = appCtx ?: return
        items.remove(t); add(app, t.kind, t.title, w)
    }
    /** (v147) 🔁 إعادة محاولة: في أي حالة — لو شغّالة بتتوقف وتتبدي من الأول، ولو واقفة/فاشلة/خالصة بتتضاف من جديد */
    fun retry(t: TaskItem) {
        val w = t.workKeep ?: return; val app = appCtx ?: return
        when (t.state) {
            1 -> { t.retryAfter = true; t.stopReq = false; t.pauseRestart = false; t.cancelled = true; t.onPause?.invoke(false); t.onCancel?.invoke() }
            0 -> { t.cancelled = true; items.remove(t); add(app, t.kind, t.title, w) }
            else -> { items.remove(t); add(app, t.kind, t.title, w) }
        }
    }
    fun remove(t: TaskItem) { if (t.state == 0 || t.state == 1) cancel(t) else items.remove(t); changed() }
    fun clearFinished() { items.removeIf { it.state >= 2 }; changed() }
}

/** مصدر الأدوات: الفيديو الشغّال دلوقتي في المشغّل */
class ToolSrc(val uri: String, val audioUri: String?, val name: String, val durMs: Long, val w: Int, val h: Int, val curMs: Long, val subs: List<Sub>, val hdr: Map<String, String> = emptyMap())

class Probe(val w: Int, val h: Int, val durMs: Long, val aRate: Int)

/** حفظ الناتج: MediaStore على أندرويد 10+ (Movies/Music/Pictures ← Subtitler) */
object Out {
    fun publish(ctx: Context, t: TaskItem, f: File, display: String, mime: String) {
        val size = f.length()
        if (size <= 0L) throw IOException("الملف الناتج فاضي")
        if (Build.VERSION.SDK_INT >= 29) {
            val coll: Uri; val rel: String
            when {
                mime.startsWith("video") -> { coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; rel = Environment.DIRECTORY_MOVIES + "/Subtitler" }
                mime.startsWith("audio") -> { coll = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; rel = Environment.DIRECTORY_MUSIC + "/Subtitler" }
                else -> { coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; rel = Environment.DIRECTORY_PICTURES + "/Subtitler" }
            }
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, display); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(coll, cv) ?: throw IOException("ما قدرتش أكتب في المعرض")
            try {
                val os = cr.openOutputStream(uri) ?: throw IOException("ما قدرتش أفتح الملف للكتابة")
                os.use { o -> f.inputStream().use { it.copyTo(o) } }
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) { try { cr.delete(uri, null, null) } catch (_: Throwable) {}; throw e }
            t.outUri = uri; t.outPath = "$rel/$display"
        } else {
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Subtitler"); dir.mkdirs()
            val dst = File(dir, display); f.copyTo(dst, true)
            t.outUri = null; t.outPath = dst.absolutePath
        }
        t.outSize = size; t.mime = mime
        try { f.delete() } catch (_: Throwable) {}
    }
}

/** تشغيل Transformer (Media3) وانتظار نتيجته؛ لازم يتبدي ويتتابع من الـ main looper */
object Xform {
    fun run(ctx: Context, t: TaskItem, edited: EditedMediaItem, out: File, vMime: String?, vBitrate: Int, aBitrate: Int) {
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        var tr: Transformer? = null
        val poll = object : Runnable {
            override fun run() {
                val x = tr ?: return
                if (latch.count == 0L) return
                try {
                    val ph = ProgressHolder()
                    if (x.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) { t.pct = ph.progress.coerceIn(0, 99); TaskCenter.changed() }
                } catch (_: Throwable) {}
                main.postDelayed(this, 500)
            }
        }
        t.onCancel = { main.post { try { tr?.cancel() } catch (_: Throwable) {}; latch.countDown() } }
        if (t.cancelled) throw TaskCancelled()
        main.post {
            try {
                val eb = DefaultEncoderFactory.Builder(ctx)
                if (vBitrate > 0) eb.setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(vBitrate).build())
                if (aBitrate > 0) eb.setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(aBitrate).build())
                val b = Transformer.Builder(ctx).setEncoderFactory(eb.build()).setAudioMimeType(MimeTypes.AUDIO_AAC)
                if (vMime != null) b.setVideoMimeType(vMime)
                b.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { latch.countDown() }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { error = exportException; latch.countDown() }
                })
                val x = b.build(); tr = x
                x.start(edited, out.absolutePath)
                main.postDelayed(poll, 500)
            } catch (e: Throwable) { error = e; latch.countDown() }
        }
        latch.await()
        if (t.cancelled) { try { out.delete() } catch (_: Throwable) {}; throw TaskCancelled() }
        error?.let { e -> try { out.delete() } catch (_: Throwable) {}; throw (if (e is Exception) e else RuntimeException(e)) }
    }
}

/** ترجمة محروقة في الصورة: بتتحوّل لـ Bitmap شفاف بتتغيّر مع وقت الفيديو (النص العربي بيتشكّل بـ StaticLayout بتاع أندرويد) */
class SubOverlay(subs: List<Sub>, private val vw: Int, private val vh: Int, private val tf: Typeface, private val scale: Int, private val useOrig: Boolean) : BitmapOverlay() {
    private val sorted = subs.filter { textOf(it).isNotBlank() }.sortedBy { it.start }
    private val blank: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private var curIdx = -2
    private var curBmp: Bitmap = blank
    private var hint = 0
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setOverlayFrameAnchor(0f, -1f)
        .setBackgroundFrameAnchor(0f, -0.86f)
        .build()

    private fun textOf(s: Sub): String = (if (useOrig) s.original else s.translated.ifBlank { s.original }).trim()

    private fun find(tUs: Long): Int {
        val t = tUs / 1_000_000.0
        val n = sorted.size
        if (n == 0) return -1
        if (hint >= n) hint = n - 1
        while (hint > 0 && sorted[hint].start > t) hint--
        while (hint < n && sorted[hint].end <= t) hint++
        return if (hint < n && t >= sorted[hint].start && t < sorted[hint].end) hint else -1
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val i = find(presentationTimeUs)
        if (i != curIdx) { curIdx = i; curBmp = if (i < 0) blank else render(textOf(sorted[i])) }
        return curBmp
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    private fun render(text: String): Bitmap {
        val maxW = (vw * 0.9f).toInt().coerceAtLeast(64)
        val size = (minOf(vw, vh) * 0.06f * scale / 100f).coerceAtLeast(14f)
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = size; typeface = tf }
        val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size; typeface = tf; style = Paint.Style.STROKE; strokeWidth = size * 0.16f; strokeJoin = Paint.Join.ROUND }
        fun lay(p: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, p, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL).setLineSpacing(0f, 1.05f).build()
        val l1 = lay(fill); val l2 = lay(stroke)
        var lineW = 0f
        for (k in 0 until l1.lineCount) lineW = maxOf(lineW, l1.getLineWidth(k))
        val padX = (size * 0.55f).toInt(); val padY = (size * 0.28f).toInt()
        val bw = (lineW.toInt() + 2 * padX).coerceAtLeast(8); val bh = (l1.height + 2 * padY).coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRoundRect(0f, 0f, bw.toFloat(), bh.toFloat(), size * 0.35f, size * 0.35f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() })
        c.translate(bw / 2f - maxW / 2f, padY.toFloat())
        l2.draw(c); l1.draw(c)
        return bmp
    }
}

/** العمليات نفسها: كل دالة بتضيف مهمة للطابور وبترجّعها */
object Tools {
    const val ORIG = "الأصلية"
    val QUALS = listOf("منخفضة", "متوسطة", "عالية")

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }
    private fun secs(ms: Long) = (ms / 1000).toString()
    private fun safe(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(60).ifBlank { "video" }
    private fun even(v: Int) = (v / 2) * 2

    fun vBitrate(shortSide: Int, q: String): Int {
        val base = when { shortSide >= 1080 -> 6_000_000; shortSide >= 720 -> 3_500_000; shortSide >= 480 -> 1_800_000; shortSide >= 360 -> 1_000_000; shortSide > 0 -> 600_000; else -> 2_500_000 }
        return when (q) { "منخفضة" -> base * 45 / 100; "عالية" -> base * 16 / 10; else -> base }
    }
    fun aBitrate(q: String): Int = when (q) { "منخفضة" -> 96_000; "عالية" -> 256_000; else -> 160_000 }

    /** (v137) لينك نت → ينزل لملف مؤقت الأول (بالهيدرز، وبيدمج الصورة والصوت لو منفصلين)؛ ملف محلي → زي ما هو. بيرجّع (المسار، الملف المؤقت لو اتعمل) */
    private fun localSrc(app: Context, t: TaskItem, s: ToolSrc): Pair<String, File?> {
        if (!s.uri.startsWith("http")) return Pair(s.uri, null)
        val isHls = s.uri.contains(".m3u8", true)
        val f = tmp(app, t, "src." + (if (isHls) "ts" else "mp4"))
        val ctl = Downloader.Ctl(); ctl.ext = { t.cancelled }
        t.onCancel = { ctl.cancelled = true }
        t.msg = "⬇ بينزّل الفيديو الأول…"; TaskCenter.changed()
        try {
            Downloader.toCache(s.uri, s.audioUri, isHls, 0, s.hdr, f, ctl) { p -> t.pct = p * 30 / 100; t.msg = "⬇ بينزّل الفيديو… $p%"; TaskCenter.changed() }
        } catch (e: Throwable) { try { f.delete() } catch (_: Throwable) {}; throw e }
        t.onCancel = null
        return Pair(Uri.fromFile(f).toString(), f)
    }
    private fun localAudio(app: Context, t: TaskItem, url: String, hdr: Map<String, String>): Pair<String, File?> {
        if (!url.startsWith("http")) return Pair(url, null)
        val f = tmp(app, t, "src.m4a")
        val ctl = Downloader.Ctl(); ctl.ext = { t.cancelled }
        t.onCancel = { ctl.cancelled = true }
        t.msg = "⬇ بينزّل الصوت الأول…"; TaskCenter.changed()
        try { Downloader.toCache(url, null, false, 0, hdr, f, ctl) { p -> t.pct = p * 30 / 100; t.msg = "⬇ بينزّل الصوت… $p%"; TaskCenter.changed() } }
        catch (e: Throwable) { try { f.delete() } catch (_: Throwable) {}; throw e }
        t.onCancel = null
        return Pair(Uri.fromFile(f).toString(), f)
    }
    private fun del(f: File?) { try { f?.delete() } catch (_: Throwable) {} }

    private fun tmp(ctx: Context, t: TaskItem, ext: String): File { val d = File(ctx.cacheDir, "tasks"); d.mkdirs(); return File(d, "t${t.id}.$ext") }

    fun probe(ctx: Context, src: String): Probe {
        val mr = MediaMetadataRetriever()
        var w = 0; var h = 0; var d = 0L; var ar = 0
        try {
            if (src.startsWith("http")) mr.setDataSource(src, HashMap<String, String>()) else mr.setDataSource(ctx, Uri.parse(src))
            w = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
            d = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {} finally { try { mr.release() } catch (_: Throwable) {} }
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, Uri.parse(src), null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) ar = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); break }
            }
        } catch (_: Throwable) {} finally { try { ex.release() } catch (_: Throwable) {} }
        return Probe(w, h, d, ar)
    }

    // ===== قص =====
    fun trim(ctx: Context, s: ToolSrc, a: Long, b: Long, q: String): TaskItem {
        val app = ctx.applicationContext
        return TaskCenter.add(app, "trim", "✂ قص ${clock(a)} ← ${clock(b)} · ${s.name}") { t ->
            val (src, srcF) = localSrc(app, t, s)
            val pr = probe(app, src)
            val item = MediaItem.Builder().setUri(src)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a).setEndPositionMs(b).build()).build()
            val out = tmp(app, t, "mp4")
            try {
                t.msg = "بيقص…"
                Xform.run(app, t, EditedMediaItem.Builder(item).build(), out, MimeTypes.VIDEO_H264, vBitrate(minOf(pr.w, pr.h).let { if (pr.w == 0 || pr.h == 0) 0 else it }, q), aBitrate(q))
                Out.publish(app, t, out, "${safe(s.name)} قص ${secs(a)}-${secs(b)} ث.mp4", "video/mp4")
            } finally { try { out.delete() } catch (_: Throwable) {}; del(srcF) }
        }
    }

    // ===== صوت (M4A/AAC) =====
    fun audio(ctx: Context, s: ToolSrc, q: String): TaskItem {
        val app = ctx.applicationContext
        return TaskCenter.add(app, "audio", "🎧 صوت ($q) · ${s.name}") { t ->
            val (src, srcF) = if (s.audioUri != null) localAudio(app, t, s.audioUri, s.hdr) else localSrc(app, t, s)
            val out = tmp(app, t, "m4a")
            try {
                if (q == ORIG) {
                    t.msg = "بيستخرج الصوت…"
                    copyAudio(app, src, out)
                } else {
                    val pr = probe(app, src)
                    // بنغيّر معدل العينة (44.1 ↔ 48 كيلو) عشان Transformer يعيد الترميز فعلاً ويطبّق الجودة بدل ما ينسخ الصوت زي ما هو
                    val target = if (pr.aRate == 44100) 48000 else 44100
                    val sonic = SonicAudioProcessor(); sonic.setOutputSampleRateHz(target)
                    val item = MediaItem.fromUri(src)
                    val ed = EditedMediaItem.Builder(item).setRemoveVideo(true)
                        .setEffects(Effects(listOf<AudioProcessor>(sonic), emptyList<Effect>())).build()
                    t.msg = "بيحوّل الصوت…"
                    Xform.run(app, t, ed, out, null, 0, aBitrate(q))
                }
                Out.publish(app, t, out, "${safe(s.name)}.m4a", "audio/mp4")
            } finally { try { out.delete() } catch (_: Throwable) {}; del(srcF) }
        }
    }

    /** نسخ مسار الصوت زي ما هو من غير إعادة ترميز (سريع، بس لازم الصوت يكون AAC أو نوع بيقبله MP4) */
    private fun copyAudio(ctx: Context, src: String, out: File) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, Uri.parse(src), null)
            var ti = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { ti = i; fmt = f; break }
            }
            if (ti < 0 || fmt == null) throw IOException("مفيش مسار صوت في الفيديو ده")
            ex.selectTrack(ti)
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val mt = try { muxer.addTrack(fmt) } catch (e: Exception) { throw IOException("الصوت الأصلي مش AAC — اختار جودة (منخفضة/متوسطة/عالية) بدل «الأصلية»") }
                muxer.start()
                val buf = ByteBuffer.allocate(1024 * 1024); val info = MediaCodec.BufferInfo()
                while (true) {
                    buf.clear(); val n = ex.readSampleData(buf, 0); if (n < 0) break
                    info.set(0, n, ex.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(mt, buf, info); ex.advance()
                }
                muxer.stop()
            } finally { try { muxer.release() } catch (_: Throwable) {} }
        } finally { try { ex.release() } catch (_: Throwable) {} }
    }

    // ===== GIF =====
    fun gif(ctx: Context, s: ToolSrc, startMs: Long, lenMs: Long, q: String): TaskItem {
        val app = ctx.applicationContext
        val width = when (q) { "منخفضة" -> 240; "عالية" -> 480; else -> 360 }
        val fps = when (q) { "منخفضة" -> 8; "عالية" -> 12; else -> 10 }
        return TaskCenter.add(app, "gif", "🎞 GIF ${clock(startMs)} (${lenMs / 1000} ث) · ${s.name}") { t ->
            val mr = MediaMetadataRetriever()
            val out = tmp(app, t, "gif")
            val (src, srcF) = localSrc(app, t, s)
            try {
                mr.setDataSource(app, Uri.parse(src))
                val frames = (lenMs * fps / 1000).toInt().coerceIn(1, 240)
                var gw: GifWriter? = null; var px: IntArray? = null; var ow = 0; var oh = 0; var got = 0
                out.outputStream().buffered().use { os ->
                    for (i in 0 until frames) {
                        if (t.cancelled) throw TaskCancelled()
                        val tUs = startMs * 1000L + i * 1_000_000L / fps
                        val bm = mr.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                        if (gw == null) {
                            ow = even(minOf(width, bm.width)).coerceAtLeast(2); oh = even((ow.toLong() * bm.height / bm.width).toInt()).coerceAtLeast(2)
                            gw = GifWriter(os, ow, oh); px = IntArray(ow * oh)
                        }
                        val sc = if (bm.width == ow && bm.height == oh) bm else Bitmap.createScaledBitmap(bm, ow, oh, true)
                        sc.getPixels(px!!, 0, ow, 0, 0, ow, oh)
                        gw!!.addFrame(px!!, 1000 / fps); got++
                        if (sc !== bm) sc.recycle(); bm.recycle()
                        t.pct = ((i + 1) * 100 / frames).coerceAtMost(99); t.msg = "فريم ${i + 1} / $frames"; TaskCenter.changed()
                    }
                    if (gw == null) throw IOException("ما قدرتش أقرا فريمات من الفيديو ده")
                    gw!!.finish()
                }
                Out.publish(app, t, out, "${safe(s.name)} ${secs(startMs)}ث.gif", "image/gif")
            } finally { try { mr.release() } catch (_: Throwable) {}; try { out.delete() } catch (_: Throwable) {}; del(srcF) }
        }
    }

    // ===== ضغط / تغيير الدقة =====
    fun compress(ctx: Context, s: ToolSrc, targetShort: Int, q: String): TaskItem {
        val app = ctx.applicationContext
        val lab = if (targetShort > 0) "${targetShort}p" else "نفس الدقة"
        return TaskCenter.add(app, "compress", "📦 ضغط ($lab · $q) · ${s.name}") { t ->
            val (src, srcF) = localSrc(app, t, s)
            val pr = probe(app, src)
            val short = minOf(pr.w, pr.h)
            val fx = ArrayList<Effect>(); var outShort = short
            if (targetShort > 0 && short > 0 && targetShort < short) {
                val sc = targetShort.toDouble() / short
                fx.add(Presentation.createForWidthAndHeight(even((pr.w * sc).toInt()).coerceAtLeast(2), even((pr.h * sc).toInt()).coerceAtLeast(2), Presentation.LAYOUT_SCALE_TO_FIT))
                outShort = targetShort
            }
            val ed = EditedMediaItem.Builder(MediaItem.fromUri(src)).setEffects(Effects(emptyList<AudioProcessor>(), fx)).build()
            val out = tmp(app, t, "mp4")
            try {
                t.msg = "بيضغط…"
                Xform.run(app, t, ed, out, MimeTypes.VIDEO_H264, vBitrate(outShort, q), aBitrate(q))
                Out.publish(app, t, out, "${safe(s.name)} مضغوط.mp4", "video/mp4")
            } finally { try { out.delete() } catch (_: Throwable) {}; del(srcF) }
        }
    }

    // ===== هارد ساب =====
    fun hardsub(ctx: Context, s: ToolSrc, q: String, fontFile: String?, scale: Int, useOrig: Boolean): TaskItem {
        val app = ctx.applicationContext
        return TaskCenter.add(app, "hardsub", "🎬 ترجمة ثابتة في الفيديو · ${s.name}") { t ->
            val (src, srcF) = localSrc(app, t, s)
            var pr = probe(app, src)
            if ((pr.w <= 0 || pr.h <= 0) && s.w > 0 && s.h > 0) pr = Probe(s.w, s.h, pr.durMs, pr.aRate)   // أبعاد من المشغّل لو الفحص فشل
            if (pr.w <= 0 || pr.h <= 0) { del(srcF); throw IOException("ما قدرتش أعرف أبعاد الفيديو") }
            val tf = try { if (fontFile != null) Typeface.createFromAsset(app.assets, "fonts/$fontFile") else Typeface.DEFAULT_BOLD } catch (_: Throwable) { Typeface.DEFAULT_BOLD }
            val ov = SubOverlay(s.subs, pr.w, pr.h, tf, scale, useOrig)
            val fx = ArrayList<Effect>(); fx.add(OverlayEffect(ImmutableList.of<TextureOverlay>(ov)))
            val ed = EditedMediaItem.Builder(MediaItem.fromUri(src)).setEffects(Effects(emptyList<AudioProcessor>(), fx)).build()
            val out = tmp(app, t, "mp4")
            try {
                t.msg = "بيحرق الترجمة (بياخد وقت)…"
                Xform.run(app, t, ed, out, MimeTypes.VIDEO_H264, vBitrate(minOf(pr.w, pr.h), q), aBitrate(q))
                Out.publish(app, t, out, "${safe(s.name)} (ترجمة ثابتة).mp4", "video/mp4")
            } finally { try { out.delete() } catch (_: Throwable) {}; del(srcF) }
        }
    }
}
