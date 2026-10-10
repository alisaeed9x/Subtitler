package com.tttt.subtitler

/** منطق نقي لقفل النمط (بيتختبر على JVM): هاش بملح، وتأخير بعد المحاولات الغلط */
object LockCore {
    const val MIN_DOTS = 4
    fun hash(salt: String, pattern: List<Int>): String =
        java.security.MessageDigest.getInstance("SHA-256").digest((salt + ":" + pattern.joinToString("-")).toByteArray()).joinToString("") { "%02x".format(it) }
    fun newSalt(): String { val a = ByteArray(16); java.security.SecureRandom().nextBytes(a); return a.joinToString("") { "%02x".format(it) } }
    /** بعد 5 محاولات غلط: 30 ثانية، وبتتضاعف مع كل محاولة غلط زيادة (الحد الأقصى 10 دقايق) */
    fun lockoutMs(fails: Int): Long = if (fails < 5) 0L else minOf(30_000L shl (fails - 5).coerceAtMost(5), 600_000L)
    /** لو عدّيت على نقطة ما بين نقطتين (مثلًا 0 → 2) النقطة اللي في النص (1) بتتحسب */
    fun between(a: Int, b: Int): Int? {
        val r1 = a / 3; val c1 = a % 3; val r2 = b / 3; val c2 = b % 3
        return if ((r1 - r2) % 2 == 0 && (c1 - c2) % 2 == 0 && (r1 != r2 || c1 != c2)) ((r1 + r2) / 2) * 3 + (c1 + c2) / 2 else null
    }
}
