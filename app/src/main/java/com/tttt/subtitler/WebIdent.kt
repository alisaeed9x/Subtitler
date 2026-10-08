package com.tttt.subtitler

/**
 * (v142) هوية فيديو النت مستقلة عن اللينك والجودة.
 * قبل كده الهوية كانت من اللينك نفسه: نفس الفيديو بجودة تانية، أو اتصاد تاني من صفحة التحميل بلينك/توكن جديد = فيديو جديد من غير ترجمته.
 * دلوقتي كل فيديو ليه «مفاتيح تعريف» (الهيكل من غير رقم الجودة + الصفحة والعنوان)، وأي مفتاح اتعرف قبل كده بيوصّل لنفس الهوية القديمة
 * (فالترجمة والتقدم بيفضلوا)، والهوية القديمة بتفضل زي ما هي لأول مرة (من غير ما يضيع أي تقدم محفوظ).
 */
object WebIdent {
    class Hit(val id: String, val via: String?)   // via = المفتاح اللي وصّلنا لهوية قديمة (null لو الهوية اتحسبت من اللينك)

    private const val BLOCKED = "!"
    private val GENERIC = Regex("(master|index|playlist|chunklist|manifest|stream|video|play|media|file|main|source|hls|dash)(\\.(m3u8|mpd|mp4|webm|ts|php|m4v))?")
    private fun aliasKey(k: String) = "wa:" + Store.keyFor(k)

    fun pageKey(ref: String): String = ref.substringBefore('#').substringBefore('?').trim().trimEnd('/').lowercase()
    /** الصفحة محددة (مش اسم الموقع بس) */
    fun specific(ref: String): Boolean { val p = pageKey(ref); return p.startsWith("http") && p.length > 14 && p.substringAfter("://").contains('/') }

    fun keys(u: String, ref: String, title: String): List<String> {
        val out = ArrayList<String>()
        val file = u.substringBefore('#').substringBefore('?').substringAfterLast('/').lowercase()
        val generic = u.contains("videoplayback") || file.isBlank() || GENERIC.matches(file)
        if (!generic) out.add("s|" + Sniff.skeleton(u).lowercase())                  // نفس اللينك بجودة تانية
        val t = title.trim().lowercase()
        if (specific(ref) && t.isNotEmpty()) out.add("p|" + pageKey(ref) + "|" + t)  // نفس الصفحة ونفس العنوان (حتى لو اللينك/التوكن اتغيّر)
        return out
    }

    fun resolve(legacy: String, u: String, ref: String, title: String): Hit {
        val ks = keys(u, ref, title)
        var id: String? = null; var via: String? = null
        for (k in ks) { val c = Cfg.str(aliasKey(k)); if (c.isNotEmpty() && c != BLOCKED) { id = c; via = k; break } }
        val canon = id ?: legacy
        bind(canon, ks)
        return Hit(canon, via)
    }

    private fun bind(id: String, ks: List<String>) {
        val e = Cfg.p.edit(); var n = 0
        for (k in ks) if (Cfg.str(aliasKey(k)).isEmpty()) { e.putString(aliasKey(k), id); n++ }   // مفتاح اتعرّف قبل كده ما بيتغيّرش
        if (n > 0) e.apply()
    }
    fun bindLink(id: String, u: String, ref: String, title: String) = bind(id, keys(u, ref, title))
    /** المستخدم قال «ده فيديو تاني»: المفتاح ده ما يدمجش فيديوهات تاني */
    fun blockKey(k: String) { Cfg.p.edit().putString(aliasKey(k), BLOCKED).apply() }
}
