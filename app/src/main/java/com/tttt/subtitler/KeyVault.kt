package com.tttt.subtitler

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * نسخة احتياطية مشفّرة وخفية من مفاتيح Gemini (أساسي / احتياطي / إضافي + أوضاعها) بتفضل موجودة بعد مسح التطبيق.
 * - المكان الأساسي: Android/media/<الحزمة>/ (مابيتمسحش مع المسح، ومابيحتاجش صلاحية)
 * - مكان تاني: مجلد مخفي .Subtitler في الذاكرة (لو صلاحية «كل الملفات» متاحة)
 * بيتحفظ لوحده عند أي تغيير في المفاتيح، وبيتسترجع لوحده لو التطبيق اتثبّت من جديد والمفاتيح فاضية.
 */
object KeyVault {
    private val FIELDS = listOf("keys", "backup", "extra", "keymodes")
    private val h = Handler(Looper.getMainLooper())
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var lastSaved = ""

    private fun hasKeys(): Boolean = Cfg.keys("keys").isNotEmpty() || Cfg.keys("backup").isNotEmpty() || Cfg.keys("extra").isNotEmpty()

    private fun secret(ctx: Context): SecretKeySpec =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(("sbt-vault-v1:" + ctx.packageName).toByteArray()), "AES")

    private fun files(ctx: Context): List<File> {
        val out = ArrayList<File>()
        try { ctx.externalMediaDirs?.firstOrNull { it != null }?.let { out.add(File(it, ".kv")) } } catch (_: Exception) {}
        try {
            val all = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else Build.VERSION.SDK_INT < 29
            if (all) out.add(File(Environment.getExternalStorageDirectory(), ".Subtitler/.kv"))
        } catch (_: Exception) {}
        return out
    }

    private fun enc(ctx: Context, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, secret(ctx), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun dec(ctx: Context, b64: String): String {
        val raw = Base64.decode(b64.trim(), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, secret(ctx), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    /** يحفظ المفاتيح الحالية. ما بيكتبش لو المفاتيح كلها فاضية (عشان ما يمسحش نسخة سليمة). */
    @Synchronized fun save(ctx: Context) {
        try {
            if (!hasKeys()) return
            val j = JSONObject(); for (f in FIELDS) j.put(f, Cfg.str(f))
            val s = j.toString(); if (s == lastSaved && files(ctx).all { it.exists() }) return
            val blob = enc(ctx, s)
            for (f in files(ctx)) try { f.parentFile?.mkdirs(); File(f.parentFile, ".nomedia").let { if (!it.exists()) it.createNewFile() }; f.writeText(blob) } catch (_: Exception) {}
            lastSaved = s
        } catch (_: Exception) {}
    }

    /** لو مفيش مفاتيح في الإعدادات: يدوّر على النسخة ويرجّعها كل واحد في مكانه (أساسي/احتياطي/إضافي/الأوضاع). بيرجع true لو استرجع. */
    @Synchronized fun restore(ctx: Context, gate: String = "kv_init"): Boolean {
        try {
            // مرة واحدة بس على كل تثبيت جديد (عشان لو مسحت المفاتيح بنفسك ما ترجعش لوحدها)
            if (Cfg.p.getBoolean(gate, false)) return false
            if (hasKeys()) { Cfg.p.edit().putBoolean(gate, true).apply(); return false }
            for (f in files(ctx)) {
                if (!f.isFile) continue
                val j = try { JSONObject(dec(ctx, f.readText())) } catch (_: Exception) { continue }
                val e = Cfg.p.edit(); var n = 0
                for (k in FIELDS) { val v = j.optString(k); if (v.isNotBlank()) { e.putString(k, v); n++ } }
                if (n > 0) { e.putBoolean(gate, true).apply(); lastSaved = ""; return hasKeys() }
            }
            // لو مفيش نسخة: العلامة بتتحط بس لما نكون فعلًا قدرنا نفحص كل الأماكن (صلاحية كل الملفات متاحة)
            if (gate == "kv_init" || files(ctx).size > 1) Cfg.p.edit().putBoolean(gate, true).apply()
        } catch (_: Exception) {}
        return false
    }

    /** بيرجّع المفاتيح لو لازم، وبعدين يراقب أي تغيير ويحفظ نسخة جديدة تلقائيًا */
    fun attach(ctx: Context) {
        val app = ctx.applicationContext
        Thread { restore(app); save(app) }.also { it.isDaemon = true }.start()
        if (listener != null) return
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k in FIELDS) { h.removeCallbacksAndMessages(null); h.postDelayed({ Thread { save(app) }.also { it.isDaemon = true }.start() }, 1500) }
        }
        listener = l; Cfg.p.registerOnSharedPreferenceChangeListener(l)
    }
}
