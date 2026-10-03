package com.atri.vc

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 参考音频特征读取器（`.refbin` 格式，由 `tools/gsv_pack_refbin.py` 生成）。
 *
 * 端侧**不做**参考音频编码：参考是固定的几条（高兴/悲伤/生气/平淡），
 * 其 ssl / sv / prompt / refer 全部离线预计算好，省掉 `ssl`+`sv`+`prompt`+`spec`
 * 四个模型（fp32 合计 543 MB）。
 *
 * ## 文件格式（小端）
 *
 * ```
 * magic    8 B    "ATRREF02"
 * n_ssl    i32    ssl_content 帧数
 * n_prompt i32    codes_prompt 长度
 * n_refer  i32    refer 帧数
 * n_phone  i32    参考文本 phone 个数（v01 此处是保留位 = 0）
 * ssl      f32[n_ssl * 768]
 * sv       f32[20480]
 * prompt   i32[n_prompt]
 * refer    f32[1025 * n_refer]
 * phones   i32[n_phone]
 * ```
 *
 * `refer` 必须按 `[1, 1025, w]` 传给 vits —— 1025 = 513（线性谱）+ 512（B 维），
 * 这是 v2ProPlus 的固定配置。
 *
 * `phones` 是**参考文本的日文 phone 序列**，由 `tools/gsv_export_refphones.py`
 * 离线算出（端侧只做中文 G2P，算不出日文）。它必须与 `prompt` / BERT 长度自洽：
 * `xy_pos` 长度 = `n_phone` + 目标文本 phone 数 + `n_prompt`。
 */
object RefFeatureStore {

    const val MAGIC = "ATRREF02"
    private const val MAGIC_V01 = "ATRREF01"

    /** 四种情绪 */
    val EMOTIONS = listOf("joy", "sad", "angry", "calm")

    val LABELS = mapOf(
        "joy" to "高兴",
        "sad" to "悲伤",
        "angry" to "生气",
        "calm" to "平淡",
    )

    /** 每条情绪参考音频对应的文本（合成时作为 ref_text，影响韵律对齐） */
    val REF_TEXTS = mapOf(
        "joy" to "ですよねー。よかったぁ",
        "sad" to "もし住む人がいなくなったら、ずっと夜みたいになっちゃうのかなと……",
        "angry" to "ああーー！なんですかぁその足！まさか浮気ですか！？",
        "calm" to "より有用性の高いヒューマノイドになるためです",
    )

    fun file(ctx: android.content.Context, emo: String): File =
        File(TtsAssetStore.dir(ctx), "ref/ref_$emo.refbin")

    fun exists(ctx: android.content.Context, emo: String): Boolean =
        file(ctx, emo).let { it.exists() && it.length() > 64 }

    class Loaded(val emo: String, val feat: TtsEngine.RefFeatures)

    /**
     * 读取一条参考特征。
     *
     * 输入为空时返回 null（调用方应提示用户先准备参考音频）。
     */
    fun load(ctx: android.content.Context, emo: String): Loaded? {
        val f = file(ctx, emo)
        if (!f.exists()) {
            L.w("RefFeatureStore 缺少 $f")
            return null
        }
        val raf = RandomAccessFile(f, "r")
        try {
            val magic = ByteArray(8)
            raf.readFully(magic)
            val magicStr = String(magic, Charsets.US_ASCII)
            require(magicStr == MAGIC || magicStr == MAGIC_V01) {
                "refbin 魔数不符：$magicStr（期望 $MAGIC）"
            }
            val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            raf.readFully(head.array())
            val nSsl = head.getInt()
            val nPrompt = head.getInt()
            val nRefer = head.getInt()
            val nPhone = head.getInt()                       // v01 里是 0

            val ssl = readFloats(raf, nSsl * 768)
            val sv = readFloats(raf, 20480)
            val prompt = readInts(raf, nPrompt)
            val refer = readFloats(raf, nRefer * 1025)
            // v01 没有 phones 段（文件到 refer 就结束了）
            val phones =
                if (magicStr == MAGIC && nPhone > 0) readInts(raf, nPhone)
                else IntArray(0)

            if (phones.isEmpty()) {
                L.w("RefFeatureStore $emo 的 refbin 是 v01 或缺少 phones 段 —— " +
                    "请重跑 tools/gsv_pack_refbin.py（参考音频是日文，端侧算不出 phone）")
            }
            L.i("RefFeatureStore $emo n_ssl=$nSsl n_prompt=$nPrompt n_refer=$nRefer n_phone=${phones.size}")
            return Loaded(emo, TtsEngine.RefFeatures(ssl, sv, prompt, refer, nSsl, phones))
        } finally {
            raf.close()
        }
    }

    private fun readFloats(raf: RandomAccessFile, n: Int): FloatArray {
        val buf = ByteArray(n * 4)
        raf.readFully(buf)
        val fb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val a = FloatArray(n)
        fb.get(a)
        return a
    }

    private fun readInts(raf: RandomAccessFile, n: Int): IntArray {
        val buf = ByteArray(n * 4)
        raf.readFully(buf)
        val ib = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val a = IntArray(n)
        ib.get(a)
        return a
    }
}
