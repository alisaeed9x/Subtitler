package com.tttt.subtitler

/** منطق نقي لستايل الترجمة (بدون Android) — متختبر في SubStyleTest */
class Entrance(val id: String, val label: String)
class FontOpt(val id: String, val label: String, val serif: Boolean = false, val file: String? = null, val weight: Int = 700)

data class SubStyle(
    val scale: Int = 100, val bgOpa: Int = 45, val noBg: Boolean = false, val blur: Int = 0, val animMs: Int = 250,
    val anim: String = "default", val font: String = "Cairo", val plain: Boolean = false, val dual: Int = 0,
    val uniOn: Boolean = false, val uniColor: String = "#FFFFFF", val splitOn: Boolean = false, val splitThresh: Int = 8,
    val fontStyle: String = "orig", val punctOn: Boolean = true
) {
    companion object {
        val entrances = listOf(
            Entrance("default", "افتراضي"), Entrance("typewriter", "آلة كاتبة"), Entrance("fade", "تلاشي"), Entrance("slideup", "انزلاق لأعلى"),
            Entrance("slideside", "انزلاق جانبي"), Entrance("zoom", "تكبير"), Entrance("blur", "ضبابية تتضح"), Entrance("bounce", "نطة"),
            Entrance("flip", "انقلاب"), Entrance("letters", "كلمات متتابعة"), Entrance("rotate", "دوران"), Entrance("drop", "سقوط وارتداد"), Entrance("glow", "توهج نابض"))
        val fonts = listOf(
            FontOpt("Noto Sans Arabic", "نوتو (أوضح خط)", false, "noto_sans_arabic.ttf", 700), FontOpt("Cairo", "القاهرة", false, "cairo.ttf", 800), FontOpt("Tajawal", "تجوال", false, "tajawal.ttf", 800), FontOpt("IBM Plex Sans Arabic", "IBM Plex", false, "ibm_plex.ttf", 700),
            FontOpt("El Messiri", "المسيري", false, "el_messiri.ttf", 700), FontOpt("Reem Kufi", "ريم كوفي", false, "reem_kufi.ttf", 700), FontOpt("Almarai", "المرعي", false, "almarai.ttf", 800), FontOpt("Changa", "تشانجا", false, "changa.ttf", 800), FontOpt("Mada", "مدى", false, "mada.ttf", 900),
            FontOpt("Readex Pro", "Readex", false, "readex_pro.ttf", 700), FontOpt("Marhey", "مرحي", false, "marhey.ttf", 700), FontOpt("Noto Naskh Arabic", "نسخ", true, "noto_naskh.ttf", 700), FontOpt("Noto Kufi Arabic", "كوفي", false, "noto_kufi.ttf", 700),
            FontOpt("Scheherazade New", "شهرزاد", true, "scheherazade.ttf", 700), FontOpt("Amiri", "أميري", true, "amiri.ttf", 700), FontOpt("Aref Ruqaa", "رقعة", true, "aref_ruqaa.ttf", 700), FontOpt("Lateef", "لطيف", true, "lateef.ttf", 700),
            FontOpt("Harmattan", "هرماتان", false, "harmattan.ttf", 700), FontOpt("Jomhuria", "جمهورية", false, "jomhuria.ttf", 400), FontOpt("Lalezar", "لاله زار", false, "lalezar.ttf", 400), FontOpt("Katibeh", "كاتبة", false, "katibeh.ttf", 400), FontOpt("Mirza", "ميرزا", true, "mirza.ttf", 700))
        /** أنماط الخط الأربعة: (id, اسم) */
        val fontStyles = listOf("orig" to "يحاكي الأصلي", "bold" to "عريض", "slim" to "رشيق", "casual" to "عامي")
        val MALE = 0xFFAEE2FF.toInt(); val FEMALE = 0xFFFBC9E0.toInt(); val WHITE = 0xFFFFFFFF.toInt()
        val PLACE = 0xFFFFD54F.toInt(); val EMPH = 0xFFFFF59D.toInt()
        val unifiedPalette = listOf("#FFFFFF", "#FFE600", "#00CFFF", "#A8FF3E", "#FF9F1C")

        fun load(get: (String, String) -> String): SubStyle {
            fun i(k: String, d: Int, lo: Int, hi: Int) = (get(k, d.toString()).trim().toIntOrNull() ?: d).coerceIn(lo, hi)
            fun b(k: String, d: Boolean) = get(k, if (d) "1" else "0") == "1"
            val an = get("sub_anim", "default").let { a -> if (entrances.any { it.id == a }) a else "default" }
            return SubStyle(i("sub_scale", 100, 60, 200), i("sub_bgopa", 45, 0, 100), b("sub_nobg", false), i("sub_blur", 0, 0, 20), i("sub_aspeed", 250, 50, 600),
                an, get("sub_font", "Cairo"), b("sub_plain", false), i("sub_dual", 0, 0, 4), b("sub_uni_on", false),
                get("sub_uni_color", "#FFFFFF"), b("sub_split_on", false), i("sub_split", 8, 3, 30),
                get("sub_fontstyle", "orig").let { if (it in fontStyles.map { f -> f.first }) it else "orig" }, b("sub_punct", true))
        }

        /** كلمة تأكيد/تعجب: بتنتهي بـ ! أو لاتيني كله كبير أو حرف متكرر 3 مرات (زي isEmphasisWord في الأصل) */
        fun isEmphasis(w: String): Boolean =
            Regex("[!！]$").containsMatchIn(w) || Regex("^[A-Z]{2,}$").matches(w) || Regex("(.)\\1{2,}").containsMatchIn(w)
        /** تكبير/تصغير تلقائي حسب عدد كلمات الجملة (زي sizeMult في الأصل) */
        fun sizeMult(wordCount: Int): Float = when { wordCount <= 2 -> 1.18f; wordCount <= 4 -> 1.08f; wordCount >= 14 -> 0.82f; wordCount >= 10 -> 0.9f; else -> 1f }

        /** ألوان سطور المتحدثين المتداخلين: لون الجنس أولًا، ولو اتكرر لون ياخد السطر لون تاني من الباليت عشان كل متحدث يبان لوحده */
        fun lineColors(subs: List<Sub>): List<Int> {
            // ألوان إضافية (من غير لون الجنس التاني) لو متحدثين من نفس الجنس: كل سطر لونه مختلف دايمًا
            val extra = listOf(0xFF80DEEA.toInt(), 0xFFB9F6CA.toInt(), 0xFFFFAB91.toInt(), 0xFFCE93D8.toInt())
            val used = HashSet<Int>(); val out = ArrayList<Int>()
            for (x in subs) {
                var c = if (x.gender == "female") FEMALE else MALE
                if (c in used) c = extra.firstOrNull { it !in used && it != MALE && it != FEMALE } ?: c
                used.add(c); out.add(c)
            }
            return out
        }
        /** تقسيم الجملة الطويلة لسطور متوازنة بحد أقصى كلمات للسطر */
        fun splitLong(text: String, thresh: Int): String {
            val w = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (thresh < 1 || w.size <= thresh || text.contains('\n')) return text
            val n = (w.size + thresh - 1) / thresh; val per = (w.size + n - 1) / n
            return w.chunked(per).joinToString("\n") { it.joinToString(" ") }
        }
        /** حجم الخط بالبكسل: clamp(13dp, 3.5vw, 18dp) × scale */
        fun fontPx(widthPx: Int, density: Float, scale: Int): Float {
            val v = (widthPx * 0.035f).coerceIn(13f * density, 18f * density)
            return v * scale / 100f
        }
        /** تأثير الانفعال: shout/surprise/cry/whisper، غير كده "" */
        fun emotionKind(raw: String): String { val e = raw.lowercase(); return when {
            e.contains("shout") || e.contains("anger") || e.contains("angry") -> "shout"
            e.contains("surpris") || e.contains("shock") -> "surprise"
            e.contains("cry") || e.contains("sad") || e.contains("sob") -> "cry"
            e.contains("whisper") -> "whisper"
            else -> ""
        } }
    }

    fun colorFor(s: Sub): Int = when {
        s.translated.startsWith("📻") -> WHITE   // كلام من راديو/إذاعة: أبيض عادي من غير لون جنس
        plain -> WHITE
        uniOn -> runCatching { Theme.parse(uniColor) }.getOrDefault(WHITE)
        s.gender == "female" -> FEMALE
        else -> MALE
    }
    /** وضع العرض (dual): 0 = ترجمة فقط، 1 = ترجمة فوق والأصلي تحت، 2 = الإنجليزي (pivot) تحت، 3 = الأصلي فقط، 4 = الأصلي فوق والترجمة تحت */
    fun secondary(s: Sub): String = when (dual) {
        1 -> if (s.original.isNotBlank() && s.original != s.translated) s.original else ""
        2 -> s.pivot
        4 -> if (s.translated.isNotBlank() && s.original != s.translated) s.translated else ""
        else -> ""
    }
    /** نص سطر واحد (لما أكتر من متحدث بيتكلموا مع بعض) حسب وضع العرض */
    fun lineText(s: Sub): String = if (dual >= 3) s.original.ifBlank { s.translated } else s.translated.ifBlank { s.original }
    /** حجم النص الثابت في الوضع العادي (plain)، والتكبير/التصغير التلقائي في الباقي */
    fun sizeFor(wordCount: Int): Float = if (plain) 1f else sizeMult(wordCount)
    fun mainText(s: Sub): String {
        // تقسيم الجمل الطويلة بقى بالتتابع في PlayerLogic.splitParts/partIndex (مش سطور فوق بعض)
        var t = if (dual >= 3) s.original.ifBlank { s.translated } else s.translated
        if (s.isContinuation) t += " ⋯"
        return t
    }
    /** مواضع الأسماء/الأماكن جوه النص: (start,end,isPlace) */
    fun highlights(text: String, s: Sub): List<Triple<Int, Int, Boolean>> {
        if (plain || dual >= 3) return emptyList()   // الأسماء متحددة على نص الترجمة بس
        val out = ArrayList<Triple<Int, Int, Boolean>>()
        fun scan(names: List<String>, place: Boolean) = names.filter { it.length > 1 }.forEach { n ->
            var i = text.indexOf(n)
            while (i >= 0) { out.add(Triple(i, i + n.length, place)); i = text.indexOf(n, i + n.length) }
        }
        scan(s.people, false); scan(s.places, true)
        return out
    }

    /** الخط الفعلي حسب النمط: "عامي" بيستخدم تشانجا */
    fun effectiveFont(): FontOpt {
        val id = if (fontStyle == "casual") "Changa" else font
        return SubStyle.fonts.firstOrNull { it.id == id } ?: SubStyle.fonts[1]
    }
    /** وزن الخط (wght) حسب النمط */
    fun weight(): Int = when (fontStyle) { "bold" -> 800; "slim" -> 500; "casual" -> 700; else -> effectiveFont().weight }
}
