package atri.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.abs

/**
 * 极简 WAV 读写。
 * 读：支持 PCM 8/16/24/32-bit 整数与 IEEE float32，单声道/多声道（自动下混为单声道）。
 * 写：16-bit PCM。
 */
object Wav {

    class Data(val samples: FloatArray, val sampleRate: Int)

    fun read(file: File): Data = read(file.readBytes())

    fun read(bytes: ByteArray): Data {
        var pos = 0
        require(bytes.size >= 12) { "文件太小，不是 WAV" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "缺少 RIFF 头" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "缺少 WAVE 头" }
        pos = 12

        var fmtCode = 1
        var channels = 1
        var sampleRate = 16000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataLen = 0

        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = leInt(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    fmtCode = leShort(bytes, body).toInt()
                    channels = leShort(bytes, body + 2).toInt()
                    sampleRate = leInt(bytes, body + 4)
                    bitsPerSample = leShort(bytes, body + 14).toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataLen = size
                    if (dataLen <= 0 || body + dataLen > bytes.size) dataLen = bytes.size - body
                }
            }
            // chunk 按偶数字节对齐
            pos = body + size + (size and 1)
        }
        require(dataOffset >= 0) { "找不到 data chunk" }
        require(sampleRate > 0 && channels > 0) { "fmt 信息不合法" }

        val bytesPerSample = bitsPerSample / 8
        val frameCount = dataLen / (bytesPerSample * channels)
        val out = FloatArray(frameCount)
        val bb = ByteBuffer.wrap(bytes, dataOffset, dataLen).order(ByteOrder.LITTLE_ENDIAN)

        for (f in 0 until frameCount) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += when {
                    fmtCode == 3 && bitsPerSample == 32 -> bb.getFloat()
                    bitsPerSample == 16 -> bb.getShort().toFloat() / 32768f
                    bitsPerSample == 24 -> {
                        val b0 = bb.get().toInt() and 0xFF
                        val b1 = bb.get().toInt() and 0xFF
                        val b2 = bb.get().toInt()
                        ((b2 shl 16) or (b1 shl 8) or b0).toFloat() / 8388608f
                    }
                    bitsPerSample == 32 -> bb.getInt().toFloat() / 2147483648f
                    bitsPerSample == 8 -> ((bb.get().toInt() and 0xFF) - 128).toFloat() / 128f
                    else -> throw IllegalArgumentException("不支持的位深: $bitsPerSample")
                }
            }
            out[f] = acc / channels
        }
        return Data(out, sampleRate)
    }

    /** 写 16-bit PCM 单声道 WAV */
    fun write(file: File, samples: FloatArray, sampleRate: Int, peak: Float = 0.95f) {
        var maxAbs = 1e-12f
        for (s in samples) if (abs(s) > maxAbs) maxAbs = abs(s)
        val gain = peak / maxAbs

        val dataLen = samples.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)            // PCM
        bb.putShort(1)            // mono
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataLen)
        for (s in samples) {
            val v = (s * gain).coerceIn(-1f, 1f)
            bb.putShort(((v * 32767f).toInt()).toShort())
        }
        file.parentFile?.mkdirs()
        file.writeBytes(bb.array())
    }

    // ---- 裸二进制工具（与 Python 导出的基准对齐用）----

    fun readF32(file: File, count: Int): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size >= count * 4) { "${file.name}: 期望 $count 个 f32，实际只有 ${bytes.size / 4}" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val fb = bb.asFloatBuffer()
        val out = FloatArray(count)
        fb.get(out)
        return out
    }

    fun writeF32(file: File, arr: FloatArray) {
        val bb = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(arr)
        file.parentFile?.mkdirs()
        file.writeBytes(bb.array())
    }

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    @Suppress("unused")
    private fun unusedFloatBuffer(fb: FloatBuffer, sb: ShortBuffer) = Unit
}
