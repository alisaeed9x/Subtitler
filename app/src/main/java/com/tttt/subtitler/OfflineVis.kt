package com.tttt.subtitler

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * (v174) الوضع البصري على الجهاز: ML Kit يقرا النص الظاهر في الفريم (إنجليزي / ياباني / كوري).
 * (v175) القراءة (scan) منفصلة عن الترجمة: الترجمة بتتعمل بجيميناي (مفتاح الوضع البصري) في VisualMode،
 * ولو مفيش مفتاح أو فشل بيرجع لترجمة ML Kit على الجهاز (mlTranslate — موديل ~30MB لكل لغة بيتنزّل مرة واحدة).
 * الجزء السفلي من الشاشة (مكان الهارد ساب الطبيعي) مابيتقراش؛ أي نص فوقه (حتى لو شكله ترجمة محروقة على لوحة مثلًا) بيتقرا.
 */
object OfflineVis {
    fun enabled(): Boolean = try { Cfg.bool("vis_offline", false) } catch (_: Throwable) { false }

    private val latin: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val ja: TextRecognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val ko: TextRecognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val translators = HashMap<String, Translator>()
    private val ready = HashSet<String>()

    private fun hasHangul(s: String) = s.any { it in '\uAC00'..'\uD7AF' || it in '\u1100'..'\u11FF' || it in '\u3130'..'\u318F' }
    private fun hasKana(s: String) = s.any { it in '\u3040'..'\u30FF' }
    private fun hasHan(s: String) = s.any { it in '\u4E00'..'\u9FFF' }
    private fun mostlyLatin(s: String): Boolean {
        val letters = s.filter { it.isLetter() }
        if (letters.length < 2) return false
        return letters.count { it in 'A'..'Z' || it in 'a'..'z' || it in '\u00C0'..'\u024F' }.toDouble() / letters.length >= 0.7
    }

    /** من هنا لتحت = مكان الهارد ساب الطبيعي (نسبة من ارتفاع الفيديو) */
    const val BOTTOM = 0.74f

    private class Raw(val text: String, val r: Rect, val lang: String)

    private fun translator(lang: String): Translator = synchronized(translators) {
        translators.getOrPut(lang) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(lang).setTargetLanguage(TranslateLanguage.ARABIC).build())
        }
    }

    /** ترجمة ML Kit على الجهاز (احتياطي لو مفيش مفتاح أو جيميناي فشل). لازم من خيط خلفي. */
    @Throws(Exception::class)
    fun mlTranslate(lang: String, text: String, say: (String) -> Unit): String {
        val t = translator(lang)
        val first = synchronized(ready) { ready.add(lang) }
        try {
            if (first) {
                say("👁 بنزّل موديل الترجمة (مرة واحدة)…")
                Tasks.await(t.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            }
            return Tasks.await(t.translate(text)).trim()
        } catch (e: java.util.concurrent.ExecutionException) {
            if (first) synchronized(ready) { ready.remove(lang) }
            throw Exception(e.cause?.message ?: "فشلت الترجمة")
        } catch (e: Exception) {
            if (first) synchronized(ready) { ready.remove(lang) }
            throw e
        }
    }

    /**
     * بيقرا النصوص الظاهرة في الفريم بمواضعها (من غير ترجمة). لازم يتنادى من خيط خلفي (بيستنى نتايج ML Kit).
     * hardsub=false (العادي): بيتجاهل الجزء السفلي (y ≥ BOTTOM) لأنه مكان الهارد ساب.
     * hardsub=true: العكس — بيقرا الجزء السفلي بس.
     */
    @Throws(Exception::class)
    fun scan(bmp: Bitmap, hardsub: Boolean): List<Det> {
        val big = maxOf(bmp.width, bmp.height)
        val src = if (big > 1440) { val sc = 1440f / big; Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true) } else bmp
        try {
            val img = InputImage.fromBitmap(src, 0)
            val w = src.width.toFloat(); val h = src.height.toFloat()
            val raws = ArrayList<Raw>()
            fun run(c: TextRecognizer, pick: (String) -> String?) {
                val res = try { Tasks.await(c.process(img)) } catch (e: java.util.concurrent.ExecutionException) { throw Exception(e.cause?.message ?: "ML Kit فشل") }
                for (b in res.textBlocks) {
                    val t = b.text.replace('\n', ' ').trim()
                    val bb = b.boundingBox ?: continue
                    if (t.length < 2 || t.none { it.isLetter() }) continue
                    val lang = pick(t) ?: continue
                    if (raws.any { it.r.contains(bb.centerX(), bb.centerY()) }) continue   // نفس المكان اتقرا قبل كده بلغة تانية
                    raws.add(Raw(t, bb, lang))
                }
            }
            run(ko) { if (hasHangul(it)) "ko" else null }
            run(ja) { if (hasKana(it) || (hasHan(it) && !hasHangul(it))) "ja" else null }
            run(latin) { if (mostlyLatin(it)) "en" else null }
            val out = ArrayList<Det>()
            for (r in raws) {
                val cy = (r.r.exactCenterY() / h).coerceIn(0f, 1f)
                if (hardsub) { if (cy < 0.62f) continue } else { if (cy >= BOTTOM) continue }
                out.add(Det(r.text, r.lang, (r.r.exactCenterX() / w).coerceIn(0f, 1f), cy,
                    (r.r.width() / w).coerceIn(0.03f, 1f), (r.r.height() / h).coerceIn(0.02f, 0.5f)))
            }
            return out
        } finally { if (src !== bmp) src.recycle() }
    }

    /** صندوق العرض فوق الفيديو لقراءة + ترجمتها */
    fun box(d: Det, tr: String, hardsub: Boolean): VisBox =
        if (hardsub) VisBox(0.5f, d.y.coerceIn(0.05f, 0.97f), 0.9f, (d.h * 1.4f).coerceIn(0.05f, 0.3f), 0f, d.text, tr, Color.BLACK, Color.WHITE, 90, true)
        else VisBox(d.x, d.y, d.w, d.h, 0f, d.text, tr, Color.BLACK, Color.WHITE, 85, true)
}
