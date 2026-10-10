import com.tttt.subtitler.*
import java.io.File
import org.json.JSONObject
var okN = 0; var bad = 0
fun chk(n: String, c: Boolean) { if (c) { okN++; println("PASS $n") } else { bad++; println("FAIL $n") } }
fun main() {
    // 1) is_sound صريح
    val a = Parse.sub(JSONObject("""{"start":1.0,"end":3.0,"original":"[humming]","translated":"[همهمة]","is_sound":true}"""), 0.0)
    chk("is_sound=true بيتقرا", a.isSound)
    // 2) بدون is_sound لكن الترجمة بين أقواس مربعة
    val b = Parse.sub(JSONObject("""{"start":1.0,"end":3.0,"original":"[music]","translated":"[موسيقى هادية]"}"""), 0.0)
    chk("[وصف] بدون علامة بيتعرف كصوت", b.isSound)
    // 3) حوار عادي مش صوت
    val c = Parse.sub(JSONObject("""{"start":1.0,"end":3.0,"original":"hello","translated":"أهلا يا صاحبي"}"""), 0.0)
    chk("حوار عادي مش صوت", !c.isSound)
    // 4) الدمج مابيلزقش صوت في حوار
    val m = Subs.merge(listOf(c.copy(start = 0.0, end = 1.0), a.copy(start = 1.05, end = 1.5), c.copy(start = 1.6, end = 2.0)))
    chk("الأصوات مابتتدمجش مع الحوار", m.count { it.isSound } == 1 && m.size == 3)
    // 5) LangGuard مايعتبرش الصوت أجنبي
    chk("LangGuard يتجاهل الصوت", !LangGuard.foreign(a.copy(translated = "[humming]")))
    // 6) prompt: كتلة الأصوات بتتحط بس لو الإعداد شغال
    val pb = PromptBuilder { File(System.getenv("SUBTITLER_ASSETS") ?: "app/src/main/assets", it).readText(Charsets.UTF_8) }
    val on = Conf(listOf("k"), emptyList(), "m", "مصري", "حرفي", 60, 3, 1, emptyList(), "", true, true, true, soundTags = true)
    val off = on.let { Conf(it.keys, it.backup, it.model, it.lang, it.style, 60, 3, 1, emptyList(), "", true, true, true, soundTags = false) }
    chk("prompt فيه كتلة الأصوات لما شغال", pb.build(on, "", false, 60.0, "", emptyList(), emptyList()).contains("is_sound"))
    chk("prompt من غير كتلة الأصوات لما مقفول", !pb.build(off, "", false, 60.0, "", emptyList(), emptyList()).contains("is_sound"))
    // 7) الحفظ والتحميل
    val dir = File(System.getProperty("java.io.tmpdir"), "sndtest" + System.nanoTime()); dir.mkdirs()
    val st = Store(dir, "k")
    st.save(Saved(60, "", false, listOf(a, c), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), 0.0, 0))
    val back = st.load()?.subs ?: emptyList()
    chk("isSound بيتحفظ ويرجع", back.size == 2 && back[0].isSound && !back[1].isSound)
    println("الاختبارات: $okN نجح، $bad فشل")
    System.exit(if (bad == 0) 0 else 1)
}
