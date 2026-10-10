package com.tttt.subtitler
// stubs للاختبارات على JVM بس: الحاجات اللي محتاجة أندرويد/ONNX/ML Kit (مش جزء من منطق الترجمة)
object VoiceNet {
    fun init(c: android.content.Context) {}
    fun mode() = "light"
    fun useNet() = false
    fun dist(a: FloatArray, b: FloatArray) = 0.0
    fun norm(a: FloatArray) = a
    fun embed(pcm: ShortArray, a: Double, b: Double): FloatArray? = null
}
object VisualMode { fun anyActive() = false }
