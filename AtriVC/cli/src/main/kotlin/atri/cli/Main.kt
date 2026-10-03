package atri.cli

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import atri.core.FaissIvfIndex
import atri.core.RvcConfig
import atri.core.RvcPipeline
import atri.core.Wav
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import java.util.concurrent.ConcurrentHashMap

private val report = StringBuilder()

private fun say(s: String) {
    println(s)
    report.append(s).append('\n')
}

private fun loadTensor(file: File, dtype: String, shape: IntArray): Any {
    val n = shape.fold(1L) { a, b -> a * b }.toInt()
    val bytes = file.readBytes()
    require(bytes.size >= n * (if (dtype == "i64") 8 else 4)) {
        "${file.name}: 期望 $n 个 $dtype，文件只有 ${bytes.size} 字节"
    }
    return when (dtype) {
        "f32" -> Wav.readF32(file, n)
        "i32" -> {
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            IntArray(n).also { bb.asIntBuffer().get(it) }
        }
        "i64" -> {
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            LongArray(n).also { bb.asLongBuffer().get(it) }
        }
        else -> error("未知 dtype $dtype")
    }
}

private fun asFloats(a: Any): FloatArray = when (a) {
    is FloatArray -> a
    is IntArray -> FloatArray(a.size) { a[it].toFloat() }
    is LongArray -> FloatArray(a.size) { a[it].toFloat() }
    else -> error("不支持的类型 ${a::class}")
}

private class Cmp(val name: String, val maxAbs: Double, val meanAbs: Double, val cos: Double, val n: Int) {
    val ok: Boolean get() = cos > 0.999 || maxAbs < 1e-3
    override fun toString(): String =
        String.format("  %-20s n=%-9d maxAbs=%-11.3e meanAbs=%-11.3e cos=%-10.8f %s",
            name, n, maxAbs, meanAbs, cos, if (ok) "PASS" else "!! 差异偏大")
}

private fun compare(name: String, mine: Any, ref: Any): Cmp {
    val a = asFloats(mine); val b = asFloats(ref)
    val n = minOf(a.size, b.size)
    var maxAbs = 0.0
    var sumAbs = 0.0
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in 0 until n) {
        val d = (a[i] - b[i]).toDouble()
        val ad = abs(d)
        if (ad > maxAbs) maxAbs = ad
        sumAbs += ad
        dot += a[i].toDouble() * b[i]
        na += a[i].toDouble() * a[i]
        nb += b[i].toDouble() * b[i]
    }
    val cos = if (na > 0 && nb > 0) dot / (sqrt(na) * sqrt(nb)) else 0.0
    return Cmp(name, maxAbs, sumAbs / n, cos, n)
}

fun main(args: Array<String>) {
    var refDir = File("C:/Users/21264/WorkBuddy/2026-09-28-10-31-09/tools/android_ref")
    var modelsDir = File("C:/Users/21264/WorkBuddy/2026-09-28-10-31-09/tools/onnx_q8")
    var outDir = File("C:/Users/21264/WorkBuddy/2026-09-28-10-31-09/tools/kotlin_out")
    var indexFile: File? = File("C:/Users/21264/WorkBuddy/2026-09-28-10-31-09/tools/android_ref/index_mobile.bin")
    var useRefRnd = true
    var indexRate = 0.3f
    var skipNetG = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--ref" -> refDir = File(args[++i])
            "--models" -> modelsDir = File(args[++i])
            "--out" -> outDir = File(args[++i])
            "--index" -> indexFile = File(args[++i])
            "--no-index" -> indexFile = null
            "--index-rate" -> indexRate = args[++i].toFloat()
            "--random-rnd" -> useRefRnd = false
            "--skip-netg" -> skipNetG = true
            else -> error("未知参数 ${args[i]}")
        }
        i++
    }
    outDir.mkdirs()

    say("=== Kotlin 管线 vs Python 基准 逐级对齐 ===")
    say("ref    = $refDir")
    say("models = $modelsDir")
    say("index  = ${indexFile ?: "(不用)"}  rate=$indexRate")
    say("rnd    = ${if (useRefRnd) "复用基准噪声(可做逐位比对)" else "随机"}")

    val manifest = ConcurrentHashMap<String, Pair<String, IntArray>>()
    val manFile = File(refDir, "manifest.txt")
    manFile.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        if (line.contains("SPECIAL")) return@forEachLine
        val parts = line.trim().split(" ")
        if (parts.size < 3) return@forEachLine
        manifest[parts[0]] = parts[1] to IntArray(parts.size - 2) { parts[it + 2].toInt() }
    }

    fun refOf(name: String): Any? {
        val m = manifest[name] ?: return null
        val f = File(refDir, name)
        if (!f.exists()) return null
        return loadTensor(f, m.first, m.second)
    }

    val audio = refOf("audio16k.f32") as FloatArray
    say("输入音频 %d samples (%.2f s @16k)".format(audio.size, audio.size / 16000.0))

    val env = OrtEnvironment.getEnvironment()
    val opts = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors())
    }

    val netG = env.createSession(File(modelsDir, "atri_net_g_t1024_q8.onnx").absolutePath, opts)
    val hub = env.createSession(File(modelsDir, "hubert_dyn8.onnx").absolutePath, opts)
    val rm = env.createSession(File(modelsDir, "rmvpe_q8.onnx").absolutePath, opts)
    val index = indexFile?.takeIf { it.exists() }?.let { FaissIvfIndex.load(it) }
    index?.let { say("index: nlist=${it.nlist} d=${it.d} ntotal=${it.ntotal}") }

    val pipe = RvcPipeline(env, netG, hub, rm, index) { stage, pct ->
        print("\r  [%3d%%] %-16s".format((pct * 100).toInt(), stage))
    }

    if (useRefRnd) {
        val m = manifest["blocks_rnd.f32"]!!
        val all = loadTensor(File(refDir, "blocks_rnd.f32"), m.first, m.second)
        val flat = all as FloatArray
        val per = 192 * 1024
        pipe.rndProvider = { blk ->
            if (blk * per + per <= flat.size) flat.copyOfRange(blk * per, blk * per + per) else null
        }
    }

    val cfg = RvcConfig(
        outSampleRate = 40000,
        pitchShift = 0,
        indexRate = indexRate,
        protect = 0.33f,
        sid = 0,
        overlapFrames = 64,
        topK = 8,
        seed = 1234L,
        captureDebug = true,
    )

    if (skipNetG) {
        say("--skip-netg：只跑到特征阶段")
    }

    val t0 = System.nanoTime()
    val out = pipe.convert(audio, cfg)
    val ms = (System.nanoTime() - t0) / 1_000_000
    val dur = audio.size / 16000.0
    println()
    say("Kotlin 全链路耗时 %.0f ms  (音频 %.2f s -> RTF=%.3f)".format(ms.toDouble(), dur, ms / 1000.0 / dur))
    say("net_g 输入名映射: ${pipe.resolvedNetGInputs()}")
    say("--- 分阶段耗时 (ms) ---")
    for ((k, v) in pipe.timings) say("  %-8s %d".format(k, v))

    Wav.writeF32(File(outDir, "out_40000.f32"), out)
    say("输出 %d samples -> %s".format(out.size, File(outDir, "out_40000.f32")))

    say("")
    say("=== 逐级对比（mine vs python ref）===")
    val results = ArrayList<Cmp>()
    for (name in listOf(
        "hubert_feat", "feat_after_index", "feat_interp", "feat0_interp",
        "mel", "rmvpe_hidden", "f0", "pitch", "pitchf",
        "protect_weight", "feat_final", "blocks_phone", "blocks_audio",
    )) {
        val mine = pipe.debug[name] ?: continue
        val r = refOf(if (name in listOf("hubert_feat", "feat_after_index", "feat_interp", "feat0_interp", "mel", "rmvpe_hidden", "f0", "pitch", "pitchf", "protect_weight", "feat_final", "blocks_phone", "blocks_audio")) "$name.f32" else name)
            ?: refOf("$name.i64") ?: continue
        val c = compare(name, mine, r)
        results.add(c)
        say(c.toString())
    }

    val refOut = refOf("out_40000.f32")
    if (refOut != null) {
        val c = compare("out_40000", out, refOut)
        results.add(c)
        say(c.toString())
    }

    val bad = results.count { !it.ok }
    say("")
    say(if (bad == 0) "全部 %d 级对齐通过 ✓".format(results.size) else "有 %d 级差异偏大 ✗".format(bad))

    File(outDir, "align_kotlin.txt").writeText(report.toString())
    say("报告: ${File(outDir, "align_kotlin.txt")}")

    netG.close(); hub.close(); rm.close()
}
