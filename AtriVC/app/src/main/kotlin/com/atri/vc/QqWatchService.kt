package com.atri.vc

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/**
 * 监听 QQ，在它进入录音态时自动外放待发送语音。
 *
 * ## 为什么必须用无障碍
 *
 * 非 root 下没有 API 能查「麦克风现在被谁占用」，也拿不到别的 App 的内部状态。
 * 无障碍服务是**唯一**能在用户没额外操作时感知「QQ 正在录音」的合法途径
 * （`CAPTURE_AUDIO_OUTPUT` 是 signature 权限，`AudioPlaybackCapture` 排除语音）。
 *
 * ## ⚠️ 为什么要轮询而不是只听事件（实测教训）
 *
 * 第一版只订阅 `TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED`。
 * 实测：进聊天会话能收到事件，但**按下录音键之后一条事件都没有** ——
 * QQ 的录音面板是自绘 View，不改无障碍节点树也不发事件。
 * 所以改为**主动轮询** `rootInActiveWindow`：QQ 在前台时 400ms 一次，
 * 不在前台时降到 3s 省电。事件回调仍然保留（能提前唤醒），但判据只认轮询结果。
 *
 * ## ⚠️ 「按住说话」不能当判据
 *
 * 实测 QQ 切到语音输入模式后，界面上**一直**有「按住说话」（还没按就有）。
 * 拿它当判据会在用户刚切到语音模式、还没开始录时就播出去，等真按下时音频早放完了。
 * 所以只用「按下之后才出现」的文本（见 `RECORD_HINTS`），`IDLE_HINTS` 仅用于日志。
 *
 * ## 诊断开关
 *
 * 往外部目录放 `qq_dump.txt`（内容 `1`），本服务会把 QQ 界面上读到的文本打进 logcat，
 * 用来确认当前 QQ 版本到底能读到什么、该用哪条判据：
 *
 * ```
 * adb shell "echo -n 1 > /sdcard/Android/data/com.atri.vc/files/qq_dump.txt"
 * adb logcat -s AtriVC:* | grep "QqWatch"
 * ```
 *
 * ## 权限
 *
 * 必须在系统「设置 → 无障碍」里手动开启本服务，App 内无法静默授予。
 */
class QqWatchService : AccessibilityService() {

    companion object {
        private const val TAG = "QqWatch"
        private const val QQ = "com.tencent.mobileqq"

        /** QQ 在前台时的轮询间隔；录音面板不发事件，只能靠轮询抓 */
        private const val POLL_FAST_MS = 400L
        /** QQ 不在前台时的轮询间隔（省电） */
        private const val POLL_SLOW_MS = 3000L
        /** 同一次录音内不重复触发 */
        private const val COOLDOWN_MS = 4000L
        /** 转储节流 */
        private const val DUMP_THROTTLE_MS = 1200L

        /**
         * 「按下之后才出现」的文本 = 真在录音。命中任一即触发外放。
         *
         * 这些是各版本/皮肤里见过的写法，多留几个没关系（宁可多命中）。
         */
        private val RECORD_HINTS = listOf(
            "松开 发送", "松开,发送", "松开发送", "松手发送", "松开结束",
            "手指上滑", "上滑取消", "取消发送", "正在录音", "录音中",
        )

        /**
         * 只是「切到语音输入模式」就存在的文本 —— **不能触发**，
         * 仅用于日志里区分「已按 / 未按」。
         */
        private val IDLE_HINTS = listOf(
            "按住说话", "按住 说话", "点击录音", "开始录音", "按住变声",
        )

        @Volatile
        var running = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastTrigger = 0L
    private var lastHint = ""
    private var lastDump = 0L
    private var dumpCheckedAt = 0L
    private var dumpOn = false

    private val poll = object : Runnable {
        override fun run() {
            val delay = try {
                tick()
            } catch (t: Throwable) {
                L.w("$TAG 轮询异常：${t.message}")
                POLL_SLOW_MS
            }
            handler.postDelayed(this, delay)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        // 服务可能是由系统单独拉起的（App 进程已被回收），
        // 此时 pendingFile 是空的 → 必须在这里把落盘状态读回来。
        VoiceSender.bind(this)
        L.i("$TAG 无障碍服务已连接 armed=${VoiceSender.armed} pending=${VoiceSender.pendingFile?.name}")
        handler.post(poll)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /** 事件回调只用来「顺手查一次」，不作为唯一判据（录音期间 QQ 不发事件） */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == QQ) handler.post { runCatching { tick() } }
    }

    /** 查一次当前界面；返回下次轮询间隔 */
    private fun tick(): Long {
        val root = rootInActiveWindow
        if (root == null) return POLL_SLOW_MS
        val isQq = root.packageName?.toString() == QQ
        if (!isQq) return POLL_SLOW_MS

        val texts = ArrayList<String>()
        collectTexts(root, texts, 0)

        if (dumpWanted()) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDump > DUMP_THROTTLE_MS) {
                lastDump = now
                L.i("$TAG DUMP n=${texts.size} :: ${texts.take(40).joinToString(" | ")}")
                L.i(
                    "$TAG DUMP 命中=${matchHint(texts)} idle=${IDLE_HINTS.any { h -> texts.any { it.contains(h) } }} " +
                        "armed=${VoiceSender.armed} pending=${VoiceSender.pendingFile?.name}"
                )
            }
        }

        if (VoiceSender.armed && VoiceSender.pendingFile != null) {
            val hit = matchHint(texts)
            if (hit != null) {
                val now = SystemClock.elapsedRealtime()
                if (!(hit == lastHint && now - lastTrigger < COOLDOWN_MS)) {
                    lastHint = hit
                    lastTrigger = now
                    L.i("$TAG 检测到 QQ 录音态（命中「$hit」）→ 自动外放")
                    VoiceSender.playNow(this)
                }
            }
        }
        return POLL_FAST_MS
    }

    private fun matchHint(texts: List<String>): String? {
        for (t in texts) for (h in RECORD_HINTS) if (t.contains(h)) return h
        return null
    }

    /** 外部目录下 `qq_dump.txt` 内容是否为 1（每 2 秒才重新读一次盘） */
    private fun dumpWanted(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - dumpCheckedAt > 2000L) {
            dumpCheckedAt = now
            dumpOn = runCatching {
                File(getExternalFilesDir(null), "qq_dump.txt").let { it.exists() && it.readText().trim() == "1" }
            }.getOrDefault(false)
        }
        return dumpOn
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: ArrayList<String>, depth: Int) {
        if (node == null || depth > 12 || out.size > 400) return
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), out, depth + 1)
        }
    }
}
