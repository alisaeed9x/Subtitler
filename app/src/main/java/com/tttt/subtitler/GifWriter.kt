package com.tttt.subtitler

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * (v135) كاتب GIF بدون أي مكتبة: باليتة ثابتة 252 لون (6×7×6) + dither خفيف (Bayer 4×4) + ضغط LZW.
 * نقي (من غير Android) عشان يتختبر على JVM: dev-tests/GifTest.kt
 * الاستخدام: GifWriter(out, w, h).also { it.addFrame(argbPixels, delayMs) ... it.finish() }
 */
class GifWriter(private val os: OutputStream, private val w: Int, private val h: Int, private val loop: Int = 0) {
    private var started = false
    private val idx = ByteArray(w * h)

    companion object {
        const val RL = 6; const val GL = 7; const val BL = 6
        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
        fun paletteBytes(): ByteArray {
            val p = ByteArray(256 * 3)
            for (r in 0 until RL) for (g in 0 until GL) for (b in 0 until BL) {
                val i = (r * GL + g) * BL + b
                p[i * 3] = (r * 255 / (RL - 1)).toByte(); p[i * 3 + 1] = (g * 255 / (GL - 1)).toByte(); p[i * 3 + 2] = (b * 255 / (BL - 1)).toByte()
            }
            return p
        }
    }

    private fun u16(v: Int) { os.write(v and 0xFF); os.write((v shr 8) and 0xFF) }

    private fun header() {
        os.write("GIF89a".toByteArray(Charsets.US_ASCII))
        u16(w); u16(h)
        os.write(0xF7)          // global palette, 256 colors
        os.write(0); os.write(0)
        os.write(paletteBytes())
        // Netscape loop extension
        os.write(0x21); os.write(0xFF); os.write(11); os.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        os.write(3); os.write(1); u16(loop); os.write(0)
        started = true
    }

    private fun quant(px: IntArray) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = px[row + x]
                val t = BAYER[(y and 3) * 4 + (x and 3)] - 8   // -8..7
                var r = (c shr 16) and 0xFF; var g = (c shr 8) and 0xFF; var b = c and 0xFF
                r = (r + t * 255 / (RL - 1) / 16).coerceIn(0, 255)
                g = (g + t * 255 / (GL - 1) / 16).coerceIn(0, 255)
                b = (b + t * 255 / (BL - 1) / 16).coerceIn(0, 255)
                val ri = (r * (RL - 1) + 127) / 255; val gi = (g * (GL - 1) + 127) / 255; val bi = (b * (BL - 1) + 127) / 255
                idx[row + x] = ((ri * GL + gi) * BL + bi).toByte()
            }
        }
    }

    /** بيضيف فريم؛ pixels = ARGB بطول w*h */
    fun addFrame(pixels: IntArray, delayMs: Int) {
        require(pixels.size >= w * h) { "pixels too small" }
        if (!started) header()
        quant(pixels)
        // Graphic control extension
        os.write(0x21); os.write(0xF9); os.write(4); os.write(0x00); u16(maxOf(2, delayMs / 10)); os.write(0); os.write(0)
        // Image descriptor
        os.write(0x2C); u16(0); u16(0); u16(w); u16(h); os.write(0)
        lzw()
    }

    fun finish() { if (!started) header(); os.write(0x3B); os.flush() }

    // ===== LZW =====
    private var blk = ByteArray(255); private var blkN = 0
    private var cur = 0; private var curBits = 0

    private fun flushBlock() { if (blkN > 0) { os.write(blkN); os.write(blk, 0, blkN); blkN = 0 } }
    private fun putByte(b: Int) { blk[blkN++] = b.toByte(); if (blkN == 255) flushBlock() }
    private fun emit(code: Int, size: Int) {
        cur = cur or (code shl curBits); curBits += size
        while (curBits >= 8) { putByte(cur and 0xFF); cur = cur ushr 8; curBits -= 8 }
    }

    private fun lzw() {
        val minCode = 8; os.write(minCode)
        val clear = 1 shl minCode; val eoi = clear + 1
        val keys = IntArray(8192); val vals = IntArray(8192)
        java.util.Arrays.fill(keys, -1)
        var codeSize = minCode + 1; var next = eoi + 1
        cur = 0; curBits = 0; blkN = 0
        emit(clear, codeSize)
        var prefix = idx[0].toInt() and 0xFF
        for (i in 1 until w * h) {
            val c = idx[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            var slot = ((key * -1640531535) ushr 19) and 8191
            var found = -1
            while (keys[slot] != -1) { if (keys[slot] == key) { found = vals[slot]; break }; slot = (slot + 1) and 8191 }
            if (found >= 0) { prefix = found; continue }
            emit(prefix, codeSize)
            if (next < 4096) {
                keys[slot] = key; vals[slot] = next
                if (next == (1 shl codeSize)) codeSize++
                next++
            } else {
                emit(clear, codeSize)
                java.util.Arrays.fill(keys, -1); codeSize = minCode + 1; next = eoi + 1
            }
            prefix = c
        }
        emit(prefix, codeSize)
        // العداد عند eoi: لازم نفس منطق الزيادة لو الجدول كبر بعد آخر emit
        emit(eoi, codeSize)
        if (curBits > 0) { putByte(cur and 0xFF); cur = 0; curBits = 0 }
        flushBlock(); os.write(0)
    }
}
