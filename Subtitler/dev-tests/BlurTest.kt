import com.tttt.subtitler.*
var failsB = 0
fun chk(n: String, ok: Boolean, d: String = "") { if (ok) println("PASS $n") else { failsB++; println("FAIL $n $d") } }
fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
fun main() {
    val w = 20; val h = 6
    // لون واحد يفضل زي ما هو
    val uni = IntArray(w * h) { argb(255, 32, 64, 128) }
    val u2 = uni.copyOf(); Blur.box(u2, w, h, 3)
    chk("لون موحّد مابيتغيّرش", u2.contentEquals(uni))
    // نصف أسود نصف أبيض: تدرّج متصل، الأطراف ثابتة، الألفا 255
    val step = IntArray(w * h) { i -> if (i % w < 10) argb(255, 0, 0, 0) else argb(255, 255, 255, 255) }
    val s2 = step.copyOf(); Blur.box(s2, w, h, 3)
    val row = (0 until w).map { s2[it] and 255 }
    chk("تدرّج متزايد", (0 until w - 1).all { row[it] <= row[it + 1] }, row.toString())
    chk("الأطراف ثابتة (مفيش غمق)", row.first() == 0 && row.last() == 255, row.toString())
    chk("الحافة اتنعّمت", row[9] in 1..254 && row[10] in 1..254, row.toString())
    chk("الألفا فاضلة 255", s2.all { (it ushr 24) == 255 })
    chk("القنوات متساوية في الرمادي", s2.all { ((it shr 16) and 255) == (it and 255) })
    // الصفوف متطابقة (blur أفقي بس على صورة متطابقة الصفوف)
    chk("الصفوف المتطابقة تفضل متطابقة", (1 until h).all { y -> (0 until w).all { x -> s2[y * w + x] == s2[x] } })
    // r=0 مابيعملش حاجة
    val z = step.copyOf(); Blur.box(z, w, h, 0)
    chk("نصف قطر 0 = من غير تغيير", z.contentEquals(step))
    // الاتجاه الرأسي: أعلى أسود وتحت أبيض
    val vs = IntArray(w * h) { i -> if (i / w < 3) argb(255, 0, 0, 0) else argb(255, 255, 255, 255) }
    Blur.box(vs, w, h, 2)
    val col = (0 until h).map { vs[it * w] and 255 }
    chk("blur رأسي متدرّج", (0 until h - 1).all { col[it] <= col[it + 1] } && col[2] > 0 && col[3] < 255, col.toString())
    // متوسط السطوع تقريبًا ثابت (الصورة متماثلة)
    val sum0 = step.sumOf { (it and 255).toLong() }; val sum1 = s2.sumOf { (it and 255).toLong() }
    chk("المتوسط تقريبًا ثابت", Math.abs(sum0 - sum1) <= sum0 / 50, "$sum0 $sum1")
    // حجم صغير جدًا مايبوظش
    val tiny = intArrayOf(argb(255, 10, 20, 30)); Blur.box(tiny, 1, 1, 5)
    chk("صورة 1x1", tiny[0] == argb(255, 10, 20, 30))
    // نصف القطر والمقاس
    chk("radiusFor", Blur.radiusFor(0, 3f) == 0 && Blur.radiusFor(6, 3f) == 5 && Blur.radiusFor(20, 3f) == 15 && Blur.radiusFor(500, 3f) == 16 && Blur.radiusFor(1, 1f) == 1)
    chk("smallSize", Blur.smallSize(800, 120) == (200 to 30) && Blur.smallSize(10, 10) == (4 to 4))
    if (failsB > 0) { println("فشل $failsB"); System.exit(1) } else println("كل الاختبارات نجحت")
}
