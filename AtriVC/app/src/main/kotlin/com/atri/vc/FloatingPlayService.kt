package com.atri.vc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 「QQ 语音代发」的**悬浮小窗**（前台服务）。
 *
 * ## 为什么要做悬浮窗，而不是让用户来回切界面
 *
 * 外放这条通路有个绕不开的前置条件：**QQ 必须保持在前台**（见 VoiceSender 类头）。
 * 如果用户切回本 App 点播放，QQ 退到后台会暂停采集，录出来就是一段静音。
 *
 * 所以正确姿势是：QQ 在前台 → 本 App 以一个**小悬浮球**浮在上面 →
 * 用户点一下就能播放，**不需要切换任何界面**。
 *
 * 这套交互形态是从同类产品（橘雪梨语音盒）学来的 —— 只学交互，不学原理：
 * 它的交互没问题（悬浮窗 + 一键播放），但它的"不用外放也能进麦克风"是假的
 * （逆向结论：无 RECORD_AUDIO、无 DSP 库、APK 内 0 个音频文件、运行时不占麦克风，
 * 它只是播放联网下载的预录语音包）。我们借它的**形态**，走我们实测成立的**通路**。
 *
 * ## 权限
 *
 * - `SYSTEM_ALERT_WINDOW`（悬浮窗）：`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
 *   引导用户手动授权，App 内无法静默授予。
 * - 前台服务类型：Android 14 起 `mediaPlayback` 需要声明并申请
 *   `FOREGROUND_SERVICE_MEDIA_PLAYBACK`，且必须在 `startForeground` 时传类型。
 *
 * ## 与 QQ 的关系
 *
 * 悬浮窗**不监听 QQ**（那要无障碍），它只是个手动按钮。
 * 两者互补：无障碍能自动触发但有版本限制，悬浮窗是永远可用的兜底。
 *
 * ## 窗口参数上的两个坑
 *
 * 1. 必须用 `TYPE_APPLICATION_OVERLAY`（API 26+）。旧的 `TYPE_PHONE`
 *    在 Android 8 之后会被系统拒绝，且部分 ROM 直接不放行。
 * 2. `FLAG_NOT_FOCUSABLE` 必须加：否则悬浮窗会抢焦点，
 *    QQ 的输入框/录音按钮收不到触摸，"按住说话"直接失效。
 */
class FloatingPlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.atri.vc.action.FLOATING_SHOW"
        const val ACTION_HIDE = "com.atri.vc.action.FLOATING_HIDE"

        private const val CH_ID = "atri_floating"
        private const val CH_NAME = "变声悬浮窗"
        private const val NOTI_ID = 0xA771

        /** 悬浮球直径（dp） */
        private const val BALL_DP = 58

        private const val PREFS = "atri_vc"
        private const val KEY_X = "float_x"
        private const val KEY_Y = "float_y"

        @Volatile
        var running = false
            private set

        /** 一键开关：设置页的「显示悬浮窗」用这个 */
        fun toggle(ctx: Context, on: Boolean) {
            val i = Intent(ctx, FloatingPlayService::class.java)
                .setAction(if (on) ACTION_SHOW else ACTION_HIDE)
            runCatching {
                if (on) ctx.startForegroundService(i) else ctx.startService(i)
            }.onFailure { L.w("FloatingPlayService 启动失败：${it.message}") }
        }

        /** 是否已授予悬浮窗权限（未授予时 startForegroundService 会被系统静默拒绝） */
        fun canDraw(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx)
            else true
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var ball: View? = null
    private var label: TextView? = null
    private var lp: WindowManager.LayoutParams? = null

    /** 播放中的动画任务（按钮上的呼吸感） */
    private var pulse: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removeBall()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground()
                if (ball == null) {
                    if (!canDraw(this)) {
                        L.w("FloatingPlayService 没有悬浮窗权限，无法显示")
                        Toast.makeText(this, "请先授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    addBall()
                }
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------ 前台通知

    /**
     * 起前台服务。
     *
     * ⚠️ Android 14 (API 34) 起 `startForeground()` 必须显式传服务类型，
     * 不传会抛 `MissingForegroundServiceTypeException`。
     * 这里用 mediaPlayback（我们确实会放音频）。
     */
    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, CH_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingPlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("变声悬浮窗已开启")
            .setContentText("点悬浮球播放待发语音；不用时点这里关闭")
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, "关闭悬浮窗", stop).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    // ------------------------------------------------------------ 悬浮球

    private fun addBall() {
        val w = wm ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }

        val size = (BALL_DP * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE61B2430.toInt())
                setStroke((2 * resources.displayMetrics.density).toInt(), 0xFF5CE1E6.toInt())
            }
            elevation = 8f
        }

        label = TextView(this).apply {
            text = "▶"
            setTextColor(0xFF5CE1E6.toInt())
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        container.addView(
            label,
            LinearLayout.LayoutParams(size, size, 1f)
        )

        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // 不抢焦点（否则 QQ 的「按住说话」收不到触摸）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            x = p.getInt(KEY_X, 24)
            y = p.getInt(KEY_Y, 320)
        }

        container.setOnTouchListener(DragTapListener(params, w))
        container.setOnClickListener { onBallTapped() }

        // 播放状态 → 按钮文字跟着变
        val stateCb: (VoiceSender.State) -> Unit = { st ->
            handler.post {
                val l = label ?: return@post
                when (st) {
                    VoiceSender.State.PLAYING -> {
                        l.text = "■"
                        startPulse()
                    }
                    else -> {
                        stopPulse()
                        l.text = "▶"
                    }
                }
            }
        }
        val finishCb: () -> Unit = {
            handler.post {
                stopPulse()
                label?.text = "▶"
            }
        }
        VoiceSender.onState = stateCb
        VoiceSender.onFinished = finishCb
        myStateCb = stateCb
        myFinishCb = finishCb
        VoiceSender.bind(this)

        runCatching { w.addView(container, params) }
            .onFailure {
                L.e("FloatingPlayService 添加悬浮球失败", it)
                Toast.makeText(this, "悬浮窗添加失败：${it.message}", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
        ball = container
        lp = params
        L.i("FloatingPlayService 悬浮球已显示 at (${params.x}, ${params.y})")
    }

    /**
     * 点一下悬浮球。**播放中再点 = 停止**，否则播一遍。
     *
     * ⚠️ 播放前先把自己的状态同步一次：`pendingFile` 可能是别的进程/页面刚更新的，
     * 而悬浮窗是常驻的，很容易显示旧值。
     */
    private fun onBallTapped() {
        if (VoiceSender.isPlaying) {
            VoiceSender.stop()
            stopPulse()
            label?.text = "▶"
            return
        }
        if (VoiceSender.pendingFile == null) {
            Toast.makeText(this, "还没有待发送语音，先去「合成」或「变声」页生成一条", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = VoiceSender.playNow(this)
        if (!ok) {
            Toast.makeText(this, "播放失败，看日志确认原因", Toast.LENGTH_SHORT).show()
            return
        }
        label?.text = "■"
        startPulse()
    }

    /** 播放中让按钮轻微呼吸，给一个"正在响"的视觉反馈 */
    private fun startPulse() {
        stopPulse()
        var up = true
        var i = 0
        lateinit var task: Runnable
        task = Runnable {
            val l = label ?: return@Runnable
            l.alpha = if (up) 0.45f else 1f
            up = !up
            i++
            if (i > 200) return@Runnable
            pulse = task
            handler.postDelayed(task, 420)
        }
        pulse = task
        handler.postDelayed(task, 420)
    }

    private fun stopPulse() {
        pulse?.let { handler.removeCallbacks(it) }
        pulse = null
        label?.alpha = 1f
    }

    /**
     * 「拖动 + 点击」二合一的手势监听。
     *
     * 为什么不用 `setOnClickListener` 单独接管点击：一旦在 `onTouch` 里消费了事件
     * （拖动需要），`OnClickListener` 就不会再被触发。所以自己判定 ——
     * 移动距离小于阈值（8dp）且时间短，就当成点击。
     */
    private inner class DragTapListener(
        private val params: WindowManager.LayoutParams,
        private val w: WindowManager,
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var downAt = 0L
        private var moved = false

        private val slopPx = (8 * resources.displayMetrics.density)

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    downAt = System.currentTimeMillis()
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slopPx || abs(dy) > slopPx) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { w.updateViewLayout(v, params) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val quick = System.currentTimeMillis() - downAt < 400
                    if (!moved && quick) {
                        onBallTapped()
                    } else {
                        // 位置落盘，下次还在原地
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    }
                    return true
                }
            }
            return false
        }
    }

    private var myStateCb: ((VoiceSender.State) -> Unit)? = null
    private var myFinishCb: (() -> Unit)? = null

    private fun removeBall() {
        stopPulse()
        // 只清掉自己装的那两个回调。
        // ⚠️ 不能直接写 `VoiceSender.onState = null`：那是**全局单槽**，
        // MainViewModel 也订阅了同一个字段，粗暴置空会让设置页的「正在播放…」永远不更新。
        if (myStateCb != null) { VoiceSender.onState = null; myStateCb = null }
        if (myFinishCb != null) { VoiceSender.onFinished = null; myFinishCb = null }
        ball?.let { b -> runCatching { wm?.removeView(b) } }
        ball = null
        label = null
        L.i("FloatingPlayService 悬浮球已移除")
    }

    override fun onDestroy() {
        running = false
        removeBall()
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onCreate() {
        super.onCreate()
        running = true
    }
}
