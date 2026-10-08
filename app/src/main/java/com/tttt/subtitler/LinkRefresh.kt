package com.tttt.subtitler

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONTokener

/**
 * تحديث لينك التحميل في الخلفية: بيفتح صفحة الفيديو في WebView مخفي، ويلقط روابط الفيديو اللي بتتطلب،
 * ولو لقى نفس الرابط (نفس المسار من غير التوكن) بيرجّعه بالتوكن الجديد. غير كده بيرجّع null أو رابط مختلف (same=false).
 */
object LinkRefresh {
    class Res(val url: String, val same: Boolean, val cookie: String, val ua: String, val all: List<String> = emptyList())

    // (v142) بيقرا كمان روابط <a> وأي رابط فيديو جوه كود الصفحة (صفحات التحميل بتحط اللينكات هناك)
    private val SCAN_JS = """(function(){var o=[];function a(x){try{if(x)o.push(new URL(x,location.href).href)}catch(e){}}
document.querySelectorAll('video').forEach(function(v){try{v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){})}catch(e){}});
document.querySelectorAll('video,source').forEach(function(v){a(v.currentSrc);a(v.src)});
document.querySelectorAll('meta[property^="og:video"]').forEach(function(m){a(m.content)});
document.querySelectorAll('a[href]').forEach(function(l){if(/\.(mp4|m3u8|webm|mkv|mov|m4v)([?#]|$)/i.test(l.href))a(l.href)});
try{var h=document.documentElement.innerHTML.split('\\/').join('/');var m=h.match(/https?:\/\/[^"'\s<>\\]+?\.(?:mp4|m3u8|webm)(?:\?[^"'\s<>\\]*)?/gi);if(m)m.slice(0,60).forEach(a)}catch(e){}
return JSON.stringify(o)})()"""

    @SuppressLint("SetJavaScriptEnabled")
    fun run(act: Activity, page: String, oldUrl: String, ua: String, timeoutMs: Long = 25000L, onDone: (Res?) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        val found = ArrayList<String>()
        val oldKey = Sniff.key(oldUrl)
        val oldSkel = Sniff.skeleton(oldUrl); val oldH = Sniff.heightOf(oldUrl)
        val oldStem = Sniff.nameOf(oldSkel).lowercase()
        val stemOk = oldStem.length > 8 && !Regex("(master|index|playlist|chunklist|manifest|stream|video|file|source)\\.(m3u8|mp4|mpd|webm)").matches(oldStem)
        var soft = false
        var done = false
        val wv = try { WebView(act) } catch (_: Throwable) { onDone(null); return }
        WebMute.register(wv); WebMute.mute(wv)
        val host = try { act.findViewById<ViewGroup>(android.R.id.content) } catch (_: Throwable) { null }

        fun finish() {
            if (done) return; done = true
            h.removeCallbacksAndMessages(null)
            // (v142) نفس الفيديو = نفس اللينك، أو نفس الهيكل بجودة تانية (الأقرب لجودتنا)، أو نفس اسم الملف من غير رقم الجودة
            fun dist(u: String) = Math.abs(Sniff.heightOf(u) - oldH)
            val same = found.firstOrNull { Sniff.key(it) == oldKey }
                ?: found.filter { Sniff.skeleton(it) == oldSkel }.minByOrNull { dist(it) }
                ?: (if (stemOk) found.filter { Sniff.nameOf(Sniff.skeleton(it)).lowercase() == oldStem }.minByOrNull { dist(it) } else null)
            val pick = same ?: found.firstOrNull()
            val ck = try { CookieManager.getInstance().getCookie(pick ?: page) ?: "" } catch (_: Throwable) { "" }
            val agent = try { wv.settings.userAgentString ?: "" } catch (_: Throwable) { "" }
            try { host?.removeView(wv) } catch (_: Throwable) {}
            try { wv.stopLoading(); wv.destroy() } catch (_: Throwable) {}
            LogStore.add("🔄 تحديث اللينك: لقيت ${found.size} رابط · " + (if (same != null) "نفس الفيديو ✓" else if (pick != null) "رابط مختلف" else "ولا رابط (مهلة/صفحة محمية)") + " · الصفحة: " + page.take(80))
            onDone(if (pick == null) null else Res(pick, same != null, ck, agent, ArrayList(found)))
        }
        fun add(raw: String) {
            val u = Sniff.accept(raw) ?: return
            if (found.contains(u)) return
            found.add(u)
            if (Sniff.key(u) == oldKey) h.postDelayed({ finish() }, 500)   // لقينا نفس الفيديو: مفيش داعي نستنى أكتر
            else if (!soft && (Sniff.skeleton(u) == oldSkel)) { soft = true; h.postDelayed({ finish() }, 6000) }   // نفس الفيديو بجودة تانية: نستنى شوية لو الجودة بتاعتنا لسه جاية
        }
        fun scan() {
            if (done) return
            try {
                wv.evaluateJavascript(SCAN_JS) { r ->
                    try {
                        val s = JSONTokener(r ?: "").nextValue() as? String
                        if (!s.isNullOrEmpty()) { val a = JSONArray(s); for (i in 0 until a.length()) add(a.getString(i)) }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
            h.postDelayed({ scan() }, 1800)
        }

        wv.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            if (ua.isNotEmpty()) userAgentString = ua
        }
        try { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Throwable) {}
        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (AdBlock.blocked(u)) return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                WebMute.mute(v); h.post { add(u) }
                return null
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = !r.url.toString().startsWith("http") || AdBlock.blocked(r.url.toString())
            override fun onPageStarted(v: WebView, url: String?, f: android.graphics.Bitmap?) { v.evaluateJavascript(AdBlock.JS, null); WebMute.mute(v) }
            override fun onPageFinished(v: WebView, url: String?) { v.evaluateJavascript(AdBlock.JS, null); WebMute.mute(v) }
        }
        // WebView لازم يكون متركّب في الشاشة عشان الفيديو يبدأ — بنحطه 1×1 شفاف
        try { wv.alpha = 0.01f; host?.addView(wv, FrameLayout.LayoutParams(2, 2)) } catch (_: Throwable) {}
        LogStore.add("🔄 تحديث اللينك: بفتح الصفحة في الخلفية — " + page.take(80))
        h.postDelayed({ finish() }, timeoutMs)
        wv.loadUrl(page)
        h.postDelayed({ scan() }, 1500)
    }
}
