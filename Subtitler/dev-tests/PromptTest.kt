import com.tttt.subtitler.*
import java.io.File
fun main() {
    val pb = PromptBuilder { File(System.getenv("SUBTITLER_ASSETS") ?: "app/src/main/assets", it).readText(Charsets.UTF_8) }
    val langs = listOf("مصري","شامي","لبناني","خليجي","مغربي","عراقي","سوداني","فصحى"); val styles = listOf("حرفي","شعبي","جرئ","+18")
    var n = 0; var bad = 0
    val chars = listOf(Chr("منى","female","البطلة"), Chr("علي","male",""))
    val gloss = listOf(Gloss("سينسي","معلم"))
    for (l in langs) for (s in styles) for ((src, det) in listOf("" to false, "يابانية" to true, "إنجليزية" to true, "كورية" to true)) {
        val c = Conf(listOf("k"), emptyList(), "m", l, s, 60, 3, 1, emptyList(), "ليلى = Layla", false, true, true, true, true)
        for (tf in listOf<String?>(null, "TR §ROSTER§ END")) {
            val p = pb.build(c, src, det, 61.5, "- [متكلم:أنثى] مرحبا", chars, gloss, tf)
            n++
            val prob = listOf("§", "{{", "\u0001", "undefined", "[object").filter { p.contains(it) }
            if (prob.isNotEmpty() || !p.contains("61.5") || !p.contains("منى") || !p.contains("سينسي") || !p.contains("ليلى = Layla") || !p.contains("مرحبا")) { bad++; println("BAD $l|$s|$src tf=${tf != null}: $prob") }
        }
    }
    println("بنيت $n prompt — مشاكل: $bad")
    System.exit(if (bad == 0) 0 else 1)
}
