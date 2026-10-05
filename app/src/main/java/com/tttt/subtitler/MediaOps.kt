package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** عمليات الملف من ⋮ في قايمة الفيديوهات: تفاصيل · إعادة تسمية · نقل · مشاركة · حذف.
 *  على أندرويد 10+ الملفات اللي مش بتاعة التطبيق بتحتاج موافقة النظام (نافذة تأكيد بتظهر مرة لكل عملية). */
class MediaOps(private val act: Activity, private val ui: Ui, private val th: Theme, private val onChanged: () -> Unit) {
    companion object { const val REQ = 4721 }
    private var pending: (() -> Unit)? = null

    /** مع صلاحية «إدارة كل الملفات» (أو أندرويد 9-) بنتعامل مع الملف مباشرة من غير نافذة موافقة النظام */
    private fun fileOps(v: VideoItem) = v.folderKey.startsWith("/") && (Build.VERSION.SDK_INT < 30 && Build.VERSION.SDK_INT < 29 || (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()))
    private fun toast(m: String) = Toast.makeText(act, m, Toast.LENGTH_LONG).show()
    private fun busy(v: VideoItem): Boolean {
        if (BgJobs.isActive(v.videoId)) { toast("الفيديو ده بيترجم في الخلفية — وقّف الترجمة الأول من ⋮"); return true }
        return false
    }

    /** بينفّذ العملية؛ لو النظام رفض (SecurityException) بيطلب الموافقة ويعيد التنفيذ بعدها */
    private fun access(uri: Uri, delete: Boolean, op: () -> Unit, afterSystemDelete: () -> Unit = {}) = accessMany(listOf(uri), delete, op, afterSystemDelete)

    /** نفس اللي فوق بس لأكتر من ملف في طلب موافقة واحد (أندرويد 11+) — للعمليات على فولدر كامل */
    private fun accessMany(uris: List<Uri>, delete: Boolean, op: () -> Unit, afterSystemDelete: () -> Unit = {}) {
        try { op(); return } catch (e: SecurityException) {
            try {
                val cr = act.contentResolver
                val sender = when {
                    Build.VERSION.SDK_INT >= 30 -> (if (delete) MediaStore.createDeleteRequest(cr, uris) else MediaStore.createWriteRequest(cr, uris)).intentSender
                    Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException && uris.size == 1 -> e.userAction.actionIntent.intentSender
                    else -> throw e
                }
                pending = if (delete && Build.VERSION.SDK_INT >= 30) afterSystemDelete else op
                act.startIntentSenderForResult(sender, REQ, null, 0, 0, 0)
            } catch (e2: Exception) { toast("النظام منع العملية دي: " + (e2.message ?: "").take(80)) }
        } catch (e: Exception) { toast("فشلت العملية: " + (e.message ?: e.javaClass.simpleName).take(100)) }
    }

    fun onResult(code: Int, resultOk: Boolean): Boolean {
        if (code != REQ) return false
        val p = pending; pending = null
        if (!resultOk) { toast("اتلغت العملية"); return true }
        try { p?.invoke() } catch (e: Exception) { toast("فشلت العملية: " + (e.message ?: "").take(100)) }
        return true
    }

    private fun askText(title: String, value: String, hint: String, okLabel: String, f: (String) -> Unit) {
        val et = EditText(act).apply {
            setText(value); setSelection(text.length); this.hint = hint; inputType = InputType.TYPE_CLASS_TEXT; layoutDirection = View.LAYOUT_DIRECTION_LTR
            setTextColor(th.text); setHintTextColor(th.muted); setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); background = ui.box(th.surface, th.border, 10)
        }
        val box = LinearLayout(act).apply { setPadding(ui.dp(18), ui.dp(8), ui.dp(18), 0); addView(et, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(act).setTitle(title).setView(box).setPositiveButton(okLabel) { _, _ -> f(et.text.toString().trim()) }.setNegativeButton("إلغاء", null).show()
    }

    // ===== إعادة تسمية =====
    fun rename(v: VideoItem) {
        if (busy(v)) return
        val ext = v.name.substringAfterLast('.', "")
        val base = v.name.substringBeforeLast('.', v.name)
        askText("✏ إعادة تسمية", base, "الاسم الجديد (من غير الامتداد)", "تغيير") { nn ->
            val clean = nn.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
            if (clean.isEmpty()) { toast("الاسم فاضي أو فيه رموز ممنوعة"); return@askText }
            val newName = if (ext.isEmpty()) clean else "$clean.$ext"
            if (newName == v.name) return@askText
            val uri = Uri.parse(v.uri)
            val op = {
                if (!fileOps(v)) {
                    val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                    if (act.contentResolver.update(uri, cv, null, null) <= 0) throw Exception("النظام ماغيّرش الاسم")
                } else {
                    val old = File(v.folderKey, v.name); val nw = File(v.folderKey, newName)
                    if (nw.exists() || !old.renameTo(nw)) throw Exception("ماقدرتش أغيّر اسم الملف")
                    android.media.MediaScannerConnection.scanFile(act, arrayOf(old.path, nw.path), null, null)
                }
                migrateProgress(v, newName)
                toast("✅ اتغير الاسم: $newName"); onChanged()
            }
            access(uri, false, op)
        }
    }

    /** الترجمة المحفوظة مربوطة باسم+حجم الملف — لازم تتنقل للاسم الجديد */
    private fun migrateProgress(v: VideoItem, newName: String) {
        try {
            val oldId = v.videoId; val newId = "f:$newName:${v.size}"
            val dir = File(act.filesDir, "progress")
            val o = File(dir, Store.keyFor(oldId) + ".json"); val n = File(dir, Store.keyFor(newId) + ".json")
            if (o.exists() && !n.exists()) o.renameTo(n)
            Recents.rename(act, oldId, newId, Recents.titleOf(newId))
        } catch (_: Exception) {}
    }

    // ===== نقل =====
    private fun relOf(key: String): String? {
        val r = when {
            key.startsWith("rel:") -> key.removePrefix("rel:")
            key.startsWith("/storage/emulated/0/") -> key.removePrefix("/storage/emulated/0/")
            else -> return null
        }.trim('/')
        return if (r.isEmpty()) null else "$r/"
    }

    // ===== عمليات الفولدر كله (من ⋮ الفولدر) =====
    private fun folderBusy(f: FolderItem): Boolean = f.videos.any { busy(it) }

    /** إعادة تسمية الفولدر: بتنقل كل فيديوهاته للمسار الجديد (الترجمات مربوطة بالاسم+الحجم فمابتتأثرش) */
    fun renameFolder(f: FolderItem) {
        if (f.videos.isEmpty()) { toast("الفولدر فاضي"); return }
        if (folderBusy(f)) return
        val rel = relOf(f.key) ?: run { toast("مقدرش أغيّر اسم الفولدر ده (مش على الذاكرة الداخلية)"); return }
        val parent = rel.trimEnd('/').substringBeforeLast('/', "")
        val cur = rel.trimEnd('/').substringAfterLast('/')
        askText("✏ إعادة تسمية الفولدر", cur, "الاسم الجديد", "تغيير") { nn ->
            val clean = nn.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
            if (clean.isEmpty()) { toast("الاسم فاضي أو فيه رموز ممنوعة"); return@askText }
            if (clean == cur) return@askText
            val newRel = (if (parent.isEmpty()) "" else "$parent/") + clean + "/"
            val uris = f.videos.map { Uri.parse(it.uri) }
            val op = {
                if (f.videos.all { fileOps(it) }) {
                    val root = Environment.getExternalStorageDirectory()
                    val old = File(root, rel); val nw = File(root, newRel)
                    if (nw.exists() || !old.renameTo(nw)) throw Exception("ماقدرتش أغيّر اسم الفولدر")
                    android.media.MediaScannerConnection.scanFile(act, f.videos.flatMap { listOf(File(old, it.name).path, File(nw, it.name).path) }.toTypedArray(), null, null)
                } else {
                    for (u in uris) {
                        val cv = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, newRel) }
                        if (act.contentResolver.update(u, cv, null, null) <= 0) throw Exception("النظام ماقدرش ينقل الملفات")
                    }
                }
                toast("✅ اتغير اسم الفولدر: $clean"); onChanged()
            }
            accessMany(uris, false, op)
        }
    }

    /** مسح الفولدر بكل فيديوهاته (ومعاهم الترجمات المحفوظة) */
    fun deleteFolder(f: FolderItem) {
        if (f.videos.isEmpty()) { toast("الفولدر فاضي"); return }
        if (folderBusy(f)) return
        AlertDialog.Builder(act).setTitle("🗑 مسح الفولدر").setMessage("هتمسح «${f.name}» بكل اللي فيه (${f.videos.size} فيديو) من الجهاز نهائيًا ومعاهم الترجمات المحفوظة. متأكد؟")
            .setPositiveButton("امسح الكل") { _, _ ->
                val uris = f.videos.map { Uri.parse(it.uri) }
                val cleanup = {
                    for (v in f.videos) {
                        try { File(File(act.filesDir, "progress"), Store.keyFor(v.videoId) + ".json").delete() } catch (_: Exception) {}
                        Recents.drop(act, v.videoId)
                    }
                    toast("🗑 اتمسح الفولدر"); onChanged()
                }
                val op = {
                    for (v in f.videos) {
                        if (!fileOps(v)) { if (act.contentResolver.delete(Uri.parse(v.uri), null, null) <= 0) throw Exception("النظام ماحذفش ${v.name}") }
                        else { val ff = File(v.folderKey, v.name); if (!ff.delete()) throw Exception("ماقدرتش أمسح ${v.name}"); android.media.MediaScannerConnection.scanFile(act, arrayOf(ff.path), null, null) }
                    }
                    cleanup()
                }
                accessMany(uris, true, op, cleanup)
            }.setNegativeButton("إلغاء", null).show()
    }

    fun move(v: VideoItem, folders: List<FolderItem>) {
        if (busy(v)) return
        val cur = relOf(v.folderKey)
        val opts = folders.mapNotNull { f -> relOf(f.key)?.let { f.name to it } }.filter { it.second != cur }.distinctBy { it.second }
        val labels = (opts.map { "📁 " + it.first + "   (" + it.second + ")" } + "➕ مجلد جديد…").toTypedArray()
        AlertDialog.Builder(act).setTitle("📁 نقل «${v.title}» إلى").setItems(labels) { _, i ->
            if (i < opts.size) doMove(v, opts[i].second)
            else askText("مجلد جديد", "Movies/", "المسار بالنسبة للذاكرة (مثال: Movies/أنمي)", "نقل") { p ->
                val rel = p.trim().trim('/').replace(Regex("/+"), "/")
                if (rel.isEmpty() || rel.contains("..")) { toast("مسار غير صالح"); return@askText }
                doMove(v, "$rel/")
            }
        }.setNegativeButton("إلغاء", null).show()
    }
    private fun doMove(v: VideoItem, rel: String) {
        val uri = Uri.parse(v.uri)
        val op = {
            if (!fileOps(v)) {
                val cv = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, rel) }
                if (act.contentResolver.update(uri, cv, null, null) <= 0) throw Exception("النظام ماقدرش ينقل الملف")
            } else {
                val dir = File(Environment.getExternalStorageDirectory(), rel); dir.mkdirs()
                val old = File(v.folderKey, v.name); val nw = File(dir, v.name)
                if (nw.exists() || !old.renameTo(nw)) throw Exception("ماقدرتش أنقل الملف")
                android.media.MediaScannerConnection.scanFile(act, arrayOf(old.path, nw.path), null, null)
            }
            toast("✅ اتنقل إلى $rel"); onChanged()
        }
        access(uri, false, op)
    }

    // ===== مشاركة =====
    fun share(v: VideoItem) {
        try {
            act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, Uri.parse(v.uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "مشاركة الفيديو"))
        } catch (_: Exception) { toast("ماقدرتش أشارك الملف ده") }
    }

    // ===== حذف =====
    fun delete(v: VideoItem) {
        if (busy(v)) return
        AlertDialog.Builder(act).setTitle("🗑 حذف الفيديو").setMessage("هتمسح «${v.name}» من الجهاز نهائيًا (ومعاه الترجمة المحفوظة له). متأكد؟")
            .setPositiveButton("احذف") { _, _ ->
                val uri = Uri.parse(v.uri)
                val cleanup = {
                    try { File(File(act.filesDir, "progress"), Store.keyFor(v.videoId) + ".json").delete() } catch (_: Exception) {}
                    Recents.drop(act, v.videoId)
                    toast("🗑 اتمسح"); onChanged()
                }
                val op = {
                    if (!fileOps(v)) { if (act.contentResolver.delete(uri, null, null) <= 0) throw Exception("النظام ماحذفش الملف") }
                    else { val f = File(v.folderKey, v.name); if (!f.delete()) throw Exception("ماقدرتش أمسح الملف"); android.media.MediaScannerConnection.scanFile(act, arrayOf(f.path), null, null) }
                    cleanup()
                }
                access(uri, true, op, cleanup)
            }.setNegativeButton("إلغاء", null).show()
    }

    // ===== تفاصيل =====
    fun details(v: VideoItem, rec: Recent?) {
        Thread {
            val L = ArrayList<Pair<String, String>>()
            val fmt = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US)
            L += "الاسم" to v.name
            L += "المكان" to (if (v.folderKey.startsWith("/")) v.folderKey else v.folderPath) + "/" + v.name
            L += "الحجم" to VideoLib.fmtSize(v.size) + "  (" + String.format(Locale.US, "%,d", v.size) + " بايت)"
            if (v.durMs > 0) L += "المدة" to VideoLib.fmtDur(v.durMs)
            if (v.added > 0) L += "تاريخ الإضافة" to fmt.format(Date(v.added * 1000L))
            if (v.modified > 0) L += "آخر تعديل" to fmt.format(Date(v.modified * 1000L))
            L += "الصيغة" to v.ext.uppercase()
            var w = v.w; var h = v.h
            try {
                val mr = MediaMetadataRetriever()
                try {
                    mr.setDataSource(act, Uri.parse(v.uri))
                    if (w <= 0) w = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    if (h <= 0) h = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { L += "معدل البت الكلي" to String.format(Locale.US, "%.2f Mbps", it / 1_000_000.0) }
                    if (Build.VERSION.SDK_INT >= 23) mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.let { L += "الإطارات/ثانية" to String.format(Locale.US, "%.2f", it) }
                    mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.takeIf { it != "0" }?.let { L += "الدوران" to "$it°" }
                } finally { try { mr.release() } catch (_: Exception) {} }
            } catch (_: Exception) {}
            if (w > 0 && h > 0) L.add(4.coerceAtMost(L.size), "الأبعاد" to "$w × $h")
            try {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(act, Uri.parse(v.uri), null)
                    var ai = 0
                    for (i in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                        when {
                            mime.startsWith("video/") -> L += "فيديو" to mime.removePrefix("video/").uppercase() + (if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) "  ·  " + f.getInteger(MediaFormat.KEY_FRAME_RATE) + " fps" else "")
                            mime.startsWith("audio/") -> {
                                ai++
                                val lang = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null
                                val ch = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).toString() + " قناة" else ""
                                val sr = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE).toString() + " Hz" else ""
                                L += "صوت $ai" to listOfNotNull(mime.removePrefix("audio/").uppercase(), lang?.takeIf { it != "und" }, ch.ifEmpty { null }, sr.ifEmpty { null }).joinToString("  ·  ")
                            }
                            else -> L += "ترجمة/نص" to mime
                        }
                    }
                } finally { try { ex.release() } catch (_: Exception) {} }
            } catch (_: Exception) {}
            if (rec != null && rec.subs > 0) L += "ترجمة التطبيق" to "${rec.subs} جملة · تغطية ${rec.percent}%"
            val body = L.joinToString("\n\n") { it.first + ":\n" + it.second }
            act.runOnUiThread {
                if (act.isDestroyed) return@runOnUiThread
                val tv = TextView(act).apply { this.text = body; textSize = 13f; setTextColor(th.text); setTextIsSelectable(true); layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(10)) }
                AlertDialog.Builder(act).setTitle("ℹ تفاصيل الفيديو").setView(ScrollView(act).apply { addView(tv) })
                    .setPositiveButton("تمام", null)
                    .setNeutralButton("نسخ المسار") { _, _ ->
                        val p = (if (v.folderKey.startsWith("/")) v.folderKey else v.folderPath) + "/" + v.name
                        (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("path", p)); toast("اتنسخ المسار")
                    }.show()
            }
        }.apply { isDaemon = true }.start()
    }
}
