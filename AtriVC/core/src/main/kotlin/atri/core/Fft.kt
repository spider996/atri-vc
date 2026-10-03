package atri.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 定点长度的 radix-2 复数 FFT（迭代 Cooley-Tukey）。
 * 预计算旋转因子表，误差不随级数累积。
 */
object Fft {

    private val tables = HashMap<Int, DoubleArray>()

    /** 返回长度 n 的旋转因子表：前 n/2 个为 cos(-2πk/n)，后 n/2 个为 sin(-2πk/n) */
    private fun table(n: Int): DoubleArray = tables.getOrPut(n) {
        val half = n / 2
        val t = DoubleArray(n)
        for (k in 0 until half) {
            val ang = -2.0 * PI * k / n
            t[k] = cos(ang)
            t[half + k] = sin(ang)
        }
        t
    }

    /** 原地 FFT。re/im 长度必须相同且为 2 的幂。 */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "FFT 长度必须是 2 的幂" }
        if (n == 1) return
        val tw = table(n)
        val half = n / 2

        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = half
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        var len = 2
        while (len <= n) {
            val step = n / len
            val h = len / 2
            var i = 0
            while (i < n) {
                var k = 0
                while (k < h) {
                    val twIdx = k * step
                    val wr = tw[twIdx]
                    val wi = tw[half + twIdx]
                    val a = i + k
                    val b = a + h
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    val ur = re[a]
                    val ui = im[a]
                    re[a] = ur + xr; im[a] = ui + xi
                    re[b] = ur - xr; im[b] = ui - xi
                    k++
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * 实信号单边幅度谱。返回 n/2+1 个 bin。
     * 与 torch.stft(..., return_complex=True) 后取 sqrt(re^2+im^2) 等价。
     */
    fun rfftMagnitude(frame: DoubleArray): DoubleArray {
        val n = frame.size
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        System.arraycopy(frame, 0, re, 0, n)
        fft(re, im)
        val bins = n / 2 + 1
        val mag = DoubleArray(bins)
        for (k in 0 until bins) mag[k] = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k])
        return mag
    }
}
