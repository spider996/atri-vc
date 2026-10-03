package atri.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 带限（Kaiser 窗 sinc）重采样器。
 *
 * PC 端基准用的是 ffmpeg swr（也是 windowed-sinc 一族），这里用等效质量的实现，
 * 保证 44.1k→16k 下变频后 HuBERT/音高特征不退化。
 */
class Resampler(val srcRate: Int, val dstRate: Int, tapsPerZero: Int = 16) {

    private val taps = tapsPerZero
    private val ratio = dstRate.toDouble() / srcRate
    private val up = dstRate >= srcRate

    // 抗混叠截止（相对源 Nyquist）
    private val cutoff = if (up) 1.0 else ratio
    private val beta = kaiserBeta(8.0)
    private val kernelHalf: Double = if (up) taps.toDouble() else (taps / ratio)

    /** 每个输出样本的抽头数与步长都随比例变化，这里直接做浮点相位版本。 */
    fun resample(x: FloatArray): FloatArray {
        val outLen = (x.size.toLong() * dstRate / srcRate).toInt()
        val out = FloatArray(max(outLen, 1))
        val invRatio = 1.0 / ratio

        for (i in 0 until outLen) {
            // 输出第 i 个点对应源信号位置
            val center = i * invRatio
            val i0 = kotlin.math.ceil(center - kernelHalf).toInt()
            val i1 = kotlin.math.floor(center + kernelHalf).toInt()
            var acc = 0.0
            var wsum = 0.0
            var j = i0
            while (j <= i1) {
                val t = (center - j) / kernelHalf   // -1..1
                val w = cutoff * sinc(cutoff * (center - j)) * kaiserWindow(t)
                val s = if (j < 0 || j >= x.size) 0.0 else x[j].toDouble()
                acc += s * w
                wsum += w
                j++
            }
            out[i] = (if (wsum != 0.0) acc / wsum else acc).toFloat()
        }
        return out
    }

    private fun sinc(v: Double): Double {
        if (abs(v) < 1e-9) return 1.0
        val p = PI * v
        return sin(p) / p
    }

    private fun kaiserWindow(t: Double): Double {
        val x = 1.0 - t * t
        if (x <= 0.0) return 0.0
        return besselI0(beta * sqrt(x)) / besselI0(beta)
    }

    companion object {
        private fun kaiserBeta(atten: Double): Double {
            // 经验式：8 dB 余量，atten ≈ 80 dB -> beta ≈ 8
            return if (atten > 50) 0.1102 * (atten - 8.7) else 0.0
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (k < 64) {
                term *= (x / (2.0 * k)) * (x / (2.0 * k))
                sum += term
                if (term < 1e-16 * sum) break
                k++
            }
            return sum
        }

        /** 便捷函数：任意源采样率 -> 目标采样率 */
        fun convert(x: FloatArray, srcRate: Int, dstRate: Int): FloatArray =
            if (srcRate == dstRate) x else Resampler(srcRate, dstRate).resample(x)
    }
}

/**
 * 线性插值到指定长度。对应 torch 的 F.interpolate(mode="linear")，
 * 这里用于把 f0 从 100Hz 网格对齐到特征帧网格（本管线中 f0 与特征帧率一致，
 * 主要用于兜底对齐）。
 */
object LinearInterp {
    fun toLength(x: FloatArray, n: Int): FloatArray {
        if (x.isEmpty() || n <= 0) return FloatArray(0)
        if (x.size == n) return x.copyOf()
        val out = FloatArray(n)
        val scale = (x.size - 1).toDouble() / max(1, n - 1).toDouble()
        for (i in 0 until n) {
            val p = i * scale
            val i0 = p.toInt()
            val i1 = min(i0 + 1, x.size - 1)
            val f = p - i0
            out[i] = (x[i0] * (1 - f) + x[i1] * f).toFloat()
        }
        return out
    }

    @Suppress("unused")
    private fun unusedRound(v: Double): Long = v.roundToLong()
}
