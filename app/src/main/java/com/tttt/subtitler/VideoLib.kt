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

    // ===== بحث ذكي: كلمات متعددة بأي ترتيب + أرقام عربية/هندية/كلام (تسعة = 9) + تجاهل التشكيل والهمزات =====
    private val numWords = mapOf(
        "صفر" to "0", "واحد" to "1", "واحده" to "1", "وحده" to "1", "اول" to "1", "اثنين" to "2", "اتنين" to "2", "ثنين" to "2",
        "ثلاثه" to "3", "تلاته" to "3", "ثلاث" to "3", "تلات" to "3", "اربعه" to "4", "اربع" to "4", "خمسه" to "5", "خمس" to "5",
        "سته" to "6", "ست" to "6", "سبعه" to "7", "سبع" to "7", "ثمانيه" to "8", "تمانيه" to "8", "ثماني" to "8", "تمان" to "8", "تمن" to "8",
        "تسعه" to "9", "تسع" to "9", "عشره" to "10", "عشر" to "10",
        "عشرين" to "2", "ثلاثين" to "3", "تلاتين" to "3", "اربعين" to "4", "خمسين" to "5", "ستين" to "6", "سبعين" to "7",
        "ثمانين" to "8", "تمانين" to "8", "تسعين" to "9",
        "ميتين" to "2", "مئتين" to "2", "تلتميه" to "3", "ثلاثميه" to "3", "ربعميه" to "4",
        "اربعميه" to "4", "خمسميه" to "5", "ستميه" to "6", "سبعميه" to "7", "تمنميه" to "8", "ثمانميه" to "8", "تسعميه" to "9", "تسعمايه" to "9"
    )
    fun norm(x: String): String {
        val sb = StringBuilder()
        for (ch in x.lowercase()) {
            val c = when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ة' -> 'ه'
                'ى', 'ئ' -> 'ي'
                'ؤ' -> 'و'
                '_', '.', '-', '[', ']', '(', ')' -> ' '
                else -> ch
            }
            if (c in '\u064B'..'\u065F' || c == '\u0640') continue
            if (c in '٠'..'٩') sb.append('0' + (c - '٠')) else if (c in '۰'..'۹') sb.append('0' + (c - '۰')) else sb.append(c)
        }
        return sb.toString()
    }
    fun tokens(q: String): List<String> = norm(q).split(' ', ',', '،').map { it.trim() }.filter { it.isNotEmpty() }.map { t0 ->
        val t = if (t0.length > 3 && t0.startsWith("و") && numWords.containsKey(t0.substring(1))) t0.substring(1) else t0
        numWords[t] ?: t
    }.distinct()
    /** عدد الكلمات اللي اتلاقت في الاسم (القاموس بيتقارن على الاسم + اسم الفولدر). 0 = مفيش تطابق */
    fun matchScore(v: VideoItem, toks: List<String>): Int {
        if (toks.isEmpty()) return 0
        val hay = norm(v.title + " " + v.folderName)
        return toks.count { hay.contains(it) }
    }
    fun search(all: List<VideoItem>, q: String): List<VideoItem> {
        val toks = tokens(q)
        if (toks.isEmpty()) return emptyList()
        val scored = all.map { it to matchScore(it, toks) }.filter { it.second > 0 }
        val full = scored.filter { it.second == toks.size }
        val pick = if (full.isNotEmpty()) full else scored   // لو مفيش تطابق كامل اعرض الأقرب (الأكتر كلمات)
        return pick.sortedWith { a, b -> if (a.second != b.second) b.second - a.second else natural(a.first.name, b.first.name) }.map { it.first }
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
