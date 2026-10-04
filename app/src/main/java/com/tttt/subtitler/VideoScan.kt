package com.tttt.subtitler

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.util.concurrent.Executors

/** فحص فيديوهات الجهاز (كل التخزين الداخلي والـ SD) عن طريق MediaStore — بيشمل MKV وباقي الصيغ */
object VideoScan {
    /** آخر نتيجة فحص (في الذاكرة) عشان الرجوع من المشغّل يبقى فوري */
    @Volatile var cache: List<VideoItem>? = null

    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_VIDEO" else "android.permission.READ_EXTERNAL_STORAGE"
    fun hasPermission(ctx: Context): Boolean = ctx.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun scan(ctx: Context): List<VideoItem> {
        val cr = ctx.contentResolver
        val out = ArrayList<VideoItem>()
        val seen = HashSet<String>()
        val base: Uri = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = ArrayList<String>().apply {
            add(MediaStore.Video.Media._ID); add(MediaStore.Video.Media.DISPLAY_NAME); add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DURATION); add(MediaStore.Video.Media.DATE_ADDED); add(MediaStore.Video.Media.DATE_MODIFIED)
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME); add(MediaStore.Video.Media.DATA)
            if (Build.VERSION.SDK_INT >= 29) { add(MediaStore.MediaColumns.RELATIVE_PATH); add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT) }
        }
        try {
            cr.query(base, proj.toTypedArray(), null, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val iAdd = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val iMod = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val iBuck = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val iData = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val iRel = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                val iW = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.WIDTH) else -1
                val iH = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.HEIGHT) else -1
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val size = c.getLong(iSize)
                    if (size <= 0 || name.isBlank() || VideoLib.tooSmall(size, name)) continue
                    val (key, fname, fpath) = VideoLib.folderInfo(c.getString(iData), if (iRel >= 0) c.getString(iRel) else null, c.getString(iBuck))
                    seen.add("$name:$size:$key")
                    out.add(VideoItem(c.getLong(iId), ContentUris.withAppendedId(base, c.getLong(iId)).toString(), name, key, fname, fpath, size,
                        c.getLong(iDur), c.getLong(iAdd), c.getLong(iMod), if (iW >= 0) c.getInt(iW) else 0, if (iH >= 0) c.getInt(iH) else 0))
                }
            }
        } catch (_: Exception) {}
        // دعم إضافي: ملفات الفيديو اللي الجهاز ماصنفهاش "فيديو" (مثلًا MKV على بعض الأجهزة) — لو MediaStore بيرجّعها
        try {
            val files = MediaStore.Files.getContentUri("external")
            val exts = VideoLib.EXTS.toList()
            val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE} AND (" + exts.joinToString(" OR ") { "lower(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?" } + ")"
            val args = exts.map { "%.$it" }.toTypedArray()
            cr.query(files, arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.DATA), sel, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val size = c.getLong(2)
                    if (size <= 0 || VideoLib.tooSmall(size, name)) continue
                    val (key, fname, fpath) = VideoLib.folderInfo(c.getString(5), null, null)
                    if (!seen.add("$name:$size:$key")) continue
                    out.add(VideoItem(c.getLong(0), ContentUris.withAppendedId(files, c.getLong(0)).toString(), name, key, fname, fpath, size, 0L, c.getLong(3), c.getLong(4)))
                }
            }
        } catch (_: Exception) {}
        cache = out
        return out
    }
}

/** صور مصغّرة من الفيديو نفسه: loadThumbnail (أندرويد 10+) ثم لقطة بـ MediaMetadataRetriever لو فشل (MKV مثلًا) */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)) {
        override fun sizeOf(k: String, v: Bitmap) = v.byteCount
    }
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun peek(v: VideoItem): Bitmap? = cache.get(v.uri)
    fun clear() { cache.evictAll(); synchronized(failed) { failed.clear() } }

    /** بيحمّل في الخلفية وبينادي onDone على الـ UI thread (null لو مفيش صورة) */
    fun load(ctx: Context, v: VideoItem, onDone: (Bitmap?) -> Unit) {
        cache.get(v.uri)?.let { onDone(it); return }
        if (synchronized(failed) { v.uri in failed }) { onDone(null); return }
        val app = ctx.applicationContext
        pool.execute {
            val b = decode(app, v)
            if (b != null) cache.put(v.uri, b) else synchronized(failed) { failed.add(v.uri) }
            main.post { onDone(b) }
        }
    }

    @Suppress("DEPRECATION")
    private fun decode(ctx: Context, v: VideoItem): Bitmap? {
        val u = Uri.parse(v.uri)
        try {
            if (Build.VERSION.SDK_INT >= 29) return ctx.contentResolver.loadThumbnail(u, Size(384, 216), null)
            MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, v.id, MediaStore.Video.Thumbnails.MINI_KIND, null)?.let { return it }
        } catch (_: Exception) {}
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, u)
            val t = if (v.durMs > 6000) 3_000_000L else 0L
            val f = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            f?.let { if (it.width > 384) Bitmap.createScaledBitmap(it, 384, (it.height * 384f / it.width).toInt().coerceAtLeast(1), true) else it }
        } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
    }
}
