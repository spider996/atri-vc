package com.atri.vc

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import atri.core.RvcConfig
import atri.core.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class UiState(
    val modelDir: String = "",
    val modelItems: List<ModelStore.Item> = emptyList(),
    val modelsReady: Boolean = false,

    // ---- 首次启动从 APK 内解包模型（全量包才有事做） ----
    val importing: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val importName: String = "",
    val engineLoaded: Boolean = false,

    val loadingEngine: Boolean = false,
    val engineLog: String = "",

    val srcName: String? = null,
    val srcInfo: String? = null,

    val busy: Boolean = false,
    val stage: String = "",
    val progress: Float = 0f,

    val resultFile: File? = null,
    val resultDisplay: String? = null,
    val resultSeconds: Float = 0f,
    val elapsedMs: Long = 0L,

    val error: String? = null,
    val toast: String? = null,

    // ---------------------------------------------------------------- 录音
    val recording: Boolean = false,
    val recElapsedMs: Long = 0L,
    /** 0..1 实时电平 */
    val recLevel: Float = 0f,
    /** 0..1 本次录音峰值电平，贴 1 说明可能削波 */
    val recPeakLevel: Float = 0f,
    val recSampleRate: Int = 0,
    /** 最近一次录音落盘的 wav（原始采样率） */
    val recFile: File? = null,
    val recSeconds: Float = 0f,
    val recCount: Int = 0,

    // ---------------------------------------------------------------- 合成（GPT-SoVITS）
    val ttsDir: String = "",
    val ttsModelItems: List<TtsAssetStore.Item> = emptyList(),
    val ttsReady: Boolean = false,
    val ttsMissing: List<String> = emptyList(),
    /** 已就绪的情绪参考特征（joy/sad/angry/calm 的子集） */
    val ttsRefsReady: List<String> = emptyList(),
    /** 当前选中的情绪 */
    val ttsEmotion: String = "calm",

    val ttsText: String = "",
    val ttsRefText: String = "",
    val ttsLang: String = "zh",
    val ttsSpeed: Float = 1.0f,
    val ttsTemperature: Float = 1.0f,
    val ttsTopK: Int = 15,
    val ttsTopP: Float = 1.0f,
    val ttsRepetition: Float = 1.35f,
    /** 选用的参考音频（未指定时用录音目录里最新的一条） */
    val ttsRefFile: File? = null,
    val ttsRefName: String? = null,

    val ttsBusy: Boolean = false,
    val ttsStage: String = "",
    val ttsProgress: Float = 0f,
    val ttsResultFile: File? = null,
    val ttsResultSeconds: Float = 0f,
    val ttsElapsedMs: Long = 0L,
    val ttsLog: String = "",

    /** 合成指标（对齐 PC 管线，便于 A/B） */
    val ttsArSteps: Int = 0,
    val ttsMsPerStep: Double = 0.0,
    val ttsRms: Float = 0f,

    /** 常用语音收藏（最多 10 条，LFU 淘汰）。合成耗时长，留一份便于复用。 */
    val library: List<TtsLibrary.Entry> = emptyList(),

    // 参数
    val pitchShift: Int = 0,
    val indexRate: Float = 0.3f,
    val protect: Float = 0.33f,
    val sid: Int = 0,
    val overlap: Int = 64,
    val useIndex: Boolean = true,
    /** 0 = 跟随 ORT 默认线程数（与 PC 基准一致；显式设值会改变 INT8 图的数值，见 VcEngine.load 注释） */
    val threads: Int = 0,

    // ---------------- QQ 语音代发 ----------------
    /** 总开关：允许在 QQ 录音时自动外放语音 */
    val qqAutoSend: Boolean = false,
    /** 无障碍服务是否已在系统设置里开启（开启只能在设置里手动做） */
    val qqWatchEnabled: Boolean = false,
    /** 当前待发送的语音文件名（null = 还没有） */
    val qqPendingName: String? = null,
    /** 是否正在外放 */
    val qqPlaying: Boolean = false,
    /** 音频路由：耳机（已证伪）还是手机外放（默认，唯一可行） */
    val qqHeadsetMode: Boolean = false,
    /** 悬浮小窗是否已开启（服务在跑） */
    val qqFloating: Boolean = false,
    /** 当前是否插着/连着耳机 */
    val qqHeadsetOn: Boolean = false,
    /** 当前耳机名 */
    val qqHeadsetName: String = "",
    /** 实际输出设备名（排查用） */
    val qqLastOutput: String = "",
    /** 上次播放是否因为连着耳机而没真正外放（QQ 会录成静音） */
    val qqBlockedByHeadset: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /**
         * 无障碍**自动**触发时的外放时长（秒）。
         *
         * 手动操作一律「点一次播一次」（[playPendingNow]），不用这个值。
         * 只有无障碍自动触发时才需要它：那时用户可能还没切到 QQ，
         * 得让声音多留一会儿才来得及按住说话。
         */
        const val QQ_LOOP_SEC = 12
    }

    private val ctx: Context get() = getApplication()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var engine: VcEngine? = null
    private var ttsEngine: TtsEngine? = null
    private var pendingSrc: Uri? = null
    private var pending16k: FloatArray? = null

    private val recorder = Recorder(app)

    private val prefs = ctx.getSharedPreferences("atri_vc", Context.MODE_PRIVATE)

    init {
        migrateRoutingOnce()

        val s = _state.value
        _state.value = s.copy(
            pitchShift = prefs.getInt("pitch", 0),
            indexRate = prefs.getFloat("indexRate", 0.3f),
            protect = prefs.getFloat("protect", 0.33f),
            sid = prefs.getInt("sid", 0),
            overlap = prefs.getInt("overlap", 64),
            useIndex = prefs.getBoolean("useIndex", true),
            threads = prefs.getInt("threads", 0),
            ttsLang = prefs.getString("ttsLang", "zh") ?: "zh",
            ttsSpeed = prefs.getFloat("ttsSpeed", 1.0f),
            ttsTemperature = prefs.getFloat("ttsTemperature", 1.0f),
            ttsTopK = prefs.getInt("ttsTopK", 15),
            ttsTopP = prefs.getFloat("ttsTopP", 1.0f),
            ttsRepetition = prefs.getFloat("ttsRepetition", 1.35f),
            qqAutoSend = prefs.getBoolean("qqAutoSend", false),
            // 默认外放：耳机通路已实测证伪（耳机麦收不到喇叭声）
            qqHeadsetMode = prefs.getBoolean("qqHeadsetMode", false),
        )
        // bind() 会先从 qq_pending.txt 恢复「上次待发送的语音」，
        // 再用 prefs 里的开关覆盖 armed（prefs 是开关的唯一真源）。
        VoiceSender.bind(ctx)
        VoiceSender.setArmedState(_state.value.qqAutoSend)
        // bind() 可能从磁盘恢复了上次合成的待发语音 → 同步进 UI，
        // 否则界面会显示「还没有待发送语音」但内部其实有（按钮点了没反应）。
        _state.value = _state.value.copy(qqPendingName = VoiceSender.pendingFile?.name)
        // ⚠️ 必须订阅播放状态：循环播放是 Handler 定时自动停的，
        // 不回调的话 UI 会一直停在「正在外放中」，「开始代发」按钮也就一直点不动。
        VoiceSender.applyRoute(
            if (prefs.getBoolean("qqHeadsetMode", false)) VoiceSender.Route.HEADSET
            else VoiceSender.Route.SPEAKER
        )
        _state.value = _state.value.copy(qqFloating = FloatingPlayService.running)
        refreshHeadsetState()
        VoiceSender.onState = { st ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
                _state.value = _state.value.copy(
                    qqPlaying = st == VoiceSender.State.PLAYING,
                    qqPendingName = VoiceSender.pendingFile?.name,
                    // 播放开始/结束时再读一次设备名：getRoutedDevice() 是异步解析的，
                    // 同步读会拿到「未知」。
                    qqLastOutput = VoiceSender.lastOutputName,
                    qqBlockedByHeadset = VoiceSender.lastBlockedByHeadset,
                    qqHeadsetOn = VoiceSender.hasHeadset(ctx),
                    qqHeadsetName = VoiceSender.headsetName(ctx),
                )
            }
        }
        refreshModels()
        refreshTtsModels()
        refreshLibrary()
        // 全量包：首次启动把 APK 里的模型解包到工作目录，用户装完即用。
        // 轻量包 assets 里没有 models/，这里会立刻返回。
        startAutoImport()
        restoreLastRecording()
        autoRunIfRequested()
        loopTestIfRequested()
        ttsAutoRunIfRequested()
        viewModelScope.launch {
            recorder.state.collect { r ->
                _state.value = _state.value.copy(
                    recording = r.recording,
                    recElapsedMs = r.elapsedMs,
                    recLevel = r.level,
                    recPeakLevel = r.peakLevel,
                    recSampleRate = r.sampleRate,
                )
            }
        }
    }

    /**
     * 一次性路由迁移（版本 2）。
     *
     * 背景：v1 的默认路由是 `qqHeadsetMode = true`（"耳机当虚拟麦"），
     * 但那条路后来被实测**证伪**（耳机麦收不到喇叭声 → QQ 录成静音）。
     * 而 `getBoolean(key, false)` 遇到已存在的 key 会直接读旧值 `true`，
     * 于是**老用户升级后仍然停在已废弃的耳机模式上**，表现为"QQ 里什么声音都没有"。
     *
     * 这里做一次硬迁移：只在首次跑到 v2 时把路由重置为外放。
     * 之后用户手动改回耳机也尊重（但会在 UI 上看到"此模式 QQ 会录成静音"的警告）。
     */
    private fun migrateRoutingOnce() {
        val v = prefs.getInt("routeMigrated", 0)
        if (v >= 2) return
        prefs.edit()
            .putBoolean("qqHeadsetMode", false)
            .putInt("routeMigrated", 2)
            .apply()
        L.i("路由迁移 v1->v2：qqHeadsetMode 强制置为 false（外放），原值=$v")
    }

    // ------------------------------------------------ 首次启动自动导入模型

    /**
     * 把 APK 里打包的模型解包到外部私有目录。
     *
     * 放在后台 IO 线程 —— 910 MB 的拷贝，UI 线程会直接 ANR。
     * 已存在且大小一致的文件会跳过，所以第二次启动是一瞬间的事。
     */
    private fun startAutoImport() {
        if (!ModelStore.hasBundledModels(ctx) && !TtsAssetStore.hasBundledModels(ctx)) return
        if (_state.value.importing) return

        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, importDone = 0,
                importTotal = 0, importName = "正在准备…")
            val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val n1 = ModelStore.ensureExtractedFromAssets(ctx) { d, t, n ->
                        _state.value = _state.value.copy(
                            importDone = d, importTotal = t, importName = n)
                    }
                    val n2 = TtsAssetStore.ensureExtractedFromAssets(ctx) { d, t, n ->
                        _state.value = _state.value.copy(
                            importDone = d, importTotal = t, importName = n)
                    }
                    n1 + n2
                }.getOrElse { t ->
                    L.w("自动导入模型失败：${t.message}")
                    -1
                }
            }
            L.i("自动导入完成：copied=$copied")
            _state.value = _state.value.copy(importing = false, importName = "")
            refreshModels()
            refreshTtsModels()
            if (copied > 0) {
                _state.value = _state.value.copy(toast = "模型已自动导入完成（$copied 个文件）")
            } else if (copied < 0) {
                _state.value = _state.value.copy(error = "模型自动导入失败，请检查存储空间后重试")
            }
        }
    }

    /** 设置页「重新导入模型」入口：清掉半截文件后再跑一次 */
    fun reimportModels() {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true, importDone = 0,
                importTotal = 0, importName = "正在准备…")
            val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    ModelStore.ensureExtractedFromAssets(ctx) { d, t, n ->
                        _state.value = _state.value.copy(
                            importDone = d, importTotal = t, importName = n)
                    } + TtsAssetStore.ensureExtractedFromAssets(ctx) { d, t, n ->
                        _state.value = _state.value.copy(
                            importDone = d, importTotal = t, importName = n)
                    }
                }.getOrElse { -1 }
            }
            _state.value = _state.value.copy(importing = false, importName = "")
            refreshModels()
            refreshTtsModels()
            _state.value = _state.value.copy(
                toast = if (copied > 0) "已导入 $copied 个模型文件" else "模型已是最新"
            )
        }
    }

    fun refreshModels() {
        val dir = ModelStore.modelDir(ctx)
        val st = ModelStore.status(ctx)
        L.i("models dir=${dir.absolutePath} ready=${st.ready} " +
            st.items.joinToString(", ") { "${it.name}=${if (it.present) "${it.sizeBytes / 1048576}MB" else "缺"}" })
        _state.value = _state.value.copy(
            modelDir = dir.absolutePath,
            modelItems = st.items,
            modelsReady = st.ready,
        )
    }

    fun setPitch(v: Int) { prefs.edit().putInt("pitch", v).apply(); _state.value = _state.value.copy(pitchShift = v) }
    fun setIndexRate(v: Float) { prefs.edit().putFloat("indexRate", v).apply(); _state.value = _state.value.copy(indexRate = v) }
    fun setProtect(v: Float) { prefs.edit().putFloat("protect", v).apply(); _state.value = _state.value.copy(protect = v) }
    fun setSid(v: Int) { prefs.edit().putInt("sid", v).apply(); _state.value = _state.value.copy(sid = v) }
    fun setOverlap(v: Int) { prefs.edit().putInt("overlap", v).apply(); _state.value = _state.value.copy(overlap = v) }
    fun setUseIndex(v: Boolean) { prefs.edit().putBoolean("useIndex", v).apply(); _state.value = _state.value.copy(useIndex = v) }
    fun setThreads(v: Int) { prefs.edit().putInt("threads", v).apply(); _state.value = _state.value.copy(threads = v) }

    // ------------------------------------------------ QQ 语音代发

    /** 开关「QQ 录音时自动外放」。无障碍服务必须另外在系统设置里手动开启。 */
    fun setQqAutoSend(v: Boolean) {
        prefs.edit().putBoolean("qqAutoSend", v).apply()
        VoiceSender.setArmedState(v)
        _state.value = _state.value.copy(qqAutoSend = v)
        L.i("QQ 语音代发 = $v（无障碍服务 running=${QqWatchService.running}）")
    }

    /**
     * 刷新无障碍服务开启状态（从设置页返回时调用）。
     *
     * 用 Settings.Secure 查系统设置，而不是只看 QqWatchService.running：
     * 后者是进程内静态标志，App 进程被系统回收后重新打开时会是 false，
     * 于是界面明明开着无障碍却显示「未开启」。
     */
    fun refreshQqWatchState() {
        val on = isQqWatchEnabled() || QqWatchService.running
        if (on && _state.value.qqAutoSend) VoiceSender.armed = true
        _state.value = _state.value.copy(
            qqWatchEnabled = on,
            qqPendingName = VoiceSender.pendingFile?.name,
            qqPlaying = VoiceSender.isPlaying,
        )
    }

    /** 系统「无障碍」列表里是否启用了本服务的 QQ 监听 */
    private fun isQqWatchEnabled(): Boolean {
        val want = ctx.packageName + "http://x/" + QqWatchService::class.java.name
        val want2 = ctx.packageName + "/" + QqWatchService::class.java.name
        val flat = runCatching {
            android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
        }.getOrNull()
        if (flat.isNullOrBlank()) return false
        val hit = flat.split(":").any { it.equals(want2, ignoreCase = true) || it.equals(want, ignoreCase = true) }
        L.i("无障碍列表：$flat -> hit=$hit")
        return hit
    }

    /** 把刚合成好的音频设为「待发送」 */
    fun armQqPending(file: File) {
        VoiceSender.arm(file)
        _state.value = _state.value.copy(qqPendingName = file.name)
    }

    /** 刷新耳机检测结果（插拔耳机 / 连断蓝牙后调用） */
    fun refreshHeadsetState() {
        _state.value = _state.value.copy(
            qqHeadsetOn = VoiceSender.hasHeadset(ctx),
            qqHeadsetName = VoiceSender.headsetName(ctx),
        )
    }

    /** 切换「耳机当虚拟麦」/「手机外放」 */
    fun setQqHeadsetMode(v: Boolean) {
        VoiceSender.applyRoute(if (v) VoiceSender.Route.HEADSET else VoiceSender.Route.SPEAKER)
        prefs.edit().putBoolean("qqHeadsetMode", v).apply()
        _state.value = _state.value.copy(qqHeadsetMode = v)
        refreshHeadsetState()
        L.i("QQ 代发路由 = ${if (v) "耳机" else "外放"}，耳机=${VoiceSender.hasHeadset(ctx)}")
    }

    /**
     * **点一次播一次**（主入口，也是悬浮球的行为）。
     *
     * 不依赖无障碍服务。用户在 QQ 前台按住说话前点一下即可。
     */
    fun playPendingNow() {
        VoiceSender.playNow(ctx)
        _state.value = _state.value.copy(
            qqPlaying = VoiceSender.isPlaying,
            qqHeadsetOn = VoiceSender.hasHeadset(ctx),
            qqBlockedByHeadset = VoiceSender.lastBlockedByHeadset,
        )
        // 输出设备名由 VoiceSender 异步解析，800ms 后（通常已解析完）再同步一次
        getApplication<Application>().let { app ->
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                _state.value = _state.value.copy(qqLastOutput = VoiceSender.lastOutputName)
            }, 800)
        }
    }

    /**
     * 无障碍**自动**触发时用的循环外放（`QQ_LOOP_SEC` 秒后自动停）。
     *
     * ⚠️ 手动操作不要走这里，用 [playPendingNow]（点一次播一次）。
     * 保留它是因为自动触发时用户还没切到 QQ，需要多响一会儿。
     */
    fun startQqLoop() {
        VoiceSender.playLoop(ctx, QQ_LOOP_SEC)
        _state.value = _state.value.copy(
            qqPlaying = VoiceSender.isPlaying,
            qqLastOutput = VoiceSender.lastOutputName,
            qqHeadsetOn = VoiceSender.hasHeadset(ctx),
            qqHeadsetName = VoiceSender.headsetName(ctx),
        )
    }

    fun stopQqLoop() {
        VoiceSender.stop()
        _state.value = _state.value.copy(qqPlaying = false)
    }

    /** 悬浮窗权限是否已授予 */
    fun canFloat(): Boolean = FloatingPlayService.canDraw(ctx)

    /** 打开系统「显示在其他应用上层」授权页 */
    fun requestFloatPermission() {
        runCatching {
            ctx.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + ctx.packageName),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { L.w("打开悬浮窗授权页失败：${it.message}") }
    }

    /**
     * 开关悬浮小窗。
     *
     * 没权限时不硬启动（系统会静默拒绝，用户看不到任何反馈），
     * 而是直接把授权页推出来。
     */
    fun setFloating(on: Boolean) {
        if (on && !canFloat()) {
            _state.value = _state.value.copy(toast = "请先授予「显示在其他应用上层」权限")
            requestFloatPermission()
            return
        }
        FloatingPlayService.toggle(ctx, on)
        _state.value = _state.value.copy(qqFloating = on)
        L.i("悬浮小窗 = $on")
    }

    /** 从授权页返回时刷新（onResume 调用） */
    fun refreshFloatingState() {
        _state.value = _state.value.copy(
            qqFloating = FloatingPlayService.running,
            qqPendingName = VoiceSender.pendingFile?.name,
        )
    }
    // ------------------------------------------------ 常用语音（LFU 收藏）

    /** 重新读一次收藏列表（最多 10 条，按用得多的排前面） */
    fun refreshLibrary() {
        _state.value = _state.value.copy(library = TtsLibrary.list(ctx))
    }

    /**
     * 标记「用过一次」——试听、设为待发送时调用。
     *
     * 这是 LFU 的计数来源：淘汰时计数最小的先走，所以常用的不会被清掉。
     */
    fun touchLibrary(e: TtsLibrary.Entry) {
        TtsLibrary.touch(ctx, e)
        refreshLibrary()
    }

    /** 把收藏里的一条设为「待发送」，悬浮球/设置页播的就是它 */
    fun useLibraryAsPending(e: TtsLibrary.Entry) {
        touchLibrary(e)
        armQqPending(e.file)
        _state.value = _state.value.copy(toast = "已设为待发送：${e.preview}")
    }

    fun deleteLibrary(e: TtsLibrary.Entry) {
        TtsLibrary.remove(ctx, e)
        // 删掉的正好是待发送那条时，一起解绑，免得播一个已经不存在的文件
        if (VoiceSender.pendingFile?.absolutePath == e.file.absolutePath) {
            VoiceSender.clear()
            _state.value = _state.value.copy(qqPendingName = null)
        }
        refreshLibrary()
    }

    fun clearToast() { _state.value = _state.value.copy(toast = null) }
    fun clearError() { _state.value = _state.value.copy(error = null) }

    // ---------------------------------------------------------------- 录音

    /** RECORD_AUDIO 是否已授权（UI 层据此决定是先要权限还是直接开录） */
    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun startRecording() {
        val s = _state.value
        if (s.recording) return
        if (!hasMicPermission()) {
            _state.value = s.copy(error = "需要麦克风权限才能录音")
            return
        }
        val err = recorder.start()
        if (err != null) _state.value = _state.value.copy(error = err)
    }

    /**
     * 停止录音。录到的内容会**同时**成为变声输入和合成参考音频的默认值 ——
     * 用户录一遍自己的声音，两个页都能直接用上。
     */
    fun stopRecording() {
        val s = _state.value
        if (!s.recording) return
        val r = recorder.stop()
        if (r == null) {
            _state.value = s.copy(toast = "录音太短（不足 0.3 秒），已丢弃")
            return
        }
        pending16k = r.samples16k
        pendingSrc = null
        val desc = buildString {
            append("录音 %.1f 秒 · %d Hz · 已重采样到 16 kHz".format(r.seconds, r.originRate))
            if (r.clipped) append(" · 峰值偏大，可能削波")
            if (r.tooQuiet) append(" · 几乎没有声音")
        }
        L.i("录音 → 变声输入：${r.file.absolutePath}")
        _state.value = s.copy(
            recording = false,
            recFile = r.file,
            recSeconds = r.seconds,
            recCount = s.recCount + 1,
            srcName = r.file.name,
            srcInfo = desc,
            resultFile = null,
            error = null,
            toast = when {
                r.tooQuiet -> "录到的几乎都是底噪，请检查麦克风后重录"
                r.clipped -> "录音完成，但音量偏大，建议离麦克风远一点"
                else -> "录音完成，已作为变声输入"
            },
            ttsRefFile = s.ttsRefFile ?: r.file,
            ttsRefName = s.ttsRefName ?: r.file.name,
        )
    }

    fun cancelRecording() {
        recorder.cancel()
        _state.value = _state.value.copy(recording = false, recElapsedMs = 0L, recLevel = 0f)
    }

    /** 把之前录的某一条回放素材设为变声输入（不进录音器，直接读 wav） */
    fun useRecordingAsInput(f: File) {
        _state.value = _state.value.copy(busy = true, stage = "读取录音", progress = 0.05f, error = null)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val raw = Wav.read(f)
                val mono16k = if (raw.sampleRate == AudioIo.TARGET_SR) raw.samples
                else atri.core.Resampler.convert(raw.samples, raw.sampleRate, AudioIo.TARGET_SR)
                AudioIo.preprocess16k(mono16k)
                pending16k = mono16k
                pendingSrc = null
                _state.value = _state.value.copy(
                    busy = false,
                    srcName = f.name,
                    srcInfo = "录音文件 · %.1f 秒 · %d Hz".format(
                        raw.samples.size.toFloat() / raw.sampleRate, raw.sampleRate
                    ),
                    resultFile = null,
                )
            } catch (t: Throwable) {
                L.e("读取录音失败", t)
                _state.value = _state.value.copy(busy = false, error = "读取录音失败：${t.message}")
            }
        }
    }

    fun listRecordings(): List<File> = AudioIo.listWavs(ctx, "recordings")

    private fun restoreLastRecording() {
        val all = AudioIo.listWavs(ctx, "recordings")
        val latest = all.firstOrNull() ?: return
        _state.value = _state.value.copy(
            recFile = latest,
            recCount = all.size,
            recSeconds = try {
                val d = Wav.read(latest)
                d.samples.size.toFloat() / d.sampleRate
            } catch (_: Throwable) {
                0f
            },
            ttsRefFile = _state.value.ttsRefFile ?: latest,
            ttsRefName = _state.value.ttsRefName ?: latest.name,
        )
    }

    // ---------------------------------------------------------------- 合成（GPT-SoVITS）

    fun refreshTtsModels() {
        // 首次调用时把打包进 APK 的文本前端（symbols/syl/charmap/poly/phrases/p2s/
        // bert_vocab）与 4 个参考特征 .refbin 解包到工作目录。缺表 → G2P 全 UNK；
        // 缺 refbin → 情绪列表为空、合成不可用。
        val unpacked = TtsAssetStore.unpackTextAssets(ctx)
        if (unpacked > 0) L.i("首次解包 TTS 文本资源 $unpacked 个文件")

        val st = TtsAssetStore.status(ctx)
        val refs = TtsAssetStore.refsReady(ctx)
        _state.value = _state.value.copy(
            ttsDir = TtsAssetStore.dir(ctx).absolutePath,
            ttsModelItems = st.items,
            ttsReady = st.ready && refs.isNotEmpty(),
            ttsMissing = st.missing,
            ttsRefsReady = refs,
            ttsEmotion = _state.value.ttsEmotion.let { e ->
                if (e in refs) e else (refs.firstOrNull() ?: "calm")
            },
        )
        val assetsOk = TtsAssetStore.assetsReady(ctx)
        L.i("refreshTtsModels ready=${st.ready} assets=$assetsOk refs=$refs " +
            "missing=${st.missing} 总模型 ${"%.1f".format(st.totalMb)} MB")
    }

    fun setTtsEmotion(emo: String) {
        if (emo !in RefFeatureStore.EMOTIONS) return
        L.i("TTS 情绪切换 -> $emo (${RefFeatureStore.LABELS[emo]})")
        _state.value = _state.value.copy(ttsEmotion = emo)
    }

    fun setTtsText(v: String) { _state.value = _state.value.copy(ttsText = v) }
    fun setTtsRefText(v: String) { _state.value = _state.value.copy(ttsRefText = v) }

    fun setTtsLang(v: String) {
        prefs.edit().putString("ttsLang", v).apply()
        _state.value = _state.value.copy(ttsLang = v)
    }

    fun setTtsSpeed(v: Float) {
        prefs.edit().putFloat("ttsSpeed", v).apply()
        _state.value = _state.value.copy(ttsSpeed = v)
    }

    fun setTtsTemperature(v: Float) {
        prefs.edit().putFloat("ttsTemperature", v).apply()
        _state.value = _state.value.copy(ttsTemperature = v)
    }

    fun setTtsTopK(v: Int) {
        prefs.edit().putInt("ttsTopK", v).apply()
        _state.value = _state.value.copy(ttsTopK = v)
    }

    fun setTtsTopP(v: Float) {
        prefs.edit().putFloat("ttsTopP", v).apply()
        _state.value = _state.value.copy(ttsTopP = v)
    }

    fun setTtsRepetition(v: Float) {
        prefs.edit().putFloat("ttsRepetition", v).apply()
        _state.value = _state.value.copy(ttsRepetition = v)
    }

    /** 用录音目录里最新的一条当参考音频 */
    fun useLatestRecordingAsRef() {
        val f = listRecordings().firstOrNull()
        if (f == null) {
            _state.value = _state.value.copy(error = "还没有录过音，先去变声页录一段")
            return
        }
        _state.value = _state.value.copy(ttsRefFile = f, ttsRefName = f.name, toast = "参考音频已设为 ${f.name}")
    }

    /** 选一个文件当参考音频（拷进 tts_ref 目录，采样率统一到 16k） */
    fun importTtsRef(uri: Uri) {
        _state.value = _state.value.copy(ttsBusy = true, ttsStage = "准备参考音频", ttsProgress = 0.1f, error = null)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val d = AudioIo.decodeTo16k(ctx, uri)
                val name = "ref_" + SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
                val f = AudioIo.saveLocalWav(ctx, d.samples, AudioIo.TARGET_SR, "tts_ref", name, normalize = false)
                _state.value = _state.value.copy(
                    ttsBusy = false, ttsStage = "", ttsProgress = 0f,
                    ttsRefFile = f, ttsRefName = f.name,
                    toast = "参考音频已导入（%.1f 秒）".format(d.samples.size / 16000f),
                )
            } catch (t: Throwable) {
                L.e("导入参考音频失败", t)
                _state.value = _state.value.copy(ttsBusy = false, ttsStage = "", error = "导入参考音频失败：${t.message}")
            }
        }
    }

    /**
     * 合成语音：前置校验 → 后台跑 TtsEngine → 结果落盘 → 装载为「待发送」。
     *
     * 校验顺序刻意排在起协程之前，因为这三类是用户最容易撞上的：
     * ① 没输文本 ② 模型没就位 ③ 参考特征没放。
     * 直接弹提示比起协程后再抛异常好排查得多。
     *
     * ⚠️ AR 循环在真机上要跑几十秒到两分钟，**必须有进度回调**（见 runSynthesis 的
     * onStep）。早期版本没有回调，进度条一直卡在 30%，用户报的就是「卡死/闪退」。
     */
    fun synthesizeTts() {
        val s = _state.value
        if (s.ttsBusy) return

        if (s.ttsText.isBlank()) {
            _state.value = s.copy(error = "请先输入要合成的文本")
            return
        }
        if (s.ttsMissing.isNotEmpty()) {
            _state.value = s.copy(
                error = "合成模型未就位，缺少：" + s.ttsMissing.joinToString("、") +
                    "\n放到 ${s.ttsDir}"
            )
            return
        }
        if (s.ttsRefsReady.isEmpty()) {
            _state.value = s.copy(
                error = "还没有参考特征。把 ref_*.refbin 放到 ${s.ttsDir}/ref/ 后重试"
            )
            return
        }

        val emo = s.ttsEmotion
        val text = s.ttsText.trim()
        _state.value = s.copy(
            ttsBusy = true, ttsLog = "", error = null, ttsResultFile = null,
            ttsStage = "加载引擎…", ttsProgress = 0f,
            ttsArSteps = 0, ttsMsPerStep = 0.0, ttsRms = 0f,
        )

        viewModelScope.launch {
            try {
                val out = withContext(Dispatchers.Default) { runSynthesis(text, emo) }
                val (file, info) = out
                // 合成成功即装载为「待发送」，QQ 录音时会自动外放它
                armQqPending(file)
                // 同时收进「常用语音」收藏：合成一次要几十秒，不留下就得重跑
                TtsLibrary.add(ctx, file, text, emo)
                refreshLibrary()
                _state.value = _state.value.copy(
                    ttsBusy = false, ttsResultFile = file, ttsResultSeconds = info.seconds,
                    ttsElapsedMs = info.totalMs, ttsStage = "完成",
                    ttsProgress = 1f, ttsLog = info.log,
                    ttsArSteps = info.steps, ttsMsPerStep = info.msPerStep,
                    ttsRms = info.rms, toast = "合成完成（%s）".format(
                        RefFeatureStore.LABELS[emo] ?: emo),
                )
            } catch (t: Throwable) {
                L.e("合成失败", t)
                // 带上异常类名：G2P 空、ONNX 形状不匹配、OOM 从 message 上很难区分
                val kind = t.javaClass.simpleName
                _state.value = _state.value.copy(
                    ttsBusy = false, ttsStage = "失败",
                    error = "合成失败（$kind）：${t.message ?: "无详细信息"}",
                )
            }
        }
    }

    private class SynthInfo(
        val seconds: Float, val totalMs: Long, val steps: Int,
        val msPerStep: Double, val rms: Float, val log: String,
    )

    /** 真正的合成流程（后台线程）：G2P -> BERT -> encoder -> AR -> vits */
    private fun runSynthesis(text: String, emo: String): Pair<File, SynthInfo> {
        val t0 = System.currentTimeMillis()
        val sb = StringBuilder()

        // 引擎按需加载（615 MB 模型，不想拖慢启动）
        var e = ttsEngine
        if (e == null || !e.loaded) {
            runCatching { e?.close() }
            val ne = TtsEngine(TtsAssetStore.dir(ctx), TtsAssetStore.dir(ctx))
            val info = ne.load(threads = 4)
            sb.append("引擎加载：").append(info).append('\n')
            L.i("TtsEngine $info")
            if (!ne.loaded) {
                ne.close()
                error("TTS 引擎加载失败：$info")
            }
            ttsEngine = ne
            e = ne
        }
        _state.value = _state.value.copy(ttsStage = "读取参考特征…", ttsProgress = 0.1f)

        val ref = RefFeatureStore.load(ctx, emo)
            ?: error("读取参考特征失败：ref_$emo.refbin")
        val refText = RefFeatureStore.REF_TEXTS[emo] ?: ""

        _state.value = _state.value.copy(
            ttsStage = "推理中（AR 循环）…", ttsProgress = 0.3f)

        // ⚠️ onStep 必须传：AR 循环在真机上要跑几十秒到两分钟，
        // 不回调的话进度条会一直停在一个值上，用户看到的就是「点下去没反应」，
        // 很容易被当成「卡死 / 合成失败 / 闪退」。
        val tAr = System.currentTimeMillis()
        val (audio, tm) = e!!.synthesize(
            text = text,
            refText = refText,
            ref = ref.feat,
            temperature = _state.value.ttsTemperature,
            topK = _state.value.ttsTopK,
            topP = _state.value.ttsTopP,
            repetition = _state.value.ttsRepetition,
            minSteps = 45,
            onStep = { step ->
                // AR 长度未知，用「已跑步数」做渐进式进度（每步最多推进到 90%）
                val p = (0.3f + 0.6f * (1f - kotlin.math.exp(-step / 60f))).coerceAtMost(0.9f)
                // 每 10 步打一条日志：UI 看不到进度条时，靠 logcat 也能确认「活着在跑」
                if (step % 10 == 0) {
                    L.i("tts> AR 第 %d 步，已用 %.1f 秒".format(step, (System.currentTimeMillis() - tAr) / 1000f))
                }
                _state.value = _state.value.copy(
                    ttsStage = "推理中（AR 第 %d 步，%.0f 秒）…".format(
                        step, (System.currentTimeMillis() - tAr) / 1000f),
                    ttsProgress = p,
                    ttsArSteps = step,
                )
            },
        )

        sb.append("G2P %d ms\n".format(tm.g2pMs))
        sb.append("BERT %d ms\n".format(tm.bertMs))
        sb.append("encoder %d ms\n".format(tm.encoderMs))
        sb.append("prefill %d ms\n".format(tm.prefillMs))
        sb.append("AR %d 步 %.1f ms/帧 共 %d ms\n".format(
            tm.arSteps, tm.msPerStep(), tm.arMs))
        sb.append("vits %d ms\n".format(tm.vitsMs))
        sb.append("合计 %d ms\n".format(tm.totalMs))

        // 归一化防削波，再落盘
        var peak = 0f
        for (v in audio) if (abs(v) > peak) peak = abs(v)
        val gain = if (peak > 0.999f) 0.999f / peak else 1f
        var sq = 0.0
        for (i in audio.indices) {
            audio[i] *= gain
            sq += audio[i].toDouble() * audio[i]
        }
        val rms = Math.sqrt(sq / audio.size).toFloat()
        sb.append("峰值 %.4f 增益 %.3f rms %.5f\n".format(peak, gain, rms))

        if (peak > 0.999f) {
            L.w("合成削波（峰值 %.4f），已按 %.3f 归一化".format(peak, gain))
        }

        val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
        // saveLocalWav 内部还会再做一次归一化（preprocess16k），
        // 这里传 normalize=false 自己控制增益，否则二次缩放会掩盖真实 rms。
        val f = AudioIo.saveLocalWav(
            ctx, audio, TtsEngine.SAMPLE_RATE, "tts", "tts_${emo}_$stamp",
            normalize = false,
        )
        sb.append("输出 ").append(f.absolutePath).append('\n')

        val totalMs = System.currentTimeMillis() - t0
        sb.append("墙钟 %d ms".format(totalMs))

        return f to SynthInfo(
            seconds = audio.size.toFloat() / TtsEngine.SAMPLE_RATE,
            totalMs = totalMs, steps = tm.arSteps, msPerStep = tm.msPerStep(),
            rms = rms, log = sb.toString(),
        )
    }

    fun loadEngine() {
        if (_state.value.loadingEngine) return
        _state.value = _state.value.copy(loadingEngine = true, engineLog = "", error = null)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                ModelStore.ensureExtractedFromAssets(ctx)
                val e = engine ?: VcEngine(ModelStore.candidateDirs(ctx)).also { engine = it }
                e.load(
                    withIndex = _state.value.useIndex,
                    threads = _state.value.threads,
                    onLog = { line ->
                        _state.value = _state.value.copy(engineLog = _state.value.engineLog + line + "\n")
                    },
                )
                _state.value = _state.value.copy(
                    loadingEngine = false, engineLoaded = true,
                    engineLog = _state.value.engineLog + "模型加载完成\n",
                )
            } catch (t: Throwable) {
                L.e("loadEngine 失败", t)
                _state.value = _state.value.copy(
                    loadingEngine = false,
                    error = "模型加载失败：${t.message}",
                )
            }
        }
    }

    // ---------------------------------------------------------------- 输入通路

    private val audioExts = setOf("wav", "mp3", "m4a", "aac", "flac", "ogg")

    /** 扫描 App 私有目录（外部优先）里的音频文件，按名字排序 */
    fun scanImportableAudio(): List<File> {
        val roots = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir)
        return roots.asSequence()
            .flatMap { d -> d.listFiles()?.asSequence() ?: emptySequence() }
            .filter { it.isFile && it.extension.lowercase() in audioExts }
            .sortedBy { it.name }
            .toList()
    }

    /**
     * 免文件选择器的导入：把 wav/mp3 丢进 App 私有目录，点一下就能载入。
     * 模拟器 / 真机自动化都用这条路。
     */
    fun importFromDir() {
        val found = scanImportableAudio()
        L.i("importFromDir 候选 ${found.size} 个：" + found.joinToString(", ") { it.name })
        val f = found.firstOrNull()
        if (f == null) {
            val hint = ctx.getExternalFilesDir(null)?.absolutePath ?: ctx.filesDir.absolutePath
            _state.value = _state.value.copy(error = "App 目录里没找到音频；把 wav/mp3 放到 $hint")
            return
        }
        pickAudio(Uri.fromFile(f))
    }

    fun pickAudio(uri: Uri) {
        _state.value = _state.value.copy(busy = true, stage = "解码音频", progress = 0.02f, error = null, resultFile = null)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val d = AudioIo.decodeTo16k(ctx, uri)
                pendingSrc = uri
                pending16k = d.samples
                val sec = d.samples.size / 16000f
                L.i("解码完成 ${d.sourceName} 原始 ${d.originRate}Hz -> 16k ${d.samples.size} 点 (%.2f 秒)".format(sec))
                _state.value = _state.value.copy(
                    busy = false,
                    srcName = d.sourceName,
                    srcInfo = "原始 ${d.originRate} Hz · %.1f 秒 · 已重采样到 16 kHz".format(sec),
                )
            } catch (t: Throwable) {
                L.e("音频解码失败", t)
                _state.value = _state.value.copy(busy = false, error = "音频解码失败：${t.message}")
            }
        }
    }

    /** 用一段已经解好的 16k 音频（模拟器/测试注入用） */
    fun setPreparedAudio(name: String, samples16k: FloatArray) {
        pending16k = samples16k
        _state.value = _state.value.copy(
            srcName = name,
            srcInfo = "已注入 16 kHz 音频 · %.1f 秒".format(samples16k.size / 16000f),
            resultFile = null, error = null,
        )
    }

    fun convert() {
        val audio = pending16k
        if (audio == null) {
            _state.value = _state.value.copy(error = "先选一段音频")
            return
        }
        if (!_state.value.engineLoaded) {
            _state.value = _state.value.copy(error = "模型还没加载，先到「模型」页加载")
            return
        }
        val s = _state.value
        if (s.busy) return
        _state.value = s.copy(busy = true, stage = "准备", progress = 0f, error = null, resultFile = null)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val cfg = RvcConfig(
                    outSampleRate = 40000,
                    pitchShift = s.pitchShift,
                    indexRate = if (s.useIndex) s.indexRate else 0f,
                    protect = s.protect,
                    sid = s.sid,
                    overlapFrames = s.overlap,
                    topK = 8,
                    seed = System.nanoTime(),
                )
                val t0 = System.currentTimeMillis()
                val out = engine!!.convert(audio, cfg) { stage, pct ->
                    _state.value = _state.value.copy(stage = stage, progress = pct)
                }
                val ms = System.currentTimeMillis() - t0
                val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
                val base = (s.srcName ?: "audio").substringBeforeLast('.') + "_atri_$stamp"
                val saved = AudioIo.saveWav(ctx, out, 40000, base)
                val srcSec = audio.size / 16000f
                L.i("完成：输出 ${out.size} 点 @40k，文件 ${saved.file.absolutePath}，耗时 ${ms}ms，RTF=${"%.3f".format(ms / 1000.0 / srcSec)}")
                // 变声结果同样装载为「待发送」，这样「转换好的音频」也能直接代发给 QQ
                armQqPending(saved.file)
                _state.value = _state.value.copy(
                    busy = false, stage = "", progress = 1f,
                    resultFile = saved.file,
                    resultDisplay = saved.displayPath,
                    resultSeconds = out.size / 40000f,
                    elapsedMs = ms,
                    toast = "转换完成（%.2f 秒音频用了 %.1f 秒，RTF=%.2f）".format(
                        srcSec, ms / 1000.0, ms / 1000.0 / srcSec
                    ),
                )
            } catch (t: Throwable) {
                L.e("转换失败", t)
                _state.value = _state.value.copy(
                    busy = false, stage = "",
                    error = "转换失败：${t.message}",
                )
            }
        }
    }

    // ---------------------------------------------------------------- 调试通路

    /**
     * DEBUG 专用：耳机回环自测。
     *
     * 外部私有目录放 `LOOPTEST` 文件即触发；待播放音频取 `qq_pending.txt` 第 2 行。
     * 结果写 `looptest_result.txt`（中文 log 在 PowerShell 里会乱码，所以落盘）。
     *
     * 目的：验证「耳机喇叭放的声音能不能被耳机麦收回去」——
     * 这是蓝牙耳机当虚拟麦克风方案的唯一成立条件。
     */
    private fun loopTestIfRequested() {
        if (!BuildConfig.DEBUG) return
        val ext = ctx.getExternalFilesDir(null) ?: return
        if (!File(ext, "LOOPTEST").exists()) return
        L.i("=== LOOPTEST 触发 ===")
        viewModelScope.launch(Dispatchers.Default) {
            val out = File(ext, "looptest_result.txt")
            try {
                // 找待播放音频：qq_pending.txt 第 2 行，或 output 目录里最新的 wav
                val wav = runCatching {
                    val pf = File(ext, "qq_pending.txt")
                    val lines = if (pf.exists()) pf.readText().split("\n") else emptyList()
                    val p = lines.getOrNull(1)?.trim().orEmpty()
                    if (p.isNotEmpty() && File(p).exists()) File(p) else null
                }.getOrNull() ?: File(ext, "output").listFiles()
                    ?.filter { it.extension == "wav" }
                    ?.maxByOrNull { it.lastModified() }

                if (wav == null) {
                    out.writeText("LOOPTEST 失败：找不到可播放的 wav（qq_pending.txt 第 2 行 / output 目录）")
                    L.w("=== LOOPTEST 无音频可播 ===")
                    return@launch
                }
                L.i("LOOPTEST 播放源 = ${wav.absolutePath}")

                val r = HeadsetLoopTest.run(ctx, wav)
                val txt = buildString {
                    appendLine("=== 耳机回环自测结果 ===")
                    appendLine("播放文件     : ${wav.name}")
                    appendLine()
                    appendLine("【第 1 组：A2DP 默认通路】")
                    appendLine("  静音基线   : ${"%.1f".format(r.silentDb)} dBFS")
                    appendLine("  回环时能量 : ${"%.1f".format(r.loopDb)} dBFS")
                    appendLine("  回环增益   : ${"%.1f".format(r.gainDb)} dB")
                    appendLine()
                    appendLine("【第 2 组：主动建立 SCO】")
                    appendLine("  startBluetoothSco 成功 : ${r.scoStarted}")
                    appendLine("  SCO 组增益             : ${r.scoGainDb?.let { "%.1f".format(it) } ?: "未测" } dB")
                    appendLine("  SCO 组输出设备         : ${r.scoOutputDevice.ifEmpty { "未测" }}")
                    appendLine()
                    appendLine("【第 3 组：外放 → 手机自带麦】（耳机路线证伪后的主力路线）")
                    appendLine("  静音基线   : ${"%.1f".format(r.speakerSilentDb)} dBFS")
                    appendLine("  播放时能量 : ${"%.1f".format(r.speakerLoopDb)} dBFS")
                    appendLine("  外放增益   : ${"%.1f".format(r.speakerGainDb)} dB")
                    appendLine("  外放输出设备: ${r.speakerOutputDevice.ifEmpty { "未测" }}")
                    appendLine("  外放结论   : ${r.speakerVerdict}")
                    appendLine()
                    appendLine("实际输出设备 : ${r.outputDevice}")
                    appendLine("实际输入设备 : ${r.inputDevice}")
                    appendLine("录音source   : ${r.audioSource}（3=VOICE_COMMUNICATION）")
                    appendLine("录音采样率   : ${r.sampleRate}")
                    appendLine()
                    appendLine("结论         : ${r.verdict}")
                    appendLine("可用         : ${if (r.ok) "是" else "否"}")
                    appendLine("（第 1/2 组是「蓝牙耳机回环」，已证伪，保留作对照）")
                }
                out.writeText(txt)
                L.i("=== LOOPTEST DONE ===")
                L.i("LOOPTEST 结论：${r.verdict}")
                L.i("LOOPTEST 输出=${r.outputDevice} 输入=${r.inputDevice} gain=${"%.1f".format(r.gainDb)}dB")
            } catch (t: Throwable) {
                out.writeText("LOOPTEST 异常：${t.message}\n${t.stackTraceToString()}")
                L.e("=== LOOPTEST FAILED ===", t)
            }
        }
    }

    /**
     * DEBUG 专用：App 外部私有目录里若存在 `AUTORUN` 文件，则跳过所有 UI 交互，
     * 直接「加载模型 → 导入第一段音频 → 转换 → 存盘」。
     *
     * 模拟器/真机无人值守验证用；release 包里这个文件不存在，自然不触发。
     */
    private fun autoRunIfRequested() {
        if (!BuildConfig.DEBUG) return
        val ext = ctx.getExternalFilesDir(null) ?: return
        if (!File(ext, "AUTORUN").exists()) return
        L.i("=== AUTORUN 触发 ===")
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val e = engine ?: VcEngine(ModelStore.candidateDirs(ctx)).also { engine = it }
                // 线程数可被 <外部目录>/threads.txt 覆盖（调试用）。
                // 背景：ORT 的 INT8 量化 MatMul 结果随 intra_op 线程数【分档变化】
                //   （实测桌面：4/8 -> 一档，12 -> 又一档，>=16 与默认 -> 与基准逐位相同）。
                // PC 基准是 32 线程跑出来的，所以只有 >=16 线程才能逐位复现；
                // 默认 threads=0 表示跟随 ORT 默认（手机上即核心数），数值上等价于「本机档位」。
                val thrFile = File(ext, "threads.txt")
                val threads = if (thrFile.exists()) {
                    thrFile.readText().trim().toIntOrNull()?.also { L.i("autorun> 线程数被 threads.txt 覆盖为 $it") } ?: 0
                } else 0
                // 执行提供器：<外部目录>/ep.txt 写 cpu | xnnpack | nnapi（默认 cpu）
                val epFile = File(ext, "ep.txt")
                val ep = if (epFile.exists()) epFile.readText().trim().lowercase() else "cpu"
                // 算子级 profiling：<外部目录>/profile.txt 存在即开启，结果写入 ort_profile/
                val profDir = if (File(ext, "profile.txt").exists()) File(ext, "ort_profile") else null
                e.load(
                    withIndex = _state.value.useIndex,
                    threads = threads,
                    ep = ep,
                    profileDir = profDir,
                ) { L.i("autorun> $it") }
                // net_g 分块并行度：<外部目录>/parallel.txt 写 1..8（默认 4）
                val parFile = File(ext, "parallel.txt")
                if (parFile.exists()) {
                    val n = parFile.readText().trim().toIntOrNull() ?: 4
                    e.setBlockParallelism(n)
                    L.i("autorun> net_g 块并行度被 parallel.txt 覆盖为 $n")
                }
                _state.value = _state.value.copy(engineLoaded = true, engineLog = "模型加载完成（autorun）\n")

                // 输入二选一：
                //  A) input16k.f32 存在 → 直接用（裸 f32 @16k），用于隔离「前端重采样」这一段，
                //     与 PC 基准 audio16k.f32 完全同源；
                //  B) 否则走正常前端：读 44.1k wav → 重采样 16k → 去直流 → 峰值归一化。
                val inF32 = File(ext, "input16k.f32")
                val samples: FloatArray
                val srcLabel: String
                if (inF32.exists() && inF32.length() > 4096) {
                    val n = (inF32.length() / 4).toInt()
                    samples = Wav.readF32(inF32, n)
                    srcLabel = "input16k.f32(裸,${n}点)"
                    L.i("autorun> 使用裸 16k 输入 ${n} 点")
                } else {
                    val f = scanImportableAudio().firstOrNull()
                        ?: throw IllegalStateException("App 目录里没有音频文件")
                    val d = AudioIo.decodeTo16k(ctx, Uri.fromFile(f))
                    samples = d.samples
                    srcLabel = "${f.name}(原始${d.originRate}Hz)"
                    L.i("autorun> 音频 ${f.name} 原始 ${d.originRate}Hz -> ${d.samples.size} 点")
                }

                // 把真正喂进管线的 16k 波形落盘，便于 PC 侧用同一输入复算（隔离前端差异）
                Wav.writeF32(File(ext, "audio16k_dump.f32"), samples)
                var pk = 1e-9f
                var msum = 0.0
                for (v in samples) { if (abs(v) > pk) pk = abs(v); msum += v }
                L.i("autorun> 管线输入 %d 点 peak=%.5f mean=%.6f".format(samples.size, pk, msum / samples.size))

                // 若提供了基准噪声块，则复用（用于「同输入+同噪声」的严格比对）
                val rndRef = File(ext, "blocks_rnd.f32")
                if (rndRef.exists() && rndRef.length() > 4096) {
                    try {
                        e.installRndFromReference(rndRef)
                    } catch (t: Throwable) {
                        L.e("装载基准噪声失败，改用自生成噪声", t)
                    }
                }

                val cfg = RvcConfig(
                    outSampleRate = 40000,
                    pitchShift = _state.value.pitchShift,
                    indexRate = if (_state.value.useIndex) _state.value.indexRate else 0f,
                    protect = _state.value.protect,
                    sid = _state.value.sid,
                    overlapFrames = _state.value.overlap,
                    topK = 8,
                    seed = 12345L,
                )
                val t0 = System.currentTimeMillis()
                val out = e.convertWithDebugDump(samples, cfg, File(ext, "dbg"))
                val ms = System.currentTimeMillis() - t0
                L.i("分阶段耗时(ms): " + e.timings().entries.joinToString(", ") { "${it.key}=${it.value}" })
                // 原始 f32 输出（未经 16bit 量化），与 out_40000.f32 直接比对
                Wav.writeF32(File(ext, "out_40000_dump.f32"), out)
                val saved = AudioIo.saveWav(ctx, out, 40000, "autorun_40000")
                val srcSec = samples.size / 16000f
                L.i("=== AUTORUN DONE === [$srcLabel] 输出 ${out.size} 点 @40k -> ${saved.file.absolutePath} 用时 ${ms}ms RTF=${"%.3f".format(ms / 1000.0 / srcSec)}")
                _state.value = _state.value.copy(
                    engineLoaded = true, busy = false,
                    srcName = srcLabel,
                    resultFile = saved.file,
                    resultDisplay = saved.displayPath,
                    resultSeconds = out.size / 40000f,
                    elapsedMs = ms,
                )
                if (profDir != null) {
                    // 释放 net_g 会话，强制 ONNX Runtime 把算子级 profile 落盘
                    e.flushProfileAndUnload()
                    L.i("profile flushed -> ${profDir.absolutePath}")
                }
            } catch (t: Throwable) {
                L.e("=== AUTORUN FAILED ===", t)
                _state.value = _state.value.copy(error = "autorun 失败：${t.message}")
            }
        }
    }

    /**
     * TTS 无人值守通路（调试用）。
     *
     * 真机上 `adb shell input text` 无法输入中文，所以合成验证走文件驱动：
     * ```
     * <外部目录>/TTSAUTORUN      存在即触发（内容忽略）
     * <外部目录>/tts_text.txt    要合成的文本（UTF-8）
     * <外部目录>/tts_emo.txt     可选，joy|sad|angry|calm
     * <外部目录>/tts_seed.txt    可选，采样种子（默认 11）
     * ```
     * 结果 wav 落在 `<外部目录>/output/`。
     */
    /**
     * TTS 无人值守通路（仅 debug 包）。
     *
     * 真机上 `adb shell input text` 无法输入中文，所以合成验证走文件驱动：
     * ```
     * <外部目录>/TTSAUTORUN      存在即触发（内容忽略）
     * <外部目录>/tts_text.txt    要合成的文本（UTF-8）
     * <外部目录>/tts_emo.txt     可选，joy|sad|angry|calm（默认 calm）
     * <外部目录>/tts_seed.txt    可选，采样种子（默认 11）
     * <外部目录>/tts_threads.txt 可选，ORT 线程数（默认 4）
     * ```
     * 结果 wav 落在 `<外部目录>/output/tts/`。
     */
    private fun ttsAutoRunIfRequested() {
        if (!BuildConfig.DEBUG) return
        val ext = ctx.getExternalFilesDir(null) ?: return
        if (!File(ext, "TTSAUTORUN").exists()) return
        val textFile = File(ext, "tts_text.txt")
        if (!textFile.exists()) { L.w("TTSAUTORUN 存在但缺 tts_text.txt"); return }
        L.i("=== TTSAUTORUN 触发 ===")
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val text = textFile.readText(Charsets.UTF_8).trim()
                val emo = File(ext, "tts_emo.txt").let {
                    if (it.exists()) it.readText().trim().lowercase() else "calm"
                }
                val seed = File(ext, "tts_seed.txt").let {
                    if (it.exists()) it.readText().trim().toLongOrNull() ?: 11L else 11L
                }
                val thr = File(ext, "tts_threads.txt").let {
                    if (it.exists()) it.readText().trim().toIntOrNull() ?: 4 else 4
                }
                L.i("tts-autorun> text=\"$text\" emo=$emo seed=$seed threads=$thr")

                // 诊断开关：写 tts_uipath.txt=1 就走**完整 UI 路径**（synthesizeTts），
                // 用来区分「引擎本身的问题」和「UI 编排层的问题」。
                // 用户报「合成失败/闪退」但 AUTORUN 直接调引擎是好的 —— 差异必在编排层。
                if (File(ext, "tts_uipath.txt").let { it.exists() && it.readText().trim() == "1" }) {
                    L.i("tts-autorun> 走 UI 路径（synthesizeTts）")
                    _state.value = _state.value.copy(
                        ttsText = text,
                        ttsEmotion = if (emo in RefFeatureStore.EMOTIONS) emo else "calm",
                    )
                    withContext(Dispatchers.Main) { synthesizeTts() }
                    return@launch
                }

                // 1) 加载引擎（assetsDir == modelDir：文本表与模型同目录）
                val dir = TtsAssetStore.dir(ctx)
                val e = TtsEngine(dir, dir)
                L.i("tts-autorun> " + e.load(thr))

                // 2) 参考特征
                val loaded = RefFeatureStore.load(ctx, emo)
                    ?: throw IllegalStateException("缺少 ref_$emo.refbin")
                val ref = loaded.feat
                L.i("tts-autorun> ref $emo sslLen=${ref.sslLen} prompt=${ref.prompt.size} refer=${ref.refer.size}")

                // 3) 合成
                val refText = RefFeatureStore.REF_TEXTS[emo] ?: ""
                val t0 = System.currentTimeMillis()
                val (audio, tm) = e.synthesize(
                    text = text,
                    refText = refText,
                    ref = ref,
                    temperature = _state.value.ttsTemperature,
                    topK = _state.value.ttsTopK,
                    topP = _state.value.ttsTopP,
                    repetition = _state.value.ttsRepetition,
                    minSteps = 45,
                    seed = seed,
                    noiseSeed = seed,
                )
                val ms = System.currentTimeMillis() - t0

                var peak = 0f
                var sum = 0.0
                for (v in audio) {
                    val a = kotlin.math.abs(v)
                    if (a > peak) peak = a
                    sum += v.toDouble() * v
                }
                val rms = kotlin.math.sqrt(sum / audio.size.coerceAtLeast(1)).toFloat()

                val savedFile = AudioIo.saveLocalWav(
                    ctx, audio, TtsEngine.SAMPLE_RATE, "tts", "tts_${emo}_aut$seed", normalize = false
                )
                L.i("tts-autorun> 耗时 g2p=${tm.g2pMs} bert=${tm.bertMs} encoder=${tm.encoderMs} " +
                    "prefill=${tm.prefillMs} ar=${tm.arMs}(${tm.arSteps} 步, ${"%.2f".format(tm.msPerStep())}ms/步) " +
                    "vits=${tm.vitsMs} 合计=${ms}ms")
                L.i("tts-autorun> 音频 ${audio.size} 点 @32k = ${"%.2f".format(audio.size / 32000f)}s " +
                    "peak=${"%.4f".format(peak)} rms=${"%.5f".format(rms)}")
                L.i("=== TTSAUTORUN DONE === ${savedFile.absolutePath}")

                // ttsEmotion 必须同步，否则结果卡会显示上一次（或默认）的情绪名，
                // 出现「文件是 sad 却显示平淡」这种对不上的情况。
                _state.value = _state.value.copy(
                    ttsBusy = false,
                    ttsEmotion = if (emo in RefFeatureStore.EMOTIONS) emo else "calm",
                    ttsArSteps = tm.arSteps,
                    ttsMsPerStep = tm.msPerStep(),
                    ttsRms = rms,
                    ttsResultFile = savedFile,
                    ttsResultSeconds = audio.size / 32000f,
                    ttsElapsedMs = ms,
                )
            } catch (t: Throwable) {
                L.e("=== TTSAUTORUN FAILED ===", t)
                _state.value = _state.value.copy(error = "tts autorun 失败：${t.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        recorder.release()
        engine?.close()
        engine = null
    }

    @Suppress("unused")
    private suspend fun unusedWithContext() = withContext(Dispatchers.Default) { Unit }
}
