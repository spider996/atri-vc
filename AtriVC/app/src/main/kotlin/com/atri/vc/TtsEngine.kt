package com.atri.vc

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.Random

/**
 * 端侧 GPT-SoVITS 合成引擎（S1 = AR 语义 + S2 = SoVITS 声码器）。
 *
 * 对应 PC 管线 `tools/gsv_onnx_pipeline.py`。模型目录（档 C，615 MB）：
 *
 * ```
 * bert.onnx              288.2 MB   文本理解（字级 RoBERTa，int8）
 * t2s_prefill.onnx         0.2 MB   AR 首帧：xy_pos + mask -> logits + kv
 * t2s_decode.onnx          0.2 MB   AR 逐帧：xy_pos + kv -> logits + kv
 * t2s_shared.data        145.3 MB   prefill/decode 共享的权重（external data）
 * t2s_encoder.onnx        10.5 MB   ref/text 序列 -> xy_pos + mask
 * t2s_step_embed.onnx      8.8 MB   单 token + 位置 -> xy_pos
 * vits.onnx              161.8 MB   语义 + 参考 -> 波形（32 kHz）
 * ref_features.npz         1.7 MB   参考音频预计算特征（ssl/sv/prompt/spec）
 * ```
 *
 * ## 推理流程（与 PC 逐行对应）
 *
 * ```
 *  1. G2P          text -> phones, word2ph          [TextFrontend]
 *  2. BERT         phones -> [sum(word2ph), 1024]   bert.onnx
 *  3. 参考特征      ref_features.npz -> ssl/sv/prompt/refer/spec
 *  4. encoder      拼 ref+text 序列 -> xy_pos, attn_mask
 *  5. prefill      -> logits, k_cache, v_cache
 *  6. AR 循环      top-k/top-p/rep-pen/温度采样 -> 追加 token（禁用 EOS 前 minSteps 帧）
 *  7. vits         codes + text + refer + sv_emb + noise -> 32 kHz 波形
 * ```
 *
 * ## 三个必须照抄的细节（漏掉会明显变差）
 *
 * 1. **EOS 禁用步数**。官方从 idx=0 起、条件 `idx < 11` 禁用 EOS（共 11 步）；
 *    本循环从 1 起，等价条件为 `idx <= minSteps`。实测该 checkpoint 约 27% 的种子
 *    会在 11 步后立刻 EOS 出半句话，`minSteps = 45` 可完全消除。
 * 2. **token 位置**。第 idx 步采出的 token 落在 audio 位置 `yLen + idx - 1`，
 *    要用它去查 `step_embed` 的 `pos_idx`。
 * 3. **噪声种子**。vits 的 `noise` 是外部传入的 `randn(1,192,P*2) * 0.5`，
 *    PC 侧固定 `NOISE_SEED` 以便复现；端侧若要每次不同，传 null 即可。
 */
class TtsEngine(private val assetsDir: File, private val modelDir: File) {

    companion object {
        private const val TAG = "TtsEngine"
        const val SAMPLE_RATE = 32000
        const val EOS = 1024          // GPT-SoVITS 固定：1024 = EOS，1025 类 = [0..1024]
        const val VOCAB = 1025
    }

    private var env: OrtEnvironment? = null
    private var opts: OrtSession.SessionOptions? = null

    var bertSess: OrtSession? = null; private set
    var prefillSess: OrtSession? = null; private set
    var decodeSess: OrtSession? = null; private set
    var encoderSess: OrtSession? = null; private set
    var stepSess: OrtSession? = null; private set
    var vitsSess: OrtSession? = null; private set
    var frontend: TextFrontend? = null; private set

    val loaded: Boolean
        get() = bertSess != null && prefillSess != null && decodeSess != null &&
            encoderSess != null && stepSess != null && vitsSess != null &&
            frontend?.loaded == true

    class Timings(
        var g2pMs: Long = 0, var bertMs: Long = 0, var encoderMs: Long = 0,
        var prefillMs: Long = 0, var arMs: Long = 0, var vitsMs: Long = 0,
        var arSteps: Int = 0, var totalMs: Long = 0,
    ) {
        fun msPerStep(): Double = if (arSteps == 0) 0.0 else arMs.toDouble() / arSteps
    }

    /** 参考音频的预计算特征（由 tools/gsv_pack_refbin.py 生成） */
    class RefFeatures(
        val ssl: FloatArray, val sv: FloatArray,
        val prompt: IntArray, val refer: FloatArray, val sslLen: Int,
        /**
         * 参考文本的 **日文** phone 序列（离线预计算，见 `tools/gsv_export_refphones.py`）。
         *
         * 端侧 `TextFrontend` 只做中文 G2P，算不出日文 —— 若这里为空，
         * `ref_seq` 长度会退化成 1，与 `ref_bert` / `prompt` 不自洽，
         * `xy_pos` 长度对不上基线（joy 应为 22+29+102=153）。
         */
        val phones: IntArray = IntArray(0),
    )

    fun load(threads: Int = 4): String {
        require(frontend == null) { "已加载" }
        val e = OrtEnvironment.getEnvironment()
        env = e
        val o = OrtSession.SessionOptions()
        o.setIntraOpNumThreads(threads)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        opts = o

        fun open(name: String): OrtSession? {
            val f = File(modelDir, name)
            if (!f.exists()) { L.w("$TAG 缺少 $name"); return null }
            val sess = e.createSession(f.absolutePath, o)
            L.i("$TAG 已加载 $name (${f.length() / 1048576} MB)")
            return sess
        }
        bertSess = open("bert.onnx")
        prefillSess = open("t2s_prefill.onnx")
        decodeSess = open("t2s_decode.onnx")
        encoderSess = open("t2s_encoder.onnx")
        stepSess = open("t2s_step_embed.onnx")
        vitsSess = open("vits.onnx")
        frontend = TextFrontend(assetsDir)

        return "loaded=${loaded} bert=${bertSess != null} prefill=${prefillSess != null} " +
            "decode=${decodeSess != null} encoder=${encoderSess != null} " +
            "step=${stepSess != null} vits=${vitsSess != null} g2p=${frontend?.loaded}"
    }

    fun close() {
        listOf(bertSess, prefillSess, decodeSess, encoderSess, stepSess, vitsSess)
            .forEach { runCatching { it?.close() } }
        bertSess = null; prefillSess = null; decodeSess = null
        encoderSess = null; stepSess = null; vitsSess = null
        runCatching { opts?.close() }
        opts = null; frontend = null
    }

    // ------------------------------------------------------------------ 张量工具

    // -------------------------------------------------------------- 输出取值

    /**
     * 取输出张量的扁平 float 数据。
     *
     * ONNX Runtime 的 `Result.get(i).value` 类型取决于该输出的 shape 标注：
     *   - 含动态维（`?`）→ `OnnxTensor`
     *   - **全部静态维** → 裸数组（`float[]` / `float[][]` / `float[][][]`）
     * 两种都要能取，否则静态输出的模型会 ClassCastException。
     */
    private fun asF(v: Any?): FloatArray = when (v) {
        is OnnxTensor -> flattenF(v)
        is FloatArray -> v
        is Array<*> -> {
            val buf = ArrayList<Float>(1024)
            fun walk(x: Any?) {
                when (x) {
                    is FloatArray -> for (f in x) buf.add(f)
                    is Array<*> -> for (e in x) walk(e)
                    is Float -> buf.add(x)
                    else -> {}
                }
            }
            walk(v)
            val out = FloatArray(buf.size)
            for (i in out.indices) out[i] = buf[i]
            out
        }
        else -> error("不支持的输出类型：${v?.javaClass?.name}")
    }

    /**
     * 取输出张量的形状。
     *
     * 关键：ORT 对静态输出的返回可能是**嵌套基本类型数组**
     * （如 `float[][][][]` = `Array<Array<Array<FloatArray>>>`）。
     * 逐层下钻时要区分：
     *   · `Array<*>`  → 记录 `size`，继续下钻
     *   · `FloatArray`→ 记录 `length`，**终止**
     * 只沿第一维走（各维等长，取首元素即可），得到完整 shape。
     */
    private fun asShape(v: Any?): LongArray = when (v) {
        is OnnxTensor -> v.info.shape
        else -> {
            val d = ArrayList<Long>()
            var cur: Any? = v
            var guard = 0
            while (guard++ < 8) {
                when (cur) {
                    is Array<*> -> {
                        d.add(cur.size.toLong())
                        cur = cur.firstOrNull()
                    }
                    is FloatArray -> {
                        d.add(cur.size.toLong())
                        break
                    }
                    is DoubleArray -> {
                        d.add(cur.size.toLong())
                        break
                    }
                    is LongArray -> {
                        d.add(cur.size.toLong())
                        break
                    }
                    is IntArray -> {
                        d.add(cur.size.toLong())
                        break
                    }
                    is BooleanArray -> {
                        d.add(cur.size.toLong())
                        break
                    }
                    is ByteArray -> {
                        d.add(cur.size.toLong())
                        break
                    }
                    is ShortArray -> {
                        d.add(cur.size.toLong())
                        break
                    }
                    else -> break
                }
            }
            d.toLongArray()
        }
    }

    /** 官方 quirk：文本末尾若无终止标点则补「。」 */
    private fun ensureEnd(s: String): String {
        if (s.isEmpty()) return s
        return if (s.last() in "，。？！,.:;~：—…") s else s + "。"
    }

    /**
     * 把 ORT 的输出对象**原样**包成张量，用于直接喂给下一个会话。
     *
     * PC 参考实现就是这么做的（`enc_sess.run` 的 `xy_pos`/`attn_mask`
     * 直接作为 `pre_sess.run` 的输入）。Java API 下需要按实际类型重建：
     *
     * | ORT 返回 | 处理 |
     * |---|---|
     * | `OnnxTensor`       | 原样返回 |
     * | `float[][][]`      | 展平 → FloatBuffer + shape |
     * | `boolean[][][][]`  | 展平 → ByteBuffer(0/1) + shape + **OnnxJavaType.BOOL** |
     */
    private fun toTensor(v: Any?): OnnxTensor = when (v) {
        is OnnxTensor -> v
        is FloatArray -> f32(v, longArrayOf(v.size.toLong()))
        is Array<*> -> {
            val shape = asShape(v)
            val n = shape.fold(1L) { a, b -> a * b }.toInt()
            val first = firstLeaf(v)
            when (first) {
                is BooleanArray, is Boolean -> {
                    val buf = java.nio.ByteBuffer.allocate(n).order(java.nio.ByteOrder.nativeOrder())
                    fun walk(x: Any?) {
                        when (x) {
                            is BooleanArray -> for (b in x) buf.put(if (b) 1 else 0)
                            is Array<*> -> for (e in x) walk(e)
                            is Boolean -> buf.put(if (x) 1 else 0)
                            else -> {}
                        }
                    }
                    walk(v)
                    buf.rewind()
                    OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.BOOL)
                }
                is DoubleArray, is Double -> {
                    val buf = java.nio.DoubleBuffer.allocate(n)
                    fun walk(x: Any?) {
                        when (x) {
                            is DoubleArray -> buf.put(x)
                            is Array<*> -> for (e in x) walk(e)
                            is Double -> buf.put(x)
                            else -> {}
                        }
                    }
                    walk(v)
                    buf.rewind()
                    OnnxTensor.createTensor(env, buf, shape)
                }
                else -> f32(asF(v), shape)
            }
        }
        else -> error("toTensor 不支持的类型：${v?.javaClass?.name}")
    }

    /** 沿第一维下钻到底，取出第一个叶子数组（用来判断元素类型）。 */
    private fun firstLeaf(v: Any?): Any? {
        var cur: Any? = v
        var guard = 0
        while (cur is Array<*> && guard++ < 8) {
            cur = cur.firstOrNull() ?: return null
        }
        return cur
    }

    private fun i64(a: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(a), shape)

    private fun i32(a: IntArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, IntBuffer.wrap(a), shape)

    private fun f32(a: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(a), shape)

    // ------------------------------------------------------------------ 主流程

    /**
     * 合成。
     *
     * @param text       目标文本（建议纯中文；数字/英文会被丢弃）
     * @param refText    参考音频对应文本（日文参考可为空串）
     * @param ref        参考音频预计算特征
     * @param temperature/topK/topP/repetition  采样参数（默认值 = 官方默认）
     * @param minSteps   前 N 帧禁用 EOS（见类注释；默认 45）
     * @param seed       采样随机种子（null = 随机）
     * @param noiseSeed  vits 噪声种子（null = 随机）
     */
    fun synthesize(
        text: String,
        refText: String,
        ref: RefFeatures,
        temperature: Float = 1.0f,
        topK: Int = 15,
        topP: Float = 1.0f,
        repetition: Float = 1.35f,
        minSteps: Int = 45,
        seed: Long? = null,
        noiseSeed: Long? = null,
        onStep: ((Int) -> Unit)? = null,
    ): Pair<FloatArray, Timings> {
        val fe = frontend ?: error("未加载")
        val t = Timings()
        val tAll = System.currentTimeMillis()

        // ---------- 1. G2P ----------
        // 官方 gsv_onnx_pipeline.py:182-183：文本/参考文本末尾若不是终止标点，补「。」
        // （影响 G2P 切句与 BERT 输入，必须照抄）
        var t0 = System.currentTimeMillis()
        val gr = fe.g2p(ensureEnd(text))
        val phones = gr.phones
        val word2ph = gr.word2ph
        t.g2pMs = System.currentTimeMillis() - t0
        // 错误信息要说清「为什么」和「怎么办」，否则用户只看到「合成失败」无从下手。
        // 最常见的两个原因：① 文本不含汉字（纯英文/数字/emoji）② 文本是空的。
        if (phones.isEmpty()) {
            val han = text.count { it in '\u4e00'..'\u9fff' }
            throw IllegalStateException(
                if (han == 0) {
                    "文本里没有一个汉字（共 ${text.length} 个字符）。" +
                        "端侧 G2P 只支持中文，英文/数字/emoji 会被丢掉 —— 请改成中文再合成。"
                } else {
                    "文本 G2P 后为空（含 $han 个汉字却没产出音素）。" +
                        "可能是生僻字或全角符号过多，试着换一种写法。"
                }
            )
        }

        // 参考侧 phone —— **直接取 refbin 的离线预计算**（日文，端侧 G2P 算不出）。
        // refText 只用于日志/展示，不参与端侧计算。
        val refPhones: IntArray = ref.phones
        L.i("$TAG 输入> text=\"$text\" ref_text=\"$refText\" refPhones=${refPhones.size}")

        // ---------- 2. BERT ----------
        // 官方口径：bert.onnx 内部已按 word2ph 做 repeat_interleave，
        // 输出 [sum(word2ph), 1024] = [len(phones), 1024]。
        // 非中文（日文参考）走官方 get_bert_inf()：全零，形状仍按 phones 数。
        t0 = System.currentTimeMillis()
        val textBert = bertFeat(gr.norm, word2ph, phones.size)
        // 参考侧 phone 来自 refbin 的离线预计算（日文，端侧前端算不出）。
        // 归一化文本用中文 BERT 只为拿词表 id；日文参考按官方口径**走全零 BERT**，
        // 所以这里不需要真的跑 bert —— 直接按 refPhones.size 铺零即可。
        val refBert = FloatArray(refPhones.size * 1024)
        t.bertMs = System.currentTimeMillis() - t0

        // ---------- 3. encoder ----------
        t0 = System.currentTimeMillis()
        // refSeqLen 必须与 refBert / refPhones / prompt 三方自洽
        val refSeqLen = refPhones.size
        val textSeqLen = phones.size
        if (refSeqLen == 0) {
            L.w("$TAG 参考 phone 为空 —— refbin 缺 phones 段（重跑 gsv_pack_refbin.py）")
        }
        L.i("$TAG 长度> ref=$refSeqLen text=$textSeqLen prompt=${ref.prompt.size} " +
            "cat=${refSeqLen + textSeqLen + ref.prompt.size}")
        val refSeqLenSafe = refSeqLen.coerceAtLeast(1)
        val promptLen = ref.prompt.size
        val catLen = refSeqLenSafe + textSeqLen + promptLen

        val refSeqArr = IntArray(refSeqLenSafe)
        val textSeqArr = IntArray(textSeqLen)
        for (i in refPhones.indices) refSeqArr[i] = refPhones[i]
        for (i in phones.indices) textSeqArr[i] = phones[i]

        // BERT 输出已是展开后的 [len,1024]；空则补全零
        val refBertArr = if (refBert.isNotEmpty()) refBert else FloatArray(refSeqLenSafe * 1024)
        val textBertArr = if (textBert.isNotEmpty()) textBert else FloatArray(textSeqLen * 1024)
        val catArr = IntArray(catLen)
        for (i in refSeqArr.indices) catArr[i] = refSeqArr[i]
        for (i in textSeqArr.indices) catArr[refSeqLenSafe + i] = textSeqArr[i]
        for (i in ref.prompt.indices) catArr[refSeqLenSafe + textSeqLen + i] = ref.prompt[i]

        val encOuts = encoderSess!!.run(mapOf(
            "ref_seq" to i64(refSeqArr.map { it.toLong() }.toLongArray(), longArrayOf(1, refSeqLenSafe.toLong())),
            "text_seq" to i64(textSeqArr.map { it.toLong() }.toLongArray(), longArrayOf(1, textSeqLen.toLong())),
            "ref_bert" to f32(refBertArr, longArrayOf(1, refSeqLenSafe.toLong(), 1024)),
            "text_bert" to f32(textBertArr, longArrayOf(1, textSeqLen.toLong(), 1024)),
            "prompts" to i64(ref.prompt.map { it.toLong() }.toLongArray(), longArrayOf(1, promptLen.toLong())),
        ))
        val xyPosRaw = encOuts[0].value
        val attnMaskRaw = encOuts[1].value
        val xyShape = asShape(xyPosRaw)          // [1, l, 512]
        val maskShape = asShape(attnMaskRaw)     // 应为 [1,1,l,l]
        val l = xyShape[1].toInt()
        L.i("$TAG encoder> xyPos ${xyPosRaw?.javaClass?.simpleName} shape=${xyShape.toList()} " +
            "attnMask ${attnMaskRaw?.javaClass?.simpleName} shape=${maskShape.toList()} l=$l")
        t.encoderMs = System.currentTimeMillis() - t0

        // ---------- 4. prefill ----------
        // 与 PC 参考一致：encoder 的 xy_pos / attn_mask **原样**喂给 prefill
        // （attn_mask 是 bool 张量，必须保留类型，不能转 float）
        t0 = System.currentTimeMillis()
        val preOuts = prefillSess!!.run(mapOf(
            "xy_pos" to toTensor(xyPosRaw),
            "attn_mask" to toTensor(attnMaskRaw),
        ))
        var logits = asF(preOuts[0].value)
        var kc = asF(preOuts[1].value)
        var vc = asF(preOuts[2].value)
        var kShape = asShape(preOuts[1].value)
        var vShape = asShape(preOuts[2].value)
        t.prefillMs = System.currentTimeMillis() - t0

        // ---------- 5. AR 循环 ----------
        val rng = if (seed != null) Random(seed) else Random()
        val ySeq = ArrayList<Int>(512)
        for (p in ref.prompt) ySeq.add(p)
        val yLen = ySeq.size                      // = promptLen
        // 生成的 token 单独收着。**不能**事后用 ySeq[yLen+i] 反查：
        // EOS 停止时最后一步的 token 会被 removeAt 丢掉，导致 ySeq.size = yLen+steps-1，
        // 而 i 走到 steps-1 时正好越界（真机实测 IndexOutOfBounds）。
        val semList = ArrayList<Int>(512)
        var stopped = false

        t0 = System.currentTimeMillis()
        for (idx in 1 until 1500) {
            // 官方 infer_panel_naive：`lg_use = lg[:, :-1] if idx <= min_steps else lg`
            // —— 前 minSteps 步**禁用** EOS（等价于把 EOS 的 logit 压成 -inf）。
            val eosDisabled = idx <= minSteps
            val lg = prepareLogits(logits, ySeq, repetition, eosDisabled)
            // argmax 必须取**屏蔽后**的分布（官方也是在 lg_use 上取 argmax）
            val argmaxEos = argmax(lg) == EOS
            val sampled = sample(lg, topK, topP, temperature, rng)

            // ⚠️ 无条件判停（官方没有 `not use_eos and ...` 这层门槛）。
            // 而且 **绝不能把 EOS(1024) 写进 y/sem** —— vits 的 codes 词表只有 0..1023，
            // 喂进 1024 会在 Gather 节点抛
            // 「indices element out of data bounds, idx=1024」（真机实测崩溃）。
            if (argmaxEos || sampled >= EOS) {
                stopped = true
                break
            }
            ySeq.add(sampled)
            semList.add(sampled)
            onStep?.invoke(idx)

            // step_embed: token + pos_idx -> xy_pos [1,1,512]
            val posIdx = (yLen + idx - 1).toLong()
            val seOuts = stepSess!!.run(mapOf(
                "token" to i64(longArrayOf(sampled.toLong()), longArrayOf(1, 1)),
                "pos_idx" to i64(longArrayOf(posIdx), longArrayOf()),
            ))
            val seArr = asF(seOuts[0].value)

            // decode: xy_pos + kv -> logits + kv
            val decOuts = decodeSess!!.run(mapOf(
                "xy_pos" to f32(seArr, longArrayOf(1, 1, 512)),
                "k_cache" to f32(kc, kShape),
                "v_cache" to f32(vc, vShape),
            ))
            logits = asF(decOuts[0].value)
            kc = asF(decOuts[1].value)
            vc = asF(decOuts[2].value)
            kShape = asShape(decOuts[1].value)
            vShape = asShape(decOuts[2].value)
        }
        t.arMs = System.currentTimeMillis() - t0
        var steps = semList.size
        // 兜底：跑满 1499 步还没 EOS，说明判停没生效（不该发生）。宁可截断也不要出 30 秒噪声。
        if (!stopped) {
            val cap = 400
            if (steps > cap) {
                L.w("$TAG AR 未在 1500 步内 EOS（$steps 帧），已截断到 $cap 帧")
                while (semList.size > cap) semList.removeAt(semList.size - 1)
                steps = semList.size
            }
        }
        t.arSteps = steps
        L.i("$TAG AR 步数=$steps EOS停止=$stopped ${t.msPerStep()} ms/帧")

        // pred_semantic = y[...] 的后 steps 个（与 PC 的 y[:, -n_step:] 等价）
        val sem = IntArray(steps)
        for (i in 0 until steps) {
            // 双保险：任何 >= EOS 的 token 都不许进 vits（Gather 词表只有 1024 行）
            val s = semList[i]
            if (s >= EOS || s < 0) {
                L.w("$TAG AR 产出非法 token=$s（越界），已替换为 0")
                sem[i] = 0
            } else {
                sem[i] = s
            }
        }

        // ---------- 6. vits ----------
        t0 = System.currentTimeMillis()
        val noiseRng = if (noiseSeed != null) Random(noiseSeed) else Random()
        val noise = FloatArray(192 * steps * 2)
        for (i in noise.indices) noise[i] = (gauss(noiseRng) * 0.5).toFloat()
        val vitsOuts = vitsSess!!.run(mapOf(
            "codes" to i64(sem.map { it.toLong() }.toLongArray(), longArrayOf(1, 1, steps.toLong())),
            "text_seq" to i64(textSeqArr.map { it.toLong() }.toLongArray(), longArrayOf(1, textSeqLen.toLong())),
            "refer" to f32(ref.refer, longArrayOf(1, 1025, (ref.refer.size / 1025).toLong())),
            "sv_emb" to f32(ref.sv, longArrayOf(1, ref.sv.size.toLong())),
            "noise" to f32(noise, longArrayOf(1, 192, (steps * 2).toLong())),
        ))
        val audio = asF(vitsOuts[0].value)
        t.vitsMs = System.currentTimeMillis() - t0
        t.totalMs = System.currentTimeMillis() - tAll

        encOuts.close(); preOuts.close(); vitsOuts.close()
        return audio to t
    }

    // ------------------------------------------------------------------ BERT

    /** 中文 BERT：字级词表，逐字转 id；`word2ph` 让 ONNX 内部 repeat_interleave */
    private fun bertFeat(norm: String, word2ph: IntArray, phoneLen: Int): FloatArray {
        val ids = IntArray(norm.length + 2)
        ids[0] = 101                                     // [CLS]
        for (i in norm.indices) ids[i + 1] = charId(norm[i])
        ids[norm.length + 1] = 102                       // [SEP]
        val mask = IntArray(ids.size) { 1 }
        val tt = IntArray(ids.size)
        return try {
            val outs = bertSess!!.run(mapOf(
                "input_ids" to i64(ids.map { it.toLong() }.toLongArray(), longArrayOf(1, ids.size.toLong())),
                "attention_mask" to i64(mask.map { it.toLong() }.toLongArray(), longArrayOf(1, mask.size.toLong())),
                "token_type_ids" to i64(tt.map { it.toLong() }.toLongArray(), longArrayOf(1, tt.size.toLong())),
                "word2ph" to i32(word2ph, longArrayOf(word2ph.size.toLong())),
            ))
            val f = asF(outs[0].value)
            outs.close()
            f
        } catch (e: Exception) {
            // 不再静默降级：全零 BERT 会让音质明显变差且与 PC 基准对不上，
            // 却没有任何报错线索。这里直接抛出，让上层看到真实原因。
            L.e("$TAG BERT 推理失败", e)
            throw IllegalStateException("BERT 推理失败：${e.message}", e)
        }
    }

    /** 字 → RoBERTa 词表 id（从 bert_vocab.txt 懒加载） */
    private val vocab by lazy {
        val m = HashMap<Char, Int>()
        val f = File(assetsDir, "bert_vocab.txt")
        if (f.exists()) {
            f.readLines().forEachIndexed { i, s -> if (s.isNotEmpty()) m[s[0]] = i }
        }
        m
    }

    private fun charId(c: Char): Int = vocab[c] ?: 100    // [UNK]

    // ------------------------------------------------------------------ 采样

    private fun argmax(a: FloatArray): Int {
        var bi = 0; var bv = a[0]
        for (i in 1 until a.size) if (a[i] > bv) { bv = a[i]; bi = i }
        return bi
    }

    /**
     * 采样前的 logits 准备（返回**副本**，不动调用方的原数组）。
     *
     * 对应官方 `logits_to_probs` 的第 ① 步（重复惩罚）与 `infer_panel_naive` 的
     * `lg_use = lg[:, :-1] if idx <= min_steps else lg`。
     *
     * 把 EOS 的 logit 置 `-inf` 与「截掉 EOS 那一列」在 softmax 之后完全等价
     * （该列概率为 0），但保留了 1025 的长度，方便在同一份分布上取 argmax。
     */
    private fun prepareLogits(
        logitsIn: FloatArray, y: List<Int>, repPen: Float, eosDisabled: Boolean,
    ): FloatArray {
        // logits 形状 [*, 1025]；AR 每步只有 1 行
        val n = VOCAB
        val base = logitsIn.size - n
        require(base >= 0) { "logits 长度 ${logitsIn.size} 小于词表 $n" }
        val logits = FloatArray(n)
        for (i in 0 until n) logits[i] = logitsIn[base + i]

        // ① 重复惩罚：正 logit 除以 pen，负 logit 乘以 pen（官方 logits_to_probs:83-85）
        if (repPen != 1.0f) {
            val seen = HashSet<Int>()
            for (v in y) seen.add(v)
            for (t in seen) {
                if (t in 0 until n) {
                    logits[t] = if (logits[t] < 0f) logits[t] * repPen else logits[t] / repPen
                }
            }
        }

        // 禁用 EOS：置 -inf（等价官方 lg[:, :-1] 砍掉 EOS 列后的重新归一）
        if (eosDisabled) logits[EOS] = Float.NEGATIVE_INFINITY
        return logits
    }

    /**
     * 采样：**严格照抄**官方 `export_torch_script.logits_to_probs` 的算子顺序。
     *
     * 顺序不能换 —— 官方是：
     * ```
     *   ① repetition_penalty   score<0 → score*pen   else score/pen
     *   ② top_p（在 **logit** 空间按 softmax 累计概率裁剪，且至少保留 1 个）
     *   ③ logits /= max(temp, 1e-5)
     *   ④ top_k（取第 k 大的值当 pivot，**保留 >= pivot 的全部**，可能多于 k 个）
     *   ⑤ softmax
     * ```
     * 之前写成「先除温度，再 softmax，再按概率做 top-p」+「按排序砍掉 k 之后」，
     * 结果与官方等效但**边界处理不同**（并列值、pivot 处），采样轨迹会分叉。
     *
     * 采样本身官方用 `argmax(probs / exponential(1))`，等价于按 probs 做多项式抽样。
     * 这里用逆变换法（CDF 游走）实现同一分布。
     *
     * ⚠️ **会就地修改 `logits`**（top-p / 温度 / top-k / softmax 都是原地写），
     * 所以重复惩罚与 EOS 屏蔽必须先在 [prepareLogits] 里对副本做完。
     *
     * @return 采到的 token id（可能是 EOS=1024，由调用方判停，绝不写进 codes）
     */
    private fun sample(logits: FloatArray, topK: Int, topP: Float, temp: Float, rng: Random): Int {
        val n = VOCAB

        // ② top_p：先在 **logit** 上算 softmax 累计，再 masked_fill
        //    （官方顺序：top_p 在除温度**之前**）
        if (topP > 0f && topP < 1f) {
            val order = Array(n) { it }.sortedByDescending { logits[it] }
            // 稳定排序后按降序累计 softmax 概率
            var maxL = Float.NEGATIVE_INFINITY
            for (v in logits) if (v > maxL) maxL = v
            var sum = 0.0
            val probs = DoubleArray(n)
            for (i in 0 until n) {
                val e = Math.exp((logits[i] - maxL).toDouble())
                probs[i] = e; sum += e
            }
            for (i in 0 until n) probs[i] /= sum

            val remove = BooleanArray(n)
            var acc = 0.0
            var removeFrom = -1
            for (i in 0 until n) {
                acc += probs[order[i]]
                if (acc > topP) { removeFrom = i; break }
            }
            if (removeFrom >= 0) {
                for (i in removeFrom until n) remove[order[i]] = true
                remove[order[0]] = false                  // 至少保留一个（官方 :91）
                for (i in 0 until n) if (remove[i]) logits[i] = Float.NEGATIVE_INFINITY
            }
        }

        // ③ 温度（官方 max(temp,1e-5)）
        val t = if (temp < 1e-5f) 1e-5f else temp
        if (t != 1.0f) for (i in 0 until n) logits[i] /= t

        // ④ top_k：pivot = 第 k 大的 logit，保留所有 >= pivot（官方 :97-100）
        if (topK > 0 && topK <= n) {
            val sorted = logits.sortedDescending()
            val pivot = sorted[topK - 1]
            for (i in 0 until n) if (logits[i] < pivot) logits[i] = Float.NEGATIVE_INFINITY
        }

        // ⑤ softmax
        var maxL = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > maxL) maxL = v
        var sum = 0.0
        for (i in 0 until n) {
            val e = Math.exp((logits[i] - maxL).toDouble())
            logits[i] = e.toFloat(); sum += e
        }

        // 采样：逆变换法（CDF 游走），与 probs 上的多项式抽样同分布
        var r = rng.nextDouble() * sum
        for (i in 0 until n) {
            r -= logits[i]
            if (r <= 0.0) return i
        }
        return n - 1
    }

    /** Box-Muller：标准正态 */
    private fun gauss(rng: Random): Double {
        var u: Double; var v: Double; var s: Double
        do {
            u = rng.nextDouble() * 2 - 1
            v = rng.nextDouble() * 2 - 1
            s = u * u + v * v
        } while (s >= 1.0 || s == 0.0)
        return u * Math.sqrt(-2.0 * Math.log(s) / s)
    }

    private fun flattenF(t: OnnxTensor): FloatArray {
        val fb = t.floatBuffer
        val a = FloatArray(fb.remaining())
        fb.get(a)
        return a
    }
}
