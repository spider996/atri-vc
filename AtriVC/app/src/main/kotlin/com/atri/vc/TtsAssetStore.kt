package com.atri.vc

import android.content.Context
import java.io.File

/**
 * TTS 侧资源（模型 + 文本前端数据表 + 参考特征）的目录管理。
 *
 * ## 目录布局
 *
 * ```
 * <ext>/tts/                     外部私有目录优先（adb push 方便）
 *   ├─ 模型
 *   │   bert.onnx                 288.2 MB
 *   │   t2s_prefill.onnx            0.2 MB
 *   │   t2s_decode.onnx             0.2 MB
 *   │   t2s_shared.data           145.3 MB   （prefill/decode 共享权重）
 *   │   t2s_encoder.onnx           10.5 MB
 *   │   t2s_step_embed.onnx         8.8 MB
 *   │   vits.onnx                 161.8 MB
 *   ├─ 文本前端（assets/，约 3.4 MB）
 *   │   symbols.txt / syl.txt / charmap.txt / poly.txt / phrases.txt
 *   │   p2s.txt / bert_vocab.txt
 *   └─ ref/
 *       ref_joy.refbin / ref_sad.refbin / ref_angry.refbin / ref_calm.refbin
 * ```
 *
 * 内部私有目录是兜底（首次启动可从 assets 解包，避免外置存储不可用）。
 */
object TtsAssetStore {

    /** 模型清单：[文件名] -> 说明。用于 UI 展示与完整性校验 */
    val MODELS = linkedMapOf(
        "bert.onnx" to "文本理解（字级 RoBERTa，int8）",
        "t2s_encoder.onnx" to "序列编码器",
        "t2s_prefill.onnx" to "AR 首帧",
        "t2s_decode.onnx" to "AR 逐帧",
        "t2s_step_embed.onnx" to "token 位置嵌入",
        "vits.onnx" to "SoVITS 声码器",
    )

    /** external data，prefill/decode 都要读它，单独列出来提示用户别漏 */
    val SHARED_DATA = "t2s_shared.data"

    /** 文本前端数据表 */
    val ASSETS = listOf(
        "symbols.txt", "syl.txt", "charmap.txt", "poly.txt",
        "phrases.txt", "p2s.txt", "bert_vocab.txt",
    )

    fun externalDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "ext"), "tts")

    fun internalDir(ctx: Context): File = File(ctx.filesDir, "tts")

    fun candidateDirs(ctx: Context): List<File> = listOf(externalDir(ctx), internalDir(ctx))

    /** 当前生效目录（优先外部；都为空则用外部并创建） */
    fun dir(ctx: Context): File =
        candidateDirs(ctx).firstOrNull { d ->
            d.listFiles()?.any { it.isFile || it.name == "ref" } == true
        } ?: externalDir(ctx).also { it.mkdirs() }

    fun modelsDir(ctx: Context): File = dir(ctx)
    fun refDir(ctx: Context): File = File(dir(ctx), "ref").also { it.mkdirs() }

    fun locate(ctx: Context, name: String): File? =
        candidateDirs(ctx).map { File(it, name) }
            .firstOrNull { it.exists() && it.length() > 16 }

    class Item(val name: String, val note: String, val present: Boolean, val bytes: Long)

    class Status(val items: List<Item>, val sharedDataPresent: Boolean) {
        /** 六个模型 + 共享权重都齐才算可加载 */
        val ready: Boolean get() = items.all { it.present } && sharedDataPresent

        val missing: List<String>
            get() = items.filter { !it.present }.map { it.name } +
                (if (sharedDataPresent) emptyList() else listOf(SHARED_DATA))

        val totalBytes: Long get() = items.sumOf { it.bytes }

        val totalMb: Double get() = totalBytes / 1048576.0
    }

    fun status(ctx: Context): Status {
        val items = MODELS.map { (n, note) ->
            val f = locate(ctx, n)
            Item(n, note, f != null, f?.length() ?: 0L)
        }
        val sd = locate(ctx, SHARED_DATA)
        return Status(items, sd != null)
    }

    /** 文本前端数据表是否齐（缺了 G2P 会退化成全 UNK） */
    fun assetsReady(ctx: Context): Boolean =
        ASSETS.all { locate(ctx, it) != null }

    /** 参考特征是否齐 */
    fun refsReady(ctx: Context): List<String> =
        RefFeatureStore.EMOTIONS.filter { RefFeatureStore.exists(ctx, it) }

    /**
     * 首次启动：把打包进 assets/ 的文本前端数据表解包到工作目录。
     *
     * 只负责小文件（几 MB）。大模型走 [ensureExtractedFromAssets]，那里带进度回调。
     * 注：参考特征 refbin 也由 [ensureExtractedFromAssets] 统一处理（要判魔数版本）。
     */
    fun unpackTextAssets(ctx: Context): Int {
        val dst = dir(ctx)
        dst.mkdirs()
        var n = 0
        for (name in ASSETS) {
            val out = File(dst, name)
            if (out.exists() && out.length() > 16) continue
            runCatching {
                ctx.assets.open("tts/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                n++
            }.onFailure { L.w("解包 $name 失败：${it.message}") }
        }
        if (n > 0) L.i("unpackTextAssets 解包 $n 个文件到 $dst")
        return n
    }

    // ---------------------------------------------------------------- 全量包自动导入

    /** assets 里是否带了 TTS 大模型（= 这是全量包） */
    fun hasBundledModels(ctx: Context): Boolean = try {
        !ctx.assets.list("tts").isNullOrEmpty()
    } catch (e: Exception) {
        false
    }

    /**
     * 把 APK 里的 TTS 资源释放到工作目录：六个模型 + t2s_shared.data +
     * 文本前端数据表 + 4 条参考特征。
     *
     * 与 [unpackTextAssets] 的区别：这个函数**大小比对**做幂等
     * （610 MB 的模型重传一次代价太大），并且会回调进度。
     * refbin 额外用魔数判版本 —— v01 少了 phones 段会让 xy_pos 长度对不上基线。
     *
     * @return 实际复制的文件数
     */
    fun ensureExtractedFromAssets(
        ctx: Context,
        onProgress: ((done: Int, total: Int, name: String) -> Unit)? = null,
    ): Int {
        val dst = dir(ctx).also { it.mkdirs() }
        val rd = refDir(ctx)

        // 组装任务表：(assets 路径, 目标文件, 是否用魔数判版本)
        val jobs = mutableListOf<Triple<String, File, Boolean>>()
        for ((name, _) in MODELS) jobs += Triple("tts/$name", File(dst, name), false)
        jobs += Triple("tts/$SHARED_DATA", File(dst, SHARED_DATA), false)
        for (name in ASSETS) jobs += Triple("tts/$name", File(dst, name), false)
        for (emo in RefFeatureStore.EMOTIONS) {
            jobs += Triple("tts/ref/ref_$emo.refbin", File(rd, "ref_$emo.refbin"), true)
        }

        var done = 0
        var copied = 0
        for ((assetPath, out, byMagic) in jobs) {
            done++
            // refbin 只认当前魔数：旧版 v01 缺 phones 段，必须覆盖重解
            val upToDate = if (byMagic) {
                out.exists() && out.length() > 64 && magicOf(out) == RefFeatureStore.MAGIC
            } else {
                val want = assetSizeOf(ctx, assetPath)
                out.exists() && out.length() > 16 && (want <= 0 || out.length() == want)
            }
            if (upToDate) {
                onProgress?.invoke(done, jobs.size, out.name)
                continue
            }
            try {
                ctx.assets.open(assetPath).use { ins ->
                    out.outputStream().use { outs -> ins.copyTo(outs, 1 shl 20) }
                }
                copied++
                L.i("导入 ${out.name} (%.1f MB)".format(out.length() / 1048576.0))
            } catch (t: Throwable) {
                L.w("导入 ${out.name} 失败：${t.message}")
                runCatching { if (out.exists()) out.delete() }
            }
            onProgress?.invoke(done, jobs.size, out.name)
        }
        return copied
    }

    private fun assetSizeOf(ctx: Context, path: String): Long = try {
        ctx.assets.openFd(path).use { it.length }
    } catch (e: Exception) {
        -1L
    }

    /** 读文件前 8 字节魔数（失败返回空串） */
    private fun magicOf(f: File): String = runCatching {
        val b = ByteArray(8)
        f.inputStream().use { it.read(b) }
        String(b, Charsets.US_ASCII)
    }.getOrDefault("")
}
