package com.tttt.subtitler

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** بيكتب ملف SRT جنب الفيديو (نفس الاسم) أول ما الترجمة تخلص — MX Player وأي مشغّل بيلقطه لوحده.
 *  محتاج صلاحية «إدارة كل الملفات» (أو إن الفولدر قابل للكتابة). مابيمسحش SRT مش بتاعنا: لو فيه ملف بنفس الاسم بيكتب `اسم.ar.srt`. */
object SrtWriter {
    private val lock = Any()
    private fun registry(ctx: Context) = File(ctx.filesDir, "srt_written.txt")

    fun pathOf(ctx: Context, uri: String): File? = try {
        ctx.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.startsWith("/") }?.let { File(it) } else null
        }
    } catch (_: Exception) { null }

    /** بيرجّع المسار اللي اتكتب فيه أو null */
    fun save(ctx: Context, uri: String?, subs: List<Sub>, offsetMs: Long): String? {
        if (uri.isNullOrEmpty() || subs.isEmpty() || !Cfg.bool("autosrt", true)) return null
        synchronized(lock) {
            try {
                val video = pathOf(ctx, uri) ?: return null
                val dir = video.parentFile ?: return null
                val base = video.nameWithoutExtension
                val reg = registry(ctx)
                val written = try { reg.readLines().toHashSet() } catch (_: Exception) { hashSetOf<String>() }
                var out = File(dir, "$base.srt")
                if (out.exists() && out.path !in written) out = File(dir, "$base.ar.srt")
                if (out.exists() && out.path !in written) return null
                val tmp = File(dir, out.name + ".tmp")
                tmp.writeText(PlayerLogic.toSrt(subs, offsetMs), Charsets.UTF_8)
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) { tmp.delete(); return null }
                if (out.path !in written) reg.appendText(out.path + "\n")
                android.media.MediaScannerConnection.scanFile(ctx, arrayOf(out.path), arrayOf("application/x-subrip"), null)
                return out.path
            } catch (_: Exception) { return null }
        }
    }
}
