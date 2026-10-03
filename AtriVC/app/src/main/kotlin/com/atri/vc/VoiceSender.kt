package com.atri.vc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * 「QQ 语音代发」的播放核心。
 *
 * ## 方案：**扬声器外放 → 手机自带麦克风**（零 root、任何机型通用）
 *
 * ```
 * 手机扬声器 ──空气（约 10 cm）──▶ 手机自带麦克风 ──▶ QQ
 * ```
 *
 * 非 root 下**没有**虚拟麦克风 API（真机取证：`dumpsys media.audio_policy` 里
 * 只有 5 个物理输入设备，无任何虚拟设备）：
 * - `CAPTURE_AUDIO_OUTPUT` 是 `signature|privileged`，只有系统签名应用能拿
 * - `AudioPlaybackCapture` 明确排除 `USAGE_VOICE_COMMUNICATION`（通话/语音正是这一类）
 * - 向别的 App 注入「按住/松开」手势要 `INJECT_EVENTS`，同样是签名权限
 *
 * 所以只能走**声学回环**，而回环成败的唯一因素是「声源离麦克风多近」：
 *
 * - ✅ **手机扬声器 → 手机麦**：距离几厘米、无遮挡 → **可行**（本类默认）
 * - ❌ **蓝牙耳机喇叭 → 耳机麦**：喇叭冲耳道、麦克风朝外，被耳壳隔开 →
 *   **实测证伪**（`HeadsetLoopTest` 第 1/2 组，回环增益 −1.8 / −16.8 dB，
 *   播放时比静音还安静）。A2DP 与 HFP/SCO 是两条独立链路，耳机不会内部互联。
 *
 * ⚠️ 所以 `Route.SPEAKER`（`USAGE_MEDIA` + `STREAM_MUSIC` 抬到 60%）才是**能用的那条**；
 * `Route.HEADSET` 保留只作「播放给自己听」，**此模式下 QQ 会录成静音**。
 *
 * 外放模式下**不做** AEC/NS/AGC 处理：那是 QQ 录音侧的事，我们关不掉别人的 session。
 *
 * ## 使用姿势（比实现更重要）
 *
 * **必须用系统小窗/分屏让 QQ 保持在前台**。若用返回键切到本 App，
 * QQ 退到后台会暂停采集麦克风，录出来就是一段静音 ——
 * 这正是「QQ 录音里没有任何声音」的真正原因（同类免 root 变声器的官方教程
 * 也明确写了必须靠小窗/分屏，且只在小米等支持小窗的机型上可用）。
 *
 * ## 交互形态：**点一次播一次**（不循环）
 *
 * 早期版本用「循环外放 20 秒」来覆盖「按住说话多久」这个不确定量。
 * 实测下来这是错的设计：
 * - 循环播放**没法跟用户的动作对齐** —— QQ 只取按下到松开那一段，
 *   循环多出来的部分全是白放，且松手后还在响；
 * - 用户松手后音频仍在播，会被 QQ 的下一次录音录进去（串音）；
 * - 白噪声源，很吵。
 *
 * 现在改成：**点一下 → 播一遍 → 播完即停**（本类 [playNow]），
 * 用户自己掐点：先按播放，再按住 QQ 说话键，松手前音频正好放完。
 *
 * 这正是同类产品的做法 —— 一个悬浮小窗按钮，点一下放一遍
 * （参见 [FloatingPlayService]，交互形态照抄，原理完全不用它的）。
 *
 * ## 为什么要落盘持久化
 *
 * `pendingFile` / `armed` 本来只是内存变量。但无障碍服务是由系统拉起的：
 * 用户从最近任务里划掉 App → 进程死 → 系统重新绑定服务 → 新进程里这两个值全是
 * 初始值，于是「开着开关却什么都不播」。所以状态写进 `qq_pending.txt`，
 * 每次服务连接时 `restore()` 一次。
 */
object VoiceSender {

    /** 待发送的音频（最近一次合成成功的结果） */
    @Volatile
    var pendingFile: File? = null
        private set

    /** 是否启用自动代发（总开关） */
    @Volatile
    var armed: Boolean = false

    /** 播放状态回调（给 UI 显示「正在发送…」） */
    @Volatile
    var onState: ((State) -> Unit)? = null

    enum class State { IDLE, PLAYING, DONE, ERROR }

    /** 音频路由方式 */
    enum class Route {
        /** 走蓝牙/有线耳机 —— ⚠️ 已实测证伪：耳机麦收不到喇叭声，QQ 会录成静音 */
        HEADSET,
        /** 手机扬声器外放 —— **唯一可行的那条通路**（默认） */
        SPEAKER,
    }

    /** 当前路由方式，默认外放（唯一可行的那条） */
    @Volatile
    var route: Route = Route.SPEAKER

    /** 最近一次播放时实际使用的输出设备名（给 UI 显示，验证是否真的走耳机） */
    @Volatile
    var lastOutputName: String = ""
        private set

    /** 最近一次播放时系统识别到的麦克风设备名（验证录音端是否切到耳机麦） */
    @Volatile
    var lastInputName: String = ""
        private set

    /**
     * 最近一次播放时，是否因为有耳机连着而「外放根本没出声」。
     *
     * UI 拿这个值弹醒目提示 —— 这是用户最可能踩、又最不可能自己想到的坑。
     */
    @Volatile
    var lastBlockedByHeadset: Boolean = false
        private set

    private const val FILE = "qq_pending.txt"

    /**
     * 外放时的目标音量档位比例。
     *
     * 为什么不拉满：扬声器开满会「吓人一跳」（用户实测反馈 70% 仍偏响），
     * 而且离得近时反而更容易触发麦克风/前级的限幅，录出来是糊的。
     * 取 60% 兼顾「QQ 能听清」与「不炸耳朵」。
     */
    private const val TARGET_VOLUME_RATIO = 0.60f

    /** 淡入时长（毫秒）。避免第一帧就是满音量。 */
    private const val FADE_IN_MS = 350L

    /** 淡入步进间隔 */
    private const val FADE_STEP_MS = 25L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var appCtx: Context? = null

    /** 循环播放的自动停止任务 */
    private val stopTask = Runnable { stop() }

    /** 循环模式预计停止的时间戳（UI 倒计时用），0 = 未在播放 */
    @Volatile
    var loopEndsAt: Long = 0L
        private set

    /** 播放前被我们抬高的系统音量原值；-1 表示没动过、不用恢复 */
    private var restoreVolumeTo = -1

    /** 淡入用的任务 */
    private var fadeTask: Runnable? = null

    /**
     * 播放完成回调（不论成功失败都会回调一次）。
     *
     * 悬浮窗要据此把按钮从「停止」切回「播放」，不然按钮会一直卡在「停止」。
     */
    @Volatile
    var onFinished: (() -> Unit)? = null

    /**
     * 绑定 Application 上下文并恢复落盘状态。
     * 入口：MainViewModel 初始化、QqWatchService.onServiceConnected。
     */
    @Synchronized
    fun bind(ctx: Context) {
        appCtx = ctx.applicationContext
        if (pendingFile == null) restore()
        L.i("VoiceSender bind: armed=$armed pending=${pendingFile?.name} route=$route")
    }

    /** 装载一条待发送语音（合成成功后调用） */
    @Synchronized
    fun arm(file: File?) {
        pendingFile = file
        persist()
        L.i("VoiceSender 装载待发送语音：${file?.absolutePath}")
    }

    @Synchronized
    fun clear() {
        pendingFile = null
        persist()
    }

    @Synchronized
    fun setArmedState(v: Boolean) {
        armed = v
        persist()
    }

    @Synchronized
    fun applyRoute(r: Route) {
        route = r
        L.i("VoiceSender 路由方式 = $r")
    }

    val ready: Boolean get() = armed && pendingFile != null

    // ------------------------------------------------------------ 设备探测

    /** 判断一个输出设备算不算「耳机」（蓝牙/有线/USB，能把声音关在耳朵里） */
    private fun isHeadsetOut(d: AudioDeviceInfo): Boolean =
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    /**
     * 当前有没有可用的耳机输出（蓝牙 A2DP/SCO、有线、USB）。
     *
     * 没有耳机时耳机方案会「退化」成外放（系统找不到 SCO 就回退到扬声器），
     * 那时 AEC 又会来削声音 —— 所以 UI 必须先提示用户戴上耳机。
     */
    fun hasHeadset(ctx: Context): Boolean = runCatching {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isHeadsetOut(it) }
    }.getOrDefault(false)

    /** 当前耳机名（没有则空串），给 UI 显示 */
    fun headsetName(ctx: Context): String = runCatching {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return ""
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { isHeadsetOut(it) }
            ?.productName?.toString().orEmpty()
    }.getOrDefault("")

    /** 当前输入（麦克风）设备名 —— 用来验证录音端到底用了哪个麦 */
    fun inputName(ctx: Context): String = runCatching {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return ""
        val bt = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        bt?.productName?.toString() ?: "手机麦"
    }.getOrDefault("")

    // ------------------------------------------------------------ 持久化

    private fun stateFile(): File? = appCtx?.getExternalFilesDir(null)?.let { File(it, FILE) }

    private fun persist() {
        val f = stateFile() ?: return
        runCatching {
            f.writeText(
                (if (armed) "1" else "0") + "\n" + (pendingFile?.absolutePath ?: "") +
                    "\n" + route.name
            )
        }.onFailure { L.w("VoiceSender 写状态失败：${it.message}") }
    }

    private fun restore() {
        val f = stateFile() ?: return
        val txt = runCatching { f.readText() }.getOrNull() ?: return
        val lines = txt.split("\n")
        armed = lines.getOrNull(0)?.trim() == "1"
        val p = lines.getOrNull(1)?.trim().orEmpty()
        pendingFile = if (p.isNotEmpty()) File(p).takeIf { it.exists() } else null
        lines.getOrNull(2)?.trim()?.let { r ->
            Route.entries.firstOrNull { it.name == r }?.let { route = it }
        }
    }

    // ------------------------------------------------------------ 播放

    /**
     * **播一次就停**（主入口）。
     *
     * 用法：点一下 → 立刻按住 QQ 的说话键 → 录到这段 → 松手发送。
     * 音频结束时会回调 [onFinished]，悬浮窗据此把按钮切回「播放」。
     */
    @Synchronized
    fun playNow(ctx: Context): Boolean = start(ctx, loop = false, seconds = 0)

    /**
     * 循环外放 `seconds` 秒后自动停。
     *
     * ⚠️ 主交互**不用这个**了（用户明确要求去掉循环，见类头 KDoc）。
     * 保留它只为一个场景：无障碍服务自动触发时，用户可能还没切到 QQ，
     * 需要多响一会儿才来得及按住说话。手动操作一律走 [playNow]。
     */
    @Synchronized
    fun playLoop(ctx: Context, seconds: Int): Boolean = start(ctx, loop = true, seconds = seconds)

    private fun start(ctx: Context, loop: Boolean, seconds: Int): Boolean {
        appCtx = ctx.applicationContext
        val f = pendingFile
        if (f == null) {
            L.w("VoiceSender 没有待发送语音")
            onState?.invoke(State.ERROR)
            return false
        }
        if (!f.exists()) {
            L.w("VoiceSender 待发送语音已不存在：${f.absolutePath}")
            onState?.invoke(State.ERROR)
            return false
        }

        stopInternal()

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val hasHs = hasHeadset(ctx)
        val useHeadset = route == Route.HEADSET

        // 音量策略：外放不拉满，只抬到 60%，播完恢复原值。
        // 耳机方案完全不动音量（耳机本来就够响，拉满会炸耳朵）。
        var savedVolume = -1
        if (!useHeadset && am != null) {
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = (maxVol * TARGET_VOLUME_RATIO).toInt().coerceIn(1, maxVol)
            // 只在「当前比目标小」时才抬音量；用户主动调大过就尊重用户的选择
            if (before < target) {
                savedVolume = before
                runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                L.i("VoiceSender[外放] 音量 $before -> $target / $maxVol（${(TARGET_VOLUME_RATIO * 100).toInt()}%，播完恢复）")
            } else {
                L.i("VoiceSender[外放] 音量 $before / $maxVol 已不低于目标 $target，保持不动")
            }
        }
        restoreVolumeTo = savedVolume

        val usage = if (useHeadset) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        L.i(
            "VoiceSender 开始：route=$route usage=$usage 耳机=${if (hasHs) "有(" + headsetName(ctx) + ")" else "无！"} " +
                "输入=${inputName(ctx)}"
        )
        if (useHeadset && !hasHs) {
            L.w("VoiceSender ⚠️ 选了耳机方案但当前没有耳机 → 声音会从扬声器出，且会被 QQ 的 AEC 压制")
        }
        // ⚠️ 外放模式最常见的「没用」原因：蓝牙耳机还连着。
        // 此时 STREAM_MUSIC 会被路由到 bt_a2dp，声音进耳机、不进空气 → 手机麦收不到。
        // 用户看到的现象是「播了，但 QQ 里还是没声音」，极难自己联想到是蓝牙没断。
        var btBlocking = false
        if (!useHeadset && hasHs) {
            // 只有「耳机把 MUSIC 流抢走了」才算阻断；有线/USB 同样会抢
            btBlocking = true
            L.w(
                "VoiceSender ⚠️⚠️ 当前连着耳机（" + headsetName(ctx) + "），" +
                    "外放的声音会被路由到耳机而不是扬声器 —— 手机麦收不到，QQ 会录成静音。" +
                    "请先断开蓝牙/拔掉耳机再试。"
            )
        }

        return try {
            val p = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(
                            if (useHeadset) AudioAttributes.CONTENT_TYPE_SPEECH
                            else AudioAttributes.CONTENT_TYPE_MUSIC
                        )
                        .build()
                )
                setDataSource(f.absolutePath)
                isLooping = loop
                setOnCompletionListener {
                    L.i("VoiceSender 播放结束")
                    if (!loop) {
                        loopEndsAt = 0L
                        restoreVolume()
                        onState?.invoke(State.DONE)
                        onFinished?.invoke()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    L.e("VoiceSender 播放失败 what=$what extra=$extra")
                    loopEndsAt = 0L
                    restoreVolume()
                    onState?.invoke(State.ERROR)
                    onFinished?.invoke()
                    true
                }
                setVolume(0f, 0f)
                prepare()
                start()
            }
            mp = p
            fadeIn(p)

            // 记录实际输出设备（验证确实走了耳机而不是扬声器）。
            // ⚠️ start() 之后立刻调 getRoutedDevice() 常常返回 null —— 
            //    音频通路还没建好。所以短轮询几次，拿到就停。
            lastInputName = inputName(ctx)
            lastOutputName = "未知"
            lastBlockedByHeadset = btBlocking
            mainHandler.post(object : Runnable {
                var tries = 0
                override fun run() {
                    val n = runCatching { p.getRoutedDevice()?.productName?.toString() }.getOrNull()
                    if (!n.isNullOrEmpty()) {
                        lastOutputName = n
                        L.i("VoiceSender 实际输出设备已解析：$n")
                        return
                    }
                    if (tries++ < 12) mainHandler.postDelayed(this, 150)
                    else L.w("VoiceSender getRoutedDevice() 始终为空，无法确认输出设备")
                }
            })

            // 显式关掉系统音效（AEC / 降噪 / 自动增益），
            // 否则系统可能把我们自己放出来的声音再削一道，戴上耳机也白搭
            val fxInfo = if (useHeadset) disableEffects(p.audioSessionId) else "外放模式未处理"

            L.i("VoiceSender 实际输出=${lastOutputName} 输入=${lastInputName} 音效=$fxInfo")

            if (loop && seconds > 0) {
                loopEndsAt = System.currentTimeMillis() + seconds * 1000L
                mainHandler.removeCallbacks(stopTask)
                mainHandler.postDelayed(stopTask, seconds * 1000L)
                L.i("VoiceSender 开始循环外放：${f.name}，${seconds} 秒后自动停")
            } else {
                loopEndsAt = 0L
                L.i("VoiceSender 开始外放：${f.name}")
            }
            onState?.invoke(State.PLAYING)
            true
        } catch (t: Throwable) {
            L.e("VoiceSender 播放异常", t)
            loopEndsAt = 0L
            restoreVolume()
            onState?.invoke(State.ERROR)
            onFinished?.invoke()
            false
        }
    }

    /**
     * 关掉**本 App 这条播放通路**上的 AEC / NS / AGC。
     *
     * ## 为什么不是「遍历 session 音效」
     *
     * `android.jar` 里 `AudioEffect` 是 @hide 裁剪过的公开桩：
     * `queryEffects()/getDescriptor()/setEnabled()` 有，
     * 但 `AudioEffect.create(...)`、`AudioEffect.EFFECT_TYPE_NULL`、
     * `Equalizer.numberOfEffects/getEffect` 全是 @hide，编译期直接报
     * `Unresolved reference` —— 所以那条路走不通。
     *
     * ## 公开的等价做法
     *
     * Android 提供了三个公开工厂类，`create(sessionId)` 返回的可直接 `setEnabled(false)`：
     * ```
     * AcousticEchoCanceler.create(id)
     * NoiseSuppressor.create(id)
     * AutomaticGainControl.create(id)
     * ```
     *
     * ⚠️ **语义边界（重要，别误解）**：`create(sessionId)` 只对
     * *自己拥有* 的 session 生效 —— 传别人的 session 会返回 `null`。
     * 这里传的是我们自己 `MediaPlayer` 的 `audioSessionId`，合法有效。
     * 也就是说：这一手只能保证**我们播放的这条通路干净**，
     * 关不掉 QQ 录音通路上 QQ 自己挂的 AEC —— 那属于 QQ 的 session。
     *
     * 走耳机方案时 AEC 本来就不该生效（AEC 是为「扬声器→麦克风」设计的，
     * 耳机没有这条声学回路），这一手是防某些厂商 ROM 按 usage 无脑挂效果。
     *
     * 返回值形如 `"AEC=关/无 NS=无 AGC=无"`，打到日志里便于排查。
     */
    private fun disableEffects(sessionId: Int): String {
        if (sessionId == AudioManager.ERROR) return "session 无效"
        val parts = ArrayList<String>(3)

        fun off(name: String, created: AudioEffect?) {
            if (created == null) {
                parts.add("$name=无")
                return
            }
            val r = runCatching {
                created.setEnabled(false)
                created.release()
                true
            }.getOrDefault(false)
            parts.add(if (r) "$name=关" else "$name=失败")
        }

        off("AEC", runCatching { AcousticEchoCanceler.create(sessionId) }.getOrNull())
        off("NS", runCatching { NoiseSuppressor.create(sessionId) }.getOrNull())
        off("AGC", runCatching { AutomaticGainControl.create(sessionId) }.getOrNull())

        val s = parts.joinToString(" ")
        L.i("VoiceSender 音效处理：$s（session=$sessionId）")
        return s
    }

    /** 本机是否支持这三类音效（不同 ROM 差异大，UI 用来提示） */
    fun effectSupport(): String {
        val aec = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)
        val ns = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)
        val agc = runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false)
        return "AEC=${if (aec) "支持" else "不支持"} NS=${if (ns) "支持" else "不支持"} " +
            "AGC=${if (agc) "支持" else "不支持"}"
    }

    @Synchronized
    fun stop() {
        stopInternal()
        L.i("VoiceSender 已停止")
    }

    private fun stopInternal() {
        mainHandler.removeCallbacks(stopTask)
        loopEndsAt = 0L
        fadeTask?.let { mainHandler.removeCallbacks(it) }
        fadeTask = null
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        restoreVolume()
        onState?.invoke(State.IDLE)
    }

    /**
     * 把音量从 0 平滑推到 1（约 [FADE_IN_MS] 毫秒）。
     *
     * 直接用 setVolume(1f,1f) 会在第一个采样就满幅 —— 听感上就是「突然蹦一声」，
     * 用户原话是「很容易吓人一跳」。分 25ms 一步推上去听感上就自然了。
     *
     * 分步数 = FADE_IN_MS / FADE_STEP_MS（共 14 步，每步约 1/14 音量）。
     */
    private fun fadeIn(p: MediaPlayer) {
        val steps = (FADE_IN_MS / FADE_STEP_MS).toInt().coerceAtLeast(1)
        var i = 0
        lateinit var task: Runnable
        task = Runnable {
            if (mp !== p) return@Runnable          // 已被换掉/停掉
            i++
            val v = (i.toFloat() / steps).coerceIn(0f, 1f)
            runCatching { p.setVolume(v, v) }
            if (i < steps) mainHandler.postDelayed(task, FADE_STEP_MS)
            else fadeTask = null
        }
        fadeTask = task
        mainHandler.postDelayed(task, FADE_STEP_MS)
    }

    /** 恢复播放前被抬高的系统音量（没抬过就什么都不做） */
    private fun restoreVolume() {
        val v = restoreVolumeTo
        restoreVolumeTo = -1
        if (v < 0) return
        val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
        L.i("VoiceSender[外放] 音量已恢复为 $v")
    }

    val isPlaying: Boolean get() = runCatching { mp?.isPlaying == true }.getOrDefault(false)

    /** 循环剩余秒数（未播放返回 0） */
    val loopLeftSec: Int
        get() {
            val e = loopEndsAt
            if (e == 0L) return 0
            return ((e - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
        }
}
