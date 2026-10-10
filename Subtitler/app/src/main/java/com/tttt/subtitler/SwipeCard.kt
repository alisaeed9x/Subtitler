package com.tttt.subtitler

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * (v200) كارت تبويب بيتقفل بالسحب يمين/شمال (زي كروم) من غير ما تدوس ✕.
 *  • السحب الأفقي بس هو اللي بيتحسب — السكرول الرأسي والضغطة العادية والـ ✕ شغالين زي ما هما
 *  • بعد ~35% من العرض أو حركة سريعة (fling) الكارت بيطير ويتقفل، غير كده بيرجع مكانه
 */
class SwipeCard(ctx: Context, private val onDismiss: () -> Unit) : LinearLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity * 4f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var gone = false
    private var vt: VelocityTracker? = null

    private fun reset() { vt?.recycle(); vt = null; dragging = false }

    /** بيتنادى من الاتنين (intercept لو فيه ابن واخد اللمسة · onTouch لو الكارت نفسه واخدها) */
    private fun track(e: MotionEvent): Boolean {
        if (gone) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                downX = e.rawX; downY = e.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.4f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)   // السكرول الرأسي يسيبنا
                    isPressed = false
                }
                if (dragging) {
                    translationX = dx
                    alpha = 1f - (abs(dx) / (width.coerceAtLeast(1))).coerceIn(0f, 1f) * 0.75f
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    val dx = translationX
                    val far = abs(dx) > width * 0.35f
                    val fling = abs(vx) > minFling && vx * dx > 0f && abs(dx) > slop * 3
                    if (e.actionMasked == MotionEvent.ACTION_UP && (far || fling)) fly(if (dx > 0f) 1f else -1f)
                    else animate().translationX(0f).alpha(1f).setDuration(160L).start()
                    reset(); return true
                }
                reset()
            }
        }
        return dragging
    }

    private fun fly(dir: Float) {
        gone = true
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        animate().translationX(dir * width * 1.15f).alpha(0f).setDuration(150L).withEndAction { onDismiss() }.start()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = track(e)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val was = dragging
        val consumed = track(e)
        return if (was || consumed) true else super.onTouchEvent(e)
    }
}
