package com.tttt.subtitler

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * مراقب الواجهة: لو الخيط الرئيسي وقف أكتر من ثانيتين بيسجّل في 📜 اللوجز «⛔ الواجهة واقفة» ومعاها مكان الوقوف بالظبط،
 * عشان نعرف سبب أي هنج أو شاشة «التطبيق لا يستجيب» من اللوج نفسه.
 */
object UiWatchdog {
    private val started = AtomicBoolean(false)
    @Volatile private var beat = System.currentTimeMillis()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val main = Looper.getMainLooper(); val h = Handler(main)
        val pending = AtomicBoolean(false)
        Thread {
            var reported = 0L; var lastLoop = System.currentTimeMillis()
            while (true) {
                if (pending.compareAndSet(false, true)) h.post { beat = System.currentTimeMillis(); pending.set(false) }
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                val now = System.currentTimeMillis()
                // (v150) لو اللفة نفسها اتأخرت كتير = العملية كلها اتجمّدت (أندرويد جمّد التطبيق في الخلفية): مش هنج حقيقي
                if (now - lastLoop > 3000) { lastLoop = now; beat = now; continue }
                lastLoop = now
                val stall = now - beat
                if (stall > 2000 && now - reported > 4000) {
                    reported = now
                    val st = try {
                        main.thread.stackTrace.take(14).joinToString(" ← ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
                    } catch (_: Throwable) { "؟" }
                    LogStore.add("⛔ الواجهة واقفة ${stall / 1000}ث · $st")
                }
            }
        }.apply { isDaemon = true; name = "ui-watchdog" }.start()
    }
}
