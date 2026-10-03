import com.tttt.subtitler.*
var failsL = 0
fun chkL(n: String, ok: Boolean, d: String = "") { if (ok) println("PASS $n") else { failsL++; println("FAIL $n $d") } }
fun vi(name: String, folder: String = "/storage/emulated/0/Movies", size: Long = 1000, mod: Long = 100, w: Int = 0, h: Int = 0): VideoItem {
    val (k, n, p) = VideoLib.folderInfo("$folder/$name", null, null)
    return VideoItem(name.hashCode().toLong(), "content://x/$name", name, k, n, p, size, 60000, mod, mod, w, h)
}
fun main() {
    // ترتيب طبيعي: حلقة 2 قبل حلقة 10، والأرقام العربية
    chkL("natural 2 < 10", VideoLib.natural("حلقة 2", "حلقة 10") < 0)
    chkL("natural E02 < E10", VideoLib.natural("show.E02.mkv", "show.E10.mkv") < 0)
    chkL("natural حروف كبيرة/صغيرة", VideoLib.natural("abc", "ABD") < 0 && VideoLib.natural("B", "a") > 0)
    chkL("natural أرقام عربية", VideoLib.natural("حلقة ٢", "حلقة ١٠") < 0)
    chkL("natural متساوي", VideoLib.natural("a1", "a1") == 0 && VideoLib.natural("a", "a1") < 0)

    // تجميع الفولدرات
    val all = listOf(vi("b.mp4", "/storage/emulated/0/Movies", 5, 300), vi("a.mkv", "/storage/emulated/0/Movies", 9, 100), vi("c.mp4", "/storage/emulated/0/Download", 1, 200), vi("d.avi", "/storage/1234-ABCD/Series", 50, 400))
    val g = VideoLib.group(all)
    chkL("3 فولدرات", g.size == 3, g.map { it.name }.toString())
    chkL("اسم الفولدر", g.map { it.name }.toSet() == setOf("Movies", "Download", "Series"))
    chkL("مسار مختصر داخلي", VideoLib.shortPath("/storage/emulated/0/Movies") == "الذاكرة الداخلية/Movies")
    chkL("مسار مختصر SD", VideoLib.shortPath("/storage/1234-ABCD/Series") == "SD/Series")
    chkL("folderInfo من relative path", VideoLib.folderInfo(null, "Download/Series/", "Series").let { it.second == "Series" && it.first == "rel:Download/Series" })
    chkL("folderInfo من bucket", VideoLib.folderInfo(null, null, "X").first == "bucket:X")

    // ترتيب الفيديوهات
    val vids = g.first { it.name == "Movies" }.videos
    chkL("sort name", VideoLib.sortVideos(vids, "name").map { it.name } == listOf("a.mkv", "b.mp4"))
    chkL("sort new", VideoLib.sortVideos(vids, "new").map { it.name } == listOf("b.mp4", "a.mkv"))
    chkL("sort old", VideoLib.sortVideos(vids, "old").map { it.name } == listOf("a.mkv", "b.mp4"))
    chkL("sort size", VideoLib.sortVideos(vids, "size").map { it.name } == listOf("a.mkv", "b.mp4"))
    chkL("sort غير معروف = الاسم", VideoLib.sortVideos(vids, "zzz").map { it.name } == listOf("a.mkv", "b.mp4"))
    // ترتيب الفولدرات
    chkL("folders name", VideoLib.sortFolders(g, "name").map { it.name } == listOf("Download", "Movies", "Series"))
    chkL("folders new", VideoLib.sortFolders(g, "new").map { it.name } == listOf("Series", "Movies", "Download"))
    chkL("folders size", VideoLib.sortFolders(g, "size").first().name == "Series")

    // الصيغ
    chkL("mkv مدعوم", VideoLib.isVideoName("Film.MKV") && VideoLib.isVideoName("a.mp4") && !VideoLib.isVideoName("a.srt") && !VideoLib.isVideoName("noext"))

    // مفتاح التقدم يطابق PlayerActivity.videoId
    chkL("videoId", vi("x.mkv", size = 777).videoId == "f:x.mkv:777")
    chkL("title/ext", vi("x.y.mkv").title == "x.y" && vi("x.y.mkv").ext == "mkv")
    // اتجاه التشغيل
    chkL("لاند سكيب افتراضي", vi("a.mp4").landscape && vi("a.mp4", w = 1920, h = 1080).landscape)
    chkL("طولي", !vi("a.mp4", w = 720, h = 1280).landscape)

    // تنسيقات
    chkL("fmtDur", VideoLib.fmtDur(65000) == "01:05" && VideoLib.fmtDur(3725000) == "1:02:05" && VideoLib.fmtDur(-5) == "00:00")
    chkL("fmtSize", VideoLib.fmtSize(1536L * 1048576) == "1.50 GB" && VideoLib.fmtSize(350L * 1048576) == "350 MB" && VideoLib.fmtSize(2048) == "2 KB")
    chkL("fmtDate", VideoLib.fmtDate(0) == "" && VideoLib.fmtDate(1700000000000L).length == 10)
    chkL("sortKeyOf", VideoLib.sortKeyOf("🕒 الأحدث") == "new" && VideoLib.sortLabelOf("size") == "📦 الحجم" && VideoLib.sortKeyOf("؟") == "name")
    if (failsL > 0) { println("فشل $failsL"); System.exit(1) } else println("كل الاختبارات نجحت")
}
