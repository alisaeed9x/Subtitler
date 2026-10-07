import com.tttt.subtitler.LockCore
var okN = 0; var bad = 0
fun chk(n: String, c: Boolean) { if (c) { okN++; println("PASS $n") } else { bad++; println("FAIL $n") } }
fun main() {
    val s = LockCore.newSalt()
    chk("هاش ثابت لنفس النمط", LockCore.hash(s, listOf(0, 1, 2, 5)) == LockCore.hash(s, listOf(0, 1, 2, 5)))
    chk("ترتيب مختلف = هاش مختلف", LockCore.hash(s, listOf(0, 1, 2, 5)) != LockCore.hash(s, listOf(5, 2, 1, 0)))
    chk("ملح مختلف = هاش مختلف", LockCore.hash(s, listOf(0, 1, 2, 5)) != LockCore.hash(LockCore.newSalt(), listOf(0, 1, 2, 5)))
    chk("0→2 بيعدّي على 1", LockCore.between(0, 2) == 1)
    chk("0→8 بيعدّي على 4", LockCore.between(0, 8) == 4)
    chk("0→1 مفيش نقطة بينهم", LockCore.between(0, 1) == null)
    chk("0→5 مفيش نقطة بينهم", LockCore.between(0, 5) == null)
    chk("6→2 بيعدّي على 4", LockCore.between(6, 2) == 4)
    chk("أقل من 5 محاولات = بدون تأخير", LockCore.lockoutMs(4) == 0L)
    chk("5 محاولات = 30ث", LockCore.lockoutMs(5) == 30_000L)
    chk("6 محاولات = 60ث", LockCore.lockoutMs(6) == 60_000L)
    chk("الحد الأقصى 10 دقايق", LockCore.lockoutMs(50) == 600_000L)
    println("الاختبارات: $okN نجح، $bad فشل")
}
