package com.tttt.subtitler

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

const val KEY_PAGE_URL = "https://aistudio.google.com/apikey"

/**
 * صفحة إنشاء مفتاح Gemini جوه البرنامج: بتفتح رابط المفاتيح مباشرة، وأول ما تنسخ المفتاح (AQ.… أو AIza…)
 * البرنامج بيلقطه من الكليبورد ويسلّمه لـ onKey (اللي بيحفظه) ويقفل الصفحة لوحده.
 * ملحوظة: جوجل ساعات بترفض تسجيل الدخول جوه WebView — لو حصل، زرار «Chrome» بيفتح نفس الصفحة في المتصفح والالتقاط بيشتغل برضو.
 */
@SuppressLint("SetJavaScriptEnabled")
fun Activity.showKeyBrowser(onKey: (String) -> Unit) {
    val th = Themes.byId(Cfg.str("theme", "mx")); val ui = Ui(this, th)
    val d = GDialog(this, android.R.style.Theme_Black_NoTitleBar).apply { plain = true }
    val wv = WebView(this); WebMute.register(wv)
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    var captured = false
    var inChrome = false

    fun capture(k: String) {
        if (captured) return
        captured = true
        try { onKey(k) } catch (_: Throwable) {}
        Notice.show(this, ("✓ لقطت المفتاح واتحفظ في البرنامج").toString(), 3600L)
        d.dismiss()
    }
    val listener = ClipboardManager.OnPrimaryClipChangedListener { clipboardKey()?.let { capture(it) } }
    cm.addPrimaryClipChangedListener(listener)

    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(th.card); setPadding(ui.dp(10), ui.dp(6), ui.dp(6), ui.dp(6))
    }
    bar.addView(ui.text("🔑 اعمل المفتاح وانسخه — هلقطه وأحفظه لوحدي", 13f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
    fun chip(t: String, f: () -> Unit) = IconTextView(this).apply {
        text = t; textSize = 13f; setTextColor(th.text); gravity = Gravity.CENTER; minHeight = ui.dp(44); setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7))
        background = ui.box(th.surface, th.border, 8); layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) }; setOnClickListener { f() }
    }
    bar.addView(chip("Chrome") {
        inChrome = true
        try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(KEY_PAGE_URL))) }
        catch (_: Exception) { Notice.show(this, ("مفيش متصفح").toString(), 2300L) }
    })
    bar.addView(chip("✕") { d.dismiss() })

    wv.settings.apply {
        javaScriptEnabled = true; domStorageEnabled = true; setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = true; loadWithOverviewMode = true; useWideViewPort = true
        // جوجل بتحجب تسجيل الدخول لو الـ User-Agent فيه علامة WebView (wv) — نخليه شبه متصفح عادي
        userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s"), "")
    }
    try { CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) } catch (_: Exception) {}
    wv.webViewClient = WebViewClient()
    wv.webChromeClient = WebChromeClient()
    wv.setBackgroundColor(Color.WHITE)

    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
    root.addView(bar, LinearLayout.LayoutParams(-1, -2))
    root.addView(wv, LinearLayout.LayoutParams(-1, 0, 1f))
    d.setContentView(root)
    d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    d.onBack = { if (wv.canGoBack()) wv.goBack() else d.dismiss(); true }
    // لو المستخدم راح Chrome ونسخ المفتاح هناك ورجع: نلقطه أول ما نرجع
    d.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { f -> if (f && inChrome) clipboardKey()?.let { capture(it) } }
    d.setOnDismissListener {
        try { cm.removePrimaryClipChangedListener(listener) } catch (_: Exception) {}
        try { wv.stopLoading(); (wv.parent as? ViewGroup)?.removeView(wv); wv.destroy() } catch (_: Exception) {}
    }
    d.show()
    wv.loadUrl(KEY_PAGE_URL)
}
