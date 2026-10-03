package atri.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.max

/**
 * 把 faiss IndexIVFFlat 导出成移动端可直接加载的结构，并在 Kotlin 侧复现
 * nprobe=1 / k=8 的检索语义（与 RVC 的 feature retrieval 完全一致）。
 *
 * 文件格式（小端）：
 *   char[8]  "ATRIIDX1"
 *   int32    nlist, d, ntotal
 *   int32    starts[nlist+1]        倒排表 CSR 偏移
 *   float32  centroids[nlist*d]
 *   int32    ids[ntotal]            每个槽位对应的原始向量下标
 *   float32  vectors[ntotal*d]
 *
 * 距离口径是 faiss 的 L2²（metric_type=1）。
 */
class FaissIvfIndex private constructor(
    val nlist: Int,
    val d: Int,
    val ntotal: Int,
    private val starts: IntArray,
    private val centroids: FloatArray,
    private val ids: IntArray,
    private val vectors: FloatArray,
) {

    /** 预计算每个向量的 ||v||²，把距离改成一次点积 */
    private val vecNormSq: FloatArray = FloatArray(ntotal) { i ->
        var s = 0f
        val off = i * d
        for (k in 0 until d) { val v = vectors[off + k]; s += v * v }
        s
    }
    private val centNormSq: FloatArray = FloatArray(nlist) { i ->
        var s = 0f
        val off = i * d
        for (k in 0 until d) { val v = centroids[off + k]; s += v * v }
        s
    }

    class Result(val distances: FloatArray, val indices: IntArray)

    /**
     * 复刻 faiss 的 IVFFlat 检索：
     *  1. 对 256 个聚类中心做 L2² 最近邻，取 argmin（nprobe=1 只看 1 个倒排表）
     *  2. 扫该表，取 k 个 L2² 最小的
     * 表内不足 k 个时用 (-1, +inf) 补齐，语义与 faiss 的堆初始化一致。
     */
    fun search(query: FloatArray, k: Int = 8): Result {
        require(query.size == d)
        val qNorm = FloatArray(1)
        var qs = 0f
        for (i in 0 until d) { val v = query[i]; qs += v * v }
        qNorm[0] = qs

        // --- 1. 找最近的倒排表 ---
        var bestList = 0
        var bestDist = Float.MAX_VALUE
        for (li in 0 until nlist) {
            val cOff = li * d
            var dot = 0f
            for (j in 0 until d) dot += query[j] * centroids[cOff + j]
            val dist = qs - 2f * dot + centNormSq[li]
            if (dist < bestDist) { bestDist = dist; bestList = li }
        }

        // --- 2. 扫表取 top-k ---
        val from = starts[bestList]
        val to = starts[bestList + 1]
        val cnt = to - from
        val dists = FloatArray(k) { Float.MAX_VALUE }
        val idxs = IntArray(k) { -1 }
        var filled = 0

        for (slot in from until to) {
            val vid = ids[slot]
            val vOff = vid * d
            var dot = 0f
            for (j in 0 until d) dot += query[j] * vectors[vOff + j]
            val dist = qs - 2f * dot + vecNormSq[vid]

            if (filled < k) {
                dists[filled] = dist
                idxs[filled] = vid
                filled++
                if (filled == k) {
                    // 插入排序一次
                    for (a in 1 until k) {
                        val dv = dists[a]; val iv = idxs[a]
                        var b = a - 1
                        while (b >= 0 && dists[b] > dv) { dists[b + 1] = dists[b]; idxs[b + 1] = idxs[b]; b-- }
                        dists[b + 1] = dv; idxs[b + 1] = iv
                    }
                }
            } else if (dist < dists[k - 1]) {
                dists[k - 1] = dist
                idxs[k - 1] = vid
                var b = k - 2
                while (b >= 0 && dists[b] > dists[b + 1]) {
                    val td = dists[b]; dists[b] = dists[b + 1]; dists[b + 1] = td
                    val ti = idxs[b]; idxs[b] = idxs[b + 1]; idxs[b + 1] = ti
                    b--
                }
            }
        }
        return Result(dists, idxs)
    }

    /**
     * RVC 的复现：k=8 加权平均后与原始特征按 indexRate 混合。
     * numpy 的 `big_npy[ix]` 在 ix=-1 时会取最后一行，这里保持一致。
     * @return 混合后的特征 (T, d)
     */
    fun mix(feats: Array<FloatArray>, indexRate: Float, k: Int = 8): Array<FloatArray> {
        val t = feats.size
        val out = Array(t) { FloatArray(d) }
        val w = FloatArray(k)
        val blended = FloatArray(d)

        for (f in 0 until t) {
            val r = search(feats[f], k)
            var wsum = 0.0
            for (i in 0 until k) {
                // weight = (1/score)^2
                val s = max(r.distances[i], 1e-12f).toDouble()
                val inv = 1.0 / s
                w[i] = (inv * inv).toFloat()
                wsum += w[i]
            }
            java.util.Arrays.fill(blended, 0f)
            for (i in 0 until k) {
                val wi = (w[i] / wsum).toFloat()
                val vi = if (r.indices[i] < 0) ntotal - 1 else r.indices[i]
                val vOff = vi * d
                for (j in 0 until d) blended[j] += vectors[vOff + j] * wi
            }
            val src = feats[f]
            val dst = out[f]
            for (j in 0 until d) dst[j] = blended[j] * indexRate + (1f - indexRate) * src[j]
        }
        return out
    }

    companion object {
        private val MAGIC = "ATRIIDX1".toByteArray(Charsets.US_ASCII)

        fun load(file: File): FaissIvfIndex {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { ch ->
                val size = ch.size()
                val bb = ByteBuffer.allocate(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                while (bb.hasRemaining()) {
                    if (ch.read(bb) < 0) break
                }
                bb.flip()

                val magic = ByteArray(8)
                bb.get(magic)
                require(magic.contentEquals(MAGIC)) { "不是 ATRIIDX1 格式: ${file.name}" }

                val nlist = bb.int
                val d = bb.int
                val ntotal = bb.int

                val starts = IntArray(nlist + 1)
                bb.asIntBuffer().get(starts)
                bb.position(bb.position() + (nlist + 1) * 4)

                val centroids = FloatArray(nlist * d)
                bb.asFloatBuffer().get(centroids)
                bb.position(bb.position() + nlist * d * 4)

                val ids = IntArray(ntotal)
                bb.asIntBuffer().get(ids)
                bb.position(bb.position() + ntotal * 4)

                val vectors = FloatArray(ntotal * d)
                bb.asFloatBuffer().get(vectors)

                return FaissIvfIndex(nlist, d, ntotal, starts, centroids, ids, vectors)
            }
        }
    }
}
