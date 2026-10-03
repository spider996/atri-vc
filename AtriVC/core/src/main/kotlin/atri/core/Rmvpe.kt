package atri.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * RMVPE 后处理，逐行对齐 infer/lib/rmvpe.py 的 RMVPE.decode / to_local_average_cents，
 * 以及 infer/modules/vc/pipeline.py 里的 f0 -> (pitch, pitchf) 变换。
 */
object Rmvpe {

    private const val N_CLASS = 360
    private const val CENTS_BASE = 1997.3794084376191
    private const val PAD = 4

    /** cents_mapping = 20*arange(360) + 1997.3794084376191，再 pad(4,4) 补零 -> 368 */
    private val centsPadded: DoubleArray = DoubleArray(N_CLASS + 2 * PAD) { j ->
        val i = j - PAD
        if (i < 0 || i >= N_CLASS) 0.0 else 20.0 * i + CENTS_BASE
    }

    /**
     * @param hidden (T, 360) 模型输出的 salience
     * @return f0 (T,) 单位 Hz，未发声帧为 0
     */
    fun decode(hidden: Array<FloatArray>, thred: Double = 0.03): FloatArray {
        val t = hidden.size
        val cents = DoubleArray(t)
        val maxx = DoubleArray(t)

        val tmp = FloatArray(N_CLASS + 2 * PAD)
        for (f in 0 until t) {
            val row = hidden[f]
            // argmax：numpy 取第一个最大值，故用严格大于
            var best = 0
            var bestV = row[0]
            for (i in 1 until N_CLASS) {
                if (row[i] > bestV) { bestV = row[i]; best = i }
            }
            maxx[f] = bestV.toDouble()

            // 零填充后 9 点窗口
            for (j in tmp.indices) {
                val i = j - PAD
                tmp[j] = if (i < 0 || i >= N_CLASS) 0f else row[i]
            }
            val c = best + PAD
            var psum = 0.0
            var wsum = 0.0
            for (j in (c - 4)..(c + 4)) {
                val s = tmp[j].toDouble()
                psum += s * centsPadded[j]
                wsum += s
            }
            cents[f] = if (wsum != 0.0) psum / wsum else 0.0
        }

        val f0 = FloatArray(t)
        for (f in 0 until t) {
            if (maxx[f] <= thred) { f0[f] = 0f; continue }
            val hz = 10.0 * 2.0.pow(cents[f] / 1200.0)
            f0[f] = if (hz == 10.0) 0f else hz.toFloat()
        }
        return f0
    }

    /**
     * f0(Hz) -> pitch(整数 mel 级) / pitchf(float f0)。
     * 对齐 infer/modules/vc/pipeline.py：
     *   lo, hi = 1127*ln(1+50/700), 1127*ln(1+1100/700)
     *   m = 1127*ln(1+f0/700); m[m>0] = (m[m>0]-lo)*254/(hi-lo)+1
     *   m[m<=1] = 1; m[m>255] = 255; pitch = rint(m)   // rint 是四舍六入五取偶
     *
     * @param pitchShift 半音数（f0_up_key），在转换前作用
     */
    fun f0ToPitch(f0: FloatArray, pitchShift: Int = 0): Pair<LongArray, FloatArray> {
        val n = f0.size
        val pitch = LongArray(n)
        val pitchf = FloatArray(n)
        val lo = 1127.0 * ln(1.0 + 50.0 / 700.0)
        val hi = 1127.0 * ln(1.0 + 1100.0 / 700.0)
        val scale = 2.0.pow(pitchShift / 12.0)

        for (i in 0 until n) {
            var f = f0[i].toDouble()
            if (f > 0.0) f *= scale
            pitchf[i] = f.toFloat()
            var m = 1127.0 * ln(1.0 + f / 700.0)
            if (m > 0.0) m = (m - lo) * 254.0 / (hi - lo) + 1.0
            if (m <= 1.0) m = 1.0
            if (m > 255.0) m = 255.0
            pitch[i] = Math.rint(m).toLong()
        }
        return pitch to pitchf
    }

    /** 最近邻上采样 ×2（等价 F.interpolate(scale_factor=2)，默认 mode='nearest'） */
    fun nearestUp2(x: Array<FloatArray>): Array<FloatArray> {
        val t = x.size
        if (t == 0) return x
        val d = x[0].size
        val out = Array(t * 2) { FloatArray(d) }
        for (i in 0 until t) {
            System.arraycopy(x[i], 0, out[2 * i], 0, d)
            System.arraycopy(x[i], 0, out[2 * i + 1], 0, d)
        }
        return out
    }

    @Suppress("unused")
    private fun unusedAbs(v: Double) = abs(v).let { exp(it) - 1.0 }
}
