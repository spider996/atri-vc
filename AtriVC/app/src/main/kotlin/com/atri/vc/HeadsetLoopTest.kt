package com.atri.vc

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 耳机回环自测。
 *
 * ## 为什么需要这个
 *
 * 「蓝牙耳机当虚拟麦克风」方案的成立前提是：
 * **耳机喇叭放出来的声音，能被耳机麦收回去。**
 *
 * 之前只验证了「我们的播放输出到了耳机」（`getRoutedDevice() == 耳机名`），
 * 但**从未验证耳机麦能不能收到耳机喇叭放的内容** —— 这是完全不同的一件事。
 * 实测 QQ 录音没声音，所以必须先把这一环单独测出来。
 *
 * ## 测试怎么做
 *
 * 分两段测，对比能量：
 *
 * ```
 * 阶段 A（静音基线）：不播放，只录音 2 秒  → 记 energySilent
 * 阶段 B（回环）：同时播放音频 + 录音 3 秒  → 记 energyLoop
 * ```
 *
 * 判据：
 * - `energyLoop` 明显高于 `energySilent`（差 > 6 dB）→ **耳机麦收到了耳机放的声音，方案成立**
 * - 两者接近 → 声音没进耳机麦（可能走了 A2DP 而非 SCO，或 SCO 输入未启用）
 * - 两者都很高 → 环境噪音太大，测不准，建议在安静环境重测
 *
 * ## 同时输出设备信息
 *
 * 不只给结论，还回报「播放时的实际输出设备」和「录音时的实际输入设备」，
 * 便于判断是哪一环断了。
 */
object HeadsetLoopTest {

    data class Result(
        /** 静音基线能量（dBFS） */
        val silentDb: Double,
        /** 回环时能量（dBFS） */
        val loopDb: Double,
        /** 回环 - 静音（dB），越大说明耳机麦越确实收到了声音 */
        val gainDb: Double,
        /** 播放时的实际输出设备 */
        val outputDevice: String,
        /** 录音时的实际输入设备 */
        val inputDevice: String,
        /** 录音实际使用的 source */
        val audioSource: Int,
        /** 录音采样率 */
        val sampleRate: Int,
        /** 结论文字 */
        val verdict: String,
        /** 是否判定为「方案成立」 */
        val ok: Boolean,
        /** 主动 startBluetoothSco 是否调用成功 */
        val scoStarted: Boolean = false,
        /** 开 SCO 后重测的回环增益（dB）；未测则为 null */
        val scoGainDb: Double? = null,
        /** 开 SCO 后的实际输出设备 */
        val scoOutputDevice: String = "",
        /** 外放组：静音基线（dBFS） */
        val speakerSilentDb: Double = 0.0,
        /** 外放组：播放时能量（dBFS） */
        val speakerLoopDb: Double = 0.0,
        /** 外放组：增益（dB）= loop - silent */
        val speakerGainDb: Double = 0.0,
        /** 外放组：实际输出设备名 */
        val speakerOutputDevice: String = "",
        /** 外放组结论 */
        val speakerVerdict: String = "未测",
    )

    private fun dbOf(rms: Double): Double =
        if (rms <= 1e-7) -140.0 else 20.0 * log10(rms)

    /**
     * 跑一次回环自测（阻塞，约 6 秒）。**不要在 UI 线程调用。**
     *
     * @param wav 要播放的 wav 文件
     */
    @SuppressLint("MissingPermission")
    fun run(ctx: Context, wav: File): Result {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // ---------- 1. 建 AudioRecord（优先 VOICE_COMMUNICATION，最贴近 QQ 通话场景） ----------
        var rec: AudioRecord? = null
        var usedSource = -1
        var usedRate = 0
        // QQ 语音用的是 VOICE_COMMUNICATION，所以这里也优先用它 ——
        // 只有用同一个 source，测出来的输入设备才是 QQ 真正会用的那个。
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        val rates = intArrayOf(16000, 48000, 44100)

        outer@ for (src in sources) {
            for (rate in rates) {
                val minSz = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minSz <= 0) continue
                val buf = max(minSz * 2, rate / 5 * 2)
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
                        .setBufferSizeInBytes(buf)
                        .build()
                } catch (_: Throwable) {
                    null
                }
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                    rec = r; usedSource = src; usedRate = rate
                    break@outer
                }
                try { r?.release() } catch (_: Exception) {}
            }
        }

        val recorder = rec
            ?: return Result(
                0.0, 0.0, 0.0, "", "", -1, 0,
                "无法创建 AudioRecord（麦克风被占用？）", false
            )

        L.i("HeadsetLoopTest 录音就绪 source=$usedSource rate=$usedRate")

        // ---------- 2. 阶段 A：静音基线 ----------
        val silent = measure(recorder, playFile = null, seconds = 2.0, loop = false)
        L.i("HeadsetLoopTest 静音基线：${"%.1f".format(silent)} dBFS")

        // ---------- 3. 阶段 B：播放 + 录音 ----------
        val (loopDb, outDev) = measureWithPlayback(ctx, recorder, wav)

        try { recorder.stop() } catch (_: Exception) {}
        try { recorder.release() } catch (_: Exception) {}

        // ---------- 4. 输入设备名 ----------
        val inDev = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }?.productName?.toString()
        }.getOrNull() ?: "手机麦"

        val gain = loopDb - silent

        // ---------- 3.5 SCO 对照实验 ----------
        // 主动建立 SCO 链路，看回环能不能成立。
        // 这是「耳机方案是否有救」的最后一次机会。
        var scoStarted = false
        var scoGain: Double? = null
        var scoOutDev = ""
        runCatching {
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            scoStarted = am.isBluetoothScoOn
            Thread.sleep(1200)   // 等 SCO 建链
            L.i("HeadsetLoopTest SCO 启动=$scoStarted isScoOn=${am.isBluetoothScoOn}")

            // API 31+ 还能把通信设备钉到耳机上
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val bt = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (bt != null) {
                    val r = am.setCommunicationDevice(bt)
                    L.i("HeadsetLoopTest setCommunicationDevice(${bt.productName}) = $r")
                }
            }
            Thread.sleep(400)

            val silent2 = measureSilent(recorder)
            val (loop2, out2) = measureWithPlayback(ctx, recorder, wav)
            scoGain = loop2 - silent2
            scoOutDev = out2
            L.i("HeadsetLoopTest SCO 对照：静音=${"%.1f".format(silent2)} 回环=${"%.1f".format(loop2)} gain=${"%.1f".format(loop2 - silent2)}dB")
        }.onFailure { L.w("HeadsetLoopTest SCO 实验失败：${it.message}") }

        runCatching {
            @Suppress("DEPRECATION")
            am.stopBluetoothSco()
        }

        // ---------- 3.7 外放通路对照（耳机证伪后的主力路线） ----------
        var spSilent = 0.0
        var spLoop = 0.0
        var spGain = 0.0
        var spVerdict = "未测"
        var spOut = ""
        runCatching {
            val (s0, s1, o1) = measureSpeakerLoop(ctx, wav)
            spSilent = s0; spLoop = s1; spOut = o1; spGain = s1 - s0
            spVerdict = when {
                s0 > -20.0 -> "环境太吵（${"%.0f".format(s0)} dBFS），测不准"
                spGain >= 10.0 -> "✅ 外放通路成立（+${"%.1f".format(spGain)} dB）—— 手机麦能收到扬声器"
                spGain >= 5.0 -> "⚠️ 外放能收到但偏弱（+${"%.1f".format(spGain)} dB），需调大音量"
                else -> "❌ 外放也收不到（${"%.1f".format(spGain)} dB）"
            }
            L.i("SpeakerLoop 结论=$spVerdict 输出=$spOut")
        }.onFailure { L.w("SpeakerLoop 实验失败：${it.message}") }

        // 优先看 SCO 对照组：那才是「方案能不能成立」的真判据
        val bestGain = maxOf(gain, scoGain ?: -999.0)
        val earOk = bestGain >= 6.0
        // 总判定看「耳机组 or 外放组」谁成立 —— 外放组才是主力路线
        val (verdict, ok) = when {
            silent > -20.0 ->
                "环境太吵（静音基线 ${"%.0f".format(silent)} dBFS），测不准，请在安静处重测" to false
            bestGain >= 6.0 ->
                "✅ 回环成立（+${"%.1f".format(bestGain)} dB）" +
                    (if ((scoGain ?: -999.0) > gain) "，且是**开了 SCO 之后**才有的" else "") +
                    " —— 方案可用" to true
            bestGain >= 3.0 ->
                "⚠️ 有微弱回环（+${"%.1f".format(bestGain)} dB），可能音量太低" to false
            spGain >= 5.0 ->
                // 耳机组不通，但外放组通了 —— 这才是当前采用的方案
                "耳机组不通（${"%.1f".format(bestGain)} dB），但**外放组成立**" +
                    "（+${"%.1f".format(spGain)} dB，输出=$spOut）—— 走外放方案" to true
            else ->
                "❌ 耳机组与外放组都没测到回环（耳机组 ${"%.1f".format(gain)} dB / " +
                    "外放组 ${"%.1f".format(spGain)} dB）" to false
        }

        L.i(
            "HeadsetLoopTest 结论=$verdict 输出=$outDev 输入=$inDev " +
                "gain=${"%.1f".format(gain)}dB"
        )

        return Result(
            silentDb = silent,
            loopDb = loopDb,
            gainDb = gain,
            outputDevice = outDev,
            inputDevice = inDev,
            audioSource = usedSource,
            sampleRate = usedRate,
            verdict = verdict,
            ok = ok,
            scoStarted = scoStarted,
            scoGainDb = scoGain,
            scoOutputDevice = scoOutDev,
            speakerSilentDb = spSilent,
            speakerLoopDb = spLoop,
            speakerGainDb = spGain,
            speakerOutputDevice = spOut,
            speakerVerdict = spVerdict,
        )
    }

    /**
     * 外放通路测量：用**手机自带麦克风**收**扬声器**放出来的声音。
     *
     * 这是「橘雪莉语音盒」那类免 root 变声器实际走的路线，也是耳机路线证伪后
     * 唯一剩下的非 root 通路。判据同耳机版：播放时的能量要比静音基线明显高。
     *
     * 关键设计：**用差分而非绝对值**。先录 1.2 秒静音拿基线，再边播边录 3 秒，
     * 两者相减，可以抵消环境底噪和 AGC 漂移。
     *
     * @return Triple(静音dBFS, 播放dBFS, 实际输出设备名)
     */
    private fun measureSpeakerLoop(ctx: Context, wav: File): Triple<Double, Double, String> {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 外放组：走 MEDIA 通路（避开 VOICE_COMMUNICATION 的通话链路），
        // source 用 MIC/VOICE_RECOGNITION —— 贴近 QQ 录音实际会用的那一路。
        var rec: AudioRecord? = null
        val srcTry = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        val rateTry = intArrayOf(48000, 16000)
        outer2@ for (s in srcTry) {
            for (r in rateTry) {
                val minSz = AudioRecord.getMinBufferSize(
                    r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minSz <= 0) continue
                val a = try {
                    AudioRecord.Builder()
                        .setAudioSource(s)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(r)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(max(minSz * 2, r / 5 * 2))
                        .build()
                } catch (_: Throwable) { null }
                if (a != null && a.state == AudioRecord.STATE_INITIALIZED) {
                    rec = a; break@outer2
                }
                try { a?.release() } catch (_: Exception) {}
            }
        }
        val recorder = rec ?: return Triple(-140.0, -140.0, "无法建 AudioRecord")

        var mp: MediaPlayer? = null
        var outName = "未知"
        try {
            // --- 静音基线 ---
            recorder.startRecording()
            val silent = readEnergy(recorder, 2.0)

            // --- 起播（外放：USAGE_MEDIA + 音量拉满） ---
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val oldVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
            mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(wav.absolutePath)
                isLooping = true
                prepare()
                start()
            }
            Thread.sleep(400)
            outName = runCatching { mp.getRoutedDevice()?.productName?.toString() }.getOrNull()
                ?: "未知"

            val loop = readEnergy(recorder, 3.0)
            L.i("SpeakerLoop 静音=${"%.1f".format(silent)} 播放=${"%.1f".format(loop)} " +
                "输出=$outName 音量=$maxVol/$maxVol")

            am.setStreamVolume(AudioManager.STREAM_MUSIC, oldVol, 0)
            return Triple(silent, loop, outName)
        } catch (t: Throwable) {
            L.e("SpeakerLoop 失败", t)
            return Triple(-140.0, -140.0, "失败：${t.message}")
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            try { mp?.stop() } catch (_: Exception) {}
            try { mp?.release() } catch (_: Exception) {}
        }
    }

    /** 只录音，返回 RMS 的 dBFS */
    private fun measure(rec: AudioRecord, playFile: File?, seconds: Double, loop: Boolean): Double {
        rec.startRecording()
        return readEnergy(rec, seconds.toDouble())
    }

    /** 重测静音基线（SCO 对照用） */
    private fun measureSilent(rec: AudioRecord): Double {
        rec.startRecording()
        return readEnergy(rec, 2.0)
    }

    /** 播放 wav 的同时录音，返回 (能量 dBFS, 实际输出设备名) */
    private fun measureWithPlayback(
        ctx: Context,
        rec: AudioRecord,
        wav: File,
    ): Pair<Double, String> {
        var mp: MediaPlayer? = null
        var outName = "未知"

        try {
            mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(wav.absolutePath)
                isLooping = true
                prepare()
                start()
            }
            // 等通路建立再读设备名
            Thread.sleep(300)
            outName = runCatching { mp.getRoutedDevice()?.productName?.toString() }.getOrNull()
                ?: "未知"

            rec.startRecording()
            val e = readEnergy(rec, 3.0)
            return e to outName
        } catch (t: Throwable) {
            L.e("HeadsetLoopTest 播放失败", t)
            return -140.0 to "播放失败：${t.message}"
        } finally {
            try { mp?.stop() } catch (_: Exception) {}
            try { mp?.release() } catch (_: Exception) {}
        }
    }

    /** 读 `seconds` 秒，返回 RMS 的 dBFS */
    private fun readEnergy(rec: AudioRecord, seconds: Double): Double {
        val rate = rec.sampleRate
        val total = (rate * seconds).toInt()
        val buf = ShortArray(1024)
        var sum2 = 0.0
        var got = 0
        while (got < total) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) {
                if (n < 0) break
                continue
            }
            for (i in 0 until n) {
                val v = buf[i] / 32768.0
                sum2 += v * v
            }
            got += n
        }
        if (got == 0) return -140.0
        L.i("HeadsetLoopTest 读到 $got 采样，peak=${"%.3f".format(sqrt(sum2 / got) * 3.3)}")
        return dbOf(sqrt(sum2 / got))
    }
}
