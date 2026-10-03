package atri.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * RMVPE 用的 log-mel 前端，严格对齐 librosa + torch.stft 的口径：
 *
 *   MelSpectrogram(False, 128, 16000, 1024, 160, None, 30, 8000)
 *     -> librosa.filters.mel(sr=16000, n_fft=1024, n_mels=128,
 *                            fmin=30, fmax=8000, htk=True, norm='slaney')
 *     -> torch.stft(center=True, window=torch.hann_window(1024))  // periodic 汉宁窗
 *     -> magnitude -> mel -> log(clamp(min=1e-5))
 *
 * 三个容易踩的坑：
 *  1. htk=True 用 HTK 梅尔刻度 2595*log10(1+f/700)，不是 Slaney 的
 *  2. norm='slaney' 会乘 2/(高频-低频) 的面积归一化
 *  3. torch.hann_window 默认 periodic=True（分母是 N 不是 N-1）
 */
object Mel {

    const val SR = 16000
    const val N_FFT = 1024
    const val HOP = 160
    const val N_MELS = 128
    const val FMIN = 30.0
    const val FMAX = 8000.0
    const val CLAMP = 1e-5f

    private val BINS = N_FFT / 2 + 1   // 513

    /** 稀疏三角滤波器组：每行只存非零区间 */
    class Sparse(val start: IntArray, val weights: Array<DoubleArray>)

    val filterbank: Sparse by lazy { buildFilterbank() }

    private val hann: DoubleArray by lazy {
        // torch.hann_window(1024) —— periodic=True
        DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2.0 * PI * it / N_FFT) }
    }

    private fun hzToMel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
    private fun melToHz(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1.0)

    private fun buildFilterbank(): Sparse {
        val fftFreqs = DoubleArray(BINS) { it * (SR / 2.0) / (BINS - 1) }   // linspace(0, sr/2, 513)

        val nBands = N_MELS + 2
        val minMel = hzToMel(FMIN)
        val maxMel = hzToMel(FMAX)
        val melF = DoubleArray(nBands) { i ->
            if (nBands == 1) minMel else melToHz(minMel + (maxMel - minMel) * i / (nBands - 1))
        }
        val fdiff = DoubleArray(nBands - 1) { melF[it + 1] - melF[it] }

        val start = IntArray(N_MELS)
        val weights = Array(N_MELS) { DoubleArray(0) }
        for (m in 0 until N_MELS) {
            // lower = -ramps[m]/fdiff[m]; upper = ramps[m+2]/fdiff[m+1]
            val lo = DoubleArray(BINS)
            val up = DoubleArray(BINS)
            for (k in 0 until BINS) {
                lo[k] = -(melF[m] - fftFreqs[k]) / fdiff[m]
                up[k] = (melF[m + 2] - fftFreqs[k]) / fdiff[m + 1]
            }
            var first = -1
            var last = -1
            for (k in 0 until BINS) {
                val v = max(0.0, min(lo[k], up[k]))
                if (v > 0.0) { if (first < 0) first = k; last = k }
            }
            if (first < 0) { start[m] = 0; weights[m] = DoubleArray(0); continue }
            val enorm = 2.0 / (melF[m + 2] - melF[m])          // norm='slaney'
            val row = DoubleArray(last - first + 1)
            for (k in first..last) row[k - first] = max(0.0, min(lo[k], up[k])) * enorm
            start[m] = first
            weights[m] = row
        }
        return Sparse(start, weights)
    }

    /** reflect 填充（对齐 torch.stft 的 pad_mode="reflect"） */
    private fun reflectPad(x: FloatArray, pad: Int): DoubleArray {
        val n = x.size
        require(n > 1) { "音频太短" }
        val out = DoubleArray(n + 2 * pad)
        for (i in 0 until n) out[pad + i] = x[i].toDouble()
        // 左
        for (i in 0 until pad) {
            var idx = pad - i
            // reflect: 以样本 0 为轴
            while (idx >= n) idx = 2 * n - idx - 2
            out[pad - 1 - i] = x[idx].toDouble()
        }
        // 右
        for (i in 0 until pad) {
            var idx = n - 2 - i
            while (idx < 0) idx = -idx
            out[pad + n + i] = x[idx].toDouble()
        }
        return out
    }

    /** 返回 (N_MELS, nFrames) 的 log-mel 谱 */
    fun logMel(x16k: FloatArray): Array<FloatArray> {
        val pad = N_FFT / 2
        val padded = reflectPad(x16k, pad)
        val nFrames = 1 + x16k.size / HOP
        val fb = filterbank

        val out = Array(N_MELS) { FloatArray(nFrames) }
        val frame = DoubleArray(N_FFT)

        for (t in 0 until nFrames) {
            val base = t * HOP
            for (i in 0 until N_FFT) frame[i] = padded[base + i] * hann[i]
            val mag = Fft.rfftMagnitude(frame)
            for (m in 0 until N_MELS) {
                val row = fb.weights[m]
                if (row.isEmpty()) { out[m][t] = ln(CLAMP.toDouble()).toFloat(); continue }
                val s = fb.start[m]
                var acc = 0.0
                for (k in row.indices) acc += row[k] * mag[s + k]
                val v = acc.toFloat()
                out[m][t] = ln(max(v, CLAMP).toDouble()).toFloat()
            }
        }
        return out
    }
}
