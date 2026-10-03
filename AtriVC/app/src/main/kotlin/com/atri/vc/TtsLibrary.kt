package com.atri.vc

import android.content.Context
import java.io.File

/**
 * 「常用语音」收藏库 —— 最多保留 [MAX] 条，按 **LFU（最不经常使用）** 淘汰。
 *
 * ## 为什么要做
 *
 * 端侧合成一次要几十秒（AR 自回归 + SoVITS 声码器全在手机上跑），
 * 而同一条语音经常要反复用（例如 QQ 里同一句话要发好几次）。
 * 每次重新合成纯属浪费，所以把最近的合成结果留一份，点一下就能再播。
 *
 * ## 淘汰策略
 *
 * 每条记录带一个使用计数 [Entry.count]，**试听或设为待发送时 +1**
 * （见 [touch]）。存满 [MAX] 条后每新增一条就淘汰一条：
 * 计数最小者先走；计数相同则**最久没用过**的先走（LFU + LRU 兜底）。
 *
 * ## 落盘格式
 *
 * `<外部私有目录>/tts_library/index.tsv`，一行一条，Tab 分隔：
 *
 * ```
 * 文件名 \t 计数 \t 最近使用(ms) \t 情绪 \t 文本
 * ```
 *
 * 音频本体是 wav 副本，与索引同目录。索引损坏/丢行时按「文件存在」兜底重建，
 * 不会因为一次写坏就让收藏整个消失。
 */
object TtsLibrary {

    /** 最多保留条数（用户要求 10） */
    const val MAX = 10

    private const val INDEX = "index.tsv"

    /** TTS 输出固定 32 kHz 单声道 16 bit —— 时长可以直接由文件长度反推，不必读头 */
    private const val SR = 32000
    private const val BYTES_PER_SAMPLE = 2
    private const val WAV_HEADER = 44

    class Entry(
        val file: File,
        val count: Int,
        val lastUsed: Long,
        val emotion: String,
        val text: String,
    ) {
        /** 时长（秒），直接由 wav 文件长度算，避免为了列个表去读每个文件 */
        val seconds: Float
            get() {
                val n = (file.length() - WAV_HEADER).coerceAtLeast(0L)
                return n.toFloat() / (SR * BYTES_PER_SAMPLE)
            }

        /** 列表里显示的短文本 */
        val preview: String
            get() = text.replace('\n', ' ').trim().let {
                if (it.length <= 14) it else it.take(14) + "…"
            }
    }

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "tts_library").also { it.mkdirs() }

    private fun indexFile(ctx: Context): File = File(dir(ctx), INDEX)

    /** 读取收藏列表，按「用得最多 -> 最近用过」排序 */
    fun list(ctx: Context): List<Entry> {
        val rows = readIndex(ctx)
        // 文件被外部删掉的条目直接丢弃，同时清理索引
        val alive = rows.filter { it.file.exists() && it.file.length() > WAV_HEADER }
        if (alive.size != rows.size) writeIndex(ctx, alive)
        return alive.sortedWith(
            compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsed }
        )
    }

    /**
     * 收一条新合成结果进库。
     *
     * @param src    合成落盘的 wav
     * @param text   这次合成的文本（列表里显示用）
     * @param emo    情绪代号（joy/sad/angry/calm）
     */
    fun add(ctx: Context, src: File, text: String, emo: String) {
        if (!src.exists()) return
        val dst = File(dir(ctx), src.name)
        runCatching { if (!dst.exists() || dst.length() != src.length()) src.copyTo(dst, overwrite = true) }
            .onFailure { L.w("TtsLibrary 拷贝失败：${it.message}"); return }

        val rows = list(ctx).filter { it.file.name != dst.name }.toMutableList()
        rows.add(Entry(dst, 0, System.currentTimeMillis(), emo, text))
        val kept = evict(rows)
        writeIndex(ctx, kept)
        L.i("TtsLibrary 收录 ${dst.name}（${text.take(12)}…），当前 ${kept.size}/$MAX 条")
    }

    /** 记一次使用：计数 +1 并刷新最近使用时间。UI 在试听 / 设为待发送时调用。 */
    fun touch(ctx: Context, e: Entry) {
        val rows = list(ctx).map {
            if (it.file.name == e.file.name)
                Entry(it.file, it.count + 1, System.currentTimeMillis(), it.emotion, it.text)
            else it
        }
        writeIndex(ctx, rows)
        L.i("TtsLibrary 使用计数 ${e.file.name} ${e.count} -> ${e.count + 1}")
    }

    fun remove(ctx: Context, e: Entry) {
        runCatching { e.file.delete() }
        writeIndex(ctx, list(ctx).filter { it.file.name != e.file.name })
        L.i("TtsLibrary 删除 ${e.file.name}")
    }

    /** 超上限就按 LFU 淘汰：计数小者先走，同计数则最久未用者先走 */
    private fun evict(rows: MutableList<Entry>): List<Entry> {
        while (rows.size > MAX) {
            val victim = rows.minWithOrNull(
                compareBy<Entry> { it.count }.thenBy { it.lastUsed }
            ) ?: break
            L.i("TtsLibrary LFU 淘汰 ${victim.file.name}（计数=${victim.count}）")
            runCatching { victim.file.delete() }
            rows.remove(victim)
        }
        return rows
    }

    // ------------------------------------------------------------ 索引读写

    private fun readIndex(ctx: Context): List<Entry> {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        val d = dir(ctx)
        return runCatching {
            f.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val p = line.split('\t')
                if (p.size < 4) return@mapNotNull null
                Entry(
                    file = File(d, p[0]),
                    count = p[1].toIntOrNull() ?: 0,
                    lastUsed = p[2].toLongOrNull() ?: 0L,
                    emotion = p[3],
                    text = if (p.size >= 5) p.subList(4, p.size).joinToString("\t") else "",
                )
            }
        }.onFailure { L.w("TtsLibrary 索引读取失败：${it.message}") }.getOrDefault(emptyList())
    }

    private fun writeIndex(ctx: Context, rows: List<Entry>) {
        runCatching {
            // 文本里的 Tab / 换行会破坏 TSV 结构，落盘前压成空格
            val body = rows.joinToString("\n") {
                "${it.file.name}\t${it.count}\t${it.lastUsed}\t${it.emotion}\t" +
                    it.text.replace('\t', ' ').replace('\n', ' ')
            }
            indexFile(ctx).writeText(body)
        }.onFailure { L.w("TtsLibrary 索引写入失败：${it.message}") }
    }
}
