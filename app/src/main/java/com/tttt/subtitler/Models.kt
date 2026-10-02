package com.tttt.subtitler

// موديلات الأصل (BUILTIN_MODELS) + الكوتة اليومية (QUOTA_LIMITS) — مستخرجة من الـ HTML
class ModelInfo(val id: String, val desc: String)
object Models {
    val builtin = listOf(
        ModelInfo("gemini-flash-lite-latest", "أحدث flash-lite تلقائي"),
        ModelInfo("gemini-3.1-flash-lite", "أعلى كوتة مجانية"),
        ModelInfo("gemini-2.5-flash-lite", "خفيف، كوتة عالية"),
        ModelInfo("gemini-3-flash", "سريع وذكي"),
        ModelInfo("gemini-2.5-flash", "متوازن ومستقر"),
        ModelInfo("gemini-3.5-flash", "الأقوى والأحدث"),
        ModelInfo("gemini-2.5-pro", "قوي، كوتة قليلة"))
    val quota = mapOf("gemini-3.1-flash-lite" to 1000, "gemini-2.5-flash-lite" to 1500, "gemini-3-flash" to 1500,
        "gemini-2.5-flash" to 1500, "gemini-3.5-flash" to 250, "gemini-2.5-pro" to 50)
    const val QUOTA_DEFAULT = 500
    fun quotaOf(id: String) = quota[id] ?: QUOTA_DEFAULT
}

