package com.tttt.subtitler

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** فيديو واحد من فحص التخزين. uri كنص عشان الملف ده يفضل منطق نقي (بيتختبر على JVM) */
class VideoItem(
    val id: Long, val uri: String, val name: String,
    val folderKey: String, val folderName: String, val folderPath: String,
    val size: Long, val durMs: Long, val added: Long, val modified: Long,
    val w: Int = 0, val h: Int = 0
) {
    /** نفس مفتاح PlayerActivity.videoId() — عشان نعرف التقدم المحفوظ للفيديو */
    val videoId: String get() = "f:$name:$size"
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    val title: String get() = name.substringBeforeLast('.', name)
    val dateMs: Long get() = (if (modified > 0) modified else added) * 1000L
    /** لاند سكيب إلا لو الفيديو معروف إنه طولي */
    val landscape: Boolean get() = !(w > 0 && h > w)
}

class FolderItem(val key: String, val name: String, val path: String, val videos: List<VideoItem>) {
    val totalSize: Long get() = videos.sumOf { it.size }
    val newest: Long get() = videos.maxOfOrNull { it.dateMs } ?: 0L
    val oldest: Long get() = videos.minOfOrNull { it.dateMs } ?: 0L
}

object VideoLib {
    /** صيغ الفيديو المدعومة (MKV منها) */
    val EXTS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "3g2", "ts", "m2ts", "mts", "flv", "wmv", "mpg", "mpeg", "ogv", "vob", "asf", "divx")
    fun isVideoName(n: String): Boolean = n.substringAfterLast('.', "").lowercase() in EXTS

    const val SORT_NAME = "name"; const val SORT_NEW = "new"; const val SORT_OLD = "old"; const val SORT_SIZE = "size"
    val sortLabels = listOf(SORT_NAME to "🔤 الاسم", SORT_NEW to "🕒 الأحدث", SORT_OLD to "⏳ الأقدم", SORT_SIZE to "📦 الحجم")
    fun sortKeyOf(label: String) = sortLabels.firstOrNull { it.second == label }?.first ?: SORT_NAME
    fun sortLabelOf(key: String) = sortLabels.firstOrNull { it.first == key }?.second ?: sortLabels[0].second

    /** مقارنة "طبيعية": حلقة 2 قبل حلقة 10، وبتتعامل مع الأرقام العربية */
    fun natural(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).map { Character.digit(it, 10) }.joinToString("").trimStart('0')
                val nb = b.substring(j, ej).map { Character.digit(it, 10) }.joinToString("").trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb); if (c != 0) return c
                i = ei; j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar()); if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    fun sortVideos(l: List<VideoItem>, mode: String): List<VideoItem> = when (mode) {
        SORT_NEW -> l.sortedWith(compareByDescending<VideoItem> { it.dateMs }.thenComparator { a, b -> natural(a.name, b.name) })
        SORT_OLD -> l.sortedWith(compareBy<VideoItem> { it.dateMs }.thenComparator { a, b -> natural(a.name, b.name) })
        SORT_SIZE -> l.sortedWith(compareByDescending<VideoItem> { it.size }.thenComparator { a, b -> natural(a.name, b.name) })
        else -> l.sortedWith { a, b -> natural(a.name, b.name) }
    }

    fun sortFolders(l: List<FolderItem>, mode: String): List<FolderItem> = when (mode) {
        SORT_NEW -> l.sortedWith(compareByDescending<FolderItem> { it.newest }.thenComparator { a, b -> natural(a.name, b.name) })
        SORT_OLD -> l.sortedWith(compareBy<FolderItem> { it.oldest }.thenComparator { a, b -> natural(a.name, b.name) })
        SORT_SIZE -> l.sortedWith(compareByDescending<FolderItem> { it.totalSize }.thenComparator { a, b -> natural(a.name, b.name) })
        else -> l.sortedWith { a, b -> natural(a.name, b.name) }
    }

    fun group(items: List<VideoItem>): List<FolderItem> =
        items.groupBy { it.folderKey }.map { (k, v) -> FolderItem(k, v[0].folderName, v[0].folderPath, v) }

    /** (مفتاح الفولدر، اسمه، مساره المختصر) من _data أو RELATIVE_PATH أو اسم الـ bucket */
    fun folderInfo(data: String?, rel: String?, bucket: String?): Triple<String, String, String> {
        val dir = data?.takeIf { it.contains('/') }?.substringBeforeLast('/')
        if (!dir.isNullOrEmpty()) return Triple(dir, dir.substringAfterLast('/').ifEmpty { bucket ?: "/" }, shortPath(dir))
        val r = rel?.trim('/')
        if (!r.isNullOrEmpty()) return Triple("rel:$r", r.substringAfterLast('/'), r)
        val b = bucket?.takeIf { it.isNotBlank() } ?: "غير معروف"
        return Triple("bucket:$b", b, b)
    }

    fun shortPath(p: String): String {
        val internal = "/storage/emulated/0"
        return when {
            p == internal -> "الذاكرة الداخلية"
            p.startsWith("$internal/") -> "الذاكرة الداخلية/" + p.removePrefix("$internal/")
            p.startsWith("/storage/") && p.count { it == '/' } >= 3 -> "SD/" + p.removePrefix("/storage/").substringAfter('/')
            else -> p
        }
    }

    fun fmtDur(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    fun fmtSize(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(Locale.US, "%d MB", b / 1048576)
        else -> String.format(Locale.US, "%d KB", b / 1024)
    }

    fun fmtDate(ms: Long): String = if (ms <= 0) "" else SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(ms))
}
