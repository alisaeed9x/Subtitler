package com.tttt.subtitler

/**
 * (v162) إعدادات الميزات الجديدة — بتتقرا من Cfg مرة واحدة وتتخزن هنا، عشان حلقة الـ 200ms في المشغّل
 * ومحلّل الردود (Parse) ماتقراش SharedPreferences كل شوية. ملف نقي (من غير Android) عشان اختبارات JVM.
 */
object Extras {
    /** وضع الصم: أوصاف الأحداث الصوتية [باب بيتفتح] بتتطلب من الموديل وبتظهر فوق الفيديو */
    @Volatile var deaf = false
    /** (v179) اسم المتكلم قبل الجملة: «هاوس: الكلام» */
    @Volatile var spkNames = true
    /** فلتر المشاهد الحساسة: 0 مقفول · 1 زرار تخطي · 2 تخطي تلقائي */
    @Volatile var sceneMode = 0
    @Volatile var sceneViolence = true
    @Volatile var sceneIntimate = true
    @Volatile var sceneScary = false
    /** الترجمة تتحرك فوق لو تحت فيه وش/نص */
    @Volatile var smartPos = false
    /** تغطية الهاردساب القديم: 0 مقفول · 1 صندوق غامق · 2 صندوق شفاف */
    @Volatile var coverMode = 0

    fun load() {
        try {
            deaf = Cfg.bool("fx_deaf", false)
            spkNames = Cfg.bool("fx_spk", true)
            sceneMode = Cfg.int("fx_scene", 0).coerceIn(0, 2)
            sceneViolence = Cfg.bool("fx_s_viol", true)
            sceneIntimate = Cfg.bool("fx_s_int", true)
            sceneScary = Cfg.bool("fx_s_scary", false)
            smartPos = Cfg.bool("fx_smartpos", false)
            coverMode = Cfg.int("fx_cover", 0).coerceIn(0, 2)
        } catch (_: Throwable) { /* Cfg لسه ماتفتحتش (اختبارات JVM) */ }
    }

    /** بلوك الـ prompt بتاع وصف الأصوات (بيتضاف بس لو وضع الصم شغّال) */
    const val SFX_BLOCK = "\n═══ وصف الأحداث الصوتية (وضع الصم وضعاف السمع) ═══\n" +
        "- 🔴 زوّد في نفس مصفوفة subtitles عناصر مستقلة للأحداث الصوتية المهمة اللي بتتسمع ومش كلام: خبط على باب، باب بيتفتح أو بيتقفل، تليفون بيرن، طلقة نار، انفجار، زجاج بيتكسر، خطوات بتقرب، سيارة، صريخ، ضحك جماعي، تصفيق، بكاء، صفارة إنذار، رعد، وموسيقى مؤثرة (زي «موسيقى حزينة»).\n" +
        "- كل حدث = عنصر: is_sound=true، original = وصف قصير جدًا (مش فاضي)، translated = الوصف بالعربي بنفس اللهجة المطلوبة وبين أقواس مربعة زي [باب بيتفتح] أو [طلقة نار] أو [تليفون بيرن]، و start/end لحظة الحدث (مدة العرض ثانية ونص على الأقل).\n" +
        "- ماتوصفش صوت مستمر في الخلفية (هوا، ضوضاء شارع عادية، موسيقى خلفية هادية) إلا لو بيغيّر معنى المشهد، وماتكررش نفس الوصف قبل 4 ثواني، وماتوصفش الكلام نفسه ولا همس الشخصيات.\n" +
        "- الأوصاف دي مش جزء من الحوار: ماتأثرش على جنس المتكلم ولا الأسماء ولا أي عنصر تاني.\n"
}
