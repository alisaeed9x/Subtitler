import com.tttt.subtitler.GifWriter
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import java.io.ByteArrayInputStream

fun main() {
    var ok = true
    for ((w, h) in listOf(16 to 9, 120 to 80, 321 to 177)) {
        val bo = ByteArrayOutputStream()
        val g = GifWriter(bo, w, h)
        val frames = 3
        val src = ArrayList<IntArray>()
        val rnd = java.util.Random(7)
        for (f in 0 until frames) {
            val px = IntArray(w * h) { i ->
                val x = i % w; val y = i / w
                if (f == 0) 0xFF000000.toInt() or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 128          // تدرّج
                else if (f == 1) (0xFF000000.toInt() or rnd.nextInt(0xFFFFFF))                                       // ضوضاء (أسوأ حالة للـ LZW)
                else 0xFFFF0000.toInt()                                                                              // لون واحد
            }
            src.add(px); g.addFrame(px, 100)
        }
        g.finish()
        val bytes = bo.toByteArray()
        val rd = ImageIO.getImageReadersByFormatName("gif").next()
        rd.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        val n = rd.getNumImages(true)
        var maxErr = 0.0
        for (f in 0 until minOf(n, frames)) {
            val img = rd.read(f)
            var tot = 0L
            for (y in 0 until h) for (x in 0 until w) {
                val a = img.getRGB(x, y); val b = src[f][y * w + x]
                tot += Math.abs(((a shr 16) and 255) - ((b shr 16) and 255)) + Math.abs(((a shr 8) and 255) - ((b shr 8) and 255)) + Math.abs((a and 255) - (b and 255))
            }
            val avg = tot / (3.0 * w * h); if (avg > maxErr) maxErr = avg
            if (f == 2 && avg > 1.0) ok = false
        }
        println("${w}x$h: frames=$n bytes=${bytes.size} maxAvgErr=${"%.1f".format(maxErr)}")
        if (n != frames) ok = false
    }
    println(if (ok) "PASS gif roundtrip" else "FAIL gif roundtrip")
}
