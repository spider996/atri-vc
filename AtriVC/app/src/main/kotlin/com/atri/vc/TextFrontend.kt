package com.atri.vc

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 端侧中文文本前端（G2P）—— GPT-SoVITS 的 `clean_text(zh)` 移植版。
 *
 * ## 为什么需要它
 *
 * PC 管线里 `clean_text` 依赖 pypinyin / jieba / g2pw / cn2an 四个 Python 包，
 * 端侧一个都带不动（g2pw 是个 RoBERTa 模型）。这里用**纯查表 + 规则**复刻，
 * 精度实测 **96.27%**（音素级），且端到端指标与官方**逐位一致**（见 tools/gsv_g2p_e2e.txt）。
 *
 * ## 资源（tools/tts_assets/，合计约 3.3 MB）
 *
 * | 文件 | 内容 | 体积 |
 * |---|---|---|
 * | `symbols.txt`  | 732 个符号，行号=id | 8 KB |
 * | `syl.txt`      | 音节 → `声母\|韵母调`（tone3） | 40 KB |
 * | `charmap.txt`  | 单字 → 候选读音（tone3，逗号分隔） | 620 KB |
 * | `poly.txt`     | **g2pw 消歧后的多音词**（45046 条） | 1.15 MB |
 * | `phrases.txt`  | pypinyin 词组读音（补充） | 1.3 MB |
 * | `p2s.txt`      | 拼音+调 → 2 个符号（opencpop-strict） | 60 KB |
 *
 * `poly.txt` 是精度的**主要来源**：它是 g2pw（RoBERTa 上下文推理）消歧后的结果，
 * 已把「银行→yin2,hang2」「重新→chong2,xin1」这类多音字定死。
 *
 * ## 与官方不同的三处（都是有意为之）
 *
 * 1. **分词**：官方用 jieba 的 `cut_for_search`（会产出重叠子词，再靠
 *    `pre_merge_for_modify` 去重）。这里用**最长正向匹配**（FMM）直接扫 `poly.txt`，
 *    不会过度切分，实测比"官方分词+我的规则"更接近官方最终输出。
 * 2. **词性**：官方变调规则依赖 jieba 的 POS 标注（`pos in {n,v,a}` 才轻声）。
 *    端侧没有 POS，用启发式近似（叠字、词尾字、白名单）。
 * 3. **数字/英文**：官方用 cn2an 把「123」转成「一百二十三」。这里**不做**，
 *    非汉字直接丢弃 —— 建议在 UI 层提示用户输入汉字。
 *
 * ## 数据表格式（都是 UTF-8 文本，逐行解析）
 *
 * ```
 * syl.txt       ni3\t n|i3          // 音节 tone3 -> 声母|韵母(带调)
 * charmap.txt   你\t ni3,ni2        // 单字 -> 候选读音（取第一个）
 * poly.txt      银行\t yin2,hang2   // 词 -> 逐字读音
 * p2s.txt       n i3\t n i3         // 拼音 -> 符号对
 * symbols.txt   (每行一个符号，行号即 id)
 * ```
 */
class TextFrontend(private val assets: File) {

    private val symbols = ArrayList<String>()
    private val symId = HashMap<String, Int>()

    /** 音节 tone3 -> (声母, 韵母带调号+调数字) */
    private val syl = HashMap<String, Pair<String, String>>()

    /** 单字 -> 候选读音（tone3） */
    private val charMap = HashMap<Char, List<String>>()

    /** 多音词 / 词组 -> 逐字读音（tone3） */
    private val poly = HashMap<String, List<String>>()
    private val phrases = HashMap<String, List<String>>()

    /** 拼音+调 -> (声母符, 韵母符) */
    private val p2s = HashMap<String, Pair<String, String>>()

    private var maxWordLen = 2

    val loaded: Boolean

    init {
        var ok = true
        ok = ok && loadSymbols()
        ok = ok && loadSyl()
        ok = ok && loadCharMap()
        ok = ok && loadPoly()
        ok = ok && loadPhrases()
        ok = ok && loadP2S()
        loaded = ok && symbols.isNotEmpty() && p2s.isNotEmpty()
    }

    private fun lines(name: String): List<String> {
        val f = File(assets, name)
        if (!f.exists()) return emptyList()
        return f.readText(StandardCharsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    }

    private fun loadSymbols(): Boolean {
        lines("symbols.txt").forEachIndexed { i, s ->
            val t = s.trimEnd('\r')
            symbols.add(t)
            symId[t] = i
        }
        return symbols.isNotEmpty()
    }

    private fun loadSyl(): Boolean {
        for (l in lines("syl.txt")) {
            val p = l.trimEnd('\r').split('\t')
            if (p.size != 2) continue
            val iv = p[1].split('|')
            if (iv.size != 2) continue
            syl[p[0]] = iv[0] to iv[1]
        }
        return syl.isNotEmpty()
    }

    private fun loadCharMap(): Boolean {
        for (l in lines("charmap.txt")) {
            val p = l.trimEnd('\r').split('\t')
            if (p.size != 2 || p[0].isEmpty()) continue
            charMap[p[0][0]] = p[1].split(',')
        }
        return charMap.isNotEmpty()
    }

    private fun loadDict(name: String, dst: HashMap<String, List<String>>): Boolean {
        for (l in lines(name)) {
            val p = l.trimEnd('\r').split('\t')
            if (p.size != 2 || p[0].isEmpty()) continue
            val v = p[1].split(',')
            // 只收「字数==读音数」的自洽条目，防止越界
            if (v.size != p[0].length) continue
            dst[p[0]] = v
            if (p[0].length > maxWordLen) maxWordLen = p[0].length
        }
        return dst.isNotEmpty()
    }

    private fun loadPoly(): Boolean = loadDict("poly.txt", poly)
    private fun loadPhrases(): Boolean = loadDict("phrases.txt", phrases)

    private fun loadP2S(): Boolean {
        for (l in lines("p2s.txt")) {
            val p = l.trimEnd('\r').split('\t')
            if (p.size != 2) continue
            val v = p[1].split(' ')
            if (v.size != 2) continue
            p2s[p[0]] = v[0] to v[1]
        }
        return p2s.isNotEmpty()
    }

    // ------------------------------------------------------------------ 归一化

    private val repMap = mapOf(
        '：' to ",", '；' to ",", '，' to ",", '。' to ".", '！' to "!", '？' to "?",
        '\n' to ".", '·' to ",", '、' to ",", '$' to ".", '/' to ",", '—' to "-",
        '~' to "…", '～' to "…",
    )

    /** 标点集合（与官方 `symbols.punctuation` 对齐；也是切句依据） */
    private val punct = "!?…,.-"

    private fun normalize(text: String): String {
        val sb = StringBuilder()
        var i = 0
        val t = text.replace("嗯", "恩").replace("呣", "母")
        while (i < t.length) {
            if (i + 2 < t.length && t.substring(i, i + 3) == "...") {
                sb.append('…'); i += 3; continue
            }
            val c = t[i]
            val rep = repMap[c]
            when {
                rep != null -> sb.append(rep)
                isHan(c) -> sb.append(c)
                c in punct -> sb.append(c)
                // 其余（数字/英文/其他符号）丢弃
            }
            i++
        }
        // 连续标点折叠
        val out = StringBuilder()
        for (c in sb) {
            if (c in punct && out.isNotEmpty() && out.last() in punct && out.last() == c) continue
            if (c in punct && out.isNotEmpty() && out.last() in punct) {
                out.setCharAt(out.length - 1, c); continue
            }
            out.append(c)
        }
        return out.toString()
    }

    private fun isHan(c: Char) = c.code in 0x4e00..0x9fa5

    // ------------------------------------------------------------------ 分词

    /** 词典命中：poly 优先于 phrases */
    private fun lookup(w: String): List<String>? = poly[w] ?: phrases[w]

    private class Seg(val word: String, val syls: List<String>)

    /**
     * 最长正向匹配。命中多音词/词组则整词取其读音，否则单字取首选读音；
     * 标点自成一段（读音 = 它自己）。
     */
    private fun segment(text: String): List<Seg> {
        val out = ArrayList<Seg>()
        var i = 0
        while (i < text.length) {
            val maxL = minOf(maxWordLen, text.length - i)
            var hit: Seg? = null
            for (len in maxL downTo 2) {
                val w = text.substring(i, i + len)
                val r = lookup(w)
                if (r != null) {
                    hit = Seg(w, r); break
                }
            }
            if (hit == null) {
                val c = text[i]
                hit = if (c in punct) Seg(c.toString(), listOf(c.toString()))
                else {
                    val cand = charMap[c]
                    Seg(c.toString(), listOf(if (cand.isNullOrEmpty()) "e5" else cand[0]))
                }
            }
            out.add(hit)
            i += hit.word.length
        }
        return out
    }

    /** 合并「不」「一」到后词；合并相邻同词（叠词） */
    private fun preMerge(segs: List<Seg>): List<Seg> {
        val step1 = ArrayList<Seg>()
        for (s in segs) {
            val last = step1.lastOrNull()
            if (last != null && (last.word == "不" || last.word == "一")) {
                step1[step1.size - 1] = Seg(last.word + s.word, last.syls + s.syls)
            } else {
                step1.add(s)
            }
        }
        val step2 = ArrayList<Seg>()
        for (s in step1) {
            val last = step2.lastOrNull()
            if (last != null && last.word == s.word) {
                step2[step2.size - 1] = Seg(last.word + s.word, last.syls + s.syls)
            } else {
                step2.add(s)
            }
        }
        return step2
    }

    // ------------------------------------------------------------------ 变调

    private fun toneOf(s: String): Char = if (s.isEmpty()) '5' else s.last()

    private fun setTone(s: String, t: Char): String =
        if (s.isEmpty()) "" else s.dropLast(1) + t

    /** 「不」变调：后字 4 声 -> 不读 2 声；「不」夹在三字中间 -> 轻声 */
    private fun buSandhi(w: String, f: MutableList<String>): MutableList<String> {
        if (w.length == 3 && w[1] == '不') {
            f[1] = setTone(f[1], '5')
        } else {
            for (i in w.indices) {
                if (w[i] == '不' && i + 1 < w.length && toneOf(f[i + 1]) == '4') {
                    f[i] = setTone(f[i], '2')
                }
            }
        }
        return f
    }

    /** 「一」变调：后字 4 声 -> 2 声；后字非 4 声且非标点 -> 4 声 */
    private fun yiSandhi(w: String, f: MutableList<String>): MutableList<String> {
        if (w.contains('一') && w.filter { it != '一' }.all { it.isDigit() }) return f
        if (w.length == 3 && w[1] == '一' && w[0] == w[2]) {
            f[1] = setTone(f[1], '5')
        } else if (w.startsWith("第一")) {
            f[1] = setTone(f[1], '1')
        } else {
            for (i in w.indices) {
                if (w[i] == '一' && i + 1 < w.length) {
                    if (toneOf(f[i + 1]) == '4') f[i] = setTone(f[i], '2')
                    else if (w[i + 1] !in punct) f[i] = setTone(f[i], '4')
                }
            }
        }
        return f
    }

    private val mustNeuralTail = "吧呢哈啊呐噻嘛吖嗨哦哒额滴哩哟喽啰耶喔诶"
    private val mustNotNeural = setOf(
        "男子", "女子", "分子", "原子", "量子", "莲子", "石子", "瓜子", "电子", "人人",
        "虎虎", "幺幺", "干嘛", "学子", "哈哈", "数数", "袅袅", "局地", "以下", "想想",
        "攘攘", "卵子", "死死", "冉冉", "恳恳", "佼佼", "吵吵", "打打", "考考", "整整",
        "莘莘", "落地", "算子", "家家户户", "青青", "天天", "年年", "娃娃", "家家",
        "人人", "区区",
    )

    /** 轻声：叠字 / 词尾虚词 / 量词「个」 */
    private fun neuralSandhi(w: String, f: MutableList<String>): MutableList<String> {
        if (w !in mustNotNeural) {
            for (j in 1 until w.length) {
                if (w[j] == w[j - 1]) f[j] = setTone(f[j], '5')
            }
        }
        val ge = w.indexOf('个')
        when {
            w.isNotEmpty() && w.last() in mustNeuralTail -> f[f.size - 1] = setTone(f.last(), '5')
            w.isNotEmpty() && (w.last() == '的' || w.last() == '地' || w.last() == '得') ->
                f[f.size - 1] = setTone(f.last(), '5')
            w.length == 1 && (w[0] == '了' || w[0] == '着' || w[0] == '过') ->
                f[0] = setTone(f[0], '5')
            w.length > 1 && (w.last() == '们' || w.last() == '子') && w !in mustNotNeural ->
                f[f.size - 1] = setTone(f.last(), '5')
            w.length > 1 && (w.last() == '上' || w.last() == '下' || w.last() == '里') ->
                f[f.size - 1] = setTone(f.last(), '5')
            w.length > 1 && (w.last() == '来' || w.last() == '去') &&
                w[w.length - 2] in "上下进出回过起开" -> f[f.size - 1] = setTone(f.last(), '5')
            w.length == 2 && w[0] == w[1] && w !in mustNotNeural ->
                f[f.size - 1] = setTone(f.last(), '5')
            (ge >= 1 && (w[ge - 1].isDigit() || w[ge - 1] in "几有两半多各整每做是这那哪好些")) ||
                w == "个" -> f[ge] = setTone(f[ge], '5')
        }
        return f
    }

    /** 三声连读：两字全三声 -> 前字变 2 声 */
    private fun threeSandhi(w: String, f: MutableList<String>): MutableList<String> {
        if (w.length == 2 && f.all { toneOf(it) == '3' }) {
            f[0] = setTone(f[0], '2')
        } else if (w.length == 3 && f.all { toneOf(it) == '3' }) {
            // 2+1 或 1+2 切分
            val first2IsWord = lookup(w.substring(0, 2)) != null
            if (first2IsWord) {
                f[0] = setTone(f[0], '2'); f[1] = setTone(f[1], '2')
            } else {
                f[1] = setTone(f[1], '2')
            }
        } else if (w.length == 4) {
            for (s in 0..1) {
                val a = s * 2
                if (toneOf(f[a]) == '3' && toneOf(f[a + 1]) == '3') f[a] = setTone(f[a], '2')
            }
        }
        return f
    }

    private fun modifiedTone(w: String, orig: List<String>): List<String> {
        var f = orig.toMutableList()
        f = buSandhi(w, f)
        f = yiSandhi(w, f)
        f = neuralSandhi(w, f)
        f = threeSandhi(w, f)
        return f
    }

    private val mustErhua = setOf("小院儿", "胡同儿", "范儿", "老汉儿", "撒欢儿",
        "寻老礼儿", "妥妥儿", "媳妇儿")
    private val notErhuaTail = setOf("女儿", "男儿", "婴儿", "幼儿", "孤儿", "患儿",
        "花儿", "鸟儿", "虫儿", "马儿", "狗儿", "猫儿", "猪儿", "少儿")

    /**
     * 儿化：词尾「儿」读作 `er` + 前字声调（如 院儿 → `er4`）。
     * 官方 `_merge_erhua` 还要看 POS，这里只按词表排除「女儿」这类真·儿字词。
     */
    private fun erhua(w: String, ini: List<String>, fin: List<String>):
        Pair<List<String>, List<String>> {
        if (fin.size != w.length || fin.size < 2 || w.last() != '儿') return ini to fin
        if (w.takeLast(2) in notErhuaTail && w !in mustErhua) return ini to fin
        val nf = fin.toMutableList()
        nf[nf.size - 1] = "er" + toneOf(nf[nf.size - 2])
        return ini to nf
    }

    // ------------------------------------------------------------------ 主入口

    class Result(val phones: IntArray, val word2ph: IntArray, val norm: String)

    /**
     * 文本 → phone id 序列 + word2ph。
     *
     * - `phones` 直接是 `symbols.txt` 的行号，可直接喂 ONNX
     * - `word2ph[i]` 表示第 i 个字占几个 phone（中文=2，标点=1），
     *   BERT 侧要用它把 `[1,L,1024]` 展开成 `[sum(word2ph),1024]`
     */
    fun g2p(text: String): Result {
        val norm = normalize(text)
        val phones = ArrayList<Int>()
        val word2ph = ArrayList<Int>()

        // 按标点切句（官方 g2p 的 split 模式：(?<=[punct])\s*）
        val sents = ArrayList<String>()
        var cur = StringBuilder()
        for (c in norm) {
            cur.append(c)
            if (c in punct) { sents.add(cur.toString()); cur = StringBuilder() }
        }
        if (cur.isNotEmpty()) sents.add(cur.toString())

        for (sent in sents) {
            if (sent.isBlank()) continue
            for (seg in preMerge(segment(sent))) {
                val w = seg.word
                val ini = ArrayList<String>()
                val fin = ArrayList<String>()
                for (s in seg.syls) {
                    val iv = syl[s]
                    when {
                        iv != null -> { ini.add(iv.first); fin.add(iv.second) }
                        s.length == 1 && s[0] in punct -> { ini.add(s); fin.add(s) }
                        else -> { ini.add(""); fin.add("e5") }
                    }
                }
                val fin2 = modifiedTone(w, fin)
                val (ini2, fin3) = erhua(w, ini, fin2)

                for (i in ini2.indices) {
                    val c = ini2[i]
                    val v = if (i < fin3.size) fin3[i] else "e5"
                    if (c == v) {
                        phones.add(symId[c] ?: symId["SP"] ?: 0)
                        word2ph.add(1)
                        continue
                    }
                    val tone = toneOf(v)
                    val vNoTone = if (v.isEmpty()) "" else v.dropLast(1)
                    var py = c + vNoTone
                    if (c.isNotEmpty()) {
                        py = when (vNoTone) {
                            "uei" -> c + "ui"; "iou" -> c + "iu"; "uen" -> c + "un"
                            else -> c + vNoTone
                        }
                    } else {
                        py = when (py) {
                            "ing" -> "ying"; "i" -> "yi"; "in" -> "yin"; "u" -> "wu"
                            else -> {
                                val head = py.firstOrNull()
                                when (head) {
                                    'v' -> "yu" + py.drop(1)
                                    'e' -> "e" + py.drop(1)
                                    'i' -> "y" + py.drop(1)
                                    'u' -> "w" + py.drop(1)
                                    else -> py
                                }
                            }
                        }
                    }
                    val pair = p2s[py]
                    if (pair == null) {
                        phones.add(symId["UNK"] ?: 0)
                        word2ph.add(1)
                    } else {
                        phones.add(symId[pair.first] ?: 0)
                        phones.add(symId[pair.second + tone] ?: 0)
                        word2ph.add(2)
                    }
                }
            }
        }
        return Result(phones.toIntArray(), word2ph.toIntArray(), norm)
    }

    /** BERT 用的字→id 表（中文 RoBERTa 是字级词表，无需 WordPiece） */
    fun symbolCount(): Int = symbols.size
}
