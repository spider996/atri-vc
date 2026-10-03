package com.atri.vc

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import atri.core.FaissIvfIndex
import atri.core.RvcConfig
import atri.core.RvcPipeline
import atri.core.Wav
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 端侧推理引擎：持有三件套 ONNX 会话，跑完整 RVC 管线。
 *
 * 只有一次性的会话加载 + 一次 convert，天然是「离线批处理」语义；
 * 实时变声（AAudio 低延迟）会走另一条 native 通路，这里不做。
 *
 * [modelDirs] 按优先级排列，逐个目录找同名文件（外部私有目录 / 内部私有目录）。
 */
class VcEngine(private val modelDirs: List<File>) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var netG: OrtSession? = null
    private var hubert: OrtSession? = null
    private var rmvpe: OrtSession? = null
    private var index: FaissIvfIndex? = null
    private var pipeline: RvcPipeline? = null

    val loaded: Boolean get() = pipeline != null

    /** 分阶段耗时（毫秒） */
    fun timings(): Map<String, Long> = pipeline?.timings ?: emptyMap()

    private fun need(name: String): File =
        modelDirs.asSequence().map { File(it, name) }
            .firstOrNull { it.exists() && it.length() > 1024 }
            ?: throw IllegalStateException(
                "找不到 $name；已查找：${modelDirs.joinToString(" | ") { it.absolutePath }}"
            )

    fun load(
        withIndex: Boolean = true,
        threads: Int = 0,
        ep: String = "cpu",
        profileDir: File? = null,
        onLog: (String) -> Unit = {},
    ) {
        if (loaded) return
        // ⚠️ 实测坑（2026-09-30）：只要「显式」调用 setIntraOpNumThreads（哪怕设成 1），
        // ORT 就会切到另一套线程池/内核路径，同一张 INT8 图的输出会发生系统性变化
        // （hubert_dyn8 相对基准 cos 1.0000 -> 0.9941）。不设置则与基准逐位相同。
        // 因此默认 threads = 0 = 跟随 ORT 默认；只有需要限核时才显式传值。
        val epName = ep.trim().lowercase()

        fun newOpts(profile: Boolean): OrtSession.SessionOptions =
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                if (threads > 0) setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                // 可选执行提供器：XNNPACK 对 ARM 的量化卷积优化好；NNAPI 可把算子丢给 NPU/GPU
                when (epName) {
                    "xnnpack" -> try {
                        addXnnpack(HashMap())
                    } catch (t: Throwable) {
                        L.e("addXnnpack 失败，退回 CPU", t)
                    }
                    "nnapi" -> try {
                        addNnapi()
                    } catch (t: Throwable) {
                        L.e("addNnapi 失败，退回 CPU", t)
                    }
                }
                if (profile) profileDir?.let {
                    it.mkdirs()
                    try {
                        enableProfiling(it.absolutePath)
                    } catch (t: Throwable) {
                        L.e("enableProfiling 失败", t)
                    }
                }
            }

        val opts = newOpts(profile = false)
        // net_g 的 profiling 单独开一个 session（否则三个 session 会争抢同一份 profile 文件）
        val netGOpts = if (profileDir != null) newOpts(profile = true) else opts
        L.i("session options: intraOp=${if (threads > 0) threads.toString() else "default(auto)"} interOp=1 ep=$epName profile=${profileDir != null}")

        val t0 = System.currentTimeMillis()

        val fHubert = need(ModelStore.HUBERT)
        onLog("加载 HuBERT ... ${fHubert.parentFile?.name}")
        L.i("load hubert: ${fHubert.absolutePath} (${fHubert.length() / 1048576} MB)")
        hubert = env.createSession(fHubert.absolutePath, opts)

        val fRmvpe = need(ModelStore.RMVPE)
        onLog("加载 RMVPE ...")
        L.i("load rmvpe: ${fRmvpe.absolutePath} (${fRmvpe.length() / 1048576} MB)")
        rmvpe = env.createSession(fRmvpe.absolutePath, opts)

        val fNetG = need(ModelStore.NET_G)
        onLog("加载 net_g ...")
        L.i("load net_g: ${fNetG.absolutePath} (${fNetG.length() / 1048576} MB)")
        netG = env.createSession(fNetG.absolutePath, netGOpts)

        if (withIndex) {
            val idxFile = modelDirs.asSequence().map { File(it, ModelStore.INDEX) }
                .firstOrNull { it.exists() && it.length() > 1024 }
            if (idxFile != null) {
                onLog("加载检索索引 ...")
                L.i("load index: ${idxFile.absolutePath}")
                try {
                    index = FaissIvfIndex.load(idxFile)
                } catch (t: Throwable) {
                    L.e("index 加载失败，退化为不使用检索", t)
                    onLog("索引加载失败，已跳过")
                    index = null
                }
            } else {
                L.i("index 缺失，跳过检索")
            }
        }

        pipeline = RvcPipeline(env, netG!!, hubert!!, rmvpe!!, index) { _, _ -> }
        L.i("engine ready in ${System.currentTimeMillis() - t0} ms (index=${index != null})")
        onLog("模型就绪")
    }

    /**
     * 设置 net_g 分块并行度（1 = 串行）。仅调试通路使用，用于对比并行收益。
     */
    fun setBlockParallelism(n: Int) {
        pipeline?.blockParallelism = n
        L.i("net_g 块并行度 -> $n")
    }

    /**
     * 释放 net_g 会话以强制 ORT 把 profiling 结果 flush 到磁盘。
     * 只在调试通路里调用（之后引擎不可用）。
     */
    fun flushProfileAndUnload() {
        try { netG?.close() } catch (_: Exception) {}
        netG = null
        pipeline = null
    }

    fun convert(audio16k: FloatArray, cfg: RvcConfig, onProgress: (String, Float) -> Unit): FloatArray {
        val p = pipeline ?: throw IllegalStateException("模型还没加载")
        val t0 = System.currentTimeMillis()
        val out = p.convert(audio16k, cfg.copy(captureDebug = false))
        L.i(
            "convert done: in=${audio16k.size} out=${out.size} " +
                "elapsed=${System.currentTimeMillis() - t0}ms"
        )
        return out
    }

    // ---------------------------------------------------------------- 验证用

    /**
     * 装载基准导出的 `blocks_rnd.f32`（[blocks,192,T0]，小端 f32）作为固定噪声。
     *
     * 这是逐级对齐验证的关键：NSF 的随机激励不同会让波形完全去相关（cos≈0.4），
     * 但频谱/音高仍然一致。复用基准噪声后就能做「同输入 + 同噪声」的严格比对。
     */
    fun installRndFromReference(file: File): Int {
        val p = pipeline ?: throw IllegalStateException("模型还没加载")
        val n = (file.length() / 4).toInt()
        val all = Wav.readF32(file, n)
        val per = 192 * p.blockFrames
        val blocks = if (per > 0) n / per else 0
        L.i("装载基准噪声 ${file.name}: $n 值 -> $blocks 块 x $per")
        p.rndProvider = { blk ->
            if (blk in 0 until blocks) all.copyOfRange(blk * per, (blk + 1) * per) else null
        }
        return blocks
    }

    /** 抓取管线每一级中间产物并落盘（与 tools/android_ref/ 的基准逐级比对） */
    fun convertWithDebugDump(audio16k: FloatArray, cfg: RvcConfig, outDir: File): FloatArray {
        val p = pipeline ?: throw IllegalStateException("模型还没加载")
        outDir.mkdirs()
        val out = p.convert(audio16k, cfg.copy(captureDebug = true))
        val names = ArrayList<String>()
        for ((k, v) in p.debug) {
            when (v) {
                is FloatArray -> {
                    Wav.writeF32(File(outDir, "dbg_$k.f32"), v); names += "dbg_$k.f32=${v.size}"
                }
                is LongArray -> {
                    File(outDir, "dbg_$k.i64").writeBytes(bytesOf(v)); names += "dbg_$k.i64=${v.size}"
                }
                is IntArray -> {
                    File(outDir, "dbg_$k.i32").writeBytes(bytesOf(v)); names += "dbg_$k.i32=${v.size}"
                }
                else -> names += "$k=${v}"
            }
        }
        L.i("debug 转储：" + names.joinToString(", "))
        Wav.writeF32(File(outDir, "out_40000_dump.f32"), out)
        return out
    }

    private fun bytesOf(a: LongArray): ByteArray {
        val bb = ByteBuffer.allocate(a.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        bb.asLongBuffer().put(a)
        return bb.array()
    }

    private fun bytesOf(a: IntArray): ByteArray {
        val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asIntBuffer().put(a)
        return bb.array()
    }

    fun close() {
        try { netG?.close() } catch (_: Exception) {}
        try { hubert?.close() } catch (_: Exception) {}
        try { rmvpe?.close() } catch (_: Exception) {}
        netG = null; hubert = null; rmvpe = null; pipeline = null
    }
}
