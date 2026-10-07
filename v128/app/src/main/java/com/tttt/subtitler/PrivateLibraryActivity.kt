package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import com.bumptech.glide.Glide
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

/** شاشة المكتبة: نفس شكل v125، لكن مصدر البيانات أصبح API التطبيق القديم Anime Witcher نفسه. */
class PrivateLibraryActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private val ex = Executors.newCachedThreadPool()

    private var home: WitcherApi.Home? = null
    private var banners: List<WitcherApi.Series> = emptyList()
    private var bannerPos = 0
    private var currentItem: WitcherApi.ItemDetails? = null

    private val bannerRun = object : Runnable {
        override fun run() {
            if (banners.size > 1) {
                bannerPos = (bannerPos + 1) % banners.size
                renderHome()
            }
            h.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildShell()
        loadHome()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        ex.shutdownNow()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(text: String, size: Float, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

    private fun bg(color: Int, radius: Int = 16) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun buildShell() {
        window.statusBarColor = Color.rgb(35, 35, 35)
        window.navigationBarColor = Color.rgb(20, 20, 20)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 20, 20))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(Color.rgb(43, 43, 45))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val menu = tv("☰", 30).apply { gravity = Gravity.CENTER; setOnClickListener { finish() } }
        val title = tv("الأنمي", 22, true).apply { gravity = Gravity.CENTER }
        val search = tv("⌕", 34).apply { gravity = Gravity.CENTER; setOnClickListener { showSearch() } }
        bar.addView(menu, LinearLayout.LayoutParams(dp(52), dp(56)))
        bar.addView(title, LinearLayout.LayoutParams(0, dp(56), 1f))
        bar.addView(search, LinearLayout.LayoutParams(dp(52), dp(56)))

        root.addView(bar)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val sv = ScrollView(this)
        sv.addView(content)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        // (v129) نفس شريط التنقل السفلي الموحد: الفيديوهات · الأنمي · المتصفح
        val th = Themes.byId(Cfg.str("theme", "mx"))
        val ui = Ui(this, th)
        val gates = BottomNav(this, ui, th, 1) { i ->
            when (i) {
                0 -> { startActivity(Intent(this, Main::class.java)); finish() }
                2 -> { startActivity(Intent(this, BrowserActivity::class.java)); finish() }
            }
        }
        root.addView(gates.view, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun loadHome() {
        showLoading()
        ex.submit {
            try {
                val result = WitcherApi.home()
                runOnUiThread {
                    home = result
                    // Carousel has real covers from the old backend. Use latest titles
                    // as banners because the old backend does not expose a single
                    // separate banner field in the app's public API.
                    banners = result.latest.take(8)
                    renderHome()
                    h.removeCallbacks(bannerRun)
                    h.postDelayed(bannerRun, 4000)
                }
            } catch (e: Throwable) {
                LogStore.err("WitcherHome", e)
                runOnUiThread { showError() }
            }
        }
    }

    private fun showLoading() {
        content.removeAllViews()
        repeat(5) {
            val v = View(this).apply { background = bg(0xff303030.toInt(), 14) }
            content.addView(v, LinearLayout.LayoutParams(-1, dp(130)).also { it.setMargins(0, 0, 0, dp(14)) })
        }
    }

    private fun showError() {
        content.removeAllViews()
        content.addView(
            tv("تعذر تحميل مكتبة Anime Witcher القديمة", 20, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, dp(90))
        )
        content.addView(Button(this).apply { text = "إعادة المحاولة"; setOnClickListener { loadHome() } }, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun renderHome() {
        val h0 = home ?: return
        content.removeAllViews()

        if (banners.isNotEmpty()) {
            val b = banners[bannerPos % banners.size]
            val card = FrameLayout(this).apply { background = bg(0xff222222.toInt(), 24); clipToOutline = true }
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val src = b.cover.ifBlank { b.poster }
            if (src.isNotBlank()) Glide.with(this).load(src).into(image)
            card.addView(image, FrameLayout.LayoutParams(-1, dp(250)))
            val shade = View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00141414, 0xEE141414.toInt())
                )
            }
            card.addView(shade, FrameLayout.LayoutParams(-1, dp(250)))
            card.addView(
                tv(b.name, 21, true).apply { setPadding(dp(18), 0, dp(18), dp(18)); gravity = Gravity.BOTTOM },
                FrameLayout.LayoutParams(-1, dp(250))
            )
            content.addView(card, LinearLayout.LayoutParams(-1, dp(250)))
        }

        if (banners.size > 1) {
            val dots = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(14)) }
            banners.forEachIndexed { i, _ ->
                val active = i == bannerPos % banners.size
                val d = tv(if (active) "━" else "•", if (active) 22f else 17f).apply {
                    gravity = Gravity.CENTER
                    setTextColor(if (active) 0xffffd400.toInt() else Color.WHITE)
                }
                dots.addView(d, LinearLayout.LayoutParams(dp(24), dp(28)))
            }
            content.addView(dots)
        }

        val newEpisodes = h0.newEpisodes
        if (newEpisodes.isNotEmpty()) {
            val cards = newEpisodes.map { (s, e) ->
                LibraryCard(s.id, s.name, s.poster.ifBlank { s.cover }, e.number, e.thumb, 0, "").also { it.episodeNumber = e.number }
            }
            section("حلقات جديدة", cards, true)
        }

        val latestCards = h0.latest.map { s ->
            LibraryCard(s.id, s.name, s.poster.ifBlank { s.cover }, 0, "", s.year, "")
        }
        section("آخر الأعمال المضافة", latestCards, false)
    }

    private data class LibraryCard(
        val id: String,
        val title: String,
        val cover: String,
        var episodeNumber: Int,
        val thumb: String,
        val year: Int,
        val added: String
    )

    private fun section(title: String, list: List<LibraryCard>, episodes: Boolean) {
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        head.addView(tv(title, 21, true), LinearLayout.LayoutParams(0, dp(48), 1f))
        head.addView(tv("عرض المزيد", 14).apply { setTextColor(0xffbbbbbb.toInt()) }, LinearLayout.LayoutParams(-2, dp(48)))
        content.addView(head)

        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        list.forEach { card ->
            row.addView(
                cardView(card, episodes),
                LinearLayout.LayoutParams(if (episodes) dp(190) else dp(160), if (episodes) dp(220) else dp(205)).also {
                    it.setMargins(dp(6), 0, dp(6), dp(12))
                }
            )
        }
        hs.addView(row)
        content.addView(hs)
    }

    private fun cardView(x: LibraryCard, episodes: Boolean): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fr = FrameLayout(this).apply { background = bg(0xff292929.toInt(), 14); clipToOutline = true }
        val im = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val src = if (episodes && x.thumb.isNotBlank()) x.thumb else x.cover
        if (src.isNotBlank()) Glide.with(this).load(src).into(im)
        fr.addView(im, FrameLayout.LayoutParams(-1, dp(if (episodes) 155 else 165)))
        if (episodes) {
            fr.addView(
                tv("الحلقة ${x.episodeNumber}", 12, true).apply {
                    setTextColor(Color.BLACK)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = bg(0xffffd400.toInt(), 12)
                },
                FrameLayout.LayoutParams(-2, dp(32), Gravity.BOTTOM or Gravity.END).apply {
                    bottomMargin = dp(6); marginEnd = dp(6)
                }
            )
        }
        col.addView(fr)
        col.addView(tv(x.title, 14, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(2, dp(5), 2, 0) })
        col.addView(tv(if (episodes) "من المصدر القديم" else if (x.year > 0) x.year.toString() else "", 12).apply { setTextColor(0xffaaaaaa.toInt()) })
        col.setOnClickListener {
            if (episodes) playEpisode(x.id, x.episodeNumber, x.title)
            else openItem(x.id)
        }
        return col
    }

    private fun openItem(id: String) {
        showLoading()
        ex.submit {
            try {
                val details = WitcherApi.getAnime(id)
                runOnUiThread { currentItem = details; showDetails(details) }
            } catch (e: Throwable) {
                LogStore.err("WitcherItem", e)
                runOnUiThread { showError() }
            }
        }
    }

    private fun showDetails(details: WitcherApi.ItemDetails) {
        content.removeAllViews()
        val s = details.series
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(0xff292929.toInt(), 18) }
        val src = s.cover.ifBlank { s.poster }
        if (src.isNotBlank()) Glide.with(this).load(src).into(img)
        content.addView(img, LinearLayout.LayoutParams(-1, dp(230)))
        content.addView(tv(s.name, 24, true), LinearLayout.LayoutParams(-1, dp(55)))
        if (s.year > 0) content.addView(tv("السنة: ${s.year}", 13).apply { setTextColor(0xffaaaaaa.toInt()) })
        if (s.story.isNotBlank()) {
            content.addView(tv(s.story, 14).apply { setTextColor(0xffcccccc.toInt()); setPadding(0, dp(10), 0, dp(14)) })
        }
        content.addView(tv("الحلقات (${details.episodes.size})", 20, true), LinearLayout.LayoutParams(-1, dp(55)))
        for (ep in details.episodes) {
            val b = Button(this).apply {
                text = "الحلقة ${ep.number} — ${ep.name}"
                setOnClickListener { playEpisode(s.id, ep.number, s.name) }
            }
            content.addView(b, LinearLayout.LayoutParams(-1, dp(50)).also { it.topMargin = dp(6) })
        }
    }

    private fun playEpisode(animeId: String, episodeNumber: Int, title: String) {
        toast("جاري جلب سيرفر الحلقة ${episodeNumber}…")
        ex.submit {
            try {
                val server = WitcherApi.resolvePlayable(animeId, episodeNumber)
                    ?: throw IllegalStateException("No playable server")
                runOnUiThread {
                    // مهم: لا نستخدم مشغل Anime Witcher الخاص.
                    // افتح نفس رابط السيرفر داخل المتصفح المدمج في Subtitler،
                    // وهو المتصفح الذي يلتقط الفيديو ويتيح فتحه بمشغل/ترجمة Subtitler عند الحاجة.
                    startActivity(Intent(this, BrowserActivity::class.java).apply {
                        putExtra("start", server.link)
                        putExtra("noauto", false)
                    })
                }
            } catch (e: Throwable) {
                LogStore.err("WitcherPlay", e)
                runOnUiThread { toast("تعذر الحصول على رابط تشغيل مباشر من سيرفرات Witcher القديمة") }
            }
        }
    }

    private fun showSearch() {
        val e = EditText(this).apply { hint = "ابحث في Anime Witcher"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("بحث")
            .setView(e)
            .setPositiveButton("بحث") { _, _ -> search(e.text.toString()) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun search(q: String) {
        if (q.isBlank()) return
        showLoading()
        ex.submit {
            try {
                val list = WitcherApi.search(q)
                runOnUiThread {
                    content.removeAllViews()
                    if (list.isEmpty()) {
                        content.addView(tv("لا توجد نتائج", 20, true).apply { gravity = Gravity.CENTER })
                    } else {
                        list.forEach { s ->
                            val c = LibraryCard(s.id, s.name, s.poster.ifBlank { s.cover }, 0, "", s.year, "")
                            content.addView(cardView(c, false), LinearLayout.LayoutParams(-1, dp(205)).also { it.setMargins(0, 0, 0, dp(10)) })
                        }
                    }
                }
            } catch (e: Throwable) {
                LogStore.err("WitcherSearch", e)
                runOnUiThread { showError() }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
