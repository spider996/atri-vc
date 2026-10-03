package com.atri.vc

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

/**
 * 模型仓库。
 *
 * 双目录查找（谁有文件用谁）：
 *  1. 外部私有目录 Android/data/com.atri.vc/files/models/  —— adb push / 文件管理器都能放
 *  2. 内部私有目录 /data/data/com.atri.vc/files/models/    —— 沙盒内一定可写（run-as 拷贝）
 *
 * 注意 Android 11+ 的一个坑：
 *  - adb push 到外部目录会把文件属主留成 shell，App 有时仍读不到；
 *  - 而 `run-as` 进程的 SELinux 域是 runas_app，过不了 /sdcard 的 FUSE，写不进去。
 *  所以真机/模拟器灌模型时，走「adb push 到 /data/local/tmp → run-as cp 到内部目录」最稳。
 *
 * 三件套：
 *  - atri_net_g_t1024_q8.onnx   45.4MB  合成器（INT8 静态量化）
 *  - hubert_dyn8.onnx          116.8MB  内容特征（INT8 动态量化）
 *  - rmvpe_q8.onnx              94.0MB  音高（INT8 静态量化）
 *  - index_mobile.bin           30.1MB  faiss IVF 检索结构（可选）
 */
object ModelStore {

    const val NET_G = "atri_net_g_t1024_q8.onnx"
    const val HUBERT = "hubert_dyn8.onnx"
    const val RMVPE = "rmvpe_q8.onnx"
    const val INDEX = "index_mobile.bin"

    val required = listOf(NET_G, HUBERT, RMVPE)
    val optional = listOf(INDEX)

    /** 外部私有目录：Android/data/com.atri.vc/files/models */
    fun externalDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "ext"), "models")

    /** 内部私有目录：/data/data/com.atri.vc/files/models */
    fun internalDir(ctx: Context): File = File(ctx.filesDir, "models")

    /** 搜索顺序：外部优先（方便随时替换），再内部 */
    fun candidateDirs(ctx: Context): List<File> = listOf(externalDir(ctx), internalDir(ctx))

    /** 按文件名在候选目录里定位，找不到返回 null */
    fun locate(ctx: Context, name: String): File? =
        candidateDirs(ctx).map { File(it, name) }.firstOrNull { it.exists() && it.length() > 1024 }

    /** 展示用主目录：优先返回真的装着东西的那个 */
    fun primaryDir(ctx: Context): File =
        candidateDirs(ctx).firstOrNull { d -> d.listFiles()?.any { it.isFile } == true }
            ?: externalDir(ctx)

    fun modelDir(ctx: Context): File = primaryDir(ctx).also { it.mkdirs() }

    /** 两个目录都给出来，便于排查 */
    fun modelDirHint(ctx: Context): String = candidateDirs(ctx).joinToString("\n") { it.absolutePath }

    class Status(
        val items: List<Item>,
    ) {
        val ready: Boolean get() = required.all { name -> items.first { it.name == name }.present }
    }

    class Item(val name: String, val present: Boolean, val sizeBytes: Long, val required: Boolean)

    fun status(ctx: Context): Status {
        val items = (required.map { it to true } + optional.map { it to false }).map { (n, req) ->
            val f = locate(ctx, n)
            Item(n, f != null, f?.length() ?: 0L, req)
        }
        return Status(items)
    }

    // ---------------------------------------------------------------- 全量包自动导入

    /**
     * 把打包进 APK 的模型释放到工作目录 —— 「装完就能用」的关键一环。
     *
     * 只在**全量包**（`model_assets/models/` 里有东西）时才有活干；
     * 轻量包 assets 里没有 models/ 目录，这里会立刻返回 0，不影响原来的 adb push 流程。
     *
     * 已存在且大小一致的文件直接跳过 —— 所以重复调用是幂等的，
     * 用户自己替换过的模型也不会被覆盖。
     *
     * @param onProgress 已复制文件数 / 总文件数 的回调（切后台线程前会调到 UI）
     * @return 实际复制的文件数
     */
    fun ensureExtractedFromAssets(
        ctx: Context,
        onProgress: ((done: Int, total: Int, name: String) -> Unit)? = null,
    ): Int {
        val names = try {
            ctx.assets.list("models")?.filter { !it.isNullOrBlank() }?.sorted() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (names.isEmpty()) return 0

        val dir = externalDir(ctx).also { it.mkdirs() }
        var done = 0
        var copied = 0

        for (n in names) {
            val dst = File(dir, n)
            val assetSize = assetSizeOf(ctx, "models/$n")
            done++
            // 大小一致 = 已经导过了（也覆盖「用户自己替换成别的同内容模型」的情况）
            val upToDate = dst.exists() && dst.length() > 1024 &&
                (assetSize <= 0 || dst.length() == assetSize)
            if (upToDate) {
                onProgress?.invoke(done, names.size, n)
                continue
            }
            try {
                ctx.assets.open("models/$n").use { ins ->
                    FileOutputStream(dst).use { outs -> ins.copyTo(outs, 1 shl 20) }
                }
                copied++
                L.i("导入模型 $n ($(%.1f MB))".format(dst.length() / 1048576.0))
            } catch (t: Throwable) {
                L.w("导入模型 $n 失败：${t.message}")
                // 半截文件必须删掉，否则下次会被当成「已存在」而永久损坏
                runCatching { if (dst.exists()) dst.delete() }
            }
            onProgress?.invoke(done, names.size, n)
        }
        return copied
    }

    /**
     * 读 assets 里某个文件的字节数。
     *
     * `.onnx` / `.bin` / `.data` 在 build.gradle.kts 里声明了 noCompress，
     * 所以正常情况下 openFd() 能拿到真实长度；万一被压缩了就退化成 -1，
     * 此时跳过「大小比对」改用「存在即跳过」。
     */
    private fun assetSizeOf(ctx: Context, path: String): Long = try {
        ctx.assets.openFd(path).use { it.length }
    } catch (e: Exception) {
        -1L
    }

    /** assets 里是否带了模型（= 这是全量包） */
    fun hasBundledModels(ctx: Context): Boolean = try {
        !ctx.assets.list("models").isNullOrEmpty()
    } catch (e: Exception) {
        false
    }

    fun externalRootHint(): String =
        Environment.getExternalStorageDirectory().absolutePath
}
