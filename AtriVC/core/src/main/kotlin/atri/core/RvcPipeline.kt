package atri.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.abs

data class RvcConfig(
    /** 输出采样率。net_g 固定输出 40k，如需其它采样率由上层再重采样 */
    val outSampleRate: Int = 40000,
    /** 变调半音数（RVC 的 f0_up_key） */
    val pitchShift: Int = 0,
    /** 检索特征混合比例（index_rate），0 = 不用 index */
    val indexRate: Float = 0.3f,
    /** 无声段回落权重，<0.5 才会启用保护混合 */
    val protect: Float = 0.33f,
    /** 说话人 id（emb_g 索引） */
    val sid: Int = 0,
    /** 重叠帧数，越大拼接越平滑但越慢 */
    val overlapFrames: Int = 64,
    /** 检索邻居数 */
    val topK: Int = 8,
    /** 随机激励种子 */
    val seed: Long = 1234L,
    /** 记录中间产物（逐级对齐验证用），正式运行请关掉 */
    val captureDebug: Boolean = false,
)

/**
 * RVC 离线转换管线（全 ONNX / INT8）。与 tools/onnx_infer.py 一一对应：
 *
 *   1. HuBERT 内容特征           (16k -> 50Hz x 768)
 *   2. faiss index 检索混合
 *   3. 最近邻上采样 x2           (50Hz -> 100Hz)
 *   4. RMVPE 音高                (mel -> onnx -> decode)
 *   5. f0 -> (pitch, pitchf)
 *   6. protect 混合
 *   7. net_g 分块推理 + 重叠相加  -> 40k 波形
 */
class RvcPipeline(
    private val env: OrtEnvironment,
    private val netG: OrtSession,
    private val hubert: OrtSession,
    private val rmvpe: OrtSession,
    private val index: FaissIvfIndex?,
    private val onProgress: (stage: String, pct: Float) -> Unit = { _, _ -> },
) {

    val debug = LinkedHashMap<String, Any>()

    /** 分阶段耗时（毫秒），用于性能归因 */
    val timings = LinkedHashMap<String, Long>()

    /** net_g 的固定块长（帧），来自模型输入的静态维度 */
    val blockFrames: Int by lazy {
        val info = netG.inputInfo.values.first().info as TensorInfo
        info.shape[1].toInt()
    }

    /**
     * net_g 的输入名。⚠️ 不能依赖 inputInfo 这个 Map 的迭代顺序，
     * 必须按名字取；名字不对会让波形整段跑偏（特征却完全对得上）。
     */
    private val nPhone = pickName(netG, "phone", 0)
    private val nLens = pickName(netG, "phone_lengths", 1)
    private val nPitch = pickName(netG, "pitch", 2)
    private val nPitchf = pickName(netG, "pitchf", 3)
    private val nSid = pickName(netG, "ds", 4)
    private val nRnd = pickName(netG, "rnd", 5)

    fun resolvedNetGInputs(): Map<String, String> = linkedMapOf(
        "phone" to nPhone,
        "phone_lengths" to nLens,
        "pitch" to nPitch,
        "pitchf" to nPitchf,
        "ds" to nSid,
        "rnd" to nRnd,
    )

    /** 验证时用来喂固定的噪声块（对齐 Python 基准） */
    var rndProvider: ((block: Int) -> FloatArray?)? = null

    /**
     * net_g 分块并行度（1 = 串行，与改造前行为完全一致）。
     *
     * 背景：实测（真机天玑 9400 / 20 秒音频 / 3 块）单次 `netG.run` 只用约 3.5 个核，
     * 8 核里有近一半闲置。块与块之间无依赖，并发 run 可以把剩余核心用起来。
     * 同一 OrtSession 并发 run 是 ORT 支持的用法。
     * 数值上应与串行版【逐位一致】——每块的输入张量完全相同，只是执行顺序变了。
     */
    var blockParallelism: Int = 4

    fun convert(audio16k: FloatArray, cfg: RvcConfig): FloatArray {
        val tStart = System.nanoTime()
        debug.clear()
        timings.clear()
        val cap = cfg.captureDebug

        // ---------- 1. HuBERT ----------
        onProgress("HuBERT 特征", 0.03f)
        var t = System.nanoTime()
        val feats = runHubert(audio16k)
        timings["hubert"] = (System.nanoTime() - t) / 1_000_000
        if (cap) debug["hubert_feat"] = flatten(feats)

        // ---------- 2. index 检索混合 ----------
        var cur = feats
        if (index != null && cfg.indexRate > 0f) {
            onProgress("音色检索", 0.25f)
            t = System.nanoTime()
            cur = index.mix(cur, cfg.indexRate, cfg.topK)
            timings["index"] = (System.nanoTime() - t) / 1_000_000
            if (cap) debug["feat_after_index"] = flatten(cur)
        }

        val feats0 = if (cfg.protect < 0.5f) feats else null

        // ---------- 3. 最近邻上采样 x2 ----------
        cur = Rmvpe.nearestUp2(cur)
        val up = if (feats0 != null) Rmvpe.nearestUp2(feats0) else null
        if (cap) {
            debug["feat_interp"] = flatten(cur)
            if (up != null) debug["feat0_interp"] = flatten(up)
        }

        // ---------- 4. RMVPE 音高 ----------
        onProgress("提取音高", 0.5f)
        t = System.nanoTime()
        val mel = Mel.logMel(audio16k)
        val tMel = System.nanoTime()
        val f0 = runRmvpeModel(mel, audio16k.size, cap)
        val tRmvpe = System.nanoTime()
        timings["mel"] = (tMel - t) / 1_000_000
        timings["rmvpe"] = (tRmvpe - tMel) / 1_000_000
        if (cap) {
            debug["mel"] = flatten(mel)
            debug["f0"] = f0
        }

        // ---------- 5. f0 -> pitch / pitchf ----------
        val (pitchAll, pitchfAll) = Rmvpe.f0ToPitch(f0, cfg.pitchShift)
        val tf = minOf(cur.size, pitchAll.size)
        val pitch = pitchAll.copyOf(tf)
        val pitchf = pitchfAll.copyOf(tf)
        if (cap) {
            debug["pitch"] = pitch
            debug["pitchf"] = pitchf
        }

        // ---------- 6. protect 混合 ----------
        val finalFeats: Array<FloatArray>
        if (up != null) {
            val w = FloatArray(tf) { if (pitchf[it] > 0f) 1f else cfg.protect }
            finalFeats = Array(tf) { i ->
                val a = cur[i]; val b = up[i]; val wi = w[i]
                FloatArray(a.size) { j -> a[j] * wi + b[j] * (1f - wi) }
            }
            if (cap) debug["protect_weight"] = w
        } else {
            finalFeats = Array(tf) { cur[it] }
        }
        if (cap) debug["feat_final"] = flatten(finalFeats)

        // ---------- 7. net_g 分块推理 ----------
        onProgress("合成波形", 0.6f)
        t = System.nanoTime()
        val out = runNetG(finalFeats, pitch, pitchf, cfg, tf)
        timings["netg"] = (System.nanoTime() - t) / 1_000_000

        timings["total"] = (System.nanoTime() - tStart) / 1_000_000
        onProgress("完成", 1f)
        if (cap) debug["elapsed_ms"] = timings["total"] ?: 0L
        return out
    }

    // ------------------------------------------------------------------

    private fun runHubert(x: FloatArray): Array<FloatArray> {
        val inName = pickName(hubert, "source", 0)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { tin ->
            hubert.run(mapOf(inName to tin)).use { res ->
                val outT = res.get(0) as OnnxTensor
                val info = outT.info as TensorInfo
                val t = info.shape[1].toInt()
                val d = info.shape[2].toInt()
                val flat = FloatArray(t * d)
                outT.floatBuffer.get(flat)
                return Array(t) { i -> flat.copyOfRange(i * d, (i + 1) * d) }
            }
        }
    }

    private fun runRmvpeModel(mel: Array<FloatArray>, audioLen: Int, cap: Boolean): FloatArray {
        val nMels = mel.size
        val frames = mel[0].size
        val nPad = 32 * ((frames - 1) / 32 + 1) - frames
        val totalFrames = frames + nPad

        val flat = FloatArray(nMels * totalFrames)
        for (m in 0 until nMels) System.arraycopy(mel[m], 0, flat, m * totalFrames, frames)

        val inName = pickName(rmvpe, "input", 0)
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(flat), longArrayOf(1, nMels.toLong(), totalFrames.toLong())
        ).use { tin ->
            rmvpe.run(mapOf(inName to tin)).use { res ->
                val outT = res.get(0) as OnnxTensor
                val info = outT.info as TensorInfo
                val d = info.shape[2].toInt()
                val all = FloatArray(totalFrames * d)
                outT.floatBuffer.get(all)
                val hidden = Array(frames) { i -> all.copyOfRange(i * d, (i + 1) * d) }
                if (cap) debug["rmvpe_hidden"] = flatten(hidden)
                return Rmvpe.decode(hidden, 0.03)
            }
        }
    }

    private fun runNetG(
        feats: Array<FloatArray>,
        pitch: LongArray,
        pitchf: FloatArray,
        cfg: RvcConfig,
        t: Int,
    ): FloatArray {
        val cap = cfg.captureDebug
        val t0 = blockFrames
        val overlap = cfg.overlapFrames.coerceIn(0, t0 - 1)
        val hop = maxOf(1, t0 - overlap)
        val upp = 400
        val total = t * upp
        val acc = DoubleArray(total + upp)
        val wsum = DoubleArray(total + upp)
        val fade = overlap * upp
        val fadeDen = maxOf(1, fade - 1).toDouble()   // 对齐 np.linspace 的分母
        val d = feats[0].size

        val rng = Random(cfg.seed)
        val phoneBuf = FloatArray(t0 * d)
        val pitchBuf = LongArray(t0)
        val pitchfBuf = FloatArray(t0)
        val rndBuf = FloatArray(192 * t0)

        val blockPhone = ArrayList<FloatArray>()

        // ---------- 阶段 A：顺序准备每块的输入 ----------
        // 必须顺序执行：rnd 由共享 Random 按块序生成，顺序一变激励就全变了。
        class Blk(
            val s: Int, val n: Int, val e: Int,
            val phone: FloatArray, val pitch: LongArray,
            val pitchf: FloatArray, val rnd: FloatArray,
        )
        val blocks = ArrayList<Blk>()
        var nblk = 0
        var s = 0
        while (s < t) {
            val e = minOf(s + t0, t)
            val n = e - s

            java.util.Arrays.fill(phoneBuf, 0f)
            for (i in 0 until n) System.arraycopy(feats[s + i], 0, phoneBuf, i * d, d)
            for (i in 0 until n) {
                pitchBuf[i] = pitch[s + i]
                pitchfBuf[i] = pitchf[s + i]
            }
            for (i in n until t0) {
                pitchBuf[i] = 1L
                pitchfBuf[i] = 0f
            }
            fillRnd(rng, rndBuf, nblk)
            if (cap) blockPhone.add(phoneBuf.copyOf())
            // 每块独占输入缓冲，避免并发 run 时互相踩内存
            blocks.add(
                Blk(s, n, e, phoneBuf.copyOf(), pitchBuf.copyOf(), pitchfBuf.copyOf(), rndBuf.copyOf())
            )
            nblk++
            if (e >= t) break
            s += hop
        }

        // ---------- 阶段 B：分块推理（blockParallelism > 1 时并发） ----------
        val results = arrayOfNulls<FloatArray>(blocks.size)
        val nThr = blockParallelism.coerceIn(1, maxOf(1, blocks.size))
        if (nThr <= 1) {
            for (i in blocks.indices) {
                val b = blocks[i]
                results[i] = runNetGBlock(b.phone, b.pitch, b.pitchf, b.rnd, t0, d, cfg)
                onProgress("合成波形", 0.6f + 0.38f * ((i + 1).toFloat() / blocks.size))
            }
        } else {
            val pool = Executors.newFixedThreadPool(nThr) { r ->
                Thread(r, "netg-block").also { it.isDaemon = true }
            }
            try {
                val futs = blocks.indices.map { i ->
                    val b = blocks[i]
                    pool.submit { results[i] = runNetGBlock(b.phone, b.pitch, b.pitchf, b.rnd, t0, d, cfg) }
                }
                var done = 0
                for (f in futs) {
                    f.get()
                    done++
                    onProgress("合成波形", 0.6f + 0.38f * (done.toFloat() / blocks.size))
                }
            } finally {
                pool.shutdown()
            }
        }

        // ---------- 阶段 C：顺序重叠相加 ----------
        val blockAudio = ArrayList<FloatArray>()
        val blockLens = ArrayList<Int>()
        for ((idx, b) in blocks.withIndex()) {
            val yAll = results[idx] ?: continue
            val need = b.n * upp
            val w = DoubleArray(need)
            java.util.Arrays.fill(w, 1.0)
            if (idx > 0 && fade > 0) {
                val m = minOf(fade, need)
                for (i in 0 until m) w[i] = i / fadeDen
            }
            if (b.e < t && fade > 0) {
                val m = minOf(fade, need)
                for (i in 0 until m) w[need - 1 - i] = i / fadeDen
            }
            val a0 = b.s * upp
            for (i in 0 until need) {
                acc[a0 + i] += yAll[i] * w[i]
                wsum[a0 + i] += w[i]
            }
            if (cap) {
                blockAudio.add(yAll.copyOf(need))
                blockLens.add(need)
            }
        }

        if (cap) {
            debug["blocks_phone"] = flatten(blockPhone)
            debug["blocks_audio_len"] = blockLens.toIntArray()
            val mx = blockAudio.maxOfOrNull { it.size } ?: 0
            val pad = Array(blockAudio.size) { i ->
                FloatArray(mx).also { System.arraycopy(blockAudio[i], 0, it, 0, blockAudio[i].size) }
            }
            debug["blocks_audio"] = flatten(pad)
            debug["block_count"] = nblk
        }

        onProgress("归一化", 0.99f)
        val out = FloatArray(total)
        var maxAbs = 1e-8f
        for (i in 0 until total) {
            val v = (acc[i] / maxOf(wsum[i], 1e-8)).toFloat()
            out[i] = v
            val a = abs(v)
            if (a > maxAbs) maxAbs = a
        }
        val gain = 0.95f / maxAbs
        for (i in 0 until total) out[i] *= gain
        return out
    }

    /** 单块 net_g 推理。并发调用时各块持有独立输入缓冲，互不共享。 */
    private fun runNetGBlock(
        phone: FloatArray,
        pitch: LongArray,
        pitchf: FloatArray,
        rnd: FloatArray,
        t0: Int,
        d: Int,
        cfg: RvcConfig,
    ): FloatArray {
        val upp = 400
        OnnxTensor.createTensor(env, FloatBuffer.wrap(phone), longArrayOf(1, t0.toLong(), d.toLong())).use { tPhone ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(t0.toLong())), longArrayOf(1)).use { tLen ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(pitch), longArrayOf(1, t0.toLong())).use { tPitch ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(pitchf), longArrayOf(1, t0.toLong())).use { tPitchf ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(cfg.sid.toLong())), longArrayOf(1)).use { tSid ->
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(rnd), longArrayOf(1, 192, t0.toLong())).use { tRnd ->
                                netG.run(
                                    mapOf(
                                        nPhone to tPhone,
                                        nLens to tLen,
                                        nPitch to tPitch,
                                        nPitchf to tPitchf,
                                        nSid to tSid,
                                        nRnd to tRnd,
                                    )
                                ).use { res ->
                                    val outT = res.get(0) as OnnxTensor
                                    val yAll = FloatArray(t0 * upp)
                                    outT.floatBuffer.get(yAll)
                                    return yAll
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 噪声激励 rnd = randn(1,192,T0) * 0.66666。
     * 这个 0.66666 是原版 PyTorch 内部的缩放，而 ONNX 版噪声由外部传入且不乘
     * （官方 onnx_inference.py 也漏了）—— 不补 cos 会掉到 0.87。
     */
    private fun fillRnd(rng: Random, buf: FloatArray, block: Int) {
        val provided = rndProvider?.invoke(block)
        if (provided != null) {
            System.arraycopy(provided, 0, buf, 0, minOf(provided.size, buf.size))
            return
        }
        for (i in buf.indices) buf[i] = (rng.nextGaussian() * 0.66666).toFloat()
    }

    private fun flatten(a: Array<FloatArray>): FloatArray {
        if (a.isEmpty()) return FloatArray(0)
        val d = a[0].size
        val out = FloatArray(a.size * d)
        for (i in a.indices) System.arraycopy(a[i], 0, out, i * d, d)
        return out
    }

    private fun flatten(list: List<FloatArray>): FloatArray {
        if (list.isEmpty()) return FloatArray(0)
        val d = list[0].size
        val out = FloatArray(list.size * d)
        for (i in list.indices) System.arraycopy(list[i], 0, out, i * d, d)
        return out
    }

    private companion object {
        /** 优先按名字取；名字不在就退回按位置取（保证老模型也能跑） */
        fun pickName(session: OrtSession, preferred: String, idx: Int): String {
            val keys = session.inputInfo.keys.toList()
            return if (keys.contains(preferred)) preferred else keys.getOrElse(idx) { preferred }
        }
    }
}
