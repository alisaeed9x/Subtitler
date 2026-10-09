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
 * (v174) الوضع البصري من غير مفاتيح: ML Kit على الجهاز يقرا النص الظاهر في الفريم (إنجليزي / ياباني / كوري) ويترجمه للعربي (ML Kit Translate).
 * بيشتغل لو الإعداد «على الجهاز» مفعّل، أو لو مفيش مفتاح للوضع البصري. موديل الترجمة (~30MB لكل لغة) بيتنزّل مرة واحدة أول استخدام وبعدها أوفلاين.
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

    private class Raw(val text: String, val r: Rect, val lang: String)

    private fun translator(lang: String): Translator = synchronized(translators) {
        translators.getOrPut(lang) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(lang).setTargetLanguage(TranslateLanguage.ARABIC).build())
        }
    }

    private fun translate(lang: String, text: String, say: (String) -> Unit): String {
        val t = translator(lang)
        val first = synchronized(ready) { ready.add(lang) }
        if (first) {
            say("👁 بنزّل موديل الترجمة (مرة واحدة)…")
            try { Tasks.await(t.downloadModelIfNeeded(DownloadConditions.Builder().build())) }
            catch (e: Exception) { synchronized(ready) { ready.remove(lang) }; throw e }
        }
        return Tasks.await(t.translate(text))
    }

    /** بيرجّع النصوص المقروءة والمترجمة بمواضعها. لازم يتنادى من خيط خلفي (بيستنى نتايج ML Kit). */
    @Throws(Exception::class)
    fun detect(bmp: Bitmap, hardsub: Boolean, say: (String) -> Unit): List<VisBox> {
        val img = InputImage.fromBitmap(bmp, 0)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
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
        val out = ArrayList<VisBox>()
        for (r in raws) {
            val cy = r.r.exactCenterY() / h
            if (hardsub && cy < 0.62f) continue
            val tr = try { translate(r.lang, r.text, say).trim() } catch (e: java.util.concurrent.ExecutionException) { throw Exception(e.cause?.message ?: "فشلت الترجمة") }
            if (tr.isEmpty() || tr == r.text) continue
            if (hardsub) out.add(VisBox(0.5f, cy.coerceIn(0.05f, 0.97f), 0.9f, (r.r.height() / h * 1.4f).coerceIn(0.05f, 0.3f), 0f, r.text, tr, Color.BLACK, Color.WHITE, 90, true))
            else out.add(VisBox((r.r.exactCenterX() / w).coerceIn(0f, 1f), cy.coerceIn(0f, 1f), (r.r.width() / w).coerceIn(0.03f, 1f), (r.r.height() / h).coerceIn(0.02f, 0.5f), 0f, r.text, tr, Color.BLACK, Color.WHITE, 85, true))
        }
        return out
    }
}
