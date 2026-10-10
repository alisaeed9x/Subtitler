package com.tttt.subtitler

// ثيمات مستخرجة بالسكريبت من CSS البرنامج الأصلي (html[data-theme])
class Theme(val id: String, val name: String, val bg: Int, val surface: Int, val card: Int, val border: Int,
            val primary: Int, val accent: Int, val text: Int, val muted: Int, val danger: Int, val success: Int) {
    val isLight: Boolean get() = lum(bg) > 0.5
    companion object {
        fun lum(c: Int): Double { val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255; return (0.299*r + 0.587*g + 0.114*b) / 255.0 }
        fun parse(s: String): Int { var h = s.trim().removePrefix("#"); if (h.length == 3) h = h.map { "$it$it" }.joinToString(""); return (0xFF000000L or h.take(6).toLong(16)).toInt() }
    }
}

object Themes {
    val all: List<Theme> = listOf(
        // أبيض وأزرق زي MX Player — الثيم الأساسي
        Theme("mx", "MX أبيض وأزرق", 0xFFFFFFFF.toInt(), 0xFFF3F5F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFE3E8EF.toInt(), 0xFF2196F3.toInt(), 0xFF1565C0.toInt(), 0xFF1B1F27.toInt(), 0xFF6B7280.toInt(), 0xFFE53935.toInt(), 0xFF2E7D32.toInt()),
        Theme("default", "افتراضي (غرفة تحكم)", 0xFF0B0E11.toInt(), 0xFF12161B.toInt(), 0xFF1A1F26.toInt(), 0xFF262D36.toInt(), 0xFFF5A623.toInt(), 0xFF43C6D0.toInt(), 0xFFECEFF2.toInt(), 0xFF7C8591.toInt(), 0xFFE85C5C.toInt(), 0xFF4FD1A5.toInt()),
        Theme("amoled", "أموليد", 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF0A0A0A.toInt(), 0xFF1E1E1E.toInt(), 0xFFF5A623.toInt(), 0xFF43C6D0.toInt(), 0xFFECEFF2.toInt(), 0xFF7C8591.toInt(), 0xFFE85C5C.toInt(), 0xFF4FD1A5.toInt()),
        Theme("light", "فاتح", 0xFFF5F6F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFE1E4EA.toInt(), 0xFFE08E1D.toInt(), 0xFF1596A0.toInt(), 0xFF1B1F27.toInt(), 0xFF6B7280.toInt(), 0xFFD64545.toInt(), 0xFF1E9E75.toInt()),
        Theme("warm", "دافئ", 0xFFFBF4EC.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFF9F1.toInt(), 0xFFEBDCC9.toInt(), 0xFFD9772E.toInt(), 0xFFC0562F.toInt(), 0xFF2B221A.toInt(), 0xFF8A7A66.toInt(), 0xFFC6493F.toInt(), 0xFF3E9B6F.toInt()),
        Theme("sakura", "ساكورا", 0xFFFFF5F7.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFAFB.toInt(), 0xFFF3D9E1.toInt(), 0xFFE8628A.toInt(), 0xFF8E7CC3.toInt(), 0xFF2E1F26.toInt(), 0xFF8A6E78.toInt(), 0xFFD64545.toInt(), 0xFF1E9E75.toInt()),
        Theme("sky", "سماوي", 0xFFF3F8FC.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFDCE9F3.toInt(), 0xFF2E86D6.toInt(), 0xFF4FBFD9.toInt(), 0xFF1B2733.toInt(), 0xFF6C8299.toInt(), 0xFFD64545.toInt(), 0xFF1E9E75.toInt()),
        Theme("mint", "نعناع", 0xFFF2FAF6.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFD9EFE1.toInt(), 0xFF1E9E75.toInt(), 0xFF38B58F.toInt(), 0xFF1B2A22.toInt(), 0xFF6A8577.toInt(), 0xFFD64545.toInt(), 0xFF1E9E75.toInt()),
        Theme("sand", "رملي", 0xFFFAF6EE.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFDF8.toInt(), 0xFFEBE0C9.toInt(), 0xFFB8863A.toInt(), 0xFF8C7048.toInt(), 0xFF2B261B.toInt(), 0xFF857A63.toInt(), 0xFFC6493F.toInt(), 0xFF3E9B6F.toInt()),
        Theme("win11", "ويندوز 11", 0xFFF3F3F3.toInt(), 0xFFFBFBFB.toInt(), 0xFFFFFFFF.toInt(), 0xFFE5E5E5.toInt(), 0xFF0078D4.toInt(), 0xFF005FB8.toInt(), 0xFF1B1B1B.toInt(), 0xFF5D5D5D.toInt(), 0xFFC42B1C.toInt(), 0xFF107C10.toInt()),
        Theme("infinix", "إنفينكس", 0xFF121214.toInt(), 0xFF1B1B1F.toInt(), 0xFF232327.toInt(), 0xFF333338.toInt(), 0xFFFF5722.toInt(), 0xFFFFA000.toInt(), 0xFFF5F5F7.toInt(), 0xFF96969C.toInt(), 0xFFFF3B30.toInt(), 0xFF34C759.toInt()),
        Theme("winxp", "ويندوز XP", 0xFFECE9D8.toInt(), 0xFFECE9D8.toInt(), 0xFFFFFFFF.toInt(), 0xFF7F9DB9.toInt(), 0xFF2A66C8.toInt(), 0xFF3C9C3C.toInt(), 0xFF000000.toInt(), 0xFF4A4A4A.toInt(), 0xFFC83232.toInt(), 0xFF3C9C3C.toInt())
    )
    fun byId(id: String?): Theme = all.firstOrNull { it.id == id } ?: all[0]   // all[0] = mx
}
