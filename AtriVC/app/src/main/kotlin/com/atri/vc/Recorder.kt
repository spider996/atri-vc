package com.atri.vc

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import atri.core.Resampler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 麦克风录音。
 *
 * 目标不是「录一段好听的音频」，而是「录一段能直接喂进 RVC 管线的素材」，
 * 所以有三条硬约束：
 *
 * 1. **直采 16 kHz**。管线前端本来就是 16k（HuBERT / RMVPE 的输入采样率），
 *    直接在硬件层拿 16k 就省掉一次重采样，也避免 48k→16k 的抗混叠损失。
 *    设备不支持时按 44100 → 48000 退让，拿到后统一重采样回 16k。
 * 2. **走 UNPROCESSED / VOICE_RECOGNITION 音源**，绕开系统 AGC、降噪、回声消除。
 *    `MIC` 在很多机器上挂着 AGC，会把音量拉平，破坏 RVC 需要的动态。
 * 3. **预处理口径与文件输入完全一致**（[AudioIo.preprocess16k] 去直流 + 峰值归一化），
 *    否则同一个人「录进来」和「选文件进来」会得到不同幅度的特征，结果对不上。
 *
 * 线程模型：读循环跑在独立线程，[state] 是给 UI 的唯一出口（时长 / 电平）。
 */
class Recorder(private val ctx: Context) {

    data class State(
        val recording: Boolean = false,
        val elapsedMs: Long = 0L,
        /** 0..1 当前音量，已映射到 dB 刻度，直接喂电平条 */
        val level: Float = 0f,
        /** 0..1 整段录音出现过的最大音量，用于提示爆音 */
        val peakLevel: Float = 0f,
        val sampleRate: Int = 0,
    )

    class Result(
        /** 16k 单声道、已去直流 + 峰值归一化，可直接作为变声输入 */
        val samples16k: FloatArray,
        /** 落盘的 wav（原始采样率，已归一化），可用于回放或当 TTS 参考音频 */
        val file: File,
        val seconds: Float,
        val originRate: Int,
        /** 峰值贴到 0 dBFS，说明录入时可能已经削波 */
        val clipped: Boolean,
        /** 原始峰值低于 -40dBFS，说明基本没收到声音，多半是没说话或麦克风被挡 */
        val tooQuiet: Boolean,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile private var running = false

    private val chunks = ArrayList<FloatArray>()
    private var collected = 0
    private var srcRate = 0
    private var peakAbs = 0f

    val isRecording: Boolean get() = running

    /** 设备上实际生效的采样率（未开始录音时为 0） */
    val activeSampleRate: Int get() = srcRate

    /**
     * 开始录音。返回 null 表示成功，非 null 是给用户看的错误原因。
     * 调用方需自行保证已拿到 RECORD_AUDIO 权限。
     */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        if (running) return "已经在录音了"
        chunks.clear()
        collected = 0
        peakAbs = 0f

        var created: AudioRecord? = null
        var chosenRate = 0

        // UNPROCESSED 最干净但设备支持率低；VOICE_RECOGNITION 覆盖面广且通常不带 AGC；MIC 兜底。
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        val rates = intArrayOf(16000, 44100, 48000)

        outer@ for (src in sources) {
            for (rate in rates) {
                val minSz = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minSz <= 0) continue
                val bufBytes = max(minSz * 2, rate / 5 * 2)   // 至少 200ms，避免欠载爆音
                val r = try {
                    AudioRecord.Builder()
                        .setAudioSource(src)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufBytes)
                        .build()
                } catch (_: Throwable) {
                    null
                }
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                    created = r
                    chosenRate = rate
                    L.i("录音就绪：source=$src rate=$rate buf=${bufBytes}B")
                    break@outer
                }
                try { r?.release() } catch (_: Exception) {}
            }
        }

        val rec = created ?: return "麦克风不可用（设备不支持所选采样率，或被其它应用占用）"

        try {
            rec.startRecording()
        } catch (t: Throwable) {
            try { rec.release() } catch (_: Exception) {}
            L.e("startRecording 失败", t)
            return "无法开始录音：${t.message}"
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            try { rec.release() } catch (_: Exception) {}
            return "麦克风没有真正启动，请检查是否被其它应用占用"
        }

        record = rec
        srcRate = chosenRate
        running = true
        _state.value = State(recording = true, sampleRate = chosenRate)

        worker = Thread({ readLoop(rec) }, "atri-recorder").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        return null
    }

    private fun readLoop(rec: AudioRecord) {
        val frame = ShortArray(1024)
        val rate = srcRate
        while (running) {
            val n = rec.read(frame, 0, frame.size)
            if (n <= 0) {
                if (n < 0) {
                    L.e("AudioRecord.read 返回 $n，录音线程退出")
                    break
                }
                continue
            }
            val out = FloatArray(n)
            var peak = 0f
            var sum2 = 0.0
            for (i in 0 until n) {
                val v = frame[i] / 32768f
                out[i] = v
                val a = abs(v)
                if (a > peak) peak = a
                sum2 += v.toDouble() * v
            }
            synchronized(chunks) {
                chunks.add(out)
                collected += n
            }
            if (peak > peakAbs) peakAbs = peak
            val rms = sqrt(sum2 / n).toFloat()
            val lvl = rmsToLevel(rms)
            val prev = _state.value
            _state.value = prev.copy(
                elapsedMs = collected * 1000L / rate,
                level = lvl,
                peakLevel = max(prev.peakLevel, lvl),
            )
        }
    }

    /** 停止并取回结果。录音短于 0.3 秒直接丢弃（多半是误触）。 */
    fun stop(): Result? {
        if (!running) return null
        running = false
        try { worker?.join(800) } catch (_: InterruptedException) {}
        worker = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null

        val rate = srcRate
        val total = collected
        _state.value = State(recording = false, sampleRate = rate)

        if (total < max(1, rate * 3 / 10)) {
            synchronized(chunks) { chunks.clear() }
            return null
        }

        val all = FloatArray(total)
        var p = 0
        synchronized(chunks) {
            for (c in chunks) {
                System.arraycopy(c, 0, all, p, c.size)
                p += c.size
            }
            chunks.clear()
        }

        val at16k = if (rate == 16000) all.copyOf() else Resampler.convert(all, rate, 16000)
        AudioIo.preprocess16k(at16k, maxGain = GAIN_CAP)

        val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
        val file = AudioIo.saveLocalWav(
            ctx, all, rate, "recordings", "rec_$stamp", maxGain = GAIN_CAP
        )

        val clipped = peakAbs >= 0.985f
        val tooQuiet = peakAbs < QUIET_THRESHOLD
        L.i(
            "录音结束：%.2f 秒 @%dHz -> %s，peak=%.5f%s%s".format(
                total.toFloat() / rate, rate, file.name, peakAbs,
                if (clipped) "（可能削波）" else "",
                if (tooQuiet) "（几乎没收到声音）" else "",
            )
        )
        return Result(at16k, file, total.toFloat() / rate, rate, clipped, tooQuiet)
    }

    /** 放弃本次录音（不落盘） */
    fun cancel() {
        if (!running) return
        running = false
        try { worker?.join(500) } catch (_: InterruptedException) {}
        worker = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        synchronized(chunks) { chunks.clear() }
        collected = 0
        _state.value = State()
    }

    fun release() = cancel()

    private fun rmsToLevel(rms: Float): Float {
        if (rms <= 1e-7f) return 0f
        // -60dB..0dB 线性映射到 0..1，-60dB 以下视作静音
        val db = 20f * log10(rms)
        return ((db + 60f) / 60f).coerceIn(0f, 1f)
    }

    companion object {
        /** 归一化增益上限（约 +30dB）。防止「没出声」的录音把底噪放大成满幅噪声。 */
        private const val GAIN_CAP = 32f

        /** 原始峰值低于此值判定为几乎没收到声音（约 -40dBFS） */
        private const val QUIET_THRESHOLD = 0.01f
    }
}
