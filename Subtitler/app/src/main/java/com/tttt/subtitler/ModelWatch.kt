package com.tttt.subtitler

import android.app.Activity

/**
 * (v141) فحص يومي — عند فتح التطبيق، مرة كل 24 ساعة: هل جوجل طلّعت موديل flash-lite أحدث من الموديل الحالي (الافتراضي 3.1)؟
 * لو أيوه: إشعار باسمه، وبيتعلّم 🆕 في «جلب كل الموديلات» وانت اللي بتختاره (الاختيار بيتحفظ فورًا).
 * مفيش أي تغيير تلقائي للموديل المستخدم.
 */
object ModelWatch {
    private const val DAY_MS = 24L * 3600 * 1000
    private val RX = Regex("^gemini-(\\d+)(?:\\.(\\d+))?-flash-lite")
    private val SKIP = listOf("tts", "image", "live", "audio", "embedding", "robotics")

    private fun ver(id: String): Pair<Int, Int>? {
        val m = RX.find(id) ?: return null
        return Pair(m.groupValues[1].toIntOrNull() ?: return null, m.groupValues[2].toIntOrNull() ?: 0)
    }
    private fun gt(a: Pair<Int, Int>, b: Pair<Int, Int>) = a.first > b.first || (a.first == b.first && a.second > b.second)

    /** الأساس اللي بنقارن بيه: 3.1 (المثبّت)، أو إصدار الموديل المختار حاليًا لو أحدث منه */
    private fun baseline(): Pair<Int, Int> {
        val pin = ver(Models.DEFAULT) ?: Pair(3, 1)
        val cur = ver(Cfg.str("model", Models.DEFAULT).trim())
        return if (cur != null && gt(cur, pin)) cur else pin
    }

    /** موديلات flash-lite أحدث من الأساس (من غير tts/image/live…) */
    fun candidates(ids: List<String>): List<String> {
        val b = baseline()
        return ids.filter { id -> SKIP.none { id.contains(it) } && (ver(id)?.let { gt(it, b) } == true) }.distinct().sorted()
    }

    /** اللي اتبلّغت بيه ولسه أحدث من الموديل الحالي */
    fun pending(): List<String> = candidates(Cfg.str("model_new").split(',').map { it.trim() }.filter { it.isNotEmpty() })

    /** بعد ما يتختار موديل: شيل اللي بقى مش أحدث منه */
    fun refreshPending() { Cfg.p.edit().putString("model_new", pending().joinToString(",")).apply() }

    fun maybeCheck(act: Activity) {
        val now = System.currentTimeMillis()
        if (now - Cfg.p.getLong("mw_last", 0L) < DAY_MS) return
        val c = Cfg.snapshot()
        val key = (c.keys + c.backup).firstOrNull { it.length > 10 } ?: return
        Thread {
            try {
                val rows = Api.listModels(key)
                Cfg.p.edit().putLong("mw_last", System.currentTimeMillis()).apply()   // بيتسجّل بس لو الفحص نجح؛ لو النت وقع نجرّب في الفتحة الجاية
                val seen = HashSet(Cfg.p.getStringSet("mw_seen", emptySet()) ?: emptySet())
                val fresh = candidates(rows.map { it.id }).filter { it !in seen }
                if (fresh.isNotEmpty()) {
                    seen.addAll(fresh)
                    val pend = (pending() + fresh).distinct()
                    Cfg.p.edit().putStringSet("mw_seen", seen).putString("model_new", pend.joinToString(",")).apply()
                    act.runOnUiThread {
                        if (!act.isFinishing && !act.isDestroyed)
                            Notice.show(act, "🆕 جوجل طلّعت موديل flash-lite أحدث من الحالي: " + fresh.joinToString("، ") + "\nالإعدادات ← الموديل ← «🔄 جلب كل الموديلات» وإختاره", 9000L)
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true }.start()
    }
}
